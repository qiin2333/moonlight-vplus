package com.limelight.perfhud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PerfHudVisibilityTest {
    private data class Circle(val x: Float, val y: Float, val radius: Float, val color: Int, val style: Paint.Style)
    private data class Text(val text: String, val x: Float, val top: Float, val bottom: Float, val width: Float, val color: Int)
    private data class Hit(val rect: RectF, val target: PerfHudTarget)

    private class RecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        val circles = mutableListOf<Circle>()
        val texts = mutableListOf<Text>()
        val roundRectColors = mutableListOf<Int>()

        override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {
            circles += Circle(cx, cy, radius, paint.color, paint.style)
            super.drawCircle(cx, cy, radius, paint)
        }

        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            val fm = paint.fontMetrics
            texts += Text(text, x, y + fm.ascent, y + fm.descent, paint.measureText(text), paint.color)
            super.drawText(text, x, y, paint)
        }

        override fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) {
            roundRectColors += paint.color
            super.drawRoundRect(rect, rx, ry, paint)
        }
    }

    private val noMetrics = PerfHudVisibility(
        resolution = false, decoder = false, fps = false, onePercentLow = false,
        loss = false, network = false, decode = false, host = false, battery = false,
        clock = false, health = false
    )

    private val layouts = listOf(
        PerfHudLayout.CAPSULE to false,
        PerfHudLayout.BAR to false,
        PerfHudLayout.COLUMN to false,
        PerfHudLayout.PANEL to true,
        PerfHudLayout.DOCK to false,
        PerfHudLayout.DOCK to true
    )

    @Test
    fun emptyMetricsKeepMeasuredDrawnAndHittableMoonInEveryLayout() = onMain {
        for ((layout, detailed) in layouts) {
            val view = newView(layout, detailed, noMetrics)
            val measuredHits = measureAndLayout(view)
            val drawing = draw(view)
            assertHitsEqual("$layout/$detailed", measuredHits, hits(view))
            assertEquals(view.measuredHeight, view.measureHeightFor(layout, detailed, view.width))
            assertHitsEqual("$layout/$detailed after height probe", measuredHits, hits(view))

            val badge = drawing.circles.first { it.style == Paint.Style.STROKE }
            assertEquals(PerfHudPalette.DIM, badge.color)
            assertTrue(badge.x - badge.radius >= 0f && badge.x + badge.radius <= view.width)
            assertTrue(badge.y - badge.radius >= 0f && badge.y + badge.radius <= view.height)
            val moonHit = hits(view).first { it.target == PerfHudTarget.DIAGNOSIS }.rect
            assertTrue(moonHit.contains(badge.x, badge.y))
            assertTrue(moonHit.left >= 0f && moonHit.top >= 0f && moonHit.right <= view.width && moonHit.bottom <= view.height)
            assertEquals(PerfHudTarget.DIAGNOSIS, view.targetAt(badge.x, badge.y))
            assertNull(view.targetAt(-1f, badge.y))

            view.setPressedAt(badge.x, badge.y)
            assertTrue("$layout badge must have an actual highlightable hit",
                draw(view).roundRectColors.contains(PerfHudPalette.withAlpha(view.accentColor, 0x2E)))
            view.clearPressed()
            assertFalse(view.isFocusable)
            assertFalse(view.isFocusableInTouchMode)
            assertFalse(view.requestFocus())
            assertFalse(view.isFocused)

            if (layout != PerfHudLayout.CAPSULE) {
                assertEquals("$layout must not register hidden metrics", listOf(PerfHudTarget.DIAGNOSIS), hits(view).map { it.target })
                assertEquals("$layout must draw only the moon", listOf(MOON), drawing.texts.map { it.text })
            } else {
                assertTrue("mini retains its fixed FPS summary", drawing.texts.any { it.text == "40" })
            }
            if (layout == PerfHudLayout.PANEL) {
                assertTrue("empty panel should wrap the badge", view.width < TEXT_SIZE * 6)
                assertTrue("empty panel should not contain a blank body", view.height < TEXT_SIZE * 4)
            }
            if (layout == PerfHudLayout.DOCK) {
                assertEquals("dock retains its available width", MAX_WIDTH, view.width)
                assertTrue("empty dock should not contain a blank body", view.height < TEXT_SIZE * 4)
            }
            val emptyWidth = view.width
            val emptyHeight = view.height
            view.setItemVisibility(noMetrics.copy(health = true))
            measureAndLayout(view)
            assertEquals(PerfHudPalette.BAD, badgeStroke(draw(view)).color)
            view.setItemVisibility(noMetrics)
            val restoredHits = measureAndLayout(view)
            assertEquals("$layout restored width", emptyWidth, view.width)
            assertEquals("$layout restored height", emptyHeight, view.height)
            assertHitsEqual("$layout restored geometry", measuredHits, restoredHits)
            draw(view)
            assertHitsEqual("$layout restored draw", restoredHits, hits(view))
        }
    }

    @Test
    fun healthChangesOnlyBadgeAndMoodAcrossLayoutsAndSeverities() = onMain {
        for ((layout, detailed) in layouts) {
            for ((fps, severity) in listOf(120f to PerfSeverity.OK, 100f to PerfSeverity.WARN, 40f to PerfSeverity.BAD)) {
                val snapshot = snapshot(fps = fps)
                assertEquals(severity, snapshot.overallSeverity)
                val view = newView(layout, detailed, PerfHudVisibility(), snapshot)
                measureAndLayout(view)
                val enabled = draw(view)
                view.setItemVisibility(PerfHudVisibility(health = false))
                measureAndLayout(view)
                val disabled = draw(view)
                assertEquals(PerfHudPalette.severity(severity), badgeStroke(enabled).color)
                assertEquals(PerfHudPalette.DIM, badgeStroke(disabled).color)
                val hasMood = layout == PerfHudLayout.PANEL || layout == PerfHudLayout.DOCK
                assertEquals("$layout enabled mood", hasMood, enabled.texts.any { it.text.contains(MOOD) })
                assertFalse("$layout disabled mood", disabled.texts.any { it.text.contains(MOOD) })
                val value = fps.toInt().toString()
                for (drawing in listOf(enabled, disabled)) {
                    assertTrue("$layout must retain the independent FPS color for $severity",
                        drawing.texts.any { it.text == value && it.color == PerfHudPalette.severity(severity) })
                }
            }
        }
    }

    @Test
    fun refreshingSnapshotDoesNotRecordAnotherFpsSample() = onMain {
        val view = newView(PerfHudLayout.BAR, false, PerfHudVisibility())
        val field = PerfHudView::class.java.getDeclaredField("fpsHistory").apply { isAccessible = true }
        val history = field.get(view) as FloatRing
        val initial = history.size
        view.setSnapshot(snapshot(fps = 80f), recordHistory = false)
        assertEquals(initial, history.size)
        view.setSnapshot(snapshot(fps = 100f))
        assertEquals(initial + 1, history.size)
        assertEquals(100f, history[history.size - 1], 0f)
    }

    @Test
    fun healthOffKeepsOtherMetricColorsAndHeaderBodySpacing() = onMain {
        for ((layout, detailed) in layouts) {
            val view = newView(layout, detailed, PerfHudVisibility(), snapshot(badMetrics = true))
            measureAndLayout(view)
            val enabled = draw(view)
            view.setItemVisibility(PerfHudVisibility(health = false))
            val measuredHits = measureAndLayout(view)
            val drawing = draw(view)
            assertHitsEqual("$layout/$detailed", measuredHits, hits(view))
            fun metricColors(canvas: RecordingCanvas) = canvas.texts.filter {
                it.text == "40" || it.text.contains("7.0%") || it.text == "85" ||
                    it.text.startsWith("85 ms") || it.text.contains("5%") || it.text == "16.0"
            }.map { it.text to it.color }
            assertEquals("$layout metric text and colors must not depend on health", metricColors(enabled), metricColors(drawing))
            assertTrue("$layout must keep loss red", drawing.texts.any { it.text.contains("7.0%") && it.color == PerfHudPalette.BAD })
            val fps = drawing.texts.first { it.text == "40" && it.color == PerfHudPalette.BAD }
            if (layout == PerfHudLayout.PANEL || layout == PerfHudLayout.DOCK) {
                val badge = badgeStroke(drawing)
                assertTrue("$layout body must begin below the badge", fps.top > badge.y + badge.radius)
                assertTrue("$layout FPS must fit vertically", fps.bottom <= view.height)
            }
        }
    }

    @Test
    fun panelAndDockKeepStreamSummaryWithoutMood() = onMain {
        for ((layout, detailed) in listOf(PerfHudLayout.PANEL to true, PerfHudLayout.DOCK to false, PerfHudLayout.DOCK to true)) {
            for (resolution in listOf(true, false)) {
                val visibility = noMetrics.copy(resolution = resolution, decoder = !resolution)
                val view = newView(layout, detailed, visibility)
                val measuredHits = measureAndLayout(view, width = 300, exact = layout == PerfHudLayout.DOCK)
                val drawing = draw(view)
                assertHitsEqual("$layout/$detailed/$resolution", measuredHits, hits(view))
                val expected = when {
                    resolution -> RESOLUTION
                    layout == PerfHudLayout.PANEL -> "$CODEC (decoder)"
                    else -> CODEC
                }
                val summary = drawing.texts.first { it.text == expected }
                assertFalse(drawing.texts.any { it.text.contains(MOOD) })
                assertTrue(summary.x >= badgeStroke(drawing).x + badgeStroke(drawing).radius)
                assertTrue(summary.x + summary.width <= view.width + 1f)
                assertEquals(if (resolution) PerfHudTarget.RESOLUTION else PerfHudTarget.DECODER,
                    view.targetAt(summary.x + summary.width / 2f, (summary.top + summary.bottom) / 2f))
                if (layout == PerfHudLayout.PANEL) {
                    assertTrue("panel detail must start below its badge", summary.top > badgeStroke(drawing).y + badgeStroke(drawing).radius)
                } else if (!detailed || resolution) {
                    assertTrue("summary-only dock should have no empty body", view.height < TEXT_SIZE * 4)
                } else {
                    assertTrue("detailed dock must retain the decoder detail", view.height > TEXT_SIZE * 4)
                }
            }
        }
    }

    @Test
    fun dockGivesSummaryTheUnusedMoodSpace() = onMain {
        val visibility = noMetrics.copy(resolution = true, decoder = true, health = true)
        val view = newView(PerfHudLayout.DOCK, false, visibility)
        measureAndLayout(view, width = 300, exact = true)
        val withMood = draw(view)
        view.setItemVisibility(visibility.copy(health = false))
        val measuredHits = measureAndLayout(view, width = 300, exact = true)
        val withoutMood = draw(view)
        assertHitsEqual("dock summary", measuredHits, hits(view))
        val fullSummary = "$RESOLUTION  $CODEC"
        assertFalse(withMood.texts.any { it.text == fullSummary })
        val summary = withoutMood.texts.first { it.text == fullSummary }
        assertEquals(PerfHudTarget.RESOLUTION,
            view.targetAt(summary.x + summary.width / 2f, (summary.top + summary.bottom) / 2f))
    }

    @Test
    fun narrowDockEllipsizesSummaryWithoutCoveringMoonHit() = onMain {
        val view = newView(PerfHudLayout.DOCK, false, noMetrics.copy(resolution = true, decoder = true))
        val measuredHits = measureAndLayout(view, width = 160, exact = true)
        val drawing = draw(view)
        assertHitsEqual("narrow dock", measuredHits, hits(view))
        val summary = drawing.texts.first { it.text != MOON }
        val moonHit = hits(view).first { it.target == PerfHudTarget.DIAGNOSIS }.rect
        assertTrue(summary.text.isNotEmpty() && summary.text != "$RESOLUTION  $CODEC")
        assertTrue(summary.x >= moonHit.right)
        assertTrue(summary.x + summary.width <= view.width)
        assertEquals(PerfHudTarget.RESOLUTION,
            view.targetAt(summary.x + summary.width / 2f, (summary.top + summary.bottom) / 2f))
        assertEquals(PerfHudTarget.DIAGNOSIS, view.targetAt(moonHit.centerX(), moonHit.centerY()))
    }

    @Test
    fun missingMoodTextUsesEmptyHeaderSpaceWithoutHidingHealthColor() = onMain {
        val panel = newView(PerfHudLayout.PANEL, true, noMetrics.copy(health = true)).apply {
            setStrings(PerfHudStrings(moods = emptyMap()))
        }
        measureAndLayout(panel)
        val panelDrawing = draw(panel)
        assertTrue(panel.width < TEXT_SIZE * 6)
        assertEquals(PerfHudPalette.BAD, badgeStroke(panelDrawing).color)
        assertEquals(listOf(MOON), panelDrawing.texts.map { it.text })

        val dock = newView(PerfHudLayout.DOCK, false,
            noMetrics.copy(resolution = true, decoder = true, health = true)).apply {
            setStrings(PerfHudStrings(moods = emptyMap()))
        }
        val measuredHits = measureAndLayout(dock, width = 300, exact = true)
        val dockDrawing = draw(dock)
        assertHitsEqual("dock without mood text", measuredHits, hits(dock))
        assertEquals(PerfHudPalette.BAD, badgeStroke(dockDrawing).color)
        assertTrue(dockDrawing.texts.any { it.text == "$RESOLUTION  $CODEC" })
    }

    @Test
    fun compactPanelRemeasuresWhenSnapshotAddsOrRemovesContent() = onMain {
        val view = newView(PerfHudLayout.PANEL, true, noMetrics.copy(health = true)).apply {
            setStrings(PerfHudStrings(moods = mapOf(PerfMood.HOT_DECODE to MOOD)))
        }
        measureAndLayout(view)
        val compactWidth = view.width
        assertTrue(compactWidth < TEXT_SIZE * 6)
        view.setSnapshot(snapshot(badMetrics = true))
        assertTrue("new mood needs a fresh panel measurement", view.isLayoutRequested)
        measureAndLayout(view)
        assertTrue(view.width > compactWidth)
        assertTrue(draw(view).texts.any { it.text.contains(MOOD) })
        view.setSnapshot(snapshot())
        assertTrue("removed mood needs a fresh panel measurement", view.isLayoutRequested)
        measureAndLayout(view)
        assertEquals(compactWidth, view.width)

        view.setItemVisibility(noMetrics.copy(battery = true))
        view.setSnapshot(snapshot().copy(batteryLevel = -1))
        measureAndLayout(view)
        assertEquals(compactWidth, view.width)
        view.setSnapshot(snapshot().copy(batteryLevel = 5))
        assertTrue("new battery detail needs a fresh panel measurement", view.isLayoutRequested)
        measureAndLayout(view)
        assertTrue(view.width > compactWidth)
        assertTrue(draw(view).texts.any { it.text.contains("5%") })

        view.setItemVisibility(noMetrics)
        val measuredHits = measureAndLayout(view, width = 500, exact = true)
        view.setSnapshot(snapshot(fps = 41f))
        assertFalse("fixed width and unchanged height should not relayout", view.isLayoutRequested)
        draw(view)
        assertHitsEqual("exact-width panel", measuredHits, hits(view))
    }

    @Test
    fun panelAndDockAddOnlySpacingNeededByVisibleBlocks() = onMain {
        for ((layout, detailed) in listOf(PerfHudLayout.PANEL to true, PerfHudLayout.DOCK to false, PerfHudLayout.DOCK to true)) {
            for ((body, bodyTarget) in listOf(
                noMetrics.copy(fps = true) to PerfHudTarget.FPS,
                noMetrics.copy(network = true) to PerfHudTarget.LATENCY,
                noMetrics.copy(loss = true) to PerfHudTarget.LOSS
            )) {
                val view = newView(layout, detailed, noMetrics)
                measureAndLayout(view, width = 500, exact = layout == PerfHudLayout.DOCK)
                val emptyHeight = view.height
                view.setItemVisibility(body)
                val measuredHits = measureAndLayout(view, width = 500, exact = layout == PerfHudLayout.DOCK)
                val drawing = draw(view)
                assertHitsEqual("$layout/$detailed/$bodyTarget", measuredHits, hits(view))
                assertEquals(view.height, view.measureHeightFor(layout, detailed, view.width))
                val moon = hits(view).first { it.target == PerfHudTarget.DIAGNOSIS }.rect
                val bodyHits = hits(view).filter { it.target == bodyTarget && it.rect.top >= moon.bottom }
                assertTrue("$layout must register $bodyTarget", bodyHits.isNotEmpty())
                val bodyTop = bodyHits.minOf { it.rect.top }
                assertTrue("$layout needs a positive, bounded gap before $bodyTarget",
                    bodyTop > moon.bottom && bodyTop - moon.bottom < TEXT_SIZE * 2)
                assertTrue(view.height > emptyHeight)
                assertTrue("$layout must draw within its bottom edge", drawing.texts.all { it.bottom <= view.height + 1f })
            }
        }
    }

    @Test
    fun panelAndDockVisibilityRefreshDoesNotTakeExistingFocus() = onMain {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for ((layout, detailed) in listOf(PerfHudLayout.PANEL to true, PerfHudLayout.DOCK to false, PerfHudLayout.DOCK to true)) {
            val hud = newView(layout, detailed, noMetrics.copy(health = true))
            val existing = View(context).apply {
                isFocusable = true
                isFocusableInTouchMode = true
            }
            val parent = FrameLayout(context).apply {
                addView(existing, FrameLayout.LayoutParams(60, 60))
                addView(hud, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
                ))
            }
            fun layoutParent() {
                parent.measure(
                    View.MeasureSpec.makeMeasureSpec(MAX_WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY)
                )
                parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)
                draw(hud)
            }
            layoutParent()
            assertTrue("$layout needs an existing focus owner", existing.requestFocus())
            for (health in listOf(false, true)) {
                hud.setItemVisibility(noMetrics.copy(health = health))
                layoutParent()
                assertTrue("$layout refresh must preserve focus", existing.isFocused)
                assertFalse("$layout HUD must not take focus", hud.requestFocus())
                assertTrue(existing.isFocused)
            }
        }
    }

    private fun newView(
        layout: PerfHudLayout,
        detailed: Boolean,
        visibility: PerfHudVisibility,
        snapshot: PerfHudSnapshot = snapshot()
    ): PerfHudView = PerfHudView(ApplicationProvider.getApplicationContext<Context>()).apply {
        setBaseTextSizePx(TEXT_SIZE)
        setHudLayout(layout, detailed)
        setStrings(PerfHudStrings(moods = PerfMood.entries.associateWith { MOOD }))
        setSnapshot(snapshot)
        setItemVisibility(visibility)
    }

    private fun snapshot(fps: Float = 40f, badMetrics: Boolean = false) = PerfHudSnapshot(
        width = 2560, height = 1440, targetFps = 120, codec = CODEC, hdrLabel = null,
        decoderName = "decoder", renderedFps = fps, receivedFps = 120f, framegenFps = 0f,
        onePercentLowFps = 0f, packetLossPercent = if (badMetrics) 7f else 0f,
        rttMs = if (badMetrics) 85 else 8, rttVarianceMs = 2, bandwidth = "40 Mbps",
        latency = LatencyChain.estimate(if (badMetrics) 85 else 8, 2f, if (badMetrics) 16f else 3f, 4f),
        batteryLevel = if (badMetrics) 5 else 80, charging = false, drainPercentPerHour = null,
        clock = null, moonIcon = MOON, fullMoon = false
    )

    private fun measureAndLayout(view: PerfHudView, width: Int = MAX_WIDTH, exact: Boolean = false): List<Hit> {
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, if (exact) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST)
        view.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        assertTrue(view.width > 0 && view.height > 0)
        return hits(view)
    }

    private fun draw(view: PerfHudView): RecordingCanvas {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = RecordingCanvas(bitmap)
        view.draw(canvas)
        bitmap.recycle()
        return canvas
    }

    private fun badgeStroke(canvas: RecordingCanvas) = canvas.circles.first { it.style == Paint.Style.STROKE }

    private fun hits(view: PerfHudView): List<Hit> {
        val field = PerfHudView::class.java.getDeclaredField("hits").apply { isAccessible = true }
        return (field.get(view) as List<*>).map { item ->
            val hit = checkNotNull(item)
            val rect = hit.javaClass.getDeclaredField("rect").apply { isAccessible = true }.get(hit) as RectF
            val target = hit.javaClass.getDeclaredField("target").apply { isAccessible = true }.get(hit) as PerfHudTarget
            Hit(RectF(rect), target)
        }
    }

    private fun assertHitsEqual(label: String, expected: List<Hit>, actual: List<Hit>) {
        assertEquals("$label hit count", expected.size, actual.size)
        expected.zip(actual).forEach { (a, b) ->
            assertEquals("$label hit target", a.target, b.target)
            assertEquals("$label hit left", a.rect.left, b.rect.left, 0.01f)
            assertEquals("$label hit top", a.rect.top, b.rect.top, 0.01f)
            assertEquals("$label hit right", a.rect.right, b.rect.right, 0.01f)
            assertEquals("$label hit bottom", a.rect.bottom, b.rect.bottom, 0.01f)
        }
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private companion object {
        const val TEXT_SIZE = 20f
        const val MAX_WIDTH = 700
        const val MOON = "\u263e"
        const val MOOD = "HEALTH_MARK"
        const val RESOLUTION = "2560\u00d71440"
        const val CODEC = "HEVC"
    }
}
