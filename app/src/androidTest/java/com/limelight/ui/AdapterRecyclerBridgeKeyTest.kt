package com.limelight.ui

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.limelight.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdapterRecyclerBridgeKeyTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var bridge: AdapterRecyclerBridge
    private lateinit var recycler: RecyclerView
    private var clicks = 0

    @Before
    fun showCard() {
        rule.runOnUiThread {
            val adapter = object : BaseAdapter() {
                override fun getCount() = 1
                override fun getItem(position: Int) = "Card"
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
                    convertView ?: TextView(rule.activity).apply { text = "Card"; minHeight = 96 }
            }
            bridge = AdapterRecyclerBridge(rule.activity, adapter)
            bridge.setOnItemClickListener { _, _ -> clicks++ }
            // Use the production inflation path; R8 optimizes unused constructor overloads.
            recycler = (rule.activity.layoutInflater.inflate(R.layout.app_grid_view, null) as RecyclerView).apply {
                layoutManager = LinearLayoutManager(context)
                this.adapter = bridge
            }
            rule.activity.setContentView(recycler)
        }
        rule.waitUntil(5_000) {
            var bound = false
            rule.runOnUiThread { bound = recycler.findViewHolderForAdapterPosition(0) != null }
            bound
        }
    }

    @After
    fun releaseBridge() {
        rule.runOnUiThread { bridge.cleanup() }
    }

    @Test
    fun unconsumedDpadCenterRetainsPlatformClick() {
        rule.runOnUiThread {
            bridge.setOnItemKeyListener { _, _, _, _ -> false }
            val card = card()
            card.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
            assertTrue(card.isPressed)
            card.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
            assertFalse(card.isPressed)
            assertEquals(1, clicks)
        }
    }

    @Test
    fun consumedAKeyHasFeedbackWithoutAnExtraClick() {
        rule.runOnUiThread {
            bridge.setOnItemKeyListener { _, _, _, event ->
                if (event.action == KeyEvent.ACTION_UP) clicks++
                true
            }
            val card = card()
            card.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
            assertTrue(card.isPressed)
            card.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A))
            assertFalse(card.isPressed)
            assertEquals(1, clicks)
        }
    }

    @Test
    fun fallbackAKeyClicksOnceAndCanceledReleaseDoesNotClick() {
        rule.runOnUiThread {
            val card = card()
            card.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
            card.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A))
            assertEquals(1, clicks)
            card.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
            val canceled = KeyEvent.changeFlags(
                KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A), KeyEvent.FLAG_CANCELED
            )
            card.dispatchKeyEvent(canceled)
            assertFalse(card.isPressed)
            assertEquals(1, clicks)
        }
    }

    private fun card(): View = requireNotNull(recycler.findViewHolderForAdapterPosition(0)).itemView
}
