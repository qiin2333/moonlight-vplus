package com.limelight.binding.input.advance_setting.superpage

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NumberSeekbarTest {
    @Test
    fun productionLayoutKeepsValuesAndCallbacksWithinTheConfiguredRange() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val target = instrumentation.targetContext
            val theme = target.resources.getIdentifier("AppTheme", "style", target.packageName)
            val context = ContextThemeWrapper(target, theme)
            val root = LayoutInflater.from(context).inflate(R.layout.page_config, null)
            val control = root.findViewById<NumberSeekbar>(R.id.mouse_sense_number_seekbar)
            val seekBar = control.findViewById<SeekBar>(R.id.number_seekbar_seekbar)
            val number = control.findViewById<TextView>(R.id.number_seekbar_number)
            val changes = mutableListOf<Int>()
            val stoppedValues = mutableListOf<Int>()
            control.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    assertEquals(progress, seekBar.progress)
                    changes.add(progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    stoppedValues.add(seekBar.progress)
                }
            })

            assertEquals(100, control.value)
            control.setProgressMin(40)
            control.setProgressMax(150)
            changes.clear()
            seekBar.progress = 10
            assertEquals(40, control.value)
            assertEquals("40", number.text.toString())
            assertEquals(listOf(40), changes)

            control.findViewById<View>(R.id.number_seekbar_minus).performClick()
            assertEquals(40, control.value)
            control.findViewById<View>(R.id.number_seekbar_add).performClick()
            assertEquals(41, control.value)
            assertEquals(listOf(41), stoppedValues)
            control.findViewById<View>(R.id.number_seekbar_minus).performClick()
            assertEquals(40, control.value)
            assertEquals(listOf(41, 40), stoppedValues)

            changes.clear()
            control.setValueWithNoCallBack(1000)
            assertEquals(150, control.value)
            assertEquals("150", number.text.toString())
            control.setValueWithNoCallBack(-1)
            assertEquals(40, control.value)
            assertEquals("40", number.text.toString())
            assertTrue(changes.isEmpty())

            control.setProgressMin(50)
            assertEquals(50, control.value)
            assertEquals(listOf(50), changes)
            control.setProgressMax(45)
            assertEquals(50, seekBar.max)
            control.setProgressMin(0)
            control.setProgressMax(100)
            seekBar.progress = 0
            assertEquals(0, control.value)
        }
    }
}
