package com.volumeboost.app.ui

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.volumeboost.app.R
import com.volumeboost.app.VolumeBoostApplication
import com.volumeboost.app.audio.AudioSessionDiscovery
import com.volumeboost.app.audio.VisualizerSessionScanner
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
    private var settingsDialog: Dialog? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    /** Audio detection permission as of the last check: a change made in system settings. */
    private var hadAudioPermission = false

    /** Set by the open settings sheet to refresh its session detection section. */
    var onAudioDetectionChanged: (() -> Unit)? = null

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hadAudioPermission = granted
            preferences.audioDetectionDenied = !granted
            if (granted) {
                // Find the playing app now instead of at the next periodic check.
                VolumeBoostService.rescan(this)
            } else {
                Toast.makeText(this, R.string.detection_audio_denied, Toast.LENGTH_LONG).show()
            }
            onAudioDetectionChanged?.invoke()
        }

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
        hadAudioPermission = VisualizerSessionScanner.hasPermission(this)
        bindUi()
        bindState(boostStore.snapshot())
        // Only restore a boost that should be running (e.g. after the process was killed).
        // Opening the app must not stop the service when boost was switched off from the
        // notification: those controls stay in the shade until the app toggle is used.
        if (boostStore.snapshot().enabled) {
            VolumeBoostService.sync(this)
        }
        maybeRequestNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        unsubscribe = boostStore.observe { state -> bindState(state) }
        bindState(boostStore.snapshot())
        // Back from the system app settings, where the permission may have been changed.
        val audioPermission = VisualizerSessionScanner.hasPermission(this)
        if (audioPermission != hadAudioPermission) {
            hadAudioPermission = audioPermission
            if (audioPermission) VolumeBoostService.rescan(this)
            onAudioDetectionChanged?.invoke()
        }
    }

    override fun onStop() {
        // If the user leaves mid-drag, still commit the current store value to the shade.
        VolumeBoostService.publishNotification(this)
        unsubscribe?.invoke()
        unsubscribe = null
        super.onStop()
    }

    override fun onDestroy() {
        // Avoid leaking the settings window on rotation / recreate.
        settingsDialog?.dismiss()
        settingsDialog = null
        super.onDestroy()
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
            // Without root, apps like YouTube are boosted only with audio detection.
            if (isChecked && !preferences.audioDetectionAsked && !autoPromptShown &&
                AudioSessionDiscovery.knownAccess(this) == AudioSessionDiscovery.Access.NONE
            ) {
                autoPromptShown = true
                requestAudioDetection()
            }
        }

        binding.settingsButton.setOnClickListener {
            if (settingsDialog?.isShowing == true) return@setOnClickListener
            settingsDialog = SettingsBottomSheet(
                activity = this,
                preferences = preferences,
                boostStore = boostStore,
                onThemeOrLanguageChanged = { recreate() },
                // The service already follows the store; only the shade needs a refresh.
                onMaxBoostChanged = { VolumeBoostService.publishNotification(this) },
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

    /**
     * Asks for RECORD_AUDIO, which lets [VisualizerSessionScanner] find the playing app without
     * root. First explains why (the system dialog only says "record audio") and which answer to
     * pick. When Android no longer shows its dialog (denied twice), opens the app's system
     * settings instead.
     */
    fun requestAudioDetection() {
        if (VisualizerSessionScanner.hasPermission(this)) return
        // No rationale also after an expired "Only this time" grant, when the dialog still
        // shows: only a denial as the last answer means Android stopped asking.
        val blocked = preferences.audioDetectionDenied &&
            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        if (blocked) {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.audio_permission_title)
            .setMessage(R.string.audio_permission_message)
            .setPositiveButton(R.string.audio_permission_continue) { _, _ ->
                launchAudioPermission()
            }
            .setNegativeButton(R.string.audio_permission_later, null)
            .show()
    }

    private fun launchAudioPermission() {
        preferences.audioDetectionAsked = true
        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
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

    private companion object {
        /** The explanation is offered automatically at most once per process. */
        var autoPromptShown = false
    }
}
