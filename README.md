# Nuvio Companion

Nuvio Companion is an Android TV companion launcher with a configurable, system-wide app drawer.

## Current direction

- **Nuvio-first Home behavior:** when selected as the device Home app, pressing Home opens the configured Nuvio installation.
- **Never trapped in Nuvio:** backing out of Nuvio (or Nuvio closing) shows the Companion home screen instead of reopening Nuvio. It always offers Open Nuvio, Android Settings, Companion Settings and Stop using as Home. If Nuvio closes within a few seconds of opening twice in a row, Home pauses until you choose Open Nuvio. The app drawer always ends with Android Settings and Companion Settings tiles.
- **Universal app library:** discovers installed apps that expose Android TV or standard launcher activities.
- **Five drawer placements:** Bottom, Top, Left, Right, and Center.
- **App controls:** hide apps and mark favorites; favorites appear first.
- **Remote shortcut:** accessibility-service key filtering is used to receive supported remote key events.

## Setup

1. Install the APK.
2. Open Nuvio Companion and choose the Nuvio installation to launch from Home.
3. Optional: select **Choose Home app** and pick Nuvio Companion. On Google TV the stock launcher may keep the Home button even after this.
4. Enable **Nuvio Companion Remote Shortcuts** in Android Accessibility settings.
5. Return to Companion, record a remote button, and choose the shortcut gesture.

## Device compatibility

Remote-control key delivery varies by Android TV firmware, remote model, and vendor key mapping. Android does not guarantee that every physical button can be intercepted by an accessibility service. Some buttons may be consumed by firmware or system components before an app receives them.

The current build is an implementation milestone, not a claim of device-wide compatibility. Validate shortcut delivery, overlay focus, and Home behavior on each target device before relying on it.

## Build

The project uses Gradle, Android Gradle Plugin, Kotlin, and Jetpack Compose. Builds are manual only: commits and pull requests do not start GitHub Actions builds. Use **Actions → Build and Test → Run workflow** for a compile/test run, or **Actions → Build Debug APK → Run workflow** to publish a test APK from the selected branch. The workflow dispatch ref is the source revision that gets built.

## Stable APK download

The manually dispatched **Build Debug APK** workflow publishes the latest debug APK to the same path each time:

`https://raw.githubusercontent.com/homelessbrian/NuvioCompanion/main/downloads/NuvioCompanion.apk`

Checksum for the current APK:

`https://raw.githubusercontent.com/homelessbrian/NuvioCompanion/main/downloads/NuvioCompanion.apk.sha256`

The APK is committed to `main` by the manual workflow; normal commits do not trigger builds. Run **Actions → Build Debug APK → Run workflow** from the `main` branch.

## Debug APK signing

The debug workflow uses Android's standard debug signing automatically; no repository secrets or signing variables are needed for these test builds. GitHub-hosted runners generate their own debug key, so APKs from separate runs may not share the same signing identity. If Android refuses an in-place update, uninstall the previous debug build and install the newly downloaded APK. We can set up a persistent debug key later if you want seamless in-place updates.
