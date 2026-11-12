# Nuvio Companion

Nuvio Companion is an Android TV companion launcher with a configurable, system-wide app drawer.

## Current direction

- **Nuvio-first Home behavior:** when selected as the device Home app, Companion launches the configured Nuvio installation when Home is pressed.
- **Universal app library:** discovers installed apps that expose Android TV or standard launcher activities.
- **Five drawer placements:** Bottom, Top, Left, Right, and Center.
- **App controls:** hide apps and mark favorites; favorites appear first.
- **Remote shortcut:** accessibility-service key filtering is used to receive supported remote key events.

## Setup

1. Install the APK.
2. Open Nuvio Companion and choose the Nuvio installation to launch from Home.
3. Select **Set as device launcher** and choose Nuvio Companion as Home.
4. Enable **Nuvio Companion Remote Shortcuts** in Android Accessibility settings.
5. Return to Companion, record a remote button, and choose the shortcut gesture.

## Device compatibility

Remote-control key delivery varies by Android TV firmware, remote model, and vendor key mapping. Android does not guarantee that every physical button can be intercepted by an accessibility service. Some buttons may be consumed by firmware or system components before an app receives them.

The current build is an implementation milestone, not a claim of device-wide compatibility. Validate shortcut delivery, overlay focus, and Home behavior on each target device before relying on it.

## Build

The project uses Gradle, Android Gradle Plugin, Kotlin, and Jetpack Compose. Builds are manual only: commits and pull requests do not start GitHub Actions builds. Use **Actions → Build and Test → Run workflow** for a compile/test run, or **Actions → Build Debug APK → Run workflow** to publish a test APK from the selected branch. The workflow dispatch ref is the source revision that gets built.

## Release signing

The current release variant is prototype-only and uses the debug signing configuration. A production release must use a private signing key managed outside the repository.
