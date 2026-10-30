package com.limelight.gamemenu

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.Game
import com.limelight.binding.video.MediaCodecDecoderRenderer
import com.limelight.computers.ComputerDatabaseManager
import com.limelight.computers.IdentityManager
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.utils.AppSettingsManager
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Opt-in acceptance test: uses a paired, reachable real Sunshine host and real video output. */
class ResolutionHotSwitchDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val report = StringBuilder()

    @Test fun realStreamSwitchesResolutionAndRecoversFromMissingVideo() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit real-device/paired-host opt-in", args.getString("resolutionDeviceTest") == "true")
        val uuid = requireNotNull(args.getString("hostUuid"))
        val appId = requireNotNull(args.getString("appId")) { "Pass the Sunshine appId to launch" }.toInt()
        val expectFallback = args.getString("expectFallback") == "true"
        val database = ComputerDatabaseManager(context)
        val computer = requireNotNull(database.getComputerByUUID(uuid))
        database.close()
        val address = computer.activeAddress ?: computer.localAddress ?: computer.manualAddress!!
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val before = prefs.all.toMap()
        val appPrefs = context.getSharedPreferences("app_last_settings", 0)
        val appBefore = appPrefs.all.toMap()
        var scenario: ActivityScenario<Game>? = null
        try {
            prefs.edit().putBoolean("checkbox_framegen_enabled", false)
                .putBoolean("use_external_display", false)
                .putBoolean("checkbox_enable_hdr", false)
                .putString("list_resolution", "1920x1080")
                .putString("list_fps", "60").commit()
            val config = PreferenceConfiguration.readPreferences(context).apply {
                width = 1920; height = 1080; fps = 60
                isNativeResolution = false; isCustomResolution = false
                enableHdr = false
                args.getString("codec")?.let { requested ->
                    videoFormat = when (requested) {
                        "h264" -> PreferenceConfiguration.FormatOption.FORCE_H264
                        "hevc" -> PreferenceConfiguration.FormatOption.FORCE_HEVC
                        "av1" -> PreferenceConfiguration.FormatOption.FORCE_AV1
                        else -> PreferenceConfiguration.FormatOption.AUTO
                    }
                }
            }
            val intent = Intent(context, Game::class.java).apply {
                putExtra(Game.EXTRA_HOST, address.address)
                putExtra(Game.EXTRA_PORT, address.port)
                putExtra(Game.EXTRA_HTTPS_PORT, computer.httpsPort)
                putExtra(Game.EXTRA_PC_UUID, computer.uuid)
                putExtra(Game.EXTRA_PC_NAME, computer.name)
                putExtra(Game.EXTRA_APP_NAME, "Desktop")
                putExtra(Game.EXTRA_APP_ID, appId)
                putExtra(Game.EXTRA_APP_HDR, false)
                putExtra(Game.EXTRA_UNIQUEID, IdentityManager(context).uniqueId)
                putExtra(Game.EXTRA_PAIR_NAME, computer.getPairName(context))
                putExtra(Game.EXTRA_PC_USEVDD, true)
                putExtra(Game.EXTRA_SERVER_CERT, computer.serverCert!!.encoded)
                AppSettingsManager.addLastSettingsToIntent(this, config)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            scenario = ActivityScenario.launch(intent)
            await(scenario, "initial video", 30000) { game -> game.connected && framesPresented(game) > 20 }
            log("START codec=${args.getString("codec") ?: "auto"} device=${android.os.Build.MODEL}")
            for (size in listOf("1280x720", "2560x1440", "1920x1080")) {
                val original = current(scenario)
                val connection = original.conn
                val started = SystemClock.elapsedRealtime()
                val controller = onMain { original.getBitrateCardController(connection!!) }
                onMain { controller.stageResolution(size); assertTrue(controller.applyDisplayDraft()) }
                SystemClock.sleep(180)
                screenshot("switch-${size}.png")
                val parts = size.split('x').map(String::toInt)
                await(scenario, "hot switch $size", 12000) { game ->
                    !game.isApplyingDisplaySettings && game.connected &&
                        framesPresented(game) > 20 && outputSize(game) == (parts[0] to parts[1])
                }
                val active = current(scenario)
                if (expectFallback) {
                    assertNotSame("Unavailable hot switch must reconnect", original, active)
                    assertNotSame(connection, active.conn)
                } else {
                    assertSame("Hot switch must preserve Activity", original, active)
                    assertSame("Hot switch must preserve connection", connection, active.conn)
                }
                assertFalse(active.displayedFailureDialog)
                assertEquals("Menu must describe the encoded stream", parts[0], active.prefConfig.width)
                assertEquals(parts[1], active.prefConfig.height)
                log("${if (expectFallback) "FALLBACK" else "HOT"} $size elapsedMs=${SystemClock.elapsedRealtime() - started} " +
                    "sameActivity=${original === active} sameConnection=${connection === active.conn} " +
                    "decoded=${outputSize(active)} menu=${active.prefConfig.width}x${active.prefConfig.height}")
            }

            // Fault injection: suppress actual decode/present while keeping transport alive.
            // This exercises the production 5-second watchdog and a real reconnect, not a fake host ACK.
            val stalled = current(scenario)
            val stalledConnection = stalled.conn
            val started = SystemClock.elapsedRealtime()
            val controller = onMain { stalled.getBitrateCardController(stalledConnection!!) }
            renderer(stalled).pauseProcessing()
            onMain { controller.stageResolution("1280x720"); assertTrue(controller.applyDisplayDraft()) }
            SystemClock.sleep(1000)
            screenshot("waiting-for-video.png")
            await(scenario, "timeout reconnect", 25000) { game ->
                game !== stalled && game.connected && framesPresented(game) > 20 && !game.isApplyingDisplaySettings
            }
            val recovered = current(scenario)
            assertNotSame(stalledConnection, recovered.conn)
            assertEquals(1280 to 720, outputSize(recovered))
            assertTrue(SystemClock.elapsedRealtime() - started >= 5000)
            assertFalse(recovered.displayedFailureDialog)
            log("FALLBACK elapsedMs=${SystemClock.elapsedRealtime() - started} newActivity=true newConnection=true videoResumed=true")
            screenshot("recovered.png")

            val fpsActivity = current(scenario)
            val fpsController = onMain { fpsActivity.getBitrateCardController(fpsActivity.conn!!) }
            onMain { fpsController.stageFrameRate("30"); assertTrue(fpsController.applyDisplayDraft()) }
            await(scenario, "FPS reconnect", 25000) { game ->
                game !== fpsActivity && game.connected && framesPresented(game) > 20 && game.prefConfig.fps == 30
            }
            log("FPS 30 newActivity=true videoResumed=true")
            screenshot("fps-reconnected.png")
        } finally {
            // Finish directly before close(): some OEMs block the test APK's EmptyActivity trampoline.
            scenario?.let { active ->
                if (active.state != androidx.lifecycle.Lifecycle.State.DESTROYED) {
                    active.onActivity { it.finish() }
                    instrumentation.waitForIdleSync()
                }
            }
            scenario?.close()
            instrumentation.waitForIdleSync()
            restore(prefs, before)
            restore(appPrefs, appBefore)
            File(context.getExternalFilesDir(null), "resolution-device-test/report.txt").apply {
                parentFile!!.mkdirs(); writeText(report.toString())
            }
        }
    }

    private fun renderer(game: Game): MediaCodecDecoderRenderer =
        Game::class.java.getDeclaredField("decoderRenderer").apply { isAccessible = true }
            .get(game) as MediaCodecDecoderRenderer

    private fun framesPresented(game: Game): Int =
        MediaCodecDecoderRenderer::class.java.getDeclaredField("numFramesOut").apply { isAccessible = true }
            .getInt(renderer(game))

    private fun outputSize(game: Game): Pair<Int, Int>? {
        val format = MediaCodecDecoderRenderer::class.java.getDeclaredField("outputFormat")
            .apply { isAccessible = true }.get(renderer(game)) as? MediaFormat ?: return null
        fun dimension(size: String, low: String, high: String): Int =
            if (format.containsKey(low) && format.containsKey(high)) format.getInteger(high) - format.getInteger(low) + 1
            else format.getInteger(size)
        return dimension(MediaFormat.KEY_WIDTH, "crop-left", "crop-right") to
            dimension(MediaFormat.KEY_HEIGHT, "crop-top", "crop-bottom")
    }

    private fun current(scenario: ActivityScenario<Game>): Game = onMain {
        val ref = AtomicReference<Game>(); scenario.onActivity { ref.set(it) }; ref.get()
    }

    private fun await(scenario: ActivityScenario<Game>, label: String, timeoutMs: Long, predicate: (Game) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain { predicate(current(scenario)) }) return
            SystemClock.sleep(100)
        }
        fail("Timed out: $label")
    }

    private fun <T> onMain(block: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
        val ref = AtomicReference<T>()
        instrumentation.runOnMainSync { ref.set(block()) }
        return ref.get()
    }

    private fun screenshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null), "resolution-device-test/$name").apply {
                parentFile!!.mkdirs()
                outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            bitmap.recycle()
        }
    }

    private fun log(message: String) { report.appendLine(message); Log.i("ResolutionDeviceTest", message) }

    private fun restore(prefs: SharedPreferences, values: Map<String, *>) {
        val editor = prefs.edit().clear()
        values.forEach { (key, value) -> when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>)
        } }
        editor.commit()
    }
}
