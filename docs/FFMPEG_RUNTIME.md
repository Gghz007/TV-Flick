# Desktop HLS runtime

TV Flick uses FFmpeg as a separate executable to remux downloaded HLS segments into MP4 without transcoding. It does not use a system FFmpeg installation or search PATH at runtime. Android keeps its external download-app flow.

- Distribution: FFmpeg-Builds **LGPL shared**, Windows x64, FFmpeg n8.1.3-36-ge22a4510c9.
- Build release: https://github.com/FFmpeg-Builds/FFmpeg-Builds/releases/tag/autobuild-2026-11-19-12-39
- Archive: `ffmpeg-n8.1.3-36-ge22a4510c9-win64-lgpl-shared-8.1.zip`.
- SHA-256: `ae947c6f7f87d19cf3de80e448409419a9e81a18987d55ead133c55c2db29e9c` (checked against the release's checksums.sha256).
- FFmpeg source: https://github.com/FFmpeg/FFmpeg/tree/e22a4510c9
- Build recipes/dependency sources: https://github.com/FFmpeg-Builds/FFmpeg-Builds/tree/autobuild-2026-11-19-12-39
- Upstream license information: https://ffmpeg.org/legal.html

The Gradle task `:composeApp:prepareDesktopMediaRuntime` verifies the archive, extracts the executable/shared libraries and supplied documentation/licenses into generated build resources. Compose copies these resources into the desktop distribution. Public release preparation must also retain the exact corresponding dependency sources/build recipes and review the included third-party notices; public distribution is not completed by this development setup.

Implemented download scope: finite, unencrypted MPEG-TS or fragmented MP4 HLS media playlists. HTTP(S) only; relative URLs use the final redirected manifest URL. Cookies/Authorization are removed when crossing origins. Only downloaded local files are handed to FFmpeg (`protocol_whitelist=file`); the original URLs and credentials never enter the child process arguments. Cancellation/errors clean temporary data; the destination is replaced only after successful assembly.

Master playlists with unresolved audio/quality, live playlists, encrypted segments and byte ranges currently report unsupported download. The resolver must return the selected media playlist rather than silently guessing a rendition. Download does not update Watched.

Native smoke test: set `TVFLICK_TEST_FFMPEG` to the prepared `bin/ffmpeg.exe`, then run `:composeApp:desktopTest --tests app.tvflick.DesktopHlsNativeTest`. It generates short synthetic TS/fMP4 inputs, serves their bytes through a mock HTTP transport, downloads/remuxes them and decodes the resulting video and audio. No application window or third-party media is opened.
