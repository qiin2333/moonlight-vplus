package com.limelight.widget

import android.content.Intent
import android.widget.RemoteViewsService

class GameListWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return GameListRemoteViewsFactory(applicationContext, intent)
    }
}
