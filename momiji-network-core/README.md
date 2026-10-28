# Shared network core

Pure Kotlin filtering and loopback transport sources compiled directly into both Android apps. `SecureResolver` uses Android's JSON API for optional DoH.

The product exposes normal routing, TCP ClientHello splitting and user-configured external SOCKS5. The loopback proxy bounds clients, workers, buffers and idle lifetime. TLS stays end to end. Legacy experimental record-fragmentation preferences migrate to TCP; record fragmentation remains in the protocol test suite but is not available in the UI.

Request filtering implements a bounded ABP/AdGuard subset with domain/context/type matching, exceptions, priority and supported badfilter semantics, plus `$popup`, `$all`, `$redirect`, `$redirect-rule`, `$denyallow`, `example.*` domains, page-wide exceptions (`$document`, `$elemhide`, `$generichide`, `$specifichide`, `$jsinject`, `$content`, `$urlblock`, `$genericblock`) and response rules (`$replace`, `$hls`, `$jsonprune`). Lists pass through `FilterSyntax`, which applies `!#if` blocks and `!+ PLATFORM` hints as the AdGuard Chromium extension. Unsupported modifiers and regex are skipped rather than widened. PSL data is used for party classification.

- `CosmeticRules` compiles element hiding, extended CSS, CSS injection, AdGuard/uBO scriptlets and raw JS rules, and answers per frame; generic `.class`/`#id` selectors are served only for tokens a page reports.
- `HlsPruner` removes server-inserted ads from HLS VOD playlists while keeping keys, init maps, byte ranges and discontinuities intact; live playlists are never edited.
- `Shield.kt` builds the shield payload, applies `$replace` with JavaScript semantics and names stand-ins for blocked ad libraries and media.

See [testing](../docs/TESTING.md), [privacy](../PRIVACY.md) and [third-party notices](../THIRD_PARTY_NOTICES.md).
