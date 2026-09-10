package com.limelight.preferences

import androidx.annotation.StringRes
import com.limelight.R
import kotlin.math.roundToLong

/** 输入错误指向的字段;DUPLICATE 属于整体错误,不指向单个字段。 */
enum class ResolutionField { WIDTH, HEIGHT }

enum class ResolutionInputReason { EMPTY, OUT_OF_RANGE, ODD, DUPLICATE }

data class ResolutionInputError(
    val field: ResolutionField?,
    val reason: ResolutionInputReason
)

/** 校验宽高输入,返回 null 表示通过;width/height 传解析后的整数,空或非数字为 null。 */
fun validateResolutionInput(
    width: Int?,
    height: Int?,
    existing: List<Resolution>
): ResolutionInputError? = when {
    width == null -> ResolutionInputError(ResolutionField.WIDTH, ResolutionInputReason.EMPTY)
    height == null -> ResolutionInputError(ResolutionField.HEIGHT, ResolutionInputReason.EMPTY)
    !ResolutionValidator.isValidWidth(width) ->
        ResolutionInputError(ResolutionField.WIDTH, ResolutionInputReason.OUT_OF_RANGE)
    !ResolutionValidator.isValidHeight(height) ->
        ResolutionInputError(ResolutionField.HEIGHT, ResolutionInputReason.OUT_OF_RANGE)
    !ResolutionValidator.isEven(width) ->
        ResolutionInputError(ResolutionField.WIDTH, ResolutionInputReason.ODD)
    !ResolutionValidator.isEven(height) ->
        ResolutionInputError(ResolutionField.HEIGHT, ResolutionInputReason.ODD)
    existing.any { it.width == width && it.height == height } ->
        ResolutionInputError(null, ResolutionInputReason.DUPLICATE)
    else -> null
}

/** 列表与存储共用的排序:按宽升序,同宽按高升序。 */
val resolutionOrder: Comparator<Resolution> = compareBy({ it.width }, { it.height })

private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

/** "16:9";无法干净化简的比例(3440×1440 → 43:18)显示为相对 9 的形式。 */
fun ratioText(width: Int, height: Int): String {
    val g = gcd(width, height)
    val a = width / g
    val b = height / g
    if (a > 40 || b > 40) {
        val scaled = ((width * 9f / height) * 10).roundToLong().toInt()
        if (scaled % 10 == 0) {
            return "${scaled / 10}:9"
        }
        return String.format("%.1f:9", scaled / 10f)
    }
    return "$a:$b"
}

/** 常见分辨率的识别标签;无法归类的返回 null。 */
@StringRes
fun resolutionTag(width: Int, height: Int): Int? = when {
    height > width -> R.string.custom_resolution_tag_portrait
    width >= 7680 -> R.string.custom_resolution_tag_8k
    width >= 3840 || height >= 2160 -> R.string.custom_resolution_tag_4k
    width.toFloat() / height >= 2.2f -> R.string.custom_resolution_tag_ultrawide
    height >= 1440 -> R.string.custom_resolution_tag_2k
    height >= 1080 -> R.string.custom_resolution_tag_1080p
    else -> null
}

/** 等比小图的宽高(dp):按真实比例适配进 36×24dp 的框,最小边 6dp。 */
fun ratioGlyphSize(width: Int, height: Int): Pair<Float, Float> {
    val ratio = width.toFloat() / height
    val glyphWidth: Float
    val glyphHeight: Float
    if (ratio > 36f / 24f) {
        glyphWidth = 36f
        glyphHeight = 36f / ratio
    } else {
        glyphHeight = 24f
        glyphWidth = 24f * ratio
    }
    val minSide = minOf(glyphWidth, glyphHeight)
    val scale = if (minSide < 6f) 6f / minSide else 1f
    return glyphWidth * scale to glyphHeight * scale
}
