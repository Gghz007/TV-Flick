package app.tvflick.plugin.source

import app.tvflick.plugin.api.*

/** The only source ID Core needs: protocol handlers are chosen automatically. */
class AdaptiveSourcePlugin(
    private val specialized: List<VideoSourcePlugin> = listOf(FilmixSourcePlugin()),
    private val standard: VideoSourcePlugin = StructuredWebSource(),
    private val hls: HlsCatalogResolver = HlsCatalogResolver(),
) : VideoSourcePlugin, AutoCloseable {
    override val id = ID
    override val version = "0.1.0"
    override fun supports(baseUrl: String) = webUrl(baseUrl)
    private fun handler(request: VideoRequest): Pair<VideoSourcePlugin, VideoRequest> {
        require(request.sourcePluginId == id && supports(request.sourceSiteBaseUrl))
        val handler = specialized.firstOrNull { it.supports(request.sourceSiteBaseUrl) } ?: standard
        return handler to request.copy(sourcePluginId = handler.id)
    }
    override suspend fun search(request: VideoRequest, context: SourceRequestContext): List<ParsedMediaCandidate> {
        val (handler, adapted) = handler(request)
        return handler.search(adapted, context)
    }
    override suspend fun inspect(request: VideoRequest, context: SourceRequestContext): ParsedMediaCandidate? {
        val (handler, adapted) = handler(request)
        return handler.inspect(adapted, context)
    }
    override suspend fun resolve(request: VideoRequest): ResolvedVideoSource = throw SourceFailure(SourceFailureReason.RequestBlocked)
    override suspend fun resolve(request: VideoRequest, context: SourceRequestContext): ResolvedVideoSource {
        val (handler, adapted) = handler(request)
        return hls.expand(handler.resolve(adapted, context), request, context)
    }
    override fun close() {
        (specialized + standard).distinct().forEach { (it as? AutoCloseable)?.close() }
        hls.close()
    }
    companion object { const val ID = ADAPTIVE_SOURCE_PLUGIN_ID }
}
