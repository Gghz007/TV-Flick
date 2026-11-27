package app.tvflick.plugin.video

import app.tvflick.plugin.api.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.openani.mediamp.*
import org.openani.mediamp.features.*
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.metadata.MediaProperties
import kotlin.test.*

/** Drives the real session/engine wrapper with a deterministic SDK boundary; no native window. */
class PlayerProgressSessionTest {
    @Suppress("DEPRECATION_ERROR", "OVERRIDE_DEPRECATION")
    @OptIn(InternalForInheritanceMediampApi::class)
    private class Sdk : MediampPlayer {
        override val state = MutableStateFlow(PlayerState(MediaStatus.Idle, false, false))
        val position = MutableStateFlow(0L)
        override val currentPositionMillis = position
        override val mediaProperties = MutableStateFlow(MediaProperties(null, 100_000L, null, null))
        override val mediaData = MutableStateFlow<MediaData?>(null)
        override val events = MutableSharedFlow<PlaybackEvent>()
        override val impl: Any get() = this
        override val mainDispatcher = Dispatchers.Unconfined
        override val playbackProgress: Flow<Float> = position.map { it.toFloat() / 100_000 }
        override val playbackState = MutableStateFlow(PlaybackState.READY)
        var opens = 0
        var closed = false
        var requestedPosition = 0L
        var seekIsAsync = false
        val audio = object : AudioLevelController {
            override val volume = MutableStateFlow(1f)
            override val maxVolume = 1f
            override val isMute = MutableStateFlow(false)
            override fun setVolume(volume: Float) { this.volume.value = volume }
            override fun setMute(mute: Boolean) { isMute.value = mute }
            override fun volumeUp(step: Float) = setVolume(volume.value + step)
            override fun volumeDown(step: Float) = setVolume(volume.value - step)
        }
        val rate = object : PlaybackSpeed {
            override val valueFlow = MutableStateFlow(1f)
            override val value: Float get() = valueFlow.value
            override fun set(value: Float) { valueFlow.value = value }
        }
        override val features = playerFeaturesOf(AudioLevelController to audio, PlaybackSpeed to rate)
        override suspend fun setMediaData(data: MediaData, playWhenReady: Boolean, startPositionMillis: Long) {
            opens++
            requestedPosition = startPositionMillis
            audio.setVolume(1f)
            audio.setMute(false)
            rate.set(1f)
            mediaData.value = data
            state.value = PlayerState(MediaStatus.Opening, playWhenReady, false)
            position.value = 0
        }
        override fun play() { state.value = state.value.copy(playWhenReady = true) }
        override fun pause() { state.value = state.value.copy(playWhenReady = false) }
        override fun stopPlayback() { state.value = PlayerState(MediaStatus.Idle, false, false) }
        override fun seekTo(positionMillis: Long) { if (!seekIsAsync) position.value = positionMillis }
        override fun close() { closed = true; state.value = PlayerState(MediaStatus.Released, false, false) }
        val session = FlickPlayerSession(this, MediaMpVideoEngine(this))
        fun ready(at: Long) { position.value = at; state.value = PlayerState(MediaStatus.Ready, true, false) }
    }
    private fun source(id: Long) = ResolvedVideoSource("https://fixture.test/$id.mp4", StreamKind.Mp4, sourceId = "fixture")

    private fun catalog() = source(1).copy(
        qualityOptions = listOf(PlaybackOption("a720", "720p"), PlaybackOption("a1080", "1080p"),
            PlaybackOption("b720", "720p"), PlaybackOption("b1080", "1080p")),
        audioTrackOptions = listOf(PlaybackOption("a", "Original"), PlaybackOption("b", "Dub")),
        variants = listOf("a720", "a1080", "b720", "b1080").map { id ->
            ResolvedStreamVariant(if (id == "a720") source(1).url else "https://fixture.test/$id.mp4",
                StreamKind.Mp4, id.take(1), id, mapOf("Referer" to "https://fixture.test"))
        },
    )

    @Test fun `buffering quality switch keeps resume intent volume mute speed and mini binding`() = runBlocking {
        val sdk = Sdk()
        val source = catalog()
        sdk.session.openPlayback(source, 1, 0, null, true)
        sdk.session.engine.seekTo(50_000) // An opening decoder must keep its requested resume.
        assertEquals(0L, sdk.position.value)
        sdk.ready(42_000)
        sdk.session.engine.seekTo(Long.MAX_VALUE)
        assertEquals(100_000L, sdk.position.value)
        sdk.session.engine.seekTo(-10)
        assertEquals(0L, sdk.position.value)
        sdk.ready(42_000)
        sdk.state.value = sdk.state.value.copy(isBuffering = true)
        sdk.session.speed = 1.5f
        sdk.audio.setVolume(.3f)
        sdk.audio.setMute(true)
        assertTrue(sdk.session.switchVariant(source, 1, qualityId = "a1080"))
        assertEquals(42_000L, sdk.requestedPosition)
        assertTrue(sdk.state.value.playWhenReady)
        assertEquals(.3f, sdk.audio.volume.value)
        assertTrue(sdk.audio.isMute.value)
        assertEquals(1.5f, sdk.rate.value)
        assertEquals("a1080", sdk.session.selectedQualityId)
        sdk.session.openPlayback(source, 1, 0, null, true)
        assertEquals(2, sdk.opens)
        sdk.ready(43_000)
        assertFalse(sdk.session.switchVariant(source, 1, qualityId = "a1080"))
        assertFalse(sdk.session.switchVariant(source, 1, qualityId = "missing"))
        assertEquals(2, sdk.opens)
        sdk.pause()
        assertTrue(sdk.session.switchVariant(source, 1, audioId = "b"))
        assertEquals("b1080", sdk.session.selectedQualityId)
        assertFalse(sdk.state.value.playWhenReady)
        assertEquals(43_000L, sdk.requestedPosition)
    }

    @Test fun `native decoder failures report once and stale failures cannot close another binding`() = runBlocking {
        val sdk = Sdk()
        sdk.session.openPlayback(source(1), 1, 0, null, true)
        val failure = MediaStatus.Error(PlaybackException(PlaybackErrorCode.DECODING, "decoder failed", null))
        sdk.state.value = PlayerState(failure, true, false)
        assertTrue(sdk.session.claimNativeFailure(source(1), 1, failure))
        assertFalse(sdk.state.value.playWhenReady)
        assertFalse(sdk.session.claimNativeFailure(source(1), 1, failure))
        sdk.session.openPlayback(source(2), 2, 0, null, true)
        assertFalse(sdk.session.claimNativeFailure(source(1), 1, failure))
        assertFalse(sdk.session.claimNativeFailure(source(2), 2, failure))
        val next = MediaStatus.Error(PlaybackException(PlaybackErrorCode.IO, "stream failed", null))
        sdk.state.value = PlayerState(next, true, false)
        assertTrue(sdk.session.claimNativeFailure(source(2), 2, next))
    }

    @Test fun `closing or backgrounding an opening stream preserves the previously saved resume`() = runBlocking {
        val sdk = Sdk()
        var saved = PlaybackSnapshot(1, 42_000, 100_000, false)
        sdk.session.openPlayback(source(1), 1, saved.positionMs, PlaybackProgressSink { saved = it }, true)
        sdk.session.persistPlayback()
        sdk.session.setHostVisible(false)
        sdk.session.closeAndPersistPlayback()
        assertEquals(42_000, saved.positionMs)
        assertFalse(saved.reachesWatchedThreshold())
        assertTrue(sdk.closed)
    }

    @Test fun `closing after an asynchronous end seek saves the intended watched episode`() = runBlocking {
        val sdk = Sdk()
        var saved: PlaybackSnapshot? = null
        sdk.session.openPlayback(source(1), 1, 0, PlaybackProgressSink { saved = it }, true)
        sdk.ready(10_000)
        sdk.seekIsAsync = true
        sdk.session.engine.seekTo(100_000)
        assertEquals(10_000L, sdk.position.value)
        sdk.session.closeAndPersistPlayback()
        assertEquals(PlaybackSnapshot(1, 100_000, 100_000, true), saved)
    }

    @Test fun `three episode boundaries and mini reuse keep identity and independent Auto MARK policy`() = runBlocking {
        val sdk = Sdk()
        val saved = linkedMapOf<Long, PlaybackSnapshot>()
        fun sink(id: Long) = PlaybackProgressSink { snapshot ->
            assertEquals(id, snapshot.contentId)
            saved[id] = snapshot
        }
        sdk.session.openPlayback(source(1), 1, 0, sink(1), true)
        sdk.ready(69_999)
        sdk.session.persistPlayback()
        assertFalse(saved.getValue(1).reachesWatchedThreshold())
        assertTrue(sdk.session.playerWatchedEpisodeIds.isEmpty())
        sdk.ready(70_000)
        sdk.session.persistPlayback()
        sdk.session.openPlayback(source(1), 1, 0, sink(1), true) // Same Full/Mini session, not a reopen.
        assertEquals(1, sdk.opens)
        assertEquals(70_000, sdk.position.value)
        sdk.session.openPlayback(source(2), 2, 0, sink(2), true)
        sdk.session.autoWatchListEnabled = false
        sdk.ready(95_000)
        sdk.session.setHostVisible(false)
        sdk.session.setHostVisible(true)
        sdk.session.openPlayback(source(3), 3, 0, sink(3), true)
        sdk.session.autoWatchListEnabled = true
        sdk.ready(99_000) // A seek near the end qualifies by position, without an Ended event.
        sdk.session.closeAndPersistPlayback()
        assertEquals(listOf(1L, 2L, 3L), saved.keys.toList())
        assertEquals(listOf(70_000L, 95_000L, 99_000L), saved.values.map { it.positionMs })
        assertTrue(saved.values.all { it.reachesWatchedThreshold() })
        assertEquals(listOf(true, false, true), saved.values.map { it.allowLibraryWatchedMark })
        assertEquals(listOf(1L, 2L, 3L), sdk.session.playerWatchedEpisodeIds.toList())
        assertTrue(sdk.closed)
    }

    @Test fun `untracked media creates no marks and failed close write still releases the engine`() = runBlocking {
        val sdk = Sdk()
        sdk.session.openPlayback(source(10), 10, 0, null, false)
        sdk.ready(99_000)
        sdk.session.persistPlayback()
        assertTrue(sdk.session.playerWatchedEpisodeIds.isEmpty())
        // Movie and episode numeric IDs can overlap. The type still starts a new binding.
        sdk.session.openPlayback(source(10), 10, 0, PlaybackProgressSink { error("storage unavailable") }, true)
        assertEquals(2, sdk.opens)
        sdk.ready(50_000)
        assertFailsWith<IllegalStateException> { sdk.session.closeAndPersistPlayback() }
        assertTrue(sdk.closed)
        assertNull(sdk.session.activeContentId)
        assertNull(sdk.session.activeProgressSink)
    }
}
