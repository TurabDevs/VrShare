package com.vrshare.app.core

/** A device discovered via mDNS, optionally paired (trusted). */
data class PeerDevice(
    val id: String,
    var name: String,
    var fp: String,
    var os: String,
    var host: String,
    var port: Int = 53417,
    var lastSeen: Long = System.currentTimeMillis(),
    var trusted: Boolean = false,
    var token: String? = null,
) {
    /** A device that disappeared from mDNS for > 30 s is considered offline (spec §1). */
    val online: Boolean get() = System.currentTimeMillis() - lastSeen < 30_000
    val baseUrl: String get() = "https://${bracket(host)}:$port"

    companion object {
        fun bracket(h: String): String = if (h.contains(":")) "[$h]" else h
    }
}

/** A paired device persisted locally. */
data class TrustedDevice(
    val id: String,
    val name: String,
    val fp: String,
    val os: String,
    val token: String,
)

/** This phone's own identity. */
data class IdentityInfo(val id: String, val name: String, val fp: String)

data class ClipEntry(val text: String, val at: Long, val outgoing: Boolean)

data class TransferRecord(
    val id: String,
    val name: String,
    val size: Long,
    val deviceName: String,
    val at: Long,
    val ok: Boolean,
)
