package com.limelight.grid;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.os.AsyncTask;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PcGridAdapter extends GenericGridAdapter<PcView.ComputerObject> {

    // 添加卡片的特殊标识UUID
    public static final String ADD_COMPUTER_UUID = "__ADD_COMPUTER__";
    
    // SharedPreferences key for show unpaired devices setting
    private static final String PREF_SHOW_UNPAIRED_DEVICES = "show_unpaired_devices";
    
    private static final int TARGET_SIZE = 128;
    private static final float ONLINE_ALPHA = 0.95f;
    private static final float OFFLINE_ALPHA = 0.45f;
    private static final int ONLINE_TEXT_COLOR = 0xFF1C1C1E;
    private static final int OFFLINE_TEXT_COLOR = 0xFF8E8E93;

    private final Context context;
    private final LayoutInflater inflater;
    private final SharedPreferences sharedPreferences;
    private final Map<String, Bitmap> boxArtCache = new ConcurrentHashMap<>();
    private final Set<String> loadingUuids = Collections.synchronizedSet(new HashSet<>());
    
    // 控制是否显示未配对设备（默认显示）
    private boolean showUnpairedDevices;
    
    // 头像点击回调接口
    public interface AvatarClickListener {
        void onAvatarClick(ComputerDetails computer, View itemView);
    }
    
    private AvatarClickListener avatarClickListener;

    public PcGridAdapter(Context context, PreferenceConfiguration prefs) {
        super(context, R.layout.pc_grid_item);
        this.context = context;
        this.inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        this.sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        this.showUnpairedDevices = sharedPreferences.getBoolean(PREF_SHOW_UNPAIRED_DEVICES, true);
    }
    
    /**
     * 设置头像点击监听器
     */
    public void setAvatarClickListener(AvatarClickListener listener) {
        this.avatarClickListener = listener;
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

                if (rawAppList.isEmpty()) {
                    return null;
                }

                List<NvApp> appList = NvHTTP.getAppListByReader(new StringReader(rawAppList));
                if (appList.isEmpty()) {
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
    
    /**
     * 重新排序列表（公开方法，用于电脑状态更新后重新排序）
     */
    public void resort() {
        // 保存排序前的顺序（通过 UUID 列表）
        List<String> beforeOrder = new ArrayList<>();
        for (PcView.ComputerObject obj : itemList) {
            if (obj != null && obj.details != null) {
                beforeOrder.add(obj.details.uuid != null ? obj.details.uuid : "");
            } else {
                beforeOrder.add("");
            }
        }
        
        // 执行排序
        sortList();
        
        // 检查排序后的顺序是否改变
        if (beforeOrder.size() != itemList.size()) {
            return; // 列表大小改变，肯定有变化
        }
        
        for (int i = 0; i < itemList.size(); i++) {
            PcView.ComputerObject obj = itemList.get(i);
            String currentUuid = (obj != null && obj.details != null && obj.details.uuid != null) 
                    ? obj.details.uuid : "";
            if (!beforeOrder.get(i).equals(currentUuid)) {
                return; // 顺序改变了
            }
        }

    }

    private void sortList() {
        Collections.sort(itemList, (lhs, rhs) -> {
            // 添加卡片始终排在最后
            boolean lhsIsAdd = isAddComputerCard(lhs);
            boolean rhsIsAdd = isAddComputerCard(rhs);
            if (lhsIsAdd && !rhsIsAdd) return 1;
            if (!lhsIsAdd && rhsIsAdd) return -1;
            if (lhsIsAdd) return 0;
            
            // 在线设备排在离线设备前面
            boolean lhsOnline = lhs.details != null && lhs.details.state == ComputerDetails.State.ONLINE;
            boolean rhsOnline = rhs.details != null && rhs.details.state == ComputerDetails.State.ONLINE;
            if (lhsOnline && !rhsOnline) return -1;
            if (!lhsOnline && rhsOnline) return 1;
            
            // 在在线设备中，已配对设备排在未配对设备前面
            if (lhsOnline) {
                boolean lhsUnpaired = isUnpairedComputer(lhs);
                boolean rhsUnpaired = isUnpairedComputer(rhs);
                if (lhsUnpaired && !rhsUnpaired) return 1;
                if (!lhsUnpaired && rhsUnpaired) return -1;
            }
            
            // 同组内按名称排序
            if (lhs.details != null) {
                return lhs.details.name.toLowerCase().compareTo(rhs.details.name.toLowerCase());
            }
            return 0;
        });
    }
    
    /**
     * 检查是否是添加卡片
     */
    public static boolean isAddComputerCard(PcView.ComputerObject obj) {
        return obj != null && obj.details != null && ADD_COMPUTER_UUID.equals(obj.details.uuid);
    }
    
    /**
     * 检查是否是未配对的设备
     */
    private static boolean isUnpairedComputer(PcView.ComputerObject obj) {
        if (obj == null || obj.details == null) {
            return false;
        }
        // 排除添加卡片
        if (isAddComputerCard(obj)) {
            return false;
        }
        // 检查是否在线且未配对
        return obj.details.state == ComputerDetails.State.ONLINE 
                && obj.details.pairState == PairingManager.PairState.NOT_PAIRED;
    }

    public void removeComputer(PcView.ComputerObject computer) {
        itemList.remove(computer);
    }
    
    /**
     * 获取原始列表大小（不过滤）
     */
    public int getRawCount() {
        return itemList.size();
    }
    
    /**
     * 获取原始列表项（不过滤）
     */
    public PcView.ComputerObject getRawItem(int i) {
        return itemList.get(i);
    }
    
    /**
     * 设置是否显示未配对设备
     */
    public void setShowUnpairedDevices(boolean show) {
        if (showUnpairedDevices != show) {
            showUnpairedDevices = show;
            sharedPreferences.edit()
                    .putBoolean(PREF_SHOW_UNPAIRED_DEVICES, show)
                    .apply();
            notifyDataSetChanged();
        }
    }
    
    /**
     * 获取是否显示未配对设备
     */
    public boolean isShowUnpairedDevices() {
        return showUnpairedDevices;
    }
    
    /**
     * 获取过滤后的列表项
     */
    private List<PcView.ComputerObject> getFilteredItems() {
        if (showUnpairedDevices) {
            return itemList;
        }
        
        List<PcView.ComputerObject> filtered = new ArrayList<>();
        for (PcView.ComputerObject obj : itemList) {
            // 显示所有已配对设备、离线设备和添加卡片
            // 隐藏在线但未配对的设备
            if (!isUnpairedComputer(obj)) {
                filtered.add(obj);
            }
        }
        return filtered;
    }
    
    @Override
    public int getCount() {
        return getFilteredItems().size();
    }

    @Override
    public Object getItem(int i) {
        List<PcView.ComputerObject> filtered = getFilteredItems();
        if (i < 0 || i >= filtered.size()) {
            return null;
        }
        return filtered.get(i);
    }
    
    @Override
    public View getView(int i, View convertView, ViewGroup viewGroup) {
        List<PcView.ComputerObject> filtered = getFilteredItems();
        if (i < 0 || i >= filtered.size()) {
            return convertView != null ? convertView : inflater.inflate(R.layout.pc_grid_item, viewGroup, false);
        }

        if (convertView == null) {
            convertView = inflater.inflate(R.layout.pc_grid_item, viewGroup, false);
        }

        ImageView imgView = convertView.findViewById(R.id.grid_image);
        ImageView overlayView = convertView.findViewById(R.id.grid_overlay);
        TextView txtView = convertView.findViewById(R.id.grid_text);
        View spinnerView = convertView.findViewById(R.id.grid_spinner);

        PcView.ComputerObject computer = filtered.get(i);
        populateView(convertView, imgView, spinnerView, txtView, overlayView, computer);
        
        // 为头像容器设置点击监听器（仅对非添加卡片）
        View imageLayout = convertView.findViewById(R.id.grid_image_layout);
        if (imageLayout != null) {
            if (!isAddComputerCard(computer) && avatarClickListener != null && computer.details != null) {
                final View itemView = convertView;
                imageLayout.setOnClickListener(v -> avatarClickListener.onAvatarClick(computer.details, itemView));
                imageLayout.setClickable(true);
                imageLayout.setFocusable(false);
            } else {
                imageLayout.setOnClickListener(null);
                imageLayout.setClickable(false);
            }
        }

        return convertView;
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void populateView(View parentView, ImageView imgView, View spinnerView, TextView txtView, ImageView overlayView, PcView.ComputerObject obj) {
        if (isAddComputerCard(obj)) {
            populateAddComputerCard(parentView, imgView, spinnerView, txtView, overlayView);
            return;
        }
        
        populateComputerCard(parentView, imgView, spinnerView, txtView, overlayView, obj.details);
    }
    
    private void populateAddComputerCard(View parentView, ImageView imgView, View spinnerView, TextView txtView, ImageView overlayView) {
        imgView.setImageResource(R.drawable.ic_add);
        imgView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imgView.setAlpha(0.7f);
        
        parentView.setBackgroundResource(R.drawable.pc_item_selector);
        spinnerView.setVisibility(View.INVISIBLE);
        overlayView.setVisibility(View.GONE);
        
        txtView.setText(context.getString(R.string.title_add_pc));
        txtView.setAlpha(0.7f);
        txtView.setTextColor(ONLINE_TEXT_COLOR);
    }
    
    private void populateComputerCard(View parentView, ImageView imgView, View spinnerView, TextView txtView, ImageView overlayView, ComputerDetails details) {
        boolean isOnline = details.state == ComputerDetails.State.ONLINE;
        boolean isUnknown = details.state == ComputerDetails.State.UNKNOWN;
        boolean isOffline = details.state == ComputerDetails.State.OFFLINE;

        // 加载头像
        boolean hasBoxArt = isOnline && details.uuid != null && loadFirstAppBoxArt(imgView, details);
        if (!hasBoxArt) {
            imgView.setImageResource(R.drawable.ic_computer);
            imgView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        }
        imgView.setAlpha(isOnline ? ONLINE_ALPHA : OFFLINE_ALPHA);

        // 设置背景
        int bgRes = (isOnline && details.hasMultipleAddresses())
                ? R.drawable.pc_item_multiple_addresses_selector
                : R.drawable.pc_item_selector;
        parentView.setBackgroundResource(bgRes);

        // 处理加载动画：状态未知或正在加载 box art 时显示
        // 注意：刚打开时电脑状态通常是 UNKNOWN，此时应该显示 spinner
        boolean isLoadingBoxArt = details.uuid != null && loadingUuids.contains(details.uuid);
        boolean shouldShowSpinner = isUnknown || isLoadingBoxArt;
        updateSpinner((ImageView) spinnerView, shouldShowSpinner);

        // 设置文本
        txtView.setText(details.name);
        txtView.setAlpha(isOffline ? 0.5f : 1.0f);
        txtView.setTextColor(isOffline ? OFFLINE_TEXT_COLOR : ONLINE_TEXT_COLOR);

        // 设置覆盖图标
        updateOverlay(overlayView, details, isOnline, isOffline);
    }

    private void updateSpinner(ImageView spinnerView, boolean shouldShow) {
        spinnerView.setVisibility(shouldShow ? View.VISIBLE : View.INVISIBLE);
        if (spinnerView.getDrawable() instanceof AnimatedVectorDrawable) {
            AnimatedVectorDrawable animatedDrawable = (AnimatedVectorDrawable) spinnerView.getDrawable();
            if (shouldShow) {
                animatedDrawable.start();
            } else {
                animatedDrawable.stop();
            }
        }
    }

    private void updateOverlay(ImageView overlayView, ComputerDetails details, boolean isOnline, boolean isOffline) {
        if (isOffline) {
            overlayView.setImageResource(R.drawable.ic_pc_offline);
            overlayView.setAlpha(0.35f);
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
            // 确保状态图标在最上层显示
            overlayView.bringToFront();
        } else {
            overlayView.setVisibility(View.GONE);
        }
    }
}

