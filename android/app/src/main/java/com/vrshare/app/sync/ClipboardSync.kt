package com.vrshare.app.sync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.vrshare.app.core.AppState
import com.vrshare.app.net.Api
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Clipboard auto-sync (spec §3.7):
 * - watches this phone's clipboard and POSTs changes to the selected PC
 *   (debounced 500 ms, with loop guards)
 * - polls GET /api/clipboard every 3 s and copies newer remote text locally
 *
 * No foreground service in v1: sync only runs while the app is alive.
 */
object ClipboardSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var pushJob: Job? = null

    private var lastPushed = ""
    private var lastPulled = ""
    private var lastPulledAt = 0L
    private var pendingPush: String? = null

    fun isRunning(): Boolean = job != null

    @Synchronized
    fun start(ctx: Context) {
        if (job != null) return
        val appCtx = ctx.applicationContext
        val cm = appCtx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            val t = cm.primaryClip?.getItemAt(0)?.coerceToText(appCtx)?.toString()
                ?: return@OnPrimaryClipChangedListener
            if (t.isEmpty() || t == lastPushed || t == lastPulled) return@OnPrimaryClipChangedListener
            pendingPush = t
            pushJob?.cancel()
            pushJob = scope.launch {
                delay(500) // debounce: wait for the user to finish copying
                pendingPush?.let { push(it) }
                pendingPush = null
            }
        }
        cm.addPrimaryClipChangedListener(listener)

        job = scope.launch {
            try {
                while (isActive) {
                    delay(3000)
                    poll(appCtx, cm)
                }
            } finally {
                cm.removePrimaryClipChangedListener(listener)
            }
        }
    }

    @Synchronized
    fun stop() {
        val j = job
        job = null
        pushJob?.cancel()
        pushJob = null
        scope.launch { j?.cancelAndJoin() }
    }

    private suspend fun push(text: String) {
        val device = AppState.selectedDevice ?: return
        if (!device.online) return
        try {
            Api.setClipboard(device, text)
            lastPushed = text
            AppState.addClip(text, outgoing = true)
        } catch (e: Exception) { /* offline / unpaired — try again on next change */ }
    }

    private suspend fun poll(appCtx: Context, cm: ClipboardManager) {
        val device = AppState.selectedDevice ?: return
        if (!device.online) return
        try {
            val remote = Api.getClipboard(device)
            if (remote.updatedAt > lastPulledAt && remote.text.isNotEmpty()
                && remote.text != lastPushed && remote.text != lastPulled
            ) {
                val local = cm.primaryClip?.getItemAt(0)?.coerceToText(appCtx)?.toString() ?: ""
                if (remote.text != local) {
                    lastPulled = remote.text
                    lastPulledAt = remote.updatedAt
                    cm.setPrimaryClip(ClipData.newPlainText("VrShare", remote.text))
                    AppState.addClip(remote.text, outgoing = false)
                } else {
                    lastPulledAt = remote.updatedAt
                }
            }
        } catch (e: Exception) { /* offline / unpaired — keep polling */ }
    }

    /** Used by the manual "Pull from PC" button. */
    suspend fun pullOnce(ctx: Context): String? {
        val device = AppState.selectedDevice ?: return null
        val remote = Api.getClipboard(device)
        if (remote.text.isEmpty()) return ""
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        lastPulled = remote.text
        lastPulledAt = remote.updatedAt
        cm.setPrimaryClip(ClipData.newPlainText("VrShare", remote.text))
        AppState.addClip(remote.text, outgoing = false)
        return remote.text
    }
}
