# Contributing

Useful contributions start with a reproducible TV workflow: the device, the input method, the steps and the expected result.

## Bugs

Include the app version, TV/device model, Android version, WebView version and a short reproduction. State whether the issue occurs in the web player, native player or Home. For network issues, include which routing/DNS/filter options are enabled without sharing proxy credentials, tokens, cookies or private viewing links.

## Changes

Keep Browser, Home and shared core as sibling directories. Build the affected app, run lint, and run the core suite for networking/filtering changes. Keep optional effects bounded, respect reduced motion and low-RAM behavior, and avoid long work on the main thread. Companion changes must preserve signature protection and profile ownership.

Document behavior and validation in the pull request. Add tests for significant failure modes rather than duplicating implementation details. Do not commit keys, local SDK paths, emulator state, APKs or private runtime data.

## Performance claims

Report the device and measurement method. Emulator timing alone is not evidence of TV speed, battery use or thermal behavior. Separate connection reachability from provider-specific circumvention effectiveness.
