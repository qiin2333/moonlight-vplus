package com.limelight.binding.input.virtual_controller

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.nvstream.input.ControllerPacket
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VirtualControllerPresetsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val keys = listOf("list_osc_layout", "checkbox_only_show_L3R3", "checkbox_flip_face_buttons",
        "checkbox_show_guide_button", "checkbox_half_height_osc_portrait")

    private fun withPreferences(block: () -> Unit) = instrumentation.runOnMainSync {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val saved = prefs.all.filterKeys { it in keys }
        val profiles = context.getSharedPreferences("OSC", Context.MODE_PRIVATE)
        val savedProfiles = profiles.all
        try {
            prefs.edit().apply {
                keys.forEach { remove(it) }
                putBoolean("checkbox_show_guide_button", true)
                putBoolean("checkbox_half_height_osc_portrait", false)
            }.commit()
            profiles.edit().clear().commit()
            block()
        } finally {
            prefs.edit().apply {
                keys.forEach { remove(it) }
                saved.forEach { (key, value) ->
                    when (value) { is String -> putString(key, value); is Boolean -> putBoolean(key, value) }
                }
            }.commit()
            profiles.edit().clear().apply {
                savedProfiles.forEach { (key, value) -> if (value is String) putString(key, value) }
            }.commit()
        }
    }

    private fun create(style: String?, width: Int = 1280, height: Int = 720): Pair<VirtualController, FrameLayout> {
        PreferenceManager.getDefaultSharedPreferences(context).edit().apply {
            if (style == null) remove("list_osc_layout") else putString("list_osc_layout", style)
        }.commit()
        val frame = FrameLayout(context)
        val controller = VirtualController(null, frame, context)
        VirtualControllerConfigurationLoader.createDefaultLayout(controller, context, width, height)
        VirtualControllerConfigurationLoader.loadFromPreferences(controller, context)
        frame.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        frame.layout(0, 0, width, height)
        return controller to frame
    }

    private fun touch(view: View, action: Int, x: Float = view.width / 2f, y: Float = view.height / 2f, time: Long = SystemClock.uptimeMillis()) {
        val event = MotionEvent.obtain(time, time, action, x, y, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    @Test fun editorGesturesStayAnchoredAndClampToFrame() = withPreferences {
        val (controller, frame) = create("xbox")
        val button = controller.elements.first { it.elementId == 6 }
        val params = button.layoutParams as FrameLayout.LayoutParams
        fun layout() {
            frame.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            frame.layout(0, 0, 1280, 720)
        }
        controller.startEditing(VirtualController.ControllerMode.MoveButtons)
        val x = button.x + button.width / 2f
        val y = button.y + button.height / 2f
        val left = button.left
        val top = button.top
        touch(frame, MotionEvent.ACTION_DOWN, x, y)
        touch(frame, MotionEvent.ACTION_MOVE, x - 17, y - 13)
        layout()
        touch(frame, MotionEvent.ACTION_MOVE, x - 29, y - 21)
        layout()
        assertEquals(left - 29, params.leftMargin)
        assertEquals(top - 21, params.topMargin)
        touch(frame, MotionEvent.ACTION_MOVE, -500f, -500f)
        layout()
        assertEquals(0, params.leftMargin)
        assertEquals(0, params.topMargin)
        touch(frame, MotionEvent.ACTION_UP, -500f, -500f)
        assertEquals(0, controller.controllerInputContext.inputMap.toInt())
        controller.cleanup()
    }

    @Test fun resizingSmallControlsDoesNotJumpAndRestoresExactPixels() = withPreferences {
        for (style in listOf("xbox", "ds", "ns")) {
            val (controller, frame) = create(style, 640, 360)
            val button = controller.elements.first { it.elementId == 6 }
            val params = button.layoutParams as FrameLayout.LayoutParams
            val originalWidth = params.width
            val originalHeight = params.height
            controller.startEditing(VirtualController.ControllerMode.ResizeButtons)
            touch(button, MotionEvent.ACTION_DOWN, 20f, 20f)
            touch(button, MotionEvent.ACTION_MOVE, 21f, 20f)
            assertEquals(originalWidth + 1, params.width)
            assertEquals(originalHeight, params.height)
            touch(button, MotionEvent.ACTION_MOVE, 27f, 23f)
            touch(button, MotionEvent.ACTION_UP, 27f, 23f)
            val expected = button.configuration.toString()
            controller.finishEditing()
            repeat(3) {
                VirtualControllerConfigurationLoader.loadFromPreferences(controller, context)
                assertEquals(expected, button.configuration.toString())
                VirtualControllerConfigurationLoader.saveProfile(controller, context)
            }
            controller.startEditing(VirtualController.ControllerMode.ResizeButtons)
            touch(button, MotionEvent.ACTION_DOWN, 20f, 20f)
            touch(button, MotionEvent.ACTION_MOVE, 5000f, 5000f)
            assertEquals(frame.width, params.leftMargin + params.width)
            assertEquals(frame.height, params.topMargin + params.height)
            touch(button, MotionEvent.ACTION_CANCEL)
            controller.cleanup()
        }
    }

    @Test fun defaultAndUnknownValuesUseXbox() = withPreferences {
        for (value in arrayOf(null, "unknown")) {
            val (controller, _) = create(value)
            assertEquals(VirtualControllerLayout.XBOX, controller.layoutStyle)
            assertEquals(16, controller.elements.size)
            controller.cleanup()
        }
    }

    @Test fun modernHitTargetsFitWithoutOverlappingAcrossAspectRatios() = withPreferences {
        for (style in listOf("xbox", "ds", "ns")) {
            for ((width, height) in listOf(640 to 360, 2400 to 1080, 720 to 1280)) {
                for (half in listOf(false, true)) {
                    PreferenceManager.getDefaultSharedPreferences(context).edit()
                        .putBoolean("checkbox_half_height_osc_portrait", half).commit()
                    val (controller, _) = create(style, width, height)
                    val rects = controller.elements.map { e ->
                        val p = e.layoutParams as FrameLayout.LayoutParams
                        Rect(p.leftMargin, p.topMargin, p.leftMargin + p.width, p.topMargin + p.height)
                    }
                    rects.forEachIndexed { i, rect ->
                        assertTrue("$style $width x $height $rect", rect.left >= 0 && rect.top >= 0 && rect.right <= width && rect.bottom <= height)
                        for (j in 0 until i) assertFalse("$style overlap ${controller.elements[i].elementId}/${controller.elements[j].elementId}", Rect.intersects(rect, rects[j]))
                    }
                    controller.cleanup()
                }
            }
        }
    }

    @Test fun faceLabelsAndWireFlagsFollowPresetAndFlipPreference() = withPreferences {
        for (style in listOf("xbox", "ds", "ns", "classic")) {
            for (flip in listOf(false, true)) {
                PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean("checkbox_flip_face_buttons", flip).commit()
                val (controller, _) = create(style)
                val bottom = controller.elements.first { it.elementId == 6 }
                val swapped = (style == "ns") xor flip
                assertEquals(if (style == "ds") { if (flip) "○" else "×" } else if (swapped) "B" else "A", bottom.contentDescription)
                touch(bottom, MotionEvent.ACTION_DOWN)
                assertEquals(if (swapped) ControllerPacket.B_FLAG else ControllerPacket.A_FLAG, controller.controllerInputContext.inputMap.toInt())
                touch(bottom, MotionEvent.ACTION_CANCEL)
                assertEquals(0, controller.controllerInputContext.inputMap.toInt())
                controller.cleanup()
            }
        }
    }

    @Test fun slideSwitchAndCancelReleaseEveryOwnedButton() = withPreferences {
        val (controller, _) = create("xbox")
        val a = controller.elements.first { it.elementId == 6 }
        val b = controller.elements.first { it.elementId == 7 }
        touch(a, MotionEvent.ACTION_DOWN)
        touch(a, MotionEvent.ACTION_MOVE, b.x - a.x + b.width / 2f, b.y - a.y + b.height / 2f)
        assertEquals(ControllerPacket.B_FLAG, controller.controllerInputContext.inputMap.toInt())
        touch(b, MotionEvent.ACTION_DOWN)
        touch(a, MotionEvent.ACTION_CANCEL)
        assertEquals(ControllerPacket.B_FLAG, controller.controllerInputContext.inputMap.toInt())
        touch(b, MotionEvent.ACTION_UP)
        assertEquals(0, controller.controllerInputContext.inputMap.toInt())
        controller.cleanup()
    }

    @Test fun stickClickAndIndependentL3DoNotReleaseEachOtherAndHideClearsInput() = withPreferences {
        val (controller, _) = create("xbox")
        val stick = controller.elements.first { it.elementId == 12 }
        val l3 = controller.elements.first { it.elementId == 14 }
        val time = SystemClock.uptimeMillis()
        touch(stick, MotionEvent.ACTION_DOWN, time = time)
        touch(stick, MotionEvent.ACTION_UP, time = time + 20)
        touch(stick, MotionEvent.ACTION_DOWN, time = time + 80)
        touch(l3, MotionEvent.ACTION_DOWN)
        touch(stick, MotionEvent.ACTION_UP, time = time + 100)
        assertEquals(ControllerPacket.LS_CLK_FLAG, controller.controllerInputContext.inputMap.toInt())
        touch(stick, MotionEvent.ACTION_DOWN, stick.width.toFloat(), stick.height / 2f, time + 500)
        assertTrue(controller.controllerInputContext.leftStickX > 0)
        controller.hide()
        assertEquals(0, controller.controllerInputContext.inputMap.toInt())
        assertEquals(0, controller.controllerInputContext.leftStickX.toInt())
        controller.cleanup()
    }

    @Test fun profilesAreIsolatedAndLegacyClassicCoordinatesSurvive() = withPreferences {
        val prefs = context.getSharedPreferences("OSC", Context.MODE_PRIVATE)
        prefs.edit().putString("6", JSONObject().put("LEFT", 50).put("TOP", 20)
            .put("WIDTH", 8).put("HEIGHT", 8).put("R_SIDE", false).toString()).commit()
        val (classic, _) = create("classic")
        val oldA = classic.elements.first { it.elementId == 6 }
        assertEquals(500, (oldA.layoutParams as FrameLayout.LayoutParams).leftMargin)
        classic.cleanup()
        val (xbox, _) = create("xbox")
        val a = xbox.elements.first { it.elementId == 6 }
        (a.layoutParams as FrameLayout.LayoutParams).leftMargin = 800
        VirtualControllerConfigurationLoader.saveProfile(xbox, context)
        xbox.cleanup()
        val (ds, _) = create("ds")
        assertNotEquals(800, (ds.elements.first { it.elementId == 6 }.layoutParams as FrameLayout.LayoutParams).leftMargin)
        ds.cleanup()
        val (restored, _) = create("xbox")
        assertEquals(800, (restored.elements.first { it.elementId == 6 }.layoutParams as FrameLayout.LayoutParams).leftMargin)
        restored.cleanup()
    }

    @Test fun frameRoutesTwoFingersAndReleasesOnlyTheLiftedPointer() = withPreferences {
        val (controller, frame) = create("xbox")
        val stick = controller.elements.first { it.elementId == 12 }
        val a = controller.elements.first { it.elementId == 6 }
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, ids: IntArray, points: List<Pair<Float, Float>>, time: Long) {
            val properties = ids.map { id -> MotionEvent.PointerProperties().apply {
                this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER
            } }.toTypedArray()
            val coordinates = points.map { (px, py) -> MotionEvent.PointerCoords().apply {
                x = px; y = py; pressure = 1f; size = 1f
            } }.toTypedArray()
            val event = MotionEvent.obtain(start, time, action, ids.size, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            frame.dispatchTouchEvent(event)
            event.recycle()
        }
        val center = stick.x + stick.width / 2f to stick.y + stick.height / 2f
        val attack = a.x + a.width / 2f to a.y + a.height / 2f
        send(MotionEvent.ACTION_DOWN, intArrayOf(7), listOf(center), start)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            intArrayOf(7, 12), listOf(center, attack), start + 10)
        send(MotionEvent.ACTION_MOVE, intArrayOf(7, 12),
            listOf(stick.x + stick.width to center.second, attack), start + 30)
        assertTrue(controller.controllerInputContext.leftStickX > 0)
        assertEquals(ControllerPacket.A_FLAG, controller.controllerInputContext.inputMap.toInt())
        send(MotionEvent.ACTION_POINTER_UP, intArrayOf(7, 12), listOf(center, attack), start + 40)
        assertEquals(0, controller.controllerInputContext.leftStickX.toInt())
        assertEquals(ControllerPacket.A_FLAG, controller.controllerInputContext.inputMap.toInt())
        send(MotionEvent.ACTION_UP, intArrayOf(12), listOf(attack), start + 50)
        assertEquals(0, controller.controllerInputContext.inputMap.toInt())
        controller.cleanup()
    }

    @Test fun minimalModeRetainsOnlyStickClicksAndOptionalGuide() = withPreferences {
        for (style in listOf("xbox", "ds", "ns", "classic")) {
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean("checkbox_only_show_L3R3", true).putBoolean("checkbox_show_guide_button", false).commit()
            val (controller, _) = create(style)
            assertEquals(setOf(14, 15), controller.elements.map { it.elementId }.toSet())
            controller.cleanup()
        }
    }

    @Test fun directionalHighlightFollowsStickAndClearsOnRelease() = withPreferences {
        val (controller, frame) = create("xbox")
        val stick = controller.elements.first { it.elementId == 12 }
        fun render(): Bitmap = Bitmap.createBitmap(stick.width, stick.height, Bitmap.Config.ARGB_8888).also {
            stick.draw(Canvas(it))
        }
        val idle = render()
        // The area between the cap and rim remains transparent.
        assertEquals(0, Color.alpha(idle.getPixel(stick.width * 3 / 4, stick.height / 2)))
        touch(stick, MotionEvent.ACTION_DOWN, stick.width.toFloat(), stick.height / 2f)
        val right = render()
        touch(stick, MotionEvent.ACTION_MOVE, stick.width / 2f, 0f)
        val up = render()
        fun change(bitmap: Bitmap, rightSide: Boolean): Long {
            var energy = 0L
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val inPatch = if (rightSide) x > bitmap.width * .85f && kotlin.math.abs(y - bitmap.height / 2) < bitmap.height * .2f
                    else y < bitmap.height * .15f && kotlin.math.abs(x - bitmap.width / 2) < bitmap.width * .2f
                if (inPatch) energy += kotlin.math.abs(Color.alpha(bitmap.getPixel(x, y)) - Color.alpha(idle.getPixel(x, y)))
            }
            return energy
        }
        assertTrue(change(right, true) > change(right, false))
        assertTrue(change(up, false) > change(up, true))
        touch(stick, MotionEvent.ACTION_UP)
        val released = render()
        assertTrue(idle.sameAs(released))
        controller.setOpacity(0)
        val invisible = render()
        for (y in 0 until invisible.height) for (x in 0 until invisible.width) {
            assertEquals(0, Color.alpha(invisible.getPixel(x, y)))
        }
        listOf(idle, right, up, released, invisible).forEach { it.recycle() }
        controller.cleanup()
    }

    @Test fun renderAllPresetsForVisualReview() = withPreferences {
        for (style in listOf("xbox", "ds", "ns", "classic")) {
            val (controller, frame) = create(style)
            val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(36, 46, 57))
            frame.draw(canvas)
            File(context.getExternalFilesDir(null), "osc-$style.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            if (style != "classic") {
                val stick = controller.elements.first { it.elementId == 12 }
                touch(stick, MotionEvent.ACTION_DOWN, stick.width.toFloat(), stick.height * .25f)
                val button = controller.elements.first { it.elementId == 6 }
                touch(button, MotionEvent.ACTION_DOWN)
                canvas.drawColor(Color.rgb(36, 46, 57))
                frame.draw(canvas)
                File(context.getExternalFilesDir(null), "osc-$style-active.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            bitmap.recycle()
            controller.cleanup()
        }
    }
}
