package com.limelight.utils

import android.annotation.TargetApi
import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteException
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.media.tv.TvContract
import android.net.Uri
import android.os.Build

import androidx.core.content.ContextCompat
import androidx.tvprovider.media.tv.TvContractCompat

import com.limelight.LimeLog
import com.limelight.PosterContentProvider
import com.limelight.R
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp

import java.io.IOException

class TvChannelHelper(private var context: Activity) {

    fun requestChannelOnHomeScreen(computer: ComputerDetails) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!isAndroidTV()) {
                return
            }

            val channelId = getChannelId(computer.uuid!!) ?: return

            val intent = Intent(TvContract.ACTION_REQUEST_CHANNEL_BROWSABLE)
            intent.putExtra(TvContract.EXTRA_CHANNEL_ID, channelId)
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
            }
        }
    }

    fun createTvChannel(computer: ComputerDetails) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!isAndroidTV()) {
                return
            }

            val builder = ChannelBuilder()
                .setType(TvContract.Channels.TYPE_PREVIEW)
                .setDisplayName(computer.name!!)
                .setInternalProviderId(computer.uuid!!)
                .setAppLinkIntent(ServerHelper.createPcShortcutIntent(context, computer))

            val channelId = getChannelId(computer.uuid!!)
            if (channelId != null) {
                context.contentResolver.update(
                    TvContract.buildChannelUri(channelId),
                    builder.toContentValues(), null, null
                )
                return
            }

            val channelUri: Uri?
            try {
                channelUri = context.contentResolver.insert(
                    TvContract.Channels.CONTENT_URI, builder.toContentValues()
                )
            } catch (e: IllegalArgumentException) {
                e.printStackTrace()
                return
            }

            if (channelUri != null) {
                val id = ContentUris.parseId(channelUri)
                updateChannelIcon(id)
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun updateChannelIcon(channelId: Long) {
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_channel) ?: return
        val logo = drawableToBitmap(drawable)
        try {
            val localUri = TvContract.buildChannelLogoUri(channelId)
            try {
                context.contentResolver.openOutputStream(localUri)?.use { outputStream ->
                    logo.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    outputStream.flush()
                }
            } catch (e: Exception) {
                when (e) {
                    is SQLiteException, is IOException -> {
                        LimeLog.warning("Failed to store the logo to the system content provider.")
                        e.printStackTrace()
                    }
                    else -> throw e
                }
            }
        } finally {
            logo.recycle()
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        val width = context.resources.getDimensionPixelSize(R.dimen.tv_channel_logo_width)
        val height = context.resources.getDimensionPixelSize(R.dimen.tv_channel_logo_width)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    fun addGameToChannel(computer: ComputerDetails, app: NvApp) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!isAndroidTV()) {
                return
            }

            val channelId = getChannelId(computer.uuid!!) ?: return

            val builder = PreviewProgramBuilder()
                .setChannelId(channelId)
                .setType(TYPE_GAME)
                .setTitle(app.appName)
                .setPosterArtAspectRatio(ASPECT_RATIO_MOVIE_POSTER)
                .setPosterArtUri(PosterContentProvider.createBoxArtUri(computer.uuid!!, "" + app.appId))
                .setIntent(ServerHelper.createAppShortcutIntent(context, computer, app))
                .setInternalProviderId("" + app.appId)
                .setWeight(((System.currentTimeMillis() - 1500000000000L) / 1000).toInt())

            val programId = getProgramId(channelId, "" + app.appId)
            if (programId != null) {
                context.contentResolver.update(
                    TvContract.buildPreviewProgramUri(programId),
                    builder.toContentValues(), null, null
                )
                return
            }

            try {
                context.contentResolver.insert(
                    TvContract.PreviewPrograms.CONTENT_URI,
                    builder.toContentValues()
                )
            } catch (e: IllegalArgumentException) {
                e.printStackTrace()
                return
            }

            TvContractCompat.requestChannelBrowsable(context, channelId)
        }
    }

    fun deleteChannel(computer: ComputerDetails) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!isAndroidTV()) {
                return
            }

            val channelId = getChannelId(computer.uuid!!) ?: return
            context.contentResolver.delete(TvContract.buildChannelUri(channelId), null, null)
        }
    }

    fun deleteProgram(computer: ComputerDetails, app: NvApp) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!isAndroidTV()) {
                return
            }

            val channelId = getChannelId(computer.uuid!!) ?: return
            val programId = getProgramId(channelId, "" + app.appId) ?: return

            context.contentResolver.delete(TvContract.buildPreviewProgramUri(programId), null, null)
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun getChannelId(computerUuid: String): Long? {
        context.contentResolver.query(
            TvContract.Channels.CONTENT_URI,
            arrayOf(TvContract.Channels._ID, TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID),
            null, null, null
        )?.use { cursor ->
            if (cursor.count == 0) {
                return null
            }
            while (cursor.moveToNext()) {
                val internalProviderId = cursor.getString(INTERNAL_PROVIDER_ID_INDEX)
                if (computerUuid == internalProviderId) {
                    return cursor.getLong(ID_INDEX)
                }
            }
        }
        return null
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun getProgramId(channelId: Long, appId: String): Long? {
        context.contentResolver.query(
            TvContract.buildPreviewProgramsUriForChannel(channelId),
            arrayOf(
                TvContract.PreviewPrograms._ID,
                TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID,
                TvContract.PreviewPrograms.COLUMN_BROWSABLE
            ),
            null, null, null
        )?.use { cursor ->
            if (cursor.count == 0) {
                return null
            }
            while (cursor.moveToNext()) {
                val internalProviderId = cursor.getString(INTERNAL_PROVIDER_ID_INDEX)
                if (appId == internalProviderId) {
                    val id = cursor.getLong(ID_INDEX)
                    val browsable = cursor.getInt(PROGRAM_BROWSABLE_INDEX)
                    if (browsable != 0) {
                        return id
                    } else {
                        val countDeleted = context.contentResolver.delete(
                            TvContract.buildPreviewProgramUri(id), null, null
                        )
                        if (countDeleted > 0) {
                            LimeLog.info("Preview program has been deleted")
                        } else {
                            LimeLog.warning("Preview program has not been deleted")
                        }
                    }
                }
            }
        }
        return null
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun isAndroidTV(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    @TargetApi(Build.VERSION_CODES.O)
    private class PreviewProgramBuilder {
        private val mValues = ContentValues()

        fun setChannelId(channelId: Long): PreviewProgramBuilder {
            mValues.put(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, channelId)
            return this
        }

        fun setType(type: Int): PreviewProgramBuilder {
            mValues.put(TvContract.PreviewPrograms.COLUMN_TYPE, type)
            return this
        }

        fun setTitle(title: String): PreviewProgramBuilder {
            mValues.put(TvContract.PreviewPrograms.COLUMN_TITLE, title)
            return this
        }

        fun setPosterArtAspectRatio(aspectRatio: Int): PreviewProgramBuilder {
            mValues.put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, aspectRatio)
            return this
        }

        fun setIntent(intent: Intent): PreviewProgramBuilder {
            mValues.put(TvContract.PreviewPrograms.COLUMN_INTENT_URI, toUriString(intent))
            return this
        }

        fun setInternalProviderId(id: String): PreviewProgramBuilder {
            mValues.put(TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID, id)
            return this
        }

        fun setPosterArtUri(uri: Uri): PreviewProgramBuilder {
            mValues.put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI, uri.toString())
            return this
        }

        fun setWeight(weight: Int): PreviewProgramBuilder {
            mValues.put(TvContract.PreviewPrograms.COLUMN_WEIGHT, weight)
            return this
        }

        fun toContentValues(): ContentValues = ContentValues(mValues)
    }

    @TargetApi(Build.VERSION_CODES.O)
    private class ChannelBuilder {
        private val mValues = ContentValues()

        fun setType(type: String): ChannelBuilder {
            mValues.put(TvContract.Channels.COLUMN_TYPE, type)
            return this
        }

        fun setDisplayName(displayName: String): ChannelBuilder {
            mValues.put(TvContract.Channels.COLUMN_DISPLAY_NAME, displayName)
            return this
        }

        fun setInternalProviderId(internalProviderId: String): ChannelBuilder {
            mValues.put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID, internalProviderId)
            return this
        }

        fun setAppLinkIntent(intent: Intent): ChannelBuilder {
            mValues.put(TvContract.Channels.COLUMN_APP_LINK_INTENT_URI, toUriString(intent))
            return this
        }

        fun toContentValues(): ContentValues = ContentValues(mValues)
    }

    companion object {
        private const val ASPECT_RATIO_MOVIE_POSTER = 5
        private const val TYPE_GAME = 12
        private const val INTERNAL_PROVIDER_ID_INDEX = 1
        private const val PROGRAM_BROWSABLE_INDEX = 2
        private const val ID_INDEX = 0

        private fun toUriString(intent: Intent?): String? {
            return intent?.toUri(Intent.URI_INTENT_SCHEME)
        }
    }
}
