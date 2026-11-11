# Nuvio Companion

A standalone Android TV companion launcher, designed to work alongside an unmodified Nuvio installation or fork.

## Current starter scope

- Kotlin + Jetpack Compose project for Android TV / Google TV.
- Scans launchable applications exposed by Android's package manager.
- Displays installed app icons and names in a D-pad-friendly grid.
- Highlights applications whose label or package name contains "Nuvio".
- Includes an accessibility-service scaffold for researching remote-key interception.

This is an initial project foundation, not yet the complete overlay launcher. The first development milestone is validating remote-key delivery and overlay behavior on real Android TV hardware.

## Stack

- Kotlin
- Jetpack Compose / Material 3
- AndroidX TV Foundation and TV Material
- Gradle version catalog
- Android minSdk 24 / compileSdk 36 / Java 17
- Gradle 8.13

## Build locally

Use JDK 17 and Gradle 8.13:

```bash
gradle :app:assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## GitHub Actions

Active workflows are stored in `.github/workflows/`:

- **Build and Test** — builds the debug APK and runs unit tests on pushes, pull requests, or manual dispatch. GitHub Actions stores these as workflow artifacts, which GitHub packages as ZIP downloads.
- **Build Debug APK** — manually builds and publishes the APK as a direct `.apk` asset on a prerelease. Download the APK from the Releases page without extracting a ZIP.
- **Build Release Variant** — manually builds and publishes a prototype APK as a direct `.apk` asset on a prerelease. This currently uses the debug signing key and is not suitable for public distribution.

The `workflow-setup/` directory contains matching workflow copies for reference.

## Planned modules

1. App drawer groups, ordering, favorites and hidden apps.
2. Nuvio fork selection and saved launch target.
3. Icon-pack discovery and compatibility investigation.
4. Remote shortcut recording, key-combination engine and conflict detection.
5. Overlay window and focus/D-pad handling.
6. Device compatibility profiles and diagnostics.

## Android permission notes

The starter declares `QUERY_ALL_PACKAGES` because the app drawer's purpose is to show launchable apps. Google Play distribution has policy restrictions for broad package visibility; direct distribution/sideloading and Play distribution need separate review.

The accessibility service is not enabled automatically. The user must explicitly enable it in Android settings. The current service does not consume remote keys or open an overlay.
