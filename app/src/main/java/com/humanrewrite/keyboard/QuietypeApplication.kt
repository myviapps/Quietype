package com.humanrewrite.keyboard

import android.app.Application
import com.humanrewrite.keyboard.core.DiagnosticLog

class QuietypeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticLog.installCrashHandler(this)
    }
}
