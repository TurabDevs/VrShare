package com.vrshare.app.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Persistent storage. Pairing tokens and trusted devices live in
 * EncryptedSharedPreferences (falls back to plain prefs if the keystore
 * is unavailable on the device).
 */
object Store {
    private lateinit var prefs: SharedPreferences
    private lateinit var appCtx: Context

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        prefs = try {
            val mk = MasterKey.Builder(appCtx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appCtx, "vrshare_secure", mk,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            appCtx.getSharedPreferences("vrshare", Context.MODE_PRIVATE)
        }
    }

    var ownId: String
        get() = prefs.getString("own_id", null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString("own_id", it).apply() }
        set(v) { prefs.edit().putString("own_id", v).apply() }

    var deviceName: String
        get() = prefs.getString("device_name", null)
            ?: (Build.MODEL ?: "Android").also { prefs.edit().putString("device_name", it).apply() }
        set(v) { prefs.edit().putString("device_name", v).apply() }

    var selectedDeviceId: String?
        get() = prefs.getString("selected_device", null)
        set(v) { prefs.edit().putString("selected_device", v).apply() }

    var clipboardAutoSync: Boolean
        get() = prefs.getBoolean("clipboard_autosync", false)
        set(v) { prefs.edit().putBoolean("clipboard_autosync", v).apply() }

    // ---- trusted devices ----

    fun saveTrusted(d: TrustedDevice) {
        val o = JSONObject()
            .put("name", d.name)
            .put("fp", d.fp)
            .put("os", d.os)
            .put("token", d.token)
        prefs.edit().putString("trusted_${d.id}", o.toString()).apply()
    }

    fun removeTrusted(id: String) {
        prefs.edit().remove("trusted_$id").apply()
    }

    fun getTrusted(id: String): TrustedDevice? {
        val s = prefs.getString("trusted_$id", null) ?: return null
        return try {
            val o = JSONObject(s)
            TrustedDevice(id, o.getString("name"), o.getString("fp"), o.getString("os"), o.getString("token"))
        } catch (e: Exception) {
            null
        }
    }

    fun allTrusted(): List<TrustedDevice> =
        prefs.all.keys.filter { it.startsWith("trusted_") }
            .mapNotNull { getTrusted(it.removePrefix("trusted_")) }

    // ---- transfer history (last 50, app-private file) ----

    private fun historyFile() = java.io.File(appCtx.filesDir, "transfer_history.json")

    fun recordTransfer(r: TransferRecord) {
        try {
            val arr = try {
                JSONArray(historyFile().takeIf { it.exists() }?.readText() ?: "[]")
            } catch (e: Exception) { JSONArray() }
            arr.put(
                JSONObject()
                    .put("id", r.id).put("name", r.name).put("size", r.size)
                    .put("deviceName", r.deviceName).put("at", r.at).put("ok", r.ok)
            )
            while (arr.length() > 50) arr.remove(0)
            historyFile().writeText(arr.toString())
        } catch (e: Exception) { /* best effort */ }
    }

    fun transferHistory(): List<TransferRecord> {
        return try {
            val f = historyFile()
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TransferRecord(
                    o.getString("id"), o.getString("name"), o.getLong("size"),
                    o.optString("deviceName", ""), o.getLong("at"), o.getBoolean("ok"),
                )
            }.reversed()
        } catch (e: Exception) { emptyList() }
    }

    // ---- clipboard history (last 50) ----

    fun saveClips(clips: List<ClipEntry>) {
        try {
            val arr = JSONArray()
            clips.take(50).forEach { c ->
                arr.put(JSONObject().put("text", c.text).put("at", c.at).put("outgoing", c.outgoing))
            }
            prefs.edit().putString("clip_history", arr.toString()).apply()
        } catch (e: Exception) { /* best effort */ }
    }

    fun loadClips(): List<ClipEntry> {
        return try {
            val s = prefs.getString("clip_history", null) ?: return emptyList()
            val arr = JSONArray(s)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ClipEntry(o.getString("text"), o.getLong("at"), o.getBoolean("outgoing"))
            }
        } catch (e: Exception) { emptyList() }
    }
}
