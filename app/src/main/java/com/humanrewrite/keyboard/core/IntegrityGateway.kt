package com.humanrewrite.keyboard.core

import android.content.Context
import android.util.Base64
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import java.security.MessageDigest

/**
 * Proves a purchase-verification request came from a genuine, unmodified copy of this app
 * installed through Play — not a patched APK with the entitlement check ripped out, and not a
 * replayed request from a previous, legitimate one.
 *
 * Uses Play Integrity's "Standard" request flow: `prepare()` warms up a token provider once
 * (call it early, e.g. app start), then `requestToken()` asks Google for a token bound to a hash
 * of that specific request — here, the purchase token being verified — so the backend can tell
 * this token was issued for this exact verification attempt, not copied from an earlier one.
 * The backend then decodes the token with Google's Play Integrity API before trusting the
 * purchase. See docs/play-launch-checklist.md — this needs your Play Console-linked Google Cloud
 * project number (CLOUD_PROJECT_NUMBER below) and, server-side, a service account with the Play
 * Integrity API enabled before the backend can actually decode tokens.
 */
class IntegrityGateway(context: Context) {
    private val integrityManager = IntegrityManagerFactory.createStandard(context.applicationContext)
    private var tokenProvider: StandardIntegrityTokenProvider? = null

    /** Call once, early (e.g. Application/Activity start) — preparing a provider takes a moment. */
    fun prepare() {
        if (CLOUD_PROJECT_NUMBER == 0L || tokenProvider != null) return
        val request = StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
            .setCloudProjectNumber(CLOUD_PROJECT_NUMBER)
            .build()
        integrityManager.prepareIntegrityToken(request)
            .addOnSuccessListener { tokenProvider = it }
            .addOnFailureListener { /* stays null; requestToken() below falls back gracefully */ }
    }

    /** [requestPayload] is typically the purchase token being verified, binding the integrity token to it. */
    fun requestToken(requestPayload: String, onResult: (token: String?) -> Unit) {
        val provider = tokenProvider
        if (CLOUD_PROJECT_NUMBER == 0L || provider == null) {
            // Not configured/prepared yet — verification falls back to purchase-token-only
            // (still real, just without the extra tamper/replay proof). See checklist section 2.
            onResult(null)
            return
        }
        val request = StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
            .setRequestHash(sha256Base64Url(requestPayload))
            .build()
        provider.request(request)
            .addOnSuccessListener { response -> onResult(response.token()) }
            .addOnFailureListener { onResult(null) }
    }

    private fun sha256Base64Url(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private companion object {
        // TODO: set this to the Google Cloud project number linked to your Play Console app
        // (Play Console → App integrity → Play Integrity API). 0 disables the integrity check.
        const val CLOUD_PROJECT_NUMBER = 0L
    }
}
