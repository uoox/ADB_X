package top.cbug.adbx.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import top.cbug.adbx.MainActivity
import top.cbug.adbx.R
import top.cbug.adbx.store.Settings as AppSettings

/**
 * Settings tab — automation switches + language picker + about card.
 */
class SettingsFragment : Fragment() {

    private lateinit var swAutoEnable: MaterialSwitch
    private lateinit var swAutoDisable: MaterialSwitch
    private lateinit var swBootStart: MaterialSwitch
    private lateinit var toggleLanguage: MaterialButtonToggleGroup
    private lateinit var cardPairing: com.google.android.material.card.MaterialCardView
    private lateinit var tvAboutVersion: TextView
    private lateinit var tvAboutModuleId: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        swAutoEnable   = view.findViewById(R.id.swAutoEnable)
        swAutoDisable  = view.findViewById(R.id.swAutoDisable)
        swBootStart    = view.findViewById(R.id.swBootStart)
        toggleLanguage = view.findViewById(R.id.toggleLanguage)
        cardPairing    = view.findViewById(R.id.cardPairing)
        tvAboutVersion = view.findViewById(R.id.tvAboutVersion)
        tvAboutModuleId = view.findViewById(R.id.tvAboutModuleId)

        // Pairing shortcut opens the dedicated PairingActivity
        cardPairing.setOnClickListener {
            (activity as? MainActivity)?.openPairingActivity()
        }

        AppSettings.load(requireContext())
        swAutoEnable.isChecked = AppSettings.autoEnable
        swAutoDisable.isChecked = AppSettings.autoDisable
        swBootStart.isChecked = AppSettings.bootStart

        swAutoEnable.setOnCheckedChangeListener { _, c ->
            AppSettings.autoEnable = c
            AppSettings.save(requireContext())
        }
        swAutoDisable.setOnCheckedChangeListener { _, c ->
            AppSettings.autoDisable = c
            AppSettings.save(requireContext())
        }
        swBootStart.setOnCheckedChangeListener { _, c ->
            AppSettings.bootStart = c
            AppSettings.save(requireContext())
        }

        // Restore language selection
        val langButtonId = when (AppSettings.locale) {
            "en"  -> R.id.langEnglish
            "zh"  -> R.id.langChinese
            else  -> R.id.langSystem
        }
        toggleLanguage.check(langButtonId)

        toggleLanguage.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val newLocale = when (checkedId) {
                R.id.langEnglish -> "en"
                R.id.langChinese -> "zh"
                else             -> "system"
            }
            if (newLocale == AppSettings.locale) return@addOnButtonCheckedListener
            AppSettings.locale = newLocale
            AppSettings.save(requireContext())
            // Force activity recreate to re-inflate all fragments with the
            // new locale.
            (activity as? MainActivity)?.recreate()
        }

        // About
        try {
            val info = requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0)
            val version = info.versionName ?: "?"
            tvAboutVersion.text = getString(R.string.about_version) + ": " + version
        } catch (_: PackageManager.NameNotFoundException) {
            tvAboutVersion.text = getString(R.string.about_version) + ": ?"
        }
        tvAboutModuleId.text = getString(R.string.about_module_id) + ": " +
            requireContext().packageName
    }
}
