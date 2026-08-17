# Hermex-Type-J Android

Native Kotlin + Jetpack Compose client for a self-hosted Hermes agent.

This directory is the native Android implementation maintained by the
[`jcartu/Hermex-Type-J`](https://github.com/jcartu/Hermex-Type-J) fork. For the product
story, screenshots, fork rationale, and complete feature map, start with the
[root README](../README.md).

[![Android CI](https://github.com/jcartu/Hermex-Type-J/actions/workflows/android-ci.yml/badge.svg)](https://github.com/jcartu/Hermex-Type-J/actions/workflows/android-ci.yml)
[![Android 8+](https://img.shields.io/badge/Android-8%2B-3DDC84?logo=android&logoColor=white)](build.gradle.kts)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)](gradle/libs.versions.toml)

<img src="../docs/assets/android/android-postures.png" alt="Hermex-Type-J across phone tablet fold and trifold layouts" width="100%" />

## Supported Android layouts

| Window | Result |
|---|---|
| Compact / cover | Bottom navigation and one focused screen |
| Phone | Full-screen list/detail navigation |
| Tablet / opened fold | Navigation rail and persistent list + detail |
| Trifold | Hinge-aligned three-pane Chat, Tasks, Skills, and Files |
| Half-open | Hinge-safe controls with live state continuity |
| Multi-window | Reflow from measured width without restarting work |

The app consumes `androidx.window` folding features when available. Width-based
fallbacks keep the same behavior on tablets and emulators that report no hinge.

## Requirements

- JDK 21
- Android SDK platform 37
- Android build-tools (36.0.0 or newer works with the current project)
- `adb` for device installation
- a reachable hermes-webui server for real use

The checked-in Gradle wrapper pins Gradle 9.7.0. The version catalog pins AGP,
Kotlin, Compose, OkHttp, serialization, and the remaining dependencies.

## Build

From the repository root:

```bash
cd android
./gradlew assembleDebug
```

Debug APK:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

Install it:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Run the complete JVM gate:

```bash
./gradlew testDebugUnitTest
```

Build the release variant:

```bash
./gradlew assembleRelease
```

Without local signing configuration, CI produces an unsigned release APK and
uploads it as a workflow artifact.

## Release signing

Signing material is never committed. Keep the keystore outside the repository
and create `android/keystore.properties` (already gitignored):

```properties
storeFile=/absolute/path/to/hermex-release.jks
storePassword=...
keyAlias=hermex
keyPassword=...
```

Then build and verify:

```bash
./gradlew assembleRelease
$ANDROID_SDK_ROOT/build-tools/36.0.0/apksigner \
  verify --verbose app/build/outputs/apk/release/app-release.apk
```

The source tree ignores `keystore.properties`, `*.jks`, `local.properties`,
APKs, Gradle output, IDE metadata, and local SDK state.

## Connect to a server

Hermex is a client; it does not embed the Hermes Python runtime.

1. Start [hermes-webui](https://github.com/nesquena/hermes-webui).
2. Set `HERMES_WEBUI_PASSWORD` whenever the host is not loopback-only.
3. Expose it over HTTPS with Tailscale Serve, Cloudflare Tunnel, or a reverse
   proxy.
4. Open Hermex, enter the exact base URL, test the connection, and connect.

Passwordless servers are supported when `/api/auth/status` reports auth
disabled. Passkey-only servers are detected and rejected with a clear message
because this client does not implement the browser passkey ceremony.

Plain HTTP is permitted for trusted LAN/emulator development. Prefer HTTPS for
anything routed beyond that boundary.

## Architecture

```text
app/src/main/java/io/github/jcartu/hermex/
├── HermexApplication.kt
├── MainActivity.kt
├── service/
│   └── AgentRunService.kt           # foreground run monitor + notifications
├── di/
│   └── AppContainer.kt              # process-wide dependencies
├── core/
│   ├── crypto/                      # Android Keystore AES-GCM
│   ├── media/                       # voice notes + assistant TTS
│   ├── navigation/                  # durable deep-link inbox
│   ├── net/                         # persistent cookie jar
│   ├── prefs/                       # settings/config + encrypted headers
│   └── share/                       # durable ACTION_SEND inbox
├── data/
│   └── CacheStore.kt                # file-backed offline cache
├── model/                           # tolerant server contract models
├── net/
│   ├── Endpoints.kt                 # every route incl. Kanban/Git/voice
│   ├── HermesApi.kt                 # OkHttp REST client
│   └── SseChatStream.kt             # native SSE stream
└── ui/
    ├── adaptive/                    # fold state + hinge geometry
    ├── components/
    ├── theme/
    └── feature/
        ├── onboarding/ settings/
        ├── sessions/ chat/ models/
        ├── kanban/ git/
        ├── tasks/ skills/
        └── workspace/ memory/ insights/
```

### Dependency model

`HermexApplication` owns one manual `AppContainer`. That container provides a
single instance of:

- `HermesApi`
- `AuthManager`
- `PersistentCookieJar`
- `ServerConfigRepository`
- `SettingsRepository`
- `ModelFavoritesStore` and `ModelRecentsStore`
- `CacheStore`

Keeping these process-wide prevents split auth state, duplicate DataStores, or
feature-specific caches.

### State continuity

- ViewModels own feature state.
- `AppNavigationViewModel` uses `SavedStateHandle` for destination, selected
  session, and new-chat mode.
- `rememberSaveable` covers local scroll and disclosure state.
- Chat suspends SSE and auxiliary polling on lifecycle stop, then performs a
  status-checked reconnect on return.
- The client never resends a user message merely because the transport dropped.

### Trifold geometry

`FoldState` collects `WindowInfoTracker.windowLayoutInfo`. The shell derives a
pane count from physical hinges and window width. `HingeAwarePaneRow` converts
hinge centers from window coordinates into local layout coordinates so controls
and pane boundaries do not cross the occlusion strip.

The important three-pane contracts are:

```text
Chat     sessions | transcript | composer + run controls
Kanban   boards | columns | card detail, activity, runs
Git      workspace sessions | status + files | diff + commit
Tasks    task list | detail + actions | recent output
Skills   skill list | rendered skill | linked file
Files    workspaces | directory contents | file preview
```

## Networking contract

The Android app follows the same upstream pin as iOS:
[`UPSTREAM_TESTED_SHA`](../UPSTREAM_TESTED_SHA).

Important rules:

- unknown JSON keys never crash decoding;
- optional malformed fields degrade independently;
- native requests do not add browser `Origin` or `Referer` headers;
- non-2xx responses map to typed `ApiError` cases;
- 401 demotes auth state without discarding the saved server;
- cross-origin redirects strip custom headers;
- SSE comments/heartbeats do not render as events;
- interrupted streams use status/replay recovery and never auto-resend.

## Tests

The current suite covers models, endpoint request shapes, auth, cache, SSE,
ViewModels, mutations, chat stream lifecycle, slash commands, attachment flow,
adaptive geometry, analytics, and navigation continuity.

```bash
./gradlew testDebugUnitTest
```

Current local gate: **538 tests across 49 suites, 0 failures**.

GitHub Actions runs the same test task, builds the release variant, and uploads
an unsigned APK for every Android change.

## Screenshots

Real Android emulator captures live in [`docs/assets/android`](../docs/assets/android):

- `cover-chat.png`
- `phone-sessions.png`
- `tablet-chat.png`
- `fold-open-chat.png`
- `half-open-chat.png`
- `trifold-chat.png`
- `trifold-tasks.png`
- `trifold-kanban.png`
- `trifold-git.png`
- `trifold-files.png`
- `trifold-insights.png`

## Current exclusions

A TTS engine voice picker, remote-relay push notifications, and iOS-only Live
Activities/App Intents are outside the current Android scope. Everything else —
Kanban boards, Git workspace tooling, the share target, deep links, foreground
run notifications, encrypted custom headers, and voice notes/transcription — is
implemented. There are no fake buttons or placeholder screens.

## Contributing

Read [`AGENTS.md`](AGENTS.md) before changing Android code. The short version:

- verify every endpoint and wire key against upstream or the Swift client;
- keep model decoding tolerant;
- preserve compact, tablet, fold, and trifold behavior together;
- do not add a second convention beside an existing one;
- add behavior tests for new observable contracts;
- never commit secrets, APK signing material, or local SDK paths.

## License and attribution

MIT — see [`../LICENSE`](../LICENSE).

The Android implementation is maintained in the proper
[`jcartu/Hermex-Type-J`](https://github.com/jcartu/Hermex-Type-J) fork. The original
SwiftUI project and product foundation are by
[`uzairansaruzi/hermex`](https://github.com/uzairansaruzi/hermex).
