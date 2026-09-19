package com.humanrewrite.keyboard.core

import android.content.Context
import android.content.SharedPreferences
import com.humanrewrite.keyboard.BuildConfig

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("humanrewrite_settings", Context.MODE_PRIVATE)

    var hasAcceptedTerms: Boolean
        get() = prefs.getBoolean(KEY_ACCEPTED_TERMS, false)
        set(value) = prefs.edit().putBoolean(KEY_ACCEPTED_TERMS, value).apply()

    var readPrivacyPolicy: Boolean
        get() = prefs.getBoolean(KEY_READ_PRIVACY, false)
        set(value) = prefs.edit().putBoolean(KEY_READ_PRIVACY, value).apply()

    var readTermsOfService: Boolean
        get() = prefs.getBoolean(KEY_READ_TERMS, false)
        set(value) = prefs.edit().putBoolean(KEY_READ_TERMS, value).apply()

    var appMode: AppMode
        get() = runCatching { AppMode.valueOf(prefs.getString(KEY_APP_MODE, null)!!) }.getOrDefault(AppMode.BOTH)
        set(value) = prefs.edit().putString(KEY_APP_MODE, value.name).apply()

    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME_MODE, null)!!) }.getOrDefault(ThemeMode.SYSTEM)
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value.name).apply()

    /** Fires whenever appMode changes, including from another window/process (e.g. the bubble's own service). */
    fun addAppModeListener(onChange: (AppMode) -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_APP_MODE) onChange(appMode)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    fun addThemeListener(onChange: () -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_THEME_MODE) onChange()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    fun removeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    var rewriteMode: RewriteMode
        get() = RewriteMode.valueOf(prefs.getString(KEY_MODE, RewriteMode.GRAMMAR_ONLY.name)!!)
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    var cloudPolicy: CloudPolicy
        get() = CloudPolicy.valueOf(prefs.getString(KEY_CLOUD_POLICY, CloudPolicy.ASK_BEFORE_CLOUD.name)!!)
        set(value) = prefs.edit().putString(KEY_CLOUD_POLICY, value.name).apply()

    var cloudConsentGranted: Boolean
        get() = prefs.getBoolean(KEY_CLOUD_CONSENT, false)
        set(value) = prefs.edit().putBoolean(KEY_CLOUD_CONSENT, value).apply()

    var backendBaseUrl: String
        get() = prefs.getString(KEY_BACKEND_URL, "").orEmpty().ifEmpty { BuildConfig.BACKEND_URL }
        set(value) = prefs.edit().putString(KEY_BACKEND_URL, value.trim()).apply()

    var backendSessionToken: String
        get() = prefs.getString(KEY_BACKEND_SESSION_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BACKEND_SESSION_TOKEN, value.trim()).apply()

    var numberRowEnabled: Boolean
        get() = prefs.getBoolean(KEY_NUMBER_ROW, false)
        set(value) = prefs.edit().putBoolean(KEY_NUMBER_ROW, value).apply()

    var hapticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTICS, value).apply()

    var keySoundEnabled: Boolean
        get() = prefs.getBoolean(KEY_KEY_SOUND, false)
        set(value) = prefs.edit().putBoolean(KEY_KEY_SOUND, value).apply()

    /** Most recently used emoji first, at most 32. */
    var recentEmoji: List<String>
        get() = prefs.getString(KEY_RECENT_EMOJI, "")!!.split('\n').filter { it.isNotEmpty() }
        set(value) = prefs.edit().putString(KEY_RECENT_EMOJI, value.distinct().take(32).joinToString("\n")).apply()

    var localModelOption: LocalModelOption
        // runCatching: older installs may have saved the removed "NONE" option.
        get() = runCatching { LocalModelOption.valueOf(prefs.getString(KEY_LOCAL_MODEL, null)!!) }
            .getOrDefault(LocalModelOption.SMOLLM2_360M)
        set(value) = prefs.edit().putString(KEY_LOCAL_MODEL, value.name).apply()

    // 0L = never opted into beta. Set once, permanently, by EntitlementStore.optInToBeta() —
    // see that class for the opt-in/expiry/one-time-per-generation logic built on these two fields.
    var betaOptInAtMillis: Long
        get() = prefs.getLong(KEY_BETA_OPT_IN_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_BETA_OPT_IN_AT, value).apply()

    var betaOptInGeneration: Int
        get() = prefs.getInt(KEY_BETA_OPT_IN_GENERATION, 0)
        set(value) = prefs.edit().putInt(KEY_BETA_OPT_IN_GENERATION, value).apply()

    // How long unpinned clips stick around before ClipboardStore prunes them. One of
    // CLIP_RETENTION_DAY_OPTIONS; pinned clips never expire regardless of this setting.
    var clipRetentionDays: Int
        get() = prefs.getInt(KEY_CLIP_RETENTION_DAYS, 15)
        set(value) = prefs.edit().putInt(KEY_CLIP_RETENTION_DAYS, value).apply()

    companion object {
        val CLIP_RETENTION_DAY_OPTIONS = listOf(3, 7, 10, 15)
        private const val KEY_ACCEPTED_TERMS = "accepted_terms"
        private const val KEY_READ_PRIVACY = "read_privacy_policy"
        private const val KEY_READ_TERMS = "read_terms_of_service"
        private const val KEY_APP_MODE = "app_mode"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_MODE = "rewrite_mode"
        private const val KEY_CLOUD_POLICY = "cloud_policy"
        private const val KEY_CLOUD_CONSENT = "cloud_consent"
        private const val KEY_BACKEND_URL = "backend_base_url"
        private const val KEY_BACKEND_SESSION_TOKEN = "backend_session_token"
        private const val KEY_NUMBER_ROW = "number_row"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_KEY_SOUND = "key_sound"
        private const val KEY_LOCAL_MODEL = "local_model"
        private const val KEY_RECENT_EMOJI = "recent_emoji"
        private const val KEY_BETA_OPT_IN_AT = "beta_opt_in_at"
        private const val KEY_BETA_OPT_IN_GENERATION = "beta_opt_in_generation"
        private const val KEY_CLIP_RETENTION_DAYS = "clip_retention_days"
    }
}
