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
- a reachable Hermes dashboard (0.21.3 or newer) or legacy hermes-webui server

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

1. Run a [Hermes](https://github.com/NousResearch/hermes-agent) dashboard, or
   keep your existing [hermes-webui](https://github.com/nesquena/hermes-webui).
2. Configure the server's sign-in provider and expose it over HTTPS through
   Tailscale Serve, Cloudflare Tunnel, or a reverse proxy.
3. Enter the exact URL in the app. The connect form detects a Hermes host and
   offers its password, browser-PKCE, or open-dashboard connection flow;
   non-Hermes servers follow the existing WebUI flow.
4. Add and switch servers in Settings. Credentials, headers, cached sessions,
   drafts, and model history remain scoped to the selected server.

For Hermes authentication and persistent signing secrets, see
[`DEVELOPMENT.md`](../DEVELOPMENT.md#hermes-host-setup). A reachable dashboard
with no sign-in displays an explicit access warning. Passwordless WebUI is
accepted only when its auth-status endpoint reports authentication disabled.

Tokyo Night dark is the default appearance. Settings → Appearance offers
Classic, Light, and System alternatives without overwriting saved choices.

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
│   ├── prefs/                       # server registry, encrypted records, settings
│   └── share/                       # durable ACTION_SEND inbox
├── data/
│   ├── CacheStore.kt                # server-scoped offline cache
│   └── ChatDraftStore.kt            # durable drafts and retained attachments
├── model/                           # tolerant server contract models
├── net/
│   ├── Endpoints.kt                 # legacy WebUI routes + feature extensions
│   ├── HermesApi.kt                 # legacy WebUI REST client
│   ├── SseChatStream.kt             # WebUI SSE with replay cursor
│   └── hermes/                     # Hermes REST, sign-in, shared gateway RPC
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
- `HermesConnections` and the encrypted `BotConnectionRecordStore`
- `ServerRegistry`, `ServerCredentialStore`, and server identity defaults
- `ChatDraftStore` and durable live-stream snapshots

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

The Android port uses the same behavioral references and compatibility pins as
the retained Swift sources: [`UPSTREAM_TESTED_SHA`](../UPSTREAM_TESTED_SHA) for
WebUI and [`HERMES_AGENT_TESTED_SHA`](../HERMES_AGENT_TESTED_SHA) for Hermes.
The current upstream 2.0 development snapshot is included; these pins do not
claim compatibility with every past or future host release.

Important rules:

- unknown JSON keys never crash decoding;
- optional malformed fields degrade independently;
- native requests do not add browser `Origin` or `Referer` headers;
- failures remain typed (`ApiError` on WebUI, `BotFailure` on Hermes);
- a sign-in rejection affects only the owning server and current credential generation;
- cross-origin redirects strip custom headers;
- SSE comments/heartbeats do not render as events;
- interrupted streams use status/replay recovery and never auto-resend.
- Hermes socket upgrades never follow redirects with their ticket/proxy credentials;
- transport failures do not silently replay non-idempotent Hermes operations;
- only confirmed authentication-gate 401s permit the guarded re-sign-in/retry path.

## Tests

The suite covers protocol decoding, authentication isolation, replay/recovery,
draft persistence, transactional imports, requests and approvals, notifications,
session mutations, adaptive geometry, file/Git operations, and navigation.

```bash
./gradlew testDebugUnitTest
```

Current results are recorded by the build output and Android CI, not a static
test-count badge. Run `./gradlew lintDebug assembleDebug assembleRelease` as
the packaging/static gate, then install and exercise the actual app.

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

The upstream relay supports APNs only. Foreground monitoring and local Android
notifications are not remote push delivery while the app is stopped. Live
Activities/App Intents use Android ongoing notifications and shortcuts instead.
Document previews use PdfRenderer, the native source/image viewer, or an
external Android document handler rather than iOS Quick Look. There is no
separate TTS-engine voice picker; Hermes speech uses the host's configured voice.

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

Tokyo Night follows the palettes maintained by
[enkia](https://github.com/enkia/tokyo-night-vscode-theme) and
[folke](https://github.com/folke/tokyonight.nvim). The file-type mapping follows
upstream's Pierre `@pierre/trees` icons (Apache-2.0) and T3 Code additions (MIT),
with native Android vector counterparts.
