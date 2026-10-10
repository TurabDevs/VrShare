package com.vrshare.app.ui

import android.widget.Toast
import androidx.fragment.app.Fragment
import com.vrshare.app.R
import com.vrshare.app.core.AppState
import com.vrshare.app.core.PeerDevice
import com.vrshare.app.core.Store
import com.vrshare.app.net.ApiException

fun Fragment.toast(msg: String) {
    val ctx = context ?: return
    activity?.runOnUiThread {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }
}

/** The selected trusted device, or null after telling the user to pair first. */
fun Fragment.target(): PeerDevice? {
    val d = AppState.selectedDevice
    if (d == null) toast(getString(R.string.remote_no_target))
    return d
}

/**
 * 403/bad_token means the PC unpaired us: drop local trust and prompt re-pairing.
 * Returns true if the error was handled.
 */
fun Fragment.handleApiError(e: Exception, device: PeerDevice? = null, silent: Boolean = false): Boolean {
    if (e is ApiException && e.http == 403 && e.code == "bad_token") {
        device?.let {
            AppState.removeTrust(it.id)
            Store.removeTrusted(it.id)
        }
        if (!silent) toast(getString(R.string.device_unpaired_notice))
        return true
    }
    if (!silent) toast(getString(R.string.action_failed, e.message ?: e.javaClass.simpleName))
    return false
}
