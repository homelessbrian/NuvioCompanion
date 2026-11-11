# Nuvio Companion

A standalone Android TV companion launcher. It is designed to work alongside an unmodified Nuvio installation or fork.

## Current starter-kit scope

- Android TV / Google TV-friendly Kotlin + Jetpack Compose project.
- Scans launchable applications exposed by Android's package manager.
- Shows installed app icons and names in a D-pad-friendly grid.
- Highlights applications whose label or package name contains "Nuvio".
- Includes an accessibility-service scaffold for researching remote key interception.

This is an initial project foundation, **not yet the complete overlay launcher**. The first development milestone is validating remote key delivery and overlay behavior on real Android TV hardware.

## Stack

- Kotlin
- Jetpack Compose / Material 3
- AndroidX TV Foundation and TV Material dependencies
- Gradle version catalog
- Android minSdk 24 / compileSdk 36 / Java 17

## Build locally

Open the repository in Android Studio (a current version with Android SDK 36 support), allow Gradle sync, then run the `app` configuration.

With Gradle 8.13.2 and JDK 17 installed:

```bash
gradle :app:assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## GitHub Actions

The `workflow-setup` folder contains the initial workflow files:

- `apply-kit.yml`: watches for `nuviocompanion-kit.zip`, extracts it into the repository root, commits the changes and removes the uploaded ZIP.
- `build-ci.yml`: builds and tests on pushes and pull requests.
- `build-debug.yml`: manually builds a debug APK from the Actions tab.
- `build-release.yml`: manually builds a release-variant APK from the Actions tab.

### One-time workflow installation

GitHub does not allow a workflow run using the default `GITHUB_TOKEN` to create or update workflow files and then use that same token to trigger more workflows. Therefore:

1. Create an empty GitHub repository for this project.
2. Upload the contents of this kit ZIP to the repository root, or extract it locally and push the files.
3. Copy the YAML files from `workflow-setup/` into `.github/workflows/` in the repository.
4. Commit those workflow files.
5. For future updates, upload `nuviocompanion-kit.zip` to the repository root. `apply-kit.yml` will extract it into the correct paths, commit the update and remove the ZIP.
6. Run `Build Debug APK` or `Build Release APK` from the Actions tab when you want an APK.

The kit installer intentionally excludes `.github/` from extraction, but it does extract `workflow-setup/` into the repository so the YAML files remain available for one-time installation. This protects active workflows from accidental replacement and follows the workflow used in the Nuvio + IPTV repository.

## Planned modules

1. App drawer groups, ordering, favorites and hidden apps.
2. Nuvio fork selection and saved launch target.
3. Icon-pack discovery, Projectivy/Blueprint compatibility investigation and per-app custom icons.
4. Remote shortcut recording, key-combination engine and conflict detection.
5. Overlay window and focus/D-pad handling.
6. Device compatibility profiles and diagnostics.

## Android permission notes

The starter declares `QUERY_ALL_PACKAGES` because the app drawer's purpose is to show launchable apps. Google Play distribution has policy restrictions for broad package visibility; direct distribution/sideloading and Play distribution need separate review.

The accessibility service is not enabled automatically. The user must explicitly enable it in Android settings. The current service does not consume remote keys or open an overlay.
