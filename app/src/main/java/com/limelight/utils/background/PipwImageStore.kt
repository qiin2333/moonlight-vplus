package com.limelight.utils.background

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Current-request sharing only: the last owner closes the download and file. */
class PipwImageStore(
    private val loader: (PipwPool, String, PipwDownloadOperation) -> File,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val lock = Any()
    private val entries = mutableMapOf<String, Entry>()

    internal inner class Entry(val key: String, pool: PipwPool, url: String) {
        var references = 0
        var file: File? = null
        val operation = PipwDownloadOperation()
        val result = scope.async(start = CoroutineStart.LAZY) {
            val downloaded = loader(pool, url, operation)
            synchronized(lock) {
                if (references == 0) {
                    downloaded.delete()
                    throw CancellationException("Pipw image no longer owned")
                }
                file = downloaded
            }
            downloaded
        }
    }

    inner class Lease internal constructor(private val entry: Entry) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        val key: String get() = entry.key

        suspend fun await(): File {
            if (closed.get()) throw CancellationException("Pipw lease closed")
            val file = entry.result.await()
            if (closed.get()) throw CancellationException("Pipw lease closed")
            return file
        }

        fun retainReady(): Lease? = synchronized(lock) {
            if (closed.get() || entry.file == null) null
            else {
                entry.references++
                Lease(entry)
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            val last = synchronized(lock) {
                entry.references--
                if (entry.references == 0) {
                    entries.remove(entry.key)
                    true
                } else false
            }
            if (last) {
                entry.operation.cancel()
                entry.result.cancel()
                entry.file?.let { file -> scope.launch { file.delete() } }
            }
        }
    }

    fun acquire(key: String, pool: PipwPool, url: String): Lease = synchronized(lock) {
        val entry = entries.getOrPut(key) { Entry(key, pool, url) }
        entry.references++
        Lease(entry)
    }
}
