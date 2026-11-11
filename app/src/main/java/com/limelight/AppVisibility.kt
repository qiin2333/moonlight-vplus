package com.limelight

import android.app.Activity
import android.app.Application
import android.os.Bundle

/** 统计当前处于 started 状态的本应用 Activity 数，用来判断整个应用是否已退到后台。 */
object AppVisibility {
    @Volatile
    private var startedCount = 0

    val anyActivityStarted: Boolean get() = startedCount > 0

    fun register(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { startedCount++ }
            override fun onActivityStopped(activity: Activity) { if (startedCount > 0) startedCount-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
