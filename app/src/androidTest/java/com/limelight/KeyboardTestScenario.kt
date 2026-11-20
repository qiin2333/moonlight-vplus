package com.limelight

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.net.InetAddress

/** Uses a real window and input handlers, with connection transitions driven by each test. */
internal object KeyboardTestScenario {
    fun launch(): ActivityScenario<Game> {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (activity is Game && stage == Stage.CREATED) {
                // Prevent the surface from starting a real connection to the fixture address.
                // Otherwise asynchronous failure dialogs race with simulated connection state.
                activity.streamView.holder.removeCallback(activity)
                activity.activeStreamView?.holder?.removeCallback(activity)
                activity.progressOverlay?.dismiss()
                activity.progressOverlay = null
            }
        }
        monitor.addLifecycleCallback(callback)
        try {
            return ActivityScenario.launch(Intent(ApplicationProvider.getApplicationContext(), Game::class.java)
                .putExtra(Game.EXTRA_APP_ID, 1).putExtra(Game.EXTRA_APP_NAME, "app")
                .putExtra(Game.EXTRA_HOST, InetAddress.getLoopbackAddress().hostAddress)
                .putExtra(Game.EXTRA_PORT, 1))
        } finally {
            monitor.removeLifecycleCallback(callback)
        }
    }
}
