package com.limelight.binding.input.advance_setting.element

import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.Game
import com.limelight.R
import com.limelight.binding.input.advance_setting.ControllerManager
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrownEditorNavigationTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)

    /** A real Crown controller hosted without starting a streaming connection. */
    @Suppress("DEPRECATION")
    private fun createController(host: ComponentActivity): Triple<Game, ControllerManager, FrameLayout> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(host, Game::class.java)
        val game = instrumentation.newActivity(
            Game::class.java, host, host.window.decorView.windowToken, host.application, intent,
            host.packageManager.getActivityInfo(intent.component!!, 0), "Crown navigation test",
            null, null, null
        ) as Game
        game.setTheme(R.style.StreamTheme)
        val elements = FrameLayout(game).apply {
            id = R.id.layer_2_element
            addView(View(game).apply { id = R.id.element_touch_view })
        }
        val root = FrameLayout(game).apply {
            id = R.id.advance_setting_view
            addView(elements)
            addView(FrameLayout(game).apply { id = R.id.super_pages_box })
        }
        host.setContentView(root)
        val manager = ControllerManager(root, game)
        game.controllerManager = manager
        return Triple(game, manager, elements)
    }

    @Test fun editingFromGameMenuReturnsOnceWithoutOpeningCrownSidebar() {
        lateinit var game: Game
        lateinit var manager: ControllerManager
        lateinit var elements: FrameLayout
        var returnedToGameMenu = 0
        activity.scenario.onActivity { host ->
            val fixture = createController(host)
            game = fixture.first
            manager = fixture.second
            elements = fixture.third
            game.setcurrentBackKeyMenu(Game.BackKeyMenuMode.NO_MENU)
            val editor = manager.elementController!!
            val pages = manager.superPagesController!!
            editor.enterEditMode { returnedToGameMenu++ }
            assertEquals(2, elements.childCount)
            pages.returnOperation()
            pages.pageNow.endAnimator()
            assertNotNull(pages.pageNow.findViewById<View>(R.id.page_edit_exit_edit_mode))

            // Hiding and reopening the editor while editing must retain its origin.
            editor.open()
            pages.pageNow.endAnimator()
            pages.returnOperation()
            pages.pageNow.endAnimator()
            val exit = pages.pageNow.findViewById<View>(R.id.page_edit_exit_edit_mode)
            exit.performClick()
            exit.performClick() // A repeated input must not reopen the menu twice.
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        activity.scenario.onActivity {
            val pages = manager.superPagesController!!
            assertEquals(1, returnedToGameMenu)
            assertEquals(Game.BackKeyMenuMode.GAME_MENU, game.getCurrentBackKeyMenuMode())
            assertEquals(1, elements.childCount)
            assertEquals(pages.pageNull, pages.pageNow)
            assertNull(pages.pageNow.findViewById<View>(R.id.page_super_menu_edit_mode))
            manager.superConfigDatabaseHelper?.close()
        }
    }

    @Test fun editingFromCrownSidebarReturnsToCrownSidebar() {
        lateinit var game: Game
        lateinit var manager: ControllerManager
        lateinit var elements: FrameLayout
        activity.scenario.onActivity { host ->
            val fixture = createController(host)
            game = fixture.first
            manager = fixture.second
            elements = fixture.third
            game.setcurrentBackKeyMenu(Game.BackKeyMenuMode.CROWN_MODE)
            val pages = manager.superPagesController!!
            manager.pageSuperMenuController!!.open()
            pages.returnOperation()
            pages.pageNow.endAnimator()
            pages.pageNow.findViewById<View>(R.id.page_super_menu_edit_mode).performClick()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        activity.scenario.onActivity {
            val pages = manager.superPagesController!!
            pages.pageNow.endAnimator()
            val editPage: SuperPageLayout = pages.pageNow
            assertNotNull(editPage.findViewById<View>(R.id.page_edit_exit_edit_mode))
            assertEquals(2, elements.childCount)
            editPage.findViewById<View>(R.id.page_edit_exit_edit_mode).performClick()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        activity.scenario.onActivity {
            val pages = manager.superPagesController!!
            pages.pageNow.endAnimator()
            assertNotNull(pages.pageNow.findViewById<View>(R.id.page_super_menu_edit_mode))
            assertEquals(Game.BackKeyMenuMode.CROWN_MODE, game.getCurrentBackKeyMenuMode())
            assertEquals(1, elements.childCount)
            manager.superConfigDatabaseHelper?.close()
        }
    }
}
