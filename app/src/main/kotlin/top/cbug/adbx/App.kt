package top.cbug.adbx

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import top.cbug.adbx.store.Settings

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        Settings.load(this)
    }
}
