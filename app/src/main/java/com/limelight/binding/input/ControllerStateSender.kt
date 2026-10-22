package com.limelight.binding.input

/** Compares the final, merged wire state. Changed inputs never wait for a timer. */
internal class ControllerStateSender(
    private val nowNanos: () -> Long = System::nanoTime,
    private val send: (Short, Short, Int, Byte, Byte, Short, Short, Short, Short) -> Int
) {
    private class State {
        var valid = false
        var sentAt = 0L
        var mask: Short = 0
        var buttons = 0
        var lt: Byte = 0
        var rt: Byte = 0
        var lx: Short = 0
        var ly: Short = 0
        var rx: Short = 0
        var ry: Short = 0
    }

    private val states = Array(16) { State() }

    @Synchronized
    fun submit(
        controller: Short, mask: Short, buttons: Int, lt: Byte, rt: Byte,
        lx: Short, ly: Short, rx: Short, ry: Short, force: Boolean = false
    ) {
        val state = states.getOrNull(controller.toInt()) ?: return
        val now = nowNanos()
        if (!force && state.valid && now - state.sentAt < KEEPALIVE_NANOS &&
            state.mask == mask && state.buttons == buttons && state.lt == lt && state.rt == rt &&
            state.lx == lx && state.ly == ly && state.rx == rx && state.ry == ry
        ) return

        // A rejected enqueue must not hide the next identical retry (especially release).
        if (send(controller, mask, buttons, lt, rt, lx, ly, rx, ry) != 0) return
        state.valid = true
        state.sentAt = now
        state.mask = mask
        state.buttons = buttons
        state.lt = lt
        state.rt = rt
        state.lx = lx
        state.ly = ly
        state.rx = rx
        state.ry = ry
    }

    @Synchronized
    fun reset(controller: Short) {
        states.getOrNull(controller.toInt())?.valid = false
    }

    private companion object {
        const val KEEPALIVE_NANOS = 1_000_000_000L
    }
}
