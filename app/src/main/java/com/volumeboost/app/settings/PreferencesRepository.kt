package com.volumeboost.app.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent storage for user choices (SharedPreferences).
 *
 * Live boost sync (app UI ↔ notification) goes through [com.volumeboost.app.state.BoostStateStore];
 * this repository is the durable backing store only.
 */
class PreferencesRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // One-time migration from the old percentage model (0–200% → 0–20 dB).
        if (!prefs.contains(KEY_BOOST_DB) && prefs.contains(KEY_LEGACY_BOOST_LEVEL)) {
            val legacyPercent = prefs.getInt(KEY_LEGACY_BOOST_LEVEL, 0)
            prefs.edit()
                .putInt(KEY_BOOST_DB, (legacyPercent / 10).coerceIn(MIN_BOOST_DB, DEFAULT_MAX_BOOST_DB))
                .remove(KEY_LEGACY_BOOST_LEVEL)
                .commit()
        }
    }

    /** Boost gain in dB. Only store — UI and notification both bind to this value. */
    var boostDb: Int
        get() = prefs.getInt(KEY_BOOST_DB, 0).coerceIn(MIN_BOOST_DB, maxBoostDb)
        set(value) {
            // commit() so UI and notification never see a stale value mid-update.
            prefs.edit()
                .putInt(KEY_BOOST_DB, value.coerceIn(MIN_BOOST_DB, maxBoostDb))
                .commit()
        }

    /** Slider upper bound in dB. Default [DEFAULT_MAX_BOOST_DB], up to [MAX_MAX_BOOST_DB]. */
    var maxBoostDb: Int
        get() = prefs.getInt(KEY_MAX_BOOST_DB, DEFAULT_MAX_BOOST_DB)
            .coerceIn(MIN_MAX_BOOST_DB, MAX_MAX_BOOST_DB)
        set(value) {
            val clamped = value.coerceIn(MIN_MAX_BOOST_DB, MAX_MAX_BOOST_DB)
            val editor = prefs.edit().putInt(KEY_MAX_BOOST_DB, clamped)
            if (prefs.getInt(KEY_BOOST_DB, 0) > clamped) {
                editor.putInt(KEY_BOOST_DB, clamped)
            }
            editor.commit()
        }

    var boostEnabled: Boolean
        get() = prefs.getBoolean(KEY_BOOST_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_BOOST_ENABLED, value).commit()
        }

    /** One async write for the whole boost snapshot (safe for rapid slider drags). */
    fun writeBoostState(boostDb: Int, enabled: Boolean, maxBoostDb: Int) {
        val max = maxBoostDb.coerceIn(MIN_MAX_BOOST_DB, MAX_MAX_BOOST_DB)
        val db = boostDb.coerceIn(MIN_BOOST_DB, max)
        prefs.edit()
            .putInt(KEY_MAX_BOOST_DB, max)
            .putInt(KEY_BOOST_DB, db)
            .putBoolean(KEY_BOOST_ENABLED, enabled)
            .apply()
    }

    var themeMode: ThemeMode
        get() = ThemeMode.fromStorage(prefs.getString(KEY_THEME, ThemeMode.DARK.storageValue))
        set(value) = prefs.edit().putString(KEY_THEME, value.storageValue).apply()

    var language: AppLanguage
        get() = AppLanguage.fromStorage(prefs.getString(KEY_LANGUAGE, AppLanguage.ENGLISH.storageValue))
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value.storageValue).apply()

    /** Classic RemoteViews notification (−1 / +1 / checkbox). */
    var showClassicNotification: Boolean
        get() = prefs.getBoolean(KEY_SHOW_CLASSIC_NOTIFICATION, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_CLASSIC_NOTIFICATION, value).apply()

    /** MediaStyle notification with YouTube/Spotify-like seek bar. */
    var showMediaNotification: Boolean
        get() = prefs.getBoolean(KEY_SHOW_MEDIA_NOTIFICATION, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_MEDIA_NOTIFICATION, value).apply()

    /** Whether the app already explained and asked for audio detection (RECORD_AUDIO). */
    var audioDetectionAsked: Boolean
        get() = prefs.getBoolean(KEY_AUDIO_DETECTION_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUDIO_DETECTION_ASKED, value).apply()

    /** Whether the last answer to the RECORD_AUDIO request was a denial. */
    var audioDetectionDenied: Boolean
        get() = prefs.getBoolean(KEY_AUDIO_DETECTION_DENIED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUDIO_DETECTION_DENIED, value).apply()

    fun registerOnChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterOnChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        const val MIN_BOOST_DB = 0
        const val DEFAULT_MAX_BOOST_DB = 20
        const val MIN_MAX_BOOST_DB = 20
        const val MAX_MAX_BOOST_DB = 75

        const val KEY_BOOST_DB = "boost_db"
        const val KEY_MAX_BOOST_DB = "max_boost_db"
        const val KEY_BOOST_ENABLED = "boost_enabled"

        private const val PREFS_NAME = "volume_boost_prefs"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_SHOW_CLASSIC_NOTIFICATION = "show_classic_notification"
        private const val KEY_SHOW_MEDIA_NOTIFICATION = "show_media_notification"
        private const val KEY_AUDIO_DETECTION_ASKED = "audio_detection_asked"
        private const val KEY_AUDIO_DETECTION_DENIED = "audio_detection_denied"

        /** Pre-dB storage key (percentage 0–200), kept only for migration. */
        private const val KEY_LEGACY_BOOST_LEVEL = "boost_level"
    }
}

enum class ThemeMode(val storageValue: String) {
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromStorage(value: String?): ThemeMode =
            entries.firstOrNull { it.storageValue == value } ?: DARK
    }
}

enum class AppLanguage(val storageValue: String, val localeTag: String) {
    ENGLISH("en", "en"),
    ITALIAN("it", "it");

    companion object {
        fun fromStorage(value: String?): AppLanguage =
            entries.firstOrNull { it.storageValue == value } ?: ENGLISH
    }
}
