package com.limelight.binding.input.advance_setting.element;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;

import com.limelight.Game;
import com.limelight.ui.StreamView;

final class CrownScreenColorSampler {
    interface Callback {
        void onPalette(CrownAutoColorPalette palette);
        void onError(String message);
    }

    private static final int SAMPLE_SIZE = 48;
    private static final int FALLBACK_ACCENT = 0xFFE65A9C;

    private CrownScreenColorSampler() {
    }

    static void sample(Game game, Callback callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            callback.onError(game.getString(com.limelight.R.string.crown_auto_color_unsupported));
            return;
        }

        StreamView streamView = game.getStreamView();
        if (streamView == null || streamView.getHolder() == null || !streamView.getHolder().getSurface().isValid()) {
            callback.onError(game.getString(com.limelight.R.string.crown_auto_color_no_frame));
            return;
        }

        Bitmap bitmap = Bitmap.createBitmap(SAMPLE_SIZE, SAMPLE_SIZE, Bitmap.Config.ARGB_8888);
        PixelCopy.request(streamView, bitmap, result -> {
            if (result == PixelCopy.SUCCESS) {
                callback.onPalette(CrownAutoColorPalette.fromAccent(extractAccent(bitmap)));
            } else {
                callback.onError(game.getString(com.limelight.R.string.crown_auto_color_failed));
            }
            bitmap.recycle();
        }, new Handler(Looper.getMainLooper()));
    }

    private static int extractAccent(Bitmap bitmap) {
        double totalWeight = 0;
        double red = 0;
        double green = 0;
        double blue = 0;
        float[] hsv = new float[3];

        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                int color = bitmap.getPixel(x, y);
                Color.colorToHSV(color, hsv);
                float saturation = hsv[1];
                float value = hsv[2];
                if (value < 0.10f || value > 0.98f) {
                    continue;
                }

                double weight = Math.max(0.05, saturation) * Math.pow(value, 0.65);
                red += Color.red(color) * weight;
                green += Color.green(color) * weight;
                blue += Color.blue(color) * weight;
                totalWeight += weight;
            }
        }

        if (totalWeight < 1) {
            return FALLBACK_ACCENT;
        }

        int accent = Color.rgb(
                clampToByte(red / totalWeight),
                clampToByte(green / totalWeight),
                clampToByte(blue / totalWeight)
        );
        Color.colorToHSV(accent, hsv);
        if (hsv[1] < 0.18f || hsv[2] < 0.18f) {
            return FALLBACK_ACCENT;
        }
        return accent;
    }

    private static int clampToByte(double value) {
        return Math.max(0, Math.min(255, (int) Math.round(value)));
    }
}
