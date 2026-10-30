package com.limelight.binding.video

/** Matches actual output geometry and PTS, so queued frames from the old size cannot finish a switch. */
internal class ResolutionFrameTracker(val width: Int, val height: Int) {
    private data class Frame(val decoder: Any, val pts: Long)
    private val frames = ArrayDeque<Frame>()

    @Synchronized
    fun decoded(decoder: Any, pts: Long, width: Int, height: Int) {
        if (width != this.width || height != this.height) return
        if (frames.size == 32) frames.removeFirst()
        frames.addLast(Frame(decoder, pts))
    }

    @Synchronized
    fun presented(decoder: Any, pts: Long): Boolean {
        val match = frames.any { it.decoder === decoder && it.pts == pts }
        if (match) frames.clear()
        return match
    }
}
