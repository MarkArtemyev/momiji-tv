<div align="center">

<img src="docs/images/banner.png" width="240" alt="Momiji TV">

# Momiji TV

**Your apps. Your screen. Your way to watch.**

A customizable Android TV home screen and a remote-friendly browser, built around local profiles, native video playback and lightweight visual effects.

[![Build](https://github.com/MarkArtemyev/momiji-tv/actions/workflows/build.yml/badge.svg)](https://github.com/MarkArtemyev/momiji-tv/actions/workflows/build.yml)
![Android 8+](https://img.shields.io/badge/Android-8%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white)
[![License](https://img.shields.io/badge/License-GPL--3.0-blue)](LICENSE)
![Status](https://img.shields.io/badge/status-preview-e8a44d)

[Download preview](https://github.com/MarkArtemyev/momiji-tv/releases) · [Русский](README.ru.md) · [Build](docs/BUILDING.md) · [Controls](docs/CONTROLS.md) · [Roadmap](docs/ROADMAP.md)

</div>

<img src="docs/images/home.png" width="100%" alt="Momiji Home: centered app icons, local watch history, categories and a static landscape background">

*Release preview captured in an Android 15 emulator. Installed apps and available content depend on the device.*

## One home, two companion apps

| Component | What it does | Version |
| --- | --- | --- |
| **Momiji Home** | Native launcher with profiles, categories, custom artwork and local watch history | **0.4.0** |
| **Momiji Browser** | WebView browser with remote navigation, filtering and a Media3 video player | **0.3.0** |
| **Network core** | Shared filtering, TCP/SOCKS5 routing and optional encrypted DNS | Shared source |

Both apps can run independently. Install the pair to share network settings and resume native playback from Home. Companion access requires matching APK signatures.

## Make the home screen yours

- Arrange, rename and hide applications; build your own categories and favorites.
- Give each local profile its own layout, appearance, game shortcuts and playback history.
- Choose card sizes, grid density, accent colors, fonts, corners and custom backgrounds.
- Keep app cards clean with centered icons and optional labels. Content titles stay readable.
- Turn on falling maple leaves, fireflies or stars, with per-profile speed, density and color controls.
- Use a dedicated games view with gamepad detection and a shortcut to Bluetooth settings.
- Import and export layout settings without exporting history or account data.

Home uses Android Views and Canvas. Artwork loading is limited to visible cards, responses and caches are bounded, and background work is cancelled when the screen leaves the foreground. Low-RAM and reduced-motion settings are respected; atmospheric effects are off by default.

## Browse from the couch

- Switch between element focus and a free cursor for websites that need mouse-style navigation.
- Open direct supported video streams in a native Media3 player with seeking, subtitles, quality selection and playback statistics.
- Continue native playback through a profile-scoped Home history.
- Pair a computer browser over the local network for mouse, keyboard, text and playback controls.
- Keep the normal HttpEngine path for native playback when custom routing is off, using HTTP/2 or HTTP/3 when supported by the device and server.

DRM, opaque blob/MSE streams and some authenticated players still need the website's player. Local Home profiles do not switch website cookies or third-party service accounts.

## Network & protection

The shared settings screen includes offline domain filtering, contextual URL rules, cosmetic hiding, custom rules, site exceptions, popup controls and third-party cookie settings.

| Setting | Behavior |
| --- | --- |
| Default | Normal routing, system DNS, filtering enabled |
| TCP splitting | Changes the initial HTTPS send pattern without decrypting traffic |
| External SOCKS5 | Connects to a proxy you run yourself, including ByeDPI |
| DNS over HTTPS | Optional Cloudflare or Google resolver |
| Home coverage | Home's own artwork requests |
| Browser coverage | Website requests, supported background requests and native media requests |

No root access, interception certificate or Android VPN service is required for these network options. TCP splitting does not hide your IP or increase your connection's bandwidth. Effectiveness depends on the network; IP/protocol blocks, QUIC and server-side video ads are not universally handled. Other apps' traffic is outside the filter's scope.

<details>
<summary><strong>See network settings</strong></summary>

<img src="docs/images/network.png" width="100%" alt="Shared Momiji network settings with ad filtering, exceptions, TCP routing and DNS options">

</details>

## Try the preview

1. Download both APKs from [Releases](https://github.com/MarkArtemyev/momiji-tv/releases).
2. Install them using Android's package installer or the explicit-device ADB commands in [Installation](docs/INSTALLATION.md).
3. Open Momiji Home, then choose apps and appearance. Keep the original launcher available while evaluating the preview.
4. Open Browser and use **Menu** for navigation, controls and **Network & protection**.

The interface is currently Russian. Android 8+ is supported by the manifests; device-specific launcher behavior, remote keys, HDMI, channels, sleep/resume and long-session performance still need validation on physical televisions. The published preview uses the existing development signing certificate. See [signing and update compatibility](docs/BUILDING.md#signing).

## Build and verify

```sh
# JDK 17+, Android SDK 35
./momiji-browser/gradlew -p momiji-browser :app:assembleRelease :app:lintDebug --max-workers=1
./momiji-home/gradlew -p momiji-home :app:assembleRelease :app:lintDebug --max-workers=1

# Pure JVM network and filter integration tests; no TV required
./momiji-browser/gradlew -p tests/network-core networkCheck --max-workers=1
```

Keep all three source directories together. [Building](docs/BUILDING.md) covers Android Studio, Windows, SDK paths, memory limits and signing. [Testing](docs/TESTING.md) records verified scenarios and the remaining hardware checks.

## Project map

```text
momiji-home/           Native launcher, profiles, appearance and artwork
momiji-browser/        Browser, PC controls and native player
momiji-network-core/   Shared networking and filtering sources
tests/network-core/   Local TLS, proxy and filtering integration suite
docs/                 Setup, controls, privacy, testing and roadmap
```

## Privacy, contributions and licenses

Profiles, settings and watch history are stored locally. There is no Momiji account requirement. Websites, filter sources, selected DNS/proxy providers and optional voice services have their own network behavior; [Privacy](PRIVACY.md) explains the boundaries and optional permissions.

Bug reports with the TV model, Android/WebView versions and reproducible steps are welcome. See [Contributing](CONTRIBUTING.md) and [Security](SECURITY.md).

Momiji TV is distributed under [GPL-3.0](LICENSE). Bundled AdGuard lists retain their licenses and source snapshots; the Public Suffix List is MPL-2.0. Credits and exact upstream sources are in [Third-party notices](THIRD_PARTY_NOTICES.md).
