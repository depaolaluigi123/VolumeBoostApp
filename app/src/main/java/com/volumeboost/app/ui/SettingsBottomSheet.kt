package com.volumeboost.app.ui

import android.view.LayoutInflater
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.Slider
import com.volumeboost.app.R
import com.volumeboost.app.databinding.BottomSheetSettingsBinding
import com.volumeboost.app.settings.AppLanguage
import com.volumeboost.app.settings.PreferencesRepository
import com.volumeboost.app.settings.ThemeMode
import com.volumeboost.app.state.BoostStateStore

/**
 * Settings sheet. Theme/language changes only update SharedPreferences,
 * then recreate the Activity so app XML resources are reloaded.
 * Max boost (dB) updates via [BoostStateStore] so all observers refresh.
 */
class SettingsBottomSheet(
    private val activity: MainActivity,
    private val preferences: PreferencesRepository,
    private val boostStore: BoostStateStore,
    private val onThemeOrLanguageChanged: () -> Unit,
    private val onMaxBoostChanged: () -> Unit,
    private val onNotificationDisplayChanged: () -> Unit
) {

    fun show() {
        val dialog = BottomSheetDialog(activity)
        val binding = BottomSheetSettingsBinding.inflate(LayoutInflater.from(activity))
        dialog.setContentView(binding.root)

        when (preferences.themeMode) {
            ThemeMode.LIGHT -> binding.themeToggle.check(R.id.themeLightButton)
            ThemeMode.DARK -> binding.themeToggle.check(R.id.themeDarkButton)
        }
        when (preferences.language) {
            AppLanguage.ENGLISH -> binding.languageToggle.check(R.id.langEnglishButton)
            AppLanguage.ITALIAN -> binding.languageToggle.check(R.id.langItalianButton)
        }

        binding.maxBoostSlider.value = boostStore.snapshot().maxBoostDb.toFloat()
        binding.maxBoostValue.text =
            activity.getString(R.string.max_boost_value_format, boostStore.snapshot().maxBoostDb)
        binding.maxBoostSlider.addOnChangeListener { _: Slider, value: Float, fromUser: Boolean ->
            val maxDb = value.toInt()
            binding.maxBoostValue.text =
                activity.getString(R.string.max_boost_value_format, maxDb)
            if (fromUser) {
                boostStore.setMaxBoostDb(maxDb)
                onMaxBoostChanged()
            }
        }

        binding.classicNotificationSwitch.isChecked = preferences.showClassicNotification
        binding.mediaNotificationSwitch.isChecked = preferences.showMediaNotification
        binding.classicNotificationSwitch.setOnCheckedChangeListener { _, checked ->
            if (preferences.showClassicNotification == checked) return@setOnCheckedChangeListener
            preferences.showClassicNotification = checked
            onNotificationDisplayChanged()
        }
        binding.mediaNotificationSwitch.setOnCheckedChangeListener { _, checked ->
            if (preferences.showMediaNotification == checked) return@setOnCheckedChangeListener
            preferences.showMediaNotification = checked
            onNotificationDisplayChanged()
        }

        binding.themeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.themeLightButton -> ThemeMode.LIGHT
                else -> ThemeMode.DARK
            }
            if (mode != preferences.themeMode) {
                preferences.themeMode = mode
                dialog.dismiss()
                onThemeOrLanguageChanged()
            }
        }

        binding.languageToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val language = when (checkedId) {
                R.id.langItalianButton -> AppLanguage.ITALIAN
                else -> AppLanguage.ENGLISH
            }
            if (language != preferences.language) {
                preferences.language = language
                dialog.dismiss()
                onThemeOrLanguageChanged()
            }
        }

        dialog.show()
    }
}
