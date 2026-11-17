package com.limelight.binding.input.advance_setting.element

import android.content.ContentValues
import android.content.Intent
import android.graphics.Rect
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.Game
import com.limelight.binding.input.advance_setting.ControllerManager
import com.limelight.binding.input.advance_setting.sqlite.SuperConfigDatabaseHelper
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout
import com.limelight.utils.AppSwitchStyle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CrownSwitchTouchTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val profile = -System.currentTimeMillis()
    private var database: SuperConfigDatabaseHelper? = null
    private fun id(name: String) = context.resources.getIdentifier(name, "id", context.packageName)
    private fun layout(name: String) = context.resources.getIdentifier(name, "layout", context.packageName)

    @After fun cleanUp() {
        activity.scenario.onActivity { database?.deleteConfig(profile); database?.close() }
    }

    @Suppress("DEPRECATION")
    private fun gameContext(host: ComponentActivity): Game {
        val intent = Intent(host, Game::class.java)
        return (instrumentation.newActivity(Game::class.java, host, host.window.decorView.windowToken,
            host.application, intent, host.packageManager.getActivityInfo(intent.component!!, 0),
            "Crown switch test", null, null, null) as Game).apply {
            setTheme(context.resources.getIdentifier("StreamTheme", "style", context.packageName))
        }
    }

    @Test fun groupSwitchTapAndThumbDragSurviveClosingAndReloading() {
        lateinit var manager: ControllerManager
        activity.scenario.onActivity { host ->
            val game = gameContext(host)
            val root = FrameLayout(game).apply {
                this.id = id("advance_setting_view")
                addView(FrameLayout(game).apply { this.id = id("layer_2_element") })
                addView(FrameLayout(game).apply { this.id = id("super_pages_box") })
            }
            host.setContentView(root)
            manager = ControllerManager(root, game)
            database = manager.superConfigDatabaseHelper
            database!!.insertConfig(ContentValues().apply {
                put("config_id", profile); put("config_name", "Crown switch touch regression")
            })
            database!!.insertElement(GroupButton.getInitialInfo().apply {
                put("config_id", profile); put("element_id", 1L)
            })
        }
        onView(withId(id("advance_setting_view"))).check(matches(isDisplayed()))
        var page: SuperPageLayout? = null
        fun reopen() {
            activity.scenario.onActivity {
                page?.let {
                    it.pageReturn()
                    it.endAnimator()
                    manager.superPagesController!!.pageNow.endAnimator()
                }
                manager.elementController!!.loadAllElement(profile)
                page = (manager.elementController!!.elements.single() as GroupButton).infoPage
                manager.elementController!!.toggleInfoPage(page!!)
                page!!.endAnimator()
            }
            instrumentation.waitForIdleSync()
        }
        reopen()
        for (name in listOf("page_group_button_hidden_switch",
            "page_group_button_movable_in_normal_switch", "page_group_button_child_visible")) {
            showSwitch(page!!, name)
            lateinit var control: SwitchCompat
            var initial = false
            activity.scenario.onActivity {
                control = page!!.findViewById(id(name))
                initial = control.isChecked
            }
            // Espresso injects through the window; performClick() would hide a touch regression.
            onView(withId(id(name))).perform(click())
            activity.scenario.onActivity { assertEquals("$name tap", !initial, control.isChecked) }
            reopen()
            showSwitch(page!!, name)
            activity.scenario.onActivity {
                control = page!!.findViewById(id(name))
                assertEquals("$name saved tap", !initial, control.isChecked)
            }
            dragThumb(control)
            activity.scenario.onActivity { assertEquals("$name drag", initial, control.isChecked) }
            reopen()
            activity.scenario.onActivity {
                assertEquals("$name saved drag", initial,
                    page!!.findViewById<SwitchCompat>(id(name)).isChecked)
            }
        }
    }

    @Test fun streamThemeSwitchStylesKeepTouchKeyboardAndDisabledBehavior() {
        lateinit var standalone: SwitchCompat
        lateinit var settings: SwitchCompat
        activity.scenario.onActivity { host ->
            val game = gameContext(host)
            val inflater = LayoutInflater.from(game)
            val groupPage = inflater.inflate(layout("page_group_button_clean"), null)
            standalone = groupPage.findViewById(id("page_group_button_hidden_switch"))
            (standalone.parent as ViewGroup).removeView(standalone)
            settings = inflater.inflate(layout("preference_widget_app_switch"), null) as SwitchCompat
            settings.id = View.generateViewId()
            AppSwitchStyle.apply(standalone)
            AppSwitchStyle.apply(settings)
            assertTrue("AppSwitchWidget must accept touch", standalone.isClickable)
            assertTrue("AppSwitchWidget must accept controller focus", standalone.isFocusable)
            assertTrue("SettingsSwitchWidget remains clickable", settings.isClickable)
            assertFalse("SettingsSwitchWidget keeps row-owned focus", settings.isFocusable)
            val box = LinearLayout(game).apply {
                orientation = LinearLayout.VERTICAL
                val inset = (24 * resources.displayMetrics.density).toInt()
                setPadding(inset, inset, inset, inset)
                addView(standalone)
                addView(settings)
            }
            host.setContentView(box)
            standalone.isEnabled = false
        }
        onView(withId(standalone.id)).check(matches(isDisplayed())).perform(click())
        activity.scenario.onActivity {
            assertFalse("Disabled touch must not toggle", standalone.isChecked)
            standalone.isEnabled = true
        }
        // Enter keyboard mode through the window, including on API 22 where the
        // setInTouchMode() notification can leave the attached root in touch mode.
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        instrumentation.waitForIdleSync()
        activity.scenario.onActivity { assertTrue(standalone.requestFocus()) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        instrumentation.waitForIdleSync()
        activity.scenario.onActivity { assertTrue("Focused D-pad confirmation toggles", standalone.isChecked) }
        onView(withId(settings.id)).perform(click())
        activity.scenario.onActivity { assertTrue("Settings switch still accepts touch", settings.isChecked) }
    }

    private fun showSwitch(page: SuperPageLayout, name: String) {
        activity.scenario.onActivity {
            val control = page.findViewById<SwitchCompat>(id(name))
            var parent = control.parent
            while (parent !is ScrollView) parent = requireNotNull(parent.parent)
            val bounds = Rect()
            control.getDrawingRect(bounds)
            parent.offsetDescendantRectToMyCoords(control, bounds)
            // API 22's rectangle reveal can leave the target under the ScrollView padding.
            val viewport = parent.height - parent.paddingTop - parent.paddingBottom
            parent.scrollTo(parent.scrollX, bounds.centerY() - parent.paddingTop - viewport / 2)
        }
        instrumentation.waitForIdleSync()
        onView(withId(id(name))).check(matches(isDisplayed()))
    }

    private fun dragThumb(control: SwitchCompat) {
        // End thumb/track animation, then wait for draw to refresh the drawable's bounds.
        val drawn = CountDownLatch(1)
        activity.scenario.onActivity {
            control.jumpDrawablesToCurrentState()
            control.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    control.viewTreeObserver.removeOnPreDrawListener(this)
                    drawn.countDown()
                    return true
                }
            })
            control.invalidate()
        }
        assertTrue("Switch must draw before thumb targeting", drawn.await(3, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        fun coordinate(end: Boolean) = CoordinatesProvider { view ->
            val switch = view as SwitchCompat
            val thumb = Rect(requireNotNull(switch.thumbDrawable).bounds)
            assertFalse("Switch thumb must have drawable bounds", thumb.isEmpty)
            val rtl = switch.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val direction = if ((!switch.isChecked) xor rtl) 1f else -1f
            val offset = if (end) direction * 28 * switch.resources.displayMetrics.density else 0f
            val x = (thumb.exactCenterX() + offset).coerceIn(1f, switch.width - 1f)
            val y = thumb.exactCenterY()
            val visible = Rect()
            assertTrue(switch.getLocalVisibleRect(visible))
            assertTrue("Thumb gesture stays visible", visible.contains(x.toInt(), y.toInt()))
            val screen = IntArray(2)
            switch.getLocationOnScreen(screen)
            floatArrayOf(screen[0] + x, screen[1] + y)
        }
        onView(withId(control.id)).perform(GeneralSwipeAction(Swipe.SLOW,
            coordinate(false), coordinate(true), Press.FINGER))
    }
}
