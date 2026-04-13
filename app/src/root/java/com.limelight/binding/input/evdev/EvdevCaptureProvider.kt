package com.limelight.binding.input.evdev

import android.app.Activity
import android.os.Build
import android.os.Looper
import android.widget.Toast

import com.limelight.LimeLog
import com.limelight.binding.input.capture.InputCaptureProvider

import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

class EvdevCaptureProvider(
    private val activity: Activity,
    private val listener: EvdevListener
) : InputCaptureProvider() {

    private val libraryPath: String = activity.applicationInfo.nativeLibraryDir

    private var shutdown = false
    private var evdevIn: InputStream? = null
    private var evdevOut: OutputStream? = null
    private var su: Process? = null
    private var servSock: ServerSocket? = null
    private var evdevSock: Socket? = null
    private var started = false

    private val handlerThread = object : Thread() {
        override fun run() {
            var deltaX = 0
            var deltaY = 0
            var deltaVScroll: Byte = 0
            var deltaHScroll: Byte = 0

            try {
                servSock = ServerSocket(0, 1)
            } catch (e: IOException) {
                e.printStackTrace()
                return
            }

            val evdevReaderCmd = libraryPath + File.separatorChar + "libevdev_reader.so " + servSock!!.localPort

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    su = ProcessBuilder("su", "-c", evdevReaderCmd).start()
                } catch (e: IOException) {
                    reportDeviceNotRooted()
                    e.printStackTrace()
                    return
                }
            } else {
                val builder = ProcessBuilder("su")
                builder.redirectErrorStream(true)

                try {
                    su = builder.start()
                } catch (e: IOException) {
                    reportDeviceNotRooted()
                    e.printStackTrace()
                    return
                }

                val suOut = DataOutputStream(su!!.outputStream)
                try {
                    suOut.writeChars("$evdevReaderCmd\n")
                } catch (e: IOException) {
                    reportDeviceNotRooted()
                    e.printStackTrace()
                    return
                }
            }

            LimeLog.info("Waiting for EvdevReader connection to port " + servSock!!.localPort)
            try {
                evdevSock = servSock!!.accept()
                evdevIn = evdevSock!!.getInputStream()
                evdevOut = evdevSock!!.getOutputStream()
            } catch (e: IOException) {
                e.printStackTrace()
                return
            }
            LimeLog.info("EvdevReader connected from port " + evdevSock!!.port)

            while (!isInterrupted && !shutdown) {
                val event: EvdevEvent?
                try {
                    event = EvdevReader.read(evdevIn!!)
                } catch (e: IOException) {
                    break
                }
                if (event == null) break

                when (event.type) {
                    EvdevEvent.EV_SYN -> {
                        if (deltaX != 0 || deltaY != 0) {
                            listener.mouseMove(deltaX, deltaY)
                            deltaX = 0
                            deltaY = 0
                        }
                        if (deltaVScroll.toInt() != 0) {
                            listener.mouseVScroll(deltaVScroll)
                            deltaVScroll = 0
                        }
                        if (deltaHScroll.toInt() != 0) {
                            listener.mouseHScroll(deltaHScroll)
                            deltaHScroll = 0
                        }
                    }

                    EvdevEvent.EV_REL -> {
                        when (event.code) {
                            EvdevEvent.REL_X -> deltaX = event.value
                            EvdevEvent.REL_Y -> deltaY = event.value
                            EvdevEvent.REL_HWHEEL -> deltaHScroll = event.value.toByte()
                            EvdevEvent.REL_WHEEL -> deltaVScroll = event.value.toByte()
                        }
                    }

                    EvdevEvent.EV_KEY -> {
                        when (event.code) {
                            EvdevEvent.BTN_LEFT ->
                                listener.mouseButtonEvent(EvdevListener.BUTTON_LEFT, event.value != 0)
                            EvdevEvent.BTN_MIDDLE ->
                                listener.mouseButtonEvent(EvdevListener.BUTTON_MIDDLE, event.value != 0)
                            EvdevEvent.BTN_RIGHT ->
                                listener.mouseButtonEvent(EvdevListener.BUTTON_RIGHT, event.value != 0)
                            EvdevEvent.BTN_SIDE ->
                                listener.mouseButtonEvent(EvdevListener.BUTTON_X1, event.value != 0)
                            EvdevEvent.BTN_EXTRA ->
                                listener.mouseButtonEvent(EvdevListener.BUTTON_X2, event.value != 0)
                            EvdevEvent.BTN_FORWARD, EvdevEvent.BTN_BACK, EvdevEvent.BTN_TASK -> {
                                // Other unhandled mouse buttons
                            }
                            else -> {
                                val keyCode = EvdevTranslator.translateEvdevKeyCode(event.code)
                                if (keyCode.toInt() != 0) {
                                    listener.keyboardEvent(event.value != 0, keyCode)
                                }
                            }
                        }
                    }

                    EvdevEvent.EV_MSC -> { }
                }
            }
        }
    }

    private fun reportDeviceNotRooted() {
        activity.runOnUiThread {
            Toast.makeText(activity, "This device is not rooted - Mouse capture is unavailable", Toast.LENGTH_LONG).show()
        }
    }

    private fun runInNetworkSafeContextSynchronously(runnable: Runnable) {
        if (Looper.getMainLooper().thread === Thread.currentThread()) {
            val t = Thread(runnable)
            t.start()
            try {
                t.join()
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        } else {
            runnable.run()
        }
    }

    override fun showCursor() {
        super.showCursor()
        runInNetworkSafeContextSynchronously {
            if (started && !shutdown && evdevOut != null) {
                try {
                    evdevOut!!.write(UNGRAB_REQUEST.toInt())
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun hideCursor() {
        super.hideCursor()
        runInNetworkSafeContextSynchronously {
            if (started && !shutdown && evdevOut != null) {
                try {
                    evdevOut!!.write(REGRAB_REQUEST.toInt())
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun enableCapture() {
        if (!started) {
            handlerThread.start()
            started = true
        }
        super.enableCapture()
    }

    override fun destroy() {
        if (!started) return

        shutdown = true
        handlerThread.interrupt()

        runInNetworkSafeContextSynchronously {
            try {
                servSock?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }

            try {
                evdevSock?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }

            try {
                evdevIn?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }

            try {
                evdevOut?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        su?.destroy()

        try {
            handlerThread.join()
        } catch (_: InterruptedException) { }
    }

    companion object {
        private const val UNGRAB_REQUEST: Byte = 1
        private const val REGRAB_REQUEST: Byte = 2
    }
}
