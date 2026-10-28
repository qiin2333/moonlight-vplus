package com.limelight.binding.input.haptics

import com.limelight.nvstream.Ds5HapticsIrFrame
import com.limelight.nvstream.Ds5HapticsIrExtendedEvent
import com.limelight.nvstream.Ds5HapticsIrExtendedFrame
import java.util.PriorityQueue

/** Source-relative clock, bounded event inbox, and latest continuous state. No device rate gate. */
internal class AuthoredIrTimeline {
    data class Event(val value: Ds5HapticsIrExtendedEvent, val dueUs: Long, val expiresUs: Long)
    data class Output(val body: ControllerRumbleState, val event: Event?, val ended: Boolean,
                      val reset: Boolean = false, val bodyValidUntilUs: Long = Long.MAX_VALUE)
    private data class State(val body: ControllerRumbleState, val sourceUs: Long)
    private var epoch: Int? = null
    private var sequence: Int? = null
    private var lastSourceUs: Long? = null
    private var offsetUs = 0L
    private var body = ControllerRumbleState.ZERO
    private var expiresUs = Long.MIN_VALUE
    private var dirty = false
    private var pendingReset = false
    private var ended = true
    private var terminal = false
    private val events = PriorityQueue<Event>(compareBy { it.value.sourceTimeUs })
    private val states = PriorityQueue<State>(compareBy { it.sourceUs })
    private val seenIds = LinkedHashSet<Int>()

    val queuedEvents: Int get() = events.size

    fun accept(frame: Ds5HapticsIrExtendedFrame, nowUs: Long): Boolean {
        if (frame.windowEndUs < 0 || frame.events.size > 8) return false
        val oldEpoch = epoch
        val changedEpoch = oldEpoch == null || oldEpoch != frame.epoch
        if (oldEpoch != null && changedEpoch && !newer(frame.epoch, oldEpoch)) return false
        if (!changedEpoch && terminal) return false
        val discontinuity = frame.flags and Ds5HapticsIrFrame.FLAG_DISCONTINUITY != 0
        val latest = changedEpoch || sequence?.let { newer(frame.frameSequence, it) } != false &&
            (discontinuity || lastSourceUs?.let { frame.windowEndUs >= it } != false)
        if (changedEpoch || latest && discontinuity) {
            events.clear()
            states.clear()
            body = ControllerRumbleState.ZERO
            dirty = true
            pendingReset = true
            if (changedEpoch) seenIds.clear()
            terminal = false
            offsetUs = nowUs - frame.windowEndUs
        } else if (latest) {
            // Delayed packets never move the source clock forward or become newly fresh.
            offsetUs = minOf(offsetUs, nowUs - frame.windowEndUs)
        }
        val mappedEnd = frame.windowEndUs + offsetUs
        if (nowUs - mappedEnd > FRAME_FRESH_US && !frame.ended) return false
        if (latest) {
            epoch = frame.epoch
            sequence = frame.frameSequence
            lastSourceUs = frame.windowEndUs
            ended = frame.ended
            terminal = ended
            if (!ended) {
                val state = State(ControllerRumbleState(
                    maxOf(level(frame.left.continuousLow), level(frame.right.continuousLow)),
                    maxOf(level(frame.left.continuousHigh), level(frame.right.continuousHigh))), frame.windowEndUs)
                if (states.size == MAX_STATES) states.poll()
                states.add(state)
                expiresUs = mappedEnd + BODY_LEASE_US
            }
        }
        if (latest && ended) {
            events.clear()
            states.clear()
            body = ControllerRumbleState.ZERO
            dirty = true
            return true
        }
        var ingested = latest
        for (event in frame.events.sortedBy { it.sourceTimeUs }) {
            if (event.sourceTimeUs < 0 || event.sourceTimeUs > frame.windowEndUs ||
                event.eventId in seenIds) continue
            seenIds += event.eventId
            if (seenIds.size > SEEN_IDS) seenIds.remove(seenIds.first())
            val sourceLocal = event.sourceTimeUs + offsetUs
            if (nowUs - sourceLocal > EVENT_FRESH_US ||
                maxOf(level(event.leftStrength), level(event.rightStrength)) == 0f) continue
            if (events.size == MAX_EVENTS) events.poll()
            events.add(Event(event, sourceLocal + PLAYOUT_LEAD_US, sourceLocal + EVENT_FRESH_US))
            ingested = true
        }
        return ingested
    }

    fun poll(nowUs: Long): Output? {
        while (states.isNotEmpty() && states.peek()!!.sourceUs + offsetUs + PLAYOUT_LEAD_US <= nowUs) {
            val state = states.poll() ?: break
            body = state.body
            expiresUs = state.sourceUs + offsetUs + BODY_LEASE_US
            dirty = true
        }
        if (!ended && nowUs >= expiresUs) {
            body = ControllerRumbleState.ZERO
            ended = true
            events.clear()
            states.clear()
            dirty = true
        }
        var selected: Event? = null
        // After a stalled consumer, discard missed source slots; do not replay a catch-up train.
        while (events.isNotEmpty() && events.peek()!!.value.sourceTimeUs + offsetUs + PLAYOUT_LEAD_US <= nowUs) {
            val event = events.poll() ?: break
            val sourceLocal = event.value.sourceTimeUs + offsetUs
            if (nowUs <= sourceLocal + EVENT_FRESH_US) selected = event.copy(
                dueUs = sourceLocal + PLAYOUT_LEAD_US, expiresUs = sourceLocal + EVENT_FRESH_US)
        }
        if (!dirty && selected == null) return null
        dirty = false
        return Output(body, selected, ended, pendingReset, expiresUs).also { pendingReset = false }
    }

    fun nextWakeUs(): Long? = listOfNotNull(events.peek()?.value?.sourceTimeUs?.let { it + offsetUs + PLAYOUT_LEAD_US },
        states.peek()?.sourceUs?.let { it + offsetUs + PLAYOUT_LEAD_US },
        expiresUs.takeUnless { ended }).minOrNull()

    private fun newer(next: Int, previous: Int): Boolean =
        (next.toLong() - previous.toLong()).and(0xffffffffL) in 1L..0x7fffffffL
    private fun level(value: Float) = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

    companion object {
        const val MAX_EVENTS = 64
        private const val MAX_STATES = 16
        const val PLAYOUT_LEAD_US = 20_000L
        private const val SEEN_IDS = 512
        const val EVENT_FRESH_US = 30_000L
        private const val FRAME_FRESH_US = 100_000L
        private const val BODY_LEASE_US = 200_000L
    }
}
