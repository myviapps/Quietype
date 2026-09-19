package com.humanrewrite.keyboard.core

import android.content.Context
import android.os.Build
import com.humanrewrite.keyboard.BuildConfig
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * A privacy-safe, in-app diagnostic trail — never the user's typed or rewritten text, only short
 * lifecycle/error markers (e.g. "rewrite failed: model not ready"). Android has blocked normal apps
 * from reading system logcat since API 16, so this is the app's own record: the only way a "send
 * diagnostic report" feature can attach anything useful without needing special permissions.
 */
object DiagnosticLog {
    private const val MAX_EVENTS = 40
    private val events = ArrayDeque<String>()
    // SimpleDateFormat isn't thread-safe and is used from the crash handler thread too.
    private fun now(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(System.currentTimeMillis())

    @Synchronized
    fun log(event: String) {
        if (events.size >= MAX_EVENTS) events.removeFirst()
        events.addLast("${now()}  $event")
    }

    // Installs once per process (see QuietypeApplication) so a crash anywhere — keyboard, bubble,
    // or the main app — is captured before the OS's default handler kills the process.
    fun installCrashHandler(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val trace = throwable.stackTraceToString().take(4000)
                prefs.edit()
                    .putString(KEY_LAST_CRASH, "${now()} on ${thread.name}\n$trace")
                    .apply()
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    // Plain text, meant to be pasted straight into an email body — see MainActivity.sendDiagnosticReport().
    @Synchronized
    fun report(context: Context): String {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val settingsStore = SettingsStore(appContext)
        val entitlementStore = EntitlementStore(appContext)
        val lastCrash = prefs.getString(KEY_LAST_CRASH, null)
        return buildString {
            appendLine("Quietype diagnostic report")
            appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${if (BuildConfig.DEBUG) "debug" else "release"})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Plan: ${planSummary(entitlementStore)}")
            appendLine("Selected model: ${settingsStore.localModelOption.title}")
            appendLine("Rewrite mode: ${settingsStore.rewriteMode.title}")
            appendLine("App mode: ${settingsStore.appMode.title}")
            if (lastCrash != null) {
                appendLine()
                appendLine("Last crash:")
                append(lastCrash)
                appendLine()
            }
            appendLine()
            appendLine("Recent events:")
            if (events.isEmpty()) appendLine("(none)") else events.forEach { appendLine(it) }
        }
    }

    private fun planSummary(entitlementStore: EntitlementStore): String {
        val now = System.currentTimeMillis()
        return when {
            BuildConfig.DEBUG -> "debug build"
            entitlementStore.isBetaActive(now) -> "beta"
            entitlementStore.isPaidFeatureActive(now) -> "premium"
            entitlementStore.hasUsedBetaThisGeneration() -> "free (beta used)"
            else -> "free"
        }
    }

    private const val PREFS_NAME = "humanrewrite_diagnostics"
    private const val KEY_LAST_CRASH = "last_crash"
}
