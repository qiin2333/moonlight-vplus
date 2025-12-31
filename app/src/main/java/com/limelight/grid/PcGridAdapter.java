package com.limelight.grid;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.os.AsyncTask;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.limelight.LimeLog;
import com.limelight.PcView;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.CacheHelper;

import java.io.File;
import java.io.StringReader;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PcGridAdapter extends GenericGridAdapter<PcView.ComputerObject> {

    // 添加卡片的特殊标识UUID
    public static final String ADD_COMPUTER_UUID = "__ADD_COMPUTER__";
    
    private static final int TARGET_SIZE = 128;
    private static final float ONLINE_ALPHA = 0.85f;
    private static final float OFFLINE_ALPHA = 0.45f;
    private static final int ONLINE_TEXT_COLOR = 0xFF1C1C1E;
    private static final int OFFLINE_TEXT_COLOR = 0xFF8E8E93;

    private final Context context;
    private final Map<String, Bitmap> boxArtCache = new ConcurrentHashMap<>();
    private final Set<String> loadingUuids = Collections.synchronizedSet(new HashSet<>());

    public PcGridAdapter(Context context, PreferenceConfiguration prefs) {
        super(context, R.layout.pc_grid_item);
        this.context = context;
    }

    public void updateLayoutWithPreferences(Context context, PreferenceConfiguration prefs) {
        setLayoutId(R.layout.pc_grid_item);
    }

    private boolean loadFirstAppBoxArt(ImageView imgView, ComputerDetails computer) {
        if (computer.uuid == null) {
            return false;
        }

        Bitmap cachedBitmap = boxArtCache.get(computer.uuid);
        if (cachedBitmap != null && !cachedBitmap.isRecycled()) {
            applyBoxArt(imgView, cachedBitmap);
            return true;
        }

        if (loadingUuids.contains(computer.uuid)) {
            return false;
        }

        loadingUuids.add(computer.uuid);
        new LoadBoxArtTask(imgView, computer, context, this).execute();
        return false;
    }

    private static void applyBoxArt(ImageView imgView, Bitmap bitmap) {
        imgView.setImageBitmap(bitmap);
        imgView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imgView.setClipToOutline(true);
    }

    void cacheBoxArt(String uuid, Bitmap bitmap) {
        if (uuid != null && bitmap != null) {
            boxArtCache.put(uuid, bitmap);
        }
        loadingUuids.remove(uuid);
    }

    void markLoadingComplete(String uuid) {
        loadingUuids.remove(uuid);
    }

    private static class LoadBoxArtTask extends AsyncTask<Void, Void, Bitmap> {
        private final WeakReference<ImageView> imageViewRef;
        private final ComputerDetails computer;
        private final WeakReference<Context> contextRef;
        private final WeakReference<PcGridAdapter> adapterRef;

        LoadBoxArtTask(ImageView imageView, ComputerDetails computer, Context context, PcGridAdapter adapter) {
            this.imageViewRef = new WeakReference<>(imageView);
            this.computer = computer;
            this.contextRef = new WeakReference<>(context);
            this.adapterRef = new WeakReference<>(adapter);
        }

        @Override
        protected Bitmap doInBackground(Void... voids) {
            Context ctx = contextRef.get();
            if (ctx == null || computer.uuid == null) {
                return null;
            }

            try {
                String rawAppList = CacheHelper.readInputStreamToString(
                        CacheHelper.openCacheFileForInput(ctx.getCacheDir(), "applist", computer.uuid));

                if (rawAppList == null || rawAppList.isEmpty()) {
                    return null;
                }

                List<NvApp> appList = NvHTTP.getAppListByReader(new StringReader(rawAppList));
                if (appList == null || appList.isEmpty()) {
                    return null;
                }

                File cacheDir = ctx.getCacheDir();
                for (NvApp app : appList) {
                    Bitmap bitmap = loadBoxArtForApp(cacheDir, computer.uuid, app);
                    if (bitmap != null) {
                        LimeLog.info("Loaded box art for PC card: " + app.getAppName());
                        return bitmap;
                    }
                }
            } catch (Exception e) {
                LimeLog.warning("Failed to load first app box art: " + e.getMessage());
            }

            return null;
        }

        private Bitmap loadBoxArtForApp(File cacheDir, String uuid, NvApp app) {
            File boxArtFile = CacheHelper.openPath(false, cacheDir, "boxart", uuid, app.getAppId() + ".png");
            if (!boxArtFile.exists() || boxArtFile.length() == 0) {
                return null;
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(boxArtFile.getAbsolutePath(), options);

            options.inSampleSize = calculateSampleSize(options.outWidth, options.outHeight);
            options.inJustDecodeBounds = false;

            return BitmapFactory.decodeFile(boxArtFile.getAbsolutePath(), options);
        }

        private int calculateSampleSize(int width, int height) {
            int sampleSize = 1;
            if (height > TARGET_SIZE || width > TARGET_SIZE) {
                int halfHeight = height / 2;
                int halfWidth = width / 2;
                while ((halfHeight / sampleSize) >= TARGET_SIZE && (halfWidth / sampleSize) >= TARGET_SIZE) {
                    sampleSize *= 2;
                }
            }
            return sampleSize;
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            PcGridAdapter adapter = adapterRef.get();
            if (adapter != null) {
                if (bitmap != null) {
                    adapter.cacheBoxArt(computer.uuid, bitmap);
                } else {
                    adapter.markLoadingComplete(computer.uuid);
                }
            }

            ImageView imageView = imageViewRef.get();
            if (imageView != null && bitmap != null) {
                applyBoxArt(imageView, bitmap);
            }
        }
    }

    public void addComputer(PcView.ComputerObject computer) {
        itemList.add(computer);
        sortList();
    }

    private void sortList() {
        Collections.sort(itemList, (lhs, rhs) -> {
            // 添加卡片始终排在最后
            boolean lhsIsAdd = isAddComputerCard(lhs);
            boolean rhsIsAdd = isAddComputerCard(rhs);
            if (lhsIsAdd && !rhsIsAdd) return 1;
            if (!lhsIsAdd && rhsIsAdd) return -1;
            if (lhsIsAdd && rhsIsAdd) return 0;
            // 普通卡片按名称排序
            return lhs.details.name.toLowerCase().compareTo(rhs.details.name.toLowerCase());
        });
    }
    
    /**
     * 检查是否是添加卡片
     */
    public static boolean isAddComputerCard(PcView.ComputerObject obj) {
        return obj != null && obj.details != null && ADD_COMPUTER_UUID.equals(obj.details.uuid);
    }

    public boolean removeComputer(PcView.ComputerObject computer) {
        return itemList.remove(computer);
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void populateView(View parentView, ImageView imgView, View spinnerView, TextView txtView, ImageView overlayView, PcView.ComputerObject obj) {
        if (isAddComputerCard(obj)) {
            imgView.setImageResource(R.drawable.ic_add);
            imgView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            imgView.setAlpha(0.7f);
            
            // 设置背景
            parentView.setBackgroundResource(R.drawable.pc_item_selector);
            
            // 隐藏加载动画和覆盖图标
            spinnerView.setVisibility(View.INVISIBLE);
            overlayView.setVisibility(View.GONE);
            
            // 设置文本
            try {
                txtView.setText(context.getString(R.string.title_add_pc));
            } catch (Exception e) {
                txtView.setText("添加电脑");
            }
            txtView.setAlpha(0.7f);
            txtView.setTextColor(ONLINE_TEXT_COLOR);
            return;
        }
        
        ComputerDetails details = obj.details;
        boolean isOnline = details.state == ComputerDetails.State.ONLINE;
        boolean isUnknown = details.state == ComputerDetails.State.UNKNOWN;
        boolean isOffline = details.state == ComputerDetails.State.OFFLINE;

        // 加载头像
        boolean hasBoxArt = isOnline && details.uuid != null && loadFirstAppBoxArt(imgView, details);
        if (!hasBoxArt) {
            imgView.setImageResource(R.drawable.ic_computer);
            imgView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        }

        // 设置透明度
        float alpha = isOnline ? ONLINE_ALPHA : OFFLINE_ALPHA;
        imgView.setAlpha(alpha);

        // 设置背景
        int bgRes = (isOnline && details.hasMultipleAddresses())
                ? R.drawable.pc_item_multiple_addresses_selector
                : R.drawable.pc_item_selector;
        parentView.setBackgroundResource(bgRes);

        // 处理加载动画
        updateSpinner((ImageView) spinnerView, isUnknown);

        // 设置文本
        txtView.setText(details.name);
        txtView.setAlpha(isOnline ? 1.0f : 0.55f);
        txtView.setTextColor(isOnline ? ONLINE_TEXT_COLOR : OFFLINE_TEXT_COLOR);

        // 设置覆盖图标
        updateOverlay(overlayView, details, isOnline, isOffline);
    }

    private void updateSpinner(ImageView spinnerView, boolean isUnknown) {
        spinnerView.setVisibility(isUnknown ? View.VISIBLE : View.INVISIBLE);
        if (spinnerView.getDrawable() instanceof AnimatedVectorDrawable) {
            AnimatedVectorDrawable animatedDrawable = (AnimatedVectorDrawable) spinnerView.getDrawable();
            if (isUnknown) {
                animatedDrawable.start();
            } else {
                animatedDrawable.stop();
            }
        }
    }

    private void updateOverlay(ImageView overlayView, ComputerDetails details, boolean isOnline, boolean isOffline) {
        if (isOffline) {
            overlayView.setImageResource(R.drawable.ic_pc_offline);
            overlayView.setAlpha(0.3f);
            overlayView.setVisibility(View.VISIBLE);
            overlayView.setPadding(0, 0, 10, 12);
            overlayView.setScaleX(1.4f);
            overlayView.setScaleY(1.4f);
        } else if (isOnline && details.pairState == PairingManager.PairState.NOT_PAIRED) {
            overlayView.setImageResource(R.drawable.ic_lock);
            overlayView.setAlpha(1.0f);
            overlayView.setVisibility(View.VISIBLE);
            overlayView.setPadding(0, 0, 0, 0);
            overlayView.setScaleX(1.0f);
            overlayView.setScaleY(1.0f);
        } else {
            overlayView.setVisibility(View.GONE);
        }
    }
}
