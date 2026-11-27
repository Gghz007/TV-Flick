package app.tvflick

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.tvflick.core.database.TvFlickDatabase
import app.tvflick.core.library.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class SourceSettingsTest {
    @Test
    fun `site addresses normalize without fetching or losing the site path`() {
        assertEquals(SourceAddress("https://example.com", "example.com"), normalizeSourceAddress(" EXAMPLE.com/ "))
        assertEquals("https://example.com/catalog", normalizeSourceAddress("https://EXAMPLE.com:443/catalog/").url)
        assertEquals("http://localhost:8080/video", normalizeSourceAddress("http://localhost:8080/video").url)
        assertEquals("https://[::1]:8443", normalizeSourceAddress("https://[::1]:8443/").url)
    }

    @Test
    fun `invalid addresses and credentials are not stored as sources`() {
        listOf("", "not a site", "https://", "https:///catalog", "ftp://example.com", "file:///C:/video",
            "https://user:password@example.com", "https://example.com?token=secret",
            "https://example.com#section", "https://example.com:99999", "example.com\\path").forEach {
            assertFails("Accepted invalid source: $it") { normalizeSourceAddress(it) }
        }
    }

    @Test
    fun `duplicate site keeps previous source and does not create playback or library entries`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            TvFlickDatabase.Schema.create(driver)
            val repository = SqlDelightLibraryRepository(TvFlickDatabase(driver))
            val settings = VideoSourceSettings(repository)
            val site = settings.add("example.com")
            assertEquals(app.tvflick.plugin.api.ADAPTIVE_SOURCE_PLUGIN_ID, site.parserPluginId)
            assertTrue(site.isEnabled)
            assertFailsWith<IllegalArgumentException> { settings.add("https://EXAMPLE.com:443/") }
            assertEquals(listOf(site), repository.listVideoSourceSites())
            assertTrue(repository.listItems().isEmpty())
        } finally { driver.close() }
    }

    @Test
    fun `source and adblock settings are independent and survive database reopen`() = runBlocking {
        val file = Files.createTempFile("tv-flick-sources-", ".db").toFile()
        var driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath.replace('\\', '/')}")
        try {
            TvFlickDatabase.Schema.create(driver)
            var repository = SqlDelightLibraryRepository(TvFlickDatabase(driver))
            val first = VideoSourceSettings(repository).add("one.example")
            val second = VideoSourceSettings(repository).add("two.example")
            val assigned = repository.saveVideoSourceSite(VideoSourceSiteDraft(first.id, first.displayName,
                first.baseUrl, "test-source-plugin"))
            repository.setSourceAdblockEnabled(first.id, false)
            VideoSourceSettings(repository).setEnabled(assigned, false)
            driver.close()
            driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath.replace('\\', '/')}")
            repository = SqlDelightLibraryRepository(TvFlickDatabase(driver))
            val restored = repository.listVideoSourceSites().first { it.id == first.id }
            assertFalse(restored.isEnabled)
            assertEquals("test-source-plugin", restored.parserPluginId)
            assertEquals(false, repository.listSourceAdblockSettings()[first.id])
            assertTrue(repository.listSourceAdblockSettings()[second.id] ?: true)
            assertTrue(repository.listVideoSourceSites().first { it.id == second.id }.isEnabled)
            VideoSourceSettings(repository).setEnabled(restored, true)
            assertEquals(false, repository.listSourceAdblockSettings()[first.id])
        } finally { driver.close(); file.delete() }
    }

    @Test
    fun `removing a source cleans only its preferences and unknown source cannot acquire preferences`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            TvFlickDatabase.Schema.create(driver)
            val repository = SqlDelightLibraryRepository(TvFlickDatabase(driver))
            val settings = VideoSourceSettings(repository)
            val first = settings.add("one.example")
            val second = settings.add("two.example")
            repository.setSourceAdblockEnabled(first.id, false)
            repository.setSourceAdblockEnabled(second.id, false)
            repository.removeVideoSourceSite(first.id)
            assertEquals(mapOf(second.id to false), repository.listSourceAdblockSettings())
            assertEquals(listOf(second.id), repository.listVideoSourceSites().map { it.id })
            assertFailsWith<IllegalArgumentException> { repository.setSourceAdblockEnabled("missing", true) }
            Unit
        } finally { driver.close() }
    }
}
