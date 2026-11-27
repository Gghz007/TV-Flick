package app.tvflick.plugin.video

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes Android visibility changes with source/episode changes. PiP remains visible. */
internal class PlaybackVisibility(
    private val transition: Mutex,
    private val isPlaying: () -> Boolean,
    private val pause: () -> Unit,
    private val play: () -> Unit,
    private val persist: suspend () -> Unit,
) {
    private var visible = true
    private var suspended = false
    private var resumeWhenVisible = false

    suspend fun setVisible(value: Boolean) {
        // Record this before waiting for an in-flight source open. That open must not
        // start audible playback after the activity has already been stopped.
        visible = value
        transition.withLock {
            if (!visible) {
                if (!suspended) resumeWhenVisible = isPlaying()
                suspended = true
                pause()
                persist()
            } else if (suspended) {
                suspended = false
                val resume = resumeWhenVisible
                resumeWhenVisible = false
                if (resume) play()
            }
        }
    }

    /** Called under transition lock after an engine open, which normally autoplays. */
    fun sourceOpened(autoplay: Boolean = true) {
        if (!visible) {
            suspended = true
            resumeWhenVisible = autoplay
            pause()
        }
    }
}
