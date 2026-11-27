package app.tvflick.desktop

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

class DesktopLocalDatabaseTest {
    @Test fun `launcher path wins over working and development directory after moving app`() {
        val root = Files.createTempDirectory("portable-path-").toFile()
        try {
            val executable = File(root, "Moved App/TV Flick.exe")
            assertEquals(executable.parentFile.canonicalFile,
                DesktopLocalDatabase.applicationDirectory(executable.path, root, "unrelated"))
        } finally { root.deleteRecursively() }
    }

    @Test fun `legacy WAL snapshot survives move and reset never resurrects old library`() {
        val root = Files.createTempDirectory("portable-db-").toFile()
        try {
            val legacy = File(root, "legacy.db")
            DesktopLibraryDatabase.open(legacy).close()
            DriverManager.getConnection("jdbc:sqlite:${legacy.path}").use { connection ->
                connection.createStatement().use { sql ->
                    sql.execute("PRAGMA journal_mode=WAL")
                    sql.execute("CREATE TABLE portable_probe (value TEXT)")
                    sql.execute("INSERT INTO portable_probe VALUES ('preserve me')")
                }
                val application = File(root, "App").apply { mkdir() }
                val portable = DesktopLocalDatabase.prepare(application, legacy)
                assertEquals("preserve me", readProbe(portable))
                assertEquals("preserve me", readProbe(legacy))
                assertEquals(portable, DesktopLocalDatabase.prepare(application, legacy))
                val moved = File(root, "Moved App")
                Files.move(application.toPath(), moved.toPath())
                val relocated = DesktopLocalDatabase.prepare(moved, legacy)
                assertEquals("preserve me", readProbe(relocated))
                relocated.parentFile.deleteRecursively()
                val reset = DesktopLocalDatabase.prepare(moved, legacy)
                assertFalse(reset.exists(), "Reset must not reimport the legacy library")
                DesktopLibraryDatabase.open(reset).close()
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun `existing portable database is never replaced by legacy data`() {
        val root = Files.createTempDirectory("portable-existing-").toFile()
        try {
            val portable = File(root, "Local Database/TV Flick.db")
            portable.parentFile.mkdirs()
            portable.writeText("existing database must remain untouched")
            val legacy = File(root, "legacy.db").apply { writeText("invalid legacy") }
            DesktopLocalDatabase.prepare(root, legacy)
            assertEquals("existing database must remain untouched", portable.readText())
        } finally { root.deleteRecursively() }
    }

    private fun readProbe(file: File): String =
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT value FROM portable_probe").use { result ->
                    check(result.next()); result.getString(1)
                }
            }
        }
}
