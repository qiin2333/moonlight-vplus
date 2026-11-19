package com.limelight.ui

import android.app.Dialog
import android.content.Context
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.ui.theme.AppShapes
import com.limelight.utils.AppActionSheet
import kotlinx.coroutines.launch

/**
 * Shows a scrollable explanation. The optional [onAction] dismisses the sheet when it returns true.
 * In-stream callers register the returned dialog as the input owner, including for USB gamepads.
 */
internal fun showAppInfoSheet(
    context: Context,
    title: String,
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Boolean)? = null
): Dialog {
    lateinit var dialog: Dialog
    dialog = AppActionSheet.showCustom(context) {
        val scrollState = rememberScrollState()
        val scope = rememberCoroutineScope()
        val focusRequester = remember { FocusRequester() }
        val actionFocusRequester = remember { FocusRequester() }
        val footerFocusRequester = remember { FocusRequester() }
        val hasAction = actionLabel != null && onAction != null
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        val scrollStep = with(LocalDensity.current) { 48.dp.toPx() }
        LaunchedEffect(windowFocused) {
            if (windowFocused) focusRequester.requestFocus()
        }

        AppActionSheet.ActionSheetContainer {
            AppActionSheet.ActionSheetHeader(title, subtitle = null, activeStatus = false)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.55f).dp)
                    .padding(horizontal = 12.dp)
                    .focusRequester(focusRequester)
                    .gamepadFocusOutline(AppShapes.small)
                    .onPreviewKeyEvent { event ->
                        val direction = when (event.key) {
                            Key.DirectionUp -> -1f
                            Key.DirectionDown -> 1f
                            else -> return@onPreviewKeyEvent false
                        }
                        val canScroll = if (direction < 0) {
                            scrollState.canScrollBackward
                        } else {
                            scrollState.canScrollForward
                        }
                        if (!canScroll) {
                            if (direction > 0) {
                                if (event.type == KeyEventType.KeyDown) {
                                    if (hasAction) actionFocusRequester.requestFocus()
                                    else footerFocusRequester.requestFocus()
                                }
                                return@onPreviewKeyEvent true
                            }
                            return@onPreviewKeyEvent false
                        }
                        if (event.type == KeyEventType.KeyDown) {
                            scope.launch { scrollState.scrollBy(direction * scrollStep) }
                        }
                        true
                    }
                    .focusable()
            ) {
                Text(
                    text = text,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                )
            }
            if (actionLabel != null && onAction != null) {
                AppActionSheet.ActionSheetFooterAction(
                    label = actionLabel,
                    onClick = { if (onAction()) dialog.dismiss() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(actionFocusRequester)
                        .focusProperties {
                            up = focusRequester
                            down = footerFocusRequester
                        }
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            AppActionSheet.ActionSheetFooterAction(
                label = context.getString(android.R.string.ok),
                onClick = { dialog.dismiss() },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(footerFocusRequester)
                    .focusProperties { up = if (hasAction) actionFocusRequester else focusRequester }
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
    return dialog
}
