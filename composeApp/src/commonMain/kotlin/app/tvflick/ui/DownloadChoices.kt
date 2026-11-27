package app.tvflick.ui

import app.tvflick.plugin.api.PlaybackOption
import app.tvflick.plugin.api.ResolvedVideoSource

/** Audio is selected first; a quality choice must belong to that exact track. */
internal class DownloadChoices(private val source: ResolvedVideoSource, preferredAudioId: String?) {
    val audio = source.variants.distinctBy { it.audioId }.map {
        PlaybackOption(it.audioId, source.audioTrackOptions.find { option -> option.id == it.audioId }?.label
            ?: it.audioId.ifBlank { "Source" })
    }
    val preferredAudio = (preferredAudioId ?: source.variants.find { it.url == source.url }?.audioId)
        ?.takeIf { id -> audio.any { it.id == id } } ?: audio.firstOrNull()?.id

    fun qualities(audioId: String): List<PlaybackOption> = source.variants.filter { it.audioId == audioId }
        .distinctBy { it.qualityId }.map {
            PlaybackOption(it.qualityId, source.qualityOptions.find { option -> option.id == it.qualityId }?.label
                ?: it.qualityId.ifBlank { "Source" })
        }.let { if (source.variants.isEmpty()) listOf(PlaybackOption("", "Source")) else it }

    fun resolve(audioId: String, qualityId: String): ResolvedVideoSource? {
        if (source.variants.isEmpty()) return source.takeIf { audioId.isEmpty() && qualityId.isEmpty() }
        val variant = source.variants.find { it.audioId == audioId && it.qualityId == qualityId } ?: return null
        return source.copy(url = variant.url, kind = variant.kind, headers = variant.headers)
    }
}
