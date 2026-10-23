# Third-party software notices

LiquidTab's own source code is distributed under GNU GPL v3; see `LICENSE`.
The following direct dependencies are distributed under Apache License 2.0:

| Component | Use | License |
|---|---|---|
| AndroidX Activity Compose and Jetpack Compose (Animation, Foundation, Material, Material 3, UI, Tooling Preview) | Module UI and animation | Apache-2.0 |
| AndroidX Navigation 3 Runtime/UI and Navigation Event Compose | Module secondary-page navigation | Apache-2.0 |
| AndroidX Graphics Shapes | Smoothed corner geometry | Apache-2.0 |
| Miuix Compose UI, Icons, Preference, Blur, Squircle and Navigation 3 | MIUIX-style module UI and shared effects | Apache-2.0 |
| LSPosed AndroidHiddenApiBypass 6.1 | Access to Android hidden APIs where required | Apache-2.0 |
| libxposed service API (`io.github.libxposed:service`) | Framework module service used for activation detection and remote preferences | Apache-2.0 |
| libxposed module API (`io.github.libxposed:api`, compile-only) | Modern module entry (`META-INF/xposed/java_init.list`) | Apache-2.0 |

The Apache License 2.0 text is included at `licenses/Apache-2.0.txt`.

The libxposed module API is compile-only: it is provided at runtime by the
user's LSPosed installation, which is not bundled. The module ships no legacy
`de.robv.android.xposed` declarations or calls.

Dependency versions are declared in `app/build.gradle.kts`. Their upstream
license texts and notices remain authoritative. No dependency source tree or
binary archive is redistributed in this repository.
