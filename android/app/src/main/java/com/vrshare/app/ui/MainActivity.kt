package com.vrshare.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.vrshare.app.R

class MainActivity : AppCompatActivity() {
    private var current = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        bottomNav.setOnItemSelectedListener { item ->
            show(item.itemId)
            true
        }
        if (savedInstanceState == null) show(R.id.nav_devices)
        else current = bottomNav.selectedItemId
    }

    private fun show(id: Int) {
        if (id == current && supportFragmentManager.findFragmentById(R.id.fragment_container) != null) return
        current = id
        val f: Fragment = when (id) {
            R.id.nav_remote -> RemoteFragment()
            R.id.nav_files -> FilesFragment()
            R.id.nav_clipboard -> ClipboardFragment()
            R.id.nav_settings -> SettingsFragment()
            else -> DevicesFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, f)
            .commit()
    }
}
