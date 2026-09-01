package com.volumeboost.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.volumeboost.app.settings.PreferencesRepository
import com.volumeboost.app.state.BoostStateStore

class VolumeBoostApplication : Application() {

    lateinit var preferences: PreferencesRepository
        private set

    lateinit var boostStore: BoostStateStore
        private set

    override fun onCreate() {
        super.onCreate()
        preferences = PreferencesRepository(this)
        boostStore = BoostStateStore(preferences)
        // One-time detach from AppCompat per-app locales left by older builds.
        // Theme/language are not driven by this API anymore.
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }
}
