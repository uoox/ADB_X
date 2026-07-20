package top.cbug.adbx

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import top.cbug.adbx.store.Settings
import top.cbug.adbx.util.LocaleHelper
import top.cbug.adbx.util.XposedStatus

class App : Application() {
    companion object {
        /** App-level context available from any thread once onCreate has run. */
        lateinit var appContext: Context
            private set
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Hand the application context to XposedStatus so its LSPosed-time
        // markActive() writes can land in our SharedPreferences without
        // needing the activity context (which doesn't exist yet in
        // system_server handleLoadPackage).
        XposedStatus.init(this)
        // Load Settings first so LocaleHelper sees the saved choice.
        Settings.load(base)
    }

    override fun onCreate() {
        super.onCreate()
        appContext = this
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        // Settings was already loaded in attachBaseContext; reload is a no-op.
        Settings.load(this)
    }
}
