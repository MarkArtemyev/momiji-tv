# Installation

## APKs

Use the two APKs attached to a [preview release](https://github.com/MarkArtemyev/momiji-tv/releases). Browser and Home work independently; installing both enables shared settings and profile-scoped native playback history. Android 8+ is the manifest minimum. The UI is currently Russian.

Allow the installer you use to install apps from its source, then install the APKs normally. Open Momiji Home from the apps list before changing a default launcher setting. Keep access to the original launcher and system settings during evaluation. Stock Google TV recommendations are not automatically reproduced by a third-party launcher.

## ADB

Use an explicit device serial so commands do not target another connected device:

```sh
adb devices
adb -s YOUR_DEVICE_SERIAL install -r momiji-home-0.4.0.apk
adb -s YOUR_DEVICE_SERIAL install -r momiji-browser-0.3.0.apk
adb -s YOUR_DEVICE_SERIAL shell am start -n app.momiji.home/.HomeActivity
```

Wireless debugging requires pairing/authorization through the device's developer settings. This repository does not include pairing credentials, device addresses or scripts that automatically connect to a television.

## Optional integrations

- **Home button:** select Momiji as the home app where supported. The optional Home accessibility helper exists for firmware that routes Home to the stock launcher; read its additional behavior in [Privacy](../PRIVACY.md) before enabling it.
- **Service cards:** system TV channels and Watch Next depend on the firmware, provider permissions and data exposed by installed services.
- **Media controls:** optional notification access exposes compatible active media sessions.
- **Keyboard/mouse:** standard Bluetooth devices pair in Android settings. The computer-browser controller is a separate local-network feature; it is not a Bluetooth HID driver.

## Updates and removal

Use APKs with the same signing certificate for updates. Self-built and CI APKs may have different certificates from the published preview. See [Signing](BUILDING.md#signing).

To remove Home, return to the stock launcher, disable the optional Home accessibility helper if enabled, and uninstall Momiji Home through Android settings. Browser is a separate package and can be removed independently.
