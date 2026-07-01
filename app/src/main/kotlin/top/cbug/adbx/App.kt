package top.cbug.adbx

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        top.cbug.adbx.store.Settings.load(this)
    }
}
