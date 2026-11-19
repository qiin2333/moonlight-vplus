package com.limelight.preferences

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import androidx.compose.foundation.isSystemInDarkTheme
import com.limelight.R

/**
 * Shared design tokens for the Crown Store Compose surfaces.
 *
 * Crown uses the same shell and dialog roles as the rest of the app so the
 * store remains legible when it is opened from a streamed Crown session.
 */
@Composable
internal fun CrownTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val primary = colorResource(R.color.ui_shell_accent)
    val onPrimary = colorResource(R.color.crown_on_accent)
    val background = colorResource(R.color.settings_page_background)
    val surface = colorResource(R.color.ui_shell_surface_elevated)
    val surfaceVariant = colorResource(R.color.crown_surface_raised)
    val outline = colorResource(R.color.ui_shell_outline)
    val onSurface = colorResource(R.color.ui_shell_text_primary)
    val onSurfaceVariant = colorResource(R.color.ui_shell_text_secondary)

    val colors = if (dark) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = colorResource(R.color.ui_shell_accent_soft),
            onPrimaryContainer = onSurface,
            background = background,
            onBackground = onSurface,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            outlineVariant = colorResource(R.color.ui_shell_outline_strong)
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = colorResource(R.color.ui_shell_accent_soft),
            onPrimaryContainer = onSurface,
            background = background,
            onBackground = onSurface,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            outlineVariant = colorResource(R.color.ui_shell_outline_strong)
        )
    }

    MaterialTheme(colorScheme = colors, content = content)
}
