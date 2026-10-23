# Hermex-Type-J Android — Agent Conventions

This directory contains the native Hermex-Type-J Android client. The upstream
Swift sources remain the behavioral source of truth at `../HermesMobile/`.
This document is the binding contract for every agent working in `android/`.
## Hard rules

1. **Never invent API shapes.** Every endpoint, JSON field, SSE event, and
   gateway method must match the current Swift sources and the server contract.
   `../UPSTREAM_TESTED_SHA` pins WebUI; `../HERMES_AGENT_TESTED_SHA` pins Hermes.
   Connection and protocol guidance lives in `../docs/agents/hermes-server.md`
   and `../DEVELOPMENT.md`. Port tolerant decoding; unknown fields must not crash.
2. **No TODO, no stubs, no placeholders, no "not implemented" comments.**
   Implement the feature completely or leave the file untouched.
3. **Never add `Origin` or `Referer` headers** to any request. WebUI treats
   requests without them as non-browser clients (see `../DEVELOPMENT.md`,
   "SSE and Cloudflare Stream Verification"). OkHttp adds neither by default.
4. **Only dependencies from `gradle/libs.versions.toml`** — adding a library
   requires the project owner's approval. Everything needed is already there
   (Compose, Material3 + adaptive, androidx.window, OkHttp + SSE, DataStore,
   kotlinx.serialization, Coil, mikepenz markdown renderer, mockk, MockWebServer).
5. **Do not run Gradle.** Agents write code; the integrator builds, fixes, and
   tests. Do not create build files or modify `app/build.gradle.kts`.

## Project layout

```
app/src/main/java/io/github/jcartu/hermex/
├── HermexApplication.kt / MainActivity.kt
├── di/AppContainer.kt          # manual DI; feature services hang off here
├── core/
│   ├── crypto/                 # AndroidKeystoreCipher (StringCipher)
│   ├── logging/HermexLog.kt
│   ├── net/                    # persisted cookies and connection helpers
│   └── prefs/                  # server registry, credentials, settings and cookies
├── model/                      # ported Swift Models/ — one Kotlin file per Swift file, same type names
├── net/                        # WebUI REST/SSE; net/hermes contains Hermes REST/RPC
├── data/                       # server-scoped offline cache and durable chat drafts
└── ui/
    ├── HermexApp.kt            # authentication gate and adaptive navigation shell
    ├── adaptive/FoldState.kt   # trifold hinge state + pane-count heuristics
    ├── components/             # shared composables (LoadingState/ErrorState/EmptyState live here)
    └── feature/<name>/         # one package per feature: <Name>Screen.kt, <Name>ViewModel.kt, parts as needed
```

Package: `io.github.jcartu.hermex` (unchanged for in-place upgrades). Feature
packages include `onboarding`, `sessions`, `chat`, `models`, `tasks`, `skills`,
`workspace`, `memory`, `insights`, `settings`, `kanban`, and `git`.

## Swift → Kotlin mapping

| iOS | Android |
|---|---|
| SwiftUI `View` | `@Composable fun XScreen(...)` in `ui/feature/.../XScreen.kt` |
| `@Observable`/ViewModel logic | `class XViewModel : ViewModel` with `StateFlow<XUiState>` |
| Swift `Codable` (tolerant) | `@Serializable` data class + `Json` from `AppContainer.json` (`ignoreUnknownKeys=true` etc. already set) |
| URLSession + APIClient | `OkHttp` via `AppContainer.okHttpClient` + `net/HermesApi` |
| Keychain | `core/crypto` (already built) |
| SwiftData cache | `data/` file cache via kotlinx.serialization |
| String(localized:) | plain English strings inline (the Android client is English-only) |
| SF Symbols | Material icons (`androidx.compose.material.icons.Icons`) |

Type/field naming: keep Swift type names (`SessionSummary`, `ChatMessage`,
`ServerCatalog`…). Kotlin properties camelCase matching the Swift property;
`@SerialName` only when the Swift `CodingKeys` differ from the Kotlin name
(wire keys are usually snake_case).

### Decoding tolerance pattern

Swift's custom `init(from:)` logic (optional-everything, alternate keys,
string-or-array unions) must be ported to custom serializers or safe secondary
constructors. For JSON unions use `JsonElement` fields plus accessor properties,
following the existing `model/JsonValue.kt` and `model/hermes/BotJSON.kt`.

## Networking contract

`net/HermesApi.kt` is the WebUI REST client; its suspend configuration provider
resolves the active server. Feature-specific extension files use the same
request helpers and `Endpoint.Custom`; do not duplicate authentication or
redirect handling. Hermes connections use `net/hermes/HermesConnection.kt`,
`HermesREST`, and `HermesCall`, not WebUI endpoints.

- All endpoints from `../HermesMobile/Networking/Endpoints.swift` + the
  extension files `APIClient+*.swift` — port every one the features below use
  (auth, sessions, chat start/stream/cancel/steer/status, upload, workspaces,
  files, models, providers, settings, reasoning, profiles, crons, skills,
  memory, projects, session usage).
- Errors: sealed class `ApiError` mirroring `APIError.swift` (port its cases
  and messages exactly). Suspend functions throw `ApiError`; ViewModels catch.
- SSE: `net/SseChatStream.kt` wrapping okhttp-sse. Port the event model from
  `SSEClient.swift` EXACTLY (every `case` in its event enum, including
  heartbeats-ignored semantics, `title`, `tool`, `tool_complete`,
  `interim_assistant` handling per the Swift file).
- The chat stream needs `readTimeout = 0` — build the SSE client from
  `okHttpClient.newBuilder()`.
- Multipart upload per `MultipartFormData.swift` + `APIClient+Upload.swift`
  (field name `session_id`, file part; response `UploadResponse`).

## UI conventions

- `HermexTheme` supplies the selected palette: Tokyo Night by default, Classic
  as an option, each supporting System/Light/Dark. Use `MaterialTheme.colorScheme`
  roles rather than fixed black/white/gold values, including onboarding screens.
- ViewModels expose a single immutable `data class ...UiState` through
  `StateFlow`; mutate via private `MutableStateFlow`. No RxJava, no LiveData.
- Collect with `collectAsStateWithLifecycle()`.
- Get dependencies via `viewModel(factory = ...)` reading from
  `AppContainer` (see `HermexApplication.container`). Do not use a service
  locator from composables for data — only for ViewModels factories.
- Errors surface via `ui/components/CommonStates.kt` (`ErrorState`,
  `EmptyState`, `LoadingState`) or inline `assist`/snackbar where the iOS app
  does the same.
- Markdown rendering: mikepenz renderer (`com.mikepenz.markdown.m3.Markdown`).
  Code blocks monospaced via `ui/theme/Type.kt#CodeTextStyle`; copy button on
  code blocks like the iOS renderer.
- Images: Coil (`AsyncImage`).

### Trifold / foldable / tablet rules (Samsung Galaxy Trifold is the primary target; tablets are first-class)

Adaptivity is a hard acceptance criterion, not a final pass. Every screen
works in ALL four layout modes, verified by the author of the screen:

1. **Compact (cover screen / phone portrait)** — single pane, bottom nav,
   16dp content margins, thumb-reachable actions.
2. **Medium (half-fold, tablet portrait, split-screen)** — navigation rail +
   two panes (list | detail). A selected item's detail opens beside the list,
   not pushed over it. When no item is selected show a sensible empty detail
   pane ("Select a session" etc.).
3. **Expanded (tablet landscape, unfolded dual-fold)** — rail + two panes with
   generous max-content widths (~480dp per pane; text does not stretch
   full-bleed), grids where grids beat lists (skills, insights cards).
4. **Trifold (three panels)** — `HingeAwarePaneRow` from `ui/adaptive/` places
   three content regions whose boundaries snap to the two physical hinges:
   sessions list | transcript | composer+run controls for Chat; list | detail |
   inspector for other features where a third region adds value (e.g. file
   browser: tree | files | preview).

Posture awareness (`FoldState.isHalfOpened`, `isFlatWithHinges`):
- Half-opened horizontal hinge (book/tent): content splits above/below the
  fold line — primary content top, controls bottom — wherever the screen has a
  natural split (chat transcript over composer).
- Never place interactive controls (buttons, text fields) across a hinge
  occlusion strip (`FoldState.occlusionRects()`).

Continuity: ViewModels survive fold/unfold (don't key state on configuration);
`rememberSaveable` for scroll + selection; detail selection persists when pane
count changes; nothing reloads from network on a pure posture change.

Use `LocalFoldState` / `LocalPaneCount` (already provided by the shell),
`rememberFoldState()`, `trifoldPaneCount()`, `HingeAwarePaneRow`,
`computePaneWidths()`. No `LocalConfiguration.current.orientation` branching —
size classes and hinge state only. `Modifier.fillMaxWidth()` over fixed
widths. Never assume ~400dp.

## Tests

Keep deterministic regression coverage under `app/src/test/java/...` for
consumer-visible boundaries, state transitions, and protocol decisions:

- Transcribe fixtures from current Swift/server sources; test tolerant cases
  where the server genuinely varies, not implementation details or copy.
- ViewModel tests drive events and assert resulting state; network tests use
  `okhttp-mockwebserver` and must close their sockets during teardown.
- Robolectric is available for Android-dependent behavior. Android resources
  are not included in JVM tests, so these tests do not replace emulator proof.
- Coordinate coroutine races with an explicit signal from the required boundary
  (for example, `CompletableDeferred` when a write is entered). `yield()` and
  dispatcher hops are not barriers. Bound waits and release test gates in `finally`.
- The integrator builds, installs, and exercises the app on an emulator/device,
  including folded/unfolded layouts and a restart with saved server settings.
- Android's regex engine rejects the JVM-only `(?U)` flag. Use portable Unicode
  classes such as `[\\s\\p{Z}]`; JVM-only tests will not catch this startup crash.

## Feature ↔ Swift file map

| Android package | Swift sources to port |
|---|---|
| `model/` | `HermesMobile/Models/*.swift` (all files listed in the models task) |
| `net/` | `HermesMobile/Networking/*.swift` |
| `ui/feature/onboarding` | `Features/Onboarding/*`, `Auth/AuthManager.swift` |
| `ui/feature/sessions` | `Features/SessionList/*` |
| `ui/feature/chat` | `Features/Chat/*` |
| `ui/feature/models` | model picker + `Models/ModelFavoritesStore.swift`, composer selector views |
| `ui/feature/tasks` | `Features/Tasks/*`, `Models/Cron.swift` |
| `ui/feature/skills` | `Features/Skills/*`, `Models/Skills.swift` |
| `ui/feature/workspace` | `Features/Workspace/*`, `Models/Workspace.swift` |
| `ui/feature/memory` | `Features/Memory/*`, `Models/Memory.swift` |
| `ui/feature/insights` | `Features/Insights/*` |
| `ui/feature/settings` | `Features/Settings/*` (minus iOS-only icon/streaming-lab sections) |

Kanban, Git panels, durable Android sharing, voice transcription/TTS, deep
links, launcher shortcuts, and foreground run notifications are part of Type-J.
iOS-only App Intents and Live Activities map to Android shortcuts and ongoing
notifications. The upstream push relay targets APNs: do not claim Android
remote-push support or introduce Firebase without an approved Android transport.
