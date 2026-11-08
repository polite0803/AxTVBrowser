# AxTVBrowser

A web browser for Android TVs, built around the TV remote: D-pad navigation with a virtual
cursor, or direct navigation without one. The same APK also has a touch interface for phones
and tablets, so a phone can drive the browser the way a remote does and send its open tabs and
bookmarks to a TV over the local network.

It ships as two separate apps that differ only in the engine that renders pages:

| App | applicationId | Engine | minSdk |
|---|---|---|---|
| AxTVBrowser | `com.axtv.browser` | the system WebView | 24 |
| AxTVBrowser (GeckoView) | `com.axtv.browser.gecko` | a bundled GeckoView | 26 |

The engine of an installed app is fixed at build time; there is no engine setting to switch.
The WebView app is the one to try on a weak device, the GeckoView one brings its own newer
engine, its tracking protection and its own page behaviour.

## Features

- tabs, with page state and thumbnails restored on start, and recently closed tabs to reopen
- bookmarks in folders, export and import of the Chrome/Firefox/Edge bookmarks file
- browsing history with search and multi-select delete, and address suggestions from it
- find in page, voice search, user configurable keyboard and remote shortcuts
- ad blocking with an engine written for TV hardware (EasyList, EasyPrivacy, a regional list,
  your own list) and element hiding
- a built-in video player (HLS, DASH and files) with qualities, audio tracks, subtitles,
  playback speed, picture-in-picture and frame-rate matching, plus the list of the videos a
  page plays and the address of each one
- a download manager: resumable downloads, a custom folder, its own process so downloads go
  on while the browser restarts
- desktop or mobile site view per site, plus a per-site override of the ad blocking, of the
  popup blocking and of the player
- HTTP and SOCKS5 proxy, with a login
- phone-to-TV sync of tabs, bookmarks and files, with a QR code pairing and an encrypted channel
- Android TV home screen channels of bookmarks, open tabs and finished downloads
- a privacy screen: browsing history, site data and cached files cleared by period or by site
- 16 interface languages

## Build

JDK 21, Gradle (Kotlin DSL); on Windows use `gradlew.bat`. The command line needs no setup file
beyond an Android SDK (`local.properties` or `ANDROID_HOME`). The wrapper downloads Gradle 9.9,
which needs a JDK 21 for the toolchain of `buildSrc`.

Variants combine `appstore` = `generic` | `google` | `foss` with `webengine` =
`geckoIncluded` | `geckoExcluded`:

```bash
./gradlew assembleGenericGeckoIncludedDebug      # the GeckoView app
./gradlew assembleGenericGeckoExcludedDebug      # the WebView app
./gradlew testGenericGeckoExcludedDebugUnitTest  # the unit tests of one variant
./gradlew :adblock:testDebugUnitTest             # the ad blocker, plain JVM tests
```

- `-PenableAbiSplits` on `assemble` gives per-ABI APKs (armeabi-v7a, arm64-v8a, x86_64).
- Release signing reads `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`;
  without `KEYSTORE_PATH` a release build is unsigned.
- `google` and `foss` differ only in the built-in auto-update (`generic` has it) and in picture
  in picture (`google` has none, per the TV quality guidelines of Google Play); `foss` also adds
  the `.foss` application id suffix. A debug build always carries `.dev`, so it installs next to
  a release one.

## Modules

- `:app` - the application: the browser core, the UI, the Room database, the download service,
  the WebView engine and the ad blocking filter lists.
- `:app:common` - the code both engines need: the `WebEngine` abstraction and its factory, the
  entity models, `Config` and the cursor widgets.
- `:app:gecko` - the GeckoView engine and its bundled WebExtension; a dependency of the
  `geckoIncluded` variants only.
- `:adblock` - the ad blocker: filter parsing, the immutable matching engine and the element
  hiding index, plain Kotlin so its tests run on the JVM.
- `buildSrc` - the `axtv.android.application` and `axtv.android.library` convention plugins.

`AGENTS.md` describes the architecture, the module layout and the build flags in more detail;
`.github/workflows/pr.yml` runs the tests, the extra flavors and the release metadata check of
`tools/release-metadata-check.mjs` on every push and pull request.

## Brand and identifiers

The name, the launcher icon and the application id are this project's own. A few identifiers
keep the upstream names on purpose, because they are part of a protocol rather than of the
brand:

- `https://tvbro.phlox.dev/sync` and the `tvbro://sync` scheme - the address the sync QR code
  opens on the phone, served by the upstream project;
- `_tvbro._tcp.` - the mDNS service type a phone looks for;
- `TVBro` - the name of the JavaScript bridge object the home page talks to.

Changing them would break the pairing of the phones that use the upstream page.

## Credits and license

AxTVBrowser is a fork of [TV Bro](https://github.com/truefedex/tv-bro), Copyright (c) 2019
Fedir Tsapana, and keeps its license: see [LICENSE.md](LICENSE.md). For a modified binary that
license requires a different name, icon and application id (this fork has all three) and an
About screen that states the origin and links the upstream project (Settings - About, "Based on
TV Bro sources"). The third-party components and their licenses are listed in the app, under
Settings - About.

- [PRIVACY.md](PRIVACY.md) - what the app does and does not collect
- Upstream project: <https://github.com/truefedex/tv-bro>