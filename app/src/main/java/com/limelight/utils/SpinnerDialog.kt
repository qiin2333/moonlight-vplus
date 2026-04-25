@file:Suppress("DEPRECATION")
package com.limelight.utils

import android.app.Activity
import android.app.ProgressDialog
import android.content.DialogInterface

import com.limelight.R

class SpinnerDialog private constructor(
    private val activity: Activity,
    private val title: String,
    private val message: String,
    private val finish: Boolean
) : Runnable, DialogInterface.OnCancelListener {

    private var progress: ProgressDialog? = null

    override fun run() {
        val currentProgress = progress
        if (currentProgress == null) {
            // If we're dying, don't bother showing anything new
            if (activity.isFinishing || activity.isDestroyed) {
                return
            }

            val newProgress = ProgressDialog(activity, R.style.AppProgressDialogStyle).apply {
                setTitle(this@SpinnerDialog.title)
                setMessage(this@SpinnerDialog.message)
                setProgressStyle(ProgressDialog.STYLE_SPINNER)
                setOnCancelListener(this@SpinnerDialog)

                // If we want to finish the activity when this is killed, make it cancellable
                if (this@SpinnerDialog.finish) {
                    setCancelable(true)
                    setCanceledOnTouchOutside(false)
                } else {
                    setCancelable(false)
                }
            }

            progress = newProgress

            synchronized(rundownDialogs) {
                rundownDialogs.add(this)
                newProgress.show()
            }

            // 设置对话框透明度
            newProgress.window?.let { window ->
                val layoutParams = window.attributes
                layoutParams.alpha = 0.8f
                // layoutParams.dimAmount = 0.3f
                window.attributes = layoutParams
            }
        } else {
            dismissFromRundown()
        }
    }

    fun dismiss() {
        activity.runOnUiThread {
            dismissFromRundown()
        }
    }

    fun setMessage(message: String) {
        activity.runOnUiThread {
            progress?.setMessage(message)
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        synchronized(rundownDialogs) {
            rundownDialogs.remove(this)
        }

        // This will only be called if finish was true, so we don't need to check again
        activity.finish()
    }

    private fun dismissFromRundown() {
        synchronized(rundownDialogs) {
            rundownDialogs.remove(this)
        }
        safeDismiss(this)
    }

    companion object {
        private val rundownDialogs = ArrayList<SpinnerDialog>()

        private fun safeDismiss(dialog: SpinnerDialog) {
            safeDismiss(dialog.activity, dialog.progress)
            dialog.progress = null
        }

        private fun safeDismiss(activity: Activity, progress: ProgressDialog?) {
            if (progress == null) {
                return
            }

            if (activity.isFinishing || activity.isDestroyed) {
                return
            }

            try {
                if (progress.isShowing) {
                    progress.dismiss()
                }
            } catch (e: IllegalArgumentException) {
                // ProgressDialog 的 DecorView 已解绑，安全忽略
            } catch (e: RuntimeException) {
                // 兜底：dismiss 失败不应让生命周期回调崩溃
            }
        }

        fun displayDialog(activity: Activity, title: String, message: String, finish: Boolean): SpinnerDialog {
            val spinner = SpinnerDialog(activity, title, message, finish)
            activity.runOnUiThread(spinner)
            return spinner
        }

        fun closeDialogs(activity: Activity) {
            val dialogs = ArrayList<SpinnerDialog>()

            synchronized(rundownDialogs) {
                val i = rundownDialogs.iterator()
                while (i.hasNext()) {
                    val dialog = i.next()
                    if (dialog.activity === activity) {
                        i.remove()
                        dialogs.add(dialog)
                    }
                }
            }

            for (dialog in dialogs) {
                safeDismiss(dialog)
            }
        }
    }
}
