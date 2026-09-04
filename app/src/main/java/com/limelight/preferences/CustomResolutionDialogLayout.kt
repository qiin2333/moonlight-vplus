package com.limelight.preferences

internal data class CustomResolutionDialogLayoutSpec(
    val useTwoPane: Boolean,
    val widthFraction: Float,
    val maxWidthDp: Int,
    val heightFraction: Float,
    val maxHeightDp: Int
)

internal fun customResolutionDialogLayoutSpec(
    isLandscape: Boolean,
    screenHeightDp: Int
): CustomResolutionDialogLayoutSpec {
    return if (isLandscape && screenHeightDp <= 480) {
        CustomResolutionDialogLayoutSpec(
            useTwoPane = true,
            widthFraction = 0.94f,
            maxWidthDp = 720,
            heightFraction = 0.72f,
            maxHeightDp = 420
        )
    } else {
        CustomResolutionDialogLayoutSpec(
            useTwoPane = false,
            widthFraction = 0.86f,
            maxWidthDp = 480,
            heightFraction = 0.70f,
            maxHeightDp = 560
        )
    }
}
