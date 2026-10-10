package com.vrshare.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.vrshare.app.R
import com.vrshare.app.core.ClipEntry
import java.text.DateFormat
import java.util.Date

class ClipAdapter(
    private var items: List<ClipEntry> = emptyList(),
    private val onClick: (ClipEntry) -> Unit,
) : RecyclerView.Adapter<ClipAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val clipText: TextView = view.findViewById(R.id.clip_text)
        val clipTime: TextView = view.findViewById(R.id.clip_time)
    }

    fun submit(list: List<ClipEntry>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_clip, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val c = items[position]
        val arrow = if (c.outgoing) "↑ " else "↓ "
        h.clipText.text = arrow + c.text
        h.clipTime.text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(c.at))
        h.itemView.setOnClickListener { onClick(c) }
    }
}
