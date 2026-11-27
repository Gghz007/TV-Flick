# Windows portable library

Windows keeps the SQLite library beside its launcher:

```text
TV Flick/
  TV Flick.exe
  Local Database/
    TV Flick.db
    README.txt
  .tvflick-local-database-initialized
  .tvflick-local-database.lock
```

Close every running copy before moving, copying or deleting the data. Move the entire application folder to retain the library. SQLite may temporarily create `-wal`, `-shm` or journal companions; never copy only the main database while the application is running.

Delete `Local Database` to reset library contents, watched marks, progress and database settings. Keep the initialization marker beside the launcher: it prevents the old AppData library from being imported again. Resetting this folder does not delete Google backups or sign out of Google.

On the first portable launch, an existing `%LOCALAPPDATA%/TV Flick/tv-flick.db` is copied using a consistent SQLite snapshot, validated/migrated and installed without overwriting an existing portable database. The original is retained. Subsequent launches use only the portable database. Failures do not silently fall back to a separate AppData library.

The application folder must be writable. Place the portable application in a user-writable folder, not protected Program Files. During Gradle development runs, `Local Database` is at the project root. Packaged runs derive the root from the launcher or packaged JAR location, independent of shortcut working directory.

This change moves the library only. Google credentials and encrypted sign-in tokens remain in per-user AppData; moving to another computer requires signing in there. Auxiliary caches and recovery backups also retain their existing locations. Android storage is unchanged.

User acceptance: move the closed application folder, launch its EXE from a shortcut with a different working directory, and confirm the same library opens. The native packaged-launch acceptance is pending the final release build.
