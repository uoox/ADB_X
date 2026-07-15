package top.cbug.adbx.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import top.cbug.adbx.MainActivity
import top.cbug.adbx.R
import top.cbug.adbx.store.Settings as AppSettings

/**
 * Port tab — fixed-port switch + port input + copy-address button.
 */
class PortFragment : Fragment() {

    private lateinit var swFixedPort: MaterialSwitch
    private lateinit var tilPort: TextInputLayout
    private lateinit var etPort: TextInputEditText
    private lateinit var btnApplyPort: MaterialButton
    private lateinit var btnCopyAddress: MaterialButton

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_port, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        swFixedPort   = view.findViewById(R.id.swFixedPort)
        tilPort       = view.findViewById(R.id.tilPort)
        etPort        = view.findViewById(R.id.etPort)
        btnApplyPort  = view.findViewById(R.id.btnApplyPort)
        btnCopyAddress = view.findViewById(R.id.btnCopyAddress)

        AppSettings.load(requireContext())
        swFixedPort.isChecked = AppSettings.fixedPortEnabled
        etPort.setText(AppSettings.fixedPort.toString())

        swFixedPort.setOnCheckedChangeListener { _, checked ->
            AppSettings.fixedPortEnabled = checked
            AppSettings.save(requireContext())
        }

        btnApplyPort.setOnClickListener {
            val portText = etPort.text?.toString()?.trim().orEmpty()
            val port = portText.toIntOrNull()
            if (port == null || port < 1024 || port > 65535) {
                tilPort.error = getString(R.string.err_port_range)
                return@setOnClickListener
            }
            tilPort.error = null
            AppSettings.fixedPort = port
            AppSettings.save(requireContext())
            (activity as? MainActivity)?.applyFixedPort(port)
        }

        btnCopyAddress.setOnClickListener {
            val act = activity as? MainActivity ?: return@setOnClickListener
            val ip = act.currentCachedIp()
            val port = act.currentCachedPort()
            val text = if (ip.isNotEmpty() && port.isNotEmpty()) "$ip:$port" else port
            if (text.isNotEmpty()) {
                act.copyToClipboard("ADB address", text)
                act.toast(getString(R.string.msg_copied, text))
            }
        }
    }
}
