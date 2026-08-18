package com.limelight.ui

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class AboutDialogInputTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun mainActionsHaveDeterministicControllerNavigation() {
        val ecosystemOpened = AtomicBoolean(false)
        composeTestRule.setContent {
            AboutDialogContent(
                appName = "Moonlight V+",
                versionInfo = "Version test",
                onHandbook = {},
                onEcosystem = { ecosystemOpened.set(true) },
                onBilibili = {},
                onGithub = {},
                onQq = {},
                onSite = {},
                onClose = {}
            )
        }

        composeTestRule.onNodeWithTag(AboutDialogTags.HANDBOOK).requestFocus()
        composeTestRule.onNodeWithTag(AboutDialogTags.HANDBOOK).assertIsFocused()
        composeTestRule.onNodeWithTag(AboutDialogTags.HANDBOOK).performKeyInput {
            pressKey(Key.DirectionRight)
        }
        composeTestRule.onNodeWithTag(AboutDialogTags.ECOSYSTEM).assertIsFocused()

        InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_A)
        composeTestRule.waitForIdle()
        assertTrue(ecosystemOpened.get())

        composeTestRule.onNodeWithTag(AboutDialogTags.ECOSYSTEM).performKeyInput {
            pressKey(Key.DirectionLeft)
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        composeTestRule.onNodeWithTag(AboutDialogTags.GITHUB).assertIsFocused()
        composeTestRule.onNodeWithTag(AboutDialogTags.GITHUB).performKeyInput {
            pressKey(Key.DirectionRight)
        }
        composeTestRule.onNodeWithTag(AboutDialogTags.QQ).assertIsFocused()
        composeTestRule.onNodeWithTag(AboutDialogTags.QQ).performKeyInput {
            pressKey(Key.DirectionRight)
        }
        composeTestRule.onNodeWithTag(AboutDialogTags.SITE).assertIsFocused()
    }

    @Test
    fun invalidEcosystemFocusFallsBackToClose() {
        composeTestRule.setContent {
            EcosystemDialogContent(
                projects = projects(),
                onOpen = {},
                onClose = {},
                initialFocusIndex = -1
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(AboutDialogTags.ECOSYSTEM_CLOSE).assertIsFocused()
    }

    @Test
    fun portraitGridUsesColumnAwareDirectionalNavigation() {
        composeTestRule.setContent {
            val portrait = Configuration(LocalConfiguration.current).apply {
                orientation = Configuration.ORIENTATION_PORTRAIT
                screenWidthDp = 700
            }
            CompositionLocalProvider(LocalConfiguration provides portrait) {
                // 列数按对话框实际宽度决定，requiredWidth 强制真实双列布局
                Box(Modifier.requiredWidth(700.dp)) {
                    EcosystemDialogContent(projects(), onOpen = {}, onClose = {})
                }
            }
        }

        composeTestRule.onNodeWithTag(AboutDialogTags.ecosystemItem(0)).requestFocus()
        composeTestRule.onNodeWithTag(AboutDialogTags.ecosystemItem(0)).assertIsFocused()
        composeTestRule.onNodeWithTag(AboutDialogTags.ecosystemItem(0)).requestFocus()
        composeTestRule.onNodeWithTag(AboutDialogTags.ecosystemItem(0)).performKeyInput {
            pressKey(Key.DirectionDown)
        }
        composeTestRule.onNodeWithTag(AboutDialogTags.ecosystemItem(2)).assertIsFocused()
        composeTestRule.onNodeWithTag(AboutDialogTags.ecosystemItem(2)).performKeyInput {
            pressKey(Key.DirectionUp)
            pressKey(Key.DirectionUp)
        }
        composeTestRule.onNodeWithTag(AboutDialogTags.ECOSYSTEM_CLOSE).assertIsFocused()
    }

    @Test
    fun singleColumnPortraitCanNavigateToLastProject() {
        composeTestRule.setContent {
            val portrait = Configuration(LocalConfiguration.current).apply {
                orientation = Configuration.ORIENTATION_PORTRAIT
                screenWidthDp = 400
            }
            CompositionLocalProvider(LocalConfiguration provides portrait) {
                Box(Modifier.requiredWidth(400.dp)) {
                    EcosystemDialogContent(projects(), onOpen = {}, onClose = {})
                }
            }
        }

        composeTestRule.onNodeWithTag(AboutDialogTags.ecosystemItem(0)).requestFocus()
        repeat(5) { index ->
            composeTestRule.onNodeWithTag(AboutDialogTags.ecosystemItem(index)).performKeyInput {
                pressKey(Key.DirectionDown)
            }
        }
        composeTestRule.onNodeWithTag(AboutDialogTags.ecosystemItem(5)).assertIsFocused()
    }

    @Test
    fun regularDialogWidthUsesTwoColumns() {
        composeTestRule.setContent {
            val portrait = Configuration(LocalConfiguration.current).apply {
                orientation = Configuration.ORIENTATION_PORTRAIT
                screenWidthDp = 448
            }
            CompositionLocalProvider(LocalConfiguration provides portrait) {
                // 448dp 是设计稿常规档对话框宽度，应为双列（下键跨行到 item 2）
                Box(Modifier.requiredWidth(448.dp)) {
                    EcosystemDialogContent(projects(), onOpen = {}, onClose = {})
                }
            }
        }

        composeTestRule.onNodeWithTag(AboutDialogTags.ecosystemItem(0)).requestFocus()
        composeTestRule.onNodeWithTag(AboutDialogTags.ecosystemItem(0)).performKeyInput {
            pressKey(Key.DirectionDown)
        }
        composeTestRule.onNodeWithTag(AboutDialogTags.ecosystemItem(2)).assertIsFocused()
    }

    @Test
    fun landscapeGridNavigatesRowsAndOpensProject() {
        val opened = AtomicReference<EcosystemProject>()
        val projects = projects()
        composeTestRule.setContent {
            val landscape = Configuration(LocalConfiguration.current).apply {
                orientation = Configuration.ORIENTATION_LANDSCAPE
                screenWidthDp = 900
            }
            CompositionLocalProvider(LocalConfiguration provides landscape) {
                EcosystemDialogContent(
                    projects = projects,
                    onOpen = opened::set,
                    onClose = {}
                )
            }
        }

        composeTestRule.onNodeWithTag(AboutDialogTags.ecosystemItem(0)).requestFocus()
        composeTestRule.onNodeWithTag(AboutDialogTags.ecosystemItem(0)).performKeyInput {
            pressKey(Key.DirectionDown)
        }
        // 横屏为 3 列网格，下键跨行到第二行首列
        composeTestRule.onNodeWithTag(AboutDialogTags.ecosystemItem(3)).assertIsFocused()
        InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_A)
        composeTestRule.waitForIdle()
        assertEquals(projects[3], opened.get())
    }

    @Test
    fun landscapeGridCardsSupportTouchOpen() {
        val opened = AtomicReference<EcosystemProject>()
        val projects = projects()
        composeTestRule.setContent {
            val landscape = Configuration(LocalConfiguration.current).apply {
                orientation = Configuration.ORIENTATION_LANDSCAPE
                screenWidthDp = 900
                screenHeightDp = 360
            }
            CompositionLocalProvider(LocalConfiguration provides landscape) {
                EcosystemDialogContent(
                    projects = projects,
                    onOpen = opened::set,
                    onClose = {}
                )
            }
        }

        composeTestRule.onNodeWithTag(AboutDialogTags.ecosystemItem(4)).performClick()
        assertEquals(projects[4], opened.get())
    }

    private fun projects(): List<EcosystemProject> = List(6) { index ->
        EcosystemProject(
            badge = "P$index",
            title = "Project $index",
            platform = "Platform",
            description = "Description",
            url = "https://example.com/$index"
        )
    }
}