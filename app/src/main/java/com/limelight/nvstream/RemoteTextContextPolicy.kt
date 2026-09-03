package com.limelight.nvstream

/** Pure validation and viewport policy for host-reported text focus. */
object RemoteTextContextPolicy {
    fun isTrustedActivation(context: RemoteTextContext): Boolean {
        if (!context.hasFlag(RemoteTextContext.FLAG_INPUT_MATCHED)) return false

        return when (context.source) {
            RemoteTextContext.SOURCE_INPUT_PANE ->
                context.cause == RemoteTextContext.CAUSE_REMOTE_TOUCH &&
                    context.hasFlag(RemoteTextContext.FLAG_PANE_VISIBLE) &&
                    context.hasFlag(RemoteTextContext.FLAG_AUTO_SHOW)
            RemoteTextContext.SOURCE_UIA ->
                (context.cause == RemoteTextContext.CAUSE_REMOTE_TOUCH ||
                    context.cause == RemoteTextContext.CAUSE_REMOTE_MOUSE) &&
                    context.hasFlag(RemoteTextContext.FLAG_ACTIVE) &&
                    context.hasFlag(RemoteTextContext.FLAG_EDITABLE) &&
                    context.hasFlag(RemoteTextContext.FLAG_ELEMENT_RECT) &&
                    isValidRect(
                        context.elementLeft,
                        context.elementTop,
                        context.elementRight,
                        context.elementBottom,
                    )
            else -> false
        }
    }

    fun isNewerRevision(candidate: Long, previous: Long): Boolean {
        val delta = (candidate - previous) and 0xffff_ffffL
        return delta in 1..0x7fff_ffffL
    }

    fun isTrustedDeactivation(context: RemoteTextContext, activeActivationId: Long): Boolean {
        if (activeActivationId == 0L || context.activationId != activeActivationId) return false
        if (!context.hasFlag(RemoteTextContext.FLAG_INPUT_MATCHED)) return false
        if (context.source != RemoteTextContext.SOURCE_UIA) return false
        if (context.cause != RemoteTextContext.CAUSE_REMOTE_TOUCH &&
            context.cause != RemoteTextContext.CAUSE_REMOTE_MOUSE
        ) return false

        return !context.hasFlag(RemoteTextContext.FLAG_ACTIVE) ||
            !context.hasFlag(RemoteTextContext.FLAG_EDITABLE)
    }

    fun focusY(context: RemoteTextContext): Int = when {
        context.hasFlag(RemoteTextContext.FLAG_CARET_RECT) &&
            isValidRect(context.caretLeft, context.caretTop, context.caretRight, context.caretBottom) ->
            context.caretBottom
        context.hasFlag(RemoteTextContext.FLAG_ELEMENT_RECT) &&
            isValidRect(context.elementLeft, context.elementTop, context.elementRight, context.elementBottom) ->
            context.elementBottom
        else -> context.anchorY
    }

    private fun isValidRect(left: Int, top: Int, right: Int, bottom: Int): Boolean =
        left < right && top < bottom

    fun viewportOffset(focusInWindow: Float, visibleBottom: Float, margin: Float): Float =
        minOf(0f, visibleBottom - margin - focusInWindow)

    fun imeVisibleBottom(rootTop: Float, rootHeight: Int, imeBottomInset: Int): Float? {
        if (rootHeight <= 0 || imeBottomInset <= 0) return null
        return rootTop + (rootHeight - imeBottomInset.coerceAtMost(rootHeight))
    }

    fun legacyVisibleBottom(
        rootTop: Float,
        rootHeight: Int,
        frameBottom: Int,
        minimumOcclusionFraction: Float = 0.15f,
    ): Float? {
        if (rootHeight <= 0) return null
        val rootBottom = rootTop + rootHeight
        val occlusion = rootBottom - frameBottom
        return frameBottom.toFloat().takeIf {
            occlusion > rootHeight * minimumOcclusionFraction
        }
    }
}
