# Nuvio Companion

A standalone Android TV companion launcher that provides a configurable app drawer over other apps.

## Features in this development build

- Discovers installed launchable Android TV and Android apps.
- Drawer layouts: Bottom, Top, Left, Right and Center.
- Per-app visibility controls.
- Remote shortcut recording through an explicitly enabled accessibility service.
- Request to set Nuvio Companion as the Android TV Home launcher.
- Overlay drawer with D-pad-friendly app tiles.

Remote-key delivery varies by Android TV device and remote. A key can only be recorded if Android exposes it to the accessibility service. Test shortcut behavior on each target device.

## Stack

Kotlin, Jetpack Compose, Material 3, AndroidX TV libraries, Gradle 8.13, Java 17.

## Build

```bash
gradle :app:assembleDebug
```

The release workflow currently signs prototype builds with the debug key and is not suitable for public distribution.
