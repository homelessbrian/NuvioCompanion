# Nuvio Launch

Nuvio Launch is an Android TV companion launcher for Nuvio: one remote button opens your apps from anywhere, and Home can take you straight to Nuvio.

## What it does

- **First-run setup:** one question per page: your Nuvio app, remote access, your drawer button, press style, Home button (with a live Home test) and drawer style. Back always goes one step back.
- **One-button app drawer:** press your chosen remote button in any app to bring up your apps as a bottom dock, top bar, side rail or center grid. Hold OK on an app for favorite, hide and change icon.
- **Nuvio-first Home:** when Nuvio Launch is the Home app, Home opens your Nuvio app.
- **Never trapped in Nuvio:** backing out of Nuvio shows the Nuvio Launch home screen. If Nuvio closes right after opening twice in a row, Home pauses until you choose Open Nuvio. The drawer always ends with Android Settings and Nuvio Launch.
- **Icon packs:** pick any Nova/Lawnchair/ADW icon pack and every app matches it. Apps the pack doesn't include get a pack-style icon. Change one app's icon from any installed pack, with suggestions, categories and search.
- **Highlight color:** pick from Nuvio's own theme colors so the drawer matches Nuvio.
- **Settings:** remote shortcut (with a button test), Home button, drawer look, apps (show/hide, favorites, order, icons), icon pack, updates, help & recovery.
- **Built-in updates:** when a new release is out, a banner offers Download & install. Settings → Updates checks on demand.

## Setup

1. Install the APK and open Nuvio Launch. Setup starts by itself.
2. Follow the steps. Everything can be changed later in Settings.

## Device compatibility

Remote-control key delivery varies by Android TV firmware, remote model, and vendor key mapping. Android does not guarantee that every physical button can be intercepted by an accessibility service. Some buttons may be consumed by firmware or system components before an app receives them.

The current build is an implementation milestone, not a claim of device-wide compatibility. Validate shortcut delivery, overlay focus, and Home behavior on each target device before relying on it.

## Build

The project uses Gradle, Android Gradle Plugin, Kotlin, and Jetpack Compose. Builds are manual only: commits and pull requests do not start GitHub Actions builds. Use **Actions → Build and Test → Run workflow** for a compile/test run, or **Actions → Build Debug APK → Run workflow** to publish a test APK from the selected branch. The workflow dispatch ref is the source revision that gets built.

## Download (regular version, updates itself)

`https://github.com/homelessbrian/NuvioLaunch/releases/latest/download/NuvioLaunch.apk`

Releases are made with **Actions → Build Release → Run workflow** (enter a version number such as 0.3.0). It builds the full, faster version, signs it with the permanent Nuvio Launch key (repository secrets `NUVIO_KEYSTORE_BASE64` and `NUVIO_KEYSTORE_PASSWORD`) and posts it as a GitHub Release using `RELEASE_NOTES.md`. Installed copies see the new release and offer to update.

## Test (debug) APK download

The manually dispatched **Build Debug APK** workflow publishes the latest debug APK to the same path each time:

`https://raw.githubusercontent.com/homelessbrian/NuvioLaunch/main/downloads/NuvioLaunch.apk`

Checksum for the current APK:

`https://raw.githubusercontent.com/homelessbrian/NuvioLaunch/main/downloads/NuvioLaunch.apk.sha256`

The APK is committed to `main` by the manual workflow; normal commits do not trigger builds. Run **Actions → Build Debug APK → Run workflow** from the `main` branch.

## Debug APK signing

The debug workflow uses Android's standard debug signing automatically; no repository secrets or signing variables are needed for these test builds. GitHub-hosted runners generate their own debug key, so APKs from separate runs may not share the same signing identity. If Android refuses an in-place update, uninstall the previous debug build and install the newly downloaded APK. We can set up a persistent debug key later if you want seamless in-place updates.
