package com.vrshare.app.net

import com.vrshare.app.core.PeerDevice
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.buffer
import okio.source
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Machine-readable API error, e.g. code "bad_token", "offset_mismatch", "unknown_key". */
class ApiException(
    val code: String,
    message: String,
    val http: Int,
    val body: JSONObject?,
) : IOException("[$code] $message")

class PairRejectedException : IOException("Pairing rejected on the PC")
class PairGoneException : IOException("Pairing request expired")

/** Sent by the server as 409 with {"received": N} when our resume offset is stale. */
class OffsetMismatch(val received: Long) : IOException("offset mismatch, server has $received")

/**
 * VrShare Protocol v1 client (see INTERFACE.md).
 *
 * Base URL: https://<ip>:53417. Auth via X-VrShare-Token on every endpoint
 * except /api/info, /api/pair and /api/pair/status.
 *
 * Lenient parsing notes (desktop BUILD.md vs INTERFACE.md differ slightly):
 * - pair responses are read from either "state" or "status"
 * - file upload sends BOTH "id" and "transfer_id" multipart fields
 * - upload responses are read from either "done" or "status"
 */
object Api {
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val OCTET = "application/octet-stream".toMediaType()

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { try { cancel() } catch (_: Exception) { } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isCompleted) return
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isCompleted) {
                    response.close()
                    return
                }
                cont.resume(response)
            }
        })
    }

    private fun base(device: PeerDevice, path: String): Request.Builder {
        val b = Request.Builder().url(device.baseUrl + path)
        if (device.trusted && device.token != null) {
            b.header("X-VrShare-Token", device.token!!)
        }
        return b
    }

    private suspend fun exec(device: PeerDevice, request: Request, longTimeouts: Boolean = false): JSONObject {
        val client = TlsPinning.clientFor(device.fp, longTimeouts)
        client.newCall(request).await().use { resp ->
            val raw = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                var err = "request failed"
                var code = "http_${resp.code}"
                var obj: JSONObject? = null
                try {
                    obj = JSONObject(raw)
                    err = obj.optString("error", err)
                    code = obj.optString("code", code)
                } catch (_: Exception) { }
                throw ApiException(code, err, resp.code, obj)
            }
            return if (raw.isBlank()) JSONObject() else JSONObject(raw)
        }
    }

    private suspend fun postJson(device: PeerDevice, path: String, body: JSONObject): JSONObject =
        exec(device, base(device, path).post(body.toString().toRequestBody(JSON)).build())

    // ---- info & pairing ----

    suspend fun getInfo(device: PeerDevice): JSONObject =
        exec(device, base(device, "/api/info").get().build())

    sealed interface PairOutcome
    data object PairPending : PairOutcome
    data class PairApproved(val token: String) : PairOutcome

    /** POST /api/pair — 202/200 pending, 200 approved+token, 409 already_pending, 429 rate_limited. */
    suspend fun pair(device: PeerDevice, myId: String, myName: String, myFp: String): PairOutcome {
        val body = JSONObject()
            .put("id", myId).put("name", myName).put("fp", myFp).put("os", "android")
        val resp = exec(device, base(device, "/api/pair").post(body.toString().toRequestBody(JSON)).build())
        return outcomeOf(resp)
    }

    /** GET /api/pair/status?id= — 200 pending/approved, 403 rejected, 404 gone. */
    suspend fun pairStatus(device: PeerDevice, myId: String): PairOutcome {
        try {
            val resp = exec(device, base(device, "/api/pair/status?id=$myId").get().build())
            return outcomeOf(resp)
        } catch (e: ApiException) {
            when (e.http) {
                403 -> throw PairRejectedException()
                404 -> throw PairGoneException()
                else -> throw e
            }
        }
    }

    private fun outcomeOf(resp: JSONObject): PairOutcome {
        val state = resp.optString("state", resp.optString("status", ""))
        return if (state == "approved") PairApproved(resp.getString("token")) else PairPending
    }

    suspend fun unpair(device: PeerDevice) {
        exec(device, base(device, "/api/pair").delete().build())
    }

    // ---- text & clipboard ----

    suspend fun sendText(device: PeerDevice, text: String, clipboard: Boolean) {
        postJson(device, "/api/text", JSONObject().put("text", text).put("clipboard", clipboard))
    }

    data class ClipData(val text: String, val updatedAt: Long)

    suspend fun getClipboard(device: PeerDevice): ClipData {
        val o = exec(device, base(device, "/api/clipboard").get().build())
        return ClipData(o.optString("text", ""), o.optLong("updated_at", 0))
    }

    suspend fun setClipboard(device: PeerDevice, text: String) {
        postJson(device, "/api/clipboard", JSONObject().put("text", text))
    }

    // ---- files ----

    data class FileStatus(val received: Long, val done: Boolean)

    /** GET /api/files/status?id= — null when the server knows nothing about the transfer. */
    suspend fun fileStatus(device: PeerDevice, transferId: String): FileStatus? {
        return try {
            val o = exec(device, base(device, "/api/files/status?id=$transferId").get().build())
            FileStatus(o.optLong("received", 0), isDone(o))
        } catch (e: ApiException) {
            if (e.http == 404) null else throw e
        }
    }

    /** Fresh multipart upload (spec §3.8). */
    suspend fun uploadNew(
        device: PeerDevice,
        transferId: String,
        name: String,
        size: Long,
        sha256: String,
        open: () -> InputStream,
        onProgress: (Long) -> Unit,
    ): FileStatus {
        val filePart = StreamBody(OCTET, size, open, onProgress)
        val mp = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("id", transferId) // field name per desktop BUILD.md
            .addFormDataPart("transfer_id", transferId) // field name per INTERFACE.md
            .addFormDataPart("name", name)
            .addFormDataPart("size", size.toString())
            .addFormDataPart("sha256", sha256)
            .addFormDataPart("file", name, filePart)
            .build()
        val o = exec(device, base(device, "/api/files").post(mp).build(), longTimeouts = true)
        return FileStatus(o.optLong("received", size), isDone(o))
    }

    /** Resume upload: raw octet-stream body starting at `offset` with X-Offset header (spec §3.8). */
    suspend fun uploadResume(
        device: PeerDevice,
        transferId: String,
        offset: Long,
        size: Long,
        open: () -> InputStream,
        onProgress: (Long) -> Unit,
    ): FileStatus {
        val body = StreamBody(OCTET, size - offset, { open().apply { skipFully(offset) } }, { sent ->
            onProgress(offset + sent)
        })
        try {
            val o = exec(
                device,
                base(device, "/api/files?id=$transferId")
                    .post(body)
                    .header("X-Offset", offset.toString())
                    .build(),
                longTimeouts = true,
            )
            return FileStatus(o.optLong("received", size), isDone(o))
        } catch (e: ApiException) {
            if (e.http == 409) {
                val received = e.body?.optLong("received", 0L) ?: 0L
                throw OffsetMismatch(received)
            }
            throw e
        }
    }

    private fun isDone(o: JSONObject): Boolean =
        o.optBoolean("done", o.optString("status", "") == "complete")

    private fun InputStream.skipFully(n: Long) {
        var left = n
        while (left > 0) {
            val skipped = skip(left)
            if (skipped <= 0) {
                // InputStream.skip may return 0; fall back to reading a byte.
                if (read() == -1) break
                left--
            } else left -= skipped
        }
    }

    /** RequestBody streaming an InputStream with progress callbacks. */
    class StreamBody(
        private val ct: okhttp3.MediaType?,
        private val len: Long,
        private val open: () -> InputStream,
        private val onProgress: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType() = ct
        override fun contentLength() = len
        override fun writeTo(sink: BufferedSink) {
            var written = 0L
            open().use { ins ->
                val src = ins.source().buffer()
                val buf = Buffer()
                while (true) {
                    val n = src.read(buf, 8192)
                    if (n == -1L) break
                    sink.write(buf, n)
                    written += n
                    onProgress(written)
                }
            }
            sink.flush()
        }
    }

    // ---- remote input ----

    suspend fun inputMove(d: PeerDevice, dx: Int, dy: Int) =
        postJson(d, "/api/input/move", JSONObject().put("dx", dx).put("dy", dy))

    suspend fun inputClick(d: PeerDevice, button: String, action: String = "click") =
        postJson(d, "/api/input/click", JSONObject().put("button", button).put("action", action))

    suspend fun inputScroll(d: PeerDevice, dx: Int, dy: Int) =
        postJson(d, "/api/input/scroll", JSONObject().put("dx", dx).put("dy", dy))

    suspend fun inputKey(d: PeerDevice, key: String, action: String = "press") =
        postJson(d, "/api/input/key", JSONObject().put("key", key).put("action", action))

    suspend fun inputText(d: PeerDevice, text: String) =
        postJson(d, "/api/input/text", JSONObject().put("text", text))

    // ---- system ----

    suspend fun system(d: PeerDevice, action: String) =
        postJson(d, "/api/system", JSONObject().put("action", action))
}
