package app.tvflick.plugin.adblock

import app.tvflick.plugin.api.NetworkRequestDecision
import app.tvflick.plugin.api.VideoNetworkRequest
import app.tvflick.plugin.api.VideoResourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith

class RuleBasedAdBlockPluginTest {
    private val plugin = RuleBasedAdBlockPlugin(blockedHostSuffixes = setOf("ads.example.test"))

    @Test
    fun `blocks a configured secondary ad request`() {
        val decision = plugin.decide(
            VideoNetworkRequest(
                url = "https://cdn.ads.example.test/slot.js",
                sourceId = "example",
                kind = VideoResourceKind.Ad,
            ),
        )

        assertIs<NetworkRequestDecision.Block>(decision)
    }

    @Test
    fun `never blocks a media manifest even when its host matches a rule`() {
        val decision = plugin.decide(
            VideoNetworkRequest(
                url = "https://cdn.ads.example.test/stream.m3u8",
                sourceId = "example",
                kind = VideoResourceKind.MediaManifest,
            ),
        )

        assertEquals(NetworkRequestDecision.Allow, decision)
    }

    @Test fun `URL matching separates host path and query and respects DNS boundaries`() {
        val rules = RuleBasedAdBlockPlugin(setOf("ads.example.test"), setOf("/advert/"))
        for (url in listOf("https://ads.example.test?slot=1", "https://CDN.ADS.EXAMPLE.TEST.:443/a",
            "https://site.test/advert/slot", "https://user@ads.example.test/a")) {
            assertIs<NetworkRequestDecision.Block>(rules.decide(VideoNetworkRequest(url, "site")))
        }
        for (url in listOf("https://notads.example.test/a", "https://ads.example.test.evil.test/a",
            "https://site.test/watch?next=/advert/", "https://ads.example.test@site.test/watch")) {
            assertEquals(NetworkRequestDecision.Allow, rules.decide(VideoNetworkRequest(url, "site")))
        }
    }

    @Test fun `rules update is validated before replacing active snapshot`() {
        val rules = RuleBasedAdBlockPlugin()
        val valid = """{"schemaVersion":1,"revision":2,"blockedHosts":["ads.example.test"],"sourceIds":["site-a"]}"""
        rules.installRules(valid)
        val request = VideoNetworkRequest("https://ads.example.test/a", "site-a", VideoResourceKind.Redirect)
        assertIs<NetworkRequestDecision.Block>(rules.decide(request))
        assertEquals(NetworkRequestDecision.Allow, rules.decide(request.copy(sourceId = "site-b")))
        for (kind in listOf(VideoResourceKind.MediaManifest, VideoResourceKind.MediaSegment))
            assertEquals(NetworkRequestDecision.Allow, rules.decide(request.copy(kind = kind)))
        for (bad in listOf(valid.replace("\"revision\":2", "\"revision\":1"),
            valid.replace("ads.example.test", "*.test"), valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            valid.dropLast(1) + ",\"script\":\"anything\"}", " ".repeat(MAX_ADBLOCK_RULE_BYTES + 1))) {
            assertFailsWith<IllegalArgumentException> { rules.installRules(bad) }
            assertEquals(2, rules.rulesRevision)
            assertIs<NetworkRequestDecision.Block>(rules.decide(request))
        }
    }
}
