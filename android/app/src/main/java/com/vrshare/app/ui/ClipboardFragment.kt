package com.vrshare.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.vrshare.app.R
import com.vrshare.app.core.AppState
import com.vrshare.app.core.ClipEntry
import com.vrshare.app.core.Store
import com.vrshare.app.net.Api
import com.vrshare.app.sync.ClipboardSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Clipboard tab: send text (optionally also setting the PC clipboard,
 * spec §3.5), auto-sync toggle (spec §3.7), manual pull, and history.
 */
class ClipboardFragment : Fragment() {
    private lateinit var clipInput: TextInputEditText
    private lateinit var clipboardCheckbox: MaterialCheckBox
    private lateinit var autosyncSwitch: SwitchMaterial
    private lateinit var clipAdapter: ClipAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_clipboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        clipInput = view.findViewById(R.id.clip_input)
        clipboardCheckbox = view.findViewById(R.id.clipboard_checkbox)
        autosyncSwitch = view.findViewById(R.id.autosync_switch)
        clipAdapter = ClipAdapter(onClick = { copyToLocal(it) })
        val clipList = view.findViewById<RecyclerView>(R.id.clip_list)
        clipList.layoutManager = LinearLayoutManager(requireContext())
        clipList.adapter = clipAdapter

        autosyncSwitch.isChecked = Store.clipboardAutoSync && ClipboardSync.isRunning()
        autosyncSwitch.setOnCheckedChangeListener { _, on ->
            if (on && target() == null) {
                autosyncSwitch.isChecked = false
                return@setOnCheckedChangeListener
            }
            Store.clipboardAutoSync = on
            if (on) ClipboardSync.start(requireContext()) else ClipboardSync.stop()
        }

        view.findViewById<Button>(R.id.send_button).setOnClickListener {
            val text = clipInput.text?.toString().orEmpty()
            if (text.isEmpty()) return@setOnClickListener
            val d = target() ?: return@setOnClickListener
            val asClipboard = clipboardCheckbox.isChecked
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    Api.sendText(d, text, asClipboard)
                    AppState.addClip(text, outgoing = true)
                    withContext(Dispatchers.Main) {
                        if (isAdded) {
                            clipInput.text?.clear()
                            toast(getString(R.string.sent))
                        }
                    }
                } catch (e: Exception) {
                    handleApiError(e, d)
                }
            }
        }

        view.findViewById<Button>(R.id.pull_button).setOnClickListener {
            if (target() == null) return@setOnClickListener
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val text = ClipboardSync.pullOnce(requireContext())
                    withContext(Dispatchers.Main) {
                        if (isAdded) toast(if (text.isNullOrEmpty()) getString(R.string.copied) else getString(R.string.sent))
                    }
                } catch (e: Exception) {
                    handleApiError(e, AppState.selectedDevice)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            AppState.clips.collect { clipAdapter.submit(it) }
        }
    }

    private fun copyToLocal(c: ClipEntry) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("VrShare", c.text))
        toast(getString(R.string.copied))
    }
}
