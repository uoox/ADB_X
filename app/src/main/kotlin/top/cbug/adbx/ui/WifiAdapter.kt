package top.cbug.adbx.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import top.cbug.adbx.R
import top.cbug.adbx.store.Settings
import top.cbug.adbx.util.WifiHelper

data class WifiItem(val ssid: String, val bssid: String?, val security: String)

class WifiAdapter(
    private val items: MutableList<WifiItem> = mutableListOf()
) : RecyclerView.Adapter<WifiAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvSsid: TextView = view.findViewById(R.id.tvWifiSsid)
        val tvSecurity: TextView = view.findViewById(R.id.tvWifiSecurity)
        val swTrusted: MaterialSwitch = view.findViewById(R.id.swTrusted)
    }

    fun update(newItems: List<WifiItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_wifi, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.tvSsid.text = item.ssid.ifBlank { "(unknown)" }
        holder.tvSecurity.text = item.security
        holder.swTrusted.setOnCheckedChangeListener(null)
        holder.swTrusted.isChecked = Settings.isTrusted(item.ssid)
        holder.swTrusted.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) Settings.addTrusted(item.ssid)
            else Settings.removeTrusted(item.ssid)
            Settings.save(holder.itemView.context)
        }
    }

    override fun getItemCount(): Int = items.size
}