package top.cbug.adbx

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import top.cbug.adbx.util.AdbHelper

/**
 * Full-screen pairing-code manager. Reachable from:
 *   - Settings tab → "Pairing" list item
 *   - Status tab → quick shortcut button (if present)
 *
 * The pairing code is what the host (PC) types into `adb pair`; it is
 * distinct from the wireless-ADB port. Persisted to /data/local/tmp
 * via AdbHelper so the Xposed hook can read it on the next pairing
 * dialog.
 */
class PairingActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvCurrentCode: android.widget.TextView
    private lateinit var btnCopyCode: MaterialButton
    private lateinit var tilNewCode: TextInputLayout
    private lateinit var etNewCode: TextInputEditText
    private lateinit var btnSaveCode: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pairing)

        toolbar       = findViewById(R.id.pairingToolbar)
        tvCurrentCode = findViewById(R.id.tvCurrentCode)
        btnCopyCode   = findViewById(R.id.btnCopyCode)
        tilNewCode    = findViewById(R.id.tilNewCode)
        etNewCode     = findViewById(R.id.etNewCode)
        btnSaveCode   = findViewById(R.id.btnSaveCode)

        toolbar.setNavigationOnClickListener { finish() }
        // Toolbar gets its top status-bar inset automatically from the
        // LinearLayout's fitsSystemWindows="true" — no manual listener
        // needed.

        refreshCode()
        btnCopyCode.setOnClickListener {
            val code = tvCurrentCode.text.toString()
            if (code.isNotBlank()) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("ADB pairing code", code))
                Toast.makeText(this, getString(R.string.msg_copied, code), Toast.LENGTH_SHORT).show()
            }
        }
        btnSaveCode.setOnClickListener {
            val raw = etNewCode.text?.toString()?.trim().orEmpty()
            if (raw.length !in 6..8 || !raw.all { it.isDigit() }) {
                tilNewCode.error = getString(R.string.err_pairing_length)
                return@setOnClickListener
            }
            tilNewCode.error = null
            AdbHelper.setPairingCode(raw)
            Toast.makeText(this, getString(R.string.msg_pairing_saved, raw), Toast.LENGTH_SHORT).show()
            etNewCode.setText("")
            refreshCode()
        }
    }

    private fun refreshCode() {
        val code = AdbHelper.readPairingCode()
        tvCurrentCode.text = code.ifBlank { "—" }
    }
}