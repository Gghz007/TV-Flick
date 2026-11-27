package app.tvflick.plugin.video

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlin.test.*

class PlaybackVisibilityTest {
    @Test fun backgroundSavesAndResumesOnlyPreviouslyPlayingVideo() = runBlocking {
        var playing = true
        var saves = 0
        var resumes = 0
        val policy = PlaybackVisibility(Mutex(), { playing }, { playing = false },
            { playing = true; resumes++ }, { saves++ })
        policy.setVisible(false)
        policy.setVisible(false) // Duplicate stop must not forget the resume intent.
        assertFalse(playing)
        assertEquals(2, saves)
        policy.setVisible(true)
        policy.setVisible(true)
        assertTrue(playing)
        assertEquals(1, resumes)
        playing = false // User pause is preserved across background/foreground.
        policy.setVisible(false)
        policy.setVisible(true)
        assertFalse(playing)
        assertEquals(1, resumes)
    }

    @Test fun sourceFinishingAfterStopCannotAutoplayInBackground() = runBlocking {
        val transition = Mutex(locked = true)
        var playing = false
        var savedPlaying: Boolean? = null
        val policy = PlaybackVisibility(transition, { playing }, { playing = false },
            { playing = true }, { savedPlaying = playing })
        val stop = launch(start = CoroutineStart.UNDISPATCHED) { policy.setVisible(false) }
        playing = true // Engine open completes while the visibility change waits.
        policy.sourceOpened()
        assertFalse(playing)
        transition.unlock()
        stop.join()
        assertEquals(false, savedPlaying)
        policy.setVisible(true)
        assertTrue(playing)
    }

    @Test fun failedProgressWriteStillPausesAndCanResume() = runBlocking {
        var playing = true
        val policy = PlaybackVisibility(Mutex(), { playing }, { playing = false },
            { playing = true }, { error("storage unavailable") })
        assertFailsWith<IllegalStateException> { policy.setVisible(false) }
        assertFalse(playing)
        policy.setVisible(true)
        assertTrue(playing)
    }

    @Test fun rapidReturnDuringOpenUsesLatestVisibilityWithoutReopening() = runBlocking {
        val transition = Mutex(locked = true)
        var playing = true
        var pauses = 0
        var resumes = 0
        val policy = PlaybackVisibility(transition, { playing }, { playing = false; pauses++ },
            { playing = true; resumes++ }, {})
        val stop = launch(start = CoroutineStart.UNDISPATCHED) { policy.setVisible(false) }
        val start = launch(start = CoroutineStart.UNDISPATCHED) { policy.setVisible(true) }
        policy.sourceOpened()
        transition.unlock()
        joinAll(stop, start)
        assertTrue(playing)
        assertEquals(0, pauses)
        assertEquals(0, resumes)
    }
}
