package com.vrshare.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vrshare.app.R
import com.vrshare.app.core.AppState
import com.vrshare.app.core.Store
import com.vrshare.app.net.TransferManager
import kotlinx.coroutines.launch

/**
 * Files tab: pick files via the system picker and upload them to the selected
 * device (spec §3.8, with resume). Shows active transfers with progress/cancel
 * plus a persistent history.
 */
class FilesFragment : Fragment() {
    private lateinit var targetText: TextView
    private lateinit var noTransfersText: TextView
    private lateinit var activeAdapter: TransferAdapter
    private lateinit var historyAdapter: TransferHistoryAdapter

    private val picker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val d = target() ?: return@registerForActivityResult
        TransferManager.enqueue(d, uris)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_files, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        targetText = view.findViewById(R.id.target_text)
        noTransfersText = view.findViewById(R.id.no_transfers_text)
        activeAdapter = TransferAdapter(onCancel = { TransferManager.cancel(it.id) })
        historyAdapter = TransferHistoryAdapter()
        val activeList = view.findViewById<RecyclerView>(R.id.active_list)
        activeList.layoutManager = LinearLayoutManager(requireContext())
        activeList.adapter = activeAdapter
        val historyList = view.findViewById<RecyclerView>(R.id.history_list)
        historyList.layoutManager = LinearLayoutManager(requireContext())
        historyList.adapter = historyAdapter

        view.findViewById<Button>(R.id.send_button).setOnClickListener {
            if (target() == null) return@setOnClickListener
            picker.launch("*/*")
        }

        viewLifecycleOwner.lifecycleScope.launch {
            AppState.peers.collect { updateTarget() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            TransferManager.tasks.collect { tasks ->
                val active = tasks.filter {
                    it.state == TransferManager.State.QUEUED || it.state == TransferManager.State.UPLOADING
                }
                activeAdapter.submit(active)
                noTransfersText.visibility = if (active.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateTarget()
        historyAdapter.submit(Store.transferHistory())
    }

    private fun updateTarget() {
        val d = AppState.selectedDevice
        targetText.text = if (d != null) getString(R.string.remote_target, d.name)
        else getString(R.string.remote_no_target)
    }
}
