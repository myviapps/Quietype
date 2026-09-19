package com.humanrewrite.keyboard.core

import android.content.Context
import com.humanrewrite.keyboard.BuildConfig

class EntitlementStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("humanrewrite_entitlement", Context.MODE_PRIVATE)
    private val settingsStore = SettingsStore(appContext)

    fun current(): Entitlement {
        val status = runCatching {
            EntitlementStatus.valueOf(prefs.getString(KEY_STATUS, EntitlementStatus.EXPIRED.name)!!)
        }.getOrDefault(EntitlementStatus.EXPIRED)
        return Entitlement(
            status = status,
            validUntilMillis = prefs.getLong(KEY_VALID_UNTIL, 0L),
            lastVerifiedAtMillis = prefs.getLong(KEY_LAST_VERIFIED, 0L),
            offlineExpiresAtMillis = prefs.getLong(KEY_OFFLINE_EXPIRES, 0L)
        )
    }

    fun save(entitlement: Entitlement) {
        prefs.edit()
            .putString(KEY_STATUS, entitlement.status.name)
            .putLong(KEY_VALID_UNTIL, entitlement.validUntilMillis)
            .putLong(KEY_LAST_VERIFIED, entitlement.lastVerifiedAtMillis)
            .putLong(KEY_OFFLINE_EXPIRES, entitlement.offlineExpiresAtMillis)
            .apply()
    }

    // True only while this device's own 20-day beta window (started by optInToBeta()) hasn't
    // elapsed yet, and only for the generation it was granted under — see CURRENT_BETA_GENERATION.
    fun isBetaActive(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val optInAt = settingsStore.betaOptInAtMillis
        if (optInAt == 0L || settingsStore.betaOptInGeneration != CURRENT_BETA_GENERATION) return false
        return monotonicNow(nowMillis) < optInAt + BETA_DURATION_MILLIS
    }

    // Winding the phone's clock back must not extend the beta or the 24h offline grace: remember the
    // latest time ever seen and never report anything earlier. (Clear data still resets it — a
    // server-issued trial would be needed to close that.)
    @Synchronized
    fun monotonicNow(nowMillis: Long): Long {
        val lastSeen = prefs.getLong(KEY_LAST_SEEN, 0L)
        if (nowMillis > lastSeen) {
            prefs.edit().putLong(KEY_LAST_SEEN, nowMillis).apply()
            return nowMillis
        }
        return lastSeen
    }

    // Whether this device has already spent its beta opt-in for the current generation — active or
    // already expired, doesn't matter. Governs whether the "Try Beta" button should even be shown:
    // it's one shot per generation, not renewable by re-tapping once the window closes.
    fun hasUsedBetaThisGeneration(): Boolean =
        settingsStore.betaOptInAtMillis != 0L && settingsStore.betaOptInGeneration == CURRENT_BETA_GENERATION

    // Starts this device's one-time 20-day beta window right now. Caller should check
    // hasUsedBetaThisGeneration() first — this doesn't guard against being called twice itself,
    // since a second call would just reset the clock, which the UI should never offer.
    fun optInToBeta(nowMillis: Long = System.currentTimeMillis()) {
        settingsStore.betaOptInAtMillis = nowMillis
        settingsStore.betaOptInGeneration = CURRENT_BETA_GENERATION
    }

    // Debug builds skip the paywall so rewrite, clipboard and templates work without a subscription.
    // ponytail: the lock can't be tested in debug; add a developer toggle when paywall QA starts.
    fun isPaidFeatureActive(nowMillis: Long = System.currentTimeMillis()): Boolean {
        return BuildConfig.DEBUG || isBetaActive(nowMillis) || current().isPaidFeatureActive(monotonicNow(nowMillis))
    }

    // The real subscription lives on Google's servers, not in this local cache — Android's "Clear
    // storage/Clear data" wipes this cache unconditionally (no app can exempt specific prefs from
    // that, by OS design), but it can't touch or cancel the actual Play subscription. A never-
    // verified cache (lastVerifiedAtMillis == 0) is indistinguishable locally from "never
    // subscribed" — the only way to tell is to ask Play Billing, which currently only happens in
    // MainActivity.onResume()'s restorePurchases() call. Callers use this to word a blocked-feature
    // message as "open the app to restore it" instead of flatly "not subscribed," covering the
    // real case (data got cleared, subscription is still active on Play) without falsely claiming
    // a fresh install is secretly subscribed.
    fun looksNeverVerified(): Boolean = current().lastVerifiedAtMillis == 0L

    companion object {
        private const val KEY_STATUS = "status"
        private const val KEY_VALID_UNTIL = "valid_until"
        private const val KEY_LAST_VERIFIED = "last_verified"
        private const val KEY_OFFLINE_EXPIRES = "offline_expires"
        private const val KEY_LAST_SEEN = "last_seen_millis"
        const val MAX_OFFLINE_MILLIS = 24L * 60L * 60L * 1000L

        const val BETA_DURATION_MILLIS = 20L * 24L * 60L * 60L * 1000L

        // Bump this in a future release to re-open beta eligibility for everyone, including users
        // who already used an earlier round — matches "he should not get beta again ... until we
        // push an update." A user's saved betaOptInGeneration only matches CURRENT_BETA_GENERATION
        // for the round they opted into, so raising this number makes hasUsedBetaThisGeneration()
        // false again for everyone, without touching their saved betaOptInAtMillis.
        const val CURRENT_BETA_GENERATION = 1
    }
}
