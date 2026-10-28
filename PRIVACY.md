# Data and permissions

## Local data

Home stores profiles, layouts, categories, app shortcuts, imported artwork and native-player history in its private storage. Browser stores settings, its WebView website data, paired-controller credentials and applicable standalone playback state. An app account is not required. Home profiles are not Android users and do not isolate website cookies or third-party accounts.

History records the source page. If a user opens a direct signed media URL, that page can itself contain temporary query parameters. Do not share app databases or private storage as diagnostics. Layout export excludes history, account data and private images.

## Network requests

- Browsing contacts the chosen websites and their resources under the configured filter rules.
- Artwork downloads contact the providers exposed by installed services or chosen card sources.
- Filter updates contact the fixed AdGuard sources listed in the bundled manifests.
- Optional DoH sends DNS queries to the selected Cloudflare or Google endpoint.
- Optional SOCKS5 routes eligible Momiji requests through the proxy configured by the user.
- The optional Gemini voice remote uses the user's API key and sends commands and the screen/context needed for its operation to Google's service. It is separate from ordinary browsing and Home operation.

The project does not require a central Momiji account or a bundled remote analytics service. Websites and optional third-party services have their own privacy policies. TCP splitting keeps TLS end to end and does not hide the device's public IP.

## Companion exchange

Home and Browser exchange supported history/settings through signature-protected providers with package checks. Both APKs must be signed with the same certificate. The network-settings provider shares configuration and a read-only domain snapshot; it does not export browsing history through that interface.

## Optional permissions and helpers

- TV listing access enables content exposed by compatible system providers.
- Notification access enables controls for compatible active media sessions.
- The optional Home accessibility helper intercepts Home on compatible firmware. It can observe the active package and use Android's app-info screen to stop/restart supported apps. It also watches VPN reconnections and can reset previously used YouTube instances after a network change. It does not reconfigure the VPN itself.
- The optional voice accessibility service can inspect and operate the screen when the user enables it.

The main launcher and browser do not require enabling every optional helper. Review each service in Android settings and disable it there when not needed.

## Computer control

The PC controller is authenticated local-network HTTP. Its pairing code is short-lived; the paired token stays on the participating devices. Use it on a trusted network and do not forward the control port to the public internet. It controls Momiji Browser rather than acting as a system-wide Bluetooth keyboard.
