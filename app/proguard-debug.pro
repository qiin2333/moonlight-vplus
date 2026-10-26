# AndroidX Test runner loads Kotlin runtime facades reflectively from the target APK.
# Keep them in minified debug builds so on-device instrumentation tests can start.
-keep class kotlin.** { *; }
-dontwarn kotlin.**
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-keep class androidx.compose.** { *; }

# Cross-APK instrumentation tests inspect dialog recreation and focus state.
-keepclassmembers class com.limelight.utils.AboutDialogLauncher {
	*** dialogSnapshot*(...);
}
-keep class com.limelight.utils.AboutDialogLauncher$DialogSnapshot { *; }

# Instrumentation invokes these target-APK symbols after the debug APK is minified.
-keepclassmembers class com.limelight.binding.input.advance_setting.sqlite.SuperConfigDatabaseHelper {
    public java.lang.Object queryElementAttribute(long, long, java.lang.String);
}
-keep class com.limelight.grid.PcCardDecor { *; }
-keep class com.limelight.utils.AppTheme { *; }
-keep class com.limelight.utils.BgAccent { *; }
-keep class com.limelight.gamemenu.TouchPointerSensitivity** { *; }
-keep class com.limelight.gamemenu.TouchPointerPreset** { *; }
-keep class com.limelight.gamemenu.GameMenuCardsKt { *; }
# USB panel instrumentation renders state and exercises controller navigation.
-keep class com.limelight.UsbDevicePanelKt { *; }
-keep class com.limelight.UsbPanelDevice { *; }
-keep class com.limelight.UsbDeviceType** { *; }
-keep class com.limelight.utils.AppActionSheet** { *; }
-keep class com.limelight.ui.ControllerInputKt {
    public static boolean isGamepadNavigationSource(android.view.KeyEvent);
}
# Per-app settings instrumentation verifies the JSON/Intent round trip after R8.
-keep class com.limelight.utils.AppSettingsManager {
    public <init>(android.content.Context);
    public static final com.limelight.utils.AppSettingsManager$Companion Companion;
    public void saveAppLastSettings(java.lang.String, com.limelight.nvstream.http.NvApp, com.limelight.preferences.PreferenceConfiguration);
    public com.limelight.preferences.PreferenceConfiguration getAppLastSettings(java.lang.String, com.limelight.nvstream.http.NvApp);
    public java.lang.String getSettingsSummary(java.lang.String, com.limelight.nvstream.http.NvApp);
    public boolean applyLastSettingsFromIntent(android.content.Intent, com.limelight.preferences.PreferenceConfiguration);
}
-keep class com.limelight.utils.AppSettingsManager$Companion {
    public void addLastSettingsToIntent(android.content.Intent, com.limelight.preferences.PreferenceConfiguration);
}
-keepclassmembers class com.limelight.nvstream.http.NvApp {
    public <init>(java.lang.String, int, boolean);
}
-keepclassmembers class com.limelight.preferences.IconListPreference {
    public int[] getEntryIcons();
}
-keepclassmembers class com.limelight.preferences.DisplayChoicePreference {
    public java.util.Set getEnabledValues();
}

# Virtual-controller instrumentation calls across the target/test APK boundary.
-keep class com.limelight.binding.input.virtual_controller.** { *; }

# Instrumentation exercises constructors and IME metadata across the APK boundary.
-keepclassmembers class com.limelight.ui.StreamView {
    public <init>(...);
    public void setTextInputEnabled(boolean);
    public boolean isTextInputEnabled();
}

# Remote-IME instrumentation constructs the controller via default arguments and
# copies RemoteTextContext fixtures across the APK boundary.
-keep class com.limelight.utils.RemoteImeController { <init>(...); }
-keep class com.limelight.nvstream.RemoteTextContext { *; }

# Display-panel instrumentation constructs state and calls the target APK's
# composables/persistence seam. These symbols must retain their cross-APK ABI.
# Test composables also read Compose-generated $stable fields on these models.
-keep class com.limelight.gamemenu.GameMenuScreenKt {
    public static void GameMenuScreen(...);
}
-keep class com.limelight.gamemenu.GameMenuCallbacks { <init>(...); public static final int $stable; }
-keep class com.limelight.gamemenu.GameMenuComposeUiState { <init>(...); public static final int $stable; }
-keep class com.limelight.gamemenu.GameMenuVisibleCards { <init>(...); public static final int $stable; }
-keep class com.limelight.gamemenu.GameMenuGuideDismissController { <init>(...); public static final int $stable; }
-keep class com.limelight.gamemenu.BitrateCardState { <init>(...); public static final int $stable; }
-keep class com.limelight.gamemenu.DisplayChoice { <init>(...); public static final int $stable; }
-keep class com.limelight.gamemenu.DisplaySettingsDraft { <init>(...); public static final int $stable; }
-keepclassmembers class com.limelight.gamemenu.SensitivityParameter {
    public static final int $stable;
}
-keepclassmembers class com.limelight.preferences.TouchpadInputValues {
    public <init>(...);
}
-keep class com.limelight.gamemenu.AudioHapticsCardState { <init>(...); public static final int $stable; }
-keep class com.limelight.gamemenu.WaveformHapticsCardState { <init>(...); public static final int $stable; }
-keep class com.limelight.gamemenu.GyroCardState { <init>(...); public static final int $stable; }
-keepclassmembers class com.limelight.gamemenu.BitrateCardController {
    public static final com.limelight.gamemenu.BitrateCardController$Companion Companion;
}
-keep class com.limelight.gamemenu.BitrateCardController$Companion {
    *** persistDisplayDraft*(...);
}
-keep class com.limelight.DisplayReconnectSettings** { *; }
-keepclassmembers class com.limelight.preferences.DisplayPreferenceValues {
    public java.util.Map of(...);
}
-keepclassmembers class com.limelight.preferences.PreferenceConfiguration {
    public <init>();
    public *** *Width(...);
    public *** *Height(...);
    public *** *Fps(...);
    public *** *Bitrate(...);
    public *** *Resolution(...);
    public *** *AbrMode(...);
    public *** *ScreenCombinationMode(...);
    public boolean getShowDisplaySettingsCard();
    public void setShowDisplaySettingsCard(boolean);
    public void setResolutionScale(int);
    public boolean getShowAudioHapticsCard();
    public int getPerfOverlayBgOpacity();
}
-keepclassmembers class com.limelight.preferences.PreferenceConfiguration$Companion {
    public int getDefaultBitrate(java.lang.String, java.lang.String);
    public com.limelight.preferences.PreferenceConfiguration readPreferences(android.content.Context, android.view.Display);
}

# Feature-page navigation is exercised by instrumentation after R8.
-keep class com.limelight.preferences.StreamSettings$SettingsFragment {
    public <init>();
    public java.lang.String getActiveSettingsScreenKey();
    public void applySearchFilter(java.lang.String);
    public void openSettingsScreen(java.lang.String, java.lang.String);
    public boolean closeSettingsScreen();
}

# Backup instrumentation exercises the public exporter/importer across APKs.
-keep class com.limelight.utils.ConfigurationSyncManager {
    public <init>(android.content.Context);
    public java.lang.String exportSyncPackage();
    public com.limelight.utils.ConfigurationSyncManager$ImportResult importSyncPackage(java.lang.String, java.lang.String);
}
-keep class com.limelight.utils.ConfigurationSyncManager$ImportResult {
    public int getDefaultPreferencesImported();
    public int getTotalImported();
    public int getCrownProfilesFailed();
    public int getPairingItemsFailed();
}
