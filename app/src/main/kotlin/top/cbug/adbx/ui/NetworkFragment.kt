package top.cbug.adbx.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import top.cbug.adbx.MainActivity
import top.cbug.adbx.R
import top.cbug.adbx.store.Settings as AppSettings

/**
 * Networks tab — saved WiFi + fixed port + copy-address.
 *
 * Merges the previous WiFi tab and Port tab into a single scrollable
 * page. Auto-refreshes the WiFi list once on first resume so users
 * land on populated content. Port controls save to Settings and
 * delegate the actual `setprop` call to MainActivity.
 */
class NetworkFragment : Fragment() {

    private lateinit var cgTrusted: ChipGroup
    private lateinit var btnRefreshWifi: MaterialButton
    private lateinit var rvWifi: RecyclerView
    private lateinit var tvWifiCount: TextView
    private lateinit var tvWifiEmpty: TextView

    private lateinit var swFixedPort: MaterialSwitch
    private lateinit var tilPort: TextInputLayout
    private lateinit var etPort: TextInputEditText
    private lateinit var btnApplyPort: MaterialButton
    private lateinit var tvAddress: TextView
    private lateinit var btnCopyAddress: MaterialButton

    private val wifiAdapter = WifiAdapter()
    private var firstResume = true

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_network, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        cgTrusted      = view.findViewById(R.id.cgTrusted)
        btnRefreshWifi = view.findViewById(R.id.btnRefreshWifi)
        rvWifi         = view.findViewById(R.id.rvWifi)
        tvWifiCount    = view.findViewById(R.id.tvWifiCount)
        tvWifiEmpty    = view.findViewById(R.id.tvWifiEmpty)

        swFixedPort    = view.findViewById(R.id.swFixedPort)
        tilPort        = view.findViewById(R.id.tilPort)
        etPort         = view.findViewById(R.id.etPort)
        btnApplyPort   = view.findViewById(R.id.btnApplyPort)
        tvAddress      = view.findViewById(R.id.tvAddress)
        btnCopyAddress = view.findViewById(R.id.btnCopyAddress)

        rvWifi.layoutManager = LinearLayoutManager(requireContext())
        rvWifi.adapter = wifiAdapter

        AppSettings.load(requireContext())
        renderTrustedChips()
        renderPortControls()
        renderAddress()

        btnRefreshWifi.setOnClickListener {
            (activity as? MainActivity)?.requestNeededPermissions()
            (activity as? MainActivity)?.refreshWifiList()
        }

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
            val text = when {
                ip.isNotEmpty() && port.isNotEmpty() -> "$ip:$port"
                ip.isNotEmpty() -> ip
                port.isNotEmpty() -> port
                else -> ""
            }
            if (text.isNotEmpty()) {
                act.copyToClipboard("ADB address", text)
                act.toast(getString(R.string.msg_copied, text))
            }
        }

        wifiAdapter.onToggleTrusted = { ssid, trusted ->
            if (trusted) AppSettings.addTrusted(ssid) else AppSettings.removeTrusted(ssid)
            AppSettings.save(requireContext())
            renderTrustedChips()
        }
    }

    override fun onResume() {
        super.onResume()
        AppSettings.load(requireContext())
        renderTrustedChips()
        wifiAdapter.refresh(AppSettings.trustedSet())
        renderPortControls()
        renderAddress()

        if (firstResume) {
            firstResume = false
            val act = activity as? MainActivity ?: return
            act.requestNeededPermissions()
            act.refreshWifiList()
        }
    }

    /** Called by MainActivity when wifi scan finishes. */
    fun onNetworksLoaded(count: Int) {
        if (!isAdded) return
        tvWifiCount.text = getString(R.string.wifi_count_label, count)
        tvWifiEmpty.visibility = if (count == 0) View.VISIBLE else View.GONE
    }

    private fun renderTrustedChips() {
        cgTrusted.removeAllViews()
        for (ssid in AppSettings.trustedSet().sorted()) {
            val chip = Chip(requireContext()).apply {
                text = ssid
                isCloseIconVisible = true
                isCheckable = false
                setOnCloseIconClickListener {
                    AppSettings.removeTrusted(ssid)
                    AppSettings.save(requireContext())
                    renderTrustedChips()
                    wifiAdapter.refresh(AppSettings.trustedSet())
                }
            }
            cgTrusted.addView(chip)
        }
    }

    private fun renderPortControls() {
        swFixedPort.isChecked = AppSettings.fixedPortEnabled
        etPort.setText(AppSettings.fixedPort.toString())
    }

    private fun renderAddress() {
        val act = activity as? MainActivity
        val ip = act?.currentCachedIp().orEmpty()
        val port = act?.currentCachedPort().orEmpty()
        tvAddress.text = when {
            ip.isNotEmpty() && port.isNotEmpty() -> "$ip:$port"
            ip.isNotEmpty() -> ip
            port.isNotEmpty() -> port
            else -> "—"
        }
    }
}