package com.limelight.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.R
import com.limelight.binding.input.StartWheelAction
import com.limelight.binding.input.StartWheelLayout
import com.limelight.binding.input.StartWheelSnapshot
import com.limelight.utils.appAccentColor
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Live state of the features the Start wheel switches; each one is marked on its item. */
@Immutable
internal data class StartWheelFeatureState(
    val perfOverlayVisible: Boolean = false,
    val gyroEnabled: Boolean = false,
    val mouseEmulationActive: Boolean = false,
    val keyboardVisible: Boolean = false,
    val micActive: Boolean = false,
    /** Items whose feature is not set up on this device are left out, as in the game menu. */
    val unavailable: Set<StartWheelAction> = emptySet()
)

/** Everything the Start hold wheel overlay draws: the wheel itself, or the disconnect it can call off. */
@Immutable
internal data class StartWheelUiState(
    val wheel: StartWheelSnapshot = StartWheelSnapshot(),
    val features: StartWheelFeatureState = StartWheelFeatureState(),
    /** True while a chosen disconnect waits out its cancel window. */
    val undoVisible: Boolean = false
) {
    val visible: Boolean get() = wheel.visible
}

/** How long a chosen disconnect waits before it runs, leaving time to call it off with B. */
internal const val START_WHEEL_UNDO_MS = 2000L

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

// The wheel takes after the app's moon-wheel icon (the Settings button): a dark disc, light lines
// and a single accent. Mint is the colour of every line, charcoal of the disc.
private val OverlayScrim = Color.Black.copy(alpha = 0.38f)
private val WheelText = Color(0xFFF4F8F6)
private val SliceFill = Color(0xB8393E46)
private val HubFill = Color(0xF50E1216)
private val Mint = Color(0xFFDCF6FF)
private val WheelDanger = Color(0xFFFFB4A2)

private val WheelMaxSize = 460.dp
private val WheelMinSize = 200.dp
private val WheelCompactBelow = 340.dp

/** Room kept under the legend for the system navigation bar, so the key hints stay readable. */
private val FOOTER_SAFETY = 28.dp

private val SECTOR_COUNT = StartWheelLayout.slotCount
private val SECTOR_DEGREES = 360f / SECTOR_COUNT

/** The rim is a ring with one opening, as in the Settings icon; it is the wheel's cursor. */
private const val OPENING_DEGREES = 34f

/** With nothing highlighted the opening looks down, where the Settings icon has it. */
private const val REST_POINTER_DEGREES = 180f

private const val POINTER_ANIMATION_MS = 220
private const val SELECTION_ANIMATION_MS = 120
private const val OPEN_ANIMATION_MS = 300

/** Fitted labels never shrink below this share of their normal size. */
private const val MIN_TEXT_SCALE = 0.55f

/** The disc never takes more than this share of the screen height, so the game stays in view. */
private const val WHEEL_MAX_HEIGHT_SHARE = 0.68f

/** Share of the available width a fitted label may claim. */
private const val FIT_SLACK = 0.94f

/** Text and icon sizes that shrink together on phones in landscape, where the wheel is smallest. */
private class WheelTypography(compact: Boolean) {
    val itemIcon = if (compact) 22.dp else 26.dp
    val itemTitle = if (compact) 12.sp else 13.sp
    val itemTitleLine = if (compact) 15.sp else 17.sp
    val hubIcon = if (compact) 28.dp else 34.dp
    val dot = if (compact) 5.dp else 6.dp
    val keyCap = if (compact) 9.sp else 10.sp
    val keyLabel = if (compact) 11.sp else 12.sp
    val keyCapPaddingH = if (compact) 5.dp else 6.dp
    val keyCapPaddingV = if (compact) 2.dp else 3.dp
    val legendGap = if (compact) 10.dp else 16.dp
    val legendRow = if (compact) 20.dp else 24.dp
}

/**
 * The Start hold wheel: one disc cut into eight sectors around a hub.
 *
 * Pushing a stick or a D-pad direction highlights the sector it points at, and the highlight follows
 * the direction: back in the middle there is no highlight. Releasing whatever was pushed runs the
 * highlighted item; releasing with nothing highlighted runs nothing. It is drawn the way classic
 * in-game wheels are, one dark disc with thin cuts, icons and names always visible, and it borrows
 * its lines from the app's own moon-wheel icon: the rim is a ring with one opening and a needle that
 * turns towards whatever is highlighted, and the hub shows the icon itself while nothing would run.
 *
 * A disconnect runs through a short cancel window instead of a confirmation: the same overlay draws
 * a small prompt while B can still call it off.
 */
@Composable
internal fun StartHoldWheelOverlay(state: StartWheelUiState, modifier: Modifier = Modifier) {
    when {
        state.wheel.visible -> StartWheelDisc(state, modifier)
        state.undoVisible -> StartWheelUndoPrompt(modifier)
    }
}

@Composable
private fun StartWheelDisc(state: StartWheelUiState, modifier: Modifier = Modifier) {
    val wheel = state.wheel
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
        // The disc gets what the legend below it leaves, plus a margin over the system navigation bar,
        // and never more than its share of the height.
        val footer = typography.legendGap + typography.legendRow + FOOTER_SAFETY
        val wheelSize = minOf(
            maxHeight * WHEEL_MAX_HEIGHT_SHARE,
            maxHeight - footer,
            maxWidth * 0.86f,
            WheelMaxSize
        ).coerceAtLeast(WheelMinSize)
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Wheel(wheelSize, wheel, itemVisuals, accent, inkOnAccent, compact, typography)
            Spacer(Modifier.height(typography.legendGap))
            WheelLegend(typography)
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

    // The wheel grows out of its hub when it appears; the labels follow a moment later, clockwise.
    val open = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        open.animateTo(1f, tween(OPEN_ANIMATION_MS, easing = FastOutSlowInEasing))
    }
    // The opening in the rim faces the highlighted sector, so it is the wheel's cursor.
    val pointerTarget = if (wheel.slot >= 0) wheel.slot * SECTOR_DEGREES else REST_POINTER_DEGREES
    val pointer = remember { Animatable(REST_POINTER_DEGREES) }
    LaunchedEffect(pointerTarget) {
        // Take the short way round the rim.
        var shortest = (pointerTarget - pointer.value).mod(360f)
        if (shortest > 180f) shortest -= 360f
        pointer.animateTo(pointer.value + shortest, tween(POINTER_ANIMATION_MS, easing = FastOutSlowInEasing))
    }

    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        // A soft ring of moonlight hugging the rim.
        Box(
            modifier = Modifier
                .requiredSize(size * 1.3f)
                .graphicsLayer { alpha = open.value }
                .drawBehind {
                    val reach = geometry.wheelEdge * 1.3f
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
                    val scale = 0.88f + 0.12f * open.value
                    scaleX = scale
                    scaleY = scale
                    alpha = open.value
                }
        ) {
            WheelSlices(geometry, wheel, accent, inkOnAccent)
            for (sector in 0 until SECTOR_COUNT) {
                val action = StartWheelLayout.action(sector) ?: continue
                val visual = itemVisuals[action] ?: continue
                SectorLabel(
                    geometry = geometry,
                    sector = sector,
                    ink = if (wheel.slot == sector) inkOnAccent else if (visual.danger) WheelDanger else WheelText,
                    icon = visual.icon,
                    title = visual.title,
                    state = visual.state,
                    alpha = { ((open.value - sector * 0.04f) / 0.7f).coerceIn(0f, 1f) },
                    compact = compact,
                    typography = typography
                )
            }
            WheelLines(geometry, wheel, accent, inkOnAccent) { pointer.value }
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
 * The cancel window after choosing to disconnect. The game keeps the whole screen; only a small pill
 * appears over the bottom edge, counting down the time B still has to call the disconnect off.
 */
@Composable
private fun StartWheelUndoPrompt(modifier: Modifier = Modifier) {
    val remaining = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        remaining.animateTo(0f, tween(START_WHEEL_UNDO_MS.toInt(), easing = LinearEasing))
    }
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        val compact = minOf(maxHeight * 0.84f, maxWidth * 0.86f, WheelMaxSize) < WheelCompactBelow
        val typography = remember(compact) { WheelTypography(compact) }
        val shape = RoundedCornerShape(18.dp)
        Column(
            modifier = Modifier
                .padding(bottom = FOOTER_SAFETY + 16.dp)
                .clip(shape)
                .background(HubFill)
                .border(1.dp, Mint.copy(alpha = 0.18f), shape)
                .padding(horizontal = 22.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.start_wheel_undo_disconnecting),
                color = WheelText,
                fontSize = typography.itemTitle,
                lineHeight = typography.itemTitleLine,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Spacer(Modifier.height(8.dp))
            KeyHint("B", stringResource(R.string.start_wheel_control_cancel), emphasized = true, typography)
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .width(180.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(Mint.copy(alpha = 0.16f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(remaining.value)
                        .fillMaxHeight()
                        .align(Alignment.CenterStart)
                        .background(Mint)
                )
            }
        }
    }
}

/** The eight slices of the disc. The cuts between them are erased so the game shows through. */
@Composable
private fun WheelSlices(
    geometry: WheelGeometry,
    wheel: StartWheelSnapshot,
    accent: Color,
    inkOnAccent: Color
) {
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
    val fills = List(SECTOR_COUNT) { sector ->
        animateColorAsState(
            targetValue = if (wheel.slot == sector) accent else SliceFill,
            animationSpec = tween(SELECTION_ANIMATION_MS),
            label = "startWheelFill"
        )
    }
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    ) {
        for (sector in 0 until SECTOR_COUNT) {
            val path = geometry.sectorPath(sector)
            drawPath(path, fills[sector].value)
            drawPath(path, sheen)
        }
        // A cut may run a little past the rim: what lies beyond is transparent anyway.
        for (boundary in 0 until SECTOR_COUNT) {
            val (start, end) = geometry.cutLine(
                boundary,
                geometry.ringInner - geometry.cutPx,
                geometry.wheelEdge + geometry.cutPx
            )
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

/**
 * Everything drawn as a line: the cuts, the rim and the needle.
 *
 * The rim follows the Settings icon, a ring with one opening and an accent tick inside it. The
 * opening faces the highlighted sector, which makes it the wheel's cursor.
 */
@Composable
private fun WheelLines(
    geometry: WheelGeometry,
    wheel: StartWheelSnapshot,
    accent: Color,
    inkOnAccent: Color,
    pointerDegrees: () -> Float
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val degrees = pointerDegrees()
        val centre = Offset(size.width / 2f, size.height / 2f)
        drawCircle(Mint.copy(alpha = 0.50f), radius = geometry.hubRadius, style = Stroke(geometry.hairPx * 1.3f))

        // Cuts run out of the hub to the rim.
        val cutColor = Mint.copy(alpha = 0.55f)
        for (boundary in 0 until SECTOR_COUNT) {
            val (start, end) = geometry.cutLine(boundary, geometry.cutInner, geometry.wheelEdge)
            drawLine(cutColor, start, end, strokeWidth = geometry.hairPx, cap = StrokeCap.Round)
        }

        drawOpenRing(
            geometry.wheelEdge, geometry.rimPx, Mint.copy(alpha = 0.62f),
            OPENING_DEGREES, degrees, centre
        )
        // The needle is the accent tick of the icon. Beside the accent-filled slice it turns to ink.
        drawNeedle(
            geometry.wheelEdge, geometry.rimPx,
            if (wheel.slot >= 0 && !wheel.emptySlot) inkOnAccent else accent,
            degrees, centre, geometry
        )
    }
}

/** A ring with one opening of [openingDegrees] centred on [pointerDegrees] (clockwise from twelve). */
private fun DrawScope.drawOpenRing(
    radius: Float,
    width: Float,
    color: Color,
    openingDegrees: Float,
    pointerDegrees: Float,
    centre: Offset
) {
    drawArc(
        color = color,
        // Canvas angles start at three o'clock.
        startAngle = pointerDegrees - 90f + openingDegrees / 2f,
        sweepAngle = 360f - openingDegrees,
        useCenter = false,
        topLeft = Offset(centre.x - radius, centre.y - radius),
        size = Size(radius * 2f, radius * 2f),
        style = Stroke(width, cap = StrokeCap.Round)
    )
}

/** A short radial tick in the rim's opening: the accent tick of the Settings icon, the wheel's cursor. */
private fun DrawScope.drawNeedle(
    ringRadius: Float,
    ringWidth: Float,
    color: Color,
    degrees: Float,
    centre: Offset,
    geometry: WheelGeometry
) {
    val tail = ringRadius + ringWidth * 1.5f
    val angle = Math.toRadians(degrees.toDouble())
    val towardsX = sin(angle).toFloat()
    val towardsY = -cos(angle).toFloat()
    drawLine(
        color = color,
        start = Offset(centre.x + towardsX * tail, centre.y + towardsY * tail),
        end = Offset(
            centre.x + towardsX * (tail + geometry.needleLengthPx),
            centre.y + towardsY * (tail + geometry.needleLengthPx)
        ),
        strokeWidth = geometry.needlePx,
        cap = StrokeCap.Round
    )
}

/** Icon and name of one sector, centred at that sector's share of the disc. */
@Composable
private fun SectorLabel(
    geometry: WheelGeometry,
    sector: Int,
    ink: Color,
    @DrawableRes icon: Int,
    title: String,
    state: ItemState,
    alpha: () -> Float,
    compact: Boolean,
    typography: WheelTypography
) {
    val density = LocalDensity.current
    val center = geometry.labelCenter(sector)
    val maxLine = with(density) { (geometry.radius * 0.47f).toDp() }
    val titleSize = fittedFontSize(
        text = title,
        base = typography.itemTitle,
        fontWeight = FontWeight.Medium,
        maxWidth = maxLine,
        density = density
    )
    Layout(
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
                        modifier = Modifier.size(typography.itemIcon)
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
                Text(
                    text = title,
                    color = ink,
                    fontSize = titleSize,
                    lineHeight = typography.itemTitleLine,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha() }
    ) { measurables, constraints ->
        // Placed in the canvas coordinates of the disc, which layout direction never mirrors.
        val label = measurables.first().measure(
            Constraints(maxWidth = (geometry.radius * 0.9f).roundToInt(), maxHeight = Constraints.Infinity)
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
        animationSpec = tween(SELECTION_ANIMATION_MS * 2),
        label = "startWheelMarkAlpha"
    )
    val itemAlpha by animateFloatAsState(
        targetValue = if (highlighted != null) 1f else 0f,
        animationSpec = tween(SELECTION_ANIMATION_MS * 2),
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
        run {
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
            Icon(
                painter = painterResource(highlighted.icon),
                contentDescription = null,
                tint = accent,
                modifier = Modifier
                    .graphicsLayer {
                        alpha = itemAlpha
                        val scale = 0.82f + 0.18f * itemAlpha
                        scaleX = scale
                        scaleY = scale
                    }
                    .size(typography.hubIcon)
            )
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
        val ring = size.minDimension / 2f * 0.70f
        drawArc(
            color = WheelText,
            startAngle = 112.5f,
            sweepAngle = 315f,
            useCenter = false,
            topLeft = Offset(centre.x - ring, centre.y - ring),
            size = Size(ring * 2f, ring * 2f),
            style = Stroke(ring * 0.18f, cap = StrokeCap.Round)
        )
        for (index in 0 until 8) {
            val angle = Math.toRadians((index * 45f - 90f).toDouble())
            // The bottom spoke stops short and carries the accent out towards the opening.
            val length = ring * if (index == 4) 0.467f else 0.722f
            drawLine(
                color = WheelText,
                start = centre,
                end = Offset(centre.x + cos(angle).toFloat() * length, centre.y + sin(angle).toFloat() * length),
                strokeWidth = ring * 0.158f,
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

/** The rule that runs an item, and the key that calls the wheel off. */
@Composable
private fun WheelLegend(typography: WheelTypography) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(typography.legendGap)
    ) {
        Text(
            text = stringResource(R.string.start_wheel_hint_release_runs),
            color = WheelText.copy(alpha = 0.9f),
            fontSize = typography.keyLabel,
            lineHeight = typography.itemTitleLine,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
        KeyHint("B", stringResource(R.string.start_wheel_control_cancel), emphasized = false, typography)
    }
}

/** A key cap and the short word for what pressing it does. */
@Composable
private fun KeyHint(
    key: String,
    label: String,
    emphasized: Boolean,
    typography: WheelTypography
) {
    val capShape = RoundedCornerShape(6.dp)
    val ink = WheelText.copy(alpha = if (emphasized) 0.96f else 0.80f)
    Row(
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
            lineHeight = typography.itemTitleLine,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

/** Names and icons of every wheel item, following the live state of the feature each one switches. */
@Composable
private fun startWheelItemVisuals(
    features: StartWheelFeatureState
): Map<StartWheelAction, WheelItemVisual> {
    val items = LinkedHashMap<StartWheelAction, WheelItemVisual>()
    for (item in StartWheelLayout.items) {
        if (item.action in features.unavailable) continue
        items[item.action] = if (item.action == StartWheelAction.PERFORMANCE_TOGGLE) {
            // The one item whose label and icon say what using it will do, not what it is.
            val hidden = !features.perfOverlayVisible
            WheelItemVisual(
                title = stringResource(
                    if (hidden) R.string.start_wheel_item_perf_show else R.string.start_wheel_item_perf_hide
                ),
                icon = item.iconRes,
                state = if (hidden) ItemState.OFF else ItemState.ON
            )
        } else {
            WheelItemVisual(
                title = stringResource(item.titleRes),
                icon = item.iconRes,
                danger = item.danger,
                state = itemState(item.action, features)
            )
        }
    }
    return items
}

private fun itemState(action: StartWheelAction, features: StartWheelFeatureState): ItemState =
    when (action) {
        StartWheelAction.GYRO -> if (features.gyroEnabled) ItemState.ON else ItemState.OFF
        StartWheelAction.MOUSE -> if (features.mouseEmulationActive) ItemState.ON else ItemState.OFF
        StartWheelAction.MIC -> if (features.micActive) ItemState.ON else ItemState.OFF
        StartWheelAction.KEYBOARD -> if (features.keyboardVisible) ItemState.ON else ItemState.OFF
        else -> ItemState.NONE
    }

/**
 * Largest size at which [text] stays on one line within [maxWidth]. A label that would otherwise run
 * out of its sector gives up size down to a floor.
 */
@Composable
private fun fittedFontSize(
    text: String,
    base: TextUnit,
    fontWeight: FontWeight,
    maxWidth: Dp,
    density: Density
): TextUnit {
    val textMeasurer = rememberTextMeasurer()
    return remember(textMeasurer, text, base, fontWeight, maxWidth) {
        val allowed = with(density) { maxWidth.toPx() }
        val measured = textMeasurer.measure(
            text = AnnotatedString(text),
            style = TextStyle(fontSize = base, fontWeight = fontWeight),
            density = density,
            softWrap = false,
            maxLines = 1
        ).size.width.toFloat()
        if (measured <= allowed * FIT_SLACK || measured == 0f) {
            base
        } else {
            (base.value * (allowed * FIT_SLACK / measured).coerceAtLeast(MIN_TEXT_SCALE)).sp
        }
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
    val cutPx: Float,
    val hairPx: Float,
    val rimPx: Float,
    val needleLengthPx: Float,
    val needlePx: Float
) {
    val hubRadius = radius * 0.205f
    val ringInner = radius * 0.27f
    val wheelEdge = radius - rimPx
    val cutInner = ringInner + hairPx * 2f
    val labelRadius = radius * 0.66f

    private val sectors = Array(SECTOR_COUNT) { buildSector(it) }

    fun sectorPath(sector: Int): Path = sectors[sector]

    /** Centre of a sector's label. */
    fun labelCenter(sector: Int): Offset = polar(labelRadius, sector * SECTOR_DEGREES)

    /** The cut between two neighbouring sectors, between two distances from the centre. */
    fun cutLine(boundary: Int, from: Float, to: Float): Pair<Offset, Offset> {
        val degrees = SECTOR_DEGREES / 2f + boundary * SECTOR_DEGREES
        return polar(from, degrees) to polar(to, degrees)
    }

    private fun buildSector(sector: Int): Path {
        val start = sector * SECTOR_DEGREES - SECTOR_DEGREES / 2f
        val end = sector * SECTOR_DEGREES + SECTOR_DEGREES / 2f
        val innerStart = polar(ringInner, start)
        val outerStart = polar(wheelEdge, start)
        val innerEnd = polar(ringInner, end)
        val path = Path()
        path.moveTo(innerStart.x, innerStart.y)
        path.lineTo(outerStart.x, outerStart.y)
        path.arcTo(
            rect = Rect(radius - wheelEdge, radius - wheelEdge, radius + wheelEdge, radius + wheelEdge),
            startAngleDegrees = start - 90f,
            sweepAngleDegrees = SECTOR_DEGREES,
            forceMoveTo = false
        )
        path.lineTo(innerEnd.x, innerEnd.y)
        path.arcTo(
            rect = Rect(radius - ringInner, radius - ringInner, radius + ringInner, radius + ringInner),
            startAngleDegrees = end - 90f,
            sweepAngleDegrees = -SECTOR_DEGREES,
            forceMoveTo = false
        )
        path.close()
        return path
    }

    /** Degrees are clockwise from twelve o'clock. */
    private fun polar(distance: Float, degrees: Float): Offset {
        val angle = Math.toRadians(degrees.toDouble())
        return Offset(
            x = radius + sin(angle).toFloat() * distance,
            y = radius - cos(angle).toFloat() * distance
        )
    }
}
