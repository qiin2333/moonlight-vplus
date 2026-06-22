package com.limelight.preferences

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.limelight.R
import com.limelight.binding.input.advance_setting.config.PageConfigController
import com.limelight.binding.input.advance_setting.share.CrownProfileShareManager
import com.limelight.binding.input.advance_setting.share.GitHubCrownProfileStorePublisher
import com.limelight.binding.input.advance_setting.sqlite.SuperConfigDatabaseHelper
import com.limelight.utils.AppDialogStyler
import com.limelight.utils.ConfigurationSyncScheduler
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread
import kotlin.math.abs

class CrownStoreActivity : AppCompatActivity() {
    private enum class CrownTab {
        STORE,
        MINE
    }

    private data class LocalCrownProfile(
        val id: String,
        val name: String
    )

    private lateinit var storeTabView: TextView
    private lateinit var mineTabView: TextView
    private lateinit var toolbarTitleView: TextView
    private lateinit var contentView: LinearLayout

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
    @Volatile
    private var developerPendingDeviceCode: GitHubStarVerifier.DeviceCode? = null
    private var developerDeviceCodeDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContentView())
        selectTab(CrownTab.STORE)
    }

    override fun onResume() {
        super.onResume()
        if (selectedTab == CrownTab.MINE) {
            renderMineTab()
        }
    }

    private fun createContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@CrownStoreActivity, R.color.advance_setting_background))
        }

        root.addView(createToolbar())

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
        }
        contentView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        scrollView.addView(
            contentView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        root.addView(createTabs())
        return root
    }

    private fun createToolbar(): View {
        val safeTopMargin = resources.getDimensionPixelSize(R.dimen.activity_safearea_top)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(12), dp(8))

            val back = ImageButton(this@CrownStoreActivity).apply {
                contentDescription = getString(R.string.crown_store_action_back)
                setImageDrawable(tintedDrawable(R.drawable.ic_arrow_right, R.color.crown_text_primary))
                rotation = 180f
                setBackgroundResource(R.drawable.crown_action_icon_button_bg)
                scaleType = ImageView.ScaleType.CENTER
                setOnClickListener { finish() }
            }
            addView(back, LinearLayout.LayoutParams(dp(40), dp(40)))

            toolbarTitleView = TextView(this@CrownStoreActivity).apply {
                text = getString(R.string.title_crown_store_view)
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(ContextCompat.getColor(this@CrownStoreActivity, R.color.crown_text_primary))
            }
            addView(
                toolbarTitleView,
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )
            addView(View(this@CrownStoreActivity), LinearLayout.LayoutParams(dp(40), dp(40)))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(10), safeTopMargin, dp(10), 0)
            }
        }
    }

    private fun createTabs(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.crown_store_bottom_nav_bg)
            setPadding(dp(12), dp(7), dp(12), dp(8))

            storeTabView = createTabView(R.string.crown_store_tab_store, R.drawable.phc_crown) {
                selectTab(CrownTab.STORE)
            }
            mineTabView = createTabView(R.string.crown_store_tab_mine, R.drawable.phc_list) {
                selectTab(CrownTab.MINE)
            }
            addView(
                storeTabView,
                LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                    marginEnd = dp(6)
                }
            )
            addView(
                mineTabView,
                LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                    marginStart = dp(6)
                }
            )
        }
    }

    private fun createTabView(labelRes: Int, iconRes: Int, click: () -> Unit): TextView {
        return TextView(this).apply {
            text = getString(labelRes)
            contentDescription = getString(labelRes)
            tag = iconRes
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            compoundDrawablePadding = dp(3)
            includeFontPadding = false
            setOnClickListener { click() }
        }
    }

    private fun selectTab(tab: CrownTab) {
        selectedTab = tab
        updateTabStyles()
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

    private fun updateTabStyles() {
        updateToolbarTitle()
        updateTabStyle(storeTabView, selectedTab == CrownTab.STORE)
        updateTabStyle(mineTabView, selectedTab == CrownTab.MINE)
    }

    private fun updateToolbarTitle() {
        val tabTitle = when (selectedTab) {
            CrownTab.STORE -> getString(R.string.crown_store_tab_store)
            CrownTab.MINE -> getString(R.string.crown_store_tab_mine)
        }
        toolbarTitleView.text = tabTitle
    }

    private fun updateTabStyle(view: TextView, selected: Boolean) {
        val textColor = if (selected) R.color.crown_accent else R.color.crown_text_secondary
        view.setTextColor(ContextCompat.getColor(this, textColor))
        view.setBackgroundResource(if (selected) R.drawable.crown_store_tab_selected_bg else R.drawable.crown_store_tab_idle_bg)
        (view.tag as? Int)?.let { iconRes ->
            view.setTopIcon(iconRes, textColor, 24)
        }
    }

    private fun renderStoreTab() {
        contentView.removeAllViews()
        contentView.addView(bodyText(R.string.crown_store_store_summary))

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(4))
        }
        actionRow.addView(
            secondaryActionButton(R.string.crown_store_action_refresh, R.drawable.phc_action_reset) {
                loadStoreProfiles(force = true)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
        )
        actionRow.addView(
            secondaryActionButton(R.string.crown_share_action_import_url, R.drawable.phc_plug) {
                showCrownShareUrlImportDialog()
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            }
        )
        contentView.addView(actionRow)

        when {
            storeLoading -> renderProgressState(R.string.toast_crown_store_loading)
            storeError != null -> renderState(
                title = getString(R.string.toast_crown_store_failed),
                message = storeError ?: "",
                buttonText = getString(R.string.crown_store_action_refresh),
                buttonAction = { loadStoreProfiles(force = true) }
            )
            storeProfiles == null -> renderState(
                title = getString(R.string.crown_store_empty_state_title),
                message = getString(R.string.crown_store_empty_state_message),
                buttonText = getString(R.string.crown_store_action_refresh),
                buttonAction = { loadStoreProfiles(force = true) }
            )
            storeProfiles!!.isEmpty() -> renderState(
                title = getString(R.string.toast_crown_store_empty),
                message = getString(R.string.crown_store_empty_state_message),
                buttonText = getString(R.string.crown_store_action_refresh),
                buttonAction = { loadStoreProfiles(force = true) }
            )
            else -> contentView.addView(storeProfilesGrid(storeProfiles!!))
        }
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

    private fun storeProfileView(profile: CrownProfileShareManager.StoreProfile): View {
        return profileCardLayout().apply {
            addView(profileCardTitle(profile.name))
            addView(storeProfileMeta(profile))
            if (profile.summary.isNotBlank()) {
                addViewWithTopMargin(storeSummaryText(profile.summary), dp(8))
            }
            if (profile.tags.isNotEmpty()) {
                addViewWithTopMargin(storeTagsView(profile.tags), dp(9))
            }
            formatLayoutBasis(profile.layoutBasis)?.let { layoutText ->
                addViewWithTopMargin(
                    storeFootnoteText(getString(R.string.crown_store_layout_basis, layoutText)),
                    dp(8)
                )
            }
            if (profile.updatedAt.isNotBlank()) {
                addViewWithTopMargin(
                    storeFootnoteText(
                        getString(
                            R.string.crown_store_updated_at,
                            formatStoreUpdatedAt(profile.updatedAt)
                        )
                    ),
                    dp(8)
                )
            }
            addView(cardActionArea(
                listOf(
                    cardActionButton(R.string.crown_store_action_import_profile, primary = true) {
                        importStoreProfile(profile)
                    },
                    cardActionButton(R.string.crown_store_action_report_profile) {
                        reportStoreProfile(profile)
                    }
                )
            ))
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

    private fun renderMineTab() {
        contentView.removeAllViews()
        contentView.addView(bodyText(R.string.crown_store_my_summary))

        val importRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(4))
        }
        importRow.addView(
            secondaryActionButton(R.string.crown_share_action_import, R.drawable.phc_action_plus) {
                openCrownShareDocument()
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
        )
        importRow.addView(
            secondaryActionButton(R.string.crown_share_action_import_url, R.drawable.phc_plug) {
                showCrownShareUrlImportDialog()
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            }
        )
        contentView.addView(importRow)

        val profiles = loadLocalProfiles()
        if (profiles.isEmpty()) {
            renderState(
                title = getString(R.string.crown_config_no_profiles),
                message = getString(R.string.crown_store_my_empty_message),
                buttonText = getString(R.string.crown_config_action_import_legacy),
                buttonAction = { openLegacyImportDocument() }
            )
        } else {
            contentView.addView(localProfilesGrid(profiles))
        }

        contentView.addView(sectionLabel(R.string.crown_store_local_tools))
        contentView.addView(
            secondaryActionButton(R.string.crown_config_action_import_legacy, R.drawable.phc_list) {
                openLegacyImportDocument()
            },
            fullWidthButtonParams()
        )
    }

    private fun localProfileView(profile: LocalCrownProfile): View {
        return profileCardLayout().apply {
            setOnLongClickListener {
                showDeleteLocalProfileDialog(profile)
                true
            }
            addView(profileCardTitle(profile.name))
            addView(metaText(getString(R.string.crown_store_local_profile_id, profile.id)))
            addView(cardActionArea(
                listOf(
                    cardActionButton(R.string.crown_store_action_publish_profile, primary = true) {
                        showCrownStorePublishMetadataDialog(profile.id, profile.name)
                    },
                    cardActionButton(R.string.crown_store_action_export_share) {
                        exportCrownSharePackage(profile.id, profile.name)
                    },
                    cardActionButton(R.string.crown_store_action_export_legacy_short) {
                        exportLegacyConfig(profile.id, profile.name)
                    },
                    cardActionButton(R.string.crown_store_action_merge_short) {
                        openLegacyMergeDocument(profile.id)
                    }
                )
            ))
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
        return loadConfigMap(helper).map { (id, name) ->
            LocalCrownProfile(id = id, name = name)
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
        val accessToken = PreferenceManager.getDefaultSharedPreferences(this)
            .getString(DeveloperUnlockSettings.PREF_ACCESS_TOKEN, null)
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (accessToken.isNullOrBlank() ||
            !DeveloperUnlockSettings.hasAccessTokenScope(
                prefs,
                GitHubStarVerifier.OAuthScope.CROWN_STORE_PUBLISH
            )) {
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
            textSize = 12f
            includeFontPadding = true
            setTextColor(ContextCompat.getColor(this@CrownStoreActivity, R.color.app_dialog_title_color))
            buttonTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this@CrownStoreActivity, R.color.app_dialog_accent_color)
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
            setTextColor(ContextCompat.getColor(this@CrownStoreActivity, R.color.app_dialog_title_color))
            setHintTextColor(ContextCompat.getColor(this@CrownStoreActivity, R.color.app_dialog_subtitle_color))
            backgroundTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_focused),
                    intArrayOf()
                ),
                intArrayOf(
                    ContextCompat.getColor(this@CrownStoreActivity, R.color.app_dialog_accent_color),
                    ContextCompat.getColor(this@CrownStoreActivity, R.color.app_dialog_subtitle_color)
                )
            )
        }
    }

    private fun LinearLayout.addCrownStorePublishField(labelRes: Int, input: EditText) {
        val block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = getString(labelRes)
                setTextColor(ContextCompat.getColor(this@CrownStoreActivity, R.color.app_dialog_title_color))
                textSize = 13f
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
        val accessToken = PreferenceManager.getDefaultSharedPreferences(appContext)
            .getString(DeveloperUnlockSettings.PREF_ACCESS_TOKEN, null)
        val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
        if (accessToken.isNullOrBlank() ||
            !DeveloperUnlockSettings.hasAccessTokenScope(
                prefs,
                GitHubStarVerifier.OAuthScope.CROWN_STORE_PUBLISH
            )) {
            showCrownStoreGitHubAuthorizationRequiredDialog(clearSavedToken = false)
            return
        }

        Toast.makeText(this, R.string.toast_crown_store_publish_started, Toast.LENGTH_LONG).show()
        thread(name = "CrownStorePublish") {
            val result = runCatching {
                GitHubCrownProfileStorePublisher.publish(accessToken, request)
            }

            mainHandler.post {
                result
                    .onSuccess { publishResult ->
                        showCrownStorePublishSuccessDialog(publishResult)
                    }
                    .onFailure { error ->
                        Log.e("CrownStore", "Failed to publish Crown Store profile", error)
                        if (error is GitHubCrownProfileStorePublisher.GitHubCrownStoreException &&
                            error.authorizationFailure) {
                            showCrownStoreGitHubAuthorizationRequiredDialog(clearSavedToken = true)
                        } else {
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

    private fun showCrownStoreGitHubAuthorizationRequiredDialog(clearSavedToken: Boolean) {
        if (clearSavedToken) {
            PreferenceManager.getDefaultSharedPreferences(this).edit {
                remove(DeveloperUnlockSettings.PREF_ACCESS_TOKEN)
                remove(DeveloperUnlockSettings.PREF_ACCESS_TOKEN_SCOPE)
                remove(DeveloperUnlockSettings.PREF_UNLOCKED)
                remove(DeveloperUnlockSettings.PREF_VERIFIED_AT_MS)
            }
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
                val deviceCode = GitHubStarVerifier.requestDeviceCode(scope)
                developerPendingDeviceCode = deviceCode
                GitHubDeviceAuthorization.savePendingDeviceCode(appContext, deviceCode)
                mainHandler.post {
                    showDeveloperDeviceCodeDialog(deviceCode)
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
    }

    private fun pollDeveloperPendingDeviceCode(showPendingToast: Boolean) {
        val deviceCode = developerPendingDeviceCode ?: GitHubDeviceAuthorization.loadPendingDeviceCode(this)
        if (deviceCode == null) {
            developerUnlockVerificationRunning = false
            Toast.makeText(this, R.string.toast_developer_verification_expired, Toast.LENGTH_LONG).show()
            return
        }

        developerUnlockVerificationRunning = true
        val appContext = applicationContext
        thread(name = "CrownStoreGitHubDevicePoll") {
            try {
                when (val poll = GitHubStarVerifier.pollAccessToken(deviceCode)) {
                    is GitHubStarVerifier.TokenPollResult.Authorized -> {
                        completeDeveloperUnlockVerification(
                            appContext,
                            poll.accessToken,
                            GitHubStarVerifier.checkStar(poll.accessToken),
                            deviceCode.scope
                        )
                    }
                    GitHubStarVerifier.TokenPollResult.Pending -> {
                        if (showPendingToast) {
                            mainHandler.post {
                                Toast.makeText(
                                    appContext,
                                    R.string.toast_developer_authorization_pending,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                    is GitHubStarVerifier.TokenPollResult.SlowDown -> {
                        GitHubDeviceAuthorization.savePendingDeviceCode(
                            appContext,
                            deviceCode.copy(intervalSeconds = poll.intervalSeconds)
                        )
                    }
                    is GitHubStarVerifier.TokenPollResult.Failed -> {
                        failDeveloperUnlockVerification(appContext, poll.message)
                    }
                }
            } catch (e: Exception) {
                Log.e("DeveloperUnlock", "GitHub star foreground verification failed", e)
                failDeveloperUnlockVerification(appContext, e.message ?: e.javaClass.simpleName)
            } finally {
                if (developerPendingDeviceCode != null) {
                    developerUnlockVerificationRunning = false
                }
            }
        }
    }

    private fun completeDeveloperUnlockVerification(
        ctx: Context,
        accessToken: String,
        starCheck: GitHubStarVerifier.StarCheck,
        scope: GitHubStarVerifier.OAuthScope
    ) {
        developerUnlockVerificationRunning = false
        clearDeveloperPendingDeviceCode(ctx)
        GitHubDeviceAuthorization.saveAuthorizedAccount(ctx, accessToken, starCheck, scope)
        mainHandler.post {
            developerDeviceCodeDialog?.dismiss()
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
            }
        }
    }

    private fun failDeveloperUnlockVerification(ctx: Context, message: String) {
        developerUnlockVerificationRunning = false
        clearDeveloperPendingDeviceCode(ctx)
        Log.w("DeveloperUnlock", "GitHub star verification failed: $message")
        mainHandler.post {
            developerDeviceCodeDialog?.dismiss()
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
    }

    private fun importPendingCrownShareAsNew() {
        val profile = pendingCrownShareImport ?: return
        val errorCode = helper.importConfig(profile.payload)
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

    private fun renderProgressState(textRes: Int) {
        val state = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(32), 0, dp(16))
        }
        state.addView(ProgressBar(this))
        state.addView(bodyText(textRes))
        contentView.addView(state)
    }

    private fun renderState(
        title: String,
        message: String,
        buttonText: String,
        buttonAction: () -> Unit
    ) {
        val state = cardLayout()
        state.addView(iconTitleRow(R.drawable.phc_info, title))
        state.addView(bodyText(message))
        state.addView(secondaryActionButton(buttonText, R.drawable.phc_action_reset, buttonAction), fullWidthButtonParams())
        contentView.addView(state)
    }

    private fun titleText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(this@CrownStoreActivity, R.color.crown_text_primary))
        }
    }

    private fun iconTitleRow(iconRes: Int, text: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(
                ImageView(this@CrownStoreActivity).apply {
                    setImageDrawable(tintedDrawable(iconRes, R.color.crown_text_primary))
                },
                LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                    marginEnd = dp(10)
                }
            )
            addView(
                titleText(text),
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )
        }
    }

    private fun bodyText(textRes: Int): TextView = bodyText(getString(textRes))

    private fun bodyText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13.5f
            setLineSpacing(0f, 1.08f)
            setTextColor(ContextCompat.getColor(this@CrownStoreActivity, R.color.crown_text_secondary))
            setPadding(0, dp(4), 0, dp(3))
        }
    }

    private fun metaText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@CrownStoreActivity, R.color.crown_text_secondary))
            setPadding(0, dp(3), 0, dp(3))
        }
    }

    private fun sectionLabel(textRes: Int): TextView {
        return TextView(this).apply {
            text = getString(textRes)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(this@CrownStoreActivity, R.color.crown_text_primary))
            setPadding(0, dp(16), 0, dp(5))
        }
    }

    private fun cardLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.crown_config_section_bg)
            setPadding(dp(12), dp(11), dp(12), dp(11))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        }
    }

    private fun storeProfilesGrid(profiles: List<CrownProfileShareManager.StoreProfile>): View {
        return masonryGrid(
            items = profiles,
            estimateHeight = { profile ->
                5 + profile.summary.length.coerceAtMost(110) / 30 + profile.tags.size.coerceAtMost(4) / 2
            },
            itemView = { storeProfileView(it) }
        )
    }

    private fun localProfilesGrid(profiles: List<LocalCrownProfile>): View {
        return masonryGrid(
            items = profiles,
            estimateHeight = { 4 + it.name.length / 12 },
            itemView = { localProfileView(it) }
        )
    }

    private fun <T> masonryGrid(
        items: List<T>,
        estimateHeight: (T) -> Int,
        itemView: (T) -> View
    ): View {
        val columns = List(2) {
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
        }
        val weights = IntArray(columns.size)
        items.forEach { item ->
            val targetIndex = weights.indices.minByOrNull { weights[it] } ?: 0
            columns[targetIndex].addView(
                itemView(item),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(10)
                }
            )
            weights[targetIndex] += estimateHeight(item).coerceAtLeast(1)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            setPadding(0, dp(10), 0, dp(2))
            columns.forEachIndexed { index, column ->
                addView(
                    column,
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        if (index == 0) {
                            marginEnd = dp(5)
                        } else {
                            marginStart = dp(5)
                        }
                    }
                )
            }
        }
    }

    private fun profileCardLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.crown_store_profile_card_bg)
            setPadding(dp(12), dp(12), dp(12), dp(11))
        }
    }

    private fun profileCardTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 15.4f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(ContextCompat.getColor(this@CrownStoreActivity, R.color.crown_text_primary))
            setLineSpacing(0f, 1.08f)
        }
    }

    private fun storeProfileMeta(profile: CrownProfileShareManager.StoreProfile): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(7), 0, 0)
            if (profile.game.isNotBlank()) {
                addView(storeMetaText(profile.game, strong = true))
            }
            if (profile.author.isNotBlank()) {
                addView(storeMetaText(profile.author, strong = false).apply {
                    if (profile.game.isNotBlank()) {
                        setPadding(0, dp(2), 0, 0)
                    }
                })
            }
        }
    }

    private fun storeMetaText(text: String, strong: Boolean): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = if (strong) 12.2f else 11.6f
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            if (strong) {
                typeface = Typeface.DEFAULT_BOLD
            }
            setTextColor(
                ContextCompat.getColor(
                    this@CrownStoreActivity,
                    if (strong) R.color.crown_text_primary else R.color.crown_text_secondary
                )
            )
            alpha = if (strong) 0.84f else 0.76f
        }
    }

    private fun storeSummaryText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 12.6f
            includeFontPadding = false
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(1).toFloat(), 1.08f)
            setTextColor(ContextCompat.getColor(this@CrownStoreActivity, R.color.crown_text_secondary))
        }
    }

    private fun storeTagsView(tags: List<String>): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val visibleTags = tags.filter { it.isNotBlank() }.take(2)
            visibleTags.forEachIndexed { index, tag ->
                addView(
                    storeTagChip(tag),
                    LinearLayout.LayoutParams(
                        0,
                        dp(25),
                        1f
                    ).apply {
                        if (index > 0) {
                            marginStart = dp(5)
                        }
                    }
                )
            }
            val overflowCount = tags.size - visibleTags.size
            if (overflowCount > 0) {
                addView(
                    storeTagChip("+$overflowCount"),
                    LinearLayout.LayoutParams(dp(36), dp(25)).apply {
                        marginStart = dp(5)
                    }
                )
            }
        }
    }

    private fun storeTagChip(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 10.4f
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(7), 0, dp(7), 0)
            setTextColor(ContextCompat.getColor(this@CrownStoreActivity, R.color.crown_text_secondary))
            setBackgroundResource(R.drawable.crown_store_tag_chip_bg)
            alpha = 0.9f
        }
    }

    private fun storeFootnoteText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 10.8f
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(ContextCompat.getColor(this@CrownStoreActivity, R.color.crown_text_secondary))
            alpha = 0.62f
        }
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

    private fun formatStoreUpdatedAt(updatedAt: String): String {
        val trimmed = updatedAt.trim()
        if (trimmed.isBlank()) return trimmed

        val parsedDate = parseStoreUpdatedAt(trimmed) ?: return trimmed
        return DateFormat
            .getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(parsedDate)
    }

    private fun parseStoreUpdatedAt(updatedAt: String): Date? {
        val utcPatterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        )
        for (pattern in utcPatterns) {
            val date = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                    isLenient = false
                }.parse(updatedAt)
            }.getOrNull()
            if (date != null) return date
        }

        return runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                isLenient = false
            }.parse(updatedAt)
        }.getOrNull()
    }

    private fun LinearLayout.addViewWithTopMargin(view: View, topMargin: Int) {
        addView(
            view,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                this.topMargin = topMargin
            }
        )
    }

    private fun cardActionArea(actions: List<View>): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(9), 0, 0)
            actions.chunked(2).forEachIndexed { rowIndex, rowActions ->
                val row = LinearLayout(this@CrownStoreActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    isBaselineAligned = false
                }
                rowActions.forEachIndexed { actionIndex, action ->
                    row.addView(
                        action,
                        LinearLayout.LayoutParams(
                            0,
                            dp(38),
                            1f
                        ).apply {
                            if (actionIndex > 0) {
                                marginStart = dp(6)
                            }
                        }
                    )
                }
                addView(
                    row,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        if (rowIndex > 0) {
                            topMargin = dp(6)
                        }
                    }
                )
            }
        }
    }

    private fun cardActionButton(
        textRes: Int,
        primary: Boolean = false,
        action: () -> Unit
    ): TextView {
        val textColor = if (primary) R.color.app_dialog_title_color else R.color.crown_text_primary
        return TextView(this).apply {
            text = getString(textRes)
            contentDescription = text
            gravity = Gravity.CENTER
            textSize = if (primary) 11.3f else 10.8f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setLineSpacing(0f, 1.0f)
            setPadding(dp(4), 0, dp(4), 0)
            setTextColor(ContextCompat.getColor(this@CrownStoreActivity, textColor))
            setBackgroundResource(
                if (primary) {
                    R.drawable.crown_store_card_action_primary_bg
                } else {
                    R.drawable.crown_store_card_action_bg
                }
            )
            setOnClickListener { action() }
        }
    }

    private fun secondaryActionButton(textRes: Int, iconRes: Int, action: () -> Unit): Button {
        return actionButton(getString(textRes), iconRes, primary = false, action = action)
    }

    private fun secondaryActionButton(text: String, iconRes: Int, action: () -> Unit): Button {
        return actionButton(text, iconRes, primary = false, action = action)
    }

    private fun actionButton(text: String, iconRes: Int, primary: Boolean, action: () -> Unit): Button {
        val textColor = if (primary) R.color.app_dialog_title_color else R.color.crown_text_primary
        return BackgroundIconButton(this).apply {
            this.text = text
            isAllCaps = false
            gravity = Gravity.CENTER
            textSize = 13.5f
            minHeight = dp(40)
            minWidth = 0
            includeFontPadding = false
            setPadding(dp(12), 0, dp(12), 0)
            setTextColor(ContextCompat.getColor(this@CrownStoreActivity, textColor))
            setBackgroundResource(if (primary) R.drawable.crown_store_primary_action_bg else R.drawable.crown_config_action_button_bg)
            setBackgroundIcon(
                icon = tintedDrawable(iconRes, textColor),
                sizePx = dp(76),
                insetPx = -dp(8),
                alpha = if (primary) 38 else 32
            )
            setOnClickListener { action() }
        }
    }

    private fun fullWidthButtonParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(8)
        }
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

    private fun tintedDrawable(iconRes: Int, colorRes: Int): Drawable? {
        return ContextCompat.getDrawable(this, iconRes)?.mutate()?.apply {
            setTint(ContextCompat.getColor(this@CrownStoreActivity, colorRes))
        }
    }

    private fun TextView.setStartIcon(iconRes: Int, colorRes: Int, sizeDp: Int) {
        val icon = tintedDrawable(iconRes, colorRes)?.apply {
            val size = dp(sizeDp)
            setBounds(0, 0, size, size)
        }
        setCompoundDrawables(icon, null, null, null)
    }

    private fun TextView.setTopIcon(iconRes: Int, colorRes: Int, sizeDp: Int) {
        val icon = tintedDrawable(iconRes, colorRes)?.apply {
            val size = dp(sizeDp)
            setBounds(0, 0, size, size)
        }
        setCompoundDrawables(null, icon, null, null)
    }

    private class BackgroundIconButton(context: Context) : Button(context) {
        private var backgroundIcon: Drawable? = null
        private var backgroundIconSize = 0
        private var backgroundIconInset = 0
        private var backgroundIconAlpha = 36

        fun setBackgroundIcon(icon: Drawable?, sizePx: Int, insetPx: Int, alpha: Int) {
            backgroundIcon = icon
            backgroundIconSize = sizePx
            backgroundIconInset = insetPx
            backgroundIconAlpha = alpha
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            backgroundIcon?.let { icon ->
                val size = backgroundIconSize.coerceAtLeast(height)
                val left = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                    backgroundIconInset
                } else {
                    width - size - backgroundIconInset
                }
                val top = (height - size) / 2
                icon.alpha = backgroundIconAlpha
                icon.setBounds(left, top, left + size, top + size)
                icon.draw(canvas)
            }
            super.onDraw(canvas)
        }
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

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
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
