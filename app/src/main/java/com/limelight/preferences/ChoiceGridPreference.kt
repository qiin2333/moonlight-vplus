package com.limelight.preferences

import android.content.Context
import android.util.AttributeSet
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.material3.Text
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.limelight.R
import com.limelight.utils.appAccentColor

/** 网格内动作档(如「自定义分辨率」)的内部标识;只存在于组件内部,调用方永不接触该值。 */
private const val CHOICE_GRID_ACTION_VALUE = "__choice_grid_action__"

/** 主偏好设置的分辨率/帧率选择网格,与游戏内显示页同款小瓦片(34dp·6dp 圆角)。 */
class ChoiceGridPreference(
    context: Context,
    attrs: AttributeSet?
) : Preference(context, attrs) {
    private var labels by mutableStateOf(emptyList<String>())
    private var subLabels by mutableStateOf(emptyList<String?>())
    private var values by mutableStateOf(emptyList<String>())
    private var enabledValues by mutableStateOf(emptySet<String>())
    private var selected by mutableStateOf("")
    private var actionLabel by mutableStateOf<String?>(null)
    private var focusedChoice: String? = null
    private var focusRequest by mutableStateOf<Pair<String, Int>?>(null)
    private var focusRequestGeneration = 0

    /** 点击不经过选择值流(不触发 callChangeListener/持久化)的尾随动作档。 */
    var onActionClick: (() -> Unit)? = null

    fun isChoiceFocused(value: String): Boolean = focusedChoice == value

    fun restoreChoiceFocus(value: String) {
        focusRequest = value to ++focusRequestGeneration
    }

    fun isActionFocused(): Boolean = focusedChoice == CHOICE_GRID_ACTION_VALUE

    fun restoreActionFocus() {
        focusRequest = CHOICE_GRID_ACTION_VALUE to ++focusRequestGeneration
    }

    fun setAction(label: String?, onAction: (() -> Unit)? = null) {
        actionLabel = label
        onActionClick = onAction
        notifyChanged()
    }

    init {
        layoutResource = R.layout.preference_choice_grid
    }

    fun replaceChoices(
        labels: List<String>,
        values: List<String>,
        selected: String,
        enabledValues: Set<String> = values.toSet(),
        subLabels: List<String?> = emptyList()
    ) {
        this.labels = labels
        this.subLabels = if (subLabels.size == values.size) subLabels else List(values.size) { null }
        this.values = values
        this.enabledValues = enabledValues
        this.selected = selected
        notifyChanged()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.isFocusable = false
        holder.itemView.isFocusableInTouchMode = false
        val host = holder.findViewById(R.id.choice_grid_host) as ComposeView
        host.isFocusable = false
        host.isFocusableInTouchMode = false
        host.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        host.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        host.setContent {
            ChoiceGrid(
                labels = labels,
                values = values,
                enabledValues = enabledValues,
                selected = selected,
                subLabels = subLabels,
                actionLabel = actionLabel,
                onAction = { onActionClick?.invoke() },
                focusRequest = focusRequest,
                onFocusRestored = { focusRequest = null },
                onChoiceFocusChanged = { value, focused ->
                    if (focused) focusedChoice = value
                    else if (focusedChoice == value) focusedChoice = null
                },
                onSelect = { value ->
                    if (callChangeListener(value)) {
                        persistString(value)
                        this.selected = value
                    }
                }
            )
        }
    }
}

@Composable
private fun ChoiceGrid(
    labels: List<String>,
    subLabels: List<String?>,
    values: List<String>,
    enabledValues: Set<String>,
    selected: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    focusRequest: Pair<String, Int>?,
    onFocusRestored: () -> Unit,
    onChoiceFocusChanged: (String, Boolean) -> Unit,
    onSelect: (String) -> Unit
) {
    val gridValues = if (actionLabel != null) values + CHOICE_GRID_ACTION_VALUE else values
    val gridLabels = if (actionLabel != null) labels + actionLabel else labels
    val requesters = androidx.compose.runtime.remember(gridValues) {
        List(gridValues.size) { FocusRequester() }
    }
    LaunchedEffect(focusRequest) {
        val value = focusRequest?.first ?: return@LaunchedEffect
        val index = gridValues.indexOf(value)
        withFrameNanos { }
        if (index >= 0) requesters.getOrNull(index)?.requestFocus()
        onFocusRestored()
    }
    fun choose(value: String) {
        if (value == CHOICE_GRID_ACTION_VALUE) onAction?.invoke() else onSelect(value)
    }
    val view = LocalView.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val longestText = gridLabels.maxByOrNull(String::length).orEmpty()
    val naturalWidth = androidx.compose.runtime.remember(gridLabels, textMeasurer) {
        val measured = textMeasurer.measure(
            longestText,
            TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold)
        )
        with(density) { measured.size.width.toDp() } + 24.dp
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        val columns = ((maxWidth + 8.dp) / (naturalWidth + 8.dp)).toInt().coerceIn(1, 4)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            gridValues.chunked(columns).forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEachIndexed { colIndex, value ->
                        val index = rowIndex * columns + colIndex
                        val isAction = value == CHOICE_GRID_ACTION_VALUE
                        ChoiceTile(
                            label = gridLabels.getOrElse(index) { value },
                            subLabel = if (isAction) null else subLabels.getOrElse(index) { null },
                            selected = value == selected,
                            enabled = isAction || value in enabledValues,
                            isAction = isAction,
                            onClick = { choose(value) },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(requesters[index])
                                .onFocusChanged { onChoiceFocusChanged(value, it.isFocused) }
                                .onPreviewDirection(index, gridValues, columns, requesters, view, ::choose)
                                .semantics { contentDescription = gridLabels.getOrElse(index) { value } }
                        )
                    }
                    repeat(columns - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}


@Composable
private fun ChoiceTile(
    label: String,
    subLabel: String?,
    selected: Boolean,
    enabled: Boolean,
    isAction: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(6.dp)
    val accent = appAccentColor()
    val normalBorder = colorResource(R.color.game_menu_list_item_border)
    val surface = colorResource(R.color.game_menu_list_item_normal)
    val disabledText = colorResource(R.color.game_menu_text_secondary).copy(alpha = 0.38f)
    val primaryText = colorResource(R.color.game_menu_text_primary)
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val dash = if (isAction) PathEffect.dashPathEffect(floatArrayOf(16f, 10f)) else null
    Box(
        modifier = modifier
            .alpha(if (enabled || isAction) 1f else 0.45f)
            .height(40.dp)
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.06f) else surface, shape)
            .drawBehind {
                drawRoundRect(
                    color = when {
                        focused -> accent
                        selected -> accent.copy(alpha = 0.6f)
                        else -> normalBorder
                    },
                    cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = dash)
                )
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .onFocusChanged { focused = it.isFocused }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = if (isAction) "＋" else label,
                color = when {
                    !enabled -> disabledText
                    selected -> accent
                    else -> primaryText
                },
                fontSize = if (isAction) 16.sp else 14.sp,
                fontWeight = FontWeight.Light,
                lineHeight = 16.sp,
                maxLines = 1
            )
            if (!isAction && subLabel != null) {
                Text(
                    text = subLabel,
                    color = colorResource(R.color.game_menu_text_secondary),
                    fontSize = 9.sp,
                    lineHeight = 10.sp,
                    modifier = Modifier.offset(y = (-1).dp),
                    maxLines = 1
                )
            }
        }
    }
}

private fun Modifier.onPreviewDirection(
    index: Int,
    values: List<String>,
    columns: Int,
    requesters: List<FocusRequester>,
    view: android.view.View,
    onSelect: (String) -> Unit
): Modifier = onPreviewKeyEvent { event ->
    val nativeEvent = event.nativeKeyEvent
    if (com.limelight.ui.isMenuConfirmKey(nativeEvent.keyCode)) {
        if (nativeEvent.action == android.view.KeyEvent.ACTION_UP) onSelect(values[index])
        return@onPreviewKeyEvent true
    }
    val backward = nativeEvent.isShiftPressed
    val target = when (nativeEvent.keyCode) {
        android.view.KeyEvent.KEYCODE_TAB -> if (backward) index - 1 else index + 1
        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> index - 1
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> index + 1
        android.view.KeyEvent.KEYCODE_DPAD_UP -> index - columns
        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> index + columns
        else -> return@onPreviewKeyEvent false
    }
    if (nativeEvent.action != android.view.KeyEvent.ACTION_DOWN || target !in values.indices) {
        return@onPreviewKeyEvent false
    }
    val wasFocusableInTouchMode = view.isFocusableInTouchMode
    view.isFocusableInTouchMode = true
    requesters.getOrNull(target)?.requestFocus()
    view.post {
        view.isFocusableInTouchMode = wasFocusableInTouchMode
    }
    true
}
