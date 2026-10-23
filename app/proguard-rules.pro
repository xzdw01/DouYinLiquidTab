# The framework loads LiquidTabModule by name from META-INF/xposed/java_init.list.
-keep class io.github.xzdw.dylt.hook.** { *; }
-keep class io.github.xzdw.dylt.config.SettingsProvider { *; }
-keepattributes *Annotation*
# The libxposed API is provided by the framework at run time (compileOnly here).
-dontwarn io.github.libxposed.api.**
