package app.tvflick.plugin.adblock

import app.tvflick.plugin.api.AdBlockPlugin
import app.tvflick.plugin.api.NetworkRequestDecision
import app.tvflick.plugin.api.ResolvedVideoSource
import app.tvflick.plugin.api.VideoNetworkRequest
import app.tvflick.plugin.api.VideoResourceKind
import io.ktor.http.Url
import io.ktor.http.URLProtocol
import kotlin.concurrent.Volatile

/** Default safe plugin. Filtering rules will be supplied by an independently versioned ruleset. */
class NoOpAdBlockPlugin : AdBlockPlugin {
    override val id = "tvflick.adblock"
    override val version = "0.1.0"
    override fun filter(source: ResolvedVideoSource) = source
}

/**
 * Compiled rule interpreter with separately replaceable data. Primary media
 * is always allowed to prevent playback breaks. No source page scripts run.
 */
class RuleBasedAdBlockPlugin(
    blockedHostSuffixes: Set<String> = AdBlockRules.Bundled.blockedHosts,
    blockedPathFragments: Set<String> = emptySet(),
    supportedSourceIds: Set<String> = emptySet(),
) : AdBlockPlugin {
    override val id = "tvflick.adblock.rules"
    override val version = "0.2.0"
    @Volatile private var rules = AdBlockRules(1, blockedHostSuffixes.map { it.lowercase().trim('.') }.toSet(),
        blockedPathFragments.toSet(), supportedSourceIds.toSet())
    val rulesRevision: Int get() = rules.revision

    /** Startup-only install. A rejected document leaves the active immutable snapshot intact. */
    fun installRules(text: String) {
        val replacement = AdBlockRules.parse(text)
        val current = rules
        require(replacement.revision > current.revision || replacement == current) { "Rules revision must increase" }
        rules = replacement
    }

    override fun supports(source: ResolvedVideoSource): Boolean =
        rules.let { it.sourceIds.isEmpty() || source.sourceId in it.sourceIds }

    override fun filter(source: ResolvedVideoSource): ResolvedVideoSource = source

    override fun decide(request: VideoNetworkRequest): NetworkRequestDecision {
        if (request.kind == VideoResourceKind.MediaManifest || request.kind == VideoResourceKind.MediaSegment) {
            return NetworkRequestDecision.Allow
        }
        val snapshot = rules
        if (snapshot.sourceIds.isNotEmpty() && request.sourceId !in snapshot.sourceIds) {
            return NetworkRequestDecision.Allow
        }
        val url = runCatching { Url(request.url) }.getOrNull() ?: return NetworkRequestDecision.Allow
        if (url.protocol != URLProtocol.HTTP && url.protocol != URLProtocol.HTTPS) return NetworkRequestDecision.Allow
        val host = url.host.lowercase().trimEnd('.')
        val matchingHost = snapshot.blockedHosts.any { suffix ->
            host == suffix || host.endsWith(".$suffix")
        }
        val matchingPath = snapshot.blockedPaths.any { fragment ->
            fragment.isNotBlank() && url.encodedPath.contains(fragment)
        }
        return if (matchingHost || matchingPath) {
            NetworkRequestDecision.Block("Matched the configured TV Flick AdBlock ruleset")
        } else {
            NetworkRequestDecision.Allow
        }
    }
}
