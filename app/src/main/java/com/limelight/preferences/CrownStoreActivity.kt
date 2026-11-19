package com.limelight.preferences

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.limelight.ui.ThemedAppCompatActivity
import com.limelight.R
import com.limelight.binding.input.advance_setting.config.PageConfigController
import com.limelight.binding.input.advance_setting.share.CrownProfileShareManager
import com.limelight.binding.input.advance_setting.sqlite.SuperConfigDatabaseHelper
import com.limelight.ui.theme.AppShapes
import com.limelight.utils.AppDialogStyler
import com.limelight.utils.ConfigurationSyncScheduler
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import com.limelight.utils.appAccentFocusColor
import com.limelight.utils.appAccentColor
import com.limelight.utils.UiHelper

class CrownStoreActivity : ThemedAppCompatActivity() {
    private data class LocalCrownProfile(
        val id: String,
        val name: String,
        val isActive: Boolean
    )

    private data class CrownStoreUiState(
        val localProfiles: List<LocalCrownProfile> = emptyList()
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val helper: SuperConfigDatabaseHelper by lazy { SuperConfigDatabaseHelper(this) }

    private var pendingCrownShareExportString = ""
    private var exportConfigString = ""
    private var mergeTargetConfigId: String? = null
    private var pendingCrownShareImport: CrownProfileShareManager.ImportedProfile? = null
    private val composeUiState = mutableStateOf(CrownStoreUiState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CrownStoreScreen(composeUiState.value)
        }
        renderMineTab()
    }

    override fun onResume() {
        super.onResume()
        renderMineTab()
    }

    private fun renderMineTab() {
        val profiles = loadLocalProfiles()
        composeUiState.value = composeUiState.value.copy(localProfiles = profiles)
    }

    @Composable
    private fun CrownStoreScreen(state: CrownStoreUiState) {
        val background = colorResource(R.color.crown_store_background)
        MaterialTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = background
            ) {
                Scaffold(
                    containerColor = Color.Transparent,
                    topBar = { CrownStoreTopBar() }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        CrownMineTabContent(state.localProfiles)
                    }
                }
            }
        }
    }

    @Composable
    private fun CrownStoreTopBar() {
        val textColor = colorResource(R.color.crown_store_text_primary)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { finish() }) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_right),
                    contentDescription = stringResource(R.string.crown_store_action_back),
                    modifier = Modifier.rotate(180f),
                    tint = textColor
                )
            }
            Text(
                text = stringResource(R.string.crown_store_tab_mine),
                modifier = Modifier.weight(1f),
                color = textColor,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(48.dp))
        }
    }

    @Composable
    private fun CrownMineTabContent(profiles: List<LocalCrownProfile>) {
        CrownMineHero(profiles.size)
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
                            color = appAccentFocusColor(),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.phc_crown),
                        contentDescription = null,
                        tint = appAccentColor(),
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.crown_store_my_workspace_title),
                        color = colorResource(R.color.crown_store_text_primary),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    CrownBodyText(stringResource(R.string.crown_store_my_summary), maxLines = 2)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .background(
                            color = colorResource(R.color.crown_store_input_background),
                            shape = AppShapes.medium
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = profileCount.toString(),
                            color = appAccentColor(),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.crown_store_profile_unit),
                            color = colorResource(R.color.crown_store_text_secondary),
                            fontSize = 10.sp
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
                tint = appAccentColor(),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                color = colorResource(R.color.crown_store_text_primary),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (!countText.isNullOrBlank()) {
                Text(
                    text = countText,
                    color = colorResource(R.color.crown_store_text_secondary),
                    fontSize = 11.sp,
                    modifier = Modifier
                        .background(
                            color = colorResource(R.color.crown_store_input_background),
                            shape = RoundedCornerShape(50)
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
                    tint = colorResource(R.color.crown_store_text_secondary),
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.crown_config_action_import_legacy),
                        color = colorResource(R.color.crown_store_text_primary),
                        fontSize = 14.sp,
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
        CrownProfileCard(
            modifier = modifier.pointerInput(profile.id) {
                detectTapGestures(onLongPress = { showDeleteLocalProfileDialog(profile) })
            }
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    CrownCardTitle(profile.name, maxLines = 1)
                    Spacer(modifier = Modifier.height(4.dp))
                    CrownMetaText(getString(R.string.crown_store_local_profile_id, profile.id), strong = false)
                }
                if (profile.isActive) {
                    CrownStatusChip(stringResource(R.string.crown_store_profile_active))
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                CrownActionButton(
                    text = stringResource(R.string.crown_store_action_export_share),
                    iconRes = R.drawable.phc_action_copy,
                    primary = true,
                    compact = true,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    exportCrownSharePackage(profile.id, profile.name)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    CrownActionButton(
                        text = stringResource(R.string.crown_store_action_export_legacy_short),
                        iconRes = R.drawable.phc_list,
                        compact = true,
                        modifier = Modifier.weight(1f)
                    ) {
                        exportLegacyConfig(profile.id, profile.name)
                    }
                    CrownActionButton(
                        text = stringResource(R.string.crown_store_action_merge_short),
                        iconRes = R.drawable.phc_plug,
                        compact = true,
                        modifier = Modifier.weight(1f)
                    ) {
                        openLegacyMergeDocument(profile.id)
                    }
                }
            }
        }
    }

    @Composable
    private fun CrownProfileCard(
        modifier: Modifier = Modifier,
        content: @Composable ColumnScope.() -> Unit
    ) {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = AppShapes.large,
            colors = CardDefaults.cardColors(
                containerColor = colorResource(R.color.crown_store_section_background)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            border = BorderStroke(
                width = 1.dp,
                color = colorResource(R.color.crown_store_section_border)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
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
                color = appAccentColor(),
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
                    tint = colorResource(R.color.crown_store_text_primary),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    color = colorResource(R.color.crown_store_text_primary),
                    fontSize = 17.sp,
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
    private fun CrownCardTitle(text: String, maxLines: Int = 2) {
        Text(
            text = text,
            color = colorResource(R.color.crown_store_text_primary),
            fontSize = 15.4.sp,
            fontWeight = FontWeight.Bold,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
    }

    @Composable
    private fun CrownBodyText(text: String, maxLines: Int = Int.MAX_VALUE) {
        Text(
            text = text,
            color = colorResource(R.color.crown_store_text_secondary),
            fontSize = 13.5.sp,
            lineHeight = 18.sp,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
    }

    @Composable
    private fun CrownMetaText(text: String, strong: Boolean, maxLines: Int = 1) {
        Text(
            text = text,
            color = if (strong) colorResource(R.color.crown_store_text_primary) else colorResource(R.color.crown_store_text_secondary),
            fontSize = if (strong) 12.2.sp else 11.6.sp,
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
            color = colorResource(R.color.crown_store_text_secondary),
            fontSize = 10.8.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }

    @Composable
    private fun CrownStatusChip(text: String) {
        Row(
            modifier = Modifier
                .background(
                    color = appAccentFocusColor(),
                    shape = RoundedCornerShape(50)
                )
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(appAccentColor(), CircleShape)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = text,
                color = appAccentColor(),
                fontSize = 10.sp,
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
        val container = if (primary) appAccentColor() else colorResource(R.color.crown_store_input_background)
        val content = if (primary) colorResource(R.color.app_dialog_title_color) else colorResource(R.color.crown_store_text_primary)
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
                    appAccentColor().copy(alpha = 0.75f)
                } else {
                    colorResource(R.color.crown_store_input_border)
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
                fontSize = if (compact) 10.5.sp else 13.sp,
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
        val details = getString(
            R.string.message_crown_share_import_preview,
            profile.name,
            profile.author.ifBlank { getString(R.string.crown_share_unknown_value) },
            profile.game.ifBlank { getString(R.string.crown_share_unknown_value) },
            profile.sourceLabel,
            profile.payloadInfo.version,
            profile.payloadInfo.elementCount,
            profile.payloadInfo.settingsCount
        ) + "\n\n" + layoutCompatibilityMessage(profile.layoutBasis)

        AlertDialog.Builder(this, R.style.AppDialogStyle)
            .setTitle(R.string.crown_share_action_import)
            .setMessage(details)
            .setPositiveButton(R.string.crown_share_install_as_new) { _, _ ->
                importPendingCrownShareAsNew()
            }
            .setNeutralButton(R.string.crown_share_merge_into_existing) { _, _ ->
                showCrownShareMergeTargetDialog()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .also { AppDialogStyler.installDismissKeys(it) }
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
        renderMineTab()
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

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val REQUEST_CODE_CREATE_LEGACY_EXPORT = 501
        private const val REQUEST_CODE_OPEN_LEGACY_IMPORT = 502
        private const val REQUEST_CODE_OPEN_LEGACY_MERGE = 503
        private const val REQUEST_CODE_CREATE_CROWN_SHARE = 504
        private const val REQUEST_CODE_OPEN_CROWN_SHARE = 505
        private const val CROWN_SHARE_MAX_DOWNLOAD_BYTES = 512 * 1024
        private const val LAYOUT_DPI_TOLERANCE = 8
        private const val DEFAULT_CROWN_CONFIG_ID = 0L
        private const val CURRENT_CROWN_CONFIG_ID_KEY = "current_config_id"
    }
}

