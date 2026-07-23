package com.limelight.utils

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.widget.Toast
import com.limelight.LimeLog
import com.limelight.R

/**
 * Opens a public HTTPS URL only in a general-purpose browser. Domain-specific
 * deep-link handlers are deliberately excluded.
 */
object BrowserOnlyLauncher {
    private const val BROWSER_PROBE_URL = "https://example.com/"

    fun open(context: Context, rawUrl: String): Boolean {
        val uri = rawUrl.toUri()
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.host.isNullOrBlank() ||
            !uri.userInfo.isNullOrEmpty()
        ) {
            showFailure(context, null)
            return false
        }

        val browseIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }

        val resolved = try {
            resolveBrowserIntent(context, browseIntent)
        } catch (error: RuntimeException) {
            LimeLog.warning("Browser discovery failed: ${error.javaClass.simpleName}")
            null
        }

        if (resolved != null) {
            try {
                context.startActivity(resolved.forContext(context))
                return true
            } catch (error: RuntimeException) {
                LimeLog.warning("Resolved browser launch failed: ${error.javaClass.simpleName}")
            }
        }

        val selector = Intent.makeMainSelectorActivity(
            Intent.ACTION_MAIN,
            Intent.CATEGORY_APP_BROWSER
        ).apply {
            data = uri
        }
        return try {
            context.startActivity(selector.forContext(context))
            true
        } catch (error: ActivityNotFoundException) {
            showFailure(context, error)
            false
        } catch (error: SecurityException) {
            showFailure(context, error)
            false
        } catch (error: RuntimeException) {
            showFailure(context, error)
            false
        }
    }

    private fun resolveBrowserIntent(context: Context, browseIntent: Intent): Intent? {
        val browserSelector = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_BROWSER)
        }
        val packageManager = context.packageManager
        val browserPackages = packageManager
            .queryIntentActivities(browserSelector, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo?.packageName }
            .filterNot { it == context.packageName }
            .distinct()
            .filter { packageName ->
                packageManager.resolveActivity(
                    Intent(browseIntent).setPackage(packageName),
                    PackageManager.MATCH_DEFAULT_ONLY
                ) != null
            }
        if (browserPackages.isEmpty()) return null

        val probe = Intent(Intent.ACTION_VIEW, BROWSER_PROBE_URL.toUri()).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        val defaultPackage = packageManager
            .resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.packageName
            ?.takeIf(browserPackages::contains)
        val explicitIntents = browserPackages.map { packageName ->
            Intent(browseIntent).setPackage(packageName)
        }

        return defaultPackage?.let { Intent(browseIntent).setPackage(it) }
            ?: if (explicitIntents.size == 1) {
                explicitIntents.first()
            } else {
                Intent.createChooser(
                    explicitIntents.first(),
                    context.getString(R.string.about_dialog_choose_browser)
                ).apply {
                    putExtra(
                        Intent.EXTRA_INITIAL_INTENTS,
                        explicitIntents.drop(1).toTypedArray()
                    )
                }
            }
    }

    private fun Intent.forContext(context: Context): Intent {
        if (context !is Activity) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return this
    }

    private fun showFailure(context: Context, error: RuntimeException?) {
        if (error != null) {
            LimeLog.warning("External browser launch failed: ${error.javaClass.simpleName}")
        }
        Toast.makeText(context, R.string.about_dialog_no_browser, Toast.LENGTH_SHORT).show()
    }
}
