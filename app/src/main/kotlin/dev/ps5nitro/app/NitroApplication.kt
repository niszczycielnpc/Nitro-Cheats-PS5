package dev.ps5nitro.app

import android.app.Application

class NitroApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Session.init(this)
    }
}
