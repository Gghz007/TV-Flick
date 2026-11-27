package app.tvflick

import app.tvflick.plugin.adblock.RuleBasedAdBlockPlugin
import app.tvflick.plugin.api.*
import app.tvflick.plugin.engine.PluginEngine
import app.tvflick.plugin.engine.PluginRegistry
import app.tvflick.ui.readAdBlockRulesBytes
import kotlin.test.*

class AdBlockWiringTest {
    @Test fun `production rules are per-site optional and cannot block primary video`() {
        val engine = PluginEngine(PluginRegistry(adBlockPlugins = listOf(RuleBasedAdBlockPlugin())))
        val request = VideoNetworkRequest("https://ad.doubleclick.net/slot", "site", VideoResourceKind.Redirect)
        assertIs<NetworkRequestDecision.Block>(engine.decideNetworkRequest(request, true))
        assertEquals(NetworkRequestDecision.Allow, engine.decideNetworkRequest(request, false))
        for (kind in listOf(VideoResourceKind.MediaManifest, VideoResourceKind.MediaSegment))
            assertEquals(NetworkRequestDecision.Allow, engine.decideNetworkRequest(request.copy(kind = kind), true))
    }

    @Test fun `local rules reader rejects excessive or invalid bytes`() {
        val data = "{\"revision\":2}".encodeToByteArray()
        assertEquals(data.decodeToString(), data.inputStream().use { readAdBlockRulesBytes(it::read) })
        assertFailsWith<IllegalArgumentException> {
            ByteArray(app.tvflick.plugin.adblock.MAX_ADBLOCK_RULE_BYTES + 1).inputStream().use { readAdBlockRulesBytes(it::read) }
        }
        assertFailsWith<Exception> {
            byteArrayOf(0xC3.toByte(), 0x28).inputStream().use { readAdBlockRulesBytes(it::read) }
        }
    }
}
