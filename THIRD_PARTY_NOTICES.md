# Third-party notices

## AdGuard filter data

Bundled AdGuard DNS, Base, Mobile Ads, Russian and Tracking Protection lists are provided by the AdGuard Team under GPL-3.0. Original downloaded snapshots, their manifests and the full license text are preserved under `momiji-browser/app/src/main/assets/protection/`.

The compact domain table, serialized supported-rule table and normalized cosmetic table are derived data. Home bundles the domain table and license; the corresponding original DNS snapshot is included with Browser in the same repository.

Official projects and sources:

- https://github.com/AdguardTeam/AdGuardSDNSFilter
- https://github.com/AdguardTeam/FiltersRegistry
- https://github.com/AdguardTeam/AdguardFilters

The source URL and SHA-256 of each snapshot are recorded in the bundled `manifest.json`. Runtime updates use the configured official endpoints with a mirror fallback where implemented. No claim of full AdGuard engine or syntax compatibility is made.

## AdGuard Scriptlets and ExtendedCss

The browser bundles unmodified builds of AdGuard Scriptlets (the corelibs build of each scriptlet, and its redirect resources) and AdGuard ExtendedCss, both GPL-3.0 by AdGuard Software Ltd. They are stored with their license and versions under `momiji-browser/app/src/main/assets/protection/vendor/` and regenerated from the published npm packages by `tools/vendor-adguard.mjs`.

- https://github.com/AdguardTeam/Scriptlets (`@adguard/scriptlets`)
- https://github.com/AdguardTeam/ExtendedCss (`@adguard/extended-css`)

## Public Suffix List

The Public Suffix List is maintained by its contributors and distributed under MPL-2.0. The preserved source list includes its license notice.

- Source: https://publicsuffix.org/list/public_suffix_list.dat
- License: https://mozilla.org/MPL/2.0/

## Android and build dependencies

AndroidX WebKit and Media3 are Apache-2.0 projects. Kotlin is distributed under Apache-2.0; Gradle is distributed under Apache-2.0. Dependencies are resolved through their official repositories by Gradle; no modified upstream engine is included.

- https://android.googlesource.com/platform/frameworks/support/
- https://github.com/androidx/media
- https://github.com/JetBrains/kotlin
- https://github.com/gradle/gradle

## Optional external proxy

Momiji can connect to a separately installed SOCKS5 proxy. ByeDPI and ByeDPIAndroid are external projects; their code and APKs are not bundled or copied into Momiji.

- https://github.com/hufrea/byedpi
- https://github.com/dovecoteescapee/ByeDPIAndroid

## Names and screenshots

Android, Google TV and third-party app names/icons shown by the device belong to their respective owners. Screenshots demonstrate the launcher displaying installed applications and do not imply endorsement or an official partnership.

Full reference texts: [Apache-2.0](LICENSES/Apache-2.0.txt) and [MPL-2.0](LICENSES/MPL-2.0.txt).
