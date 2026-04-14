package com.limelight.computers

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

import com.limelight.LimeLog
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvHTTP

import org.json.JSONException
import org.json.JSONObject

import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.LinkedList
import java.util.Locale

/**
 * SQLiteOpenHelper implementation for managing computer database with proper version management.
 * This class consolidates all legacy database migrations into a single upgrade path.
 *
 * Database Version History:
 * - Version 1 (computers.db): Original schema with byte blob addresses
 * - Version 2 (computers2.db): Added serverCert, separate address fields
 * - Version 3 (computers3.db): Combined addresses with delimiters, added IPv6
 * - Version 4 (computers4.db): JSON format for addresses (current)
 */
class ComputerDatabaseHelper(private val context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    private var pendingMigrations: List<ComputerDetails>? = null
    private var migrationCollected = false

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(String.format(null as Locale?,
            "CREATE TABLE IF NOT EXISTS %s(%s TEXT PRIMARY KEY, %s TEXT NOT NULL, %s TEXT NOT NULL, %s TEXT, %s BLOB)",
            TABLE_NAME, COL_UUID, COL_NAME, COL_ADDRESSES, COL_MAC, COL_CERT))
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        LimeLog.info("Upgrading database from version $oldVersion to $newVersion")
    }

    /**
     * Lazily collect and return pending migrations from legacy databases.
     * This is called once and then cleared.
     */
    @Synchronized
    fun getPendingMigrations(): List<ComputerDetails> {
        if (!migrationCollected) {
            pendingMigrations = collectAllLegacyData()
            migrationCollected = true
        }

        val result = pendingMigrations
        pendingMigrations = null
        return result ?: LinkedList()
    }

    /**
     * Check if there are any legacy databases to migrate.
     * This is a fast check that doesn't read any data.
     */
    fun hasLegacyDatabases(): Boolean {
        val v1File = context.getDatabasePath(DATABASE_NAME)
        if (v1File.exists() && isLegacyV1Schema(v1File)) {
            return true
        }

        for (legacyDb in LEGACY_DB_FILES) {
            if (context.getDatabasePath(legacyDb).exists()) {
                return true
            }
        }
        return false
    }

    // ======================== Migration Collection ========================

    private fun collectAllLegacyData(): List<ComputerDetails> {
        val allComputers = LinkedList<ComputerDetails>()

        allComputers.addAll(migrateLegacyDb(DATABASE_NAME, ::isLegacyV1Schema, ::parseV1Cursor, true))
        allComputers.addAll(migrateLegacyDb("computers2.db", null, ::parseV2Cursor, true))
        allComputers.addAll(migrateLegacyDb("computers3.db", null, ::parseV3Cursor, true))
        allComputers.addAll(migrateLegacyDb("computers4.db", null, ::parseV4Cursor, true))

        if (allComputers.isNotEmpty()) {
            LimeLog.info("Collected ${allComputers.size} computers from legacy databases")
        }

        return allComputers
    }

    private fun migrateLegacyDb(
        dbName: String,
        schemaChecker: ((java.io.File) -> Boolean)?,
        parser: (Cursor) -> ComputerDetails?,
        deleteAfter: Boolean
    ): List<ComputerDetails> {
        val dbFile = context.getDatabasePath(dbName)
        if (!dbFile.exists()) {
            return LinkedList()
        }

        val computers = LinkedList<ComputerDetails>()

        try {
            SQLiteDatabase.openDatabase(
                dbFile.path, null, SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                if (schemaChecker != null && !schemaChecker(dbFile)) {
                    return computers
                }

                db.rawQuery("SELECT * FROM $TABLE_NAME", null).use { c ->
                    while (c.moveToNext()) {
                        val details = parser(c)
                        if (details?.uuid != null) {
                            computers.add(details)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            LimeLog.warning("Failed to migrate $dbName: ${e.message}")
        }

        if (deleteAfter && computers.isNotEmpty()) {
            context.deleteDatabase(dbName)
            LimeLog.info("Deleted legacy database: $dbName")
        }

        return computers
    }

    // ======================== Schema Checker ========================

    private fun isLegacyV1Schema(dbFile: java.io.File): Boolean {
        try {
            SQLiteDatabase.openDatabase(
                dbFile.path, null, SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                db.rawQuery("PRAGMA table_info($TABLE_NAME)", null).use { cursor ->
                    var hasOldNameColumn = false
                    var hasNewAddressesColumn = false

                    while (cursor.moveToNext()) {
                        val colName = cursor.getString(1)
                        if ("name".equals(colName, ignoreCase = true)) hasOldNameColumn = true
                        if ("Addresses".equals(colName, ignoreCase = true)) hasNewAddressesColumn = true
                    }

                    return hasOldNameColumn && !hasNewAddressesColumn
                }
            }
        } catch (_: Exception) {
            return false
        }
    }

    // ======================== Cursor Parsers ========================

    private fun parseV1Cursor(c: Cursor): ComputerDetails? {
        return try {
            ComputerDetails().apply {
                name = c.getString(0)
                uuid = c.getString(1)
                localAddress = parseV1Address(c, 2, name!!, "local")
                remoteAddress = parseV1Address(c, 3, name!!, "remote")
                manualAddress = remoteAddress
                macAddress = c.getString(4)
                state = ComputerDetails.State.UNKNOWN
            }
        } catch (e: Exception) {
            LimeLog.severe("Failed to parse V1 computer: ${e.message}")
            null
        }
    }

    private fun parseV1Address(c: Cursor, col: Int, name: String, type: String): ComputerDetails.AddressTuple? {
        try {
            val blob = c.getBlob(col)
            if (blob != null) {
                val addr = InetAddress.getByAddress(blob).hostAddress ?: return null
                LimeLog.warning("DB: Legacy $type address (blob) for $name")
                return ComputerDetails.AddressTuple(addr, NvHTTP.DEFAULT_HTTP_PORT)
            }
        } catch (_: UnknownHostException) {
        } catch (_: IllegalStateException) {
        }

        val str = c.getString(col)
        if (str != null && str.startsWith(V1_ADDRESS_PREFIX)) {
            return ComputerDetails.AddressTuple(
                str.substring(V1_ADDRESS_PREFIX.length),
                NvHTTP.DEFAULT_HTTP_PORT
            )
        }
        return null
    }

    private fun parseV2Cursor(c: Cursor): ComputerDetails? {
        return try {
            ComputerDetails().apply {
                uuid = c.getString(0)
                name = c.getString(1)
                localAddress = tupleFromString(c.getString(2))
                remoteAddress = tupleFromString(c.getString(3))
                manualAddress = tupleFromString(c.getString(4))
                macAddress = c.getString(5)
                serverCert = parseCertificate(c, 6)
                state = ComputerDetails.State.UNKNOWN
            }
        } catch (e: Exception) {
            LimeLog.severe("Failed to parse V2 computer: ${e.message}")
            null
        }
    }

    private fun parseV3Cursor(c: Cursor): ComputerDetails? {
        return try {
            val details = ComputerDetails()
            details.uuid = c.getString(0)
            details.name = c.getString(1)

            val addrs = c.getString(2).split(V3_ADDR_DELIM.toString(), limit = -1).toTypedArray()
            details.localAddress = parseV3Address(addrs, 0)
            details.remoteAddress = parseV3Address(addrs, 1)
            details.manualAddress = parseV3Address(addrs, 2)
            details.ipv6Address = parseV3Address(addrs, 3)

            details.externalPort = details.remoteAddress?.port ?: NvHTTP.DEFAULT_HTTP_PORT
            details.macAddress = c.getString(3)
            details.serverCert = parseCertificate(c, 4)
            details.state = ComputerDetails.State.UNKNOWN
            details
        } catch (e: Exception) {
            LimeLog.severe("Failed to parse V3 computer: ${e.message}")
            null
        }
    }

    private fun parseV3Address(addrs: Array<String>, index: Int): ComputerDetails.AddressTuple? {
        if (index >= addrs.size || addrs[index].isEmpty()) return null
        val parts = addrs[index].split(V3_PORT_DELIM.toString(), limit = -1).toTypedArray()
        val port = if (parts.size > 1) parts[1].toInt() else NvHTTP.DEFAULT_HTTP_PORT
        return ComputerDetails.AddressTuple(parts[0], port)
    }

    private fun parseV4Cursor(c: Cursor): ComputerDetails? {
        return try {
            val details = ComputerDetails()
            details.uuid = c.getString(0)
            details.name = c.getString(1)

            val addrs = JSONObject(c.getString(2))
            details.localAddress = tupleFromJson(addrs, AddressFields.LOCAL)
            details.remoteAddress = tupleFromJson(addrs, AddressFields.REMOTE)
            details.manualAddress = tupleFromJson(addrs, AddressFields.MANUAL)
            details.ipv6Address = tupleFromJson(addrs, AddressFields.IPv6)

            details.externalPort = details.remoteAddress?.port ?: NvHTTP.DEFAULT_HTTP_PORT
            details.macAddress = c.getString(3)
            details.serverCert = parseCertificate(c, 4)
            details.state = ComputerDetails.State.UNKNOWN
            details
        } catch (e: Exception) {
            LimeLog.severe("Failed to parse V4 computer: ${e.message}")
            null
        }
    }

    // ======================== Common Utilities ========================

    private fun tupleFromString(addr: String?): ComputerDetails.AddressTuple? {
        return if (addr.isNullOrEmpty()) null
        else ComputerDetails.AddressTuple(addr, NvHTTP.DEFAULT_HTTP_PORT)
    }

    private fun parseCertificate(c: Cursor, col: Int): X509Certificate? {
        if (c.columnCount <= col) return null
        return try {
            val certData = c.getBlob(col)
            if (certData != null) {
                CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(certData)) as X509Certificate
            } else null
        } catch (e: Exception) {
            LimeLog.warning("Failed to parse certificate: ${e.message}")
            null
        }
    }

    companion object {
        private const val DATABASE_NAME = "computers.db"
        private const val DATABASE_VERSION = 4

        private const val TABLE_NAME = "Computers"
        private const val COL_UUID = "UUID"
        private const val COL_NAME = "ComputerName"
        private const val COL_ADDRESSES = "Addresses"
        private const val COL_MAC = "MacAddress"
        private const val COL_CERT = "ServerCert"

        private val LEGACY_DB_FILES = arrayOf("computers2.db", "computers3.db", "computers4.db")

        private const val V1_ADDRESS_PREFIX = "ADDRESS_PREFIX__"
        private const val V3_ADDR_DELIM = ';'
        private const val V3_PORT_DELIM = '_'

        interface AddressFields {
            companion object {
                const val LOCAL = "local"
                const val REMOTE = "remote"
                const val MANUAL = "manual"
                const val IPv6 = "ipv6"
                const val ADDRESS = "address"
                const val PORT = "port"
            }
        }

        @Throws(JSONException::class)
        fun tupleFromJson(json: JSONObject, name: String): ComputerDetails.AddressTuple? {
            if (!json.has(name) || json.isNull(name)) return null
            val addr = json.getJSONObject(name)
            return ComputerDetails.AddressTuple(
                addr.getString(AddressFields.ADDRESS),
                addr.getInt(AddressFields.PORT)
            )
        }
    }
}
