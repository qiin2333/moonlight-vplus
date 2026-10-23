package com.limelight.preferences

import android.annotation.SuppressLint
import android.os.Build
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceViewHolder
import com.limelight.R
import com.limelight.utils.AppTheme

internal fun isSettingsExpandButton(preference: Preference?): Boolean =
    preference?.javaClass?.name == "androidx.preference.ExpandButton"

/** Restyles AndroidX's expansion row while retaining its click, state and stable ID. */
@SuppressLint("RestrictedApi")
internal class SettingsPreferenceGroupAdapter(group: PreferenceGroup) : PreferenceGroupAdapter(group) {
    override fun getItemViewType(position: Int): Int {
        getItem(position)?.takeIf(::isSettingsExpandButton)?.layoutResource = R.layout.preference_expand_settings
        return super.getItemViewType(position)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        if (isSettingsExpandButton(getItem(position))) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                holder.itemView.defaultFocusHighlightEnabled = false
            }
            (holder.findViewById(android.R.id.title) as? TextView)?.setText(R.string.settings_show_more)
            // Accent changes rebind existing holders, so resolve drawables with the current palette.
            val context = AppTheme.paletteContext(holder.itemView.context)
            holder.findViewById(R.id.settings_expand_surface)?.background =
                ResourcesCompat.getDrawable(
                    context.resources, R.drawable.preference_expand_settings_background, context.theme
                )
            (holder.findViewById(R.id.settings_expand_icon) as? ImageView)?.apply {
                background = ResourcesCompat.getDrawable(
                    context.resources, R.drawable.preference_expand_settings_icon_background, context.theme
                )
                setImageDrawable(ResourcesCompat.getDrawable(
                    context.resources, R.drawable.ic_expand_more_accent, context.theme
                ))
            }
            holder.isDividerAllowedAbove = false
            holder.isDividerAllowedBelow = false
        }
    }
}
