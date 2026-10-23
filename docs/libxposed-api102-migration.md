# Migrating LiquidTab to the libxposed API 102

Status: **complete** (2026-10-16), and the module is now libxposed-only. Stages 1–4 were
implemented and each verified on the connected device; afterwards the legacy entry, the
`de.robv` backend and the `xposed-stubs` compile-only module were deleted on request, so the
shipped dex contains no legacy API reference. Written from the activation-detection
investigation (internal notes, not published with this source).

## Progress

| Stage | State | Evidence |
|---|---|---|
| 0 — service dependency + activation card | done | card reads framework/scope/processes |
| 1 — `hookx` facade + `LegacyBackend`, all call sites moved | done | Douyin glass bar rendered by the legacy backend build |
| 2 — `ModernBackend` + modern entry + metadata/build | done | Douyin glass bar rendered through `io.github.libxposed.api`; card reports `LSPosed 2.2.1 · 服务 API 102`, `作用域：抖音`, both hooked processes |
| 3 — config on remote preferences, drop the 1 s poll | done | settings mirror into the daemon's store (21 keys verified in `module_configs`); Douyin logged `framework preference change seen` + `framework push ... liquid=true`; **0** provider reads in a 30 s foreground window (was ~30) |
| 4 — unhook one-shot hooks, docs, full regression | done | `DouyinControlMarginGuard`'s global `View.requestLayout` hook now bails out on a volatile read instead of taking a lock and probing a `WeakHashMap`; top-bar glass and the publish-button toggle verified live in Douyin without a restart |
| 5 — remove the legacy layer (after completion) | done | `assets/xposed_init`, `hook/HookEntry.kt`, `hookx/LegacyBackend.kt`, the `HookEntry` keep rule and the whole `xposed-stubs` module deleted; the release dex contains 0 references to `de/robv`, `XposedBridge` or `XSharedPreferences`, and Douyin still installs its hooks and receives the configuration push |

Facts learned while doing 1–2 (both cost a full debug cycle, see the internal notes):
`deoptimize(executable)` is mandatory before `hook()` on an inlined method, and the modern
path has no `XSharedPreferences`/`XposedBridge`, so any stray reference to them inside a
*hook body* fails the surrounding hook — even when wrapped in `runCatching` further out.

## Deviation from the original stage 4

The plan was to unhook one-shot hooks through `HookHandle`. Measurement says that is not
where the cost is: `Application.attach` fires a dozen times per process and each extra
invocation is a boolean check, while the one genuinely hot hook was the adapter's global
`View.requestLayout` observer, which paid a monitor lock plus a `WeakHashMap` lookup on every
layout request in the app. That hook now exits on a `@Volatile` count before touching the lock,
and the one-shot hooks stay installed. If hook *count* ever matters again, the handle is
available on both backends (`XposedBridge.hookAllMethods` returns `Unhook`s; the modern
`HookBuilder.intercept` returns a `HookHandle`).

## Why this migration is needed

On LSPosed 2.2.1 the framework only hands a module the libxposed module service — the source the
manager UI's activation card reads — to modules it treats as *modern*, i.e. an APK that carries
`META-INF/xposed/java_init.list`. Measured on the device:

- Adding that file and an `io.github.libxposed.api.XposedModule` entry made the framework call the
  module's `XposedProvider` with `SendBinder`; the card then reported
  `LSPosed 2.2.1 · 服务 API 102`, `作用域：抖音` and the live hooked processes.
- The same build stopped hooking Douyin:
  `java.lang.NoClassDefFoundError: Failed resolution of: Lde/robv/android/xposed/XC_MethodHook;`

A modern module receives `io.github.libxposed.api` but **not** the legacy
`de.robv.android.xposed` classes, and a legacy module never receives the service. The module must
therefore move its hook and configuration layer to the libxposed API before the modern entry can
be shipped.

## What has to change: measured surface

The legacy API surface is small and regular — six shapes, no exotic helpers:

| Legacy symbol | Occurrences | Files |
|---|---|---|
| `XposedBridge.log` | 41 | 9 |
| `XposedHelpers.callMethod` | 34 | 6 |
| `XC_MethodHook` (15 anonymous hooks) | 25 | 7 |
| `MethodHookParam` | 21 | 5 |
| `XposedBridge.hookAllMethods` | 15 | 5 |
| `XposedBridge.hookAllConstructors` | 1 | 1 |
| `XposedHelpers.findClass` | 5 | 3 |
| `XSharedPreferences` | 11 | 2 |
| `IXposedHookLoadPackage` / `XC_LoadPackage` (entry) | 5 | 3 |

Not used anywhere: `findAndHookMethod`, `callStaticMethod`, field helpers, `newInstance`,
throwable accessors, `getResult`. Callback features used are exactly three:

- `param.setResult(v)` to skip the original: 3 sites (`HookCoordinator`, `HookInstaller`,
  `DouyinPlayerTextureHooks`);
- one argument mutation: `p.args[1] = false` in `DouyinPlayerTextureHooks`;
- everything else only reads `thisObject` / `args`.

Per file: `DouyinPlayerTextureHooks.kt` (42), `HookInstaller.kt` (37), `GlassHostLayout.kt` (30),
`GlassInstaller.kt` (22), `HookConfigReader.kt` (11), then six files with 1–7 each.

Mapping to API 102:

| Legacy | API 102 |
|---|---|
| `IXposedHookLoadPackage.handleLoadPackage` | `XposedModule.onModuleLoaded` + `onPackageReady` (already prototyped) |
| `hookAllMethods` / `hookAllConstructors` | iterate `getDeclaredMethods()`/`getDeclaredConstructors()` + `XposedInterface.hook(executable).intercept(hooker)` |
| `XC_MethodHook` before/after | `Hooker.intercept(Chain)`: run "before", call `chain.proceed()` (or skip and return the result), then run "after" |
| `param.setResult(v)` | return `v` without calling `proceed()` |
| `p.args[i] = v` | `chain.proceed(newArgs)` (the argument list from `getArgs()` is a snapshot) |
| `XposedHelpers.callMethod` | own reflection helper, or `XposedInterface.getInvoker(method).invoke(...)` |
| `XposedHelpers.findClass` | `Class.forName(name, false, classLoader)` |
| `XposedBridge.log` | `XposedInterface.log(priority, tag, msg)` |
| `XSharedPreferences` | `XposedInterface.getRemotePreferences(group)` for the hooked process, `XposedService.getRemotePreferences(group)` for the manager UI |
| — | `XposedInterface.deoptimize(executable)` may be needed where the legacy bridge used to deoptimize implicitly |

## Shape that was used: one facade, then one backend

The migration kept `assets/xposed_init` working while the hook layer moved, so the module could
be verified on the device at every step:

1. `hookx/` facade with the same shapes the code already used (`Xposed.log`, `Xposed.findClass`,
   `Xposed.callMethod`, `Xposed.hookAllMethods`, `Xposed.hookAllConstructors`, plus `MethodHook`
   and `MethodHookParam` reproducing the legacy before/after + `setResult` lifecycle). Call sites
   kept their text; only imports changed.
2. `LegacyBackend` implemented the facade over `de.robv.*` and `ModernBackend` over
   `XposedInterface`, in separate classes so neither process loaded a class whose signature
   referenced the other generation's types.
3. `HookEntry` activated the legacy backend, `LiquidTabModule` the modern one; `HookInstaller` /
   `HookLoadContext` stayed the single install path.
4. `META-INF/xposed/java_init.list` + `META-INF/xposed/module.prop` + `compileOnly
   io.github.libxposed:api:102.0.0` + R8 keep for the entry class.

Once the modern path was verified, the legacy half was deleted (stage 5): the facade now holds
the libxposed implementation directly, `assets/xposed_init`, `HookEntry`, `LegacyBackend` and the
whole `xposed-stubs` compile-only module are gone, and `HookConfigReader` reads only the
framework's remote preferences.

## Stages and gates

| Stage | Work | Size | Gate on the device |
|---|---|---|---|
| 1 | Facade + `LegacyBackend`; rewrite the 7 hook files to the facade (behaviour must not change) | ~3 files touched, ~350 lines | Douyin hook install lines unchanged; bottom bar, video texture, top bar, back-peek behave as today |
| 2 | `ModernBackend` + `LiquidTabModule` + `java_init.list`/`module.prop` + build/proguard | ~2 files, ~250 lines | Activation card reports LSPosed/service/scope/processes; every hook verified again (this is where semantics differ: priority, exception mode, skip, deoptimize) |
| 3 | Config channel: write-through to remote preferences in `LocalSettings`, read them in `HookConfigReader` instead of `XSharedPreferences`; keep the existing provider/broadcast path as the primary channel | 2 files, ~120 lines | Settings change while Douyin runs; provider-unavailable fallback still serves a config; one-time migration of existing prefs |
| 4 | Cleanup and docs: `xposed-stubs` scope, README "运行条件" (LSPosed ≥ 1.9, modern entry), internal notes | docs + build files | Release build + full Douyin pass (40.7.0) |

Rough total: 12–14 modified files, 2–3 new files, ~500–800 changed lines, 4 stages; each stage is
independently verifiable and the current signed APK can be reinstalled as a rollback.

## Risks and mitigations

- **Hook semantics differ.** Priority defaults, exception mode and the before/after split are not
  identical. Mitigation: the facade reproduces the legacy lifecycle exactly, then each of the 15
  hooks is verified on the device rather than assumed.
- **`deoptimize`.** The legacy bridge deoptimized some targets implicitly; the new API expects
  `deoptimize(executable)` where ART may have inlined. Symptom: a hook silently never fires.
  Mitigation: deoptimize the specific executables this module hooks.
- **Configuration.** `XSharedPreferences` is gone. The manager UI must mirror writes into remote
  preferences, and existing settings need a one-time copy; the provider/broadcast channel stays
  primary, so a framework without `PROP_CAP_REMOTE` only loses the fallback.
- **Compatibility.** The dual-entry plan preserved frameworks older than `java_init.list`; the
  module now requires one that understands it (LSPosed ≥ 1.9). That was an explicit decision
  after the modern path was verified — the legacy half bought nothing on the target device.
- **Regression surface.** The glass bottom bar, video/texture path, immersive/standby handling,
  top bar, back-peek and live config sync all run through the rewritten layer, so the Douyin
  version in use (40.7.0, code 40700001) must be re-checked end to end.
