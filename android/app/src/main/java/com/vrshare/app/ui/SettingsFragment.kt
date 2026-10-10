package com.vrshare.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vrshare.app.R
import com.vrshare.app.core.AppState
import com.vrshare.app.core.Identity
import com.vrshare.app.core.PeerDevice
import com.vrshare.app.core.Store
import com.vrshare.app.net.Api
import com.vrshare.app.net.Discovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings tab: device name (used in mDNS + pairing), trusted-device
 * management, and the About box (VrShare — developed by Asim).
 */
class SettingsFragment : Fragment() {
    private lateinit var deviceNameInput: EditText
    private lateinit var trustedAdapter: DeviceAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        deviceNameInput = view.findViewById(R.id.device_name_input)
        deviceNameInput.setText(Store.deviceName)
        view.findViewById<Button>(R.id.save_name_button).setOnClickListener { saveName() }

        trustedAdapter = DeviceAdapter(
            actionLabel = { getString(R.string.unpair) },
            onAction = { unpair(it) },
        )
        val trustedList = view.findViewById<RecyclerView>(R.id.trusted_list)
        trustedList.layoutManager = LinearLayoutManager(requireContext())
        trustedList.adapter = trustedAdapter

        viewLifecycleOwner.lifecycleScope.launch {
            AppState.peers.collect { peers ->
                trustedAdapter.submit(peers.filter { it.trusted })
            }
        }
    }

    private fun saveName() {
        val name = deviceNameInput.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            toast(getString(R.string.device_name))
            return
        }
        Store.deviceName = name.take(64)
        Identity.refreshName()
        Discovery.reregister()
        toast(getString(R.string.device_name_saved))
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
}
