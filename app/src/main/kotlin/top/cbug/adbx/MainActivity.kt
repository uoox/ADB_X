package top.cbug.adbx

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import top.cbug.adbx.store.Settings
import top.cbug.adbx.util.AdbHelper
import top.cbug.adbx.util.ShellUtils
import top.cbug.adbx.util.WifiHelper
import top.cbug.adbx.ui.WifiAdapter
import top.cbug.adbx.ui.WifiItem

class MainActivity : AppCompatActivity() {

    private lateinit var swFixedPort: Switch
    private lateinit var etPort: EditText
    private lateinit var btnApplyPort: Button
    private lateinit var btnRefreshWifi: Button
    private lateinit var rvWifi: RecyclerView
    private lateinit var swAutoEnable: Switch
    private lateinit var swAutoDisable: Switch
    private lateinit var btnPairingCode: Button
    private lateinit var tvStatus: TextView

    private val wifiAdapter = WifiAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        loadSettings()
        setupListeners()
        refreshWifiList()
        updateStatus()
    }

    private fun bindViews() {
        swFixedPort = findViewById(R.id.swFixedPort)
        etPort = findViewById(R.id.etPort)
        btnApplyPort = findViewById(R.id.btnApplyPort)
        btnRefreshWifi = findViewById(R.id.btnRefreshWifi)
        rvWifi = findViewById(R.id.rvWifi)
        swAutoEnable = findViewById(R.id.swAutoEnable)
        swAutoDisable = findViewById(R.id.swAutoDisable)
        btnPairingCode = findViewById(R.id.btnPairingCode)
        tvStatus = findViewById(R.id.tvStatus)

        rvWifi.layoutManager = LinearLayoutManager(this)
        rvWifi.adapter = wifiAdapter
    }

    private fun loadSettings() {
        Settings.load(this)
        swFixedPort.isChecked = Settings.fixedPortEnabled
        etPort.setText(Settings.fixedPort.toString())
        swAutoEnable.isChecked = Settings.autoEnable
        swAutoDisable.isChecked = Settings.autoDisable
    }

    private fun setupListeners() {
        swFixedPort.setOnCheckedChangeListener { _, checked ->
            Settings.fixedPortEnabled = checked
            Settings.save(this)
        }

        btnApplyPort.setOnClickListener {
            val portText = etPort.text.toString().trim()
            val port = portText.toIntOrNull()
            if (port == null || port < 1024 || port > 65535) {
                toast("端口范围: 1024-65535")
                return@setOnClickListener
            }
            Settings.fixedPort = port
            Settings.save(this)
            if (ShellUtils.hasRoot()) {
                val ok = AdbHelper.setFixedPort(port)
                toast(if (ok) "已设置固定端口 $port" else "设置失败，请检查 ROOT 权限")
            } else {
                toast("需要 ROOT 权限才能立即生效")
            }
            updateStatus()
        }

        btnRefreshWifi.setOnClickListener { refreshWifiList() }

        swAutoEnable.setOnCheckedChangeListener { _, checked ->
            Settings.autoEnable = checked
            Settings.save(this)
        }

        swAutoDisable.setOnCheckedChangeListener { _, checked ->
            Settings.autoDisable = checked
            Settings.save(this)
        }

        btnPairingCode.setOnClickListener {
            val code = AdbHelper.readPairingCode()
            val currentSsid = WifiHelper.getCurrentSsid(this)
            val port = AdbHelper.getCurrentPort()
            val msg = buildString {
                appendLine("配对码: ${code.ifBlank { "暂无，请先在开发者选项中开启无线调试并点击配对" }}")
                if (port.isNotBlank()) appendLine("当前端口: $port")
                if (currentSsid.isNotBlank()) appendLine("当前 WiFi: $currentSsid")
                appendLine("ROOT: ${if (ShellUtils.hasRoot()) "可用" else "不可用"}")
            }
            tvStatus.text = msg
        }
    }

    private fun refreshWifiList() {
        val networks = WifiHelper.getSavedNetworks(this)
        val items = networks.map { WifiItem(it.ssid, it.bssid, it.security) }
        wifiAdapter.update(items)
        if (items.isEmpty()) {
            toast("未读取到 WiFi 列表，请授予位置权限")
        }
    }

    private fun updateStatus() {
        val port = AdbHelper.getCurrentPort()
        val enabled = AdbHelper.getCurrentState()
        val ssid = WifiHelper.getCurrentSsid(this)
        val root = ShellUtils.hasRoot()
        tvStatus.text = buildString {
            appendLine("无线调试: ${if (enabled) "已开启" else "未开启"}")
            if (port.isNotBlank()) appendLine("端口: $port")
            if (ssid.isNotBlank()) appendLine("当前 WiFi: $ssid")
            appendLine("ROOT: ${if (root) "可用" else "不可用"}")
        }
    }

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}
