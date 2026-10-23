# Douyin DEX tooling

Helpers for re-locating obfuscated Douyin classes after an app update. They exist because
Douyin re-maps obfuscated names between builds — including builds that keep the same
`versionName` — and a broken lookup fails *silently*: the hook logs one line and falls
back to a slower path, which reads as "the feature just stopped working".

See the internal development notes (not published with this source) for the full procedure.

## The problem these solve

`DouyinPlayerTextureHooks` forces the feed's video output to a `TextureView`
(`KeepSurfaceTextureView`) so the glass layer can sample it on the GPU. Otherwise the feed
is a `SurfaceView` — its own hardware layer, unsampleable — and the glass has to fall back
to a PixelCopy readback that lags the video by a frame or two.

That hook has to name a class that Douyin obfuscates:

| Build | Factory class |
|---|---|
| 40.6.0 | `X.0wMt` |
| 40.7.0 | `X.1KBI` |
| 2026-10-17 update | `X.0GO1` |

Note that the last one kept `versionName = 40.7.0`. Version numbers cannot be used to
decide whether the hook still fits.

## Anchors

Obfuscation renames classes, methods and most fields, but **not the string literals
Douyin's own source declares**. These four boolean switches sit on the factory class and
have survived every build so far:

```
sIsEnableSurfaceviewForGeneralDevice
sIsEnableSurfaceviewForGeneralDevicesFetched
sIsSurfaceViewEnabledWhenInOppoBlackList
sIsSurfaceViewEnabledWhenInOppoBlackListFetched
```

The factory also keeps its shape: a `LJIILIIL(Landroid/view/ViewGroup;Z)` method whose
return type is its own class, a `wrapper` field holding the view wrapper, and the
`LJIJJ` / `LJJI` / `LJJIIJZLJL` decision methods.

## Usage

```bash
# 1. unzip the installed APK's dex files (on device or after pulling it)
adb shell su -c "unzip -o <base.apk path> 'classes*.dex' -d /data/local/tmp/dydex"

# 2. pull the dex that carries the anchors (find it first if unsure)
adb shell su -c "grep -l sIsEnableSurfaceviewForGeneralDevice /data/local/tmp/dydex/classes*.dex"
adb pull /data/local/tmp/dydex/classes2.dex .

# 3. locate the factory
python find_factory.py classes2.dex
```

Output:

```
class    : X.0GO1   (classes2.dex)
anchors  : sIsEnableSurfaceviewForGeneralDevice, ...
factory  : LJIILIIL(Landroid/view/ViewGroup;Z)LX/0GO1;  OK
switches : LJIJJ, LJJI, LJJIIJZLJL

Add to the candidate list in DouyinPlayerTextureHooks.kt, newest first:
    listOf("X.0GO1", ...)
```

Then edit `DouyinPlayerTextureHooks.install()`:

```kotlin
val factory = listOf("X.0GO1", "X.1KBI", "X.0wMt").firstNotNullOfOrNull { name -> ... }
```

The lookup there resolves **by capability**, not by name: a candidate is only accepted if
it really declares `LJIILIIL(ViewGroup, boolean)`. That matters because obfuscated names
are *recycled* — in the 2026-10-17 build `X.1KBI` still existed, but as an unrelated class,
which is exactly how the previous version got misled.

`dexlib.py` can also be used directly for other lookups:

```bash
python dexlib.py <dex> classes <substr>   # find class descriptors
python dexlib.py <dex> fields  <class>    # fields of a class
python dexlib.py <dex> methods <class>    # methods of a class
python dexlib.py <dex> bytype  <type>     # classes owning a field of that type
python dexlib.py <dex> sig     <substr>   # methods whose signature contains substr
```

## Reading the result

- **`factory : ... OK`** — this is the class. Add it to the candidate list and rebuild.
- **`factory : !! ... NOT found on this class`** — the anchors and the factory have been
  split across two classes. Douyin restructured the player; find the class that declares
  `LJIILIIL(ViewGroup, boolean)` returning its own type.
- **`No class declares any anchor field`** — the anchors themselves are gone. Fall back to
  the structural fingerprints, in this order:
  1. a class referenced near `com/ss/android/ugc/playerkit/videoview/KeepSurfaceTextureView`
  2. a `(ViewGroup, boolean)` static method whose return type is its own class
  3. the `wrapper` field + `getView()` pair

## Caveats

- `find_factory.py` reports the `wrapper` field only when the field table parses cleanly for
  that class; on some classes it is omitted. The class name itself is unaffected — that is
  what the tool is for.
- `dexlib.py` is a partial reader: it decodes the class/field/method tables and nothing
  else (no bytecode, no annotations). That is deliberate — it is ~1000x faster than
  `dexdump -d`, which takes minutes per dex and is only needed when you have to read
  control flow.
- These scripts are development-only. They never ship in the APK.
