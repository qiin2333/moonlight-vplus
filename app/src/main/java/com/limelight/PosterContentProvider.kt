package com.limelight

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor

import com.limelight.grid.assets.DiskAssetLoader

import java.io.FileNotFoundException

class PosterContentProvider : ContentProvider() {

    private lateinit var diskAssetLoader: DiskAssetLoader

    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val match = sUriMatcher.match(uri)
        if (match == BOXART_URI_ID) {
            return openBoxArtFile(uri, mode)
        }
        return openBoxArtFile(uri, mode)
    }

    @Throws(FileNotFoundException::class)
    fun openBoxArtFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if ("r" != mode) {
            throw UnsupportedOperationException("This provider is only for read mode")
        }

        val segments = uri.pathSegments
        if (segments.size != 3) {
            throw FileNotFoundException()
        }
        val appId = segments[APP_ID_PATH_INDEX]
        val uuid = segments[COMPUTER_UUID_PATH_INDEX]
        val file = diskAssetLoader.getFile(uuid, appId.toInt())
        if (file.exists()) {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        throw FileNotFoundException()
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        throw UnsupportedOperationException("This provider is only for read mode")
    }

    override fun getType(uri: Uri): String {
        return PNG_MIME_TYPE
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw UnsupportedOperationException("This provider is only for read mode")
    }

    override fun onCreate(): Boolean {
        diskAssetLoader = DiskAssetLoader(context!!)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        throw UnsupportedOperationException("This provider doesn't support query")
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int {
        throw UnsupportedOperationException("This provider is support read only")
    }

    companion object {
        val AUTHORITY = "poster." + BuildConfig.APPLICATION_ID
        const val PNG_MIME_TYPE = "image/png"
        const val APP_ID_PATH_INDEX = 2
        const val COMPUTER_UUID_PATH_INDEX = 1

        private const val BOXART_PATH = "boxart"
        private const val BOXART_URI_ID = 1

        private val sUriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, BOXART_PATH, BOXART_URI_ID)
        }

        fun createBoxArtUri(uuid: String, appId: String): Uri {
            return Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(AUTHORITY)
                .appendPath(BOXART_PATH)
                .appendPath(uuid)
                .appendPath(appId)
                .build()
        }
    }
}
