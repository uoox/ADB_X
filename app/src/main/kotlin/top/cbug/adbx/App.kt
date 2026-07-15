package top.cbug.adbx

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import top.cbug.adbx.store.Settings
import top.cbug.adbx.util.LocaleHelper

class App : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Load Settings first so LocaleHelper sees the saved choice.
        Settings.load(base)
        // No-op for "system"; forces locale for "en" / "zh".
        // We don't applyLocale here — Application base context doesn't
        // surface string resources to UI directly. Activities apply it
        // via LocaleHelper.wrap in their attachBaseContext override.
    }

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        // Settings was already loaded in attachBaseContext; reload is a no-op.
        Settings.load(this)
    }
}
