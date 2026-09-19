# R8/ProGuard rules for release builds. See app/build.gradle.kts (buildTypes.release).

# LlamaEngine's native methods are resolved by exact name at JNI link time against the symbols
# compiled into libhumanrewrite.so (Java_com_humanrewrite_keyboard_core_LlamaEngine_nativeLoad,
# etc.) — if R8 renamed them, the app would crash with UnsatisfiedLinkError on first rewrite in
# every release build, a bug that would never show up in debug (unminified) testing.
# proguard-android-optimize.txt already keeps all native methods by default; this rule is kept
# here too so the requirement is visible in-repo rather than only implicit in the default file.
-keepclasseswithmembernames class * {
    native <methods>;
}
