package com.limelight.grid.assets;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TextView;
import android.content.Context;

import com.limelight.AppView;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class CachedAppAssetLoader {
    private static final int MAX_CONCURRENT_DISK_LOADS = 3;
    private static final int MAX_CONCURRENT_NETWORK_LOADS = 3;
    private static final int MAX_CONCURRENT_CACHE_LOADS = 1;

    private static final int MAX_PENDING_CACHE_LOADS = 100;
    private static final int MAX_PENDING_NETWORK_LOADS = 40;
    private static final int MAX_PENDING_DISK_LOADS = 40;

    private final Context context;

    private final ThreadPoolExecutor cacheExecutor = new ThreadPoolExecutor(
            MAX_CONCURRENT_CACHE_LOADS, MAX_CONCURRENT_CACHE_LOADS,
            Long.MAX_VALUE, TimeUnit.DAYS,
            new LinkedBlockingQueue<Runnable>(MAX_PENDING_CACHE_LOADS),
            new ThreadPoolExecutor.DiscardOldestPolicy());

    private final ThreadPoolExecutor foregroundExecutor = new ThreadPoolExecutor(
            MAX_CONCURRENT_DISK_LOADS, MAX_CONCURRENT_DISK_LOADS,
            Long.MAX_VALUE, TimeUnit.DAYS,
            new LinkedBlockingQueue<Runnable>(MAX_PENDING_DISK_LOADS),
            new ThreadPoolExecutor.DiscardOldestPolicy());

    private final ThreadPoolExecutor networkExecutor = new ThreadPoolExecutor(
            MAX_CONCURRENT_NETWORK_LOADS, MAX_CONCURRENT_NETWORK_LOADS,
            Long.MAX_VALUE, TimeUnit.DAYS,
            new LinkedBlockingQueue<Runnable>(MAX_PENDING_NETWORK_LOADS),
            new ThreadPoolExecutor.DiscardOldestPolicy());

    private final ComputerDetails computer;
    private final double scalingDivider;
    private final NetworkAssetLoader networkLoader;
    private final MemoryAssetLoader memoryLoader;
    private final DiskAssetLoader diskLoader;
    private final Bitmap placeholderBitmap;
    private final Bitmap noAppImageBitmap;

    public CachedAppAssetLoader(Context context, ComputerDetails computer, double scalingDivider,
                                NetworkAssetLoader networkLoader, MemoryAssetLoader memoryLoader,
                                DiskAssetLoader diskLoader, Bitmap noAppImageBitmap) {
        this.context = context;
        this.computer = computer;
        this.scalingDivider = scalingDivider;
        this.networkLoader = networkLoader;
        this.memoryLoader = memoryLoader;
        this.diskLoader = diskLoader;
        this.noAppImageBitmap = noAppImageBitmap;
        this.placeholderBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
    }

    public void cancelBackgroundLoads() {
        Runnable r;
        while ((r = cacheExecutor.getQueue().poll()) != null) {
            cacheExecutor.remove(r);
        }
    }

    public void cancelForegroundLoads() {
        Runnable r;

        while ((r = foregroundExecutor.getQueue().poll()) != null) {
            foregroundExecutor.remove(r);
        }

        while ((r = networkExecutor.getQueue().poll()) != null) {
            networkExecutor.remove(r);
        }
    }

    public void freeCacheMemory() {
        memoryLoader.clearCache();
    }

    public ScaledBitmap getBitmapFromCache(LoaderTuple tuple) {
        return diskLoader.loadBitmapFromCache(tuple, (int) scalingDivider);
    }
    
    /**
     * 压缩过大的Bitmap
     */
    private Bitmap compressLargeBitmap(Bitmap original) {
        if (original == null) return null;
        
        // 计算Bitmap的内存大小（字节）
        int byteCount = original.getByteCount();
        int maxSize = 1024 * 1024; // 1MB限制
        
        // 如果大小超过限制，进行压缩
        if (byteCount > maxSize) {
            try {
                // 计算压缩比例
                float scale = (float) Math.sqrt((double) maxSize / byteCount);
                int newWidth = Math.round(original.getWidth() * scale);
                int newHeight = Math.round(original.getHeight() * scale);
                
                // 确保最小尺寸
                newWidth = Math.max(newWidth, 300);
                newHeight = Math.max(newHeight, 400);
                
                // 创建压缩后的Bitmap
                Bitmap compressed = Bitmap.createScaledBitmap(original, newWidth, newHeight, true);
                
                LimeLog.info("Compressed bitmap from " + original.getWidth() + "x" + original.getHeight() + 
                           " to " + newWidth + "x" + newHeight + " (size: " + byteCount + " -> " + compressed.getByteCount() + " bytes)");
                
                return compressed;
            } catch (Exception e) {
                LimeLog.warning("Failed to compress bitmap: " + e.getMessage());
                return original;
            }
        }
        
        return original;
    }

    private ScaledBitmap doNetworkAssetLoad(LoaderTuple tuple, LoaderTask task) {
        // Try 3 times
        for (int i = 0; i < 3; i++) {
            // Check again whether we've been cancelled or the image view is gone
            if (task != null && (task.isCancelled() || task.imageViewRef.get() == null)) {
                return null;
            }

            InputStream in = networkLoader.getBitmapStream(tuple);
            if (in != null) {
                // Write the stream straight to disk
                diskLoader.populateCacheWithStream(tuple, in);

                // Close the network input stream
                try {
                    in.close();
                } catch (IOException ignored) {}

                // If there's a task associated with this load, we should return the bitmap
                if (task != null) {
                    // If the cached bitmap is valid, return it. Otherwise, we'll try the load again
                    ScaledBitmap bmp = diskLoader.loadBitmapFromCache(tuple, (int) scalingDivider);
                    if (bmp != null) {
                        return bmp;
                    }
                }
                else {
                    // Otherwise it's a background load and we return nothing
                    return null;
                }
            }

            // Wait 1 second with a bit of fuzz
            try {
                Thread.sleep((int) (1000 + (Math.random() * 500)));
            } catch (InterruptedException e) {
                e.printStackTrace();

                // InterruptedException clears the thread's interrupt status. Since we can't
                // handle that here, we will re-interrupt the thread to set the interrupt
                // status back to true.
                Thread.currentThread().interrupt();

                return null;
            }
        }

        return null;
    }

    private class LoaderTask extends AsyncTask<LoaderTuple, Void, ScaledBitmap> {
        private final WeakReference<ImageView> imageViewRef;
        private final WeakReference<TextView> textViewRef;
        private final boolean diskOnly;
        private final boolean isBackground;
        private final Runnable onLoadComplete;

        private LoaderTuple tuple;

        public LoaderTask(ImageView imageView, TextView textView, boolean diskOnly) {
            this(imageView, textView, diskOnly, false, null);
        }

        public LoaderTask(ImageView imageView, TextView textView, boolean diskOnly, boolean isBackground) {
            this(imageView, textView, diskOnly, isBackground, null);
        }
        
        public LoaderTask(ImageView imageView, TextView textView, boolean diskOnly, boolean isBackground, Runnable onLoadComplete) {
            this.imageViewRef = new WeakReference<>(imageView);
            this.textViewRef = new WeakReference<>(textView);
            this.diskOnly = diskOnly;
            this.isBackground = isBackground;
            this.onLoadComplete = onLoadComplete;
        }

        @Override
        protected ScaledBitmap doInBackground(LoaderTuple... params) {
            tuple = params[0];

            // Check whether it has been cancelled or the views are gone
            if (isCancelled() || imageViewRef.get() == null || textViewRef.get() == null) {
                return null;
            }

            ScaledBitmap bmp = diskLoader.loadBitmapFromCache(tuple, (int) scalingDivider);
            if (bmp == null) {
                if (!diskOnly) {
                    // Try to load the asset from the network
                    bmp = doNetworkAssetLoad(tuple, this);
                } else {
                    // Report progress to display the placeholder and spin
                    // off the network-capable task
                    publishProgress();
                }
            }

            // Cache the bitmap
            if (bmp != null) {
                // 压缩过大的Bitmap
                Bitmap compressedBitmap = compressLargeBitmap(bmp.bitmap);
                if (compressedBitmap != bmp.bitmap) {
                    // 如果压缩了，创建新的ScaledBitmap
                    ScaledBitmap compressedScaledBitmap = new ScaledBitmap(bmp.originalWidth, bmp.originalHeight, compressedBitmap);
                    memoryLoader.populateCache(tuple, compressedScaledBitmap);
                } else {
                    // 如果没有压缩，使用原始Bitmap
                    memoryLoader.populateCache(tuple, bmp);
                }
            }

            return bmp;
        }

        @Override
        protected void onProgressUpdate(Void... nothing) {
            // Do nothing if cancelled
            if (isCancelled()) {
                return;
            }

            // If the current loader task for this view isn't us, do nothing
            final ImageView imageView = imageViewRef.get();
            final TextView textView = textViewRef.get();
            if (getLoaderTask(imageView) == this) {
                // Set off another loader task on the network executor. This time our AsyncDrawable
                // will use the app image placeholder bitmap, rather than an empty bitmap.
                LoaderTask task = new LoaderTask(imageView, textView, false, isBackground);
                AsyncDrawable asyncDrawable = new AsyncDrawable(imageView.getResources(), noAppImageBitmap, task);
                imageView.setImageDrawable(asyncDrawable);
                // Use different animation for background images
                int animationRes = isBackground ? R.anim.background_fadein : R.anim.boxart_fadein;
                imageView.startAnimation(AnimationUtils.loadAnimation(imageView.getContext(), animationRes));
                imageView.setVisibility(View.VISIBLE);
                if (textView != null) {
                    textView.setVisibility(View.VISIBLE);
                }
                task.executeOnExecutor(networkExecutor, tuple);
            }
        }

        @Override
        protected void onPostExecute(final ScaledBitmap bitmap) {
            // Do nothing if cancelled
            if (isCancelled()) {
                return;
            }

            final ImageView imageView = imageViewRef.get();
            final TextView textView = textViewRef.get();
            if (getLoaderTask(imageView) == this) {
                // Fade in the box art
                if (bitmap != null) {
                    // Show the text if it's a placeholder
                    if (textView != null) {
                        textView.setVisibility(isBitmapPlaceholder(bitmap) ? View.VISIBLE : View.GONE);
                    }

                    if (imageView.getVisibility() == View.VISIBLE) {
                        // Fade out the placeholder first
                        int fadeOutAnimRes = isBackground ? R.anim.background_fadeout : R.anim.boxart_fadeout;
                        Animation fadeOutAnimation = AnimationUtils.loadAnimation(imageView.getContext(), fadeOutAnimRes);
                        fadeOutAnimation.setAnimationListener(new Animation.AnimationListener() {
                            @Override
                            public void onAnimationStart(Animation animation) {}

                            @Override
                            public void onAnimationEnd(Animation animation) {
                                // Fade in the new box art
                                imageView.setImageBitmap(bitmap.bitmap);
                                int fadeInAnimRes = isBackground ? R.anim.background_fadein : R.anim.boxart_fadein;
                                imageView.startAnimation(AnimationUtils.loadAnimation(imageView.getContext(), fadeInAnimRes));
                            }

                            @Override
                            public void onAnimationRepeat(Animation animation) {}
                        });
                        imageView.startAnimation(fadeOutAnimation);
                    }
                    else {
                        // View is invisible already, so just fade in the new art
                        imageView.setImageBitmap(bitmap.bitmap);
                        int fadeInAnimRes = isBackground ? R.anim.background_fadein : R.anim.boxart_fadein;
                        imageView.startAnimation(AnimationUtils.loadAnimation(imageView.getContext(), fadeInAnimRes));
                        imageView.setVisibility(View.VISIBLE);
                    }
                }
                
                // 如果提供了回调，执行它
                if (onLoadComplete != null) {
                    onLoadComplete.run();
                }
            }
        }
    }

    static class AsyncDrawable extends BitmapDrawable {
        private final WeakReference<LoaderTask> loaderTaskReference;

        public AsyncDrawable(Resources res, Bitmap bitmap,
                             LoaderTask loaderTask) {
            super(res, bitmap);
            loaderTaskReference = new WeakReference<>(loaderTask);
        }

        public LoaderTask getLoaderTask() {
            return loaderTaskReference.get();
        }
    }

    private static LoaderTask getLoaderTask(ImageView imageView) {
        if (imageView == null) {
            return null;
        }

        final Drawable drawable = imageView.getDrawable();

        // If our drawable is in play, get the loader task
        if (drawable instanceof AsyncDrawable) {
            final AsyncDrawable asyncDrawable = (AsyncDrawable) drawable;
            return asyncDrawable.getLoaderTask();
        }

        return null;
    }

    private static boolean cancelPendingLoad(LoaderTuple tuple, ImageView imageView) {
        final LoaderTask loaderTask = getLoaderTask(imageView);

        // Check if any task was pending for this image view
        if (loaderTask != null && !loaderTask.isCancelled()) {
            final LoaderTuple taskTuple = loaderTask.tuple;

            // Cancel the task if it's not already loading the same data
            if (taskTuple == null || !taskTuple.equals(tuple)) {
                loaderTask.cancel(true);
            } else {
                // It's already loading what we want
                return false;
            }
        }

        // Allow the load to proceed
        return true;
    }

    public void queueCacheLoad(NvApp app) {
        final LoaderTuple tuple = new LoaderTuple(computer, app);

        if (memoryLoader.loadBitmapFromCache(tuple) != null) {
            // It's in memory which means it must also be on disk
            return;
        }

        // Queue a fetch in the cache executor
        cacheExecutor.execute(new Runnable() {
            @Override
            public void run() {
                // Check if the image is cached on disk
                if (diskLoader.checkCacheExists(tuple)) {
                    return;
                }

                // Try to load the asset from the network and cache result on disk
                doNetworkAssetLoad(tuple, null);
            }
        });
    }

    private boolean isBitmapPlaceholder(ScaledBitmap bitmap) {
        return (bitmap == null) ||
                (bitmap.originalWidth == 130 && bitmap.originalHeight == 180) || // GFE 2.0
                (bitmap.originalWidth == 628 && bitmap.originalHeight == 888); // GFE 3.0
    }

    public boolean populateImageView(AppView.AppObject obj, ImageView imgView, TextView textView) {
        return populateImageView(obj, imgView, textView, false);
    }

    public boolean populateImageView(AppView.AppObject obj, ImageView imgView, TextView textView, boolean isBackground) {
        return populateImageView(obj, imgView, textView, isBackground, null);
    }
    
    public boolean populateImageView(AppView.AppObject obj, ImageView imgView, TextView textView, boolean isBackground, Runnable onLoadComplete) {
        LoaderTuple tuple = new LoaderTuple(computer, obj.app);

        // If there's already a task in progress for this view,
        // cancel it. If the task is already loading the same image,
        // we return and let that load finish.
        if (!cancelPendingLoad(tuple, imgView)) {
            return true;
        }

        // Always set the name text so we have it if needed later
        if (textView != null) {
            textView.setText(obj.app.getAppName());
        }
        // First, try the memory cache in the current context
        ScaledBitmap bmp = memoryLoader.loadBitmapFromCache(tuple);
        if (bmp != null) {
            // Show the bitmap immediately
            imgView.setVisibility(View.VISIBLE);
            imgView.setImageBitmap(bmp.bitmap);
            // Show the text if it's a placeholder bitmap
            if (textView != null) {
                textView.setVisibility(isBitmapPlaceholder(bmp) ? View.VISIBLE : View.GONE);
            }
            
            // 如果提供了回调，执行它
            if (onLoadComplete != null) {
                onLoadComplete.run();
            }
            return true;
        }

        // If it's not in memory, create an async task to load it. This task will be attached
        // via AsyncDrawable to this view.
        final LoaderTask task = new LoaderTask(imgView, textView, true, isBackground, onLoadComplete);
        final AsyncDrawable asyncDrawable = new AsyncDrawable(imgView.getResources(), placeholderBitmap, task);
        if (textView != null) {
            textView.setVisibility(View.INVISIBLE);
        }
        imgView.setVisibility(View.INVISIBLE);
        imgView.setImageDrawable(asyncDrawable);

        // Run the task on our foreground executor
        task.executeOnExecutor(foregroundExecutor, tuple);
        return false;
    }

    public static class LoaderTuple {
        public final ComputerDetails computer;
        public final NvApp app;

        public LoaderTuple(ComputerDetails computer, NvApp app) {
            this.computer = computer;
            this.app = app;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof LoaderTuple)) {
                return false;
            }

            LoaderTuple other = (LoaderTuple) o;
            return computer.uuid.equals(other.computer.uuid) && app.getAppId() == other.app.getAppId();
        }

        @Override
        public String toString() {
            return "("+computer.uuid+", "+app.getAppId()+")";
        }
    }
}
