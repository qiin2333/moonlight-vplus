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
