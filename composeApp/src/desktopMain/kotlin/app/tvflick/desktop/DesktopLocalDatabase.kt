package app.tvflick.desktop

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.sql.DriverManager

/** Portable user library. Authentication tokens deliberately remain in per-user storage. */
internal object DesktopLocalDatabase {
    fun applicationDirectory(
        launcher: String? = System.getProperty("jpackage.app-path"),
        codeLocation: File = File(DesktopLocalDatabase::class.java.protectionDomain.codeSource.location.toURI()),
        developmentRoot: String? = System.getProperty("tvflick.development.root"),
    ): File = when {
        !launcher.isNullOrBlank() -> File(launcher).absoluteFile.parentFile
        // Windows jpackage layout: TV Flick.exe, app/*.jar, runtime/.
        codeLocation.isFile && codeLocation.parentFile.name == "app" -> codeLocation.parentFile.parentFile
        codeLocation.isFile -> codeLocation.absoluteFile.parentFile
        !developmentRoot.isNullOrBlank() -> File(developmentRoot)
        else -> error("Cannot locate the TV Flick application directory")
    }.canonicalFile

    fun prepare(
        root: File = applicationDirectory(),
        legacy: File = File(System.getenv("LOCALAPPDATA")
            ?: File(System.getProperty("user.home"), "AppData/Local").path, "TV Flick/tv-flick.db"),
    ): File {
        // Keep the marker outside Local Database: deleting the folder intentionally resets the library.
        FileChannel.open(File(root, ".tvflick-local-database.lock").toPath(),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val directory = File(root, "Local Database")
                Files.createDirectories(directory.toPath())
                val database = File(directory, "TV Flick.db")
                val marker = File(root, ".tvflick-local-database-initialized")
                if (!marker.exists() && !database.exists() && legacy.isFile) {
                    val staging = Files.createTempFile(directory.toPath(), "migration-", ".db")
                    try {
                        // SQLite takes a consistent snapshot, including committed WAL data.
                        DriverManager.getConnection("jdbc:sqlite:${legacy.absolutePath.replace('\\', '/')}").use { connection ->
                            connection.createStatement().use { it.execute("PRAGMA busy_timeout=5000") }
                            connection.prepareStatement("VACUUM INTO ?").use { statement ->
                                statement.setString(1, staging.toString())
                                statement.execute()
                            }
                        }
                        DesktopLibraryDatabase.open(staging.toFile()).close()
                        Files.move(staging, database.toPath()) // Never replace an existing portable library.
                    } finally { Files.deleteIfExists(staging) }
                }
                File(directory, "README.txt").writeText(
                    "TV Flick — local library / локальная база\n\n" +
                    "TV Flick.db contains your library, watched marks, playback progress and settings.\n" +
                    "Close TV Flick before moving, copying or deleting this folder.\n" +
                    "Move the entire application folder to keep your library.\n" +
                    "Delete Local Database to reset the local library. Google backups are not deleted.\n\n" +
                    "TV Flick.db: библиотека, отметки просмотра, прогресс и настройки.\n" +
                    "Закройте TV Flick перед переносом или удалением этой папки.\n" +
                    "Переносите всю папку приложения вместе с Local Database.\n" +
                    "Удаление Local Database сбрасывает библиотеку, но не удаляет бекапы Google.\n",
                )
                if (!marker.exists()) marker.writeText("Portable library initialized. Do not delete this marker when resetting the library.\n")
                return database
            }
        }
    }
}
