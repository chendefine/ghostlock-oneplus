# Shizuku binds EngineUserService by component name and talks over the
# generated AIDL stubs; R8 must keep both sides in release builds.
-keep class com.ghostlock.pad.shizuku.EngineUserService { *; }
-keep interface com.ghostlock.pad.shizuku.IEngineService { *; }
-keep interface com.ghostlock.pad.shizuku.IEngineCallback { *; }
-keep class com.ghostlock.pad.shizuku.IEngineService$Stub { *; }
-keep class com.ghostlock.pad.shizuku.IEngineCallback$Stub { *; }
-keep class dev.rikka.shizuku.** { *; }
