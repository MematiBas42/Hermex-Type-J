# Changelog

Notable changes to Hermex. Version headings correspond to App Store releases;
unreleased changes accumulate at the top. Format follows
[Keep a Changelog](https://keepachangelog.com/) with Added / Changed / Fixed /
Security sections per release.

## [2.0.0] - 2026-08-17

### Added
- Renamed the Android fork to **Hermex-Type-J** (`jcartu/Hermex-Type-J`,
  package ID unchanged for in-place upgrades) and expanded it with:
- Kanban boards: boards/columns/cards, card detail (comments, activity,
  dependencies, runs), create/edit/move/block/unblock, bulk actions, dispatcher
  dry-run/execute, worker logs, and live event reconciliation.
- Git workspaces: session-scoped status, branches, staged/unstaged diffs,
  stage/unstage/discard, fetch/pull/push, checkout + stash-checkout, generated
  commit messages, and selected-path commits, all with explicit confirmations.
- Android share target (ACTION_SEND/SEND_MULTIPLE/PROCESS_TEXT) backed by a
  durable app-private inbox; drafts and attachments survive process death and
  land in a new-chat composer.
- Deep links (`hermex-type-j://` + legacy `hermes-agent://`) for new chat,
  sessions, Kanban boards/cards, and Git workspaces, plus launcher shortcuts.
- Foreground run monitoring with private ongoing/completion notifications,
  cancel action, and status/replay recovery (opt-in, POST_NOTIFICATIONS).
- Encrypted per-origin custom request headers with masked editing, validation,
  cross-origin redirect stripping, and Keystore encryption at rest.
- Voice notes (native AAC recorder) with server transcription, and assistant
  Listen via server TTS with Android TextToSpeech fallback.
- Deterministic fuzz/property tests (hinge geometry, dynamic path encoding,
  deep-link parsing, tolerant decoding, speech normalization, stream reducers).

### Fixed
- Cold-start crash from a mis-packed Compose color built with `ULong` packing
  (`Invalid ID: 40` in the draw pass), found by surface testing.
- Android Lint errors and warnings resolved to zero (state lifetime, window
  metrics, Activity access, subcomposition, backup policy, resource qualifiers,
  Compose API shape, KTX calls).
- Kanban live-event cursor could regress to a stale snapshot value.
- Composer approval/clarification polling no longer runs while the chat screen
  is backgrounded (also fixes virtual-time test starvation).
- Rail-mode headers no longer render under the status bar; Git header and
  summary chips no longer wrap/clipped at pane widths.
- A session-opening destination is restored across compact↔expanded shell
  transitions instead of resetting to the list.

### Added
- Native Hermex-Type-J Android client built with Kotlin and Jetpack Compose, with feature
  parity for streaming chat, sessions/projects, model/profile/reasoning
  selection, editable scheduled tasks, skills, workspace files, memory,
  insights, settings, and offline cache.
- Adaptive phone, tablet, foldable, and Samsung Galaxy Trifold layouts:
  compact cover-screen navigation, two-pane tablet/fold layouts, and
  hinge-aligned three-pane sessions/transcript/composer and file-browser
  surfaces.
- Native file/image attachment picker, camera capture, voice dictation, slash
  command autocomplete, approval/clarification prompts, stop/steer, and SSE
  stream recovery on Android.

### Changed
- Reworked the repository presentation around the Android fork: clear fork
  rationale, complete feature map, architecture/security/build documentation,
  and real dark-mode captures for cover, phone, tablet, fold, half-fold, and
  trifold layouts.

## [1.5.0] - TBA

### Added
- Kanban boards: browse cards by status column, view card detail with comments
  and operational history, create and edit cards, move cards through their
  workflow, act on many cards at once with accessible bulk actions, manage
  boards with shared active-board controls, preview and run the dispatcher from
  a toolbar sheet, and stay current through live updates with offline
  reconciliation.
- Settings toggles to hide unused parts of the app: session-list entries
  (Tasks, Kanban, Skills, Memory, Insights, Active Profile, Projects) and chat
  controls (Files button, Git actions). Everything stays visible by default.
- Opt-in response speed metrics.
- Public open-source release of the Hermex codebase.

### Fixed
- Interrupted backend streams now recover instead of stalling the response.
- Opening a transcript no longer jitters.
- Reopening a chat bounds the cached transcript instead of loading it all.
- Deep-linked sessions no longer lose the race to last-session restore.
- The file browser keeps the latest directory navigation.
- The session list refreshes after returning from a new chat.
