package com.volumeboost.app.state

import android.os.Handler
import android.os.Looper
import com.volumeboost.app.settings.PreferencesRepository
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-process single source of truth for boost level / enable / max.
 *
 * UI and notification only write here; observers refresh every view.
 * [PreferencesRepository] is persistence only — not the live sync bus.
 */
class BoostStateStore(
    private val preferences: PreferencesRepository
) {

    data class State(
        val boostDb: Int,
        val enabled: Boolean,
        val maxBoostDb: Int
    )

    fun interface Observer {
        fun onBoostStateChanged(state: State)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArrayList<Observer>()

    @Volatile
    private var state: State = readFromPreferences()

    fun snapshot(): State = state

    fun observe(observer: Observer): () -> Unit {
        observers.add(observer)
        return { observers.remove(observer) }
    }

    fun setBoostDb(db: Int) {
        update { copy(boostDb = db.coerceIn(PreferencesRepository.MIN_BOOST_DB, maxBoostDb)) }
    }

    fun adjustBoostDb(delta: Int) {
        update {
            copy(
                boostDb = (boostDb + delta).coerceIn(
                    PreferencesRepository.MIN_BOOST_DB,
                    maxBoostDb
                )
            )
        }
    }

    fun setEnabled(enabled: Boolean) {
        update { copy(enabled = enabled) }
    }

    fun setMaxBoostDb(maxDb: Int) {
        update {
            val clamped = maxDb.coerceIn(
                PreferencesRepository.MIN_MAX_BOOST_DB,
                PreferencesRepository.MAX_MAX_BOOST_DB
            )
            copy(
                maxBoostDb = clamped,
                boostDb = boostDb.coerceAtMost(clamped)
            )
        }
    }

    private fun update(transform: State.() -> State) {
        val next: State
        synchronized(this) {
            next = state.transform()
            if (next == state) return
            state = next
            persist(next)
        }
        dispatch(next)
    }

    private fun persist(next: State) {
        // Single apply() — avoid 3× commit() on every slider tick (was stalling notification updates).
        preferences.writeBoostState(next.boostDb, next.enabled, next.maxBoostDb)
    }

    private fun dispatch(next: State) {
        val deliver = {
            for (observer in observers) {
                observer.onBoostStateChanged(next)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            deliver()
        } else {
            mainHandler.post(deliver)
        }
    }

    private fun readFromPreferences(): State = State(
        boostDb = preferences.boostDb,
        enabled = preferences.boostEnabled,
        maxBoostDb = preferences.maxBoostDb
    )
}
