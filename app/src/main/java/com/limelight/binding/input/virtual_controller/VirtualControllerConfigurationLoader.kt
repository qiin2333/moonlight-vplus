/** Originally created by Karim Mreisi. */
package com.limelight.binding.input.virtual_controller

import android.content.Context
import android.content.res.Configuration
import com.limelight.nvstream.input.ControllerPacket
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.binding.input.virtual_controller.VirtualControllerElement.Companion.EID_A
import com.limelight.binding.input.virtual_controller.VirtualControllerElement.Companion.EID_B
import com.limelight.binding.input.virtual_controller.VirtualControllerElement.Companion.EID_X
import com.limelight.binding.input.virtual_controller.VirtualControllerElement.Companion.EID_Y
import com.limelight.binding.input.virtual_controller.VirtualControllerElement.Companion.EID_LB
import com.limelight.binding.input.virtual_controller.VirtualControllerElement.Companion.EID_RB
import com.limelight.binding.input.virtual_controller.VirtualControllerElement.Companion.EID_BACK
import com.limelight.binding.input.virtual_controller.VirtualControllerElement.Companion.EID_START
import com.limelight.binding.input.virtual_controller.VirtualControllerElement.Companion.EID_LSB
import com.limelight.binding.input.virtual_controller.VirtualControllerElement.Companion.EID_RSB
import com.limelight.binding.input.virtual_controller.VirtualControllerElement.Companion.EID_GDB
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.roundToInt

object VirtualControllerConfigurationLoader {
    const val OSC_PREFERENCE = "OSC"

    @JvmStatic
    fun createDefaultLayout(controller: VirtualController, context: Context, layoutWidth: Int, layoutHeight: Int) {
        val config = PreferenceConfiguration.readPreferences(context)
        val style = VirtualControllerLayout.fromPreference(config.oscLayout)
        controller.layoutStyle = style
        controller.onlyL3R3 = config.onlyL3R3
        val classic = style == VirtualControllerLayout.CLASSIC
        val half = config.halfHeightOscPortrait && context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val available = if (half) layoutHeight / 2 else layoutHeight
        val height = if (classic) available else minOf(available, layoutWidth * 72 / 88)
        val scale = height / 72f
        controller.profileScale = scale
        controller.profileOffsetY = if (classic) { if (half) height else 0 } else layoutHeight - height
        controller.profileWidth = layoutWidth
        controller.profileHeight = layoutHeight
        val accent = when (style) {
            VirtualControllerLayout.XBOX -> 0xFF8ED269.toInt()
            VirtualControllerLayout.DS -> 0xFF83B7FF.toInt()
            VirtualControllerLayout.NS -> 0xFF56CEE5.toInt()
            else -> 0xF00000FF.toInt()
        }
        fun add(element: VirtualControllerElement, x: Int, y: Int, w: Int, h: Int, right: Boolean = false) {
            if (!classic) {
                val color = if (style == VirtualControllerLayout.NS && right) 0xFFFF7C83.toInt() else accent
                element.setColors(if (style == VirtualControllerLayout.NS) color else 0xFFE1E6ED.toInt(), color)
            }
            controller.addElement(element,
                (x * scale).roundToInt() + if (right) layoutWidth - (128 * scale).roundToInt() else 0,
                (y * scale).roundToInt() + controller.profileOffsetY,
                // Round shared boundaries once so adjacent hit targets never overlap by a pixel.
                if (classic) (w * scale).roundToInt() else ((x + w) * scale).roundToInt() - (x * scale).roundToInt(),
                if (classic) (h * scale).roundToInt() else ((y + h) * scale).roundToInt() - (y * scale).roundToInt())
        }
        fun button(id: Int, flag: Int, label: String, layer: Int = 100 + id): DigitalButton =
            DigitalButton(controller, id, layer, context).apply {
                setText(label)
                addDigitalButtonListener(object : DigitalButton.DigitalButtonListener {
                    override fun onClick() = controller.setButtonState(this, flag, true)
                    override fun onLongClick() {}
                    override fun onRelease() = controller.setButtonState(this, flag, false)
                })
            }
        if (!config.onlyL3R3) {
            val symmetric = style == VirtualControllerLayout.DS
            val dpad = DigitalPad(controller, context).apply {
                contentDescription = context.getString(com.limelight.R.string.osc_dpad)
                addDigitalPadListener { direction ->
                    val flags = intArrayOf(ControllerPacket.LEFT_FLAG, ControllerPacket.UP_FLAG, ControllerPacket.RIGHT_FLAG, ControllerPacket.DOWN_FLAG)
                    var bits = 0
                    flags.forEachIndexed { i, flag -> bits = if (direction and (1 shl i) != 0) bits or flag else bits and flag.inv() }
                    controller.setButtonState(this, bits, bits != 0)
                }
            }
            add(dpad, if (classic) 4 else 5, if (classic) 41 else if (symmetric) 24 else 48,
                if (classic) 30 else 22, if (classic) 30 else 22)
            add(LeftAnalogStick(controller, context), if (classic) 6 else 4,
                if (classic) 4 else if (symmetric) 46 else 21, 26, 26)
            add(RightAnalogStick(controller, context), 98, if (classic) 42 else 46, 26, 26, true)
            // IDs are physical positions. NS and the face-flip preference each swap labels and wire flags.
            val swapped = (style == VirtualControllerLayout.NS) xor config.flipFaceButtons
            val flags = if (swapped) intArrayOf(ControllerPacket.B_FLAG, ControllerPacket.A_FLAG, ControllerPacket.Y_FLAG, ControllerPacket.X_FLAG)
                else intArrayOf(ControllerPacket.A_FLAG, ControllerPacket.B_FLAG, ControllerPacket.X_FLAG, ControllerPacket.Y_FLAG)
            val labels = if (symmetric) {
                if (swapped) arrayOf("○", "×", "△", "□") else arrayOf("×", "○", "□", "△")
            } else if (swapped) arrayOf("B", "A", "Y", "X") else arrayOf("A", "B", "X", "Y")
            val xs = if (classic) intArrayOf(106, 116, 96, 106) else intArrayOf(107, 117, 97, 107)
            val ys = if (classic) intArrayOf(21, 11, 11, 1) else intArrayOf(36, 26, 26, 16)
            val ids = intArrayOf(EID_A, EID_B, EID_X, EID_Y)
            flags.indices.forEach { i ->
                val key = button(ids[i], flags[i], labels[i], 1)
                if (style == VirtualControllerLayout.XBOX || symmetric) {
                    key.labelColor = when (flags[i]) {
                        ControllerPacket.A_FLAG -> if (symmetric) 0xFF83B7FF.toInt() else 0xFF8ED269.toInt()
                        ControllerPacket.B_FLAG -> 0xFFFF8792.toInt()
                        ControllerPacket.X_FLAG -> if (symmetric) 0xFFCCA2EF.toInt() else 0xFF83B7FF.toInt()
                        else -> if (symmetric) 0xFF80DABD.toInt() else 0xFFF1D475.toInt()
                    }
                }
                add(key, xs[i], ys[i], 10, 10, true)
            }
            val labelsShoulder = when (style) {
                VirtualControllerLayout.DS -> arrayOf("L2", "L1", "R1", "R2")
                VirtualControllerLayout.NS -> arrayOf("ZL", "L", "R", "ZR")
                else -> arrayOf("LT", "LB", "RB", "RT")
            }
            add(LeftTrigger(controller, if (classic) 1 else 2, context).apply { setText(labelsShoulder[0]) },
                if (classic) 1 else 3, if (classic) 31 else 6, 12, 9)
            add(button(EID_LB, ControllerPacket.LB_FLAG, labelsShoulder[1], if (classic) 1 else 3),
                if (classic) 24 else 17, if (classic) 31 else 6, 12, 9)
            add(button(EID_RB, ControllerPacket.RB_FLAG, labelsShoulder[2], if (classic) 1 else 4),
                if (classic) 92 else 99, if (classic) 31 else 6, 12, 9, true)
            add(RightTrigger(controller, if (classic) 1 else 5, context).apply { setText(labelsShoulder[3]) },
                if (classic) 115 else 113, if (classic) 31 else 6, 12, 9, true)
            val back = when (style) { VirtualControllerLayout.NS -> "−"; VirtualControllerLayout.DS -> "⋯"; else -> "BACK" }
            val start = when (style) { VirtualControllerLayout.NS -> "+"; VirtualControllerLayout.DS -> "≡"; else -> "START" }
            add(button(EID_BACK, ControllerPacket.BACK_FLAG, back, 6), if (classic) 34 else 30, if (classic) 64 else 60, 12, if (classic) 7 else 10)
            add(button(EID_START, ControllerPacket.PLAY_FLAG, start, 7), if (classic) 83 else 86, if (classic) 64 else 60, 12, if (classic) 7 else 10, true)
        }
        if (config.onlyL3R3 || !classic) {
            add(button(EID_LSB, ControllerPacket.LS_CLK_FLAG, "L3"), if (classic) 1 else 30, if (classic) 60 else 48, if (classic) 12 else 10, if (classic) 9 else 10)
            add(button(EID_RSB, ControllerPacket.RS_CLK_FLAG, "R3"), if (classic) 115 else 88, if (classic) 60 else 48, if (classic) 12 else 10, if (classic) 9 else 10, true)
        }
        if (config.showGuideButton) {
            val guide = button(EID_GDB, ControllerPacket.SPECIAL_BUTTON_FLAG, if (classic) "GUIDE" else "⌂")
            if (classic) add(guide, 49, 64, 12, 7, true)
            else {
                guide.setColors(0xFFE1E6ED.toInt(), accent)
                controller.addElement(guide, (layoutWidth - 10 * scale).roundToInt() / 2,
                    controller.profileOffsetY + ((if (layoutWidth / scale >= 100) 61 else 35) * scale).roundToInt(), (10 * scale).roundToInt(), (10 * scale).roundToInt())
            }
        }
        controller.setOpacity(config.oscOpacity)
    }

    internal fun profileKey(controller: VirtualController, id: Int): String =
        if (controller.layoutStyle == VirtualControllerLayout.CLASSIC) id.toString()
        else "${controller.layoutStyle.preferenceValue}.${if (controller.onlyL3R3) "minimal" else "full"}.$id"

    @JvmStatic
    fun saveProfile(controller: VirtualController, context: Context) {
        val editor = context.getSharedPreferences(OSC_PREFERENCE, Context.MODE_PRIVATE).edit()
        controller.elements.forEach { element ->
            val json = element.configuration
            val right = json.getInt("LEFT") * 2 + json.getInt("WIDTH") >= controller.profileWidth
            val x = json.getInt("LEFT")
            fun normalized(value: Int): Number =
                if (controller.layoutStyle == VirtualControllerLayout.CLASSIC) (value / controller.profileScale).roundToInt()
                else value.toDouble() / controller.profileScale
            json.put("R_SIDE", right)
            json.put("LEFT", normalized(if (right) controller.profileWidth - x else x))
            json.put("TOP", normalized(json.getInt("TOP") - controller.profileOffsetY))
            json.put("WIDTH", normalized(json.getInt("WIDTH")))
            json.put("HEIGHT", normalized(json.getInt("HEIGHT")))
            editor.putString(profileKey(controller, element.elementId), json.toString())
        }
        editor.apply()
    }

    @JvmStatic
    fun loadFromPreferences(controller: VirtualController, context: Context) {
        val prefs = context.getSharedPreferences(OSC_PREFERENCE, Context.MODE_PRIVATE)
        controller.elements.forEach { element ->
            val key = profileKey(controller, element.elementId)
            val saved = prefs.getString(key, null) ?: return@forEach
            try {
                val json = JSONObject(saved)
                fun scaled(name: String) = (json.getDouble(name) * controller.profileScale).roundToInt()
                val w = scaled("WIDTH").coerceIn(1, controller.profileWidth)
                val h = scaled("HEIGHT").coerceIn(1, controller.profileHeight)
                val x = if (json.optBoolean("R_SIDE")) controller.profileWidth - scaled("LEFT") else scaled("LEFT")
                val y = scaled("TOP") + controller.profileOffsetY
                json.put("LEFT", x.coerceIn(0, controller.profileWidth - w))
                json.put("TOP", y.coerceIn(0, controller.profileHeight - h))
                json.put("WIDTH", w).put("HEIGHT", h)
                element.loadConfiguration(json)
            } catch (_: JSONException) { prefs.edit().remove(key).apply() }
        }
    }
}
