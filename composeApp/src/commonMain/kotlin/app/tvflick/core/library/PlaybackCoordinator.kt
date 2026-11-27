package app.tvflick.core.library

import app.tvflick.plugin.api.*
import app.tvflick.plugin.engine.MediaMatchResult
import app.tvflick.plugin.engine.PluginEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** A choice belongs to one discovery, never a caller-supplied playback URL. */
class PlaybackChoice internal constructor(
    val sourceName: String,
    val candidate: ParsedMediaCandidate,
    val requiresConfirmation: Boolean,
    internal val local: LocalPlaybackRequest,
) {
    val sourceId: String get() = local.request.sourceSiteId
}

data class SourceLookupFailure(val sourceId: String, val reason: SourceFailureReason)

class PlaybackDiscovery internal constructor(
    val choices: List<PlaybackChoice>,
    val failures: List<SourceLookupFailure>,
    internal val owner: Any,
    internal val generation: Long,
    internal val contentId: Long,
    internal val episodeId: Long?,
) {
    // Multiple sources/results require an explicit choice, not first-result autoplay.
    val automaticChoice: PlaybackChoice? get() = choices.singleOrNull()?.takeUnless { it.requiresConfirmation }
}

class PreparedPlayback internal constructor(
    val target: PlaybackTarget,
    val source: ResolvedVideoSource,
    val resumePositionMs: Long,
    val progressSink: PlaybackProgressSink,
)

class PlaybackSelectionExpired : Exception("Playback selection expired")

/**
 * Database -> discovery -> verified stream -> typed local progress sink.
 * Neither source IDs nor page URLs can become library/episode IDs.
 * The host must invalidate before replacing the database, then discard its player UI.
 */
class PlaybackCoordinator(
    private val library: LibraryRepository,
    private val sites: VideoSourceSiteRepository,
    private val engine: PluginEngine,
    private val lookupTimeoutMs: Long = 45_000,
) {
    private val factory = PlaybackRequestFactory(library, sites)
    private val owner = Any()
    private val gate = Mutex()
    private val networkSlots = Semaphore(2)
    private var generation = 0L

    /** Waits for any in-flight progress write before the host starts a restore. */
    suspend fun invalidate() = gate.withLock { generation++ }

    suspend fun discover(contentId: Long, episodeId: Long? = null, sourceId: String? = null): PlaybackDiscovery {
        val (version, enabled) = gate.withLock {
            generation to sites.listVideoSourceSites().filter { it.isEnabled && (sourceId == null || it.id == sourceId) }
        }
        val results = coroutineScope {
            enabled.map { site -> async {
                networkSlots.withPermit {
                    try {
                        withTimeoutOrNull(lookupTimeoutMs) {
                            val local = gate.withLock {
                                checkGeneration(version)
                                factory.create(contentId, site.id, episodeId)
                            }
                            val match = engine.discover(local.request)
                            gate.withLock { validateRequest(version, contentId, episodeId, local) }
                            when (match) {
                                is MediaMatchResult.Confirmed -> Lookup(listOf(PlaybackChoice(site.displayName, match.candidate, false, local)))
                                is MediaMatchResult.Ambiguous -> Lookup(match.candidates.map { PlaybackChoice(site.displayName, it, true, local) })
                                MediaMatchResult.NotFound -> Lookup(failure = SourceLookupFailure(site.id, SourceFailureReason.NotFound))
                            }
                        } ?: Lookup(failure = SourceLookupFailure(site.id, SourceFailureReason.Unavailable))
                    } catch (e: CancellationException) { throw e }
                    catch (e: PlaybackSelectionExpired) { throw e }
                    catch (e: SourceFailure) { Lookup(failure = SourceLookupFailure(site.id, e.reason)) }
                    catch (_: Exception) { Lookup(failure = SourceLookupFailure(site.id, SourceFailureReason.Unavailable)) }
                }
            } }.awaitAll()
        }
        return gate.withLock {
            checkGeneration(version)
            PlaybackDiscovery(results.flatMap { it.choices }, results.mapNotNull { it.failure }, owner, version, contentId, episodeId)
        }
    }

    /** Called only for automaticChoice or after the user selects one of discovery.choices. */
    suspend fun prepare(discovery: PlaybackDiscovery, choice: PlaybackChoice): PreparedPlayback {
        if (discovery.owner !== owner || discovery.choices.none { it === choice }) throw PlaybackSelectionExpired()
        gate.withLock { validateRequest(discovery.generation, discovery.contentId, discovery.episodeId, choice.local) }
        // Reinspect identity even after a user choice. Choosing a row cannot override a mismatch.
        val source = networkSlots.withPermit {
            withTimeoutOrNull(lookupTimeoutMs) {
                engine.resolveVerified(choice.local.request.copy(pageUrl = choice.candidate.pageUrl))
            } ?: throw SourceFailure(SourceFailureReason.Unavailable)
        }
        return gate.withLock {
            // Source/AdBlock settings and library identity may change while the network is suspended.
            val fresh = validateRequest(discovery.generation, discovery.contentId, discovery.episodeId, choice.local)
            val sink = RepositoryPlaybackProgressSink(library, fresh.target)
            PreparedPlayback(fresh.target, source, fresh.resumePositionMs, PlaybackProgressSink { snapshot ->
                gate.withLock {
                    if (generation != discovery.generation || !sameLibraryTarget(discovery, fresh)) return@withLock
                    sink.save(snapshot)
                }
            })
        }
    }

    private fun checkGeneration(expected: Long) {
        if (generation != expected) throw PlaybackSelectionExpired()
    }

    /** gate must be held. Resume is deliberately refreshed, not compared to an old position. */
    private suspend fun validateRequest(version: Long, contentId: Long, episodeId: Long?, previous: LocalPlaybackRequest): LocalPlaybackRequest {
        checkGeneration(version)
        val fresh = try { factory.create(contentId, previous.request.sourceSiteId, episodeId) }
        catch (e: CancellationException) { throw e }
        catch (_: IllegalArgumentException) { throw PlaybackSelectionExpired() }
        if (fresh.target != previous.target || fresh.request != previous.request ||
            fresh.libraryItemAddedAtEpochMs != previous.libraryItemAddedAtEpochMs) throw PlaybackSelectionExpired()
        return fresh
    }

    // Progress remains valid when a source is disabled: it describes already watched content.
    private suspend fun sameLibraryTarget(discovery: PlaybackDiscovery, local: LocalPlaybackRequest): Boolean {
        val item = library.listItems().singleOrNull { it.localId == discovery.contentId } ?: return false
        val identity = local.request.mediaMatch ?: return false
        if (item.content.tmdbId != identity.tmdbId || item.addedAtEpochMs != local.libraryItemAddedAtEpochMs) return false
        return when (val target = local.target) {
            is PlaybackTarget.Movie -> item.content.mediaType == MediaType.Movie
            is PlaybackTarget.Episode -> item.content.mediaType == MediaType.Tv &&
                library.listEpisodesForShow(item.localId).any {
                    it.episodeId == target.id && it.seasonNumber == identity.seasonNumber?.toLong() &&
                        it.episodeNumber == identity.episodeNumber?.toLong()
                }
        }
    }

    private data class Lookup(val choices: List<PlaybackChoice> = emptyList(), val failure: SourceLookupFailure? = null)
}
