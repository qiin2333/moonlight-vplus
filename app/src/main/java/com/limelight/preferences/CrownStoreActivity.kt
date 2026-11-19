package com.limelight.preferences

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button as ComposeButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.limelight.ui.ThemedAppCompatActivity
import com.limelight.R
import com.limelight.binding.input.advance_setting.config.PageConfigController
import com.limelight.binding.input.advance_setting.share.CrownProfileShareManager
import com.limelight.binding.input.advance_setting.share.GitHubCrownProfileStorePublisher
import com.limelight.binding.input.advance_setting.sqlite.SuperConfigDatabaseHelper
import com.limelight.ui.theme.AppShapes
import com.limelight.utils.AppDialogStyler
import com.limelight.utils.ConfigurationSyncScheduler
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.DateFormat
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread
import kotlin.math.abs
import com.limelight.utils.UiHelper

class CrownStoreActivity : ThemedAppCompatActivity() {
    private enum class CrownTab {
        STORE,
        MINE
    }

    private enum class StoreSort { STORE_ORDER, NEWEST, OLDEST, NAME_ASC, NAME_DESC }

    private data class LocalCrownProfile(
        val id: String,
        val name: String,
        val isActive: Boolean
    )

    private data class CrownStoreUiState(
        val selectedTab: CrownTab = CrownTab.STORE,
        val selectedStoreProfile: CrownProfileShareManager.StoreProfile? = null,
        val storeProfiles: List<CrownProfileShareManager.StoreProfile>? = null,
        val storeLoading: Boolean = false,
        val storeError: String? = null,
        val localProfiles: List<LocalCrownProfile> = emptyList()
    )

    private data class ProfilePreviewUiState(
        val preview: CrownProfileShareManager.ProfilePreview? = null,
        val loading: Boolean = false,
        val failed: Boolean = false
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val helper: SuperConfigDatabaseHelper by lazy { SuperConfigDatabaseHelper(this) }

    private var selectedTab = CrownTab.STORE
    private var storeProfiles: List<CrownProfileShareManager.StoreProfile>? = null
    private var storeLoading = false
    private var storeError: String? = null
    private var pendingCrownShareExportString = ""
    private var exportConfigString = ""
    private var mergeTargetConfigId: String? = null
    private var pendingCrownShareImport: CrownProfileShareManager.ImportedProfile? = null
    @Volatile
    private var developerUnlockVerificationRunning = false
    private var developerForegroundPollRunning = false
    private var developerLastForegroundPollMs = 0L
    @Volatile
    private var developerPendingDeviceCode: GitHubStarVerifier.DeviceCode? = null
    private var developerDeviceCodeDialog: AlertDialog? = null
    private val composeUiState = mutableStateOf(CrownStoreUiState())
    private val profilePreviewStates = mutableStateOf<Map<String, ProfilePreviewUiState>>(emptyMap())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val openImportPackage = intent.getBooleanExtra(EXTRA_OPEN_IMPORT_PACKAGE, false)
        val openMyCrowns = intent.getBooleanExtra(EXTRA_OPEN_MY_CROWNS, false)
        setContent {
            val dark = isSystemInDarkTheme()
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            CrownStoreScreen(composeUiState.value)
        }
        selectTab(if (openMyCrowns) CrownTab.MINE else CrownTab.STORE)
        if (openImportPackage) {
            mainHandler.post { openCrownShareDocument() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (selectedTab == CrownTab.MINE) {
            renderMineTab()
        }
    }

    private fun selectTab(tab: CrownTab) {
        selectedTab = tab
        composeUiState.value = composeUiState.value.copy(
            selectedTab = tab,
            selectedStoreProfile = null
        )
        when (tab) {
            CrownTab.STORE -> {
                if (storeProfiles == null && !storeLoading && storeError == null) {
                    loadStoreProfiles()
                } else {
                    renderStoreTab()
                }
            }
            CrownTab.MINE -> renderMineTab()
        }
    }

    private fun renderStoreTab() {
        composeUiState.value = composeUiState.value.copy(
            selectedTab = CrownTab.STORE,
            storeProfiles = storeProfiles,
            storeLoading = storeLoading,
            storeError = storeError
        )
    }

    private fun loadStoreProfiles(force: Boolean = false) {
        if (storeLoading) return
        if (!force && storeProfiles != null) {
            renderStoreTab()
            return
        }

        storeLoading = true
        storeError = null
        renderStoreTab()
        thread(name = "CrownStoreIndex") {
            val result = runCatching {
                val indexText = downloadRemoteText(CROWN_STORE_INDEX_URL, CROWN_STORE_MAX_INDEX_BYTES)
                CrownProfileShareManager.parseStoreIndex(indexText)
            }
            mainHandler.post {
                storeLoading = false
                result
                    .onSuccess {
                        storeProfiles = it
                        storeError = null
                    }
                    .onFailure {
                        Log.e("CrownStore", "Failed to load Crown profile store", it)
                        storeProfiles = null
                        storeError = it.message ?: it.javaClass.simpleName
                        Toast.makeText(this, R.string.toast_crown_store_failed, Toast.LENGTH_LONG).show()
                    }
                if (selectedTab == CrownTab.STORE) {
                    renderStoreTab()
                }
            }
        }
    }

    private fun profilePreviewKey(profile: CrownProfileShareManager.StoreProfile): String {
        return profile.bundleId.ifBlank { profile.url }
    }

    private fun ensureStoreProfilePreview(profile: CrownProfileShareManager.StoreProfile) {
        val key = profilePreviewKey(profile)
        if (profilePreviewStates.value.containsKey(key)) return

        profilePreviewStates.value = profilePreviewStates.value +
            (key to ProfilePreviewUiState(loading = true))
        thread(name = "CrownProfilePreview") {
            val result = runCatching {
                val profileUrl = CrownProfileShareManager.resolveStoreProfileUrl(
                    CROWN_STORE_INDEX_URL,
                    profile.url
                )
                val bundle = downloadRemoteText(profileUrl, CROWN_SHARE_MAX_DOWNLOAD_BYTES)
                val imported = CrownProfileShareManager.parseImportText(bundle)
                CrownProfileShareManager.parsePreview(imported.payload, imported.layoutBasis)
            }
            mainHandler.post {
                result
                    .onSuccess { preview ->
                        profilePreviewStates.value = profilePreviewStates.value +
                            (key to ProfilePreviewUiState(preview = preview))
                    }
                    .onFailure { error ->
                        Log.w("CrownStore", "Failed to load profile thumbnail: $key", error)
                        profilePreviewStates.value = profilePreviewStates.value +
                            (key to ProfilePreviewUiState(failed = true))
                    }
            }
        }
    }

    private fun localProfilePreviewKey(profile: LocalCrownProfile): String {
        return "local:${profile.id}"
    }

    private fun ensureLocalProfilePreview(profile: LocalCrownProfile) {
        val key = localProfilePreviewKey(profile)
        if (profilePreviewStates.value.containsKey(key)) return

        profilePreviewStates.value = profilePreviewStates.value +
            (key to ProfilePreviewUiState(loading = true))
        thread(name = "LocalCrownProfilePreview") {
            val result = runCatching {
                val payload = helper.exportConfig(profile.id.toLong())
                CrownProfileShareManager.parsePreview(payload, currentLayoutBasis())
            }
            mainHandler.post {
                result
                    .onSuccess { preview ->
                        profilePreviewStates.value = profilePreviewStates.value +
                            (key to ProfilePreviewUiState(preview = preview))
                    }
                    .onFailure { error ->
                        Log.w("CrownStore", "Failed to load local profile thumbnail: $key", error)
                        profilePreviewStates.value = profilePreviewStates.value +
                            (key to ProfilePreviewUiState(failed = true))
                    }
            }
        }
    }

    private fun importStoreProfile(profile: CrownProfileShareManager.StoreProfile) {
        if (!isLayoutBasisCompatible(profile.layoutBasis, currentLayoutBasis())) {
            showStoreLayoutCompatibilityDialog(profile) {
                importStoreProfileUnchecked(profile)
            }
            return
        }
        importStoreProfileUnchecked(profile)
    }

    private fun importStoreProfileUnchecked(profile: CrownProfileShareManager.StoreProfile) {
        val profileUrl = try {
            CrownProfileShareManager.resolveStoreProfileUrl(CROWN_STORE_INDEX_URL, profile.url)
        } catch (e: Exception) {
            Log.e("CrownStore", "Invalid Crown store profile URL", e)
            Toast.makeText(this, R.string.toast_crown_store_profile_failed, Toast.LENGTH_LONG).show()
            return
        }
        importCrownShareFromUrl(
            profileUrl,
            sourceLabelOverride = getString(R.string.crown_store_source_label, profile.name),
            failureToastRes = R.string.toast_crown_store_profile_failed
        )
    }

    private fun showStoreLayoutCompatibilityDialog(
        profile: CrownProfileShareManager.StoreProfile,
        onContinue: () -> Unit
    ) {
        AlertDialog.Builder(this, R.style.AppDialogStyle)
            .setTitle(R.string.title_crown_store_layout_check)
            .setMessage(layoutCompatibilityMessage(profile.layoutBasis))
            .setPositiveButton(R.string.action_crown_store_continue_import) { _, _ -> onContinue() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .also { AppDialogStyler.installDismissKeys(it) }
    }

    private fun reportStoreProfile(profile: CrownProfileShareManager.StoreProfile) {
        val profileUrl = runCatching {
            CrownProfileShareManager.resolveStoreProfileUrl(CROWN_STORE_INDEX_URL, profile.url)
        }.getOrDefault(profile.url)
        val title = getString(R.string.crown_store_report_issue_title, profile.name)
        val body = getString(R.string.crown_store_report_issue_body, profile.name, profileUrl)
        openUrl(
            "${CROWN_STORE_REPORT_URL}?template=crown_store_report.md" +
                "&title=${urlParam(title)}&body=${urlParam(body)}"
        )
    }

    private fun openStoreProfileDetail(profile: CrownProfileShareManager.StoreProfile) {
        composeUiState.value = composeUiState.value.copy(selectedStoreProfile = profile)
    }

    private fun closeStoreProfileDetail() {
        composeUiState.value = composeUiState.value.copy(selectedStoreProfile = null)
    }

    private fun renderMineTab() {
        val profiles = loadLocalProfiles()
        composeUiState.value = composeUiState.value.copy(
            selectedTab = CrownTab.MINE,
            selectedStoreProfile = null,
            localProfiles = profiles
        )
    }

    @Composable
    private fun CrownStoreScreen(state: CrownStoreUiState) {
        val selectedProfile = state.selectedStoreProfile
        val storeGridState = rememberLazyStaggeredGridState()
        var storeQuery by rememberSaveable { mutableStateOf("") }
        var storeSort by rememberSaveable { mutableStateOf(StoreSort.STORE_ORDER) }
        var filterInitialized by remember { mutableStateOf(false) }
        LaunchedEffect(storeQuery, storeSort) {
            if (filterInitialized) storeGridState.scrollToItem(0)
            filterInitialized = true
        }
        BackHandler(enabled = selectedProfile != null) {
            closeStoreProfileDetail()
        }
        CrownTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Scaffold(
                    containerColor = Color.Transparent,
                    topBar = { CrownStoreTopBar(state) },
                    bottomBar = {
                        if (state.selectedStoreProfile == null) {
                            CrownStoreBottomBar(state.selectedTab)
                        } else {
                            CrownStoreDetailBottomBar(state.selectedStoreProfile)
                        }
                    }
                ) { innerPadding ->
                    if (selectedProfile != null) {
                        CrownStoreProfileDetail(
                            profile = selectedProfile,
                            modifier = Modifier.padding(innerPadding)
                        )
                    } else {
                        when (state.selectedTab) {
                            CrownTab.STORE -> CrownStoreTabContent(
                                state = state,
                                gridState = storeGridState,
                                query = storeQuery,
                                onQueryChange = { storeQuery = it },
                                sort = storeSort,
                                onSortChange = { storeSort = it },
                                modifier = Modifier.padding(innerPadding)
                            )
                            CrownTab.MINE -> Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                                    .verticalScroll(rememberScrollState())
                                    .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 96.dp)
                            ) {
                                CrownMineTabContent(state.localProfiles)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CrownStoreTopBar(state: CrownStoreUiState) {
        val selectedProfile = state.selectedStoreProfile
        val title = selectedProfile?.name ?: when (state.selectedTab) {
            CrownTab.STORE -> stringResource(R.string.crown_store_tab_store)
            CrownTab.MINE -> stringResource(R.string.crown_store_tab_mine)
        }
        val textColor = MaterialTheme.colorScheme.onSurface
        var actionsExpanded by remember(selectedProfile, state.selectedTab) { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (selectedProfile != null) {
                    closeStoreProfileDetail()
                } else {
                    finish()
                }
            }) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_right),
                    contentDescription = stringResource(R.string.crown_store_action_back),
                    modifier = Modifier.rotate(180f),
                    tint = textColor
                )
            }
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                color = textColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Box {
                IconButton(
                    onClick = { actionsExpanded = true },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert),
                        contentDescription = stringResource(R.string.pcview_menu_more_actions),
                        tint = textColor
                    )
                }
                DropdownMenu(
                    expanded = actionsExpanded,
                    onDismissRequest = { actionsExpanded = false }
                ) {
                    if (selectedProfile == null && state.selectedTab == CrownTab.STORE) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.crown_store_action_refresh)) },
                            leadingIcon = {
                                Icon(painterResource(R.drawable.phc_action_reset), contentDescription = null)
                            },
                            onClick = {
                                actionsExpanded = false
                                loadStoreProfiles(force = true)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.crown_share_action_import_url)) },
                            leadingIcon = {
                                Icon(painterResource(R.drawable.phc_plug), contentDescription = null)
                            },
                            onClick = {
                                actionsExpanded = false
                                showCrownShareUrlImportDialog()
                            }
                        )
                    } else if (selectedProfile != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.crown_store_action_report_profile)) },
                            leadingIcon = {
                                Icon(painterResource(R.drawable.phc_info), contentDescription = null)
                            },
                            onClick = {
                                actionsExpanded = false
                                reportStoreProfile(selectedProfile)
                            }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun CrownStoreDetailBottomBar(profile: CrownProfileShareManager.StoreProfile) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                IconButton(
                    onClick = { reportStoreProfile(profile) },
                    modifier = Modifier
                        .size(44.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, AppShapes.medium)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.phc_info),
                        contentDescription = stringResource(R.string.crown_store_action_report_profile),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                CrownActionButton(
                    text = stringResource(R.string.crown_store_action_import_profile),
                    iconRes = R.drawable.ic_add,
                    primary = true,
                    modifier = Modifier.weight(1f)
                ) {
                    importStoreProfile(profile)
                }
            }
        }
    }

    @Composable
    private fun CrownStoreBottomBar(selectedTab: CrownTab) {
        val container = MaterialTheme.colorScheme.surface
        val selected = MaterialTheme.colorScheme.primary
        val unselected = MaterialTheme.colorScheme.onSurfaceVariant
        NavigationBar(
            containerColor = container,
            tonalElevation = 0.dp,
            windowInsets = WindowInsets(0, 0, 0, 0)
        ) {
            NavigationBarItem(
                selected = selectedTab == CrownTab.STORE,
                onClick = { selectTab(CrownTab.STORE) },
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.phc_crown),
                        contentDescription = null
                    )
                },
                label = { Text(stringResource(R.string.crown_store_tab_store)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = selected,
                    selectedTextColor = selected,
                    unselectedIconColor = unselected,
                    unselectedTextColor = unselected,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
            NavigationBarItem(
                selected = selectedTab == CrownTab.MINE,
                onClick = { selectTab(CrownTab.MINE) },
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.phc_list),
                        contentDescription = null
                    )
                },
                label = { Text(stringResource(R.string.crown_store_tab_mine)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = selected,
                    selectedTextColor = selected,
                    unselectedIconColor = unselected,
                    unselectedTextColor = unselected,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun CrownStoreTabContent(
        state: CrownStoreUiState,
        gridState: LazyStaggeredGridState,
        query: String,
        onQueryChange: (String) -> Unit,
        sort: StoreSort,
        onSortChange: (StoreSort) -> Unit,
        modifier: Modifier = Modifier
    ) {
        val profiles = state.storeProfiles
        val visibleProfiles = remember(profiles, query, sort) {
            val terms = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
            val matches = if (terms.isEmpty()) profiles.orEmpty() else profiles.orEmpty().filter { profile ->
                val searchable = (listOf(profile.name, profile.summary, profile.author, profile.game) + profile.tags)
                    .joinToString(" ").lowercase(Locale.ROOT)
                terms.all(searchable::contains)
            }
            when (sort) {
                StoreSort.STORE_ORDER -> matches
                StoreSort.NEWEST, StoreSort.OLDEST -> {
                    val dated = matches.map { it to parseStoreUpdatedAt(it.updatedAt)?.time }
                    val sorted = if (sort == StoreSort.NEWEST) {
                        dated.sortedByDescending { it.second ?: Long.MIN_VALUE }
                    } else {
                        dated.sortedBy { it.second ?: Long.MAX_VALUE }
                    }
                    sorted.map { it.first }
                }
                StoreSort.NAME_ASC -> matches.sortedBy { it.name.lowercase(Locale.ROOT) }
                StoreSort.NAME_DESC -> matches.sortedByDescending { it.name.lowercase(Locale.ROOT) }
            }
        }
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(2),
            state = gridState,
            modifier = modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalItemSpacing = 10.dp,
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 10.dp,
                end = 16.dp,
                bottom = 96.dp
            )
        ) {
            item(span = StaggeredGridItemSpan.FullLine) {
                Column {
                    var sortMenuExpanded by remember { mutableStateOf(false) }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val searchHint = stringResource(R.string.crown_store_search_hint)
                        val searchTextColor = MaterialTheme.colorScheme.onSurface
                        val searchHintColor = MaterialTheme.colorScheme.onSurfaceVariant
                        BasicTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            singleLine = true,
                            textStyle = TextStyle(color = searchTextColor, fontSize = 13.sp, lineHeight = 20.sp),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .border(1.dp, searchHintColor, AppShapes.medium)
                                .padding(horizontal = 12.dp),
                            decorationBox = { innerTextField ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        painterResource(R.drawable.ic_search_stylish),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                        if (query.isEmpty()) {
                                            Text(searchHint, color = searchHintColor, fontSize = 13.sp)
                                        }
                                        innerTextField()
                                    }
                                    if (query.isNotEmpty()) {
                                        IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(40.dp)) {
                                            Icon(
                                                painterResource(R.drawable.ic_close_stylish),
                                                contentDescription = stringResource(R.string.crown_store_clear_search),
                                                tint = searchHintColor
                                            )
                                        }
                                    }
                                }
                            }
                        )
                        Box {
                            IconButton(
                                onClick = { sortMenuExpanded = true },
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, AppShapes.medium)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_crown_sort),
                                    contentDescription = stringResource(R.string.crown_store_sort_by, storeSortLabel(sort)),
                                    tint = if (sort == StoreSort.STORE_ORDER) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else MaterialTheme.colorScheme.primary
                                )
                            }
                            DropdownMenu(
                                expanded = sortMenuExpanded,
                                onDismissRequest = { sortMenuExpanded = false }
                            ) {
                                StoreSort.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(storeSortLabel(option)) },
                                        onClick = {
                                            onSortChange(option)
                                            sortMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CrownFilterChip(
                            text = stringResource(R.string.crown_store_filter_all),
                            selected = sort == StoreSort.STORE_ORDER,
                            onClick = { onSortChange(StoreSort.STORE_ORDER) }
                        )
                        CrownFilterChip(
                            text = stringResource(R.string.crown_store_filter_recent),
                            selected = sort == StoreSort.NEWEST,
                            onClick = { onSortChange(StoreSort.NEWEST) }
                        )
                        CrownFilterChip(
                            text = stringResource(R.string.crown_store_filter_name),
                            selected = sort == StoreSort.NAME_ASC,
                            onClick = { onSortChange(StoreSort.NAME_ASC) }
                        )
                    }
                    if (profiles != null && !state.storeLoading && state.storeError == null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        CrownFootnote(stringResource(R.string.crown_store_results_count, visibleProfiles.size))
                    }
                }
            }
            when {
                state.storeLoading -> item(span = StaggeredGridItemSpan.FullLine) {
                    CrownLoadingState(stringResource(R.string.toast_crown_store_loading))
                }
                state.storeError != null -> item(span = StaggeredGridItemSpan.FullLine) {
                    CrownStateCard(
                        title = stringResource(R.string.toast_crown_store_failed),
                        message = state.storeError,
                        buttonText = stringResource(R.string.crown_store_action_refresh)
                    ) {
                        loadStoreProfiles(force = true)
                    }
                }
                profiles == null -> item(span = StaggeredGridItemSpan.FullLine) {
                    CrownStateCard(
                        title = stringResource(R.string.crown_store_empty_state_title),
                        message = stringResource(R.string.crown_store_empty_state_message),
                        buttonText = stringResource(R.string.crown_store_action_refresh)
                    ) {
                        loadStoreProfiles(force = true)
                    }
                }
                profiles.isEmpty() -> item(span = StaggeredGridItemSpan.FullLine) {
                    CrownStateCard(
                        title = stringResource(R.string.toast_crown_store_empty),
                        message = stringResource(R.string.crown_store_empty_state_message),
                        buttonText = stringResource(R.string.crown_store_action_refresh)
                    ) {
                        loadStoreProfiles(force = true)
                    }
                }
                visibleProfiles.isEmpty() -> item(span = StaggeredGridItemSpan.FullLine) {
                    CrownStateCard(
                        title = stringResource(R.string.crown_store_no_search_results),
                        message = stringResource(R.string.crown_store_try_another_search)
                    )
                }
                else -> {
                    val featuredProfile = visibleProfiles.first()
                    item(
                        key = featuredProfile.url.ifBlank {
                            "${featuredProfile.name}:${featuredProfile.author}:featured"
                        },
                        span = StaggeredGridItemSpan.FullLine
                    ) {
                        CrownFeaturedStoreProfileCard(
                            profile = featuredProfile,
                            modifier = Modifier.clickable { openStoreProfileDetail(featuredProfile) }
                        )
                    }
                    if (visibleProfiles.size > 1) {
                        items(
                            items = visibleProfiles.drop(1),
                            key = { profile -> profile.url.ifBlank { "${profile.name}:${profile.author}" } },
                            span = { StaggeredGridItemSpan.SingleLane }
                        ) { profile ->
                            CrownStoreProfileCard(
                                profile = profile,
                                modifier = Modifier.clickable { openStoreProfileDetail(profile) }
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun storeSortLabel(sort: StoreSort): String = stringResource(when (sort) {
        StoreSort.STORE_ORDER -> R.string.crown_store_sort_store_order
        StoreSort.NEWEST -> R.string.crown_store_sort_newest
        StoreSort.OLDEST -> R.string.crown_store_sort_oldest
        StoreSort.NAME_ASC -> R.string.crown_store_sort_name_asc
        StoreSort.NAME_DESC -> R.string.crown_store_sort_name_desc
    })

    @Composable
    private fun CrownMineTabContent(profiles: List<LocalCrownProfile>) {
        val currentProfile = profiles.firstOrNull { it.isActive } ?: profiles.firstOrNull()
        if (currentProfile != null) {
            CrownMineCurrentProfileCard(currentProfile)
        } else {
            CrownMineHero(profiles.size)
        }
        Spacer(modifier = Modifier.height(18.dp))
        CrownSectionHeader(
            title = stringResource(R.string.crown_store_local_profiles),
            iconRes = R.drawable.phc_list,
            countText = stringResource(R.string.crown_store_profile_count, profiles.size)
        )
        Spacer(modifier = Modifier.height(10.dp))
        if (profiles.isEmpty()) {
            CrownStateCard(
                title = stringResource(R.string.crown_config_no_profiles),
                message = stringResource(R.string.crown_store_my_empty_message)
            )
        } else {
            CrownLocalProfilesList(profiles)
        }
        Spacer(modifier = Modifier.height(18.dp))
        CrownSectionHeader(
            title = stringResource(R.string.crown_store_local_tools),
            iconRes = R.drawable.phc_settings
        )
        Spacer(modifier = Modifier.height(10.dp))
        CrownLegacyToolsCard()
    }

    @Composable
    private fun CrownMineCurrentProfileCard(profile: LocalCrownProfile) {
        val previewKey = localProfilePreviewKey(profile)
        val previewState = profilePreviewStates.value[previewKey]
        LaunchedEffect(previewKey) {
            ensureLocalProfilePreview(profile)
        }
        CrownProfileCard(contentPadding = PaddingValues(0.dp)) {
            Box(modifier = Modifier.fillMaxWidth()) {
                CrownLayoutPreview(
                    currentLayoutBasis(),
                    previewState,
                    height = 128.dp
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))
                            ),
                            AppShapes.large
                        )
                )
                if (profile.isActive) {
                    CrownStatusChip(
                        text = stringResource(R.string.crown_store_profile_active),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp)
                    )
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = profile.name,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = previewState?.preview?.elements?.size?.takeIf { it > 0 }
                                ?.let { getString(R.string.crown_store_detail_element_count, it) }
                                ?: getString(R.string.crown_store_local_profile_id, profile.id),
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 9.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun CrownMineHero(profileCount: Int) {
        CrownProfileCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.phc_crown),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.crown_store_my_workspace_title),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    CrownBodyText(stringResource(R.string.crown_store_my_summary), maxLines = 2)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = AppShapes.medium
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = profileCount.toString(),
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.crown_store_profile_unit),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 9.5.sp
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CrownActionButton(
                    text = stringResource(R.string.crown_store_action_import_package),
                    iconRes = R.drawable.ic_add,
                    primary = true,
                    compact = true,
                    modifier = Modifier.weight(1f)
                ) {
                    openCrownShareDocument()
                }
                CrownActionButton(
                    text = stringResource(R.string.crown_share_action_import_url),
                    iconRes = R.drawable.phc_plug,
                    compact = true,
                    modifier = Modifier.weight(1f)
                ) {
                    showCrownShareUrlImportDialog()
                }
            }
        }
    }

    @Composable
    private fun CrownSectionHeader(title: String, iconRes: Int, countText: String? = null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (!countText.isNullOrBlank()) {
                Text(
                    text = countText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = CircleShape
                        )
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                )
            }
        }
    }

    @Composable
    private fun CrownLegacyToolsCard() {
        CrownProfileCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.phc_list),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.crown_config_action_import_legacy),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    CrownBodyText(stringResource(R.string.crown_store_legacy_tool_summary), maxLines = 2)
                }
                Spacer(modifier = Modifier.width(8.dp))
                CrownActionButton(
                    text = stringResource(R.string.crown_store_action_open),
                    iconRes = R.drawable.ic_add,
                    compact = true
                ) {
                    openLegacyImportDocument()
                }
            }
        }
    }

    @Composable
    private fun CrownStoreProfileCard(
        profile: CrownProfileShareManager.StoreProfile,
        modifier: Modifier = Modifier
    ) {
        val previewKey = profilePreviewKey(profile)
        val previewState = profilePreviewStates.value[previewKey]
        LaunchedEffect(previewKey) {
            ensureStoreProfilePreview(profile)
        }
        CrownProfileCard(modifier, contentPadding = PaddingValues(12.dp)) {
            // Design P2: preview first so a screen of cards reads as layouts,
            // then scan-level text only - no actions on the card itself.
            CrownLayoutPreview(profile.layoutBasis, previewState, height = 84.dp)
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CrownCardTitle(profile.name, maxLines = 1, modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_right),
                    contentDescription = stringResource(R.string.crown_store_action_open),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            val meta = listOfNotNull(
                profile.game.takeIf { it.isNotBlank() },
                profile.author.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                CrownMetaText(meta, strong = false, maxLines = 1)
            }
            val elementCount = previewState?.preview?.elements?.size?.takeIf { it > 0 }
            val updatedLabel = formatStoreUpdatedAtCompact(profile.updatedAt)
            if (elementCount != null || updatedLabel.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                val footer = listOfNotNull(
                    elementCount?.let {
                        stringResource(R.string.crown_store_detail_element_count, it)
                    },
                    updatedLabel.takeIf { it.isNotBlank() }
                ).joinToString(" · ")
                CrownFootnote(footer)
            }
        }
    }

    @Composable
    private fun CrownFeaturedStoreProfileCard(
        profile: CrownProfileShareManager.StoreProfile,
        modifier: Modifier = Modifier
    ) {
        val previewKey = profilePreviewKey(profile)
        val previewState = profilePreviewStates.value[previewKey]
        LaunchedEffect(previewKey) {
            ensureStoreProfilePreview(profile)
        }
        CrownProfileCard(modifier, contentPadding = PaddingValues(0.dp)) {
            Box(modifier = Modifier.fillMaxWidth()) {
                CrownLayoutPreview(profile.layoutBasis, previewState, height = 150.dp)
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.84f))
                            ),
                            AppShapes.large
                        )
                )
                CrownStatusChip(
                    text = stringResource(R.string.crown_store_featured),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = profile.name,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = listOfNotNull(
                                profile.author.takeIf { it.isNotBlank() }?.let { "by $it" },
                                profile.game.takeIf { it.isNotBlank() },
                                previewState?.preview?.elements?.size?.takeIf { it > 0 }
                                    ?.let { getString(R.string.crown_store_detail_element_count, it) }
                            ).joinToString(" · "),
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_right),
                        contentDescription = stringResource(R.string.crown_store_action_open),
                        tint = Color.White,
                        modifier = Modifier
                            .size(34.dp)
                            .background(Color.White.copy(alpha = 0.16f), AppShapes.medium)
                            .padding(8.dp)
                    )
                }
            }
        }
    }

    @Composable
    private fun CrownStoreProfileDetail(
        profile: CrownProfileShareManager.StoreProfile,
        modifier: Modifier = Modifier
    ) {
        val previewKey = profilePreviewKey(profile)
        val previewState = profilePreviewStates.value[previewKey]
        LaunchedEffect(previewKey) {
            ensureStoreProfilePreview(profile)
        }
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .padding(bottom = 96.dp)
        ) {
            CrownProfileCard {
                Text(
                    text = profile.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                // Design P3: orientation and control count ride on the preview itself.
                Box {
                    CrownLayoutPreview(profile.layoutBasis, previewState, height = 164.dp)
                    Row(
                        modifier = Modifier
                            .padding(10.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        formatLayoutBasisCompact(profile.layoutBasis)?.let { basis ->
                            CrownPreviewOverlayChip(basis)
                        }
                        previewState?.preview?.elements?.size?.takeIf { it > 0 }?.let { count ->
                            CrownPreviewOverlayChip(
                                stringResource(R.string.crown_store_detail_element_count, count)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.phc_crown),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(9.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        CrownMetaText(profile.author.ifBlank { profile.game }, strong = true)
                        CrownFootnote(
                            profile.updatedAt.takeIf { it.isNotBlank() }?.let {
                                stringResource(R.string.crown_store_updated_at, formatStoreUpdatedAt(it))
                            } ?: formatLayoutBasisCompact(profile.layoutBasis).orEmpty()
                        )
                    }
                    CrownStatusChip(stringResource(R.string.crown_store_detail_ready))
                }
                if (profile.summary.isNotBlank()) {
                    Spacer(modifier = Modifier.height(13.dp))
                    CrownBodyText(profile.summary, maxLines = 6)
                }
                if (profile.tags.any { it.isNotBlank() }) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        profile.tags.filter { it.isNotBlank() }.take(5).forEach { tag ->
                            CrownTagChip(tag)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                CrownDetailInfoRow(
                    iconRes = R.drawable.phc_list,
                    title = stringResource(R.string.crown_store_detail_elements),
                    value = previewState?.preview?.elements?.size?.let {
                        stringResource(R.string.crown_store_detail_element_count, it)
                    } ?: stringResource(R.string.crown_store_detail_loading)
                )
                CrownDetailInfoRow(
                    iconRes = R.drawable.phc_info,
                    title = stringResource(R.string.crown_store_detail_compatibility),
                    value = layoutCompatibilityShortLabel(profile.layoutBasis)
                )
                CrownDetailInfoRow(
                    iconRes = R.drawable.phc_action_copy,
                    title = stringResource(R.string.crown_store_detail_share_package),
                    value = stringResource(R.string.crown_store_detail_share_package_value)
                )
            }
        }
    }

    @Composable
    private fun CrownLocalProfilesList(profiles: List<LocalCrownProfile>) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            profiles.forEach { profile ->
                CrownLocalProfileCard(profile)
            }
        }
    }

    @Composable
    private fun CrownLocalProfileCard(
        profile: LocalCrownProfile,
        modifier: Modifier = Modifier
    ) {
        val previewKey = localProfilePreviewKey(profile)
        val previewState = profilePreviewStates.value[previewKey]
        LaunchedEffect(previewKey) {
            ensureLocalProfilePreview(profile)
        }
        var menuExpanded by remember(profile.id) { mutableStateOf(false) }
        CrownProfileCard(modifier, contentPadding = PaddingValues(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CrownLayoutPreview(
                    currentLayoutBasis(),
                    previewState,
                    height = 64.dp,
                    width = 112.dp
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    CrownCardTitle(profile.name, maxLines = 1)
                    Spacer(modifier = Modifier.height(4.dp))
                    CrownMetaText(
                        getString(R.string.crown_store_local_profile_id, profile.id),
                        strong = false,
                        maxLines = 1
                    )
                    if (profile.isActive) {
                        Spacer(modifier = Modifier.height(6.dp))
                        CrownStatusChip(stringResource(R.string.crown_store_profile_active))
                    }
                }
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_more_vert),
                            contentDescription = stringResource(R.string.pcview_menu_more_actions),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (profile.isActive) {
                                        stringResource(R.string.crown_store_profile_active)
                                    } else {
                                        stringResource(R.string.crown_store_action_use_profile)
                                    }
                                )
                            },
                            leadingIcon = {
                                Icon(painterResource(R.drawable.phc_action_check), contentDescription = null)
                            },
                            enabled = !profile.isActive,
                            onClick = {
                                menuExpanded = false
                                PreferenceManager.getDefaultSharedPreferences(this@CrownStoreActivity)
                                    .edit { putLong(CURRENT_CROWN_CONFIG_ID_KEY, profile.id.toLong()) }
                                onLocalProfilesChanged()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.crown_store_action_publish_profile)) },
                            leadingIcon = {
                                Icon(painterResource(R.drawable.phc_action_check), contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                showCrownStorePublishMetadataDialog(profile.id, profile.name)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.crown_store_action_export_share)) },
                            leadingIcon = {
                                Icon(painterResource(R.drawable.phc_action_copy), contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                exportCrownSharePackage(profile.id, profile.name)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.crown_store_action_export_legacy_short)) },
                            leadingIcon = {
                                Icon(painterResource(R.drawable.phc_list), contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                exportLegacyConfig(profile.id, profile.name)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.crown_store_action_merge_short)) },
                            leadingIcon = {
                                Icon(painterResource(R.drawable.phc_plug), contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                openLegacyMergeDocument(profile.id)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_crown_store_delete_profile)) },
                            leadingIcon = {
                                Icon(painterResource(R.drawable.phc_action_trash), contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                showDeleteLocalProfileDialog(profile)
                            }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun CrownLayoutPreview(
        layoutBasis: CrownProfileShareManager.LayoutBasis?,
        state: ProfilePreviewUiState? = null,
        height: androidx.compose.ui.unit.Dp = 76.dp,
        width: androidx.compose.ui.unit.Dp? = null
    ) {
        // Layout previews stay dark in both themes (they mimic the game picture),
        // so these come from fixed resources instead of the theme's roles.
        val previewBackground = colorResource(R.color.crown_preview_bg_top)
        val previewBackgroundEnd = colorResource(R.color.crown_preview_bg_bottom)
        val previewControl = colorResource(R.color.crown_preview_accent)
        val previewMuted = colorResource(R.color.crown_preview_ink).copy(alpha = 0.46f)
        val previewInk = colorResource(R.color.crown_preview_ink)
        val previewPrimary = colorResource(R.color.crown_preview_accent)
        val previewSecondary = colorResource(R.color.crown_preview_secondary)
        val previewTertiary = colorResource(R.color.crown_preview_tertiary)
        val preview = state?.preview?.takeUnless { it.isEmpty }
        Box(
            modifier = (if (width == null) Modifier.fillMaxWidth() else Modifier.width(width))
                .height(height)
                .background(
                    Brush.linearGradient(listOf(previewBackground, previewBackgroundEnd)),
                    AppShapes.medium
                )
                .border(1.dp, Color.White.copy(alpha = 0.12f), AppShapes.medium)
                .padding(8.dp)
        ) {
            if (preview != null) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val guideColor = Color.White.copy(alpha = 0.055f)
                    drawLine(guideColor, androidx.compose.ui.geometry.Offset(size.width * 0.33f, 0f), androidx.compose.ui.geometry.Offset(size.width * 0.33f, size.height), strokeWidth = 1f)
                    drawLine(guideColor, androidx.compose.ui.geometry.Offset(size.width * 0.66f, 0f), androidx.compose.ui.geometry.Offset(size.width * 0.66f, size.height), strokeWidth = 1f)
                    drawLine(guideColor, androidx.compose.ui.geometry.Offset(0f, size.height * 0.5f), androidx.compose.ui.geometry.Offset(size.width, size.height * 0.5f), strokeWidth = 1f)
                    val fitScale = minOf(
                        size.width / preview.widthPx.toFloat().coerceAtLeast(1f),
                        size.height / preview.heightPx.toFloat().coerceAtLeast(1f)
                    )
                    val canvasWidth = preview.widthPx * fitScale
                    val canvasHeight = preview.heightPx * fitScale
                    val canvasOffsetX = (size.width - canvasWidth) / 2f
                    val canvasOffsetY = (size.height - canvasHeight) / 2f
                    selectThumbnailElements(
                        elements = preview.elements,
                        widthPx = preview.widthPx,
                        heightPx = preview.heightPx
                    )
                        .sortedBy { if (it.kind == CrownProfileShareManager.PreviewElementKind.GROUP) 0 else 1 }
                        .forEach { element ->
                        val centerX = (canvasOffsetX + element.centerX * fitScale)
                            .coerceIn(canvasOffsetX, canvasOffsetX + canvasWidth)
                        val centerY = (canvasOffsetY + element.centerY * fitScale)
                            .coerceIn(canvasOffsetY, canvasOffsetY + canvasHeight)
                        val width = (element.width * fitScale).coerceIn(4f, size.width * 0.38f)
                        val height = (element.height * fitScale).coerceIn(4f, size.height * 0.62f)
                        val left = centerX - width / 2f
                        val top = centerY - height / 2f
                        val accent = when (element.kind) {
                            CrownProfileShareManager.PreviewElementKind.STICK -> previewSecondary
                            CrownProfileShareManager.PreviewElementKind.PAD -> previewTertiary
                            CrownProfileShareManager.PreviewElementKind.WHEEL -> previewSecondary
                            CrownProfileShareManager.PreviewElementKind.GROUP -> previewInk
                            CrownProfileShareManager.PreviewElementKind.BUTTON -> previewPrimary
                        }
                        val elementAlpha = element.alpha.coerceIn(0.35f, 1f)
                        val outline = accent.copy(
                            alpha = when (element.kind) {
                                CrownProfileShareManager.PreviewElementKind.GROUP -> 0.64f
                                else -> (0.72f * elementAlpha).coerceAtLeast(0.48f)
                            }
                        )
                        val fill = accent.copy(
                            alpha = when (element.kind) {
                                CrownProfileShareManager.PreviewElementKind.BUTTON -> 0.26f * elementAlpha
                                CrownProfileShareManager.PreviewElementKind.PAD -> 0.20f * elementAlpha
                                CrownProfileShareManager.PreviewElementKind.STICK -> 0.14f * elementAlpha
                                CrownProfileShareManager.PreviewElementKind.WHEEL -> 0.12f * elementAlpha
                                CrownProfileShareManager.PreviewElementKind.GROUP -> 0f
                            }
                        )
                        val stroke = Stroke(
                            width = if (element.kind == CrownProfileShareManager.PreviewElementKind.GROUP) 1f else 1.25f
                        )

                        when (element.kind) {
                            CrownProfileShareManager.PreviewElementKind.STICK -> {
                                val radius = minOf(width, height) / 2f
                                if (!element.invisible) {
                                    drawCircle(fill, radius, androidx.compose.ui.geometry.Offset(centerX, centerY))
                                }
                                drawCircle(outline, radius, androidx.compose.ui.geometry.Offset(centerX, centerY), style = stroke)
                                drawCircle(
                                    accent.copy(alpha = 0.76f * elementAlpha),
                                    radius * 0.29f,
                                    androidx.compose.ui.geometry.Offset(centerX, centerY)
                                )
                            }
                            CrownProfileShareManager.PreviewElementKind.WHEEL -> {
                                val radius = minOf(width, height) / 2f
                                drawCircle(fill, radius, androidx.compose.ui.geometry.Offset(centerX, centerY))
                                drawCircle(outline, radius, androidx.compose.ui.geometry.Offset(centerX, centerY), style = stroke)
                                drawCircle(outline.copy(alpha = 0.55f), radius * 0.58f, androidx.compose.ui.geometry.Offset(centerX, centerY), style = stroke)
                            }
                            CrownProfileShareManager.PreviewElementKind.GROUP -> {
                                drawRoundRect(
                                    color = outline,
                                    topLeft = androidx.compose.ui.geometry.Offset(left, top),
                                    size = androidx.compose.ui.geometry.Size(width, height),
                                    cornerRadius = CornerRadius(6f, 6f),
                                    style = stroke
                                )
                            }
                            CrownProfileShareManager.PreviewElementKind.PAD,
                            CrownProfileShareManager.PreviewElementKind.BUTTON -> {
                                if (!element.invisible) {
                                    drawRoundRect(
                                        color = fill,
                                        topLeft = androidx.compose.ui.geometry.Offset(left, top),
                                        size = androidx.compose.ui.geometry.Size(width, height),
                                        cornerRadius = CornerRadius(minOf(width, height) * 0.18f, minOf(width, height) * 0.18f)
                                    )
                                }
                                drawRoundRect(
                                    color = outline,
                                    topLeft = androidx.compose.ui.geometry.Offset(left, top),
                                    size = androidx.compose.ui.geometry.Size(width, height),
                                    cornerRadius = CornerRadius(minOf(width, height) * 0.18f, minOf(width, height) * 0.18f),
                                    style = stroke
                                )
                            }
                        }

                        if (element.kind == CrownProfileShareManager.PreviewElementKind.PAD && width > 12f && height > 12f) {
                            drawLine(outline.copy(alpha = 0.72f), androidx.compose.ui.geometry.Offset(centerX, top + height * 0.22f), androidx.compose.ui.geometry.Offset(centerX, top + height * 0.78f), strokeWidth = 1f)
                            drawLine(outline.copy(alpha = 0.72f), androidx.compose.ui.geometry.Offset(left + width * 0.22f, centerY), androidx.compose.ui.geometry.Offset(left + width * 0.78f, centerY), strokeWidth = 1f)
                        }

                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(0.8f),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .background(previewMuted, AppShapes.extraSmall)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.72f)
                                .height(8.dp)
                                .background(previewMuted, AppShapes.extraSmall)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .background(previewControl, CircleShape)
                    )
                    Column(
                        modifier = Modifier.weight(0.7f),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .background(previewControl, AppShapes.extraSmall)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.58f)
                                .height(8.dp)
                                .background(previewMuted, AppShapes.extraSmall)
                        )
                    }
                }
                if (state?.loading == true) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(16.dp)
                            .align(Alignment.TopEnd),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp
                    )
                }
            }
        }
    }

    @Composable
    private fun CrownProfileCard(
        modifier: Modifier = Modifier,
        contentPadding: PaddingValues = PaddingValues(16.dp),
        content: @Composable ColumnScope.() -> Unit
    ) {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = AppShapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = BorderStroke(
                width = 0.75.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.58f)
            )
        ) {
            Column(
                modifier = Modifier.padding(contentPadding),
                content = content
            )
        }
    }

    @Composable
    private fun CrownLoadingState(text: String) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp
            )
            Spacer(modifier = Modifier.height(12.dp))
            CrownBodyText(text)
        }
    }

    @Composable
    private fun CrownStateCard(
        title: String,
        message: String,
        buttonText: String? = null,
        buttonAction: (() -> Unit)? = null
    ) {
        CrownProfileCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.phc_info),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            CrownBodyText(message)
            if (buttonText != null && buttonAction != null) {
                Spacer(modifier = Modifier.height(10.dp))
                CrownActionButton(
                    text = buttonText,
                    iconRes = R.drawable.phc_action_reset,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = buttonAction
                )
            }
        }
    }

    @Composable
    private fun CrownCardTitle(
        text: String,
        maxLines: Int = 2,
        modifier: Modifier = Modifier
    ) {
        Text(
            text = text,
            modifier = modifier,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
    }

    @Composable
    private fun CrownBodyText(text: String, maxLines: Int = Int.MAX_VALUE) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
    }

    @Composable
    private fun CrownMetaText(text: String, strong: Boolean, maxLines: Int = 1) {
        Text(
            text = text,
            color = if (strong) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alpha(if (strong) 0.9f else 1f)
        )
    }

    @Composable
    private fun CrownFootnote(text: String) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }

    @Composable
    private fun CrownFilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
        Text(
            text = text,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier
                .height(30.dp)
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    CircleShape
                )
                .border(
                    1.dp,
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                    else MaterialTheme.colorScheme.outline,
                    CircleShape
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }

    @Composable
    private fun CrownDetailInfoRow(iconRes: Int, title: String, value: String) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = value,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    /** Design P3: translucent pill pinned over a layout preview. */
    @Composable
    private fun CrownPreviewOverlayChip(text: String) {
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.45f), AppShapes.small)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }

    @Composable
    private fun CrownTagChip(text: String, modifier: Modifier = Modifier) {
        Box(
            modifier = modifier
                .height(25.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = AppShapes.small
                )
                .padding(horizontal = 7.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    @Composable
    private fun CrownStatusChip(text: String, modifier: Modifier = Modifier) {
        Row(
            modifier = modifier
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape
                )
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = text,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }

    @Composable
    private fun CrownActionButton(
        text: String,
        iconRes: Int,
        modifier: Modifier = Modifier,
        primary: Boolean = false,
        compact: Boolean = false,
        onClick: () -> Unit
    ) {
        val container = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest
        val content = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        ComposeButton(
            onClick = onClick,
            modifier = modifier.height(if (compact) 38.dp else 44.dp),
            shape = AppShapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = container,
                contentColor = content
            ),
            border = BorderStroke(
                width = 1.dp,
                color = if (primary) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                } else {
                    MaterialTheme.colorScheme.outline
                }
            ),
            contentPadding = if (compact) {
                PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            } else {
                ButtonDefaults.ButtonWithIconContentPadding
            }
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(if (compact) 16.dp else 18.dp)
            )
            Spacer(modifier = Modifier.width(if (compact) 4.dp else 6.dp))
            Text(
                text = text,
                fontSize = if (compact) 11.sp else 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    private fun showDeleteLocalProfileDialog(profile: LocalCrownProfile) {
        val configId = profile.id.toLongOrNull()
        if (configId == null) {
            Toast.makeText(this, R.string.toast_crown_store_delete_failed, Toast.LENGTH_LONG).show()
            return
        }
        if (configId == DEFAULT_CROWN_CONFIG_ID) {
            Toast.makeText(this, R.string.toast_crown_store_delete_default_blocked, Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this, R.style.AppDialogStyle)
            .setTitle(R.string.title_crown_store_delete_profile)
            .setMessage(getString(R.string.message_crown_store_delete_profile, profile.name))
            .setPositiveButton(R.string.action_crown_store_delete_profile) { _, _ ->
                deleteLocalProfile(configId, profile.name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .also { AppDialogStyler.installDismissKeys(it) }
    }

    private fun deleteLocalProfile(configId: Long, profileName: String) {
        runCatching {
            helper.deleteConfig(configId)
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            if (prefs.getLong(CURRENT_CROWN_CONFIG_ID_KEY, DEFAULT_CROWN_CONFIG_ID) == configId) {
                prefs.edit {
                    putLong(CURRENT_CROWN_CONFIG_ID_KEY, DEFAULT_CROWN_CONFIG_ID)
                }
            }
        }.onSuccess {
            onLocalProfilesChanged()
            Toast.makeText(
                this,
                getString(R.string.toast_crown_store_delete_success, profileName),
                Toast.LENGTH_SHORT
            ).show()
        }.onFailure { error ->
            Log.e("CrownStore", "Failed to delete local Crown profile", error)
            Toast.makeText(this, R.string.toast_crown_store_delete_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun loadLocalProfiles(): List<LocalCrownProfile> {
        val activeConfigId = PreferenceManager.getDefaultSharedPreferences(this)
            .getLong(CURRENT_CROWN_CONFIG_ID_KEY, DEFAULT_CROWN_CONFIG_ID)
            .toString()
        return loadConfigMap(helper).map { (id, name) ->
            LocalCrownProfile(id = id, name = name, isActive = id == activeConfigId)
        }
    }

    private fun loadConfigMap(helper: SuperConfigDatabaseHelper): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        for (id in helper.queryAllConfigIds()) {
            val name = helper.queryConfigAttribute(
                id,
                PageConfigController.COLUMN_STRING_CONFIG_NAME,
                "default"
            ) as String
            map[id.toString()] = name
        }
        return map
    }

    private fun exportCrownSharePackage(configId: String, profileName: String) {
        try {
            val payload = helper.exportConfig(configId.toLong())
            pendingCrownShareExportString = CrownProfileShareManager.createBundle(
                profileName = profileName,
                payload = payload,
                metadata = currentCrownShareExportMetadata()
            )
            createCrownShareDocument(profileName)
        } catch (e: Exception) {
            Log.e("CrownShare", "Failed to export Crown share package", e)
            Toast.makeText(this, R.string.toast_crown_share_export_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun exportLegacyConfig(configId: String, profileName: String) {
        exportConfigString = helper.exportConfig(configId.toLong())
        createConfigDocument(profileName)
    }

    private fun openLegacyMergeDocument(configId: String) {
        mergeTargetConfigId = configId
        openConfigDocument(REQUEST_CODE_OPEN_LEGACY_MERGE)
    }

    private fun openLegacyImportDocument() {
        openConfigDocument(REQUEST_CODE_OPEN_LEGACY_IMPORT)
    }

    private fun currentCrownShareExportMetadata(): CrownProfileShareManager.ExportMetadata {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        return CrownProfileShareManager.ExportMetadata(
            packageName = packageName,
            appVersionCode = versionCode,
            appVersionName = packageInfo.versionName ?: "",
            layoutBasis = currentLayoutBasis()
        )
    }

    private fun currentLayoutBasis(): CrownProfileShareManager.LayoutBasis {
        val metrics = resources.displayMetrics
        val orientation = when (resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> "landscape"
            Configuration.ORIENTATION_PORTRAIT -> "portrait"
            else -> "unknown"
        }
        return CrownProfileShareManager.LayoutBasis(
            widthPx = metrics.widthPixels,
            heightPx = metrics.heightPixels,
            densityDpi = metrics.densityDpi,
            density = metrics.density,
            orientation = orientation
        )
    }

    private fun showCrownStorePublishMetadataDialog(configId: String, defaultName: String) {
        if (!GitHubStarVerifier.isConfigured()) {
            Toast.makeText(this, R.string.toast_developer_oauth_unconfigured, Toast.LENGTH_LONG).show()
            return
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (GitHubDeviceAuthorization.accessToken(
                this, GitHubStarVerifier.OAuthScope.CROWN_STORE_PUBLISH
            ) == null) {
            showCrownStoreGitHubAuthorizationRequiredDialog(clearSavedToken = false)
            return
        }

        val defaultAuthor = prefs.getString(DeveloperUnlockSettings.PREF_USER_LOGIN, null).orEmpty()
        val nameInput = crownStorePublishInput(defaultName, R.string.hint_crown_store_profile_name)
        val gameInput = crownStorePublishInput("", R.string.hint_crown_store_game)
        val authorInput = crownStorePublishInput(defaultAuthor, R.string.hint_crown_store_author)
        val tagsInput = crownStorePublishInput("", R.string.hint_crown_store_tags)
        val summaryInput = crownStorePublishInput("", R.string.hint_crown_store_summary).apply {
            setSingleLine(false)
            minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = Gravity.START or Gravity.TOP
        }
        val termsCheckBox = CheckBox(this).apply {
            text = getString(R.string.message_crown_store_publish_terms)
            textSize = 11f
            includeFontPadding = true
            setTextColor(ContextCompat.getColor(this@CrownStoreActivity, R.color.app_dialog_title_color))
            buttonTintList = ColorStateList.valueOf(
                UiHelper.accentColor(this@CrownStoreActivity)
            )
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = dp(22)
            setPadding(inset, 0, inset, 0)
            addCrownStorePublishField(R.string.label_crown_store_profile_name, nameInput)
            addCrownStorePublishField(R.string.label_crown_store_game, gameInput)
            addCrownStorePublishField(R.string.label_crown_store_author, authorInput)
            addCrownStorePublishField(R.string.label_crown_store_tags, tagsInput)
            addCrownStorePublishField(R.string.label_crown_store_summary, summaryInput)
            addView(termsCheckBox, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
            })
        }

        val scrollView = ScrollView(this).apply {
            isFillViewport = false
            addView(container)
        }

        val dialog = AlertDialog.Builder(this, R.style.AppDialogStyle)
            .setTitle(R.string.title_crown_store_publish_metadata)
            .setMessage(R.string.message_crown_store_publish_metadata)
            .setView(scrollView)
            .setPositiveButton(R.string.crown_store_action_publish, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val profileName = nameInput.text?.toString().orEmpty().trim()
                if (profileName.isBlank()) {
                    nameInput.error = getString(R.string.hint_crown_store_profile_name)
                    return@setOnClickListener
                }
                if (!termsCheckBox.isChecked) {
                    Toast.makeText(
                        this,
                        R.string.toast_crown_store_publish_terms_required,
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }

                val game = gameInput.text?.toString().orEmpty().trim()
                val author = authorInput.text?.toString().orEmpty().trim()
                val summary = summaryInput.text?.toString().orEmpty().trim()
                val tags = tagsInput.text?.toString().orEmpty()
                    .split(Regex("[,\\s\\uFF0C]+"))
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                validateCrownStoreSubmission(profileName, game, author, summary, tags)?.let { reason ->
                    Toast.makeText(
                        this,
                        getString(R.string.toast_crown_store_publish_invalid_metadata, reason),
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }

                try {
                    val payload = helper.exportConfig(configId.toLong())
                    val bundle = CrownProfileShareManager.createBundle(
                        profileName = profileName,
                        payload = payload,
                        metadata = currentCrownShareExportMetadata(),
                        displayMetadata = CrownProfileShareManager.BundleDisplayMetadata(
                            summary = summary,
                            authorName = author,
                            gameName = game,
                            tags = tags
                        )
                    )
                    validateCrownStoreBundleForPublishing(bundle)?.let { reason ->
                        Toast.makeText(
                            this,
                            getString(R.string.toast_crown_store_publish_invalid_metadata, reason),
                            Toast.LENGTH_LONG
                        ).show()
                        return@setOnClickListener
                    }
                    dialog.dismiss()
                    publishCrownStoreProfile(
                        GitHubCrownProfileStorePublisher.PublishRequest(
                            profileName = profileName,
                            summary = summary,
                            author = author,
                            game = game,
                            tags = tags,
                            bundleJson = bundle
                        )
                    )
                } catch (e: Exception) {
                    Log.e("CrownStore", "Failed to prepare Crown Store profile", e)
                    Toast.makeText(this, R.string.toast_crown_store_publish_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
        AppDialogStyler.installDismissKeys(dialog)
    }

    private fun validateCrownStoreSubmission(
        profileName: String,
        game: String,
        author: String,
        summary: String,
        tags: List<String>
    ): String? {
        if (profileName.length > 80) return getString(R.string.crown_store_validation_name_too_long)
        if (game.length > 80) return getString(R.string.crown_store_validation_game_too_long)
        if (author.length > 60) return getString(R.string.crown_store_validation_author_too_long)
        if (summary.length > 240) return getString(R.string.crown_store_validation_summary_too_long)
        if (tags.size > 8) return getString(R.string.crown_store_validation_too_many_tags)
        if (tags.any { it.length > 24 }) return getString(R.string.crown_store_validation_tag_too_long)

        val metadataText = buildString {
            append(profileName).append('\n')
            append(game).append('\n')
            append(author).append('\n')
            append(summary).append('\n')
            append(tags.joinToString("\n"))
        }
        if (CROWN_STORE_EXTERNAL_LOCATOR_PATTERN.containsMatchIn(metadataText)) {
            return getString(R.string.crown_store_validation_external_locator)
        }
        if (CROWN_STORE_SENSITIVE_PATTERN.containsMatchIn(metadataText)) {
            return getString(R.string.crown_store_validation_sensitive_metadata)
        }
        return null
    }

    private fun validateCrownStoreBundleForPublishing(bundle: String): String? {
        return try {
            CrownProfileShareManager.parseImportText(bundle)
            val root = JSONObject(bundle)
            if (!root.hasOnlyKeys(CROWN_STORE_PUBLIC_BUNDLE_KEYS)) {
                return getString(R.string.crown_store_validation_invalid_bundle)
            }
            val profile = root.optJSONObject("profile")
                ?: return getString(R.string.crown_store_validation_invalid_bundle)
            if (!profile.hasOnlyKeys(CROWN_STORE_PUBLIC_PROFILE_KEYS)) {
                return getString(R.string.crown_store_validation_invalid_bundle)
            }
            val profilePayload = profile.optString("payload", "")
            if (CROWN_STORE_SENSITIVE_PATTERN.containsMatchIn(profilePayload)) {
                return getString(R.string.crown_store_validation_sensitive_metadata)
            }
            null
        } catch (e: Exception) {
            Log.e("CrownStore", "Invalid Crown Store bundle", e)
            getString(R.string.crown_store_validation_invalid_bundle)
        }
    }

    private fun crownStorePublishInput(value: String, hintRes: Int): EditText {
        return EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
            setText(value)
            hint = getString(hintRes)
            setTextColor(ContextCompat.getColor(this@CrownStoreActivity, R.color.crown_text_primary))
            setHintTextColor(ContextCompat.getColor(this@CrownStoreActivity, R.color.crown_text_secondary))
            setPadding(dp(12), 0, dp(12), 0)
            minimumHeight = dp(36)
            val normalStroke = ContextCompat.getColor(this@CrownStoreActivity, R.color.crown_input_border)
            val focusedStroke = UiHelper.accentColor(this@CrownStoreActivity)
            fun applyFieldBackground(focused: Boolean) {
                background = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(this@CrownStoreActivity, R.color.crown_input_background))
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(if (focused) 2 else 1), if (focused) focusedStroke else normalStroke)
                }
            }
            applyFieldBackground(false)
            setOnFocusChangeListener { _, focused -> applyFieldBackground(focused) }
        }
    }

    private fun LinearLayout.addCrownStorePublishField(labelRes: Int, input: EditText) {
        val block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                // Design P5: micro section labels above each 36dp rounded field.
                text = getString(labelRes).uppercase(Locale.getDefault())
                setTextColor(ContextCompat.getColor(this@CrownStoreActivity, R.color.crown_text_secondary))
                textSize = 9.5f
                letterSpacing = 0.08f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
            })
            addView(input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
            })
        }
        addView(block, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(12)
        })
    }

    private fun publishCrownStoreProfile(request: GitHubCrownProfileStorePublisher.PublishRequest) {
        val appContext = applicationContext
        val accessToken = GitHubDeviceAuthorization.accessToken(
            appContext, GitHubStarVerifier.OAuthScope.CROWN_STORE_PUBLISH
        )
        if (accessToken == null) {
            showCrownStoreGitHubAuthorizationRequiredDialog(clearSavedToken = false)
            return
        }

        Toast.makeText(this, R.string.toast_crown_store_publish_started, Toast.LENGTH_LONG).show()
        thread(name = "CrownStorePublish") {
            val result = runCatching {
                GitHubCrownProfileStorePublisher.publish(accessToken, request)
            }

            mainHandler.post {
                result.onSuccess { publishResult ->
                    showCrownStorePublishSuccessDialog(publishResult)
                }.onFailure { error ->
                    if (error is GitHubCrownProfileStorePublisher.GitHubCrownStoreException &&
                        error.authorizationFailure) {
                        showCrownStoreGitHubAuthorizationRequiredDialog(
                            clearSavedToken = true,
                            failedToken = accessToken
                        )
                    } else {
                        Log.e("CrownStore", "Failed to publish Crown Store profile", error)
                        Toast.makeText(
                            appContext,
                            getString(
                                R.string.toast_crown_store_publish_failed_with_error,
                                error.message ?: error.javaClass.simpleName
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun showCrownStoreGitHubAuthorizationRequiredDialog(clearSavedToken: Boolean, failedToken: String? = null) {
        if (clearSavedToken && (failedToken == null || !GitHubDeviceAuthorization.clearCredentials(this, failedToken))) {
            return
        }

        AlertDialog.Builder(this, R.style.AppDialogStyle)
            .setTitle(R.string.title_crown_store_github_authorization)
            .setMessage(
                if (clearSavedToken) {
                    R.string.message_crown_store_github_reauthorization_required
                } else {
                    R.string.message_crown_store_github_authorization_required
                }
            )
            .setPositiveButton(R.string.action_crown_store_authorize_github) { _, _ ->
                startDeveloperUnlockVerification(GitHubStarVerifier.OAuthScope.CROWN_STORE_PUBLISH)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .also { AppDialogStyler.installDismissKeys(it) }
    }

    private fun showCrownStorePublishSuccessDialog(result: GitHubCrownProfileStorePublisher.PublishResult) {
        AlertDialog.Builder(this, R.style.AppDialogStyle)
            .setTitle(R.string.title_crown_store_publish_success)
            .setMessage(
                getString(
                    R.string.message_crown_store_publish_success,
                    result.profilePath,
                    result.pullRequestUrl
                )
            )
            .setPositiveButton(R.string.action_open_pull_request) { _, _ ->
                openUrl(result.pullRequestUrl)
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
            .also { AppDialogStyler.installDismissKeys(it) }
    }

    private fun startDeveloperUnlockVerification(scope: GitHubStarVerifier.OAuthScope) {
        if (!GitHubStarVerifier.isConfigured()) {
            Toast.makeText(this, R.string.toast_developer_oauth_unconfigured, Toast.LENGTH_LONG).show()
            return
        }
        if (developerUnlockVerificationRunning) {
            Toast.makeText(this, R.string.toast_developer_verification_running, Toast.LENGTH_LONG).show()
            return
        }

        developerUnlockVerificationRunning = true
        Toast.makeText(this, R.string.toast_developer_verification_started, Toast.LENGTH_LONG).show()
        val appContext = applicationContext
        thread(name = "CrownStoreGitHubDeviceCode") {
            try {
                val savedToken = GitHubDeviceAuthorization.accessToken(appContext, scope)
                val starCheck = try {
                    savedToken?.let(GitHubStarVerifier::checkStar)
                } catch (_: GitHubStarVerifier.AuthorizationExpiredException) {
                    GitHubDeviceAuthorization.clearCredentials(appContext, savedToken)
                    null
                }
                if (savedToken != null && starCheck != null) {
                    mainHandler.post {
                        developerUnlockVerificationRunning = false
                        completeDeveloperUnlockVerification(
                            appContext, savedToken, starCheck, scope
                        )
                    }
                } else {
                    val deviceCode = GitHubDeviceAuthorization.pendingDeviceCode(appContext, scope)
                        ?: GitHubStarVerifier.requestDeviceCode(scope).also {
                            GitHubDeviceAuthorization.savePendingDeviceCode(appContext, it)
                        }
                    developerPendingDeviceCode = deviceCode
                    mainHandler.post {
                        developerUnlockVerificationRunning = false
                        showDeveloperDeviceCodeDialog(deviceCode)
                    }
                }
            } catch (e: Exception) {
                Log.e("DeveloperUnlock", "GitHub star verification failed", e)
                failDeveloperUnlockVerification(appContext, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun showDeveloperDeviceCodeDialog(deviceCode: GitHubStarVerifier.DeviceCode) {
        developerDeviceCodeDialog?.dismiss()
        GitHubDeviceAuthorization.copyDeviceCodeToClipboard(this, deviceCode)
        val dialog = AlertDialog.Builder(this, R.style.AppDialogStyle)
            .setTitle(
                if (deviceCode.scope == GitHubStarVerifier.OAuthScope.CROWN_STORE_PUBLISH) {
                    R.string.title_crown_store_github_authorization
                } else {
                    R.string.title_developer_unlock
                }
            )
            .setMessage(
                getString(
                    if (deviceCode.scope == GitHubStarVerifier.OAuthScope.CROWN_STORE_PUBLISH) {
                        R.string.message_crown_store_device_code
                    } else {
                        R.string.message_developer_device_code
                    },
                    deviceCode.userCode,
                    deviceCode.verificationUri
                )
            )
            .setPositiveButton(R.string.action_developer_open_authorization, null)
            .setNeutralButton(R.string.action_developer_check_authorization, null)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                developerUnlockVerificationRunning = false
                clearDeveloperPendingDeviceCode(applicationContext)
            }
            .setOnCancelListener {
                developerUnlockVerificationRunning = false
                clearDeveloperPendingDeviceCode(applicationContext)
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                GitHubDeviceAuthorization.copyDeviceCodeToClipboard(
                    this,
                    deviceCode,
                    showToast = false
                )
                openUrl(GitHubDeviceAuthorization.authorizationUrl(deviceCode))
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                pollDeveloperPendingDeviceCode(showPendingToast = true)
            }
        }
        dialog.setOnDismissListener {
            if (developerDeviceCodeDialog === dialog) {
                developerDeviceCodeDialog = null
            }
        }
        developerDeviceCodeDialog = dialog
        dialog.show()
        AppDialogStyler.installDismissKeys(dialog)
    }

    private fun pollDeveloperPendingDeviceCode(showPendingToast: Boolean) {
        val deviceCode = developerPendingDeviceCode
            ?: GitHubDeviceAuthorization.pendingDeviceCode(this, GitHubStarVerifier.OAuthScope.CROWN_STORE_PUBLISH)
        if (deviceCode == null) {
            developerUnlockVerificationRunning = false
            Toast.makeText(this, R.string.toast_developer_verification_expired, Toast.LENGTH_LONG).show()
            return
        }

        developerPendingDeviceCode = deviceCode
        if (developerForegroundPollRunning) return
        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (nowMs - developerLastForegroundPollMs < deviceCode.intervalSeconds * 1000L) {
            if (showPendingToast) {
                Toast.makeText(this, R.string.toast_developer_authorization_pending, Toast.LENGTH_LONG).show()
            }
            return
        }
        developerLastForegroundPollMs = nowMs
        developerForegroundPollRunning = true
        developerUnlockVerificationRunning = true
        val appContext = applicationContext
        thread(name = "CrownStoreGitHubDevicePoll") {
            try {
                when (val poll = GitHubStarVerifier.pollAccessToken(deviceCode)) {
                    is GitHubStarVerifier.TokenPollResult.Authorized -> {
                        val starCheck = GitHubStarVerifier.checkStar(poll.accessToken)
                        runIfDeveloperAttemptActive(deviceCode) {
                            completeDeveloperUnlockVerification(
                                appContext,
                                poll.accessToken,
                                starCheck,
                                deviceCode.scope
                            )
                        }
                    }
                    GitHubStarVerifier.TokenPollResult.Pending -> {
                        runIfDeveloperAttemptActive(deviceCode) {
                            if (showPendingToast) {
                                Toast.makeText(
                                    appContext,
                                    R.string.toast_developer_authorization_pending,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                    is GitHubStarVerifier.TokenPollResult.SlowDown -> {
                        GitHubDeviceAuthorization.updatePendingPollInterval(
                            appContext, deviceCode, poll.intervalSeconds
                        )
                        runIfDeveloperAttemptActive(deviceCode) {
                            developerPendingDeviceCode = deviceCode.copy(intervalSeconds = poll.intervalSeconds)
                        }
                    }
                    is GitHubStarVerifier.TokenPollResult.Failed -> {
                        runIfDeveloperAttemptActive(deviceCode) {
                            failDeveloperUnlockVerification(appContext, poll.message)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("DeveloperUnlock", "GitHub star foreground verification failed", e)
                runIfDeveloperAttemptActive(deviceCode) {
                    failDeveloperUnlockVerification(appContext, e.message ?: e.javaClass.simpleName)
                }
            } finally {
                mainHandler.post {
                    developerForegroundPollRunning = false
                    if (developerPendingDeviceCode?.deviceCode == deviceCode.deviceCode) {
                        developerUnlockVerificationRunning = false
                    }
                }
            }
        }
    }

    private fun runIfDeveloperAttemptActive(
        deviceCode: GitHubStarVerifier.DeviceCode,
        action: () -> Unit
    ) {
        mainHandler.post {
            if (developerPendingDeviceCode?.deviceCode == deviceCode.deviceCode) {
                action()
            }
        }
    }

    private fun completeDeveloperUnlockVerification(
        ctx: Context,
        accessToken: String,
        starCheck: GitHubStarVerifier.StarCheck,
        scope: GitHubStarVerifier.OAuthScope
    ) {
        val dialogToDismiss = developerDeviceCodeDialog
        developerUnlockVerificationRunning = false
        clearDeveloperPendingDeviceCode(ctx)
        GitHubDeviceAuthorization.saveAuthorizedAccount(ctx, accessToken, starCheck, scope)
        mainHandler.post {
            if (developerDeviceCodeDialog === dialogToDismiss) {
                dialogToDismiss?.dismiss()
            }
            if (scope == GitHubStarVerifier.OAuthScope.CROWN_STORE_PUBLISH) {
                Toast.makeText(this, R.string.toast_crown_store_github_connected, Toast.LENGTH_LONG).show()
            } else if (starCheck.starred) {
                Toast.makeText(this, R.string.toast_developer_unlocked, Toast.LENGTH_LONG).show()
            } else {
                AlertDialog.Builder(this, R.style.AppDialogStyle)
                    .setTitle(R.string.title_developer_unlock)
                    .setMessage(R.string.message_developer_star_not_found)
                    .setPositiveButton(R.string.action_developer_open_project) { _, _ ->
                        openUrl(DeveloperUnlockSettings.GITHUB_REPO_URL)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                    .also { AppDialogStyler.installDismissKeys(it) }
            }
        }
    }

    private fun failDeveloperUnlockVerification(ctx: Context, message: String) {
        val dialogToDismiss = developerDeviceCodeDialog
        developerUnlockVerificationRunning = false
        clearDeveloperPendingDeviceCode(ctx)
        Log.w("DeveloperUnlock", "GitHub star verification failed: $message")
        mainHandler.post {
            if (developerDeviceCodeDialog === dialogToDismiss) {
                dialogToDismiss?.dismiss()
            }
            Toast.makeText(
                this,
                getString(R.string.toast_developer_verification_failed, message),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun clearDeveloperPendingDeviceCode(ctx: Context) {
        developerPendingDeviceCode = null
        GitHubDeviceAuthorization.clearPendingDeviceCode(ctx)
    }

    private fun showCrownShareUrlImportDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            hint = getString(R.string.hint_crown_share_import_url)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = dp(24)
            setPadding(inset, 0, inset, 0)
            addView(input)
        }

        val dialog = AlertDialog.Builder(this, R.style.AppDialogStyle)
            .setTitle(R.string.crown_share_action_import_url)
            .setMessage(R.string.message_crown_share_import_url)
            .setView(container)
            .setPositiveButton(R.string.crown_share_action_import, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = input.text?.toString().orEmpty().trim()
                if (url.isBlank()) {
                    input.error = getString(R.string.hint_crown_share_import_url)
                    return@setOnClickListener
                }
                dialog.dismiss()
                importCrownShareFromUrl(url)
            }
            input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()
        AppDialogStyler.installDismissKeys(dialog)
    }

    private fun importCrownShareFromUrl(
        url: String,
        sourceLabelOverride: String? = null,
        failureToastRes: Int = R.string.toast_crown_share_url_failed
    ) {
        val normalizedUrl = url.trim()
        if (!normalizedUrl.startsWith("https://", ignoreCase = true) &&
            !normalizedUrl.startsWith("http://", ignoreCase = true)) {
            Toast.makeText(this, R.string.toast_crown_share_url_invalid, Toast.LENGTH_LONG).show()
            return
        }

        val appContext = applicationContext
        Toast.makeText(this, R.string.toast_crown_share_url_loading, Toast.LENGTH_SHORT).show()
        thread(name = "CrownShareUrlImport") {
            val result = runCatching {
                val importText = downloadRemoteText(normalizedUrl, CROWN_SHARE_MAX_DOWNLOAD_BYTES)
                CrownProfileShareManager.parseImportText(importText)
                    .copy(sourceLabel = sourceLabelOverride ?: crownShareSourceLabel(normalizedUrl))
            }

            mainHandler.post {
                result
                    .onSuccess { importedProfile ->
                        pendingCrownShareImport = importedProfile
                        showCrownShareImportPreview(importedProfile)
                    }
                    .onFailure {
                        Log.e("CrownShare", "Failed to import Crown share package from URL", it)
                        Toast.makeText(appContext, failureToastRes, Toast.LENGTH_LONG).show()
                    }
            }
        }
    }

    private fun crownShareSourceLabel(url: String): String {
        return runCatching {
            URL(url).host
                .takeIf { it.isNotBlank() }
                ?.let { getString(R.string.crown_share_source_link_host, it) }
        }.getOrNull() ?: getString(R.string.crown_share_source_link)
    }

    private fun showCrownShareImportPreview(profile: CrownProfileShareManager.ImportedProfile) {
        val unknown = getString(R.string.crown_share_unknown_value)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(8))
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.crown_share_action_import)
            setTextColor(ContextCompat.getColor(context, R.color.ui_shell_text_primary))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.crown_store_detail_ready)
            setTextColor(ContextCompat.getColor(context, R.color.ui_shell_accent))
            textSize = 11f
            setPadding(0, dp(4), 0, dp(10))
        })

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = ContextCompat.getDrawable(context, R.drawable.crown_config_section_bg)
        }
        fun addDetail(label: String, value: String) {
            details.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, dp(5))
                addView(TextView(this@CrownStoreActivity).apply {
                    text = label
                    setTextColor(ContextCompat.getColor(context, R.color.ui_shell_text_secondary))
                    textSize = 11f
                    layoutParams = LinearLayout.LayoutParams(dp(82), LinearLayout.LayoutParams.WRAP_CONTENT)
                })
                addView(TextView(this@CrownStoreActivity).apply {
                    text = value
                    setTextColor(ContextCompat.getColor(context, R.color.ui_shell_text_primary))
                    textSize = 13f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
            })
        }
        addDetail(getString(R.string.label_crown_store_profile_name), profile.name)
        addDetail(getString(R.string.label_crown_store_author), profile.author.ifBlank { unknown })
        addDetail(getString(R.string.label_crown_store_game), profile.game.ifBlank { unknown })
        addDetail(getString(R.string.label_crown_share_source), profile.sourceLabel)
        addDetail(getString(R.string.crown_store_detail_elements),
            getString(R.string.crown_store_detail_element_count, profile.payloadInfo.elementCount))
        content.addView(details)

        content.addView(CheckBox(this).apply {
            text = getString(R.string.message_crown_share_import_preview_only)
            isChecked = true
            isEnabled = false
            setTextColor(ContextCompat.getColor(context, R.color.ui_shell_text_secondary))
            textSize = 11f
            setPadding(0, dp(8), 0, 0)
        })
        content.addView(TextView(this).apply {
            text = layoutCompatibilityMessage(profile.layoutBasis)
            setTextColor(ContextCompat.getColor(context, R.color.ui_shell_text_secondary))
            textSize = 11f
            setPadding(0, dp(2), 0, dp(8))
        })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            // Weighted so the install/merge actions stay visible even when the
            // content is taller than the dialog on short landscape screens.
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(content)
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), 0, dp(22), dp(10))
        }
        val mainActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dialog = AlertDialog.Builder(this, R.style.AppDialogStyle)
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(scroll)
                addView(actions)
            })
            .create()

        fun actionButton(label: Int, backgroundRes: Int, onClick: () -> Unit): Button =
            Button(this).apply {
                text = getString(label)
                isAllCaps = false
                textSize = 11f
                // The primary button is solid accent and needs the on-accent ink;
                // everything else keeps the regular text color.
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (backgroundRes == R.drawable.crown_primary_button_bg) {
                            R.color.crown_on_accent
                        } else {
                            R.color.crown_text_primary
                        }
                    )
                )
                background = ContextCompat.getDrawable(context, backgroundRes)
                minHeight = dp(44)
                setPadding(dp(6), 0, dp(6), 0)
                setOnClickListener { onClick() }
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    marginEnd = dp(6)
                }
            }

        mainActions.addView(actionButton(
            R.string.crown_share_install_as_new,
            R.drawable.crown_primary_button_bg
        ) {
            dialog.dismiss()
            importPendingCrownShareAsNew()
        })
        mainActions.addView(actionButton(
            R.string.crown_share_merge_into_existing,
            R.drawable.crown_config_action_button_bg
        ) {
            dialog.dismiss()
            showCrownShareMergeTargetDialog()
        })
        actions.addView(mainActions)
        actions.addView(TextView(this).apply {
            text = getString(android.R.string.cancel)
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.ui_shell_text_secondary))
            textSize = 11f
            minHeight = dp(38)
            setOnClickListener { dialog.dismiss() }
        })
        dialog.setOnShowListener { AppDialogStyler.installDismissKeys(dialog) }
        dialog.show()
    }

    private fun importPendingCrownShareAsNew() {
        val profile = pendingCrownShareImport ?: return
        val errorCode = helper.importConfig(CrownProfileShareManager.payloadForInstallAsNew(profile))
        if (errorCode == 0) {
            pendingCrownShareImport = null
            onLocalProfilesChanged()
            Toast.makeText(this, R.string.toast_crown_share_import_success, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.toast_crown_share_import_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun showCrownShareMergeTargetDialog() {
        val profile = pendingCrownShareImport ?: return
        val configMap = loadConfigMap(helper)
        if (configMap.isEmpty()) {
            Toast.makeText(this, R.string.crown_config_no_profiles, Toast.LENGTH_SHORT).show()
            return
        }

        val ids = configMap.keys.toTypedArray()
        val names = configMap.values.toTypedArray<CharSequence>()
        val dialog = AlertDialog.Builder(this, R.style.AppDialogStyle)
            .setTitle(R.string.crown_share_merge_into_existing)
            .setItems(names) { _, which ->
                val errorCode = helper.mergeConfig(profile.payload, ids[which].toLong())
                if (errorCode == 0) {
                    pendingCrownShareImport = null
                    onLocalProfilesChanged()
                    Toast.makeText(this, R.string.toast_crown_share_merge_success, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.toast_crown_share_import_failed, Toast.LENGTH_LONG).show()
                }
            }
            .create()
        dialog.show()
        AppDialogStyler.applySystemChoiceList(dialog, this)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return

        when (requestCode) {
            REQUEST_CODE_CREATE_CROWN_SHARE -> handleCrownShareExportResult(data)
            REQUEST_CODE_OPEN_CROWN_SHARE -> handleCrownShareImportResult(data)
            REQUEST_CODE_CREATE_LEGACY_EXPORT -> handleLegacyExportResult(data)
            REQUEST_CODE_OPEN_LEGACY_IMPORT -> handleLegacyImportResult(data)
            REQUEST_CODE_OPEN_LEGACY_MERGE -> handleLegacyMergeResult(data)
        }
    }

    private fun handleCrownShareExportResult(data: Intent?) {
        val uri = data?.data ?: return
        try {
            writeDocumentText(uri, pendingCrownShareExportString)
            Toast.makeText(this, R.string.toast_crown_share_export_success, Toast.LENGTH_SHORT).show()
        } catch (e: IOException) {
            Log.e("CrownShare", "Failed to write Crown share package", e)
            Toast.makeText(this, R.string.toast_crown_share_export_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun handleCrownShareImportResult(data: Intent?) {
        val uri = data?.data ?: return
        try {
            val importText = readDocumentText(uri)
            val importedProfile = CrownProfileShareManager.parseImportText(importText)
            pendingCrownShareImport = importedProfile
            showCrownShareImportPreview(importedProfile)
        } catch (e: Exception) {
            Log.e("CrownShare", "Failed to read Crown share package", e)
            Toast.makeText(this, R.string.toast_crown_share_import_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun handleLegacyExportResult(data: Intent?) {
        val uri = data?.data ?: return
        try {
            writeDocumentText(uri, exportConfigString)
            Toast.makeText(this, R.string.toast_crown_config_export_success, Toast.LENGTH_SHORT).show()
        } catch (e: IOException) {
            Toast.makeText(this, R.string.toast_crown_config_export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleLegacyImportResult(data: Intent?) {
        val uri = data?.data ?: return
        try {
            val fileContent = readDocumentText(uri)
            val errorCode = helper.importConfig(fileContent)
            if (errorCode == 0) {
                onLocalProfilesChanged()
            }
            showLegacyResultToast(errorCode, R.string.toast_crown_config_import_success)
        } catch (e: IOException) {
            Toast.makeText(this, R.string.toast_crown_config_import_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleLegacyMergeResult(data: Intent?) {
        val uri = data?.data ?: return
        val targetId = mergeTargetConfigId ?: return
        try {
            val fileContent = readDocumentText(uri)
            val errorCode = helper.mergeConfig(fileContent, targetId.toLong())
            if (errorCode == 0) {
                onLocalProfilesChanged()
            }
            showLegacyResultToast(errorCode, R.string.toast_crown_config_merge_success)
        } catch (e: IOException) {
            Toast.makeText(this, R.string.toast_crown_config_import_failed, Toast.LENGTH_SHORT).show()
        } finally {
            mergeTargetConfigId = null
        }
    }

    private fun showLegacyResultToast(errorCode: Int, successRes: Int) {
        val messageRes = when (errorCode) {
            0 -> successRes
            -1, -2 -> R.string.toast_crown_config_import_failed
            -3 -> R.string.toast_crown_config_version_unsupported
            else -> R.string.toast_crown_config_import_failed
        }
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
    }

    private fun onLocalProfilesChanged() {
        ConfigurationSyncScheduler.requestSyncSoon(this)
        if (selectedTab == CrownTab.MINE) {
            renderMineTab()
        }
    }

    private fun createConfigDocument(fileName: String) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        intent.putExtra(Intent.EXTRA_TITLE, "$fileName.mdat")
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_CODE_CREATE_LEGACY_EXPORT)
    }

    private fun createCrownShareDocument(fileName: String) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "application/json"
        intent.putExtra(Intent.EXTRA_TITLE, CrownProfileShareManager.suggestedFileName(fileName))
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_CODE_CREATE_CROWN_SHARE)
    }

    private fun openConfigDocument(requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        @Suppress("DEPRECATION")
        startActivityForResult(intent, requestCode)
    }

    private fun openCrownShareDocument() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        intent.putExtra(
            Intent.EXTRA_MIME_TYPES,
            arrayOf("application/json", "text/json", "text/plain", "application/octet-stream")
        )
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_CODE_OPEN_CROWN_SHARE)
    }

    private fun readDocumentText(uri: Uri): String {
        return contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: throw IOException("Unable to open input stream")
    }

    private fun writeDocumentText(uri: Uri, text: String) {
        contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
            ?: throw IOException("Unable to open output stream")
    }

    private fun downloadRemoteText(url: String, maxBytes: Int): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 15000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json,text/plain,*/*")
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("HTTP $responseCode")
            }
            val contentLength = connection.contentLengthLong
            if (contentLength > maxBytes) {
                throw IOException("Remote Crown profile response is too large")
            }
            return connection.inputStream.use { input ->
                readLimitedText(input, maxBytes)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimitedText(input: java.io.InputStream, maxBytes: Int): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > maxBytes) {
                throw IOException("Remote Crown profile response is too large")
            }
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun formatLayoutBasis(layoutBasis: CrownProfileShareManager.LayoutBasis?): String? {
        if (layoutBasis == null || !layoutBasis.isPresent()) return null
        val orientationText = when (layoutBasis.orientation.lowercase(Locale.US)) {
            "landscape" -> getString(R.string.crown_store_layout_orientation_landscape)
            "portrait" -> getString(R.string.crown_store_layout_orientation_portrait)
            else -> getString(R.string.crown_store_layout_orientation_unknown)
        }
        val dpiText = if (layoutBasis.densityDpi > 0) {
            " · ${layoutBasis.densityDpi} dpi"
        } else {
            ""
        }
        return "${layoutBasis.widthPx}x${layoutBasis.heightPx}$dpiText · $orientationText"
    }

    private fun layoutCompatibilityMessage(layoutBasis: CrownProfileShareManager.LayoutBasis?): String {
        val currentLayout = currentLayoutBasis()
        val currentText = formatLayoutBasis(currentLayout)
            ?: getString(R.string.crown_store_layout_orientation_unknown)
        val profileText = formatLayoutBasis(layoutBasis)
        return when {
            layoutBasis == null || !layoutBasis.isPresent() -> {
                getString(R.string.message_crown_store_layout_missing, currentText)
            }
            isLayoutBasisCompatible(layoutBasis, currentLayout) -> {
                getString(R.string.message_crown_store_layout_match, currentText)
            }
            else -> {
                getString(R.string.message_crown_store_layout_mismatch, profileText, currentText)
            }
        }
    }

    private fun layoutCompatibilityShortLabel(
        layoutBasis: CrownProfileShareManager.LayoutBasis?
    ): String {
        val currentLayout = currentLayoutBasis()
        return when {
            layoutBasis == null || !layoutBasis.isPresent() ->
                getString(R.string.crown_store_detail_compatibility_unknown)
            isLayoutBasisCompatible(layoutBasis, currentLayout) ->
                getString(R.string.crown_store_detail_compatibility_match)
            else ->
                getString(R.string.crown_store_detail_compatibility_mismatch)
        }
    }

    private fun formatLayoutBasisCompact(layoutBasis: CrownProfileShareManager.LayoutBasis?): String? {
        if (layoutBasis == null || !layoutBasis.isPresent()) return null
        val sizeText = "${layoutBasis.widthPx}x${layoutBasis.heightPx}"
        val dpiText = if (layoutBasis.densityDpi > 0) {
            "${layoutBasis.densityDpi} dpi"
        } else {
            ""
        }
        return listOf(sizeText, dpiText)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    }

    private fun isLayoutBasisCompatible(
        profileLayout: CrownProfileShareManager.LayoutBasis?,
        currentLayout: CrownProfileShareManager.LayoutBasis
    ): Boolean {
        if (profileLayout == null || !profileLayout.isPresent() || !currentLayout.isPresent()) return false
        val sameSize = profileLayout.widthPx == currentLayout.widthPx &&
            profileLayout.heightPx == currentLayout.heightPx
        val sameDpi = profileLayout.densityDpi <= 0 ||
            currentLayout.densityDpi <= 0 ||
            abs(profileLayout.densityDpi - currentLayout.densityDpi) <= LAYOUT_DPI_TOLERANCE
        val sameOrientation = profileLayout.orientation.isBlank() ||
            profileLayout.orientation.equals("unknown", ignoreCase = true) ||
            currentLayout.orientation.equals("unknown", ignoreCase = true) ||
            profileLayout.orientation.equals(currentLayout.orientation, ignoreCase = true)
        return sameSize && sameDpi && sameOrientation
    }

    private fun formatStoreUpdatedAt(updatedAt: String): String {
        val trimmed = updatedAt.trim()
        if (trimmed.isBlank()) return trimmed

        val parsedDate = parseStoreUpdatedAt(trimmed) ?: return trimmed
        return DateFormat
            .getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(parsedDate)
    }

    private fun formatStoreUpdatedAtCompact(updatedAt: String): String {
        val trimmed = updatedAt.trim()
        if (trimmed.isBlank()) return ""

        val parsedDate = parseStoreUpdatedAt(trimmed) ?: return trimmed.take(10)
        val now = Calendar.getInstance()
        val updated = Calendar.getInstance().apply { time = parsedDate }
        val sameYear = now.get(Calendar.YEAR) == updated.get(Calendar.YEAR)
        val sameDay = sameYear &&
            now.get(Calendar.DAY_OF_YEAR) == updated.get(Calendar.DAY_OF_YEAR)
        val hasTime = trimmed.contains('T')
        val pattern = when {
            sameDay && hasTime -> "HH:mm"
            sameYear -> "MMM d"
            else -> "MMM d, yyyy"
        }
        return SimpleDateFormat(pattern, Locale.getDefault()).format(parsedDate)
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(
                this,
                getString(R.string.toast_developer_open_project_failed, e.message ?: e.javaClass.simpleName),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun urlParam(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }

    private fun JSONObject.hasOnlyKeys(allowedKeys: Set<String>): Boolean {
        val iterator = keys()
        while (iterator.hasNext()) {
            if (iterator.next() !in allowedKeys) {
                return false
            }
        }
        return true
    }

    private fun selectThumbnailElements(
        elements: List<CrownProfileShareManager.PreviewElement>,
        widthPx: Int,
        heightPx: Int
    ): List<CrownProfileShareManager.PreviewElement> {
        if (elements.size <= 18) return elements

        val previewWidth = widthPx.toFloat().coerceAtLeast(1f)
        val previewHeight = heightPx.toFloat().coerceAtLeast(1f)

        val structural = elements
            .filter { it.kind != CrownProfileShareManager.PreviewElementKind.BUTTON }
            .sortedByDescending { it.width * it.height }

        val buttonsByRegion = elements
            .filter { it.kind == CrownProfileShareManager.PreviewElementKind.BUTTON }
            .groupBy { element ->
                val x = (element.centerX / previewWidth * 3f).toInt().coerceIn(0, 2)
                val y = (element.centerY / previewHeight * 2f).toInt().coerceIn(0, 1)
                y * 3 + x
            }
            .values
            .flatMap { region -> region.sortedByDescending { it.width * it.height }.take(3) }

        return (structural.take(8) + buttonsByRegion)
            .distinctBy { element ->
                listOf(
                    element.kind,
                    element.centerX.toInt(),
                    element.centerY.toInt(),
                    element.width.toInt(),
                    element.height.toInt()
                ).joinToString(":")
            }
            .take(18)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        const val EXTRA_OPEN_IMPORT_PACKAGE = "extra_open_crown_import_package"
        const val EXTRA_OPEN_MY_CROWNS = "extra_open_crown_my_crowns"
        private const val REQUEST_CODE_CREATE_LEGACY_EXPORT = 501
        private const val REQUEST_CODE_OPEN_LEGACY_IMPORT = 502
        private const val REQUEST_CODE_OPEN_LEGACY_MERGE = 503
        private const val REQUEST_CODE_CREATE_CROWN_SHARE = 504
        private const val REQUEST_CODE_OPEN_CROWN_SHARE = 505
        private const val CROWN_STORE_INDEX_URL =
            "https://raw.githubusercontent.com/qiin2333/crown-profiles/main/index/v1.json"
        private const val CROWN_STORE_MAX_INDEX_BYTES = 256 * 1024
        private const val CROWN_SHARE_MAX_DOWNLOAD_BYTES = 512 * 1024
        private const val LAYOUT_DPI_TOLERANCE = 8
        private const val DEFAULT_CROWN_CONFIG_ID = 0L
        private const val CURRENT_CROWN_CONFIG_ID_KEY = "current_config_id"
        private const val CROWN_STORE_REPORT_URL =
            "https://github.com/qiin2333/crown-profiles/issues/new"
        private val CROWN_STORE_EXTERNAL_LOCATOR_PATTERN =
            Regex("https?://|www\\.|[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
        private val CROWN_STORE_SENSITIVE_PATTERN =
            Regex("ghp_|github_pat_|-----BEGIN|password|passwd|token|secret|private key|pairing|clientcert|clientkey", RegexOption.IGNORE_CASE)
        private val CROWN_STORE_PUBLIC_BUNDLE_KEYS = setOf(
            "kind",
            "schemaVersion",
            "bundleId",
            "name",
            "summary",
            "compatibility",
            "profile",
            "createdAt",
            "updatedAt",
            "packageName",
            "layoutBasis",
            "author",
            "game",
            "tags",
            "appVersionName"
        )
        private val CROWN_STORE_PUBLIC_PROFILE_KEYS = setOf(
            "profileId",
            "name",
            "payload",
            "payloadSha256"
        )
    }
}

internal fun parseStoreUpdatedAt(updatedAt: String): Date? {
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXX",
        "yyyy-MM-dd"
    )
    for (pattern in patterns) {
        val position = ParsePosition(0)
        val date = SimpleDateFormat(pattern, Locale.US).apply {
            if (pattern.contains("'Z'")) timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }.parse(updatedAt, position)
        if (date != null && position.index == updatedAt.length) return date
    }
    return null
}
