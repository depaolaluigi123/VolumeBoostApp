package com.volumeboost.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.android.material.slider.Slider
import com.volumeboost.app.R
import com.volumeboost.app.VolumeBoostApplication
import com.volumeboost.app.databinding.ActivityMainBinding
import com.volumeboost.app.service.VolumeBoostService
import com.volumeboost.app.settings.AppConfigContext
import com.volumeboost.app.settings.PreferencesRepository
import com.volumeboost.app.settings.ThemeManager
import com.volumeboost.app.settings.ThemeMode
import com.volumeboost.app.state.BoostStateStore

/**
 * Presentation layer: writes to [BoostStateStore] and binds widgets from its observers.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: PreferencesRepository
    private lateinit var boostStore: BoostStateStore

    private var unsubscribe: (() -> Unit)? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun attachBaseContext(newBase: android.content.Context) {
        val prefs = PreferencesRepository(newBase)
        super.attachBaseContext(AppConfigContext.wrap(newBase, prefs))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as VolumeBoostApplication
        preferences = app.preferences
        boostStore = app.boostStore
        setTheme(ThemeManager.styleRes(preferences.themeMode))
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        applyWindowChrome()
        bindUi()
        bindState(boostStore.snapshot())
        VolumeBoostService.sync(this)
        maybeRequestNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        unsubscribe = boostStore.observe { state -> bindState(state) }
        bindState(boostStore.snapshot())
    }

    override fun onStop() {
        // If the user leaves mid-drag, still commit the current store value to the shade.
        VolumeBoostService.publishNotification(this)
        unsubscribe?.invoke()
        unsubscribe = null
        super.onStop()
    }

    private fun applyWindowChrome() {
        val lightBars = preferences.themeMode == ThemeMode.LIGHT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }
        window.statusBarColor = resolveThemeColor(
            if (lightBars) R.color.light_status_bar else R.color.dark_status_bar
        )
        window.navigationBarColor = resolveThemeColor(
            if (lightBars) R.color.light_nav_bar else R.color.dark_nav_bar
        )
    }

    private fun resolveThemeColor(colorRes: Int): Int =
        ContextCompat.getColor(this, colorRes)

    private fun bindUi() {
        binding.boostSlider.addOnChangeListener { _: Slider, value: Float, fromUser: Boolean ->
            if (!fromUser) return@addOnChangeListener
            // Unique store value: app UI + audio follow live; notification waits for finger up.
            boostStore.setBoostDb(value.toInt())
        }
        binding.boostSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit

            override fun onStopTrackingTouch(slider: Slider) {
                // One shade snapshot of the same store value the app already shows.
                VolumeBoostService.publishNotification(this@MainActivity)
            }
        })

        binding.enableSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (boostStore.snapshot().enabled == isChecked) {
                bindState(boostStore.snapshot())
                return@setOnCheckedChangeListener
            }
            boostStore.setEnabled(isChecked)
            VolumeBoostService.sync(this)
        }

        binding.settingsButton.setOnClickListener {
            SettingsBottomSheet(
                activity = this,
                preferences = preferences,
                boostStore = boostStore,
                onThemeOrLanguageChanged = { recreate() },
                onMaxBoostChanged = { VolumeBoostService.sync(this) },
                onNotificationDisplayChanged = { VolumeBoostService.publishNotification(this) }
            ).show()
        }
    }

    private fun bindState(state: BoostStateStore.State) {
        applySliderBounds(state.maxBoostDb)
        if (binding.boostSlider.value != state.boostDb.toFloat()) {
            binding.boostSlider.value = state.boostDb.toFloat()
        }
        if (binding.enableSwitch.isChecked != state.enabled) {
            binding.enableSwitch.isChecked = state.enabled
        }
        binding.boostValueText.text = getString(R.string.boost_value_format, state.boostDb)
        binding.boostStateText.text = getString(
            if (state.enabled && state.boostDb > 0) R.string.boost_enabled else R.string.boost_disabled
        )
        val maxDb = state.maxBoostDb.coerceAtLeast(1)
        binding.boostGauge.progress = state.boostDb / maxDb.toFloat()
        binding.boostGauge.reloadThemeColors()
        binding.statusText.text = when {
            state.enabled && state.boostDb > 0 -> getString(R.string.status_boosting, state.boostDb)
            else -> getString(R.string.status_ready)
        }
    }

    private fun applySliderBounds(maxBoostDb: Int) {
        val maxDb = maxBoostDb.toFloat()
        if (binding.boostSlider.valueTo != maxDb) {
            if (binding.boostSlider.value > maxDb) {
                binding.boostSlider.value = maxDb
            }
            binding.boostSlider.valueTo = maxDb
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
