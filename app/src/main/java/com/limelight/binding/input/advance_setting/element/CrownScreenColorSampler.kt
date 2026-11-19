package com.limelight.binding.input.advance_setting.element

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy

import com.limelight.Game
import com.limelight.ui.StreamView

import java.util.ArrayList
import java.util.Random

object CrownScreenColorSampler {
    interface Callback {
        fun onPalette(palette: CrownAutoColorPalette)
        fun onError(message: String)
    }

    private const val SAMPLE_SIZE = 72
    private const val SAMPLE_COUNT = 300
    private const val CLUSTER_COUNT = 5
    private const val KMEANS_MAX_ITERATIONS = 20
    private const val FALLBACK_ACCENT = 0xFFE65A9C.toInt()
    private const val CENTER_START_X = 0.20f
    private const val CENTER_END_X = 0.80f
    private const val CENTER_START_Y = 0.15f
    private const val CENTER_END_Y = 0.75f

    @JvmStatic
    fun sample(game: Game, callback: Callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            callback.onError(game.getString(com.limelight.R.string.crown_auto_color_unsupported))
            return
        }

        val streamView: StreamView = game.streamView
        if (streamView == null || streamView.holder == null || !streamView.holder.surface.isValid) {
            callback.onError(game.getString(com.limelight.R.string.crown_auto_color_no_frame))
            return
        }

        val bitmap = Bitmap.createBitmap(SAMPLE_SIZE, SAMPLE_SIZE, Bitmap.Config.ARGB_8888)
        PixelCopy.request(streamView, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) {
                callback.onPalette(CrownAutoColorPalette.fromAccent(extractAccent(bitmap)))
            } else {
                callback.onError(game.getString(com.limelight.R.string.crown_auto_color_failed))
            }
            bitmap.recycle()
        }, Handler(Looper.getMainLooper()))
    }

    private fun extractAccent(bitmap: Bitmap): Int {
        val samples = collectSamples(bitmap)
        if (samples.size < 10) {
            return FALLBACK_ACCENT
        }

        val clusters = kMeansPlusPlus(samples, CLUSTER_COUNT, KMEANS_MAX_ITERATIONS)
        val accent = pickAccentFromClusters(clusters, samples.size)
        if (accent == null) {
            return FALLBACK_ACCENT
        }

        val accentColor = accent.toColor()
        val hsv = FloatArray(3)
        Color.colorToHSV(accentColor, hsv)
        if (hsv[1] < 0.10f || hsv[2] < 0.12f) {
            return FALLBACK_ACCENT
        }
        return accentColor
    }

    private fun collectSamples(bitmap: Bitmap): List<ColorSample> {
        val startX = Math.max(0, Math.round(bitmap.width * CENTER_START_X))
        val endX = Math.min(bitmap.width, Math.round(bitmap.width * CENTER_END_X))
        val startY = Math.max(0, Math.round(bitmap.height * CENTER_START_Y))
        val endY = Math.min(bitmap.height, Math.round(bitmap.height * CENTER_END_Y))
        val sampleWidth = Math.max(1, endX - startX)
        val sampleHeight = Math.max(1, endY - startY)

        val samples = ArrayList<ColorSample>(SAMPLE_COUNT)
        val random = Random(seedFromBitmap(bitmap))
        for (i in 0 until SAMPLE_COUNT) {
            val x = startX + random.nextInt(sampleWidth)
            val y = startY + random.nextInt(sampleHeight)
            val color = bitmap.getPixel(x, y)
            if (Color.alpha(color) < 0x80) {
                continue
            }

            val red = Color.red(color)
            val green = Color.green(color)
            val blue = Color.blue(color)
            if (red + green + blue <= 30) {
                continue
            }
            samples.add(ColorSample(red, green, blue))
        }
        return samples
    }

    private fun seedFromBitmap(bitmap: Bitmap): Long {
        var seed = 0x6D6F6F6EL
        val stepX = Math.max(1, bitmap.width / 6)
        val stepY = Math.max(1, bitmap.height / 6)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                seed = seed * 31 + (bitmap.getPixel(x, y) and 0x00FFFFFF)
                x += stepX
            }
            y += stepY
        }
        return seed
    }

    private fun kMeansPlusPlus(samples: List<ColorSample>, requestedClusterCount: Int, maxIterations: Int): List<ColorCluster> {
        val sampleCount = samples.size
        val clusterCount = Math.min(requestedClusterCount, sampleCount)
        val centers = ArrayList<ColorSample>(clusterCount)
        val random = Random(seedFromSamples(samples))
        centers.add(samples[random.nextInt(sampleCount)])

        val distances = DoubleArray(sampleCount)
        for (clusterIndex in 1 until clusterCount) {
            var totalDistance = 0.0
            for (sampleIndex in 0 until sampleCount) {
                var minDistance = Double.MAX_VALUE
                for (center in centers) {
                    minDistance = Math.min(minDistance, distanceSquared(samples[sampleIndex], center))
                }
                distances[sampleIndex] = minDistance
                totalDistance += minDistance
            }

            if (totalDistance <= 0) {
                centers.add(samples[clusterIndex])
                continue
            }

            var cursor = random.nextDouble() * totalDistance
            var selectedIndex = sampleCount - 1
            for (sampleIndex in 0 until sampleCount) {
                cursor -= distances[sampleIndex]                if (cursor <= 0) {
                    selectedIndex = sampleIndex
                    break
                }
            }
            centers.add(samples[selectedIndex])
        }

        val assignments = IntArray(sampleCount)
        for (iteration in 0 until maxIterations) {
            for (sampleIndex in 0 until sampleCount) {
                var minDistance = Double.MAX_VALUE
                var bestCluster = 0
                for (clusterIndex in 0 until clusterCount) {
                    val distance = distanceSquared(samples[sampleIndex], centers[clusterIndex])
                    if (distance < minDistance) {
                        minDistance = distance
                        bestCluster = clusterIndex
                    }
                }
                assignments[sampleIndex] = bestCluster
            }

            var changed = false
            for (clusterIndex in 0 until clusterCount) {
                var red = 0
                var green = 0
                var blue = 0
                var count = 0
                for (sampleIndex in 0 until sampleCount) {
                    if (assignments[sampleIndex] != clusterIndex) {
                        continue
                    }
                    val sample = samples[sampleIndex]
                    red += sample.red
                    green += sample.green
                    blue += sample.blue
                    count++
                }
                if (count == 0) {
                    continue
                }

                val nextCenter = ColorSample(
                    Math.round(red / count.toFloat()),
                    Math.round(green / count.toFloat()),
                    Math.round(blue / count.toFloat())
                )
                val currentCenter = centers[clusterIndex]
                if (!currentCenter.sameColor(nextCenter)) {
                    centers[clusterIndex] = nextCenter
                    changed = true
                }
            }
            if (!changed) {
                break
            }
        }

        val counts = IntArray(clusterCount)
        for (assignment in assignments) {
            counts[assignment]++
        }

        val clusters = ArrayList<ColorCluster>(clusterCount)
        for (clusterIndex in 0 until clusterCount) {
            clusters.add(ColorCluster(centers[clusterIndex], counts[clusterIndex]))
        }
        clusters.sortWith { left, right -> right.count - left.count }
        return clusters
    }

    private fun pickAccentFromClusters(clusters: List<ColorCluster>, totalSamples: Int): ColorSample? {
        if (clusters.isEmpty()) {
            return null
        }

        var bestCluster: ColorCluster? = null
        var bestScore = -1.0
        for (cluster in clusters) {
            val ratio = cluster.count / totalSamples.toDouble()
            val brightness = cluster.center.red + cluster.center.green + cluster.center.blue
            if (ratio < 0.05 || brightness <= 30) {
                continue
            }

            val saturation = saturation(cluster.center)
            val value = Math.max(Math.max(cluster.center.red, cluster.center.green), cluster.center.blue) / 255.0
            val score = saturation * Math.pow(ratio, 0.3) * Math.pow(value, 0.2)
            if (score > bestScore) {
                bestScore = score
                bestCluster = cluster
            }
        }
        return if (bestCluster != null) bestCluster.center else clusters[0].center
    }

    private fun seedFromSamples(samples: List<ColorSample>): Long {
        var seed = 0xC001D00DL
        val step = Math.max(1, samples.size / 16)
        var i = 0
        while (i < samples.size) {
            val sample = samples[i]
            seed = seed * 31 + sample.red * 3L + sample.green * 5L + sample.blue * 7L
            i += step
        }
        return seed
    }

    private fun distanceSquared(left: ColorSample, right: ColorSample): Double {
        val red = left.red - right.red
        val green = left.green - right.green
        val blue = left.blue - right.blue
        return (red * red + green * green + blue * blue).toDouble()
    }

    private fun saturation(sample: ColorSample): Double {
        val max = Math.max(Math.max(sample.red, sample.green), sample.blue)
        val min = Math.min(Math.min(sample.red, sample.green), sample.blue)
        return if (max > 0) (max - min) / max.toDouble() else 0.0
    }

    private class ColorSample(val red: Int, val green: Int, val blue: Int) {
        fun sameColor(other: ColorSample): Boolean {
            return red == other.red && green == other.green && blue == other.blue
        }

        fun toColor(): Int {
            return Color.rgb(red, green, blue)
        }
    }

    private class ColorCluster(val center: ColorSample, val count: Int)
}
