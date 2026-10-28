# Changelog

## Unreleased

### Ad blocking engine

- Lists are read as the AdGuard Chromium extension reads them: `!#if` blocks and `!+ PLATFORM` hints are applied, so Safari/iOS workarounds no longer leak in. AdGuard Mobile Ads is added; ad lists load before the tracking list.
- Network rules gain `$popup`, `$all`, `$redirect`, `$redirect-rule`, `$denyallow`, `example.*` domains, page-wide exceptions and response rules. Rules now kept: about 465k instead of 152k; 259k image-pixel rules live in a compact hash table.
- AdGuard Scriptlets (101 scriptlets, AdGuard and uBO syntax) and ExtendedCss run in every frame before page scripts, without eval; raw JS rules run only on their domains.
- Blocked ad SDKs, VAST, clips and pixels get AdGuard's stand-in resources, so web players continue to the video. `$replace` rewrites VAST and scripts.
- HLS VOD playlists lose server-inserted ad segments in web players and in the native player; JSON player APIs are pruned by `$jsonprune`. The native player no longer fails a stream mid-playback because a segment matched a filter.
- `$popup` rules filter new windows; a script on another site must ask before it can replace the page.
- Generic hiding covers all 14k generic selectors by serving only those whose class or id the page uses.

## Preview 0.4.0 — Home 0.4.0 / Browser 0.3.0

### Home

- Centered application icons and optional app-card labels, hidden by default.
- Per-profile layouts, categories, appearance, shortcuts and native playback history.
- Optional Canvas atmosphere with maple leaves, fireflies and stars, respecting lifecycle and reduced motion.
- Shared network settings and filtered artwork loading through the companion browser.

### Browser and network

- TCP ClientHello splitting, external SOCKS5 and optional DNS over HTTPS.
- Offline domain/context filters, custom rules, site exceptions and cosmetic hiding.
- Filtering for supported WebView background requests and native media requests.
- Proxy-aware range loading while keeping the normal HttpEngine path when routing is off.
- Improved navigation after protection changes and background waiting for cold filter initialization.

### Validation and limits

- Android debug/release builds and lint, 18 pure-JVM integration checks, Android WebView/native-media/companion scenarios and minified UI checks.
- Experimental TLS-record modes excluded from the product after Android interoperability failures.
- Physical-TV performance, firmware behavior and provider-specific circumvention remain to be validated.
