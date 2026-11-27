package app.tvflick

import app.tvflick.plugin.api.*
import app.tvflick.ui.DownloadChoices
import kotlin.test.*

class DownloadChoicesTest {
    @Test fun `quality is limited to chosen audio and retains its URL and headers`() {
        val source = ResolvedVideoSource("https://test/a720", StreamKind.Hls, sourceId = "test",
            variants = listOf(
                ResolvedStreamVariant("https://test/a720", StreamKind.Hls, "a", "720", mapOf("Referer" to "a")),
                ResolvedStreamVariant("https://test/a1080", StreamKind.Hls, "a", "1080"),
                ResolvedStreamVariant("https://test/b480", StreamKind.Mp4, "b", "480", mapOf("Referer" to "b"))))
        val choices = DownloadChoices(source, "b")
        assertEquals("b", choices.preferredAudio)
        assertEquals(listOf("480"), choices.qualities("b").map { it.id })
        assertNull(choices.resolve("b", "1080"))
        val selected = assertNotNull(choices.resolve("b", "480"))
        assertEquals("https://test/b480", selected.url)
        assertEquals(StreamKind.Mp4, selected.kind)
        assertEquals(mapOf("Referer" to "b"), selected.headers)
        assertEquals("a", DownloadChoices(source, null).preferredAudio)
    }
}
