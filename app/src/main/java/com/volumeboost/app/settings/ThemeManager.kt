package com.volumeboost.app.settings

import com.volumeboost.app.R

/**
 * Maps the user theme preference to an app theme style.
 * Colors come from colors_light.xml / colors_dark.xml via that style — not from system dark mode.
 */
object ThemeManager {

    fun styleRes(mode: ThemeMode): Int = when (mode) {
        ThemeMode.LIGHT -> R.style.Theme_VolumeBoost_Light
        ThemeMode.DARK -> R.style.Theme_VolumeBoost_Dark
    }
}
