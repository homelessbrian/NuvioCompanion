# Release build rules for Nuvio Launch.

# Keep class and method names readable, so crash reports from the release app make sense.
# Shrinking and optimizing (the part that makes the app faster) still run.
-dontobfuscate

# The remote-button service is started by Android by name.
-keep class com.homelessbrian.nuviocompanion.remote.RemoteKeyAccessibilityService { *; }

# Settings are saved by enum name; keep enum names and lookups intact.
-keepclassmembers enum com.homelessbrian.nuviocompanion.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    **[] $VALUES;
    public *;
}
