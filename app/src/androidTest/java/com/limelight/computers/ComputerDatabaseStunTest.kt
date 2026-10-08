package com.limelight.computers

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.nvstream.http.ComputerDetails
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComputerDatabaseStunTest {
    private lateinit var context: IsolatedContext
    private lateinit var manager: ComputerDatabaseManager

    @Before fun setUp() {
        context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        manager = ComputerDatabaseManager(context)
    }

    @After fun tearDown() {
        manager.close()
        context.cleanup()
    }

    private fun host(): ComputerDetails = ComputerDetails().apply {
        uuid = UUID.randomUUID().toString()
        name = "STUN test"
        localAddress = ComputerDetails.AddressTuple("192.0.2.1", 47989)
        activeAddress = localAddress
        httpsPort = 47984
    }.also { assertTrue(manager.updateComputer(it)) }

    @Test fun savesAndReloadsOnlyTheRemoteAddress() {
        val host = host()
        val remote = ComputerDetails.AddressTuple("203.0.113.1", 50000)
        assertEquals(remote, manager.saveStunAddress(host.uuid!!, remote))
        manager.close()
        manager = ComputerDatabaseManager(context)
        val reloaded = manager.getComputerByUUID(host.uuid!!)!!
        assertEquals(remote, reloaded.remoteAddress)
        assertEquals(host.name, reloaded.name)
        assertEquals(host.localAddress, reloaded.localAddress)
        assertEquals(host.activeAddress, reloaded.activeAddress)
        assertEquals(host.httpsPort, reloaded.httpsPort)
    }

    @Test fun doesNotReplaceAnExistingRemoteAddress() {
        val host = host()
        val existing = ComputerDetails.AddressTuple("203.0.113.2", 50001)
        host.remoteAddress = existing
        manager.updateComputer(host)
        assertEquals(existing, manager.saveStunAddress(host.uuid!!,
            ComputerDetails.AddressTuple("203.0.113.1", 50000)))
        assertEquals(existing, manager.getComputerByUUID(host.uuid!!)!!.remoteAddress)
    }

    @Test fun lateResultCannotRecreateDeletedHost() {
        val host = host()
        manager.deleteComputer(host)
        assertNull(manager.saveStunAddress(host.uuid!!, ComputerDetails.AddressTuple("203.0.113.1", 50000)))
        assertNull(manager.getComputerByUUID(host.uuid!!))
    }

    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        private val prefix = "stun-test-${UUID.randomUUID()}-"
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
