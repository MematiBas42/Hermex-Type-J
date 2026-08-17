# Hermex for Android

Native Android port of the [Hermex](https://github.com/uzairansaruzi/hermex) iOS
app — a mobile cockpit for a self-hosted
[hermes-webui](https://github.com/nesquena/hermes-webui) agent server. Built
with Kotlin + Jetpack Compose (Material 3 Adaptive), and optimized for
foldables — specifically the **Samsung Galaxy Trifold** (cover screen,
half-fold, and fully-unfolded three-panel layouts with pane boundaries that
snap to the physical hinges).

The phone is the control plane, not the compute plane: the app talks only to
your server over HTTPS (REST + Server-Sent Events). Bring your own
hermes-webui instance — see the root [README](../README.md) for server setup.

## Feature parity (v1)

Ported from the iOS app:

- **Connect & onboarding** — server URL + password, health check, cookie
  session persisted across restarts (password stored encrypted in the Android
  Keystore, never in plain preferences).
- **Chat** — streaming transcript over SSE with reasoning blocks, tool-call
  cards, markdown + code blocks, model / reasoning-effort / profile / workspace
  selection in the composer, attachments, stop and steer mid-run.
- **Sessions** — browse, search, pin, archive, rename, delete, branch, move to
  projects; cached sessions readable offline.
- **Models** — picker with recents and favorites.
- **Tasks** — browse, create, edit, run, pause/resume and delete scheduled
  cron jobs, with delivery options, running status and recent output.
- **Skills** — browse/search skill categories, enable/disable skills, read
  skill content and linked files.
- **Files** — workspace browser with text/image file preview.
- **Memory** — read-only agent memory panel.
- **Insights** — usage analytics computed from session metadata.

Not ported (yet): Kanban boards, git panels, TTS/transcribe, share extension,
live activities (iOS-only surfaces), push notifications.

The wire contract is verified against the same upstream pin as the iOS app:
`UPSTREAM_TESTED_SHA` at the repo root.

## Trifold / foldable behavior

| Posture | Layout |
|---|---|
| Cover screen (compact) | Single pane, bottom navigation |
| Half-fold / medium width | Navigation rail + two panes (list \| detail) |
| Fully unfolded (3 panels) | Rail + sessions \| transcript \| composer, boundaries aligned to the two hinges via `androidx.window` fold state |

Layouts react live to fold/unfold; pane state and scroll positions survive the
transition. `resizeableActivity` is enabled and the app supports multi-window,
all aspect ratios, and edge-to-edge with predictive back.

## Building

Requirements: JDK 21, Android SDK (platform 37, build-tools). The Gradle
wrapper (`./gradlew`) pulls everything else.

```bash
cd android
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # JVM unit tests
./gradlew assembleRelease        # release APK (signed if keystore.properties present)
```

### Release signing (local)

Release signing is intentionally **not** committed. Create a keystore outside
the repository and point `android/keystore.properties` (gitignored) at it:

```properties
storeFile=/absolute/path/hermex-release.jks
storePassword=...
keyAlias=hermex
keyPassword=...
```

Without the file, `assembleRelease` produces an unsigned APK (as CI does).

## Project layout

See [AGENTS.md](AGENTS.md) for the full architecture/conventions document —
Swift sources under `../HermesMobile/` are the behavioral source of truth; the
Kotlin packages mirror them (`model/`, `net/`, `data/`, `core/`, `ui/feature/…`).

## License

MIT, same as the upstream project (see [LICENSE](../LICENSE)).
