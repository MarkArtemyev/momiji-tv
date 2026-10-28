# Changelog

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
