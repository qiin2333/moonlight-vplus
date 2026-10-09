package com.limelight.computers

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.utils.ConfigurationSyncManager
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StunAddressPersistenceTest {
    private lateinit var context: IsolatedContext
    private lateinit var manager: ComputerDatabaseManager

    @Before fun setup() {
        context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        manager = ComputerDatabaseManager(context)
    }

    @After fun cleanup() {
        manager.close()
        context.cleanup()
    }

    private fun host() = ComputerDetails().apply {
        uuid = UUID.randomUUID().toString()
        name = "STUN origin test"
        updateStunRemoteAddress(ComputerDetails.AddressTuple("203.0.113.1", 47989))
    }

    @Test fun databaseReloadPreservesOriginAndRefreshesDiscoveredAddress() {
        val details = host()
        assertTrue(manager.updateComputer(details))
        manager.close()
        manager = ComputerDatabaseManager(context)
        val reloaded = manager.getComputerByUUID(details.uuid!!)!!
        assertTrue(reloaded.remoteAddressFromStun)
        reloaded.updateStunRemoteAddress(ComputerDetails.AddressTuple("203.0.113.2", 50000))
        assertTrue(manager.updateComputer(reloaded))
        assertEquals(reloaded.remoteAddress, manager.getComputerByUUID(details.uuid!!)!!.remoteAddress)
        reloaded.update(ComputerDetails().apply {
            uuid = reloaded.uuid
            name = reloaded.name
            remoteAddress = reloaded.remoteAddress
        })
        assertTrue(manager.updateComputer(reloaded))
        assertFalse(manager.getComputerByUUID(details.uuid!!)!!.remoteAddressFromStun)
    }

    @Test fun backupRoundTripPreservesOriginAndOldRecordsStayProtected() {
        val sync = ConfigurationSyncManager(context)
        val encode = sync.javaClass.getDeclaredMethod("encodeComputer", ComputerDetails::class.java, String::class.java)
            .apply { isAccessible = true }
        val decode = sync.javaClass.getDeclaredMethod("decodeComputer", JSONObject::class.java)
            .apply { isAccessible = true }
        val encoded = encode.invoke(sync, host(), "") as JSONObject
        val restored = decode.invoke(sync, encoded) as ComputerDetails
        assertTrue(restored.remoteAddressFromStun)
        assertTrue(restored.canRefreshRemoteAddressWithStun)
        assertTrue(manager.updateComputer(restored))
        assertTrue(manager.getComputerByUUID(restored.uuid!!)!!.remoteAddressFromStun)
        encoded.remove("remoteAddressFromStun")
        val legacy = decode.invoke(sync, encoded) as ComputerDetails
        assertFalse(legacy.remoteAddressFromStun)
        assertFalse(legacy.canRefreshRemoteAddressWithStun)
        assertEquals(restored.remoteAddress, legacy.remoteAddress)
    }

    @Test fun syncComparisonIncludesAddressOrigin() {
        val sync = ConfigurationSyncManager(context)
        val encode = sync.javaClass.getDeclaredMethod("encodeComputer", ComputerDetails::class.java, String::class.java)
            .apply { isAccessible = true }
        val discovered = encode.invoke(sync, host(), "") as JSONObject
        val reported = JSONObject(discovered.toString()).put("remoteAddressFromStun", false)
        val normalize = ConfigurationSyncManager.Companion::class.java
            .getDeclaredMethod("normalizedPairingComputerCore", JSONObject::class.java).apply { isAccessible = true }
        val discoveredCore = normalize.invoke(ConfigurationSyncManager.Companion, discovered) as JSONObject
        val reportedCore = normalize.invoke(ConfigurationSyncManager.Companion, reported) as JSONObject
        assertTrue(discoveredCore.getBoolean("remoteAddressFromStun"))
        assertFalse(reportedCore.getBoolean("remoteAddressFromStun"))
        assertNotEquals(discoveredCore.toString(), reportedCore.toString())
    }

    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        private val prefix = "stun-origin-test-${UUID.randomUUID()}-"
        private val directory = File(base.cacheDir, prefix).apply { mkdirs() }
        private val preferenceNames = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File = File(directory, name)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = prefix + name
            preferenceNames.add(isolatedName)
            return baseContext.getSharedPreferences(isolatedName, mode)
        }
        fun cleanup() {
            preferenceNames.forEach { baseContext.getSharedPreferences(it, MODE_PRIVATE).edit().clear().commit() }
            directory.deleteRecursively()
        }
    }
}
