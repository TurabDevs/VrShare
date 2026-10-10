package com.vrshare.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.vrshare.app.R
import com.vrshare.app.core.PeerDevice

/**
 * One row per device. [actionLabel] returns the button text (or null to hide
 * the button); [onAction] fires on button tap; [onRowClick] on row tap.
 */
class DeviceAdapter(
    private var items: List<PeerDevice> = emptyList(),
    private var selectedId: String? = null,
    private val actionLabel: (PeerDevice) -> String?,
    private val onAction: (PeerDevice) -> Unit,
    private val onRowClick: ((PeerDevice) -> Unit)? = null,
) : RecyclerView.Adapter<DeviceAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val statusDot: View = view.findViewById(R.id.status_dot)
        val deviceName: TextView = view.findViewById(R.id.device_name)
        val deviceSub: TextView = view.findViewById(R.id.device_sub)
        val actionButton: Button = view.findViewById(R.id.action_button)
    }

    fun submit(list: List<PeerDevice>, selected: String? = selectedId) {
        items = list
        selectedId = selected
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val d = items[position]
        val isSel = d.id == selectedId
        h.deviceName.text = d.name + if (isSel) " ✓" else ""
        h.deviceSub.text = "${d.os} • ${d.host}" + if (isSel) " • active" else ""
        h.statusDot.setBackgroundResource(
            if (d.online) R.drawable.dot_online else R.drawable.dot_offline
        )
        val label = actionLabel(d)
        if (label == null) {
            h.actionButton.visibility = View.GONE
        } else {
            h.actionButton.visibility = View.VISIBLE
            h.actionButton.text = label
            h.actionButton.setOnClickListener { onAction(d) }
        }
        h.itemView.setOnClickListener { onRowClick?.invoke(d) }
    }
}
