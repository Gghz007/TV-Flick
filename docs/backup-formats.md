# Backup formats

As of 2026-11-14 exports use version 2, including full release dates, alternative titles and genres. Import accepts both v1 and v2; missing v1 metadata fields receive empty defaults. SQLite migration 3→4 preserves existing library/watch data. Older app builds that only support v1 cannot import a new v2 backup.

- `.tvbackup`: portable TV Flick library, series/seasons/episodes, watched flags, resume positions, favorites and source/settings data. Restores through IMPORT or Google RESTORE. OAuth tokens and TMDB credentials are excluded.
- JSON: the same versioned data with readable formatting. Can be restored by TV Flick; useful for external tools and AI.
- CSV: UTF-8 BOM, quoted cells and an explicit row for each movie, series and episode. Contains TMDB identity, season/episode, watched/favorite/watch-list flags, resume position and poster URL. Spreadsheet formula prefixes are escaped. Readable list export only; no TV Flick restore from CSV.
- PDF: searchable text list with optional poster images. Readable list export only; no TV Flick restore from PDF. Windows embeds the supplied Inter font. Android uses its system sans-serif font. Characters absent from the Windows font are replaced with `?`; JSON/CSV preserve the full Unicode text.

Image includes library title posters and backdrops from TMDB, not cast photos or episode stills. `.tvbackup` and JSON embed the images; restored artwork is persisted locally and used offline. PDF displays available posters. CSV always contains URLs and its Image switch is inactive. Image export requires internet for artwork not previously restored. Images are limited to 2 MiB each and 20 MiB total; restorable files have a 32 MiB limit. The export reports an error rather than silently dropping images.

Before restore, TV Flick validates the file and saves the current library to `backup-recovery.tvbackup` in its application data directory. SQLite replacement is transactional. AutoExport is disabled after restore to prevent an imported file from enabling cloud uploads implicitly.

Google always stores `.tvbackup` in its private application folder. The local format picker does not change that. AutoExport is opt-in and checked once at app startup, at intervals of 1, 7 or 30 days since the last successful cloud export. No background OS scheduler is installed.

The AutoExport switch can be enabled before Google setup/sign-in: the preference persists, but uploads wait for a connected account and a subsequent app startup. First sign-in preserves this choice. Signing out, changing to a different previously associated account, or restoring a backup disables automatic uploads; the user can enable them again. An unavailable Google connection leaves the schedule unchanged.

Windows PDF generation uses Apache PDFBox 3.0.8 (Apache License 2.0); its dependency jars retain their license/notice resources. See [PDFBox](https://pdfbox.apache.org/).
