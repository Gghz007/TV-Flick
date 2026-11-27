# Architecture overview

## Product boundary

TV Flick is a local-first movie and TV-show tracker. The user’s library, lists, settings, and watch progress belong to local storage. TMDB supplies external metadata and images; it does not own user data or provide video streams.

The core application must work without a video module, account system, cloud synchronization, or server.

## Layers

```text
Compose UI
    |
Application and domain
    |
Repositories
    |--------------------|
Local data             TMDB metadata
SQLDelight / SQLite    Ktor Client
```

Optional extensions, including a future Plugin Engine and Video Engine, sit behind contracts outside the core flow. They must not be prerequisites for library tracking.

## Core feature areas

- Library: films and shows saved by the user.
- Tracker: per-film watched state and per-episode progress.
- Lists: Watch List and Upcoming are distinct scenarios.
- Search: debounced TMDB search; detailed metadata is loaded only after an item is opened or explicitly needed.
- Profile: statistics, settings, lists, and TMDB attribution.
- Backup: portable export/import of user-owned local data.

## Data principles

- SQLDelight/SQLite stores user-owned data and the minimum metadata needed locally.
- Do not persist whole TMDB responses indiscriminately.
- Series progress is derived from episode states rather than kept as an independently editable aggregate.
- Network and image failures are ordinary UI states, never reasons to block local library access.

## Initial local schema

The first SQLDelight schema contains `content`, `library_entry`, `show_season`, `episode`, `episode_progress`, and `app_setting`. It holds user-owned library and progress data plus a minimal local metadata snapshot. Series progress remains derivable from `episode_progress`; it is not stored as a competing aggregate value.

## UI principles

- The Lunacy component library and reference frames are the visual source of truth.
- The app has two layout branches selected by available width: Mobile and PC/Tablet.
- PC/Tablet card grids use a deterministic maximum-fit calculation, center each row, preserve roughly 20 px minimum side space, and never show clipped cards.
- Common visual components are shared rather than recreated per screen.

## Android target

- The Android entry point is isolated in `androidApp`; `composeApp` remains the
  shared Compose Multiplatform UI and local-domain module used by Android and
  desktop.
- Minimum supported Android version is Android 8.0 (API 26).
- The project compiles against API 37. The Android module contains no Firebase,
  account, server, or TMDB key configuration.

## Deferred decisions

These are intentionally not decided in the initial project structure:

- Desktop minimum version
- Backup file schema and final extension
- TMDB key storage/distribution model
- License
- Plugin installation and sandboxing model
- Video Engine and AdBlock implementation
