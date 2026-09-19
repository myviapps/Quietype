package com.humanrewrite.keyboard.core

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsetsController

enum class ThemeMode(val title: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/** One place for every UI colour. apply() picks light/dark from the user's choice (or the system's day/night). */
object Palette {
    var isDark = false
        private set

    fun apply(context: Context, mode: ThemeMode): Boolean {
        val dark = when (mode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM ->
                (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        val changed = dark != isDark
        isDark = dark
        return changed
    }

    private fun pick(light: Long, dark: Long): Int = (if (isDark) dark else light).toInt()

    val bg get() = pick(0xFFF7F5FC, 0xFF120B22)
    val surface get() = pick(0xFFFFFFFF, 0xFF1E1533)
    val ink get() = pick(0xFF1C1230, 0xFFF2EEFF)
    val muted get() = pick(0xFF6B6480, 0xFFA79EC4)
    val line get() = pick(0xFFE8E4F3, 0xFF2E2447)
    val accent get() = pick(0xFF6C4BF6, 0xFF7C5CFF)
    val accentSoft get() = pick(0xFFECE7FF, 0xFF2C2252)
    val onAccent = Color.WHITE
    val danger get() = pick(0xFFB03A3A, 0xFFFF8A80)
    val dangerSoft get() = pick(0xFFF8E2E2, 0xFF3A1E28)
    val kbBg get() = pick(0xFFEAE6F7, 0xFF0E0819)
    val key get() = pick(0xFFFFFFFF, 0xFF2B2142)
    val keyStroke get() = pick(0xFFEEEAF8, 0xFF352A52)
    val fnKey get() = pick(0xFFDDD6F3, 0xFF3A2E5C)
    val fnKeyStroke get() = pick(0xFFCFC7EC, 0xFF46396B)
    val panelLine get() = pick(0xFFDFD9F0, 0xFF33284F)

    fun styleWindow(window: Window) {
        window.setBackgroundDrawable(ColorDrawable(bg))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (isDark) 0 else flags, flags)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (isDark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }
}
