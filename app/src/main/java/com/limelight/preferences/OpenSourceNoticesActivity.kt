package com.limelight.preferences

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.limelight.R
import com.limelight.ui.ThemedComponentActivity
import com.limelight.utils.BrowserOnlyLauncher
import com.limelight.utils.UiHelper
import com.limelight.utils.appAccentColor
import org.json.JSONArray
import java.util.Locale

/** User-visible notices bundled with the APK. The manifest and license files are release inputs. */
class OpenSourceNoticesActivity : ThemedComponentActivity() {
    private data class Notice(
        val id: String,
        val name: String,
        val version: String,
        val url: String,
        val license: String,
        val licenseFile: String,
        val credit: String?
    )

    private val notices by lazy {
        val json = assets.open("open_source_notices/notices.json").bufferedReader().use { it.readText() }
        JSONArray(json).let { array ->
            (0 until array.length()).map { index ->
                val entry = array.getJSONObject(index)
                Notice(
                    entry.getString("id"), entry.getString("name"), entry.getString("version"),
                    entry.getString("url"), entry.getString("license"),
                    entry.getString("licenseFile"), entry.optString("credit").takeIf { it.isNotEmpty() }
                )
            }
        }.sortedWith(compareBy<Notice> { it.name.lowercase(Locale.ROOT) }.thenBy { it.id })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiHelper.setLocale(this)
        setContent {
            val dark = isSystemInDarkTheme()
            val base = if (dark) darkColorScheme() else lightColorScheme()
            val colors = base.copy(
                primary = appAccentColor(),
                onPrimary = Color.White,
                background = colorResource(R.color.game_menu_dialog_background),
                onBackground = colorResource(R.color.game_menu_text_primary),
                surface = colorResource(R.color.game_menu_card_background),
                onSurface = colorResource(R.color.game_menu_text_primary),
                onSurfaceVariant = colorResource(R.color.game_menu_text_secondary),
                outline = colorResource(R.color.game_menu_dialog_border)
            )
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            MaterialTheme(colorScheme = colors) {
                var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
                var showLicense by rememberSaveable { mutableStateOf(false) }
                val selected = notices.firstOrNull { it.id == selectedId }
                fun back() {
                    if (showLicense) showLicense = false
                    else if (selectedId != null) selectedId = null
                    else finish()
                }
                BackHandler { back() }
                Surface(modifier = Modifier.fillMaxSize(), color = colors.background) {
                    Column(
                        modifier = Modifier.fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        TextButton(onClick = ::back, modifier = Modifier.heightIn(min = 48.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_arrow_back_24),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(stringResource(R.string.open_source_back))
                            }
                        }
                        val heading = when {
                            showLicense -> R.string.open_source_license_text
                            selected != null -> R.string.open_source_component_details
                            else -> R.string.open_source_title
                        }
                        Text(
                            stringResource(heading),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 14.dp)
                        )
                        if (selected == null) {
                            NoticeList(notices) { selectedId = it.id }
                        } else if (showLicense) {
                            val licenseText = remember(selected.licenseFile) {
                                assets.open("open_source_notices/${selected.licenseFile}")
                                    .bufferedReader().use { it.readText() }
                            }
                            SelectionContainer {
                                Text(
                                    licenseText,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                )
                            }
                        } else {
                            NoticeDetails(selected, onOpenProject = {
                                BrowserOnlyLauncher.open(this@OpenSourceNoticesActivity, selected.url)
                            }, onOpenLicense = { showLicense = true })
                        }
                    }
                }
            }
        }
        UiHelper.notifyNewRootView(this)
    }

    @Composable
    private fun NoticeList(entries: List<Notice>, onSelect: (Notice) -> Unit) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                stringResource(R.string.open_source_intro),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
            entries.forEach { entry ->
                Surface(
                    onClick = { onSelect(entry) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp
                ) {
                    Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(entry.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                entry.license,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text("›", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }

    @Composable
    private fun NoticeDetails(
        notice: Notice,
        onOpenProject: () -> Unit,
        onOpenLicense: () -> Unit
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(notice.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.open_source_version, notice.version))
            Text(stringResource(R.string.open_source_license, notice.license))
            notice.credit?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            OutlinedButton(onClick = onOpenProject, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.open_source_project, notice.url))
            }
            OutlinedButton(onClick = onOpenLicense, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.open_source_license_text))
            }
        }
    }

    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, OpenSourceNoticesActivity::class.java))
        }
    }
}
