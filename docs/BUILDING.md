# Building Momiji TV

## Requirements

- JDK 17 or newer; the local Android builds were verified with the Android Studio JBR.
- Android SDK Platform 35 and Build Tools 35.
- Internet access for the first Gradle dependency resolution.

The wrapper pins Gradle 8.11.1. Both Android projects use AGP 8.7.3 and Kotlin 2.0.21, target API 35 and support API 26+. Keep `momiji-home`, `momiji-browser` and `momiji-network-core` as siblings: both apps compile the shared sources directly.

## Android Studio

Open `momiji-browser` or `momiji-home` as a project. Set the Gradle JDK to 17+ and let Studio write a local SDK path. `local.properties` is intentionally ignored. Open the other app separately when needed.

## Command line

Set `ANDROID_HOME` to your SDK directory, or create each app's untracked `local.properties` with its `sdk.dir`.

From the repository root on Linux/macOS:

```sh
./momiji-browser/gradlew -p momiji-browser :app:assembleDebug :app:assembleRelease :app:lintDebug --max-workers=1
./momiji-home/gradlew -p momiji-home :app:assembleDebug :app:assembleRelease :app:lintDebug --max-workers=1
./momiji-browser/gradlew -p tests/network-core networkCheck --max-workers=1
```

From PowerShell:

```powershell
.\momiji-browser\gradlew.bat -p momiji-browser :app:assembleDebug :app:assembleRelease :app:lintDebug --max-workers=1
.\momiji-home\gradlew.bat -p momiji-home :app:assembleDebug :app:assembleRelease :app:lintDebug --max-workers=1
.\momiji-browser\gradlew.bat -p tests/network-core networkCheck --max-workers=1
```

On a memory-constrained development machine, build the apps sequentially. Add `"-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8"` and `"-Pkotlin.compiler.execution.strategy=in-process"` as whole quoted arguments. Running R8 and an emulator together can significantly increase memory use.

Outputs are under each project's `app/build/outputs/apk/`. Release builds enable R8 and resource shrinking. Lint reports are under `app/build/reports/`.

## Signing

The current preview release configuration uses Gradle's developer debug signing configuration. No private signing key is included in the repository. Both apps must use the same certificate to exchange history and network settings.

A fresh environment creates its own debug key. Its APKs will not update a published APK signed with a different key. CI artifacts also use the runner's temporary development key; they are build verification artifacts rather than updates to the published preview.

For a maintained distribution, configure a persistent release signing key for **both** projects and keep its keystore and passwords outside version control. Android requires matching signatures for in-place updates; uninstalling to change certificates removes private app data. Back up any layout settings you need before changing installations.

## Shared data

Filter snapshots and precompiled data are bundled for offline startup. Their source URLs and hashes are recorded in `app/src/main/assets/protection/manifest.json`. Android packaging removes the `.gz` suffix and exposes decompressed asset bytes; runtime asset names intentionally omit `.gz`. Do not rename them without updating the loaders.
