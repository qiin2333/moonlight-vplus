package com.limelight.computers

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException

import com.limelight.LimeLog
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvHTTP

import org.json.JSONException
import org.json.JSONObject

import java.io.ByteArrayInputStream
import java.security.cert.CertificateEncodingException
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.LinkedList
import java.util.Locale

/**
 * Database manager for computer information.
 * Uses SQLiteOpenHelper for proper version management and migration handling.
 */
class ComputerDatabaseManager(c: Context) {

    private interface AddressFields {
        companion object {
            const val LOCAL = "local"
            const val REMOTE = "remote"
            const val MANUAL = "manual"
            const val IPv6 = "ipv6"
            const val IPv6_DISABLED = "ipv6Disabled"
            const val ADDRESS = "address"
            const val PORT = "port"
        }
    }

    private val dbHelper: ComputerDatabaseHelper = ComputerDatabaseHelper(c)
    private val computerDb: SQLiteDatabase = dbHelper.writableDatabase

    init {
        applyPendingMigrations()
    }

    fun close() {
        computerDb.close()
        dbHelper.close()
    }

    /**
     * Apply pending migrations from legacy database files using a transaction for better performance.
     */
    private fun applyPendingMigrations() {
        val pendingMigrations = dbHelper.getPendingMigrations()
        if (pendingMigrations.isEmpty()) {
            return
        }

        LimeLog.info("Migrating ${pendingMigrations.size} computers from legacy databases")

        computerDb.beginTransaction()
        try {
            for (computer in pendingMigrations) {
                updateComputer(computer)
            }
            computerDb.setTransactionSuccessful()
            LimeLog.info("Successfully migrated ${pendingMigrations.size} computers")
        } finally {
            computerDb.endTransaction()
        }
    }

    fun deleteComputer(details: ComputerDetails) {
        computerDb.delete(COMPUTER_TABLE_NAME, "$COMPUTER_UUID_COLUMN_NAME=?", arrayOf(details.uuid))
    }

    fun updateComputer(details: ComputerDetails): Boolean {
        val values = ContentValues()
        values.put(COMPUTER_UUID_COLUMN_NAME, details.uuid)
        values.put(COMPUTER_NAME_COLUMN_NAME, details.name)

        try {
            val addresses = JSONObject()
            addresses.put(AddressFields.LOCAL, tupleToJson(details.localAddress))
            addresses.put(AddressFields.REMOTE, tupleToJson(details.remoteAddress))
            addresses.put(AddressFields.MANUAL, tupleToJson(details.manualAddress))
            addresses.put(AddressFields.IPv6, tupleToJson(details.ipv6Address))
            addresses.put(AddressFields.IPv6_DISABLED, details.ipv6Disabled)
            values.put(ADDRESSES_COLUMN_NAME, addresses.toString())
        } catch (e: JSONException) {
            throw RuntimeException(e)
        }

        values.put(MAC_ADDRESS_COLUMN_NAME, details.macAddress)
        try {
            if (details.serverCert != null) {
                values.put(SERVER_CERT_COLUMN_NAME, details.serverCert!!.encoded)
            } else {
                values.put(SERVER_CERT_COLUMN_NAME, null as ByteArray?)
            }
        } catch (e: CertificateEncodingException) {
            values.put(SERVER_CERT_COLUMN_NAME, null as ByteArray?)
            e.printStackTrace()
        }
        return -1L != computerDb.insertWithOnConflict(
            COMPUTER_TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun getComputerFromCursor(c: android.database.Cursor): ComputerDetails {
        val details = ComputerDetails()

        details.uuid = c.getString(0)
        details.name = c.getString(1)
        try {
            val addresses = JSONObject(c.getString(2))
            details.localAddress = tupleFromJson(addresses, AddressFields.LOCAL)
            details.remoteAddress = tupleFromJson(addresses, AddressFields.REMOTE)
            details.manualAddress = tupleFromJson(addresses, AddressFields.MANUAL)
            details.ipv6Address = tupleFromJson(addresses, AddressFields.IPv6)
            details.ipv6Disabled = addresses.optBoolean(AddressFields.IPv6_DISABLED, false)
        } catch (e: JSONException) {
            throw RuntimeException(e)
        }

        details.externalPort = details.remoteAddress?.port ?: NvHTTP.DEFAULT_HTTP_PORT
        details.macAddress = c.getString(3)

        try {
            val derCertData = c.getBlob(4)
            if (derCertData != null) {
                details.serverCert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(derCertData)) as X509Certificate
            }
        } catch (_: CertificateException) {
        }

        details.state = ComputerDetails.State.UNKNOWN

        return details
    }

    fun getAllComputers(): List<ComputerDetails> {
        computerDb.rawQuery("SELECT * FROM $COMPUTER_TABLE_NAME", null).use { c ->
            val computerList = LinkedList<ComputerDetails>()
            while (c.moveToNext()) {
                computerList.add(getComputerFromCursor(c))
            }
            return computerList
        }
    }

    fun getComputerByName(name: String): ComputerDetails? {
        computerDb.query(
            COMPUTER_TABLE_NAME, null, "$COMPUTER_NAME_COLUMN_NAME=?",
            arrayOf(name), null, null, null
        ).use { c ->
            if (!c.moveToFirst()) {
                return null
            }
            return getComputerFromCursor(c)
        }
    }

    fun getComputerByUUID(uuid: String): ComputerDetails? {
        computerDb.query(
            COMPUTER_TABLE_NAME, null, "$COMPUTER_UUID_COLUMN_NAME=?",
            arrayOf(uuid), null, null, null
        ).use { c ->
            if (!c.moveToFirst()) {
                return null
            }
            return getComputerFromCursor(c)
        }
    }

    companion object {
        private const val COMPUTER_TABLE_NAME = "Computers"
        private const val COMPUTER_UUID_COLUMN_NAME = "UUID"
        private const val COMPUTER_NAME_COLUMN_NAME = "ComputerName"
        private const val ADDRESSES_COLUMN_NAME = "Addresses"
        private const val MAC_ADDRESS_COLUMN_NAME = "MacAddress"
        private const val SERVER_CERT_COLUMN_NAME = "ServerCert"

        @Throws(JSONException::class)
        fun tupleToJson(tuple: ComputerDetails.AddressTuple?): JSONObject? {
            if (tuple == null) {
                return null
            }
            val json = JSONObject()
            json.put(AddressFields.ADDRESS, tuple.address)
            json.put(AddressFields.PORT, tuple.port)
            return json
        }

        @Throws(JSONException::class)
        fun tupleFromJson(json: JSONObject, name: String): ComputerDetails.AddressTuple? {
            if (!json.has(name)) {
                return null
            }
            val address = json.getJSONObject(name)
            return ComputerDetails.AddressTuple(
                address.getString(AddressFields.ADDRESS), address.getInt(AddressFields.PORT)
            )
        }
    }
}
