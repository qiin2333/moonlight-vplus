package com.limelight.preferences

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewTreeObserver
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class StreamSettingsExpandFocusTest {

    // The target and instrumentation APKs share the desugared j$ namespace. Keep the
    // synchronizedMap facade in the test APK so it cannot shadow the target with a partial class.
    @Suppress("unused")
    private val desugarCollectionsCompatibility =
        Collections.synchronizedMap(mutableMapOf<Any, Any>())

    @get:Rule
    val activityRule = ActivityScenarioRule(StreamSettings::class.java)

    @Test
    @SuppressLint("RestrictedApi")
    fun touchExpansionUpdatesLocallyAndShowsRevealedRowImmediately() {
        lateinit var recyclerView: RecyclerView
        lateinit var adapter: PreferenceGroupAdapter
        var expandPosition = RecyclerView.NO_POSITION
        activityRule.scenario.onActivity { activity ->
            recyclerView = activity.findViewById(androidx.preference.R.id.recycler_view)
            adapter = recyclerView.adapter as PreferenceGroupAdapter
            expandPosition = findExpandPosition(recyclerView)
            recyclerView.scrollToPosition(expandPosition)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        val frames = CountDownLatch(2)
        val firstFrameAlphas = ArrayList<Float>(2)
        var fullChanges = 0
        var structuralChanges = 0
        var clickHandled = false
        var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null
        val observer = object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() { fullChanges++ }
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { structuralChanges++ }
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { structuralChanges++ }
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) { structuralChanges++ }
        }
        try {
            activityRule.scenario.onActivity {
                val oldIds = (0 until adapter.itemCount).map(adapter::getItemId).toSet()
                adapter.registerAdapterDataObserver(observer)
                preDrawListener = ViewTreeObserver.OnPreDrawListener {
                    val preference = adapter.getItem(expandPosition)
                    if (preference != null && preference.javaClass.name != ANDROIDX_EXPAND_BUTTON_CLASS) {
                        val id = adapter.getItemId(expandPosition)
                        val holder = recyclerView.findViewHolderForItemId(id)
                        if (id !in oldIds && holder != null && firstFrameAlphas.size < 2) {
                            firstFrameAlphas.add(holder.itemView.alpha)
                            frames.countDown()
                        }
                    }
                    if (frames.count > 0) recyclerView.postInvalidateOnAnimation()
                    true
                }
                recyclerView.viewTreeObserver.addOnPreDrawListener(preDrawListener)
                clickHandled = recyclerView.findViewHolderForAdapterPosition(expandPosition)
                    ?.itemView?.performClick() == true
                recyclerView.postInvalidateOnAnimation()
            }
            val sampled = frames.await(5, TimeUnit.SECONDS)
            assertTrue("The expansion row did not handle the touch click", clickHandled)
            assertTrue("Did not observe the revealed row's first two drawing frames", sampled)
            assertEquals("Expansion invalidated the whole list; first frame alpha=$firstFrameAlphas", 0, fullChanges)
            assertTrue("Expansion did not dispatch local structure updates", structuralChanges > 0)
            firstFrameAlphas.forEachIndexed { frame, alpha ->
                assertEquals("Revealed row faded in on drawing frame ${frame + 1}", 1f, alpha, 0.001f)
            }
        } finally {
            activityRule.scenario.onActivity {
                adapter.unregisterAdapterDataObserver(observer)
                preDrawListener?.let { listener ->
                    if (recyclerView.viewTreeObserver.isAlive) {
                        recyclerView.viewTreeObserver.removeOnPreDrawListener(listener)
                    }
                }
            }
        }
    }

    @Test
    @SuppressLint("RestrictedApi")
    fun activatingAdvancedRowKeepsFocusInPreferenceList() {
        lateinit var recyclerView: RecyclerView
        var expandPosition = RecyclerView.NO_POSITION

        activityRule.scenario.onActivity { activity ->
            recyclerView = activity.findViewById(androidx.preference.R.id.recycler_view)
            val adapter = recyclerView.adapter as PreferenceGroupAdapter
            expandPosition = (0 until adapter.itemCount).first { position ->
                adapter.getItem(position)?.javaClass?.name == ANDROIDX_EXPAND_BUTTON_CLASS
            }
            recyclerView.scrollToPosition(expandPosition)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        activityRule.scenario.onActivity { activity ->
            val expandRow = recyclerView
                .findViewHolderForAdapterPosition(expandPosition)
                ?.itemView
            assertNotNull(expandRow)
            assertTrue(expandRow!!.requestFocusFromTouch())

            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        activityRule.scenario.onActivity { activity ->
            val focusedView = activity.currentFocus
            assertNotNull(focusedView)
            val focusedRow = recyclerView.findContainingItemView(focusedView!!)
            assertNotNull(focusedRow)

            val focusedPosition = recyclerView.getChildAdapterPosition(focusedRow!!)
            assertTrue(focusedPosition >= expandPosition)
            val focusedPreference =
                (recyclerView.adapter as PreferenceGroupAdapter).getItem(focusedPosition)
            assertNotEquals(ANDROIDX_EXPAND_BUTTON_CLASS, focusedPreference?.javaClass?.name)
        }
    }

    @Test
    @SuppressLint("RestrictedApi")
    fun rotationCancelsCallbacksPostedToTheDestroyedPreferenceList() {
        lateinit var oldRecyclerView: RecyclerView
        var expandPosition = RecyclerView.NO_POSITION

        activityRule.scenario.onActivity { activity ->
            oldRecyclerView = activity.findViewById(androidx.preference.R.id.recycler_view)
            expandPosition = findExpandPosition(oldRecyclerView)
            oldRecyclerView.scrollToPosition(expandPosition)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        activityRule.scenario.onActivity { activity ->
            val fragment = settingsFragment(activity)
            val expandRow = requireNotNull(
                oldRecyclerView.findViewHolderForAdapterPosition(expandPosition)?.itemView
            )
            assertTrue(expandRow.requestFocusFromTouch())
            assertTrue(fragment.prepareExpandButtonFocusRestore(expandRow))
            fragment.restoreFocusAfterExpandButton()
        }

        activityRule.scenario.recreate()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        awaitRecreatedListFocus(oldRecyclerView)

        activityRule.scenario.onActivity { activity ->
            val newRecyclerView: RecyclerView =
                activity.findViewById(androidx.preference.R.id.recycler_view)
            assertNotSame(oldRecyclerView, newRecyclerView)
            assertTrue(!oldRecyclerView.isAttachedToWindow)
            assertTrue(newRecyclerView.isAttachedToWindow)
            activity.currentFocus?.let { focusedView ->
                val focusId = if (focusedView.id == android.view.View.NO_ID) "no-id"
                    else runCatching { activity.resources.getResourceName(focusedView.id) }
                        .getOrElse { focusedView.id.toString() }
                assertTrue("Recreated list lost focus to ${focusedView.javaClass.name} ($focusId)",
                    newRecyclerView.findContainingItemView(focusedView) != null)
            }
        }
    }

    @Test
    @SuppressLint("RestrictedApi")
    fun rapidReplacementRequestsKeepOnlyTheLatestFocusRestore() {
        lateinit var recyclerView: RecyclerView
        var expandPosition = RecyclerView.NO_POSITION

        activityRule.scenario.onActivity { activity ->
            recyclerView = activity.findViewById(androidx.preference.R.id.recycler_view)
            expandPosition = findExpandPosition(recyclerView)
            recyclerView.scrollToPosition(expandPosition)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        activityRule.scenario.onActivity { activity ->
            val fragment = settingsFragment(activity)
            val expandRow = requireNotNull(
                recyclerView.findViewHolderForAdapterPosition(expandPosition)?.itemView
            )
            assertTrue(expandRow.requestFocusFromTouch())

            repeat(3) {
                assertTrue(fragment.prepareExpandButtonFocusRestore(expandRow))
                fragment.restoreFocusAfterExpandButton()
            }
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        activityRule.scenario.onActivity { activity ->
            val focusedView = activity.currentFocus
            assertNotNull(focusedView)
            val focusedRow = recyclerView.findContainingItemView(focusedView!!)
            assertNotNull(focusedRow)
            assertTrue(recyclerView.getChildAdapterPosition(focusedRow!!) >= expandPosition)
        }
    }

    private fun settingsFragment(activity: StreamSettings): StreamSettings.SettingsFragment {
        return activity.supportFragmentManager.fragments
            .filterIsInstance<StreamSettings.SettingsFragment>()
            .single()
    }

    /** RESUMED precedes restored window focus on API 22; wait for the new view hierarchy. */
    private fun awaitRecreatedListFocus(oldRecyclerView: RecyclerView) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 10_000
        do {
            var ready = false
            activityRule.scenario.onActivity { activity ->
                val recycler = activity.findViewById<RecyclerView>(androidx.preference.R.id.recycler_view)
                val focusedView = activity.currentFocus
                ready = !oldRecyclerView.isAttachedToWindow && recycler !== oldRecyclerView &&
                    recycler != null && recycler.isAttachedToWindow && recycler.isLaidOut &&
                    !recycler.isLayoutRequested && activity.hasWindowFocus() &&
                    (focusedView == null || recycler.findContainingItemView(focusedView) != null)
            }
            if (ready) return
            instrumentation.waitForIdleSync()
            SystemClock.sleep(16)
        } while (SystemClock.uptimeMillis() < deadline)
        // The assertions in the caller report the actual final hierarchy/focus on timeout.
    }

    @SuppressLint("RestrictedApi")
    private fun findExpandPosition(recyclerView: RecyclerView): Int {
        val adapter = recyclerView.adapter as PreferenceGroupAdapter
        return (0 until adapter.itemCount).first { index ->
            adapter.getItem(index)?.javaClass?.name == ANDROIDX_EXPAND_BUTTON_CLASS
        }
    }

    private companion object {
        private const val ANDROIDX_EXPAND_BUTTON_CLASS = "androidx.preference.ExpandButton"
    }
}
