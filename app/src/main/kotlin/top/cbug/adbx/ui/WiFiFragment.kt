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
import top.cbug.adbx.MainActivity
import top.cbug.adbx.R
import top.cbug.adbx.store.Settings as AppSettings

/**
 * WiFi tab — saved networks list + trusted chip group.
 *
 * On first onResume (savedInstanceState == null), kicks off an automatic
 * WiFi list refresh so the user lands on populated content.
 */
class WiFiFragment : Fragment() {

    private lateinit var cgTrusted: ChipGroup
    private lateinit var btnRefreshWifi: MaterialButton
    private lateinit var rvWifi: RecyclerView
    private lateinit var tvWifiCount: TextView
    private lateinit var tvWifiEmpty: TextView

    private val wifiAdapter = WifiAdapter()

    private var firstResume = true

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_wifi, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        cgTrusted     = view.findViewById(R.id.cgTrusted)
        btnRefreshWifi = view.findViewById(R.id.btnRefreshWifi)
        rvWifi        = view.findViewById(R.id.rvWifi)
        tvWifiCount   = view.findViewById(R.id.tvWifiCount)
        tvWifiEmpty   = view.findViewById(R.id.tvWifiEmpty)

        rvWifi.layoutManager = LinearLayoutManager(requireContext())
        rvWifi.adapter = wifiAdapter

        AppSettings.load(requireContext())
        renderTrustedChips()

        btnRefreshWifi.setOnClickListener {
            (activity as? MainActivity)?.requestNeededPermissions()
            (activity as? MainActivity)?.refreshWifiList()
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

        // First time the tab is shown on this Activity instance, kick off
        // a refresh automatically so the user sees content immediately.
        if (firstResume) {
            firstResume = false
            val act = activity as? MainActivity ?: return
            act.requestNeededPermissions()
            act.refreshWifiList()
        }
    }

    fun updateCount(count: Int) {
        tvWifiCount.text = getString(R.string.wifi_count_label, count)
        tvWifiEmpty.visibility = if (count == 0) View.VISIBLE else View.GONE
    }

    /** Called by MainActivity when wifi scan finishes. */
    fun onNetworksLoaded(count: Int) {
        if (!isAdded) return
        updateCount(count)
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
}
