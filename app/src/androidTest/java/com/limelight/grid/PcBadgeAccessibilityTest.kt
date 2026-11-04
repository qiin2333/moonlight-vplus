package com.limelight.grid

import android.content.Context
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.PcView
import com.limelight.R
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.preferences.PreferenceConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PcBadgeAccessibilityTest {
    @Test
    fun recycledCardUpdatesAndClearsFoundationDescription() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val application = ApplicationProvider.getApplicationContext<Context>()
            val theme = application.resources.getIdentifier("AppTheme", "style", application.packageName)
            val context = ContextThemeWrapper(application, theme)
            val adapter = PcGridAdapter(context, PreferenceConfiguration())
            val details = ComputerDetails().apply {
                name = "主机 One"
                state = ComputerDetails.State.ONLINE
                sunshineVersion = "Foundation 杂鱼"
            }
            adapter.itemList.add(PcView.ComputerObject(details))
            val parent = FrameLayout(context)
            val card = adapter.getView(0, null, parent)
            val name = card.findViewById<TextView>(R.id.grid_text)
            assertEquals(context.getString(R.string.pc_name_foundation_sunshine_description, details.name), name.contentDescription)

            details.name = "Host Two"
            adapter.getView(0, card, parent)
            assertEquals(context.getString(R.string.pc_name_foundation_sunshine_description, details.name), name.contentDescription)

            details.state = ComputerDetails.State.OFFLINE
            adapter.getView(0, card, parent)
            assertNull(name.contentDescription)

            details.state = ComputerDetails.State.ONLINE
            details.sunshineVersion = "standard"
            adapter.getView(0, card, parent)
            assertNull(name.contentDescription)

            details.sunshineVersion = "Foundation 杂鱼"
            adapter.getView(0, card, parent)
            details.uuid = PcGridAdapter.ADD_COMPUTER_UUID
            adapter.getView(0, card, parent)
            assertNull(name.contentDescription)
        }
    }
}
