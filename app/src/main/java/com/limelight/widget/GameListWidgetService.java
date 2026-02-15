package com.limelight.widget;

import android.content.Intent;
import android.widget.RemoteViewsService;

public class GameListWidgetService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new GameListRemoteViewsFactory(this.getApplicationContext(), intent);
    }
}
