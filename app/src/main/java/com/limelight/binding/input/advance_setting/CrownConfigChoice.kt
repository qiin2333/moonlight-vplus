package com.limelight.binding.input.advance_setting

import com.limelight.binding.input.advance_setting.share.CrownProfileShareManager

/** Display data for one Crown profile in the picker and its callers. */
data class CrownConfigChoice(
    val name: String,
    val elementCount: Int,
    val preview: CrownProfileShareManager.ProfilePreview?,
    val configId: Long = -1L,
    val isCurrent: Boolean = false,
    val isSelectable: Boolean = true
)
