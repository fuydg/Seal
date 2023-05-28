package com.deniscerri.ytdlnis.ui.more.settings

import android.content.Intent
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.deniscerri.ytdlnis.MainActivity
import com.deniscerri.ytdlnis.R
import com.deniscerri.ytdlnis.util.FileUtil
import com.deniscerri.ytdlnis.util.ThemeUtil
import com.deniscerri.ytdlnis.util.UpdateUtil
import java.util.Locale


class GeneralSettingsFragment : BaseSettingsFragment() {
    override val title: Int = R.string.general

    private var language: ListPreference? = null
    private var theme: ListPreference? = null
    private var accent: ListPreference? = null
    private var highContrast: SwitchPreferenceCompat? = null
    private var locale: ListPreference? = null

    private var updateUtil: UpdateUtil? = null
    private var activeDownloadCount = 0

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.general_preferences, rootKey)
        updateUtil = UpdateUtil(requireContext())

        WorkManager.getInstance(requireContext()).getWorkInfosByTagLiveData("download").observe(this){
            activeDownloadCount = 0
            it.forEach {w ->
                if (w.state == WorkInfo.State.RUNNING) activeDownloadCount++
            }
        }

        language = findPreference("app_language")
        theme = findPreference("ytdlnis_theme")
        accent = findPreference("theme_accent")
        highContrast = findPreference("high_contrast")
        locale = findPreference("locale")

        if(VERSION.SDK_INT < VERSION_CODES.TIRAMISU){
            val values = resources.getStringArray(R.array.language_values)
            val entries = mutableListOf<String>()
            values.forEach {
                entries.add(Locale(it).getDisplayName(Locale(it)))
            }
            language!!.entries = entries.toTypedArray()
        }else{
            language!!.isVisible = false
        }

        if(language!!.value == null) language!!.value = Locale.getDefault().language
        language!!.summary = Locale(language!!.value).getDisplayLanguage(Locale(language!!.value))
        language!!.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _: Preference?, newValue: Any ->
                language!!.summary = Locale(newValue.toString()).getDisplayLanguage(Locale(newValue.toString()))
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(newValue.toString()))
                true
            }

        theme!!.summary = theme!!.entry
        theme!!.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _: Preference?, newValue: Any ->
                when(newValue){
                    "System" -> {
                        theme!!.summary = getString(R.string.system)
                    }
                    "Dark" -> {
                        theme!!.summary = getString(R.string.dark)
                    }
                    else -> {
                        theme!!.summary = getString(R.string.light)
                    }
                }
                ThemeUtil.updateTheme(requireActivity() as AppCompatActivity)
                val intent = Intent(requireContext(), MainActivity::class.java)
                this.startActivity(intent)
                requireActivity().finishAffinity()
                true
            }
        accent!!.summary = accent!!.entry
        accent!!.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _: Preference?, _: Any ->
                ThemeUtil.updateTheme(requireActivity() as AppCompatActivity)
                val intent = Intent(requireContext(), MainActivity::class.java)
                this.startActivity(intent)
                requireActivity().finishAffinity()
                true
            }
        highContrast!!.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _: Preference?, _: Any ->
                ThemeUtil.updateTheme(requireActivity() as AppCompatActivity)
                val intent = Intent(requireContext(), MainActivity::class.java)
                this.startActivity(intent)
                requireActivity().finishAffinity()

                true
            }

    }
}