package com.vrshare.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.vrshare.app.R
import com.vrshare.app.core.TransferRecord
import com.vrshare.app.net.TransferManager
import java.text.DateFormat
import java.util.Date

/** Active uploads (with progress + cancel). */
class TransferAdapter(
    private var items: List<TransferManager.Task> = emptyList(),
    private val onCancel: (TransferManager.Task) -> Unit,
) : RecyclerView.Adapter<TransferAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val transferName: TextView = view.findViewById(R.id.transfer_name)
        val transferProgress: ProgressBar = view.findViewById(R.id.transfer_progress)
        val transferPct: TextView = view.findViewById(R.id.transfer_pct)
        val transferState: TextView = view.findViewById(R.id.transfer_state)
        val cancelButton: Button = view.findViewById(R.id.cancel_button)
    }

    fun submit(list: List<TransferManager.Task>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_transfer, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val t = items[position]
        h.transferName.text = t.name
        val pct = if (t.size > 0) (t.sent * 100 / t.size).toInt().coerceIn(0, 100) else 0
        h.transferProgress.progress = pct
        h.transferPct.text = "$pct%"
        h.transferState.text = when (t.state) {
            TransferManager.State.QUEUED -> h.itemView.context.getString(R.string.state_queued)
            TransferManager.State.UPLOADING ->
                h.itemView.context.getString(R.string.state_uploading) +
                    " • ${fmtBytes(t.sent)}/${fmtBytes(t.size)}"
            TransferManager.State.DONE -> h.itemView.context.getString(R.string.state_done)
            TransferManager.State.FAILED ->
                h.itemView.context.getString(R.string.state_failed) + (t.error?.let { ": $it" } ?: "")
            TransferManager.State.CANCELLED -> h.itemView.context.getString(R.string.state_cancelled)
        }
        val active = t.state == TransferManager.State.QUEUED || t.state == TransferManager.State.UPLOADING
        h.cancelButton.visibility = if (active) View.VISIBLE else View.GONE
        h.cancelButton.setOnClickListener { onCancel(t) }
    }

    companion object {
        fun fmtBytes(n: Long): String {
            if (n < 1024) return "$n B"
            val kb = n / 1024.0
            if (kb < 1024) return "%.1f KB".format(kb)
            val mb = kb / 1024.0
            if (mb < 1024) return "%.1f MB".format(mb)
            return "%.2f GB".format(mb / 1024.0)
        }
    }
}

/** Finished transfers from persistent history. */
class TransferHistoryAdapter(
    private var items: List<TransferRecord> = emptyList(),
) : RecyclerView.Adapter<TransferHistoryAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val transferName: TextView = view.findViewById(R.id.transfer_name)
        val transferProgress: ProgressBar = view.findViewById(R.id.transfer_progress)
        val transferPct: TextView = view.findViewById(R.id.transfer_pct)
        val transferState: TextView = view.findViewById(R.id.transfer_state)
        val cancelButton: Button = view.findViewById(R.id.cancel_button)
    }

    fun submit(list: List<TransferRecord>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_transfer, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val r = items[position]
        h.transferName.text = r.name
        h.transferProgress.visibility = View.GONE
        h.transferPct.visibility = View.GONE
        h.cancelButton.visibility = View.GONE
        val ctx = h.itemView.context
        val whenStr = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.at))
        val state = if (r.ok) ctx.getString(R.string.state_done) else ctx.getString(R.string.state_failed)
        h.transferState.text = "$state • ${TransferAdapter.fmtBytes(r.size)} • ${r.deviceName} • $whenStr"
    }
}
