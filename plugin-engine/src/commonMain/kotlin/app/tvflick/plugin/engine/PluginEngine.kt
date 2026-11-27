package app.tvflick.plugin.engine

import app.tvflick.plugin.api.AdBlockPlugin
import app.tvflick.plugin.api.NetworkRequestDecision
import app.tvflick.plugin.api.ResolvedVideoSource
import app.tvflick.plugin.api.TvFlickPlugin
import app.tvflick.plugin.api.VideoEngine
import app.tvflick.plugin.api.VideoEnginePlugin
import app.tvflick.plugin.api.VideoRequest
import app.tvflick.plugin.api.VideoNetworkRequest
import app.tvflick.plugin.api.VideoSourcePlugin
import app.tvflick.plugin.api.SourceRequestContext
import app.tvflick.plugin.api.VideoResourceKind
import app.tvflick.plugin.api.TV_FLICK_PLUGIN_API_VERSION

/**
 * Process-local registry for built-in modules and future forks. It deliberately
 * loads compiled-in plugins only: Android cannot safely load arbitrary code at
 * runtime, and TV Flick has no user-facing module toggles.
 */
class PluginRegistry(
    videoSources: Iterable<VideoSourcePlugin> = emptyList(),
    adBlockPlugins: Iterable<AdBlockPlugin> = emptyList(),
    videoEnginePlugin: VideoEnginePlugin? = null,
) {
    private val videoSources = videoSources.onEach(::checkCompatibility).associateByTo(linkedMapOf(), TvFlickPlugin::id)
    private val adBlockPlugins = adBlockPlugins.onEach(::checkCompatibility).associateByTo(linkedMapOf(), TvFlickPlugin::id)
    private var videoEnginePlugin = videoEnginePlugin
    init { videoEnginePlugin?.let(::checkCompatibility) }

    private fun checkCompatibility(plugin: TvFlickPlugin) {
        require(plugin.id.isNotBlank() && plugin.version.isNotBlank()) { "Invalid plugin identity" }
        require(plugin.apiVersion == TV_FLICK_PLUGIN_API_VERSION) { "Unsupported plugin API version: ${plugin.id}" }
    }

    fun compatibleSources(baseUrl: String): List<String> = videoSources.values.filter { it.supports(baseUrl) }.map { it.id }

    fun register(plugin: VideoSourcePlugin) {
        checkCompatibility(plugin)
        videoSources[plugin.id] = plugin
    }

    fun register(plugin: AdBlockPlugin) {
        checkCompatibility(plugin)
        adBlockPlugins[plugin.id] = plugin
    }

    fun register(plugin: VideoEnginePlugin) {
        checkCompatibility(plugin)
        videoEnginePlugin = plugin
    }

    internal fun source(id: String): VideoSourcePlugin = videoSources[id]
        ?: error("Video Source Plugin '$id' is not registered.")

    internal fun enabledAdBlockPlugins(): List<AdBlockPlugin> = adBlockPlugins.values.toList()

    internal fun videoEngine(): VideoEnginePlugin = checkNotNull(videoEnginePlugin) {
        "No Video Engine Plugin is registered."
    }
}

/**
 * The Core asks this class to open content; it never imports player-SDK code.
 * Source resolution and request filtering occur before a player receives a URL.
 */
class PluginEngine(private val registry: PluginRegistry) {
    /**
     * Resolve/filter before the composition-owned player opens a URL. A caller
     * obtains the engine from [videoEnginePlugin] in the same Composition.
     */
    suspend fun openRequest(
        engine: VideoEngine,
        request: VideoRequest,
        startPositionMs: Long = 0,
    ) {
        engine.open(resolveVerified(request), startPositionMs.coerceAtLeast(0))
    }

    suspend fun discover(request: VideoRequest): MediaMatchResult {
        val query = requireNotNull(request.mediaMatch) { "Content identity is required" }
        val plugin = checkedSource(request)
        return MediaCandidateMatcher.evaluate(query, plugin.search(request, context(request)))
    }

    /** Used only after a unique discovery result or the user's explicit candidate choice. */
    suspend fun resolveVerified(request: VideoRequest): ResolvedVideoSource {
        val query = requireNotNull(request.mediaMatch) { "Content identity is required" }
        val plugin = checkedSource(request)
        require(request.pageUrl.isNotBlank()) { "Select a source result first" }
        val context = context(request)
        val identity = requireNotNull(plugin.inspect(request, context)) { "Source did not confirm content identity" }
        require(identity.pageUrl == request.pageUrl && MediaCandidateMatcher.score(query, identity) != null) {
            "Source content does not match the requested movie or episode"
        }
        val source = plugin.resolve(request, context).copy(sourceId = request.sourceSiteId)
        require(source.url.startsWith("https://") || source.url.startsWith("http://")) { "Unsupported stream URL" }
        return prepare(source, request.adblockEnabled)
    }

    private fun checkedSource(request: VideoRequest): VideoSourcePlugin {
        require(request.contentId > 0 && request.sourceSiteId.isNotBlank()) { "Local content and source identities are required" }
        return registry.source(request.sourcePluginId).also {
            require(it.supports(request.sourceSiteBaseUrl)) { "Plugin does not support this source site" }
        }
    }

    private fun context(request: VideoRequest) = SourceRequestContext { network ->
        decideNetworkRequest(network.copy(sourceId = request.sourceSiteId), request.adblockEnabled)
    }

    fun videoEnginePlugin(): VideoEnginePlugin = registry.videoEngine()

    fun prepare(source: ResolvedVideoSource, adblockEnabled: Boolean = true): ResolvedVideoSource =
        (if (adblockEnabled) registry.enabledAdBlockPlugins() else emptyList())
        .filter { it.supports(source) }
        .fold(source) { current, plugin -> plugin.filter(current) }

    /**
     * Source/Resolver/WebView hosts call this for secondary requests. The
     * first blocking module wins; no module can block primary media by policy.
     */
    fun decideNetworkRequest(request: VideoNetworkRequest, adblockEnabled: Boolean = true): NetworkRequestDecision {
        if (!adblockEnabled || request.kind == VideoResourceKind.MediaManifest || request.kind == VideoResourceKind.MediaSegment)
            return NetworkRequestDecision.Allow
        return registry.enabledAdBlockPlugins()
            .asSequence()
            .map { it.decide(request) }
            .firstOrNull { it is NetworkRequestDecision.Block }
            ?: NetworkRequestDecision.Allow
    }
}
