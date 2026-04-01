package com.limelight.binding.video

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.PowerManager
import android.os.Process
import com.limelight.LimeLog

/**
 * Manages system performance hints and thermal monitoring for low-latency video decoding.
 *
 * - PerformanceHintManager (API 31+): Informs the system about actual work durations,
 *   allowing DVFS to maintain optimal clock speeds instead of aggressive down-clocking
 *   between frames.
 * - ThermalStatusListener (API 29+): Monitors device thermal state to proactively
 *   avoid performance cliffs from thermal throttling during long gaming sessions.
 */
internal class PerformanceBoostManager(private val context: Context) {

    fun interface ThermalThrottleListener {
        fun onThermalThrottle(thermalStatus: Int)
    }

    private var hintSession: PerformanceHintManager.Session? = null
    private var thermalStatusListener: PowerManager.OnThermalStatusChangedListener? = null

    /**
     * Creates a performance hint session for the calling thread (plus any additional threads).
     * Must be called from a thread that should receive performance hints (e.g., renderer thread).
     */
    fun createHintSession(targetFps: Int, vararg additionalTids: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        try {
            val hintManager = context.getSystemService(Context.PERFORMANCE_HINT_SERVICE)
                as? PerformanceHintManager ?: return

            val currentTid = Process.myTid()
            val tids = if (additionalTids.isNotEmpty()) {
                intArrayOf(currentTid, *additionalTids)
            } else {
                intArrayOf(currentTid)
            }

            val targetDurationNs = 1_000_000_000L / targetFps
            hintSession = hintManager.createHintSession(tids, targetDurationNs)?.also {
                LimeLog.info(
                    "PerformanceHint session: threads=${tids.size}, " +
                        "target=${targetDurationNs / 1_000_000}ms"
                )
            }
        } catch (e: Exception) {
            LimeLog.warning("PerformanceHint unavailable: ${e.message}")
            hintSession = null
        }
    }

    /**
     * Reports actual frame processing duration to help system DVFS decisions.
     * Call after each frame is decoded to maintain optimal CPU/GPU frequencies.
     */
    fun reportActualWorkDuration(actualDurationNs: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                hintSession?.reportActualWorkDuration(actualDurationNs)
            } catch (_: Exception) {
                // Some devices may not fully support this
            }
        }
    }

    /**
     * Starts monitoring device thermal status. Fires the callback when
     * severe throttling is detected.
     */
    fun startThermalMonitoring(listener: ThermalThrottleListener? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        try {
            val pm = context.getSystemService(Context.POWER_SERVICE)
                as? PowerManager ?: return

            val statusListener = PowerManager.OnThermalStatusChangedListener { status ->
                if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
                    LimeLog.warning("Thermal throttling: status=$status")
                    listener?.onThermalThrottle(status)
                }
            }
            thermalStatusListener = statusListener
            pm.addThermalStatusListener(statusListener)
            LimeLog.info("Thermal monitoring started")
        } catch (e: Exception) {
            LimeLog.warning("Thermal monitoring unavailable: ${e.message}")
        }
    }

    /**
     * Stops all performance management and releases resources.
     */
    fun close() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { hintSession?.close() }
            hintSession = null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalStatusListener?.let { listener ->
                runCatching {
                    (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                        ?.removeThermalStatusListener(listener)
                }
            }
            thermalStatusListener = null
        }
    }
}
