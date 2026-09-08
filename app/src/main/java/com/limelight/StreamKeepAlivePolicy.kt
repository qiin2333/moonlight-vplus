package com.limelight

internal object StreamKeepAlivePolicy {
    fun shouldStartForResume(
        isFinishing: Boolean,
        shouldResumeSession: Boolean,
        isResumeStreamEnabled: Boolean,
    ): Boolean = !isFinishing && shouldResumeSession && isResumeStreamEnabled
}
