package com.limelight.preferences

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
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
import com.limelight.utils.ConfigurationSyncScheduler
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import kotlin.concurrent.thread

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

            val crown = ImageView(this@CrownStoreActivity).apply {
                setImageResource(R.drawable.ic_super_crown)
                contentDescription = null
            }
            addView(
                crown,
                LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                    marginStart = dp(6)
                    marginEnd = dp(9)
                }
            )

            val title = TextView(this@CrownStoreActivity).apply {
                text = getString(R.string.title_crown_store_view)
                textSize = 23f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ContextCompat.getColor(this@CrownStoreActivity, R.color.crown_text_primary))
            }
            addView(
                title,
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(10), dp(14), dp(10), 0)
            }
        }
    }

    private fun createTabs(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.crown_store_bottom_nav_bg)
            setPadding(dp(12), dp(7), dp(12), dp(8))

            storeTabView = createTabView(R.string.crown_store_tab_store, R.drawable.crown_store_tab_store_icon) {
                selectTab(CrownTab.STORE)
            }
            mineTabView = createTabView(R.string.crown_store_tab_mine, R.drawable.crown_store_tab_mine_icon) {
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
        updateTabStyle(storeTabView, selectedTab == CrownTab.STORE)
        updateTabStyle(mineTabView, selectedTab == CrownTab.MINE)
    }

    private fun updateTabStyle(view: TextView, selected: Boolean) {
        val textColor = if (selected) R.color.crown_accent else R.color.crown_text_secondary
        view.setTextColor(ContextCompat.getColor(this, textColor))
        view.setBackgroundResource(if (selected) R.drawable.crown_store_tab_selected_bg else R.drawable.crown_store_tab_idle_bg)
        (view.tag as? Int)?.let { iconRes ->
            view.setTopIcon(iconRes, textColor, 20)
        }
    }

    private fun renderStoreTab() {
        contentView.removeAllViews()
        contentView.addView(headerText(R.string.crown_store_tab_store))
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
            secondaryActionButton(R.string.crown_share_action_import_url, R.drawable.ic_link) {
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
            else -> storeProfiles!!.forEach { profile ->
                contentView.addView(storeProfileView(profile))
            }
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
        return cardLayout().apply {
            addView(iconTitleRow(R.drawable.ic_super_crown, profile.name))
            val details = listOf(profile.game, profile.author)
                .filter { it.isNotBlank() }
                .joinToString(" - ")
            if (details.isNotBlank()) {
                addView(metaText(details))
            }
            if (profile.summary.isNotBlank()) {
                addView(bodyText(profile.summary))
            }
            if (profile.tags.isNotEmpty()) {
                addView(metaText(profile.tags.joinToString(prefix = "#", separator = " #")))
            }
            if (profile.updatedAt.isNotBlank()) {
                addView(metaText(getString(R.string.crown_store_updated_at, profile.updatedAt)))
            }
            addView(
                primaryActionButton(R.string.crown_store_action_import_profile, R.drawable.phc_action_plus) {
                    importStoreProfile(profile)
                },
                fullWidthButtonParams()
            )
        }
    }

    private fun importStoreProfile(profile: CrownProfileShareManager.StoreProfile) {
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

    private fun renderMineTab() {
        contentView.removeAllViews()
        contentView.addView(headerText(R.string.crown_store_tab_mine))
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
            secondaryActionButton(R.string.crown_share_action_import_url, R.drawable.ic_link) {
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
            profiles.forEach { profile ->
                contentView.addView(localProfileView(profile))
            }
        }

        contentView.addView(sectionLabel(R.string.crown_store_local_tools))
        contentView.addView(
            secondaryActionButton(R.string.crown_config_action_import_legacy, R.drawable.ic_export) {
                openLegacyImportDocument()
            },
            fullWidthButtonParams()
        )
    }

    private fun localProfileView(profile: LocalCrownProfile): View {
        return cardLayout().apply {
            addView(iconTitleRow(R.drawable.ic_list_view, profile.name))
            addView(metaText(getString(R.string.crown_store_local_profile_id, profile.id)))
            addView(
                primaryActionButton(R.string.crown_store_action_publish_profile, R.drawable.ic_super_crown) {
                    showCrownStorePublishMetadataDialog(profile.id, profile.name)
                },
                fullWidthButtonParams()
            )
            addView(
                actionButtonRow(
                    secondaryActionButton(R.string.crown_store_action_export_share, R.drawable.ic_export) {
                        exportCrownSharePackage(profile.id, profile.name)
                    },
                    secondaryActionButton(R.string.crown_store_action_export_legacy_short, R.drawable.ic_export) {
                        exportLegacyConfig(profile.id, profile.name)
                    }
                )
            )
            addView(
                secondaryActionButton(R.string.crown_store_action_merge_short, R.drawable.ic_change) {
                    openLegacyMergeDocument(profile.id)
                },
                fullWidthButtonParams()
            )
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
            appVersionName = packageInfo.versionName ?: ""
        )
    }

    private fun showCrownStorePublishMetadataDialog(configId: String, defaultName: String) {
        if (!GitHubStarVerifier.isConfigured()) {
            Toast.makeText(this, R.string.toast_developer_oauth_unconfigured, Toast.LENGTH_LONG).show()
            return
        }
        val accessToken = PreferenceManager.getDefaultSharedPreferences(this)
            .getString(DeveloperUnlockSettings.PREF_ACCESS_TOKEN, null)
        if (accessToken.isNullOrBlank()) {
            showCrownStoreGitHubAuthorizationRequiredDialog(clearSavedToken = false)
            return
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
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

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = dp(22)
            setPadding(inset, 0, inset, 0)
            addCrownStorePublishField(R.string.label_crown_store_profile_name, nameInput)
            addCrownStorePublishField(R.string.label_crown_store_game, gameInput)
            addCrownStorePublishField(R.string.label_crown_store_author, authorInput)
            addCrownStorePublishField(R.string.label_crown_store_tags, tagsInput)
            addCrownStorePublishField(R.string.label_crown_store_summary, summaryInput)
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

                val game = gameInput.text?.toString().orEmpty().trim()
                val author = authorInput.text?.toString().orEmpty().trim()
                val summary = summaryInput.text?.toString().orEmpty().trim()
                val tags = tagsInput.text?.toString().orEmpty()
                    .split(Regex("[,\\s\\uFF0C]+"))
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()

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
        if (accessToken.isNullOrBlank()) {
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
                startDeveloperUnlockVerification()
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

    private fun startDeveloperUnlockVerification() {
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
                val deviceCode = GitHubStarVerifier.requestDeviceCode()
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
            .setTitle(R.string.title_developer_unlock)
            .setMessage(
                getString(
                    R.string.message_developer_device_code,
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
                            GitHubStarVerifier.checkStar(poll.accessToken)
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
        starCheck: GitHubStarVerifier.StarCheck
    ) {
        developerUnlockVerificationRunning = false
        clearDeveloperPendingDeviceCode(ctx)
        GitHubDeviceAuthorization.saveAuthorizedAccount(ctx, accessToken, starCheck)
        mainHandler.post {
            developerDeviceCodeDialog?.dismiss()
            if (starCheck.starred) {
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
                ?.let { "Link: $it" }
        }.getOrNull() ?: "Link"
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
        )

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
        AlertDialog.Builder(this, R.style.AppDialogStyle)
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
            .show()
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
        state.addView(iconTitleRow(R.drawable.ic_info, title))
        state.addView(bodyText(message))
        state.addView(secondaryActionButton(buttonText, R.drawable.phc_action_reset, buttonAction), fullWidthButtonParams())
        contentView.addView(state)
    }

    private fun headerText(textRes: Int): TextView {
        return TextView(this).apply {
            text = getString(textRes)
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(this@CrownStoreActivity, R.color.crown_text_primary))
        }
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

    private fun primaryActionButton(textRes: Int, iconRes: Int, action: () -> Unit): Button {
        return actionButton(getString(textRes), iconRes, primary = true, action = action)
    }

    private fun primaryActionButton(text: String, iconRes: Int, action: () -> Unit): Button {
        return actionButton(text, iconRes, primary = true, action = action)
    }

    private fun secondaryActionButton(textRes: Int, iconRes: Int, action: () -> Unit): Button {
        return actionButton(getString(textRes), iconRes, primary = false, action = action)
    }

    private fun secondaryActionButton(text: String, iconRes: Int, action: () -> Unit): Button {
        return actionButton(text, iconRes, primary = false, action = action)
    }

    private fun actionButton(text: String, iconRes: Int, primary: Boolean, action: () -> Unit): Button {
        val textColor = if (primary) R.color.app_dialog_title_color else R.color.crown_text_primary
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 13.5f
            minHeight = dp(40)
            minWidth = 0
            includeFontPadding = false
            compoundDrawablePadding = dp(6)
            setPadding(dp(10), 0, dp(10), 0)
            setTextColor(ContextCompat.getColor(this@CrownStoreActivity, textColor))
            setBackgroundResource(if (primary) R.drawable.crown_store_primary_action_bg else R.drawable.crown_config_action_button_bg)
            setStartIcon(iconRes, textColor, 19)
            setOnClickListener { action() }
        }
    }

    private fun actionButtonRow(left: Button, right: Button): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                left,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    topMargin = dp(8)
                    marginEnd = dp(5)
                }
            )
            addView(
                right,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    topMargin = dp(8)
                    marginStart = dp(5)
                }
            )
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
    }
}
