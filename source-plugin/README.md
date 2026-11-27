# TV Flick adaptive source engine

Register `AdaptiveSourcePlugin` as the single automatic source entry point (Plugin API v1). Core uses `tvflick.source.adaptive` for new sources and legacy records without a parser ID. Users do not select parsers. The dispatcher chooses a known internal strategy or `StructuredWebSource` for other HTTP(S) domains. Instantiate it once per app session and close it on shutdown. Use PluginEngine's contextual discovery/resolve path; context-free resolution refuses network access.

StructuredWebSource reads declared [Movie](https://schema.org/Movie) / [TVEpisode](https://schema.org/TVEpisode) identities, [SearchAction](https://schema.org/SearchAction) or GET search forms, and full-content `encoding` / [VideoObject.contentUrl](https://schema.org/VideoObject) relationships. It also reads HTML5 video/source explicitly marked as an encoding in Microdata. It does not guess identity from a bare video URL, treat trailers as films, execute arbitrary page scripts, or claim support for every iframe player. Parsing on two unrelated domains is covered by synthetic tests; other real generic sites are not yet live-verified.

`FilmixSourcePlugin` remains an automatically selected internal specialization for its site-specific serialization. All strategies are independent of SQLite/TMDB/watch storage. Adding a standard-conforming domain does not require another hardcoded adapter.

composeApp registers one AdaptiveSourcePlugin per repository lifetime, closes its HTTP clients on composition disposal, and exposes it through Core's PlaybackCoordinator. The coordinator searches enabled Database sites with at most two concurrent requests, preserves ambiguous choices, reinspects the selected content and binds the resolved stream to a typed local progress sink. ParamSelect now invokes this flow from cards/episodes; ambiguous results use a source-choice popup and verified results reach the Windows/Android player hosts. Play&Mark is a persistent, reversible primary-action preference. Download uses the same identity verification but never the progress sink. UI wiring compiles; real end-to-end playback/device acceptance is still pending.

## Verified scope

After verified resolution, AdaptiveSourcePlugin enriches muxed HLS master playlists into actual resolution/bitrate variants while retaining the source's audio identity. Parsing follows the attributes and relative-URI rules of [RFC 8216](https://www.rfc-editor.org/rfc/rfc8216). Independent AUDIO/VIDEO/SUBTITLES groups, session keys, variables and malformed catalogs are retained as original streams, not flattened into video-only URLs. This is not yet separate-rendition track selection. Catalog enrichment has a 1 MiB response cap, three concurrent requests and an eight-second total budget; policy blocks and parent cancellation propagate. Redirects and children pass the site's SourceRequestContext; private headers are stripped across origins. Synthetic coverage includes two audio catalogs, redirects, actual quality choices, fallback and blocked/cancelled requests.

Guest title search with year/type checks; independent detail identity; exact series playlist entries; MP4/HLS variant parsing; actual quality/audio choices; source headers; bounded responses/timeouts; per-site filtering before requests and redirects. A short-lived inspect result is consumed by resolve for the same complete request only. Runtime URLs are never written to backups.

Only the currently observed public Filmix data serialization is implemented. Unknown versions, missing episodes, contradictory identifiers, rights restrictions, non-source playlist redirects and errors fail explicitly. No challenge, login or rights restriction bypass is provided. Site changes may require a new module release.

Run normal tests with `:source-plugin:desktopTest`. The optional `:source-plugin:probeFilmix` performs explicit live requests for a film and S02E05, then HEAD on the selected stream. It never runs as part of normal tests, never plays video, and prints no signed URLs/cookies. It is not evidence of end-to-end player support.

## Dependency provenance

HTML parsing uses [jsoup 1.24.1](https://jsoup.org/download), under the [MIT license](https://jsoup.org/license). Kotlin, Ktor and kotlinx.serialization use the project's existing dependency versions. Include dependency license notices in final packaging (step 14). The public site's PlayerJS implementation is inspected only to understand serialization and is not included in application sources or binaries.
