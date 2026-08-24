package com.limelight.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FloatBallPositionStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Before
    fun clearBeforeTest() {
        preferences.edit().clear().commit()
    }

    @After
    fun clearAfterTest() {
        preferences.edit().clear().commit()
    }

    @Test
    fun savingNormalizedPositionReplacesLegacyPixels() {
        preferences.edit()
            .putInt("lastX", 900)
            .putInt("lastY", 300)
            .putBoolean("isHalfShow", true)
            .commit()
        val store = FloatBallPositionStore(context)
        assertEquals(FloatBallLegacyPosition(900, 300), store.legacyPosition())

        val expected = FloatBallStoredPosition(
            FloatingButtonNormalizedPosition(0.8f, 0.3f),
            FloatBallEdge.RIGHT
        )
        store.saveCustom(expected, halfShown = true)

        assertEquals(expected, store.customPosition())
        assertEquals(true, store.isHalfShown())
        assertNull(store.legacyPosition())
    }

    @Test
    fun clearingCustomPositionAlsoClearsHalfShownState() {
        val store = FloatBallPositionStore(context)
        store.saveCustom(
            FloatBallStoredPosition(
                FloatingButtonNormalizedPosition(0.2f, 0.7f),
                FloatBallEdge.LEFT
            ),
            halfShown = true
        )

        store.clearCustomPosition()

        assertNull(store.customPosition())
        assertEquals(false, store.isHalfShown())
    }

    private companion object {
        const val PREFERENCES_NAME = "FloatBallPrefs"
    }
}
