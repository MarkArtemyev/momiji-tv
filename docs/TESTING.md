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

The 18-check suite starts local loopback fixtures and generates a short-lived PKCS12 certificate under its ignored build directory. It does not connect to a TV or use a production key. The pure-JVM suite covers filtering and transport; it does not substitute for Android WebView/media checks or test public resolver reachability.

TLS-record fragmentation remains in the core suite for protocol regression coverage. Named Android WebView interoperability failed for that mode, so the product exposes only normal routing, TCP splitting and external SOCKS5. Legacy experimental TLS preferences migrate to TCP.

## Hardware checks still needed

- Cold start, focus/cursor behavior, remote mappings and text entry on real TV firmware.
- Frame pacing, artwork scrolling, low-RAM behavior, memory pressure and long-session thermals.
- Sleep/wake, launcher return, HDMI, service cards, notifications and gamepad handling.
- Website-specific login/DRM/stream compatibility and behavior after network changes.
- Full public-filter update memory/time on low-end TVs.
- Provider-specific usefulness and overhead of TCP splitting, external proxies and DoH.

When reporting a failure, include app version, device model, Android and WebView versions, the interaction sequence and the observed result. Remove personal URLs, cookies, tokens and viewing history from logs and screenshots.
