package com.limelight

/** What to tell the user when the host refuses a forward. The host's own
 * reason is the only thing that tells the two cases apart: a device slot it
 * has not released yet (retry in a moment) and a usbip backend it cannot
 * attach through any more (restart Sunshine). The generic message sends the
 * user looking in the wrong place, so it is used only for reasons this
 * version does not know. */
internal object UsbForwardingFailure {
    /** Reason the host reports while it is still tearing the previous session
     *  down; see remote_usb's reverse tunnel service. */
    private const val ALREADY_FORWARDED = "already forwarded"

    /** Reason it reports when usbip-win2 itself refuses, which outlives a retry. */
    private const val ATTACH_FAILED = "attach failed"

    fun messageOf(reason: String?): Int = when {
        reason == null -> R.string.usb_forward_failed
        reason.contains(ALREADY_FORWARDED, ignoreCase = true) -> R.string.usb_forward_host_releasing
        reason.contains(ATTACH_FAILED, ignoreCase = true) -> R.string.usb_forward_host_attach_failed
        else -> R.string.usb_forward_failed
    }
}
