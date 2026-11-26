package com.limelight.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.R
import com.limelight.binding.input.StartWheelAction
import com.limelight.binding.input.StartWheelCategory
import com.limelight.binding.input.StartWheelLayout
import com.limelight.binding.input.StartWheelSnapshot
import com.limelight.utils.appAccentColor
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Display mode of the performance overlay as the Start wheel reports it. */
internal enum class StartWheelPerfMode { HIDDEN, FLOATING, LOCKED }

/** One slot of the keyboard ring: a quick action's label and icon, resolved by the app. */
@Immutable
internal data class StartWheelKeyItem(val title: String, @param:DrawableRes val icon: Int)

/** Live state of the features the Start wheel switches; each one is marked on its item. */
@Immutable
internal data class StartWheelFeatureState(
    val perfOverlayMode: StartWheelPerfMode = StartWheelPerfMode.HIDDEN,
    val virtualControllerVisible: Boolean = false,
    val gyroEnabled: Boolean = false,
    val mouseEmulationActive: Boolean = false,
    val keyboardVisible: Boolean = false,
    val micActive: Boolean = false,
    /** Items whose feature is not set up on this device are left out, as in the game menu. */
    val virtualControllerAvailable: Boolean = true,
    val gyroAvailable: Boolean = true,
    val micAvailable: Boolean = true,
    /** The keyboard ring's three slots in the user's quick-action order; empty means the defaults. */
    val keyRing: List<StartWheelKeyItem> = emptyList()
)

/** Everything the Start hold wheel overlay draws. */
@Immutable
internal data class StartWheelUiState(
    val wheel: StartWheelSnapshot = StartWheelSnapshot(),
    val features: StartWheelFeatureState = StartWheelFeatureState()
) {
    val visible: Boolean get() = wheel.visible
}

@Immutable
private data class WheelItemVisual(
    val title: String,
    @param:DrawableRes val icon: Int,
    /** Drawn as a warning until it is the item selected. */
    val danger: Boolean = false,
    /** What the item says about the feature it switches; shown as a small dot by its icon. */
    val state: ItemState = ItemState.NONE
)

private enum class ItemState { NONE, ON, OFF }

// The wheel takes after the app's moon-wheel icon (the Settings button): a dark wheel, light lines
// and a single accent. Mint is the colour of every line, charcoal of the disc.
private val OverlayScrim = Color.Black.copy(alpha = 0.38f)
private val WheelText = Color(0xFFF4F8F6)
private val CategoryFill = Color(0xB8393E46)
private val ActionFill = Color(0xB0344044)
private val ParentFill = Color(0xCC4C5862)
private val HubFill = Color(0xF50E1216)
private val Mint = Color(0xFFDCF6FF)
private val WheelDanger = Color(0xFFFFB4A2)

private val WheelMaxSize = 460.dp
private val WheelMinSize = 200.dp
private val WheelCompactBelow = 340.dp

/** Room kept under the footer for the system navigation bar, so the key hints stay readable. */
private val FOOTER_SAFETY = 28.dp

private const val SECTOR_COUNT = 4
private const val SECTOR_DEGREES = 90f

/** Opacity of the outer tier while it only previews the items of the highlighted category. */
private const val PREVIEW_ALPHA = 0.62f

/** The outer tier arrives a touch too large and settles when it opens. */
private const val OPEN_PULSE_SCALE = 1.05f

/** Sweep of the short stroke on the seam that marks the category whose items are open. */
private const val MARK_DEGREES = 28f

/** The rims are rings with one opening of this width, as in the Settings icon. */
private const val OPENING_DEGREES = 45f

/** With nothing highlighted the opening looks down, where the Settings icon has it. */
private const val REST_POINTER_DEGREES = 180f

/** Share of the accent that tints an empty slot the cursor rests on. */
private const val EMPTY_SLOT_TINT = 0.26f

private const val SELECTION_ANIMATION_MS = 110
private const val RING_ANIMATION_MS = 200
private const val OPEN_ANIMATION_MS = 240

/** Fitted labels never shrink below this share of their normal size; with a large system font
 * that is all the room a side sector has. */
private const val MIN_TEXT_SCALE = 0.7f

/** Side sectors are only as wide as their tier, so two-word titles there are stacked by hand. */
private val SideSectors = setOf(1, 3)

private val CategoryTitleRes = intArrayOf(
    R.string.start_wheel_category_system,
    R.string.start_wheel_category_input,
    R.string.start_wheel_category_keyboard,
    R.string.start_wheel_category_performance
)

private val CategoryIconRes = intArrayOf(
    R.drawable.ic_start_wheel_system,
    R.drawable.ic_start_wheel_mouse,
    R.drawable.ic_start_wheel_keyboard,
    R.drawable.ic_start_wheel_performance
)

/** Text and icon sizes that shrink together on phones in landscape, where the wheel is smallest. */
private class WheelTypography(compact: Boolean) {
    val categoryIcon = if (compact) 20.dp else 24.dp
    val categoryTitle = if (compact) 12.sp else 14.sp
    val categoryTitleLine = if (compact) 15.sp else 18.sp
    val itemIcon = if (compact) 22.dp else 28.dp
    val itemTitle = if (compact) 11.sp else 12.sp
    val itemTitleLine = if (compact) 14.sp else 16.sp
    val hubIcon = if (compact) 26.dp else 34.dp
    /** The single caption line under the disc; it names what releasing Start would run. */
    val caption = if (compact) 12.sp else 13.sp
    val captionLine = if (compact) 18.sp else 20.sp
    val captionGap = if (compact) 8.dp else 12.dp
    /** About the height of the key-cap row below the caption. */
    val keysRow = if (compact) 20.dp else 24.dp
    val dot = if (compact) 5.dp else 6.dp
    val keyCap = if (compact) 9.sp else 10.sp
    val keyLabel = if (compact) 11.sp else 12.sp
    val keyGap = if (compact) 24.dp else 32.dp
    val keyCapPaddingH = if (compact) 5.dp else 6.dp
    val keyCapPaddingV = if (compact) 2.dp else 3.dp
    val keysTopGap = if (compact) 10.dp else 16.dp
}

/**
 * The Start hold wheel: one disc, cut into four quarters and two tiers, around a hub.
 *
 * The inner tier holds the four categories. Highlighting one previews its items on the outer
 * tier; going a tier deeper opens it for selection. What releasing Start would run is captioned in
 * one line under the disc, where a long name has room, and the hub keeps only its icon.
 *
 * It is drawn the way classic in-game wheels are, one dark disc with thin cuts, icons first and no
 * more text than the item names, and it borrows its lines from the app's own moon-wheel icon: the
 * rim is a ring with one opening and a needle in it that turns towards whatever is highlighted, and
 * the hub shows the icon itself while nothing would run.
 */
@Composable
internal fun StartHoldWheelOverlay(state: StartWheelUiState, modifier: Modifier = Modifier) {
    val wheel = state.wheel
    if (!wheel.visible) return

    val accent = appAccentColor()
    val inkOnAccent = remember(accent) { accent.contentInk() }
    val itemVisuals = startWheelItemVisuals(state.features)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(OverlayScrim),
        contentAlignment = Alignment.Center
    ) {
        val compact = minOf(maxHeight * 0.84f, maxWidth * 0.86f, WheelMaxSize) < WheelCompactBelow
        val typography = remember(compact) { WheelTypography(compact) }
        // The caption's line follows the system font scale, so the room it takes is measured, not fixed.
        val captionRow = with(LocalDensity.current) { typography.captionLine.toDp() }
        // The disc gets what the caption and the key hints below it leave, plus a margin over the
        // system navigation bar, so neither is ever pushed off the screen.
        val footer = typography.captionGap + captionRow + typography.keysTopGap + typography.keysRow + FOOTER_SAFETY
        val wheelSize = minOf(maxHeight - footer, maxWidth * 0.86f, WheelMaxSize)
            .coerceAtLeast(WheelMinSize)
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Wheel(wheelSize, wheel, itemVisuals, accent, inkOnAccent, compact, typography)
            Spacer(Modifier.height(typography.captionGap))
            WheelCaption(wheel, itemVisuals, typography, captionRow)
            Spacer(Modifier.height(typography.keysTopGap))
            WheelKeys(wheel, typography)
        }
    }
}

@Composable
private fun Wheel(
    size: Dp,
    wheel: StartWheelSnapshot,
    itemVisuals: Map<StartWheelAction, WheelItemVisual>,
    accent: Color,
    inkOnAccent: Color,
    compact: Boolean,
    typography: WheelTypography
) {
    val density = LocalDensity.current
    val radiusPx = with(density) { (size / 2).toPx() }
    val cutPx = with(density) { (if (compact) 2.dp else 2.5.dp).toPx() }
    val hairPx = with(density) { 0.75f.dp.toPx() }
    val rimPx = with(density) { (if (compact) 1.5f.dp else 1.75f.dp).toPx() }
    val needleLength = with(density) { (if (compact) 7.dp else 9.dp).toPx() }
    val needleStroke = with(density) { (if (compact) 2.dp else 2.4f.dp).toPx() }
    val geometry = remember(radiusPx, cutPx, hairPx, rimPx, needleLength, needleStroke) {
        WheelGeometry(radiusPx, cutPx, hairPx, rimPx, needleLength, needleStroke)
    }
    val expanded = wheel.outerRingActive
    // The outer tier outlives its category by one fade so it never blinks off.
    var ringCategory by remember { mutableStateOf<StartWheelCategory?>(null) }
    if (wheel.category != null) ringCategory = wheel.category
    val ringAlpha by animateFloatAsState(
        targetValue = when {
            wheel.category == null -> 0f
            expanded -> 1f
            else -> PREVIEW_ALPHA
        },
        animationSpec = tween(RING_ANIMATION_MS),
        label = "startWheelRingAlpha"
    )
    val ringPulse = remember { Animatable(1f) }
    LaunchedEffect(expanded) {
        if (expanded) {
            ringPulse.snapTo(OPEN_PULSE_SCALE)
            ringPulse.animateTo(1f, tween(OPEN_ANIMATION_MS, easing = FastOutSlowInEasing))
        }
    }
    // On open the disc grows out of the hub while the cuts run out to the rim.
    val open = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        open.animateTo(1f, tween(OPEN_ANIMATION_MS, easing = FastOutSlowInEasing))
    }
    // The opening in the rim faces the highlighted slice, so it is the wheel's cursor. With the
    // outer tier open and nothing picked in it, it keeps facing the category that was opened.
    val pointerTarget = when {
        wheel.slot >= 0 -> wheel.slot * SECTOR_DEGREES.toFloat()
        expanded -> (wheel.category?.ordinal ?: 2) * SECTOR_DEGREES.toFloat()
        else -> REST_POINTER_DEGREES
    }
    val pointer = remember { Animatable(REST_POINTER_DEGREES) }
    LaunchedEffect(pointerTarget) {
        // Take the short way round the rim.
        var shortest = (pointerTarget - pointer.value).mod(360f)
        if (shortest > 180f) shortest -= 360f
        pointer.animateTo(pointer.value + shortest, tween(RING_ANIMATION_MS, easing = FastOutSlowInEasing))
    }

    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        // A soft ring of moonlight hugging the rim of the disc as it stands, inner tier only or both.
        val haloEdge = geometry.categoryOuter + (geometry.wheelEdge - geometry.categoryOuter) * ringAlpha
        Box(
            modifier = Modifier
                .requiredSize(size * 1.3f)
                .graphicsLayer { alpha = open.value }
                .drawBehind {
                    val reach = haloEdge * 1.3f
                    drawCircle(
                        Brush.radialGradient(
                            0f to Color.Transparent,
                            0.70f to Color.Transparent,
                            0.765f to Mint.copy(alpha = 0.10f),
                            1f to Color.Transparent,
                            center = center,
                            radius = reach
                        ),
                        radius = reach
                    )
                }
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val scale = 0.86f + 0.14f * open.value
                    scaleX = scale
                    scaleY = scale
                    alpha = open.value
                }
        ) {
            CategoryTier(geometry, wheel, itemVisuals, accent, compact, typography)
            val category = ringCategory
            if (category != null && ringAlpha > 0.01f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = ringAlpha
                            scaleX = ringPulse.value
                            scaleY = ringPulse.value
                        }
                ) {
                    key(category) {
                        ActionTier(geometry, wheel, category, itemVisuals, accent, inkOnAccent, compact, typography)
                    }
                }
            }
            WheelLines(geometry, wheel, accent, inkOnAccent, open.value, ringAlpha, pointer.value)
            WheelHub(
                geometry = geometry,
                wheel = wheel,
                itemVisuals = itemVisuals,
                accent = accent,
                typography = typography,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

/**
 * The quarter slices of one tier. The cuts between them are erased so the game shows through.
 * A [washes] entry tints its slice without hiding its own fill.
 */
@Composable
private fun TierSlices(geometry: WheelGeometry, outer: Boolean, fills: List<Color>, washes: List<Color>? = null) {
    val sectors = if (outer) geometry.actionSectors else geometry.categorySectors
    val radius = geometry.radius
    // Light from above and a darker lower edge make the flat fills read as one glassy disc.
    val sheen = remember(geometry) {
        Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.08f),
            0.42f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.17f),
            start = Offset(radius, 0f),
            end = Offset(radius, radius * 2f)
        )
    }
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    ) {
        for (sector in 0 until SECTOR_COUNT) {
            drawPath(sectors[sector], fills[sector])
            washes?.get(sector)?.let { wash -> drawPath(sectors[sector], wash) }
            drawPath(sectors[sector], sheen)
        }
        // A cut may run a little past its tier: what lies beyond is transparent anyway.
        val from = (if (outer) geometry.actionInner else geometry.ringInner) - geometry.cutPx
        val to = (if (outer) geometry.wheelEdge else geometry.categoryOuter) + geometry.cutPx
        for (boundary in 0 until SECTOR_COUNT) {
            val (start, end) = geometry.cutLine(boundary, from, to)
            drawLine(
                Color.Black,
                start,
                end,
                strokeWidth = geometry.cutPx,
                cap = StrokeCap.Round,
                blendMode = BlendMode.Clear
            )
        }
    }
}

/** Animated fill and ink of one slice, tinted by how it currently relates to the selection. */
@Composable
private fun sliceColors(fill: Color, ink: Color): Pair<Color, Color> {
    val animatedFill by animateColorAsState(fill, tween(SELECTION_ANIMATION_MS), label = "startWheelFill")
    val animatedInk by animateColorAsState(ink, tween(SELECTION_ANIMATION_MS), label = "startWheelInk")
    return animatedFill to animatedInk
}

/** The inner tier: the four categories, each marked with the state of its own shortcut. */
@Composable
private fun CategoryTier(
    geometry: WheelGeometry,
    wheel: StartWheelSnapshot,
    itemVisuals: Map<StartWheelAction, WheelItemVisual>,
    accent: Color,
    compact: Boolean,
    typography: WheelTypography
) {
    val expanded = wheel.outerRingActive
    val parent = wheel.category?.ordinal ?: -1
    val fills = ArrayList<Color>(SECTOR_COUNT)
    val inks = ArrayList<Color>(SECTOR_COUNT)
    for (sector in 0 until SECTOR_COUNT) {
        val selected = !expanded && wheel.slot == sector
        val isParent = expanded && sector == parent
        // While one slice is picked, the others of its tier step back.
        val stepsBack = if (expanded) !isParent else wheel.slot >= 0 && !selected
        val (fill, ink) = sliceColors(
            fill = when {
                selected -> accent
                isParent -> ParentFill
                else -> CategoryFill
            },
            ink = when {
                selected -> accent.contentInk()
                stepsBack -> WheelText.copy(alpha = if (expanded) 0.48f else 0.62f)
                else -> WheelText
            }
        )
        fills += fill
        inks += ink
    }
    TierSlices(geometry, outer = false, fills = fills)
    for (sector in 0 until SECTOR_COUNT) {
        SectorLabel(
            geometry = geometry,
            outer = false,
            sector = sector,
            ink = inks[sector],
            icon = CategoryIconRes[sector],
            title = stringResource(CategoryTitleRes[sector]),
            showTitle = true,
            state = itemVisuals[StartWheelLayout.categoryDefault(StartWheelCategory.entries[sector])]?.state
                ?: ItemState.NONE,
            compact = compact,
            typography = typography
        )
    }
}

/** The outer tier: the items of the open category, or a quiet preview of them. */
@Composable
private fun ActionTier(
    geometry: WheelGeometry,
    wheel: StartWheelSnapshot,
    category: StartWheelCategory,
    itemVisuals: Map<StartWheelAction, WheelItemVisual>,
    accent: Color,
    inkOnAccent: Color,
    compact: Boolean,
    typography: WheelTypography
) {
    // While the tier is only a preview of a highlighted category, nothing in it is selected.
    val expanded = wheel.outerRingActive
    val items = ArrayList<WheelItemVisual?>(SECTOR_COUNT)
    val fills = ArrayList<Color>(SECTOR_COUNT)
    val inks = ArrayList<Color>(SECTOR_COUNT)
    val washes = ArrayList<Color>(SECTOR_COUNT)
    for (sector in 0 until SECTOR_COUNT) {
        val item = StartWheelLayout.ringAction(category, sector)?.let(itemVisuals::get)
        val selected = expanded && wheel.slot == sector
        // An empty slot takes the cursor but runs nothing, so it is only tinted instead of lit up.
        val empty = selected && wheel.emptySlot
        items += item
        val (fill, ink) = sliceColors(
            fill = if (selected && !empty) accent else ActionFill,
            ink = when {
                selected -> inkOnAccent
                item == null -> WheelText
                item.danger -> WheelDanger
                expanded && wheel.slot >= 0 -> WheelText.copy(alpha = 0.62f)
                else -> WheelText
            }
        )
        fills += fill
        inks += ink
        washes += if (empty) accent.copy(alpha = EMPTY_SLOT_TINT) else Color.Transparent
    }
    TierSlices(geometry, outer = true, fills = fills, washes = washes)
    for (sector in 0 until SECTOR_COUNT) {
        val item = items[sector] ?: continue
        SectorLabel(
            geometry = geometry,
            outer = true,
            sector = sector,
            ink = inks[sector],
            icon = item.icon,
            title = item.title,
            // Icons alone are enough while the tier is only a preview.
            showTitle = expanded,
            state = if (expanded) item.state else ItemState.NONE,
            compact = compact,
            typography = typography
        )
    }
}

/**
 * Everything drawn as a line: the cuts, the rims and the needle.
 *
 * The rims follow the Settings icon, a ring with one opening and an accent tick inside it. The
 * opening faces the highlighted slice, which makes it the wheel's cursor. It sits on the category
 * rim until the outer tier opens, then moves out to the outer rim.
 */
@Composable
private fun WheelLines(
    geometry: WheelGeometry,
    wheel: StartWheelSnapshot,
    accent: Color,
    inkOnAccent: Color,
    open: Float,
    ringAlpha: Float,
    pointerDegrees: Float
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        drawCircle(Mint.copy(alpha = 0.50f), radius = geometry.hubRadius, style = Stroke(geometry.hairPx * 1.3f))

        // Cuts run out of the hub as the wheel opens, and reach the rim only once the outer tier is there.
        val cutEnd = geometry.categoryOuter + (geometry.wheelEdge - geometry.categoryOuter) * ringAlpha
        val to = geometry.cutInner + (cutEnd - geometry.cutInner) * open
        val cutColor = Mint.copy(alpha = 0.55f)
        for (boundary in 0 until SECTOR_COUNT) {
            val (start, end) = geometry.cutLine(boundary, geometry.cutInner, to)
            drawLine(cutColor, start, end, strokeWidth = geometry.hairPx, cap = StrokeCap.Round)
        }

        // How far the opening has moved from the category rim out to the outer rim.
        val outward = ((ringAlpha - PREVIEW_ALPHA) / (1f - PREVIEW_ALPHA)).coerceIn(0f, 1f)
        val expanded = wheel.outerRingActive

        // The seam between the tiers comes and goes with the outer tier.
        drawCircle(Mint.copy(alpha = 0.24f * ringAlpha), radius = geometry.seamRadius, style = Stroke(geometry.hairPx))
        if (expanded) {
            // A short stroke on the seam marks the category whose items are open.
            val parent = wheel.category?.ordinal ?: -1
            if (parent >= 0) {
                drawArc(
                    color = accent,
                    startAngle = parent * SECTOR_DEGREES - SECTOR_DEGREES - MARK_DEGREES / 2f,
                    sweepAngle = MARK_DEGREES,
                    useCenter = false,
                    topLeft = Offset(centre.x - geometry.seamRadius, centre.y - geometry.seamRadius),
                    size = Size(geometry.seamRadius * 2f, geometry.seamRadius * 2f),
                    style = Stroke(geometry.markPx, cap = StrokeCap.Round)
                )
            }
        }

        // While the outer tier is only a preview it is a closed ring; it takes over the opening as it opens.
        drawOpenRing(
            geometry.wheelEdge, geometry.rimPx, Mint.copy(alpha = 0.62f * ringAlpha),
            OPENING_DEGREES * outward, pointerDegrees, centre
        )
        drawOpenRing(
            geometry.categoryOuter, geometry.rimPx * 0.85f, Mint.copy(alpha = 0.62f),
            OPENING_DEGREES * (1f - outward), pointerDegrees, centre
        )
        // The needle is the accent tick of the icon. Beside the accent-filled slice it turns to ink.
        val sliceFilled = wheel.slot >= 0 && !wheel.emptySlot
        drawNeedle(
            geometry.categoryOuter, geometry.rimPx * 0.85f, if (sliceFilled && !expanded) inkOnAccent else accent,
            1f - outward, pointerDegrees, centre, geometry
        )
        drawNeedle(
            geometry.wheelEdge, geometry.rimPx, if (sliceFilled && expanded) inkOnAccent else accent,
            outward, pointerDegrees, centre, geometry
        )
    }
}

/** A ring with one opening of [openingDegrees] centred on [pointerDegrees] (clockwise from twelve). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawOpenRing(
    radius: Float,
    width: Float,
    color: Color,
    openingDegrees: Float,
    pointerDegrees: Float,
    centre: Offset
) {
    if (color.alpha <= 0.01f || openingDegrees >= 359.5f) return
    drawArc(
        color = color,
        // Canvas angles start at three o'clock.
        startAngle = pointerDegrees - SECTOR_DEGREES + openingDegrees / 2f,
        sweepAngle = 360f - openingDegrees,
        useCenter = false,
        topLeft = Offset(centre.x - radius, centre.y - radius),
        size = Size(radius * 2f, radius * 2f),
        style = Stroke(width, cap = StrokeCap.Round)
    )
}

/**
 * A short radial tick in the rim's opening: the accent tick of the Settings icon, and the wheel's
 * cursor. It sits just outside the rim, so it never runs into a label.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNeedle(
    ringRadius: Float,
    ringWidth: Float,
    color: Color,
    alpha: Float,
    degrees: Float,
    centre: Offset,
    geometry: WheelGeometry
) {
    if (alpha <= 0.01f) return
    val tail = ringRadius + ringWidth * 1.5f
    val angle = Math.toRadians(degrees.toDouble())
    val towardsX = sin(angle).toFloat()
    val towardsY = -cos(angle).toFloat()
    drawLine(
        color = color.copy(alpha = color.alpha * alpha),
        start = Offset(centre.x + towardsX * tail, centre.y + towardsY * tail),
        end = Offset(centre.x + towardsX * (tail + geometry.needleLengthPx), centre.y + towardsY * (tail + geometry.needleLengthPx)),
        strokeWidth = geometry.needlePx,
        cap = StrokeCap.Round
    )
}

/** Icon and name of one slice, centred on that slice's share of its tier. */
@Composable
private fun SectorLabel(
    geometry: WheelGeometry,
    outer: Boolean,
    sector: Int,
    ink: Color,
    @DrawableRes icon: Int,
    title: String,
    showTitle: Boolean,
    state: ItemState,
    compact: Boolean,
    typography: WheelTypography
) {
    val widthPx = geometry.labelWidth(outer, sector)
    val center = geometry.labelCenter(outer, sector)
    val density = LocalDensity.current
    val titleAlpha by animateFloatAsState(
        targetValue = if (showTitle) 1f else 0f,
        animationSpec = tween(RING_ANIMATION_MS),
        label = "startWheelTitleAlpha"
    )
    val base = if (outer) typography.itemTitle else typography.categoryTitle
    val weight = if (outer) FontWeight.Medium else FontWeight.SemiBold
    val maxLine = with(density) { geometry.labelMaxLine(outer, sector).toDp() }
    // A side sector is narrow along the text direction, so a two-word title is stacked rather than
    // shrunk. A Chinese name has no space to stack at: one that would have to shrink too much is
    // split in half.
    val side = sector in SideSectors
    val stacked = side && title.count { it == ' ' } == 1
    var lines = if (stacked) title.split(' ') else listOf(title)
    var fit = fitText(lines, base, weight, maxLine, density)
    if (side && !stacked && fit.clamped) {
        splitInHalf(title)?.let { split ->
            lines = split.split('\n')
            fit = fitText(lines, base, weight, maxLine, density)
        }
    }

    Layout(
        // The label takes the height it needs, so a large system font is not cut off.
        content = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = null,
                        tint = ink,
                        modifier = Modifier.size(if (outer) typography.itemIcon else typography.categoryIcon)
                    )
                    if (state != ItemState.NONE) {
                        // A filled dot for on, a hollow one for off: the item's state without a word.
                        val dot = typography.dot
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = dot / 3, y = -dot / 3)
                                .size(dot)
                                .clip(CircleShape)
                                .background(if (state == ItemState.ON) ink else Color.Transparent)
                                .border(1.dp, ink, CircleShape)
                        )
                    }
                }
                if (titleAlpha > 0.01f) {
                    Text(
                        text = lines.joinToString("\n"),
                        modifier = Modifier.graphicsLayer { alpha = titleAlpha },
                        color = ink,
                        fontSize = fit.fontSize,
                        lineHeight = if (outer) typography.itemTitleLine else typography.categoryTitleLine,
                        fontWeight = weight,
                        textAlign = TextAlign.Center,
                        maxLines = 2
                    )
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    ) { measurables, constraints ->
        // Placed in the canvas coordinates of the disc, which layout direction never mirrors.
        val label = measurables.first().measure(
            Constraints(maxWidth = widthPx.roundToInt(), maxHeight = Constraints.Infinity)
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            label.place(
                (center.x - label.width / 2f).roundToInt(),
                (center.y - label.height / 2f).roundToInt()
            )
        }
    }
}

/** The hub: the moon-wheel icon while nothing would run, otherwise the item that would. */
@Composable
private fun WheelHub(
    geometry: WheelGeometry,
    wheel: StartWheelSnapshot,
    itemVisuals: Map<StartWheelAction, WheelItemVisual>,
    accent: Color,
    typography: WheelTypography,
    modifier: Modifier = Modifier
) {
    val highlighted = wheel.highlighted?.let(itemVisuals::get)
    val markAlpha by animateFloatAsState(
        targetValue = if (highlighted == null) 1f else 0f,
        animationSpec = tween(RING_ANIMATION_MS),
        label = "startWheelMarkAlpha"
    )
    val itemAlpha by animateFloatAsState(
        targetValue = if (highlighted != null) 1f else 0f,
        animationSpec = tween(RING_ANIMATION_MS),
        label = "startWheelHubItemAlpha"
    )
    val density = LocalDensity.current
    val size = with(density) { (geometry.hubRadius * 2).toDp() }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(HubFill),
        contentAlignment = Alignment.Center
    ) {
        if (markAlpha > 0.01f) {
            MoonWheelMark(
                accent = accent,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = markAlpha
                        val scale = 0.8f + 0.2f * markAlpha
                        scaleX = scale
                        scaleY = scale
                    }
            )
        }
        if (highlighted != null) {
            // The item's icon alone: its name is captioned under the disc, where it has room.
            Box(
                modifier = Modifier.graphicsLayer {
                    alpha = itemAlpha
                    val scale = 0.9f + 0.1f * itemAlpha
                    scaleX = scale
                    scaleY = scale
                }
            ) {
                Icon(
                    painter = painterResource(highlighted.icon),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(typography.hubIcon)
                )
                if (highlighted.state != ItemState.NONE) {
                    val dot = typography.dot
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = dot / 2, y = -dot / 2)
                            .size(dot)
                            .clip(CircleShape)
                            .background(
                                if (highlighted.state == ItemState.ON) accent else Color.Transparent
                            )
                            .border(1.dp, accent, CircleShape)
                    )
                }
            }
        }
    }
}

/**
 * The name of what releasing Start would run, in one line under the disc. The line keeps its height
 * whether or not there is a name, so the disc never moves as the selection changes.
 */
@Composable
private fun WheelCaption(
    wheel: StartWheelSnapshot,
    itemVisuals: Map<StartWheelAction, WheelItemVisual>,
    typography: WheelTypography,
    rowHeight: Dp
) {
    val turnNote = stringResource(R.string.start_wheel_core_turn)
    val caption = when {
        wheel.highlighted != null -> itemVisuals[wheel.highlighted]?.title
        !wheel.armed -> turnNote
        else -> null
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight),
        contentAlignment = Alignment.Center
    ) {
        Crossfade(
            targetState = caption,
            animationSpec = tween(CAPTION_FADE_MS),
            label = "startWheelCaption"
        ) { text ->
            if (text != null) {
                Text(
                    text = text,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = WheelText.copy(alpha = 0.92f),
                    fontSize = typography.caption,
                    lineHeight = typography.captionLine,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * The app's moon-wheel icon, the one on the Settings button: eight spokes inside a ring that opens
 * downwards, with a tick in the accent where it opens. Drawn to its own proportions.
 */
@Composable
private fun MoonWheelMark(accent: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val ring = size.minDimension / 2f * 0.74f
        drawArc(
            color = WheelText,
            startAngle = 112.5f,
            sweepAngle = 315f,
            useCenter = false,
            topLeft = Offset(centre.x - ring, centre.y - ring),
            size = Size(ring * 2f, ring * 2f),
            style = Stroke(ring * 0.2f, cap = StrokeCap.Round)
        )
        for (index in 0 until 8) {
            val angle = Math.toRadians((index * 45f - 90f).toDouble())
            // The bottom spoke stops short and carries the accent out towards the opening.
            val length = ring * if (index == 4) 0.467f else 0.722f
            drawLine(
                color = WheelText,
                start = centre,
                end = Offset(centre.x + cos(angle).toFloat() * length, centre.y + sin(angle).toFloat() * length),
                strokeWidth = ring * 0.172f,
                cap = StrokeCap.Round
            )
        }
        drawLine(
            color = accent,
            start = Offset(centre.x, centre.y + ring * 0.467f),
            end = Offset(centre.x, centre.y + ring * 0.9f),
            strokeWidth = ring * 0.19f,
            cap = StrokeCap.Round
        )
    }
}

/**
 * The same name with a line break in the middle, if it is one word of a script that is written
 * without spaces and may break between any two characters. The longer half goes first, so the two
 * lines are as even as they can be.
 */
private fun splitInHalf(text: String): String? {
    val count = text.codePointCount(0, text.length)
    if (count < 3) return null
    var index = 0
    while (index < text.length) {
        val block = Character.UnicodeBlock.of(text.codePointAt(index))?.toString() ?: return null
        if (!block.startsWith("CJK") && block != "HIRAGANA" && block != "KATAKANA" && block != "HANGUL_SYLLABLES") {
            return null
        }
        index += Character.charCount(text.codePointAt(index))
    }
    val middle = text.offsetByCodePoints(0, (count + 1) / 2)
    return text.substring(0, middle) + "\n" + text.substring(middle)
}

/** Share of the available width a fitted label may claim. */
private const val FIT_SLACK = 0.96f

/** How long the caption takes to fade from one name to the next. */
private const val CAPTION_FADE_MS = 120

/** The three keys that drive the wheel and what each one does in the current tier. */
@Composable
private fun WheelKeys(wheel: StartWheelSnapshot, typography: WheelTypography) {
    val backLabel = if (wheel.outerRingActive) {
        stringResource(R.string.start_wheel_control_back)
    } else {
        stringResource(R.string.start_wheel_control_cancel)
    }
    // Deeper only makes sense once a category is highlighted to expand.
    val canGoDeeper = !wheel.outerRingActive && wheel.highlighted != null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(typography.keyGap)
    ) {
        KeyHint("LB", backLabel, emphasized = false, enabled = true, typography = typography)
        KeyHint(
            "RB",
            stringResource(R.string.start_wheel_control_deeper),
            emphasized = false,
            enabled = canGoDeeper,
            typography = typography
        )
        KeyHint(
            "START",
            stringResource(R.string.start_wheel_control_run),
            emphasized = true,
            enabled = true,
            typography = typography
        )
    }
}

/** A key cap and the short word for what pressing it does right now. */
@Composable
private fun KeyHint(
    key: String,
    label: String,
    emphasized: Boolean,
    enabled: Boolean,
    typography: WheelTypography
) {
    val capShape = RoundedCornerShape(6.dp)
    val ink = WheelText.copy(alpha = if (emphasized) 0.96f else 0.80f)
    Row(
        modifier = Modifier.graphicsLayer { alpha = if (enabled) 1f else 0.34f },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(
            text = key,
            modifier = Modifier
                .clip(capShape)
                .border(1.dp, ink.copy(alpha = if (emphasized) 0.9f else 0.55f), capShape)
                .padding(horizontal = typography.keyCapPaddingH, vertical = typography.keyCapPaddingV),
            color = ink,
            fontSize = typography.keyCap,
            lineHeight = (typography.keyCap.value + 2f).sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Text(
            text = label,
            color = ink,
            fontSize = typography.keyLabel,
            lineHeight = typography.captionLine,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

/**
 * Names and icons of every wheel item, following the live state of the feature each one switches.
 * An item whose feature is not set up on this device is left out, so its slot reads as empty.
 */
@Composable
private fun startWheelItemVisuals(
    features: StartWheelFeatureState
): Map<StartWheelAction, WheelItemVisual> {
    val perfHidden = features.perfOverlayMode == StartWheelPerfMode.HIDDEN
    val items = LinkedHashMap<StartWheelAction, WheelItemVisual>()
    items[StartWheelAction.MENU] = WheelItemVisual(
        title = stringResource(R.string.start_wheel_item_menu),
        icon = R.drawable.ic_start_wheel_menu
    )
    items[StartWheelAction.MOUSE] = WheelItemVisual(
        title = stringResource(R.string.start_wheel_item_mouse),
        icon = R.drawable.ic_start_wheel_mouse,
        state = if (features.mouseEmulationActive) ItemState.ON else ItemState.OFF
    )
    items[StartWheelAction.KEYBOARD] = WheelItemVisual(
        title = stringResource(R.string.start_wheel_item_keyboard),
        icon = R.drawable.ic_start_wheel_keyboard,
        state = if (features.keyboardVisible) ItemState.ON else ItemState.OFF
    )
    items[StartWheelAction.PERFORMANCE_TOGGLE] = WheelItemVisual(
        title = stringResource(
            if (perfHidden) R.string.start_wheel_item_perf_show else R.string.start_wheel_item_perf_hide
        ),
        icon = if (perfHidden) R.drawable.ic_start_wheel_overlay_show else R.drawable.ic_start_wheel_overlay_hide,
        state = if (perfHidden) ItemState.OFF else ItemState.ON
    )
    items[StartWheelAction.DISCONNECT] = WheelItemVisual(
        title = stringResource(R.string.start_wheel_item_disconnect),
        icon = R.drawable.ic_start_wheel_disconnect,
        danger = true
    )
    items[StartWheelAction.HOST_SLEEP] = WheelItemVisual(
        title = stringResource(R.string.start_wheel_item_host_sleep),
        icon = R.drawable.ic_btn_sleep,
        danger = true
    )
    items[StartWheelAction.QUIT_AND_STOP] = WheelItemVisual(
        title = stringResource(R.string.start_wheel_item_quit),
        icon = R.drawable.ic_btn_quit,
        danger = true
    )
    if (features.virtualControllerAvailable) {
        items[StartWheelAction.VIRTUAL_CONTROLLER] = WheelItemVisual(
            title = stringResource(R.string.start_wheel_item_virtual_controller),
            icon = R.drawable.ic_controller_cute,
            state = if (features.virtualControllerVisible) ItemState.ON else ItemState.OFF
        )
    }
    if (features.gyroAvailable) {
        items[StartWheelAction.GYRO] = WheelItemVisual(
            title = stringResource(R.string.start_wheel_item_gyro),
            icon = R.drawable.ic_start_wheel_gyro,
            state = if (features.gyroEnabled) ItemState.ON else ItemState.OFF
        )
    }
    if (features.micAvailable) {
        items[StartWheelAction.MIC] = WheelItemVisual(
            title = stringResource(R.string.start_wheel_item_mic),
            icon = R.drawable.ic_mic_gm,
            state = if (features.micActive) ItemState.ON else ItemState.OFF
        )
    }
    val keys = features.keyRing.ifEmpty {
        listOf(
            StartWheelKeyItem(stringResource(R.string.start_wheel_item_esc), R.drawable.ic_btn_esc),
            StartWheelKeyItem(stringResource(R.string.start_wheel_item_win), R.drawable.ic_btn_win),
            StartWheelKeyItem(stringResource(R.string.start_wheel_item_alt_tab), R.drawable.ic_start_wheel_alt_tab)
        )
    }
    items[StartWheelAction.KEY_1] = WheelItemVisual(keys[0].title, keys[0].icon)
    items[StartWheelAction.KEY_2] = WheelItemVisual(keys[1].title, keys[1].icon)
    items[StartWheelAction.KEY_3] = WheelItemVisual(keys[2].title, keys[2].icon)
    return items
}

/** How large a label is drawn, and whether it had to stop shrinking while still too wide. */
private class FittedText(val fontSize: TextUnit, val clamped: Boolean)

/**
 * Largest size at which every line of [lines] stays on one line within [maxWidth]. A label that
 * would otherwise run out of its sector, a long word in a side sector say, gives up size down to
 * a floor; [clamped] says the floor was not enough.
 */
@Composable
private fun fitText(
    lines: List<String>,
    base: TextUnit,
    fontWeight: FontWeight,
    maxWidth: Dp,
    density: Density
): FittedText {
    val textMeasurer = rememberTextMeasurer()
    return remember(textMeasurer, lines, base, fontWeight, maxWidth) {
        val allowed = with(density) { maxWidth.toPx() } * FIT_SLACK
        val measured = lines.maxOfOrNull { line ->
            textMeasurer.measure(
                text = AnnotatedString(line),
                style = TextStyle(fontSize = base, fontWeight = fontWeight),
                density = density,
                softWrap = false,
                maxLines = 1
            ).size.width.toFloat()
        } ?: 0f
        val scale = if (measured <= 0f) 1f else allowed / measured
        FittedText(
            fontSize = (base.value * scale.coerceAtMost(1f).coerceAtLeast(MIN_TEXT_SCALE)).sp,
            clamped = scale < MIN_TEXT_SCALE
        )
    }
}

/**
 * Ink that stays readable on the accent. Both brand accents are light pinks, and so is almost
 * anything a wallpaper-derived accent turns out to be, so dark ink is the default.
 */
private fun Color.contentInk(): Color = if (luminance() > ACCENT_INK_LUMINANCE) Color(0xFF12161B) else Color.White

private const val ACCENT_INK_LUMINANCE = 0.30f

/** Pixel geometry of the wheel. Distances are fractions of the wheel's radius unless noted. */
private class WheelGeometry(
    val radius: Float,
    /** Width of the cut between two slices. */
    val cutPx: Float,
    val hairPx: Float,
    val rimPx: Float,
    val needleLength: Float,
    val needleStroke: Float
) {
    val hubRadius = radius * 0.215f
    val ringInner = radius * 0.25f
    val categoryOuter = radius * 0.60f
    val actionInner = radius * 0.625f

    /** Fills stop just inside the rim stroke so the two never overlap. */
    val wheelEdge = radius - rimPx
    val seamRadius = (categoryOuter + actionInner) / 2f
    val cutInner = hubRadius + hairPx * 2f
    val markPx = rimPx * 1.5f

    /** The needle inside the rim's opening: its length and its stroke. */
    val needleLengthPx = needleLength
    val needlePx = needleStroke
    private val categoryLabelRadius = (ringInner + categoryOuter) / 2f
    private val actionLabelRadius = (actionInner + wheelEdge) / 2f

    val categorySectors = Array(SECTOR_COUNT) { sectorPath(outer = false, sector = it) }
    val actionSectors = Array(SECTOR_COUNT) { sectorPath(outer = true, sector = it) }

    /** Outline of one full quarter, numbered clockwise from the top. */
    private fun sectorPath(outer: Boolean, sector: Int): Path {
        val innerRadius = if (outer) actionInner else ringInner
        val outerRadius = if (outer) wheelEdge else categoryOuter
        // Canvas arcs start at three o'clock; the first quarter is centred on twelve.
        val start = sector * SECTOR_DEGREES - SECTOR_DEGREES - SECTOR_DEGREES / 2f
        return Path().apply {
            arcTo(
                rect = Rect(radius - outerRadius, radius - outerRadius, radius + outerRadius, radius + outerRadius),
                startAngleDegrees = start,
                sweepAngleDegrees = SECTOR_DEGREES,
                forceMoveTo = true
            )
            arcTo(
                rect = Rect(radius - innerRadius, radius - innerRadius, radius + innerRadius, radius + innerRadius),
                startAngleDegrees = start + SECTOR_DEGREES,
                sweepAngleDegrees = -SECTOR_DEGREES,
                forceMoveTo = false
            )
            close()
        }
    }

    /** Centre of a slice's label, resting halfway across its tier. */
    fun labelCenter(outer: Boolean, sector: Int): Offset =
        polar(if (outer) actionLabelRadius else categoryLabelRadius, sector * SECTOR_DEGREES)

    /** The cut between two neighbouring slices, between two distances from the centre. */
    fun cutLine(boundary: Int, from: Float, to: Float): Pair<Offset, Offset> {
        val degrees = SECTOR_DEGREES / 2f + boundary * SECTOR_DEGREES
        return polar(from, degrees) to polar(to, degrees)
    }

    /** Degrees are clockwise from twelve o'clock. */
    private fun polar(distance: Float, degrees: Float): Offset {
        val angle = Math.toRadians(degrees.toDouble())
        return Offset(
            x = radius + sin(angle).toFloat() * distance,
            y = radius - cos(angle).toFloat() * distance
        )
    }

    /**
     * Label boxes follow the slice's shape: the top and bottom slices are wide but shallow, the side
     * slices the other way round, so text hugs the tier it belongs to. Side boxes run a little past
     * the tier so a long single word overflows a thin line instead of being broken mid-word.
     */
    fun labelWidth(outer: Boolean, sector: Int): Float {
        val side = sector in SideSectors
        return radius * when {
            outer && side -> 0.54f
            outer -> 0.70f
            side -> 0.50f
            else -> 0.64f
        }
    }

    /** Width a single text line may take before it would leave its slice. */
    fun labelMaxLine(outer: Boolean, sector: Int): Float {
        if (sector !in SideSectors) return labelWidth(outer, sector)
        // Side text runs across the tier, so the tier's own thickness is the limit.
        return radius * if (outer) 0.34f else 0.31f
    }
}
