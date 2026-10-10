package com.vrshare.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vrshare.app.R
import com.vrshare.app.core.AppState
import com.vrshare.app.core.Identity
import com.vrshare.app.core.PeerDevice
import com.vrshare.app.core.Store
import com.vrshare.app.core.TrustedDevice
import com.vrshare.app.net.Api
import com.vrshare.app.net.ApiException
import com.vrshare.app.net.PairGoneException
import com.vrshare.app.net.PairRejectedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Devices tab: nearby (unpaired) devices with Connect → PIN pairing flow,
 * and the trusted-device list with Unpair. Tapping a trusted row selects it
 * as the target for Remote / Files / Clipboard.
 */
class DevicesFragment : Fragment() {
    private lateinit var nearbyList: RecyclerView
    private lateinit var trustedList: RecyclerView
    private lateinit var emptyText: TextView

    private lateinit var nearbyAdapter: DeviceAdapter
    private lateinit var trustedAdapter: DeviceAdapter
    private var pairJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_devices, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        nearbyList = view.findViewById(R.id.nearby_list)
        trustedList = view.findViewById(R.id.trusted_list)
        emptyText = view.findViewById(R.id.empty_text)

        nearbyAdapter = DeviceAdapter(
            actionLabel = { d -> if (d.online) getString(R.string.connect) else null },
            onAction = { startPairing(it) },
        )
        trustedAdapter = DeviceAdapter(
            actionLabel = { getString(R.string.unpair) },
            onAction = { unpair(it) },
            onRowClick = {
                Store.selectedDeviceId = it.id
                toast(getString(R.string.remote_target, it.name))
            },
        )
        nearbyList.layoutManager = LinearLayoutManager(requireContext())
        nearbyList.adapter = nearbyAdapter
        trustedList.layoutManager = LinearLayoutManager(requireContext())
        trustedList.adapter = trustedAdapter

        viewLifecycleOwner.lifecycleScope.launch {
            AppState.peers.collect { render(it) }
        }
    }

    private fun render(peers: List<PeerDevice>) {
        val nearby = peers.filter { !it.trusted }.sortedByDescending { it.online }
        val trusted = peers.filter { it.trusted }
        nearbyAdapter.submit(nearby)
        trustedAdapter.submit(trusted, Store.selectedDeviceId)
        emptyText.visibility = if (nearby.isEmpty()) View.VISIBLE else View.GONE
    }

    // ---------- pairing ----------

    private fun startPairing(d: PeerDevice) {
        if (pairJob?.isActive == true) return
        val dialogView = layoutInflater.inflate(R.layout.dialog_pairing, null)
        dialogView.findViewById<TextView>(R.id.pair_title).text = getString(R.string.pairing_title, d.name)
        dialogView.findViewById<TextView>(R.id.pair_fp).text = getString(R.string.pairing_fp, d.fp.take(16) + "…")
        val statusText = dialogView.findViewById<TextView>(R.id.pair_status)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()
        dialogView.findViewById<Button>(R.id.pair_cancel).setOnClickListener {
            pairJob?.cancel()
            dialog.dismiss()
        }
        dialog.show()

        pairJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val me = Identity.info
                // POST /api/pair — 409 already_pending just means "go poll the status".
                val first = try {
                    Api.pair(d, me.id, me.name, me.fp)
                } catch (e: ApiException) {
                    if (e.code == "already_pending") Api.PairPending else throw e
                }
                if (first is Api.PairApproved) {
                    finishPairing(d, first.token, dialog)
                    return@launch
                }
                // Poll GET /api/pair/status every 2 s for up to 120 s (spec §2.3).
                val deadline = System.currentTimeMillis() + 120_000
                while (System.currentTimeMillis() < deadline) {
                    delay(2000)
                    try {
                        when (val s = Api.pairStatus(d, me.id)) {
                            is Api.PairApproved -> {
                                finishPairing(d, s.token, dialog)
                                return@launch
                            }
                            Api.PairPending -> { /* keep waiting */ }
                        }
                    } catch (e: PairRejectedException) {
                        setPairStatus(statusText, getString(R.string.pairing_rejected))
                        delay(1500)
                        dismissOnMain(dialog)
                        return@launch
                    } catch (e: PairGoneException) {
                        // request expired server-side; keep polling a little longer
                    }
                }
                setPairStatus(statusText, getString(R.string.pairing_timeout))
                delay(1500)
                dismissOnMain(dialog)
            } catch (e: CancellationException) {
                dismissOnMain(dialog)
            } catch (e: Exception) {
                handleApiError(e, d)
                dismissOnMain(dialog)
            }
        }
    }

    private suspend fun finishPairing(d: PeerDevice, token: String, dialog: AlertDialog) {
        Store.saveTrusted(TrustedDevice(d.id, d.name, d.fp, d.os, token))
        AppState.markTrusted(d.id, token)
        Store.selectedDeviceId = d.id
        withContext(Dispatchers.Main) {
            if (isAdded) {
                toast(getString(R.string.sent))
                dialog.dismiss()
            }
        }
    }

    private suspend fun setPairStatus(statusText: TextView, text: String) {
        withContext(Dispatchers.Main) {
            if (isAdded) statusText.text = text
        }
    }

    private suspend fun dismissOnMain(dialog: AlertDialog) {
        withContext(Dispatchers.Main) {
            if (dialog.isShowing) dialog.dismiss()
        }
    }

    private fun unpair(d: PeerDevice) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                Api.unpair(d)
            } catch (e: Exception) {
                // Server may already be gone; still drop local trust.
            }
            Store.removeTrusted(d.id)
            AppState.removeTrust(d.id)
            withContext(Dispatchers.Main) {
                if (isAdded) toast(getString(R.string.unpaired_msg))
            }
        }
    }

    override fun onDestroy() {
        pairJob?.cancel()
        super.onDestroy()
    }
}
