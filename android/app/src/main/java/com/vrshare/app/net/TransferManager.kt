package com.vrshare.app.net

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.vrshare.app.core.AppState
import com.vrshare.app.core.PeerDevice
import com.vrshare.app.core.Store
import com.vrshare.app.core.TransferRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Upload queue for POST /api/files (spec §3.8): multipart for fresh transfers,
 * raw octet-stream + X-Offset header for resume. Up to 2 parallel transfers
 * (spec §5). Progress and cancel are exposed per task.
 */
object TransferManager {
    enum class State { QUEUED, UPLOADING, DONE, FAILED, CANCELLED }

    data class Task(
        val id: String,
        val deviceId: String,
        val deviceName: String,
        val uri: Uri,
        val name: String,
        val size: Long,
        var sent: Long = 0,
        var state: State = State.QUEUED,
        var error: String? = null,
        internal var job: Job? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks

    private lateinit var appCtx: Context

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    fun enqueue(device: PeerDevice, uris: List<Uri>) {
        val cr = appCtx.contentResolver
        val fresh = uris.mapNotNull { uri ->
            val (name, size) = queryNameSize(cr, uri) ?: return@mapNotNull null
            Task(
                id = UUID.randomUUID().toString(),
                deviceId = device.id,
                deviceName = device.name,
                uri = uri,
                name = name,
                size = size,
            )
        }
        if (fresh.isEmpty()) return
        _tasks.value = _tasks.value + fresh
        pump()
    }

    fun cancel(id: String) {
        _tasks.value.firstOrNull { it.id == id }?.job?.cancel()
    }

    fun clearFinished() {
        _tasks.value = _tasks.value.filter { it.state == State.QUEUED || it.state == State.UPLOADING }
    }

    private fun emit() {
        _tasks.value = _tasks.value.toList()
    }

    private fun pump() {
        val active = _tasks.value.count { it.state == State.UPLOADING }
        _tasks.value.filter { it.state == State.QUEUED }.take((2 - active).coerceAtLeast(0)).forEach { run(it) }
    }

    private fun run(t: Task) {
        t.state = State.UPLOADING
        emit()
        t.job = scope.launch {
            try {
                val device = AppState.peer(t.deviceId) ?: throw IOException("device not found")
                if (!device.trusted || device.token == null) throw IOException("device not paired")
                val open: () -> InputStream = { appCtx.contentResolver.openInputStream(t.uri)!! }
                val sha = sha256Of(open)

                var offset = 0L
                Api.fileStatus(device, t.id)?.let { st ->
                    if (st.done) {
                        t.sent = t.size
                        finish(t, ok = true)
                        return@launch
                    }
                    offset = st.received.coerceIn(0, t.size)
                }
                t.sent = offset
                emit()

                var attempts = 0
                while (true) {
                    val res = if (offset == 0L) {
                        Api.uploadNew(device, t.id, t.name, t.size, sha, open) { sent ->
                            t.sent = sent
                            emitThrottled()
                        }
                    } else {
                        try {
                            Api.uploadResume(device, t.id, offset, t.size, open) { sent ->
                                t.sent = sent
                                emitThrottled()
                            }
                        } catch (e: OffsetMismatch) {
                            offset = e.received.coerceIn(0, t.size)
                            if (++attempts > 5) throw e
                            continue
                        }
                    }
                    t.sent = res.received
                    emit()
                    if (res.done || res.received >= t.size) break
                    offset = res.received.coerceIn(0, t.size)
                    if (++attempts > 5) throw IOException("upload stalled at $offset/${t.size}")
                }
                finish(t, ok = true)
            } catch (e: CancellationException) {
                t.state = State.CANCELLED
                finish(t, ok = false)
            } catch (e: Exception) {
                t.state = State.FAILED
                t.error = e.message ?: e.javaClass.simpleName
                finish(t, ok = false)
            }
        }
    }

    private var lastEmit = 0L

    /** Progress callbacks can fire hundreds of times per second; re-emit at most ~10/s. */
    private fun emitThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastEmit > 100) {
            lastEmit = now
            emit()
        }
    }

    private fun finish(t: Task, ok: Boolean) {
        if (t.state == State.UPLOADING) t.state = if (ok) State.DONE else State.FAILED
        emit()
        Store.recordTransfer(
            TransferRecord(t.id, t.name, t.size, t.deviceName, System.currentTimeMillis(), ok && t.state == State.DONE)
        )
        pump()
    }

    private fun queryNameSize(cr: ContentResolver, uri: Uri): Pair<String, Long>? {
        return try {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null
                val name = c.getString(0) ?: "file"
                val size = c.getLong(1)
                name to size
            }
        } catch (e: Exception) { null }
    }

    private fun sha256Of(open: () -> InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        open().use { ins ->
            val buf = ByteArray(65536)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
