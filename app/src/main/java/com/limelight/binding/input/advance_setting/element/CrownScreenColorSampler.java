package com.limelight.binding.input.advance_setting.element;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;

import com.limelight.Game;
import com.limelight.ui.StreamView;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

final class CrownScreenColorSampler {
    interface Callback {
        void onPalette(CrownAutoColorPalette palette);
        void onError(String message);
    }

    private static final int SAMPLE_SIZE = 72;
    private static final int SAMPLE_COUNT = 300;
    private static final int CLUSTER_COUNT = 5;
    private static final int KMEANS_MAX_ITERATIONS = 20;
    private static final int FALLBACK_ACCENT = 0xFFE65A9C;
    private static final float CENTER_START_X = 0.20f;
    private static final float CENTER_END_X = 0.80f;
    private static final float CENTER_START_Y = 0.15f;
    private static final float CENTER_END_Y = 0.75f;

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
        List<ColorSample> samples = collectSamples(bitmap);
        if (samples.size() < 10) {
            return FALLBACK_ACCENT;
        }

        List<ColorCluster> clusters = kMeansPlusPlus(samples, CLUSTER_COUNT, KMEANS_MAX_ITERATIONS);
        ColorSample accent = pickAccentFromClusters(clusters, samples.size());
        if (accent == null) {
            return FALLBACK_ACCENT;
        }

        int accentColor = accent.toColor();
        float[] hsv = new float[3];
        Color.colorToHSV(accentColor, hsv);
        if (hsv[1] < 0.10f || hsv[2] < 0.12f) {
            return FALLBACK_ACCENT;
        }
        return accentColor;
    }

    private static List<ColorSample> collectSamples(Bitmap bitmap) {
        int startX = Math.max(0, Math.round(bitmap.getWidth() * CENTER_START_X));
        int endX = Math.min(bitmap.getWidth(), Math.round(bitmap.getWidth() * CENTER_END_X));
        int startY = Math.max(0, Math.round(bitmap.getHeight() * CENTER_START_Y));
        int endY = Math.min(bitmap.getHeight(), Math.round(bitmap.getHeight() * CENTER_END_Y));
        int sampleWidth = Math.max(1, endX - startX);
        int sampleHeight = Math.max(1, endY - startY);

        List<ColorSample> samples = new ArrayList<>(SAMPLE_COUNT);
        Random random = new Random(seedFromBitmap(bitmap));
        for (int i = 0; i < SAMPLE_COUNT; i++) {
            int x = startX + random.nextInt(sampleWidth);
            int y = startY + random.nextInt(sampleHeight);
            int color = bitmap.getPixel(x, y);
            if (Color.alpha(color) < 0x80) {
                continue;
            }

            int red = Color.red(color);
            int green = Color.green(color);
            int blue = Color.blue(color);
            if (red + green + blue <= 30) {
                continue;
            }
            samples.add(new ColorSample(red, green, blue));
        }
        return samples;
    }

    private static long seedFromBitmap(Bitmap bitmap) {
        long seed = 0x6D6F6F6EL;
        int stepX = Math.max(1, bitmap.getWidth() / 6);
        int stepY = Math.max(1, bitmap.getHeight() / 6);
        for (int y = 0; y < bitmap.getHeight(); y += stepY) {
            for (int x = 0; x < bitmap.getWidth(); x += stepX) {
                seed = seed * 31 + (bitmap.getPixel(x, y) & 0x00FFFFFF);
            }
        }
        return seed;
    }

    private static List<ColorCluster> kMeansPlusPlus(List<ColorSample> samples, int requestedClusterCount, int maxIterations) {
        int sampleCount = samples.size();
        int clusterCount = Math.min(requestedClusterCount, sampleCount);
        List<ColorSample> centers = new ArrayList<>(clusterCount);
        Random random = new Random(seedFromSamples(samples));
        centers.add(samples.get(random.nextInt(sampleCount)));

        double[] distances = new double[sampleCount];
        for (int clusterIndex = 1; clusterIndex < clusterCount; clusterIndex++) {
            double totalDistance = 0;
            for (int sampleIndex = 0; sampleIndex < sampleCount; sampleIndex++) {
                double minDistance = Double.MAX_VALUE;
                for (ColorSample center : centers) {
                    minDistance = Math.min(minDistance, distanceSquared(samples.get(sampleIndex), center));
                }
                distances[sampleIndex] = minDistance;
                totalDistance += minDistance;
            }

            if (totalDistance <= 0) {
                centers.add(samples.get(clusterIndex));
                continue;
            }

            double cursor = random.nextDouble() * totalDistance;
            int selectedIndex = sampleCount - 1;
            for (int sampleIndex = 0; sampleIndex < sampleCount; sampleIndex++) {
                cursor -= distances[sampleIndex];
                if (cursor <= 0) {
                    selectedIndex = sampleIndex;
                    break;
                }
            }
            centers.add(samples.get(selectedIndex));
        }

        int[] assignments = new int[sampleCount];
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            for (int sampleIndex = 0; sampleIndex < sampleCount; sampleIndex++) {
                double minDistance = Double.MAX_VALUE;
                int bestCluster = 0;
                for (int clusterIndex = 0; clusterIndex < clusterCount; clusterIndex++) {
                    double distance = distanceSquared(samples.get(sampleIndex), centers.get(clusterIndex));
                    if (distance < minDistance) {
                        minDistance = distance;
                        bestCluster = clusterIndex;
                    }
                }
                assignments[sampleIndex] = bestCluster;
            }

            boolean changed = false;
            for (int clusterIndex = 0; clusterIndex < clusterCount; clusterIndex++) {
                int red = 0;
                int green = 0;
                int blue = 0;
                int count = 0;
                for (int sampleIndex = 0; sampleIndex < sampleCount; sampleIndex++) {
                    if (assignments[sampleIndex] != clusterIndex) {
                        continue;
                    }
                    ColorSample sample = samples.get(sampleIndex);
                    red += sample.red;
                    green += sample.green;
                    blue += sample.blue;
                    count++;
                }
                if (count == 0) {
                    continue;
                }

                ColorSample nextCenter = new ColorSample(
                        Math.round(red / (float) count),
                        Math.round(green / (float) count),
                        Math.round(blue / (float) count)
                );
                ColorSample currentCenter = centers.get(clusterIndex);
                if (!currentCenter.sameColor(nextCenter)) {
                    centers.set(clusterIndex, nextCenter);
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }

        int[] counts = new int[clusterCount];
        for (int assignment : assignments) {
            counts[assignment]++;
        }

        List<ColorCluster> clusters = new ArrayList<>(clusterCount);
        for (int clusterIndex = 0; clusterIndex < clusterCount; clusterIndex++) {
            clusters.add(new ColorCluster(centers.get(clusterIndex), counts[clusterIndex]));
        }
        clusters.sort((left, right) -> right.count - left.count);
        return clusters;
    }

    private static ColorSample pickAccentFromClusters(List<ColorCluster> clusters, int totalSamples) {
        if (clusters.isEmpty()) {
            return null;
        }

        ColorCluster bestCluster = null;
        double bestScore = -1;
        for (ColorCluster cluster : clusters) {
            double ratio = cluster.count / (double) totalSamples;
            int brightness = cluster.center.red + cluster.center.green + cluster.center.blue;
            if (ratio < 0.05 || brightness <= 30) {
                continue;
            }

            double saturation = saturation(cluster.center);
            double value = Math.max(Math.max(cluster.center.red, cluster.center.green), cluster.center.blue) / 255.0;
            double score = saturation * Math.pow(ratio, 0.3) * Math.pow(value, 0.2);
            if (score > bestScore) {
                bestScore = score;
                bestCluster = cluster;
            }
        }
        return bestCluster != null ? bestCluster.center : clusters.get(0).center;
    }

    private static long seedFromSamples(List<ColorSample> samples) {
        long seed = 0xC001D00DL;
        int step = Math.max(1, samples.size() / 16);
        for (int i = 0; i < samples.size(); i += step) {
            ColorSample sample = samples.get(i);
            seed = seed * 31 + sample.red * 3L + sample.green * 5L + sample.blue * 7L;
        }
        return seed;
    }

    private static double distanceSquared(ColorSample left, ColorSample right) {
        int red = left.red - right.red;
        int green = left.green - right.green;
        int blue = left.blue - right.blue;
        return red * red + green * green + blue * blue;
    }

    private static double saturation(ColorSample sample) {
        int max = Math.max(Math.max(sample.red, sample.green), sample.blue);
        int min = Math.min(Math.min(sample.red, sample.green), sample.blue);
        return max > 0 ? (max - min) / (double) max : 0;
    }

    private static class ColorSample {
        final int red;
        final int green;
        final int blue;

        ColorSample(int red, int green, int blue) {
            this.red = red;
            this.green = green;
            this.blue = blue;
        }

        boolean sameColor(ColorSample other) {
            return red == other.red && green == other.green && blue == other.blue;
        }

        int toColor() {
            return Color.rgb(red, green, blue);
        }
    }

    private static class ColorCluster {
        final ColorSample center;
        final int count;

        ColorCluster(ColorSample center, int count) {
            this.center = center;
            this.count = count;
        }
    }
}
