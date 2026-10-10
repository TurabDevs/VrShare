package com.vrshare.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.vrshare.app.core.AppState
import com.vrshare.app.core.Identity
import com.vrshare.app.core.Store
import com.vrshare.app.net.Discovery
import com.vrshare.app.net.TransferManager
import com.vrshare.app.sync.ClipboardSync

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // VrShare is dark-theme only in v1.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        instance = this
        Store.init(this)
        Identity.ensure(this)
        AppState.init(this)
        TransferManager.init(this)
        Discovery.start(this)
        if (Store.clipboardAutoSync) ClipboardSync.start(this)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
