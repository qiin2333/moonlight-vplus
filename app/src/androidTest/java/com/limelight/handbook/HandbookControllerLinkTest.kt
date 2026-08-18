package com.limelight.handbook

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.HelpActivity
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HandbookControllerLinkTest {
    @get:Rule
    val activityRule = ActivityScenarioRule<HelpActivity>(
        Intent(ApplicationProvider.getApplicationContext(), HelpActivity::class.java)
            .setData(Uri.parse("about:blank"))
    )

    @Test
    fun rightThenGamepadAOpensFocusedHandbookLink() {
        val rendered = AtomicBoolean(false)
        val navigatedPage = AtomicReference<HandbookPageRef>()

        activityRule.scenario.onActivity { activity ->
            val webView = createLockedHandbookWebView(
                context = activity,
                onNavigate = navigatedPage::set,
                onOpenExternal = {}
            ).apply {
                renderStartedAtMs = 1L
                setControllerLinks(
                    listOf(
                        HandbookControllerLink(
                            label = "Start",
                            url = "https://www.alkaidlab.com/docs/guide/start.html"
                        )
                    )
                )
                onDocumentRendered = {
                    rendered.set(true)
                    requestFocus()
                }
            }
            activity.setContentView(FrameLayout(activity).apply {
                addView(
                    webView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
            })
            webView.loadDataWithBaseURL(
                "https://www.alkaidlab.com/docs/",
                """
                    <!doctype html><html><body>
                    <a href="https://www.alkaidlab.com/docs/guide/start.html">Start</a>
                    </body></html>
                """.trimIndent(),
                "text/html",
                "UTF-8",
                null
            )
        }

        waitUntil { rendered.get() }
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_BUTTON_A)
        waitUntil { navigatedPage.get() != null }

        assertEquals(
            "/docs/guide/start.html",
            navigatedPage.get().encodedPath
        )
    }

    @Test
    fun parserResolvesRelativeLinksAndKeepsReadableLabels() {
        val links = extractHandbookControllerLinks(
            """<a href="guide/start.html">  Quick   start </a>""",
            "https://www.alkaidlab.com/docs/"
        )

        assertEquals(1, links.size)
        assertEquals("Quick start", links.single().label)
        assertEquals(
            "https://www.alkaidlab.com/docs/guide/start.html",
            links.single().url
        )
    }

    private fun press(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (!condition() && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50L)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
        assertTrue(condition())
    }
}
