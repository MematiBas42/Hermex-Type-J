# Changelog

Notable changes to Hermex. Version headings correspond to App Store releases;
unreleased changes accumulate at the top. Format follows
[Keep a Changelog](https://keepachangelog.com/) with Added / Changed / Fixed /
Security sections per release.

## [Unreleased]

### Added
- Native Android client built with Kotlin and Jetpack Compose, with feature
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
