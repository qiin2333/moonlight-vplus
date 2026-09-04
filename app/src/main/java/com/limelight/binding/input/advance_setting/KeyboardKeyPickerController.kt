package com.limelight.binding.input.advance_setting

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Rect
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import com.limelight.R
import kotlin.math.abs

internal class KeyboardKeyPickerController(
    private val root: ViewGroup,
    private val onKeySelected: (TextView) -> Unit,
    private val externalViews: List<View> = emptyList(),
    private val editableView: View? = null,
    private val isEditing: () -> Boolean = { false },
    private val onEnterEditing: () -> Unit = {}
) {
    enum class Page {
        MAIN,
        NAV,
        NUM
    }

    private data class Point(val x: Int, val y: Int)

    private val mainTab: TextView = root.findViewById(R.id.keyboard_picker_tab_main)
    private val navTab: TextView = root.findViewById(R.id.keyboard_picker_tab_nav)
    private val numTab: TextView = root.findViewById(R.id.keyboard_picker_tab_num)
    private val mainPage: View = root.findViewById(R.id.keyboard_picker_main)
    private val navPage: View = root.findViewById(R.id.keyboard_picker_nav)
    private val numPage: View = root.findViewById(R.id.keyboard_picker_num)
    private val tabs = listOf(mainTab, navTab, numTab)
    private var page = Page.MAIN
    private var directionTargets = emptyMap<Pair<Int, Int>, View>()

    init {
        mainTab.setOnClickListener { showPage(Page.MAIN, requestContentFocus = true) }
        navTab.setOnClickListener { showPage(Page.NAV, requestContentFocus = true) }
        numTab.setOnClickListener { showPage(Page.NUM, requestContentFocus = true) }

        bindKeyClicks(mainPage)
        bindKeyClicks(navPage)
        bindKeyClicks(numPage)
        showPage(Page.MAIN, requestContentFocus = false)
    }

    fun showPage(target: Page, requestContentFocus: Boolean) {
        page = target
        mainPage.visibility = if (target == Page.MAIN) View.VISIBLE else View.GONE
        navPage.visibility = if (target == Page.NAV) View.VISIBLE else View.GONE
        numPage.visibility = if (target == Page.NUM) View.VISIBLE else View.GONE
        updateTabStyle(mainTab, target == Page.MAIN)
        updateTabStyle(navTab, target == Page.NAV)
        updateTabStyle(numTab, target == Page.NUM)

        root.post {
            rebuildNavigation()
            if (requestContentFocus) requestFirstKeyFocus()
        }
    }

    fun requestInitialFocus() {
        root.post {
            rebuildNavigation()
            requestFirstKeyFocus()
        }
    }

    private fun bindKeyClicks(view: View) {
        if (view is TextView && isKeyboardKey(view)) {
            view.contentDescription = view.contentDescription ?: view.text
            view.setOnClickListener { onKeySelected(view) }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                bindKeyClicks(view.getChildAt(index))
            }
        }
    }

    // The touch listener delegates click/cancel handling to View by returning false.
    @SuppressLint("ClickableViewAccessibility")
    private fun rebuildNavigation() {
        val nodes = buildList {
            addAll(externalViews)
            addAll(tabs)
            addAll(currentKeys())
        }.filter { it.isVisible && it.isShown }

        nodes.forEach { view ->
            if (view.id == View.NO_ID) view.id = View.generateViewId()
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            if (view !== editableView) {
                view.setOnTouchListener { touched, event ->
                    // Take focus before UP so View does not consume the first tap for focus only.
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) touched.requestFocus()
                    false
                }
            }
        }

        val centers = nodes.associateWith(::centerInWindow)
        val targets = mutableMapOf<Pair<Int, Int>, View>()
        nodes.forEach { source ->
            val sourcePoint = centers.getValue(source)
            listOf(
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN
            ).forEach { keyCode ->
                nearestDirectionalTarget(source, sourcePoint, nodes, centers, keyCode)?.let {
                    targets[source.id to keyCode] = it
                }
            }
            source.setOnKeyListener(::handleNodeKey)
        }
        directionTargets = targets
    }

    private fun handleNodeKey(view: View, keyCode: Int, event: KeyEvent): Boolean {
        if (view === editableView && isEditing()) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                return event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP
            }
            return false
        }

        if (isConfirmKey(keyCode)) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (view === editableView) onEnterEditing() else view.performClick()
            }
            return event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP
        }

        if (!isDirectionKey(keyCode)) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            directionTargets[view.id to keyCode]?.let(::focusAndReveal)
        }
        return event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP
    }

    private fun nearestDirectionalTarget(
        source: View,
        sourcePoint: Point,
        nodes: List<View>,
        centers: Map<View, Point>,
        keyCode: Int
    ): View? {
        return nodes.asSequence()
            .filter { it !== source }
            .map { it to centers.getValue(it) }
            .filter { (_, point) ->
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> point.x < sourcePoint.x
                    KeyEvent.KEYCODE_DPAD_RIGHT -> point.x > sourcePoint.x
                    KeyEvent.KEYCODE_DPAD_UP -> point.y < sourcePoint.y
                    KeyEvent.KEYCODE_DPAD_DOWN -> point.y > sourcePoint.y
                    else -> false
                }
            }
            .minByOrNull { (_, point) ->
                val primary = when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT -> abs(point.x - sourcePoint.x)
                    else -> abs(point.y - sourcePoint.y)
                }
                val secondary = when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT -> abs(point.y - sourcePoint.y)
                    else -> abs(point.x - sourcePoint.x)
                }
                primary + secondary * SECONDARY_AXIS_WEIGHT
            }
            ?.first
    }

    private fun currentKeys(): List<View> {
        val result = mutableListOf<View>()
        collectKeys(currentPage(), result)
        return result.sortedWith(compareBy({ centerInWindow(it).y }, { centerInWindow(it).x }))
    }

    private fun collectKeys(view: View, output: MutableList<View>) {
        if (view is TextView && isKeyboardKey(view) && view.isVisible) {
            output.add(view)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                collectKeys(view.getChildAt(index), output)
            }
        }
    }

    private fun currentPage(): View = when (page) {
        Page.MAIN -> mainPage
        Page.NAV -> navPage
        Page.NUM -> numPage
    }

    private fun requestFirstKeyFocus() {
        currentKeys().firstOrNull()?.let(::focusAndReveal)
    }

    private fun focusAndReveal(view: View) {
        view.requestFocus()
        view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), false)
    }

    private fun centerInWindow(view: View): Point {
        val location = IntArray(2)
        view.getLocationInWindow(location)
        return Point(location[0] + view.width / 2, location[1] + view.height / 2)
    }

    private fun updateTabStyle(tab: TextView, active: Boolean) {
        tab.setTextColor(if (active) Color.WHITE else 0xB3FFFFFF.toInt())
        tab.isSelected = active
    }

    private fun isKeyboardKey(view: TextView): Boolean =
        (view.tag as? String)?.matches(KEY_TAG_PATTERN) == true

    private fun isDirectionKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN

    private fun isConfirmKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_BUTTON_A

    companion object {
        private val KEY_TAG_PATTERN = Regex("k\\d+")
        private const val SECONDARY_AXIS_WEIGHT = 4
    }
}
