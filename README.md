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

## Stable APK download

The manually dispatched **Build Debug APK** workflow publishes the latest debug APK to the same path each time:

`https://raw.githubusercontent.com/homelessbrian/NuvioCompanion/main/downloads/NuvioCompanion.apk`

Checksum for the current APK:

`https://raw.githubusercontent.com/homelessbrian/NuvioCompanion/main/downloads/NuvioCompanion.apk.sha256`

The APK is committed to `main` by the manual workflow; normal commits do not trigger builds. Run **Actions → Build Debug APK → Run workflow** from the `main` branch.

## Persistent debug signing (required for in-place updates)

The debug APK workflow requires a persistent Android signing key stored as GitHub Actions repository secrets. Never commit the keystore or its passwords to this repository.

Create a keystore on a trusted computer with Java's `keytool` (choose and securely save your own passwords and alias):

```bash
keytool -genkeypair -v -keystore nuviocompanion-debug.jks -alias nuviocompanion -keyalg RSA -keysize 2048 -validity 10000
```

Convert the keystore to one-line Base64 text. On Windows PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes(".\\nuviocompanion-debug.jks")) | Set-Clipboard
```

In **Settings → Secrets and variables → Actions → New repository secret**, add:

- `COMPANION_SIGNING_KEYSTORE_BASE64`: the Base64 text copied above
- `COMPANION_SIGNING_STORE_PASSWORD`: the keystore password
- `COMPANION_SIGNING_KEY_ALIAS`: `nuviocompanion` (or the alias you chose)
- `COMPANION_SIGNING_KEY_PASSWORD`: the key password

Keep a secure backup of the keystore and passwords. Losing them means future APKs cannot update installations signed with that key. The debug workflow intentionally fails until all four secrets are configured.

The debug APK has application ID `com.homelessbrian.nuviocompanion.debug`; it updates the debug-flavor installation, not a release-flavor installation. Its version code increments with each Actions run.
