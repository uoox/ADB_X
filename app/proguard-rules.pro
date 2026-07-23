# libxposed (modern Xposed API)
-dontwarn io.github.libxposed.annotation.**
-keep class io.github.libxposed.api.** { *; }

# Keep module entry classes and rewrite the java_init.list manifest if they
# ever get obfuscated. allowoptimization/allowobfuscation mirror the upstream
# recommendation so R8 can still optimize the rest of the module.
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-keep class top.cbug.adbx.xposed.** { *; }
