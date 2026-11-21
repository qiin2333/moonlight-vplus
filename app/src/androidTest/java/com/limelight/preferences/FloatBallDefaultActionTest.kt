package com.limelight.preferences

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FloatBallDefaultActionTest {
    @Test
    fun longPressDefaultsToNoneButPreservesSavedAction() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val namespace = "float-ball-default-test-${UUID.randomUUID()}"
        val names = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolatedName = "$namespace.$name"
                names.add(isolatedName)
                return base.getSharedPreferences(isolatedName, mode)
            }
        }
        try {
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            assertEquals("none", PreferenceConfiguration.readPreferences(context).floatBallLongClickAction)
            preferences.edit().putString("list_float_ball_long_click_action", "toggle_visibility").commit()
            assertEquals("toggle_visibility", PreferenceConfiguration.readPreferences(context).floatBallLongClickAction)
        } finally {
            names.forEach { base.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        }
    }
}
