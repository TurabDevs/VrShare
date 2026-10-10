package com.vrshare.app.ui

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vrshare.app.R
import com.vrshare.app.core.AppState
import com.vrshare.app.core.PeerDevice
import com.vrshare.app.core.Store
import com.vrshare.app.net.Api
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Remote tab: touchpad + mouse buttons + keyboard + system/media actions,
 * all targeting the selected trusted device (spec §3.9–3.14).
 */
class RemoteFragment : Fragment() {
    private lateinit var targetText: TextView
    private lateinit var touchpad: TouchpadView
    private lateinit var keyboardInput: EditText

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_remote, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        targetText = view.findViewById(R.id.target_text)
        touchpad = view.findViewById(R.id.touchpad)
        keyboardInput = view.findViewById(R.id.keyboard_input)

        viewLifecycleOwner.lifecycleScope.launch {
            AppState.peers.collect { updateTarget() }
        }

        view.findViewById<Button>(R.id.change_button).setOnClickListener { pickTarget() }

        touchpad.listener = object : TouchpadView.Listener {
            override fun onMove(dx: Int, dy: Int) = fireQuiet { d -> Api.inputMove(d, dx, dy) }
            override fun onTap() = fireQuiet { d -> Api.inputClick(d, "left") }
            override fun onLongPress() = fireQuiet { d -> Api.inputClick(d, "right") }
            override fun onScroll(dx: Int, dy: Int) = fireQuiet { d -> Api.inputScroll(d, dx, dy) }
        }

        view.findViewById<Button>(R.id.btn_left).setOnClickListener { fire { d -> Api.inputClick(d, "left") } }
        view.findViewById<Button>(R.id.btn_right).setOnClickListener { fire { d -> Api.inputClick(d, "right") } }
        view.findViewById<Button>(R.id.btn_keyboard).setOnClickListener { showKeyboard() }

        setupHiddenKeyboard()

        // Special keys (spec §3.12 key names).
        view.findViewById<Button>(R.id.key_enter).setOnClickListener { key("Enter") }
        view.findViewById<Button>(R.id.key_backspace).setOnClickListener { key("Backspace") }
        view.findViewById<Button>(R.id.key_esc).setOnClickListener { key("Escape") }
        view.findViewById<Button>(R.id.key_tab).setOnClickListener { key("Tab") }
        view.findViewById<Button>(R.id.key_up).setOnClickListener { key("Up") }
        view.findViewById<Button>(R.id.key_down).setOnClickListener { key("Down") }
        view.findViewById<Button>(R.id.key_left).setOnClickListener { key("Left") }
        view.findViewById<Button>(R.id.key_right).setOnClickListener { key("Right") }

        // System actions (spec §3.14). Shutdown/restart need confirmation.
        view.findViewById<Button>(R.id.sys_lock).setOnClickListener { fire { d -> Api.system(d, "lock") } }
        view.findViewById<Button>(R.id.sys_sleep).setOnClickListener { fire { d -> Api.system(d, "sleep") } }
        view.findViewById<Button>(R.id.sys_screen_off).setOnClickListener { fire { d -> Api.system(d, "screen_off") } }
        view.findViewById<Button>(R.id.sys_shutdown).setOnClickListener {
            val d = target() ?: return@setOnClickListener
            confirmSystem(getString(R.string.confirm_shutdown, d.name), "shutdown")
        }
        view.findViewById<Button>(R.id.sys_restart).setOnClickListener {
            val d = target() ?: return@setOnClickListener
            confirmSystem(getString(R.string.confirm_restart, d.name), "restart")
        }

        // Media keys.
        view.findViewById<Button>(R.id.media_play).setOnClickListener { fire { d -> Api.system(d, "play_pause") } }
        view.findViewById<Button>(R.id.media_prev).setOnClickListener { fire { d -> Api.system(d, "media_prev") } }
        view.findViewById<Button>(R.id.media_next).setOnClickListener { fire { d -> Api.system(d, "media_next") } }
        view.findViewById<Button>(R.id.vol_up).setOnClickListener { fire { d -> Api.system(d, "volume_up") } }
        view.findViewById<Button>(R.id.vol_down).setOnClickListener { fire { d -> Api.system(d, "volume_down") } }
        view.findViewById<Button>(R.id.vol_mute).setOnClickListener { fire { d -> Api.system(d, "mute") } }
    }

    private fun updateTarget() {
        val d = AppState.selectedDevice
        targetText.text = if (d != null) getString(R.string.remote_target, d.name)
        else getString(R.string.remote_no_target)
    }

    private fun pickTarget() {
        val trusted = AppState.peers.value.filter { it.trusted }
        if (trusted.isEmpty()) {
            toast(getString(R.string.remote_no_target))
            return
        }
        val names = trusted.map { it.name + if (it.online) "" else " (${getString(R.string.offline)})" }
            .toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setItems(names) { _, which ->
                Store.selectedDeviceId = trusted[which].id
                updateTarget()
            }
            .show()
    }

    /** Fire-and-forget with error toast (used for discrete actions). */
    private fun fire(block: suspend (PeerDevice) -> Unit) {
        val d = target() ?: return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                block(d)
            } catch (e: Exception) {
                handleApiError(e, d)
            }
        }
    }

    /** Fire-and-forget, silent except for unpairing (used for touchpad streams). */
    private fun fireQuiet(block: suspend (PeerDevice) -> Unit) {
        val d = AppState.selectedDevice ?: return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                block(d)
            } catch (e: Exception) {
                handleApiError(e, d, silent = true)
            }
        }
    }

    private fun key(name: String) = fireQuiet { d -> Api.inputKey(d, name, "press") }

    private fun confirmSystem(message: String, action: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(message)
            .setPositiveButton(R.string.yes) { _, _ -> fire { d -> Api.system(d, action) } }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    // ---------- soft keyboard ----------

    private fun showKeyboard() {
        if (target() == null) return
        keyboardInput.requestFocus()
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(keyboardInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun setupHiddenKeyboard() {
        val kb = keyboardInput
        var guard = false
        kb.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (guard) return
                val t = s?.toString() ?: return
                if (t.isEmpty()) return
                guard = true
                s?.clear()
                guard = false
                // Literal text injection, layout-independent (spec §3.13).
                fireQuiet { d -> Api.inputText(d, t) }
            }
        })
        kb.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DEL -> {
                        fireQuiet { d -> Api.inputKey(d, "Backspace", "press") }
                        true
                    }
                    KeyEvent.KEYCODE_ENTER -> {
                        fireQuiet { d -> Api.inputKey(d, "Enter", "press") }
                        true
                    }
                    else -> false
                }
            } else false
        }
    }
}
