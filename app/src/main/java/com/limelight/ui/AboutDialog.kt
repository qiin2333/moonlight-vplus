package com.limelight.ui

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.R

/**
 * Compose implementation of the About dialog.
 *
 * Replaces the previous Views XML setup (4 layouts + 9 selector drawables) with a
 * single declarative file. Window chrome (gradient + overlay corner + outline) is
 * provided by [dialogSurface], matching app_dialog_bg_cute.
 */

internal data class EcosystemProject(
    val badge: String,
    val title: String,
    val platform: String,
    val description: String,
    val url: String
)

internal val aboutDialogShape = RoundedCornerShape(30.dp)
internal val ecosystemDialogShape = RoundedCornerShape(26.dp)
private val actionShape = RoundedCornerShape(14.dp)
private val cardShape = RoundedCornerShape(17.dp)

internal object AboutDialogTags {
    const val HANDBOOK = "about_handbook"
    const val ECOSYSTEM = "about_ecosystem"
    const val BILIBILI = "about_bilibili"
    const val GITHUB = "about_github"
    const val QQ = "about_qq"
    const val SITE = "about_site"
    const val CLOSE = "about_close"
    const val ECOSYSTEM_CLOSE = "about_ecosystem_close"

    fun ecosystemItem(index: Int) = "about_ecosystem_item_$index"
}

private fun Modifier.handleGamepadConfirm(onClick: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        val nativeEvent = event.nativeKeyEvent
        if (nativeEvent.keyCode != KeyEvent.KEYCODE_BUTTON_A) {
            false
        } else {
            if (nativeEvent.action == KeyEvent.ACTION_UP) onClick()
            true
        }
    }

/** 焦点指示仅在手柄/键盘导航（非触摸模式）下显示，避免触摸打开时出现焦点框。 */
@Composable
private fun focusIndicationVisible(focused: Boolean): Boolean {
    val view = LocalView.current
    var touchMode by remember(view) {
        mutableStateOf(view.isInTouchMode)
    }
    DisposableEffect(view) {
        val observer = view.viewTreeObserver
        val listener = android.view.ViewTreeObserver.OnTouchModeChangeListener { touchMode = it }
        observer.addOnTouchModeChangeListener(listener)
        onDispose { observer.removeOnTouchModeChangeListener(listener) }
    }
    return focused && !touchMode
}

private fun Modifier.focusTarget(
    requester: FocusRequester,
    tag: String,
    onFocused: () -> Unit,
    up: FocusRequester = requester,
    down: FocusRequester = requester,
    left: FocusRequester = requester,
    right: FocusRequester = requester
): Modifier = focusRequester(requester)
    .focusProperties {
        this.up = up
        this.down = down
        this.left = left
        this.right = right
    }
    .onFocusChanged { if (it.isFocused) onFocused() }
    .testTag(tag)

@Composable
private fun FocusAwareCloseButton(
    onClick: () -> Unit,
    modifier: Modifier,
    contentDescription: String
) {
    var focused by remember { mutableStateOf(false) }
    val showFocus = focusIndicationVisible(focused)
    val shape = RoundedCornerShape(10.dp)
    IconButton(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (showFocus) {
                    Modifier
                        .background(colorResource(R.color.app_dialog_accent_soft), shape)
                        .border(2.dp, colorResource(R.color.app_dialog_accent_color), shape)
                } else {
                    Modifier
                }
            )
            .handleGamepadConfirm(onClick)
            .focusable()
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_about_close),
            contentDescription = contentDescription,
            tint = colorResource(R.color.app_dialog_title_color)
        )
    }
}

@Composable
private fun dialogBrush(): Brush {
    return Brush.linearGradient(
        colors = listOf(
            colorResource(R.color.app_dialog_surface_gradient_start),
            colorResource(R.color.app_dialog_surface_gradient_center),
            colorResource(R.color.app_dialog_surface_gradient_end)
        )
    )
}

@Composable
internal fun AboutDialogSurface(
    shape: RoundedCornerShape = aboutDialogShape,
    content: @Composable () -> Unit
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp - 32).coerceAtLeast(240).dp
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight),
        shape = shape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, colorResource(R.color.app_dialog_outline))
    ) {
        Box(modifier = Modifier.background(dialogBrush())) {
            content()
        }
    }
}

@Composable
private fun AccentTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusModifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val showFocus = focusIndicationVisible(focused)
    val compact = LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    Button(
        onClick = onClick,
        modifier = modifier
            .then(focusModifier)
            .onFocusChanged { focused = it.isFocused }
            .handleGamepadConfirm(onClick)
            .focusable(),
        shape = actionShape,
        border = if (showFocus) {
            BorderStroke(2.dp, colorResource(R.color.app_dialog_accent_color))
        } else {
            null
        },
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (showFocus) {
                colorResource(R.color.app_dialog_accent_soft)
            } else {
                Color.Transparent
            },
            contentColor = colorResource(R.color.app_dialog_accent_color)
        ),
        contentPadding = PaddingValues(
            horizontal = 8.dp,
            vertical = if (compact) 10.dp else 13.dp
        )
    ) {
        // material3.Text 会在 Button 内将拉丁文本渲染为全大写，这里用 BasicText 保持原始大小写
        BasicText(
            text,
            style = TextStyle(
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp,
                color = colorResource(R.color.app_dialog_accent_color)
            )
        )
    }
}

@Composable
private fun BilibiliCard(
    onClick: () -> Unit,
    focusModifier: Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val showFocus = focusIndicationVisible(focused)
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(focusModifier)
            .onFocusChanged { focused = it.isFocused }
            .handleGamepadConfirm(onClick)
            .focusable(),
        shape = cardShape,
        color = if (showFocus) {
            colorResource(R.color.app_dialog_surface_focused)
        } else {
            colorResource(R.color.about_dialog_link_surface)
        },
        border = BorderStroke(
            if (showFocus) 2.dp else 1.dp,
            if (showFocus) {
                colorResource(R.color.app_dialog_accent_color)
            } else {
                colorResource(R.color.about_dialog_panel_outline)
            }
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = if (LocalConfiguration.current.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
                ) 60.dp else 66.dp)
                .padding(start = 14.dp, top = 10.dp, end = 10.dp, bottom = 10.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(width = 38.dp, height = 32.dp)
                    .border(
                        2.dp,
                        colorResource(R.color.app_dialog_accent_color),
                        RoundedCornerShape(9.dp)
                    )
            ) {
                Text(
                    stringResource(R.string.about_dialog_bilibili_badge),
                    color = colorResource(R.color.app_dialog_accent_color),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    stringResource(R.string.about_dialog_bilibili_title),
                    color = colorResource(R.color.app_dialog_accent_color),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    stringResource(R.string.about_dialog_bilibili_summary),
                    color = colorResource(R.color.app_dialog_subtitle_color),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_about_chevron_right),
                contentDescription = null,
                tint = colorResource(R.color.app_dialog_accent_color),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
internal fun AboutDialogContent(
    appName: String,
    versionInfo: String,
    onHandbook: () -> Unit,
    onEcosystem: () -> Unit,
    onBilibili: () -> Unit,
    onGithub: () -> Unit,
    onQq: () -> Unit,
    onSite: () -> Unit,
    onClose: () -> Unit,
    initialFocusIndex: Int = 0,
    focusRequestGeneration: Int = 0,
    onFocusChanged: (Int) -> Unit = {}
) {
    val focusRequesters = remember { List(7) { FocusRequester() } }
    val targetIndex = initialFocusIndex.takeIf { it in focusRequesters.indices } ?: 6

    LaunchedEffect(targetIndex, focusRequestGeneration) {
        withFrameNanos { }
        focusRequesters[targetIndex].requestFocus()
    }

    fun focusModifier(index: Int, up: Int, down: Int, left: Int, right: Int): Modifier {
        return Modifier.focusTarget(
            requester = focusRequesters[index],
            tag = when (index) {
                0 -> AboutDialogTags.HANDBOOK
                1 -> AboutDialogTags.ECOSYSTEM
                2 -> AboutDialogTags.BILIBILI
                3 -> AboutDialogTags.GITHUB
                4 -> AboutDialogTags.QQ
                5 -> AboutDialogTags.SITE
                else -> AboutDialogTags.CLOSE
            },
            onFocused = { onFocusChanged(index) },
            up = focusRequesters[up],
            down = focusRequesters[down],
            left = focusRequesters[left],
            right = focusRequesters[right]
        )
    }

    val isLandscape = LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE

    AboutDialogSurface(
        shape = if (isLandscape) RoundedCornerShape(26.dp) else aboutDialogShape
    ) {
        Column {
            Box(modifier = Modifier.weight(1f, fill = false)) {
                if (isLandscape) {
                    LandscapeAboutContent(
                        appName = appName,
                        versionInfo = versionInfo,
                        onHandbook = onHandbook,
                        onEcosystem = onEcosystem,
                        onBilibili = onBilibili,
                        handbookFocus = focusModifier(0, 6, 2, 0, 1),
                        ecosystemFocus = focusModifier(1, 6, 2, 0, 1),
                        bilibiliFocus = focusModifier(2, 0, 3, 2, 2)
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(start = 28.dp, top = 28.dp, end = 32.dp, bottom = 24.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.vplus),
                            contentDescription = stringResource(R.string.app_label),
                            tint = Color.Unspecified,
                            modifier = Modifier.size(76.dp)
                        )
                        Text(
                            appName,
                            color = colorResource(R.color.app_dialog_title_color),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                        Text(
                            versionInfo,
                            color = colorResource(R.color.app_dialog_subtitle_color),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Box(
                            modifier = Modifier
                                .padding(vertical = 22.dp)
                                .width(64.dp)
                                .height(3.dp)
                                .background(
                                    colorResource(R.color.app_dialog_accent_color),
                                    RoundedCornerShape(2.dp)
                                )
                        )
                        Text(
                            stringResource(R.string.about_dialog_description),
                            color = colorResource(R.color.app_dialog_title_color),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            stringResource(R.string.about_dialog_project_info),
                            color = colorResource(R.color.app_dialog_subtitle_color),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 14.dp)
                        )
                        Text(
                            stringResource(R.string.about_dialog_thanks),
                            color = colorResource(R.color.app_dialog_subtitle_color),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 12.dp)
                        ) {
                            AccentTextButton(
                                stringResource(R.string.about_dialog_handbook_action),
                                onHandbook,
                                modifier = Modifier.weight(1f),
                                focusModifier = focusModifier(0, 6, 2, 0, 1)
                            )
                            AccentTextButton(
                                stringResource(R.string.about_dialog_ecosystem_action),
                                onEcosystem,
                                modifier = Modifier.weight(1f),
                                focusModifier = focusModifier(1, 6, 2, 0, 1)
                            )
                        }

                        BilibiliCard(
                            onClick = onBilibili,
                            focusModifier = focusModifier(2, 0, 3, 2, 2)
                        )
                    }
                }

                FocusAwareCloseButton(
                    onClick = onClose,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .size(36.dp)
                        .then(focusModifier(6, 6, 1, 6, 6)),
                    contentDescription = stringResource(R.string.about_dialog_close)
                )
            }

            // bottom external links bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colorResource(R.color.app_dialog_outline))
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colorResource(R.color.about_dialog_links_bar_surface))
                    .padding(
                        start = 14.dp,
                        end = 14.dp,
                        top = if (isLandscape) 8.dp else 12.dp,
                        bottom = if (isLandscape) 8.dp else 12.dp
                    )
            ) {
                AccentTextButton(
                    stringResource(R.string.about_dialog_github),
                    onGithub,
                    modifier = Modifier.weight(1f),
                    focusModifier = focusModifier(3, 2, 3, 3, 4)
                )
                AccentTextButton(
                    stringResource(R.string.about_dialog_qq),
                    onQq,
                    modifier = Modifier.weight(1f),
                    focusModifier = focusModifier(4, 2, 4, 3, 5)
                )
                AccentTextButton(
                    stringResource(R.string.about_dialog_official_site),
                    onSite,
                    modifier = Modifier.weight(1f),
                    focusModifier = focusModifier(5, 2, 5, 4, 5)
                )
            }
        }
    }
}

/** 横屏关于页：brand 面板（230dp）与详情面板左右分栏，垂直居中。 */
@Composable
private fun LandscapeAboutContent(
    appName: String,
    versionInfo: String,
    onHandbook: () -> Unit,
    onEcosystem: () -> Unit,
    onBilibili: () -> Unit,
    handbookFocus: Modifier,
    ecosystemFocus: Modifier,
    bilibiliFocus: Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 28.dp, top = 20.dp, end = 34.dp, bottom = 16.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(230.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.vplus),
                contentDescription = stringResource(R.string.app_label),
                tint = Color.Unspecified,
                modifier = Modifier.size(76.dp)
            )
            Text(
                appName,
                color = colorResource(R.color.app_dialog_title_color),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 10.dp)
            )
            Text(
                versionInfo,
                color = colorResource(R.color.app_dialog_subtitle_color),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp)
            )
            Box(
                modifier = Modifier
                    .padding(top = 18.dp)
                    .width(64.dp)
                    .height(3.dp)
                    .background(
                        colorResource(R.color.app_dialog_accent_color),
                        RoundedCornerShape(2.dp)
                    )
            )
        }

        Spacer(modifier = Modifier.width(24.dp))

        Column(modifier = Modifier.weight(1f).padding(end = 18.dp)) {
            Text(
                stringResource(R.string.about_dialog_description),
                color = colorResource(R.color.app_dialog_title_color),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                stringResource(R.string.about_dialog_project_info),
                color = colorResource(R.color.app_dialog_subtitle_color),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            )
            Text(
                stringResource(R.string.about_dialog_thanks),
                color = colorResource(R.color.app_dialog_subtitle_color),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp)
            ) {
                AccentTextButton(
                    stringResource(R.string.about_dialog_handbook_action),
                    onHandbook,
                    modifier = Modifier.weight(1f),
                    focusModifier = handbookFocus
                )
                AccentTextButton(
                    stringResource(R.string.about_dialog_ecosystem_action),
                    onEcosystem,
                    modifier = Modifier.weight(1f),
                    focusModifier = ecosystemFocus
                )
            }

            BilibiliCard(
                onClick = onBilibili,
                focusModifier = bilibiliFocus
            )
        }
    }
}

@Composable
internal fun EcosystemDialogContent(
    projects: List<EcosystemProject>,
    onOpen: (EcosystemProject) -> Unit,
    onClose: () -> Unit,
    initialFocusIndex: Int = 0,
    focusRequestGeneration: Int = 0,
    onFocusChanged: (Int) -> Unit = {}
) {
    val isLandscape = LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val itemFocus = remember(projects.size) { List(projects.size) { FocusRequester() } }
    val closeFocus = remember { FocusRequester() }
    val validInitialIndex = initialFocusIndex.takeIf { it in projects.indices }

    LaunchedEffect(validInitialIndex, projects, focusRequestGeneration) {
        withFrameNanos { }
        validInitialIndex?.let(itemFocus::get)?.requestFocus() ?: closeFocus.requestFocus()
    }

    AboutDialogSurface(shape = ecosystemDialogShape) {
        // 列数按对话框整体宽度决定（设计稿：窄屏 1 列，448dp 常规 2 列），需在 28dp 内边距外测量
        BoxWithConstraints {
            val columns = if (isLandscape) 3 else if (maxWidth >= 420.dp) 2 else 1
            Box {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(
                            start = if (isLandscape) 24.dp else 28.dp,
                            top = if (isLandscape) 22.dp else 28.dp,
                            end = if (isLandscape) 24.dp else 28.dp,
                            bottom = if (isLandscape) 18.dp else 28.dp
                        )
                ) {
                    Text(
                        stringResource(R.string.about_dialog_ecosystem_title),
                        color = colorResource(R.color.app_dialog_title_color),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(end = 48.dp)
                    )
                    Text(
                        stringResource(R.string.about_dialog_ecosystem_lead),
                        color = colorResource(R.color.app_dialog_subtitle_color),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp, bottom = 14.dp, end = 48.dp)
                    )

                    EcosystemGrid(
                        projects,
                        onOpen,
                        itemFocus,
                        closeFocus,
                        columns = columns,
                        compactCards = isLandscape,
                        onFocusChanged = onFocusChanged
                    )

                    Text(
                        stringResource(R.string.about_dialog_ecosystem_footer),
                        color = colorResource(R.color.app_dialog_subtitle_color),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 18.dp)
                    )
                }

                FocusAwareCloseButton(
                    onClick = onClose,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .size(36.dp)
                        .focusTarget(
                            requester = closeFocus,
                            tag = AboutDialogTags.ECOSYSTEM_CLOSE,
                            onFocused = { onFocusChanged(-1) },
                            down = itemFocus.firstOrNull() ?: closeFocus
                        ),
                    contentDescription = stringResource(R.string.about_dialog_close)
                )
            }
        }
    }
}

@Composable
private fun EcosystemGrid(
    projects: List<EcosystemProject>,
    onOpen: (EcosystemProject) -> Unit,
    itemFocus: List<FocusRequester>,
    closeFocus: FocusRequester,
    columns: Int,
    compactCards: Boolean = false,
    onFocusChanged: (Int) -> Unit
) {
    val gridSpacing = if (compactCards) 10.dp else 12.dp
    Column(verticalArrangement = Arrangement.spacedBy(gridSpacing)) {
        projects.indices.chunked(columns).forEach { rowIndices ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(gridSpacing),
                modifier = Modifier.fillMaxWidth()
            ) {
                rowIndices.forEach { index ->
                    val project = projects[index]
                    val upIndex = GridFocusNavigator.nextIndex(
                        index,
                        projects.size,
                        columns,
                        GridFocusDirection.UP
                    )
                    val downIndex = GridFocusNavigator.nextIndex(
                        index,
                        projects.size,
                        columns,
                        GridFocusDirection.DOWN
                    )
                    val leftIndex = GridFocusNavigator.nextIndex(
                        index,
                        projects.size,
                        columns,
                        GridFocusDirection.LEFT
                    )
                    val rightIndex = GridFocusNavigator.nextIndex(
                        index,
                        projects.size,
                        columns,
                        GridFocusDirection.RIGHT
                    )
                    EcosystemCard(
                        project = project,
                        onOpen = onOpen,
                        compact = compactCards,
                        focusModifier = Modifier
                            .weight(1f)
                            .focusTarget(
                                requester = itemFocus[index],
                                tag = AboutDialogTags.ecosystemItem(index),
                                onFocused = { onFocusChanged(index) },
                                up = if (upIndex == GridFocusNavigator.CLOSE_TARGET) {
                                    closeFocus
                                } else {
                                    itemFocus[upIndex]
                                },
                                down = itemFocus[downIndex],
                                left = itemFocus[leftIndex],
                                right = itemFocus[rightIndex]
                            )
                    )
                }
                repeat(columns - rowIndices.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
@Composable
private fun EcosystemCard(
    project: EcosystemProject,
    onOpen: (EcosystemProject) -> Unit,
    compact: Boolean = false,
    focusModifier: Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val showFocus = focusIndicationVisible(focused)
    Surface(
        onClick = { onOpen(project) },
        shape = cardShape,
        color = if (showFocus) {
            colorResource(R.color.app_dialog_surface_focused)
        } else {
            colorResource(R.color.app_dialog_surface_elevated)
        },
        border = BorderStroke(
            if (showFocus) 2.dp else 1.dp,
            if (showFocus) {
                colorResource(R.color.app_dialog_accent_color)
            } else {
                colorResource(R.color.about_dialog_panel_outline)
            }
        ),
        modifier = Modifier
            .then(focusModifier)
            .onFocusChanged { focused = it.isFocused }
            .handleGamepadConfirm { onOpen(project) }
            .focusable()
    ) {
        Column(
            modifier = Modifier
                .padding(if (compact) 12.dp else 15.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(38.dp)
                        .border(
                            1.dp,
                            colorResource(R.color.about_dialog_panel_outline),
                            RoundedCornerShape(11.dp)
                        )
                        .background(
                            colorResource(R.color.app_dialog_accent_soft),
                            RoundedCornerShape(11.dp)
                        )
                ) {
                    Text(
                        project.badge,
                        color = colorResource(R.color.app_dialog_accent_color),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(
                        project.title,
                        color = colorResource(R.color.app_dialog_title_color),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        project.platform,
                        color = colorResource(R.color.app_dialog_accent_color),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            Text(
                project.description,
                color = colorResource(R.color.app_dialog_subtitle_color),
                style = MaterialTheme.typography.labelSmall,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = if (compact) 8.dp else 12.dp)
            )
        }
    }
}
