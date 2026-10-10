package com.vrshare.app.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-memory UI state: discovered peers, clipboard history.
 * All mutations re-emit so fragments collecting the flows re-render.
 */
object AppState {
    private val _peers = MutableStateFlow<List<PeerDevice>>(emptyList())
    val peers: StateFlow<List<PeerDevice>> = _peers

    private val _clips = MutableStateFlow<List<ClipEntry>>(emptyList())
    val clips: StateFlow<List<ClipEntry>> = _clips

    fun init(ctx: Context) {
        _clips.value = Store.loadClips()
    }

    val selectedDevice: PeerDevice?
        get() = _peers.value.firstOrNull { it.id == Store.selectedDeviceId && it.trusted }

    fun peer(id: String): PeerDevice? = _peers.value.firstOrNull { it.id == id }

    @Synchronized
    fun upsertPeer(p: PeerDevice) {
        val cur = _peers.value
        val e = cur.firstOrNull { it.id == p.id }
        if (e != null) {
            e.name = p.name; e.fp = p.fp; e.os = p.os
            e.host = p.host; e.port = p.port
            e.lastSeen = p.lastSeen
            _peers.value = cur.toList()
        } else {
            Store.getTrusted(p.id)?.let { t ->
                p.trusted = true
                p.token = t.token
            }
            _peers.value = cur + p
        }
    }

    @Synchronized
    fun markOffline(id: String) {
        peer(id)?.let {
            it.lastSeen = 0L
            _peers.value = _peers.value.toList()
        }
    }

    /** Re-emit so `online` flags recompute (30 s mDNS timeout, spec §1). */
    @Synchronized
    fun sweepOffline() {
        _peers.value = _peers.value.toList()
    }

    @Synchronized
    fun markTrusted(id: String, token: String) {
        peer(id)?.let {
            it.trusted = true
            it.token = token
            _peers.value = _peers.value.toList()
        }
    }

    @Synchronized
    fun removeTrust(id: String) {
        peer(id)?.let {
            it.trusted = false
            it.token = null
            if (Store.selectedDeviceId == id) Store.selectedDeviceId = null
            _peers.value = _peers.value.toList()
        }
    }

    @Synchronized
    fun addClip(text: String, outgoing: Boolean) {
        if (text.isEmpty()) return
        if (_clips.value.firstOrNull()?.text == text) return
        val next = (listOf(ClipEntry(text, System.currentTimeMillis(), outgoing)) + _clips.value).take(50)
        _clips.value = next
        Store.saveClips(next)
    }
}
