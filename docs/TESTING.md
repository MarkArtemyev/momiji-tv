# Testing and preview status

Current versions: Home **0.4.0**, Browser **0.3.0**. This is a preview, not a claim of validated performance on every Android TV device.

## Verified locally

| Area | Verification |
| --- | --- |
| Android builds | Debug and R8 release APKs for both apps |
| Static checks | `lintDebug` with zero errors; existing warnings remain |
| Filtering | PSL/private suffixes, host boundaries, resource types, party/domain restrictions, case matching, priority, exceptions and supported badfilter behavior |
| Filter loading | Compiled-rule roundtrip and offline bundled snapshots |
| Proxy | Real validated TLS, large duplex transfer, HTTP POST/Range, SOCKS5, connection bounds and shutdown |
| Certificate handling | Wrong-host certificate rejected |
| Named SNI | Native and WebView HTTPS through TCP mode with a validated certificate and matching SHA-256 for a 128 KiB ranged body |
| Web filtering | Ad script/image/fetch blocked; useful script and large body preserved; cosmetic block hidden |
| Exceptions | One resource restored by an exception; all fixture resources restored by a site exception |
| Companion link | Home HTTPS artwork, signature-protected settings exchange and unauthorized caller rejection |
| Native video | HTTPS playback through TCP, playback position advances, range bytes match the original |
| DNS | Optional Cloudflare and Google DoH were reachable in the local environment |
| Release UI | Clean app cards, Home-to-settings navigation and real HTTPS rendering in the minified browser |
| Release contents | Matching companion signatures; no local fixture CA, diagnostic activity or private key in release APKs |

Android checks used an API 35 x86_64 emulator at 1280×720. It used a phone system image with the landscape TV layout. Screenshots are emulator previews; timing and memory figures from that environment do not predict physical-TV performance.

## Run the portable core suite

```sh
./momiji-browser/gradlew -p tests/network-core networkCheck --max-workers=1
```

The 36-check suite starts local loopback fixtures and generates a short-lived PKCS12 certificate under its ignored build directory. It does not connect to a TV or use a production key. The pure-JVM suite covers filtering (list directives and platform hints, `$popup`, `$redirect`, page exceptions, `$replace`/`$hls`/`$jsonprune`), cosmetic and scriptlet compilation, HLS ad pruning, the browser's merge of page, custom and DNS-table decisions, and transport. It also compiles the bundled lists with the new engine and checks the heap they need (about 25 MiB on the JVM). It does not substitute for Android WebView/media checks or test public resolver reachability.

## Run the shield suite in Chromium

```sh
npm ci --prefix tests/web-shield
npx --prefix tests/web-shield playwright install chromium
node tests/web-shield/run.js
```

Run it after the core suite, which writes the real payloads the Kotlin core builds for some hosts. The suite installs exactly the document-start script the browser uses (AdGuard Scriptlets, ExtendedCss and `shield.js`) with a mock of the Kotlin bridge, and checks scriptlet timing, hiding (including iframes and strict CSP), HLS and `$jsonprune` rewriting for fetch and XHR players, popups, the AdGuard IMA stand-in, and that every scriptlet and raw JS rule in the bundled lists resolves and parses. Chromium is not Android WebView; WebView-specific behavior still needs a device.

After changing the bundled lists or the engine, rebuild the compiled filters with `./momiji-browser/gradlew -p tests/network-core compileProtection`, and the vendored AdGuard libraries with `tools/vendor-adguard.mjs`.

The Android scenarios in the table above were run with a local emulator harness that is not part of this repository. The only Android-side check in the tree is the debug-build store check: `adb shell am start -n app.momiji.home/.HomeActivity --ez self_check true` writes `store-self-check.json` to the app's files directory.

TLS-record fragmentation remains in the core suite for protocol regression coverage. Named Android WebView interoperability failed for that mode, so the product exposes only normal routing, TCP splitting and external SOCKS5. Legacy experimental TLS preferences migrate to TCP.

## Hardware checks still needed

- Cold start, focus/cursor behavior, remote mappings and text entry on real TV firmware.
- Frame pacing, artwork scrolling, low-RAM behavior, memory pressure and long-session thermals.
- Sleep/wake, launcher return, HDMI, service cards, notifications and gamepad handling.
- Website-specific login/DRM/stream compatibility and behavior after network changes.
- Full public-filter update memory/time on low-end TVs.
- Shield cost per frame on old WebView builds (the document-start script is about 650 KB), popup confirmation on real remotes, and HLS pruning with real SSAI streams.
- Provider-specific usefulness and overhead of TCP splitting, external proxies and DoH.

When reporting a failure, include app version, device model, Android and WebView versions, the interaction sequence and the observed result. Remove personal URLs, cookies, tokens and viewing history from logs and screenshots.
