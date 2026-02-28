package com.limelight.widget;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.utils.AppCacheManager;
import com.limelight.utils.CacheHelper;
import com.limelight.utils.ServerHelper;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

public class GameListRemoteViewsFactory implements RemoteViewsService.RemoteViewsFactory {
    private final Context context;
    private final int appWidgetId;
    private List<NvApp> appList = new java.util.ArrayList<>();
    private String computerUuid;
    private String computerName;

    public GameListRemoteViewsFactory(Context context, Intent intent) {
        this.context = context;
        this.appWidgetId = intent.getIntExtra("appWidgetId", 0);
    }

    @Override
    public void onCreate() {
        // Data loading is done in onDataSetChanged()
    }

    @Override
    public void onDataSetChanged() {
        SharedPreferences prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE);
        computerUuid = prefs.getString("widget_" + appWidgetId + "_uuid", null);
        computerName = prefs.getString("widget_" + appWidgetId + "_name", null);

        if (computerUuid == null) {
            appList.clear();
            return;
        }

        try {
            // Read app list from cache
            String rawAppList = CacheHelper.readInputStreamToString(
                    CacheHelper.openCacheFileForInput(context.getCacheDir(), "applist", computerUuid));
            
            if (!rawAppList.isEmpty()) {
                appList = NvHTTP.getAppListByReader(new StringReader(rawAppList));
                
                // Sort apps alphabetically
                Collections.sort(appList, new Comparator<NvApp>() {
                    @Override
                    public int compare(NvApp lhs, NvApp rhs) {
                        return lhs.getAppName().compareToIgnoreCase(rhs.getAppName());
                    }
                });
            } else {
                appList.clear();
            }
        } catch (IOException | XmlPullParserException e) {
            LimeLog.warning("Failed to read app list for widget: " + e.getMessage());
            appList.clear();
        }
    }

    @Override
    public void onDestroy() {
        appList.clear();
    }

    @Override
    public int getCount() {
        return appList.size();
    }

    @Override
    public RemoteViews getViewAt(int position) {
        if (position >= appList.size()) return null;

        NvApp app = appList.get(position);
        RemoteViews rv = new RemoteViews(context.getPackageName(), R.layout.widget_item_layout);

        rv.setTextViewText(R.id.widget_item_text, app.getAppName());

        // Load Box Art
        Bitmap bmp = loadBoxArt(app.getAppId());
        if (bmp != null) {
            rv.setImageViewBitmap(R.id.widget_item_image, bmp);
        } else {
            // Fallback content or placeholder
             rv.setImageViewResource(R.id.widget_item_image, R.drawable.no_app_image);
        }

        // Fill-in Intent for click
        // We use ServerHelper.createAppShortcutIntent logic but adapted for fill-in
        Bundle extras = new Bundle();
        extras.putString("UUID", computerUuid);
        extras.putString("AppId", String.valueOf(app.getAppId()));
        extras.putString("AppName", app.getAppName());
        extras.putBoolean("HDR", app.isHdrSupported());
        
        // 保存完整的应用信息到缓存中，以便 ShortcutTrampoline 可以恢复
        try {
            AppCacheManager cacheManager = new AppCacheManager(context);
            cacheManager.saveAppInfo(computerUuid, app);
        } catch (Exception e) {
            LimeLog.warning("Failed to save app info to cache: " + e.getMessage());
        }
        
        Intent fillInIntent = new Intent();
        fillInIntent.putExtras(extras);
        rv.setOnClickFillInIntent(R.id.widget_item_image, fillInIntent);
        // Also make text clickable
        rv.setOnClickFillInIntent(R.id.widget_item_text, fillInIntent);

        return rv;
    }

    private Bitmap loadBoxArt(int appId) {
        File file = CacheHelper.openPath(false, context.getCacheDir(), "boxart", computerUuid, appId + ".png");
        if (!file.exists()) return null;

        try {
            // Basic bitmap decoding - simplified here for the widget
            // Widgets have memory limits, so we should downsample
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);

            // Calculate inSampleSize to target roughly 300x400 max (standard box art)
            // or smaller for widget items
            options.inSampleSize = calculateInSampleSize(options, 200, 266);
            options.inJustDecodeBounds = false;
            
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    @Override
    public RemoteViews getLoadingView() {
        return null;
    }

    @Override
    public int getViewTypeCount() {
        return 1;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public boolean hasStableIds() {
        return true;
    }
}
