package com.limelight.filetransfer

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.limelight.Game
import com.limelight.R
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException
import java.security.SecureRandom

/**
 * Owns the file receive server for one streaming session and the dialog that explains how to
 * reach it from the PC. The server only runs after the user opens that dialog and stops with
 * the stream.
 */
class FileReceiveController(private val game: Game) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var server: FileReceiveServer? = null
    private var token = ""

    /** Starts the server if needed and shows where the PC browser should go. */
    fun show() {
        val port = try {
            ensureStarted()
        } catch (e: IOException) {
            Toast.makeText(game, R.string.file_receive_start_failed, Toast.LENGTH_LONG).show()
            return
        }

        val addresses = localAddresses()
        if (addresses.isEmpty()) {
            stop()
            Toast.makeText(game, R.string.file_receive_no_network, Toast.LENGTH_LONG).show()
            return
        }
        val urls = addresses.map { "http://$it:$port/$token" }

        AlertDialog.Builder(game)
            .setTitle(R.string.file_receive_title)
            .setMessage(game.getString(R.string.file_receive_message, urls.joinToString("\n"), saveLocationLabel()))
            .setPositiveButton(R.string.file_receive_type_on_pc) { _, _ ->
                // Types the address into whatever has focus on the PC, normally the address bar.
                game.conn?.sendUtf8Text(urls.first())
            }
            .setNeutralButton(R.string.file_receive_stop) { _, _ ->
                stop()
                Toast.makeText(game, R.string.file_receive_stopped, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.file_receive_close, null)
            .show()
    }

    fun stop() {
        server?.stop()
        server = null
    }

    @Throws(IOException::class)
    private fun ensureStarted(): Int {
        server?.takeIf { it.isRunning }?.let { return it.port }

        token = newToken()
        val created = FileReceiveServer(token, DownloadsSink(game.applicationContext), uploadPage(), listener)
        val port = created.start(FileReceiveServer.DEFAULT_PORT)
        server = created
        return port
    }

    private val listener = object : FileReceiveServer.Listener {
        override fun onFileReceived(name: String, bytes: Long) =
            toast(game.getString(R.string.file_receive_saved, name))

        override fun onFileFailed(name: String) =
            toast(game.getString(R.string.file_receive_failed, name))

        override fun onLockedOut() {
            mainHandler.post { server = null }
            toast(game.getString(R.string.file_receive_locked_out))
        }
    }

    private fun toast(text: String) {
        mainHandler.post {
            if (!game.isFinishing) Toast.makeText(game, text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveLocationLabel(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER_NAME"
        } else {
            legacyDirectory(game).absolutePath
        }

    private fun uploadPage(): String = UPLOAD_PAGE
        .replace("{{title}}", escapeHtml(game.getString(R.string.file_receive_page_title)))
        .replace("{{hint}}", escapeHtml(game.getString(R.string.file_receive_page_hint)))
        .replace("{{choose}}", escapeHtml(game.getString(R.string.file_receive_page_choose)))
        .replace("{{done}}", escapeJsString(game.getString(R.string.file_receive_page_done)))
        .replace("{{error}}", escapeJsString(game.getString(R.string.file_receive_page_error)))

    /** Stores files in the shared Downloads collection, or the app's own folder before Android 10. */
    private class DownloadsSink(private val context: Context) : FileReceiveServer.FileSink {
        override fun create(name: String): FileReceiveServer.Target =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) mediaStoreTarget(name) else fileTarget(name)

        @RequiresApi(Build.VERSION_CODES.Q)
        private fun mediaStoreTarget(name: String): FileReceiveServer.Target {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER_NAME")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Unable to create a download entry")
            val stream = try {
                resolver.openOutputStream(uri)
            } catch (e: Exception) {
                null
            }
            if (stream == null) {
                resolver.delete(uri, null, null)
                throw IOException("Unable to open the download entry")
            }
            return object : FileReceiveServer.Target {
                override val output: OutputStream = stream

                override fun commit() {
                    val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    if (resolver.update(uri, done, null, null) == 0) {
                        throw IOException("Unable to publish the download entry")
                    }
                }

                override fun abort() {
                    try {
                        resolver.delete(uri, null, null)
                    } catch (_: Exception) {
                    }
                }
            }
        }

        private fun fileTarget(name: String): FileReceiveServer.Target {
            val directory = legacyDirectory(context)
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IOException("Unable to create ${directory.path}")
            }
            val file = uniqueFile(directory, name)
            val stream = file.outputStream()
            return object : FileReceiveServer.Target {
                override val output: OutputStream = stream
                override fun commit() = Unit
                override fun abort() {
                    file.delete()
                }
            }
        }

        private fun uniqueFile(directory: File, name: String): File {
            var candidate = File(directory, name)
            val base = name.substringBeforeLast('.', name)
            val extension = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
            var index = 1
            while (candidate.exists()) {
                candidate = File(directory, "$base ($index)$extension")
                index++
            }
            return candidate
        }
    }

    companion object {
        private const val FOLDER_NAME = "Moonlight V+"
        private const val TOKEN_DIGITS = 6

        private fun legacyDirectory(context: Context): File =
            File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, FOLDER_NAME)

        private fun newToken(): String {
            val random = SecureRandom()
            return buildString { repeat(TOKEN_DIGITS) { append(random.nextInt(10)) } }
        }

        /** IPv4 addresses the PC could use to reach this device, Wi-Fi and Ethernet first. */
        private fun localAddresses(): List<String> {
            val found = ArrayList<Pair<Int, String>>()
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
                for (network in interfaces) {
                    if (!network.isUp || network.isLoopback) continue
                    val priority = when {
                        network.name.startsWith("wlan") || network.name.startsWith("eth") -> 0
                        network.name.startsWith("rmnet") || network.name.startsWith("ccmni") -> 2
                        else -> 1
                    }
                    for (address in network.inetAddresses) {
                        if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                            address.hostAddress?.let { found.add(priority to it) }
                        }
                    }
                }
            } catch (e: SocketException) {
                return emptyList()
            }
            return found.sortedBy { it.first }.map { it.second }.distinct()
        }

        private fun escapeHtml(text: String): String = text
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        /** For text placed inside a single-quoted script literal that ends up in textContent. */
        private fun escapeJsString(text: String): String = text
            .replace("\\", "\\\\").replace("'", "\\'").replace("<", "\\x3C")
            .replace("\n", " ").replace("\r", " ")

        private val UPLOAD_PAGE = """
<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>{{title}}</title>
<style>
body{font-family:system-ui,sans-serif;margin:0;padding:24px;background:#14151a;color:#eee}
h1{font-size:20px;font-weight:600}
#drop{border:2px dashed #667;border-radius:12px;padding:48px 16px;text-align:center;margin:16px 0}
#drop.over{border-color:#f7a;background:#22232b}
button{font-size:15px;padding:8px 16px;border-radius:8px;border:0;background:#f7a;color:#111;cursor:pointer}
li{margin:8px 0;list-style:none}ul{padding:0}
progress{width:100%;height:8px}.ok{color:#8d8}.bad{color:#f88}
</style></head><body>
<h1>{{title}}</h1>
<div id="drop"><p>{{hint}}</p><button id="pick">{{choose}}</button>
<input id="files" type="file" multiple hidden></div>
<ul id="list"></ul>
<script>
var base = location.pathname.replace(/\/+$/, '');
var queue = [], busy = false;
var drop = document.getElementById('drop'), input = document.getElementById('files');
document.getElementById('pick').onclick = function () { input.click(); };
input.onchange = function () { add(input.files); input.value = ''; };
drop.ondragover = function (e) { e.preventDefault(); drop.className = 'over'; };
drop.ondragleave = function () { drop.className = ''; };
drop.ondrop = function (e) { e.preventDefault(); drop.className = ''; add(e.dataTransfer.files); };
function add(files) {
  for (var i = 0; i < files.length; i++) {
    var row = document.createElement('li'), bar = document.createElement('progress');
    var label = document.createElement('div');
    label.textContent = files[i].name;
    bar.max = 1; bar.value = 0;
    row.appendChild(label); row.appendChild(bar);
    document.getElementById('list').appendChild(row);
    queue.push({file: files[i], bar: bar, label: label});
  }
  next();
}
function next() {
  if (busy || !queue.length) return;
  busy = true;
  var item = queue.shift(), xhr = new XMLHttpRequest();
  xhr.open('PUT', base + '/upload?name=' + encodeURIComponent(item.file.name));
  xhr.upload.onprogress = function (e) { if (e.lengthComputable) item.bar.value = e.loaded / e.total; };
  xhr.onloadend = function () {
    var ok = xhr.status === 200;
    item.bar.value = ok ? 1 : item.bar.value;
    item.label.textContent = item.file.name + ' - ' + (ok ? '{{done}}' : '{{error}}');
    item.label.className = ok ? 'ok' : 'bad';
    busy = false; next();
  };
  xhr.send(item.file);
}
</script></body></html>
""".trimIndent()
    }
}
