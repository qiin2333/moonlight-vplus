package com.limelight.gamemenu

// Game Menu theme, adaptive shell, header, and footer.

import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag as semanticsTestTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.limelight.R
import com.limelight.ui.FeatureGuideRegistry
import com.limelight.ui.FeatureGuideStore
import com.limelight.ui.theme.AppCornerRadii
import com.limelight.ui.theme.AppShapes
import com.joco.showcase.sequence.SequenceShowcase
import com.joco.showcase.sequence.rememberSequenceShowcaseState
import com.joco.showcaseview.BackgroundAlpha
import com.joco.showcaseview.ShowcaseAlignment
import com.joco.showcaseview.ShowcasePosition
import com.joco.showcaseview.highlight.ShowcaseHighlight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val GameMenuDialogShape = RoundedCornerShape(
    topStart = AppCornerRadii.overlay,
    topEnd = AppCornerRadii.overlay
)
internal val GameMenuCardShape = AppShapes.medium
internal val GameMenuControlRadius = AppCornerRadii.medium
internal val GameMenuControlShape = RoundedCornerShape(GameMenuControlRadius)
private const val GAME_MENU_MAX_HEIGHT_FRACTION = 0.90f
private const val GAME_MENU_WIDE_LAYOUT_MIN_WIDTH_DP = 576
private const val ORIENTATION_MISMATCH_THRESHOLD = 1.5f
private const val GAME_MENU_LANDSCAPE_WIDTH_FRACTION = 0.98f
private const val GAME_MENU_PORTRAIT_WIDTH_FRACTION = 0.95f
internal const val GAME_MENU_BACKDROP_TAG = "gameMenuBackdrop"
internal const val GAME_MENU_PANEL_TAG = "gameMenuPanel"
internal const val GAME_MENU_GUIDE_INPUT_BLOCKER_TAG = "gameMenuGuideInputBlocker"
internal val LocalGameMenuHapticFeedback = staticCompositionLocalOf<(Int) -> Unit> { {} }

internal object GameMenuDimens {
    val surfaceStroke = 0.75.dp
    val tight = 4.dp
    val compact = 6.dp
    val section = 8.dp
    val outer = 12.dp
    val compactScreenInset = 16.dp
    val wideScreenInset = 10.dp
}

private object GameMenuFabricSpec {
    val spacing = 6.dp
    val strokeWidth = 0.30.dp
    const val SHADOW_OFFSET_FRACTION = 0.36f
}

internal object GameMenuSliderSpec {
    val height = 36.dp
    val thumbSize = DpSize(width = 3.dp, height = 34.dp)
    val trackHeight = 12.dp
    val thumbTrackGap = 4.dp
    val trackInsideCorner = 1.5.dp
}

private data class GameMenuPalette(
    val accent: Color,
    val card: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val dialogBackground: Color,
    val dialogBorder: Color,
    val darkTheme: Boolean
)

@Composable
internal fun GameMenuScreen(
    state: GameMenuComposeUiState,
    callbacks: GameMenuCallbacks,
    hardwareFocusRequestToken: Int,
    guideDismissController: GameMenuGuideDismissController,
    useFabricTexture: Boolean = true,
    restoreFocusRequestToken: Int = 0
) {
    val palette = gameMenuPalette()
    val appContext = LocalContext.current.applicationContext
    val showcaseState = rememberSequenceShowcaseState()
    val initialFocusRequester = remember { FocusRequester() }
    val menuGroupFocusRequester = remember { FocusRequester() }
    val inputModeManager = LocalInputModeManager.current
    var guideStore by remember(appContext) { mutableStateOf<FeatureGuideStore?>(null) }
    var guidePending by remember(appContext) { mutableStateOf(false) }
    var guideActive by remember(appContext) { mutableStateOf(false) }
    var menuContentLaidOut by remember(state.title, state.isSubmenu) { mutableStateOf(false) }
    var quickActionGuideTargetLaidOut by remember(state.title, state.isSubmenu) {
        mutableStateOf(false)
    }
    var crownGuideTargetLaidOut by remember(state.title, state.isSubmenu) {
        mutableStateOf(false)
    }
    var menuHasFocus by remember { mutableStateOf(false) }
    var handledRestoreFocusRequestToken by remember { mutableIntStateOf(0) }
    LaunchedEffect(appContext) {
        val (store, shouldShow) = withContext(Dispatchers.IO) {
            val loadedStore = FeatureGuideStore(appContext)
            loadedStore to loadedStore.shouldShow(FeatureGuideRegistry.GameMenuDiscovery)
        }
        guideStore = store
        guidePending = shouldShow
    }
    val configuration = LocalConfiguration.current
    val windowWidth = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width.toDp()
    }
    val maxMenuHeight = rememberGameMenuMaxHeight()
    val wideLayout = windowWidth >= GAME_MENU_WIDE_LAYOUT_MIN_WIDTH_DP.dp
    val horizontalInset = if (wideLayout) {
        GameMenuDimens.wideScreenInset
    } else {
        GameMenuDimens.compactScreenInset
    }
    val menuWidthFraction = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        GAME_MENU_LANDSCAPE_WIDTH_FRACTION
    } else {
        GAME_MENU_PORTRAIT_WIDTH_FRACTION
    }
    val finishGuide = {
        guideDismissController.clear()
        guideStore?.markCompleted(FeatureGuideRegistry.GameMenuDiscovery)
        guidePending = false
        guideActive = false
        showcaseState.dismiss()
    }

    DisposableEffect(guideDismissController) {
        onDispose { guideDismissController.clear() }
    }

    GameMenuTheme(palette) {
        SequenceShowcase(state = showcaseState) {
            val quickActionGuideModifier = Modifier.sequenceShowcaseTarget(
                index = 0,
                position = ShowcasePosition.Bottom,
                alignment = ShowcaseAlignment.Start,
                highlight = ShowcaseHighlight.Rectangular(12.dp),
                backgroundAlpha = BackgroundAlpha.Dark
            ) {
                CuteFeatureGuideCard(
                    eyebrow = stringResource(R.string.feature_guide_step, 1, 2),
                    title = stringResource(R.string.feature_guide_quick_actions_title),
                    body = stringResource(R.string.feature_guide_quick_actions_body),
                    actionLabel = stringResource(R.string.feature_guide_next),
                    onAction = showcaseState::next,
                    onSkip = finishGuide,
                    hardwareFocusRequestToken = hardwareFocusRequestToken
                )
            }.onGloballyPositioned {
                quickActionGuideTargetLaidOut = it.size.width > 0 && it.size.height > 0
            }
            val crownGuideModifier = Modifier.sequenceShowcaseTarget(
                index = 1,
                position = ShowcasePosition.Bottom,
                alignment = ShowcaseAlignment.End,
                highlight = ShowcaseHighlight.Circular(targetMargin = 8.dp),
                backgroundAlpha = BackgroundAlpha.Dark
            ) {
                CuteFeatureGuideCard(
                    eyebrow = stringResource(R.string.feature_guide_step, 2, 2),
                    title = stringResource(R.string.feature_guide_crown_title),
                    body = stringResource(R.string.feature_guide_crown_body),
                    actionLabel = stringResource(R.string.feature_guide_done),
                    onAction = finishGuide,
                    onSkip = finishGuide,
                    hardwareFocusRequestToken = hardwareFocusRequestToken
                )
            }.onGloballyPositioned {
                crownGuideTargetLaidOut = it.size.width > 0 && it.size.height > 0
            }

            GameMenuDialogShell(
                widthFraction = menuWidthFraction,
                horizontalInset = horizontalInset,
                onDismissRequest = callbacks.onDismiss
            ) {
                Surface(
                    color = Color.Transparent,
                    shape = GameMenuDialogShape,
                    border = BorderStroke(GameMenuDimens.surfaceStroke, palette.dialogBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxMenuHeight)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(menuGroupFocusRequester)
                            .focusRestorer(initialFocusRequester)
                            .onFocusChanged { menuHasFocus = it.hasFocus }
                            .focusGroup()
                            .onGloballyPositioned { menuContentLaidOut = true }
                            .gameMenuFabricBackground(
                                baseColor = palette.dialogBackground,
                                darkTheme = palette.darkTheme,
                                textureEnabled = useFabricTexture
                            )
                    ) {
                        GameMenuContent(
                            state = state,
                            callbacks = callbacks,
                            wideLayout = wideLayout &&
                                (!state.isSubmenu ||
                                    state.pageLayout == GameMenuPageLayout.TOUCH_MODE),
                            maxMenuHeight = maxMenuHeight,
                            initialFocusRequester = initialFocusRequester,
                            quickActionGuideModifier = quickActionGuideModifier,
                            crownGuideModifier = crownGuideModifier
                        )
                    }
                }
            }
            if (guideActive) {
                GameMenuGuideInputBlocker()
            }
        }
    }

    LaunchedEffect(
        guidePending,
        state.isSubmenu,
        menuContentLaidOut,
        quickActionGuideTargetLaidOut,
        crownGuideTargetLaidOut
    ) {
        if (shouldStartGameMenuGuide(
                guidePending = guidePending,
                isSubmenu = state.isSubmenu,
                menuContentLaidOut = menuContentLaidOut,
                quickActionTargetLaidOut = quickActionGuideTargetLaidOut,
                crownTargetLaidOut = crownGuideTargetLaidOut
            )
        ) {
            // Consume the launch in this composition. "Maybe later" remains
            // incomplete in the store, so it can appear on a future menu visit.
            guidePending = false
            guideActive = true
            guideDismissController.register(finishGuide)
            showcaseState.start()
        }
    }

    LaunchedEffect(
        hardwareFocusRequestToken,
        state.title,
        state.isSubmenu,
        guideActive,
        menuContentLaidOut
    ) {
        if (shouldRequestGameMenuFocus(
                hardwareFocusRequestToken = hardwareFocusRequestToken,
                guideActive = guideActive,
                hasOptions = state.options.isNotEmpty(),
                menuContentLaidOut = menuContentLaidOut,
                menuHasFocus = menuHasFocus
            )
        ) {
            inputModeManager.requestInputMode(InputMode.Keyboard)
            initialFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(
        restoreFocusRequestToken,
        guideActive,
        menuContentLaidOut
    ) {
        if (shouldRestoreGameMenuFocus(
                restoreFocusRequestToken = restoreFocusRequestToken,
                handledRestoreFocusRequestToken = handledRestoreFocusRequestToken,
                guideActive = guideActive,
                menuContentLaidOut = menuContentLaidOut
            )
        ) {
            handledRestoreFocusRequestToken = restoreFocusRequestToken
            inputModeManager.requestInputMode(InputMode.Keyboard)
            menuGroupFocusRequester.requestFocus()
        }
    }
}

internal fun shouldStartGameMenuGuide(
    guidePending: Boolean,
    isSubmenu: Boolean,
    menuContentLaidOut: Boolean,
    quickActionTargetLaidOut: Boolean,
    crownTargetLaidOut: Boolean
): Boolean = guidePending &&
    !isSubmenu &&
    menuContentLaidOut &&
    quickActionTargetLaidOut &&
    crownTargetLaidOut

internal fun shouldRequestGameMenuFocus(
    hardwareFocusRequestToken: Int,
    guideActive: Boolean,
    hasOptions: Boolean,
    menuContentLaidOut: Boolean,
    menuHasFocus: Boolean
): Boolean {
    return hardwareFocusRequestToken > 0 &&
        !guideActive &&
        hasOptions &&
        menuContentLaidOut &&
        !menuHasFocus
}

internal fun shouldRestoreGameMenuFocus(
    restoreFocusRequestToken: Int,
    handledRestoreFocusRequestToken: Int,
    guideActive: Boolean,
    menuContentLaidOut: Boolean
): Boolean = restoreFocusRequestToken > handledRestoreFocusRequestToken &&
    !guideActive &&
    menuContentLaidOut

@Composable
internal fun GameMenuDialogShell(
    widthFraction: Float,
    horizontalInset: Dp,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit
) {
    val backdropInteraction = remember { MutableInteractionSource() }
    val safeDrawingPadding = WindowInsets.safeDrawing.asPaddingValues()
    val layoutDirection = LocalLayoutDirection.current
    val safeHorizontalPadding = symmetricHorizontalPadding(
        safeDrawingPadding.calculateLeftPadding(layoutDirection),
        safeDrawingPadding.calculateRightPadding(layoutDirection)
    )
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = backdropInteraction,
                    indication = null,
                    onClick = onDismissRequest
                )
                .focusProperties { canFocus = false }
                .clearAndSetSemantics {
                    semanticsTestTag = GAME_MENU_BACKDROP_TAG
                }
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(widthFraction)
                .padding(
                    start = safeHorizontalPadding,
                    top = safeDrawingPadding.calculateTopPadding(),
                    end = safeHorizontalPadding,
                    bottom = safeDrawingPadding.calculateBottomPadding()
                )
                .padding(horizontal = horizontalInset)
                .testTag(GAME_MENU_PANEL_TAG)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        waitForUpOrCancellation()
                    }
                }
        ) {
            content()
        }
    }
}

@Composable
internal fun GameMenuGuideInputBlocker() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial
                    )
                    down.consume()
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }
            .focusProperties { canFocus = false }
            .clearAndSetSemantics {
                semanticsTestTag = GAME_MENU_GUIDE_INPUT_BLOCKER_TAG
            }
    )
}

@Composable
private fun gameMenuPalette() = GameMenuPalette(
    accent = colorResource(R.color.game_menu_accent),
    card = colorResource(R.color.game_menu_card_background),
    textPrimary = colorResource(R.color.game_menu_text_primary),
    textSecondary = colorResource(R.color.game_menu_text_secondary),
    dialogBackground = colorResource(R.color.game_menu_dialog_background),
    dialogBorder = colorResource(R.color.game_menu_dialog_border),
    darkTheme = isSystemInDarkTheme()
)

@Composable
private fun GameMenuTheme(
    palette: GameMenuPalette,
    content: @Composable () -> Unit
) {
    val baseColorScheme = if (palette.darkTheme) darkColorScheme() else lightColorScheme()
    MaterialTheme(
        colorScheme = baseColorScheme.copy(
            primary = palette.accent,
            onPrimary = Color.White,
            primaryContainer = palette.accent.copy(alpha = 0.12f),
            onPrimaryContainer = palette.accent,
            secondary = palette.accent,
            onSecondary = Color.White,
            secondaryContainer = palette.accent.copy(alpha = 0.16f),
            onSecondaryContainer = palette.accent,
            tertiary = palette.accent,
            onTertiary = Color.White,
            tertiaryContainer = palette.accent.copy(alpha = 0.12f),
            onTertiaryContainer = palette.accent,
            surface = palette.card,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.card,
            surfaceContainerHighest = palette.accent.copy(alpha = 0.10f),
            onSurfaceVariant = palette.textSecondary,
            outline = palette.dialogBorder
        ),
        content = content
    )
}

@Composable
private fun GameMenuContent(
    state: GameMenuComposeUiState,
    callbacks: GameMenuCallbacks,
    wideLayout: Boolean,
    maxMenuHeight: Dp,
    initialFocusRequester: FocusRequester,
    quickActionGuideModifier: Modifier = Modifier,
    crownGuideModifier: Modifier = Modifier
) {
    var sliderGestureActive by remember { mutableStateOf(false) }
    val menuScrollState = rememberScrollState()
    val wideTouchMode = wideLayout && state.pageLayout == GameMenuPageLayout.TOUCH_MODE
    val touchModeBackFocusRequester = remember { FocusRequester() }

    LaunchedEffect(state.title, state.isSubmenu) {
        menuScrollState.scrollTo(0)
    }

    val contentModifier = if (wideTouchMode) {
        Modifier
            .fillMaxWidth()
            .height(maxMenuHeight)
    } else {
        Modifier.verticalScroll(
            state = menuScrollState,
            enabled = !sliderGestureActive
        )
    }
    Column(
        modifier = contentModifier.padding(GameMenuDimens.outer),
        verticalArrangement = Arrangement.spacedBy(GameMenuDimens.section)
    ) {
        GameMenuHeader(
            state = state,
            callbacks = callbacks,
            crownGuideModifier = crownGuideModifier,
            backFocusRequester = touchModeBackFocusRequester.takeIf { wideTouchMode },
            backDownFocusRequester = initialFocusRequester.takeIf { wideTouchMode }
        )

        if (!state.isSubmenu) {
            QuickActionRow(
                actions = state.quickActions,
                superOptions = state.superOptions,
                editMode = state.quickEditMode,
                onAction = callbacks.onQuickAction,
                onSuperOptionClick = callbacks.onOptionClick,
                onEmptySuperCommandClick = callbacks.onEmptySuperCommandClick,
                onToggleEdit = callbacks.onToggleQuickEdit,
                onAdd = callbacks.onAddQuickAction,
                onRemove = callbacks.onRemoveQuickAction,
                onMove = callbacks.onMoveQuickAction,
                modifier = quickActionGuideModifier
            )
        }

        if (wideTouchMode) {
            TouchModeTable(
                options = state.options,
                callbacks = callbacks,
                initialFocusRequester = initialFocusRequester,
                topFocusRequester = touchModeBackFocusRequester,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        } else if (wideLayout && !state.isSubmenu) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(GameMenuDimens.section),
                verticalAlignment = Alignment.Top
            ) {
                MenuOptionColumn(
                    options = state.options,
                    iconForOption = callbacks.iconForOption,
                    onOptionClick = callbacks.onOptionClick,
                    onInlineToggle = callbacks.onInlineToggle,
                    onSegmentClick = callbacks.onSegmentClick,
                    modifier = Modifier.weight(1f),
                    initialFocusRequester = initialFocusRequester
                )
                GameMenuCards(
                    state = state,
                    callbacks = callbacks,
                    modifier = Modifier.weight(1f),
                    onSliderGesture = { sliderGestureActive = it }
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(GameMenuDimens.section)) {
                MenuOptionColumn(
                    state.options,
                    callbacks.iconForOption,
                    callbacks.onOptionClick,
                    callbacks.onInlineToggle,
                    callbacks.onSegmentClick,
                    initialFocusRequester = initialFocusRequester
                )
                if (!state.isSubmenu) {
                    GameMenuCards(state, callbacks) { sliderGestureActive = it }
                }
            }
        }

        if (!state.isSubmenu && state.appName.isNotBlank()) {
            GameMenuFooter(state.appName)
        }
    }
}

internal fun symmetricHorizontalPadding(left: Dp, right: Dp): Dp = maxOf(left, right)

internal data class TouchModeFocusTargets(
    val left: Int?,
    val right: Int?,
    val up: Int?,
    val down: Int?
)

internal fun touchModeFocusTargets(
    primaryCount: Int,
    compatibleCount: Int,
    globalIndex: Int
): TouchModeFocusTargets {
    require(primaryCount >= 0 && compatibleCount >= 0)
    val totalCount = primaryCount + compatibleCount
    require(globalIndex in 0 until totalCount)

    return if (globalIndex < primaryCount) {
        TouchModeFocusTargets(
            left = null,
            right = if (compatibleCount > 0) {
                primaryCount + minOf(globalIndex, compatibleCount - 1)
            } else {
                null
            },
            up = (globalIndex - 1).takeIf { globalIndex > 0 },
            down = (globalIndex + 1).takeIf { it < primaryCount }
        )
    } else {
        val compatibleIndex = globalIndex - primaryCount
        TouchModeFocusTargets(
            left = if (primaryCount > 0) minOf(compatibleIndex, primaryCount - 1) else null,
            right = null,
            up = (globalIndex - 1).takeIf { compatibleIndex > 0 },
            down = (globalIndex + 1).takeIf { compatibleIndex + 1 < compatibleCount }
        )
    }
}

internal fun Modifier.touchModeFocusNavigation(
    targets: TouchModeFocusTargets,
    focusRequesters: List<FocusRequester>,
    topFocusRequester: FocusRequester
): Modifier = focusProperties {
    left = targets.left?.let(focusRequesters::get) ?: FocusRequester.Cancel
    right = targets.right?.let(focusRequesters::get) ?: FocusRequester.Cancel
    up = targets.up?.let(focusRequesters::get) ?: topFocusRequester
    down = targets.down?.let(focusRequesters::get) ?: FocusRequester.Cancel
}

@Composable
private fun TouchModeTable(
    options: List<GameMenu.MenuOption>,
    callbacks: GameMenuCallbacks,
    initialFocusRequester: FocusRequester,
    topFocusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val primaryModes = options.filter {
        it.presentation == GameMenuOptionPresentation.PRIMARY_MODE
    }
    val compatibleActions = options.filter {
        it.presentation == GameMenuOptionPresentation.COMPATIBLE_ACTION
    }
    val primaryFocusRequesters = remember(primaryModes.size, initialFocusRequester) {
        List(primaryModes.size) { index ->
            if (index == 0) initialFocusRequester else FocusRequester()
        }
    }
    val compatibleFocusRequesters = remember(compatibleActions.size) {
        List(compatibleActions.size) { FocusRequester() }
    }
    val focusRequesters = primaryFocusRequesters + compatibleFocusRequesters

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(GameMenuDimens.section)
    ) {
        GameMenuScrollablePane(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .testTag("touchModePrimaryPane")
        ) {
            Text(
                text = stringResource(R.string.game_menu_touch_mode_primary_group),
                color = colorResource(R.color.game_menu_text_secondary),
                fontSize = 11.sp
            )
            Column(
                modifier = Modifier.selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(GameMenuDimens.compact)
            ) {
                primaryModes.forEachIndexed { globalIndex, option ->
                    val targets = touchModeFocusTargets(
                        primaryModes.size,
                        compatibleActions.size,
                        globalIndex
                    )
                    TouchModeChoice(
                        option = option,
                        onClick = { callbacks.onOptionClick(option) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("touchModeCell$globalIndex")
                            .touchModeFocusNavigation(
                                targets,
                                focusRequesters,
                                topFocusRequester
                            )
                            .focusRequester(focusRequesters[globalIndex])
                    )
                }
            }
        }

        GameMenuScrollablePane(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .testTag("touchModeCompatiblePane")
        ) {
            primaryModes.firstOrNull(GameMenu.MenuOption::selected)?.let { selected ->
                TouchModePreview(selected)
                Spacer(Modifier.height(GameMenuDimens.compact))
            }
            Text(
                text = stringResource(R.string.game_menu_touch_mode_compatible_group),
                color = colorResource(R.color.game_menu_text_secondary),
                fontSize = 11.sp
            )
            Spacer(Modifier.height(GameMenuDimens.compact))
            compatibleActions.forEachIndexed { compatibleIndex, option ->
                val globalIndex = primaryModes.size + compatibleIndex
                val targets = touchModeFocusTargets(
                    primaryModes.size,
                    compatibleActions.size,
                    globalIndex
                )
                MenuOptionColumn(
                    options = listOf(option),
                    iconForOption = callbacks.iconForOption,
                    onOptionClick = callbacks.onOptionClick,
                    onInlineToggle = callbacks.onInlineToggle,
                    onSegmentClick = callbacks.onSegmentClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = GameMenuDimens.compact)
                        .testTag("touchModeCell$globalIndex"),
                    initialFocusRequester = focusRequesters[globalIndex],
                    optionFocusModifier = Modifier.touchModeFocusNavigation(
                        targets,
                        focusRequesters,
                        topFocusRequester
                    ),
                    inlineControlsFocusable = false
                )
            }
        }
    }
}

@Composable
private fun GameMenuScrollablePane(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val scrollState = rememberScrollState()
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    Box(
        modifier = modifier.onSizeChanged { viewportHeightPx = it.height }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = GameMenuDimens.section)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(GameMenuDimens.compact)
        ) {
            content()
        }
        GameMenuVerticalScrollbar(
            scrollState = scrollState,
            viewportHeightPx = viewportHeightPx,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(3.dp)
        )
    }
}

@Composable
private fun GameMenuVerticalScrollbar(
    scrollState: ScrollState,
    viewportHeightPx: Int,
    modifier: Modifier = Modifier
) {
    val trackColor = colorResource(R.color.game_menu_list_item_border)
    val thumbColor = colorResource(R.color.game_menu_accent)
    Canvas(modifier) {
        if (viewportHeightPx <= 0 || scrollState.maxValue <= 0) return@Canvas
        val contentHeightPx = viewportHeightPx + scrollState.maxValue
        val thumbHeight = (size.height * viewportHeightPx / contentHeightPx)
            .coerceAtLeast(20.dp.toPx())
            .coerceAtMost(size.height)
        val thumbTop = (size.height - thumbHeight) *
            (scrollState.value.toFloat() / scrollState.maxValue)
        val radius = size.width / 2f
        drawRoundRect(
            color = trackColor.copy(alpha = 0.35f),
            cornerRadius = CornerRadius(radius, radius)
        )
        drawRoundRect(
            color = thumbColor.copy(alpha = 0.82f),
            topLeft = Offset(0f, thumbTop),
            size = Size(size.width, thumbHeight),
            cornerRadius = CornerRadius(radius, radius)
        )
    }
}

@Composable
private fun TouchModePreview(option: GameMenu.MenuOption) {
    Surface(
        color = colorResource(R.color.game_menu_card_background),
        shape = GameMenuCardShape,
        border = BorderStroke(
            GameMenuDimens.surfaceStroke,
            colorResource(R.color.game_menu_list_item_border)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(GameMenuDimens.outer),
            verticalArrangement = Arrangement.spacedBy(GameMenuDimens.tight)
        ) {
            Text(
                text = stringResource(R.string.game_menu_touch_mode_preview_title),
                color = colorResource(R.color.game_menu_text_secondary),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = option.label,
                color = colorResource(R.color.game_menu_accent),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            option.subtitle?.takeIf(String::isNotBlank)?.let { subtitle ->
                Text(
                    text = subtitle,
                    color = colorResource(R.color.game_menu_text_secondary),
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
private fun TouchModeChoice(
    option: GameMenu.MenuOption,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hapticFeedback = LocalGameMenuHapticFeedback.current
    val accent = colorResource(R.color.game_menu_accent)
    val shape = GameMenuCardShape
    Column(
        modifier = modifier
            .heightIn(min = 70.dp)
            .clip(shape)
            .background(
                if (option.selected) accent.copy(alpha = 0.12f)
                else colorResource(R.color.game_menu_list_item_normal)
            )
            .border(
                GameMenuDimens.surfaceStroke,
                if (option.selected) accent.copy(alpha = 0.70f)
                else colorResource(R.color.game_menu_list_item_border),
                shape
            )
            .gamepadFocusOutline(shape)
            .selectable(
                selected = option.selected,
                role = Role.RadioButton,
                onClick = {
                    hapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onClick()
                }
            )
            .padding(GameMenuDimens.outer),
        verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = option.label,
                color = if (option.selected) {
                    accent
                } else {
                    colorResource(R.color.game_menu_text_primary)
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (option.selected) {
                Spacer(Modifier.width(GameMenuDimens.tight))
                Text(
                    text = "✓",
                    color = accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clearAndSetSemantics { }
                )
            }
        }
        option.subtitle?.takeIf(String::isNotBlank)?.let { subtitle ->
            Spacer(Modifier.height(GameMenuDimens.tight))
            Text(
                text = subtitle,
                color = colorResource(R.color.game_menu_text_secondary),
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun rememberGameMenuMaxHeight(): Dp {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val view = LocalView.current
    var availableHeightPx by remember(configuration.orientation) {
        mutableStateOf(currentWindowContentHeightPx(context, view, configuration.orientation))
    }

    LaunchedEffect(configuration.orientation, view) {
        view.post {
            val laidOutHeight = view.rootView.height
            if (laidOutHeight > 0 &&
                availableHeightPx > laidOutHeight * ORIENTATION_MISMATCH_THRESHOLD
            ) {
                availableHeightPx = laidOutHeight
            }
        }
    }

    return with(LocalDensity.current) {
        (availableHeightPx * GAME_MENU_MAX_HEIGHT_FRACTION).toDp()
    }
}

private fun Modifier.gameMenuFabricBackground(
    baseColor: Color,
    darkTheme: Boolean,
    textureEnabled: Boolean
): Modifier = if (textureEnabled) {
    gameMenuFabricTexture(baseColor, darkTheme)
} else {
    background(baseColor)
}

private fun Modifier.gameMenuFabricTexture(
    baseColor: Color,
    darkTheme: Boolean
): Modifier = drawWithCache {
    val weaveSpacing = GameMenuFabricSpec.spacing.toPx()
    val weaveStroke = GameMenuFabricSpec.strokeWidth.toPx()
    val sheen = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = if (darkTheme) 0.018f else 0.06f),
            Color.Transparent,
            Color.Black.copy(alpha = if (darkTheme) 0.032f else 0.014f)
        ),
        start = Offset.Zero,
        end = Offset(size.width, size.height)
    )
    val lightThread = Color.White.copy(alpha = if (darkTheme) 0.012f else 0.028f)
    val shadowThread = Color.Black.copy(alpha = if (darkTheme) 0.018f else 0.010f)
    val crossThread = Color.White.copy(alpha = if (darkTheme) 0.008f else 0.016f)
    val lightThreadPath = Path()
    val shadowThreadPath = Path()
    val crossThreadPath = Path()
    val threadStyle = Stroke(width = weaveStroke)

    var threadX = -size.height
    while (threadX < size.width) {
        lightThreadPath.moveTo(threadX, 0f)
        lightThreadPath.lineTo(threadX + size.height, size.height)

        val shadowX = threadX + weaveSpacing * GameMenuFabricSpec.SHADOW_OFFSET_FRACTION
        shadowThreadPath.moveTo(shadowX, 0f)
        shadowThreadPath.lineTo(shadowX + size.height, size.height)
        threadX += weaveSpacing
    }

    threadX = 0f
    while (threadX < size.width + size.height) {
        crossThreadPath.moveTo(threadX, 0f)
        crossThreadPath.lineTo(threadX - size.height, size.height)
        threadX += weaveSpacing
    }

    onDrawBehind {
        drawRect(baseColor)
        drawRect(sheen)
        drawPath(lightThreadPath, lightThread, style = threadStyle)
        drawPath(shadowThreadPath, shadowThread, style = threadStyle)
        drawPath(crossThreadPath, crossThread, style = threadStyle)
    }
}

private fun currentWindowContentHeightPx(
    context: Context,
    view: View,
    orientation: Int
): Int {
    @Suppress("DEPRECATION")
    val display = view.display
        ?: (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
    val displayHeight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val mode = display.mode
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            mode.physicalWidth
        } else {
            mode.physicalHeight
        }
    } else {
        val size = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(size)
        size.y
    }
    val insets = ViewCompat.getRootWindowInsets(view)
        ?.getInsets(WindowInsetsCompat.Type.systemBars())
    return (displayHeight - (insets?.top ?: 0) - (insets?.bottom ?: 0)).coerceAtLeast(1)
}

@Composable
private fun GameMenuHeader(
    state: GameMenuComposeUiState,
    callbacks: GameMenuCallbacks,
    crownGuideModifier: Modifier = Modifier,
    backFocusRequester: FocusRequester? = null,
    backDownFocusRequester: FocusRequester? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (state.isSubmenu) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back_24),
                contentDescription = stringResource(R.string.addpc_back),
                tint = colorResource(R.color.game_menu_text_primary),
                modifier = Modifier
                    .then(
                        if (backFocusRequester != null) {
                            Modifier
                                .testTag("touchModeBack")
                                .focusRequester(backFocusRequester)
                                .focusProperties {
                                    up = FocusRequester.Cancel
                                    down = backDownFocusRequester ?: FocusRequester.Cancel
                                    left = FocusRequester.Cancel
                                    right = FocusRequester.Cancel
                                }
                        } else {
                            Modifier
                        }
                    )
                    .size(36.dp)
                    .clip(CircleShape)
                    .gamepadFocusOutline(CircleShape)
                    .clickable(onClick = callbacks.onBack)
                    .padding(8.dp)
            )
            Spacer(Modifier.width(GameMenuDimens.tight))
        }
        Text(
            text = state.title,
            color = colorResource(R.color.game_menu_text_primary),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (!state.isSubmenu) {
            val settingsShape = CircleShape
            Icon(
                painter = painterResource(R.drawable.ic_ui_settings),
                contentDescription = stringResource(R.string.game_menu_card_config_title),
                tint = colorResource(R.color.game_menu_text_primary),
                modifier = Modifier
                    .size(36.dp)
                    .clip(settingsShape)
                    .background(colorResource(R.color.game_menu_card_background))
                    .border(
                        GameMenuDimens.surfaceStroke,
                        colorResource(R.color.game_menu_button_border),
                        settingsShape
                    )
                    .gamepadFocusOutline(settingsShape)
                    .clickable(onClick = callbacks.onEditCards)
                    .padding(8.dp)
            )
            Spacer(Modifier.width(GameMenuDimens.tight))
            state.deviceQuickOptions.forEach { option ->
                HeaderDeviceQuickAction(
                    option = option,
                    iconRes = callbacks.iconForOption(option.iconKey),
                    onToggle = callbacks.onInlineToggle
                )
                Spacer(Modifier.width(GameMenuDimens.tight))
            }
            val opacityShape = CircleShape
            Icon(
                painter = painterResource(R.drawable.ic_opacity),
                contentDescription = stringResource(
                    R.string.game_menu_opacity_button,
                    state.gameMenuOpacity
                ),
                tint = colorResource(R.color.game_menu_text_primary),
                modifier = Modifier
                    .testTag("gameMenuOpacity")
                    .size(36.dp)
                    .clip(opacityShape)
                    .background(colorResource(R.color.game_menu_card_background))
                    .border(
                        GameMenuDimens.surfaceStroke,
                        colorResource(R.color.game_menu_button_border),
                        opacityShape
                    )
                    .gamepadFocusOutline(opacityShape)
                    .clickable(onClick = callbacks.onEditOpacity)
                    .padding(8.dp)
            )
            Spacer(Modifier.width(GameMenuDimens.tight))
            val crownShape = CircleShape
            Icon(
                painter = painterResource(R.drawable.ic_super_crown),
                contentDescription = state.crownToggleText,
                tint = Color.Unspecified,
                modifier = Modifier
                    .then(crownGuideModifier)
                    .size(36.dp)
                    .clip(crownShape)
                    .background(colorResource(R.color.game_menu_accent).copy(alpha = 0.10f))
                    .border(
                        GameMenuDimens.surfaceStroke,
                        colorResource(R.color.game_menu_accent).copy(alpha = 0.20f),
                        crownShape
                    )
                    .gamepadFocusOutline(crownShape)
                    .clickable(onClick = callbacks.onCrownToggle)
                    .padding(7.dp)
            )
        }
    }
}

@Composable
private fun HeaderDeviceQuickAction(
    option: GameMenu.MenuOption,
    @DrawableRes iconRes: Int,
    onToggle: (GameMenu.InlineControl.Toggle) -> Unit
) {
    val toggle = option.inlineControl as? GameMenu.InlineControl.Toggle ?: return
    val hapticFeedback = LocalGameMenuHapticFeedback.current
    val shape = CircleShape
    val accent = colorResource(R.color.game_menu_accent)
    val stateDescription = stringResource(
        if (toggle.checked) R.string.game_menu_on else R.string.game_menu_off
    )

    Box(
        modifier = Modifier
            .size(36.dp)
            .semantics { contentDescription = "${option.label}, $stateDescription" }
            .clip(shape)
            .background(accent.copy(alpha = if (toggle.checked) 0.18f else 0.06f))
            .border(
                GameMenuDimens.surfaceStroke,
                accent.copy(alpha = if (toggle.checked) 0.52f else 0.18f),
                shape
            )
            .gamepadFocusOutline(shape)
            .toggleable(
                value = toggle.checked,
                role = Role.Switch,
                onValueChange = {
                    hapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onToggle(toggle)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (iconRes != 0) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Text(
                text = firstActionCharacter(option.label),
                color = colorResource(R.color.game_menu_text_primary),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun GameMenuFooter(subtitle: String) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = subtitle,
            color = colorResource(R.color.game_menu_text_secondary),
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
