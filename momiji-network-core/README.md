# Shared network core

Pure Kotlin filtering and loopback transport sources compiled directly into both Android apps. `SecureResolver` uses Android's JSON API for optional DoH.

The product exposes normal routing, TCP ClientHello splitting and user-configured external SOCKS5. The loopback proxy bounds clients, workers, buffers and idle lifetime. TLS stays end to end. Legacy experimental record-fragmentation preferences migrate to TCP; record fragmentation remains in the protocol test suite but is not available in the UI.

Request filtering implements a bounded ABP/AdGuard subset with domain/context/type matching, exceptions, priority and supported badfilter semantics. Unsupported modifiers and regex are skipped rather than widened. PSL data is used for party classification.

See [testing](../docs/TESTING.md), [privacy](../PRIVACY.md) and [third-party notices](../THIRD_PARTY_NOTICES.md).
