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
        // If we're dying, don't bother doing anything
        if (activity.isFinishing) {
            return
        }

        val currentProgress = progress
        if (currentProgress == null) {
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
            synchronized(rundownDialogs) {
                if (rundownDialogs.remove(this) && currentProgress.isShowing) {
                    currentProgress.dismiss()
                }
            }
        }
    }

    fun dismiss() {
        // Running again with progress != null will destroy it
        activity.runOnUiThread(this)
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

    companion object {
        private val rundownDialogs = ArrayList<SpinnerDialog>()

        fun displayDialog(activity: Activity, title: String, message: String, finish: Boolean): SpinnerDialog {
            val spinner = SpinnerDialog(activity, title, message, finish)
            activity.runOnUiThread(spinner)
            return spinner
        }

        fun closeDialogs(activity: Activity) {
            synchronized(rundownDialogs) {
                val i = rundownDialogs.iterator()
                while (i.hasNext()) {
                    val dialog = i.next()
                    if (dialog.activity === activity) {
                        i.remove()
                        if (dialog.progress?.isShowing == true) {
                            dialog.progress?.dismiss()
                        }
                    }
                }
            }
        }
    }
}
