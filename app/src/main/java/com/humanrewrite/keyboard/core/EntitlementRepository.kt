package com.humanrewrite.keyboard.core

import com.humanrewrite.keyboard.BuildConfig
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class EntitlementRepository(
    private val settingsStore: SettingsStore,
    private val entitlementStore: EntitlementStore
) {
    // integrityToken is null until IntegrityGateway is configured with a real cloud project number
    // (see docs/play-launch-checklist.md) — the backend accepts a purchase-token-only request
    // meanwhile, so this stays optional rather than blocking verification entirely.
    fun verifyPurchaseToken(purchaseToken: String, integrityToken: String? = null): Boolean {
        val baseUrl = settingsStore.backendBaseUrl.trimEnd('/')
        if (baseUrl.isEmpty() || purchaseToken.isBlank()) return false
        // Release builds only talk to the backend over TLS; plain http is for local dev builds.
        if (!BuildConfig.DEBUG && !baseUrl.startsWith("https://")) return false

        val entitlement = runCatching {
            val connection = (URL("$baseUrl/entitlements/verify").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use {
                val body = JSONObject().put("purchaseToken", purchaseToken)
                if (integrityToken != null) body.put("integrityToken", integrityToken)
                it.write(body.toString())
            }
            if (connection.responseCode !in 200..299) return@runCatching null
            parseEntitlement(connection.inputStream.bufferedReader().use { it.readText() })
        }.getOrNull()

        entitlement?.let(entitlementStore::save)
        return entitlement != null && entitlement.status != EntitlementStatus.EXPIRED
    }

    private fun parseEntitlement(raw: String): Entitlement {
        val json = JSONObject(raw)
        json.optString("sessionToken").takeIf { it.isNotEmpty() }?.let { settingsStore.backendSessionToken = it }
        val now = System.currentTimeMillis()
        val validUntil = json.optLong("validUntilMillis", 0L)
        val offlineUntil = minOf(validUntil, now + EntitlementStore.MAX_OFFLINE_MILLIS)
        return Entitlement(
            status = EntitlementStatus.valueOf(json.optString("status", EntitlementStatus.EXPIRED.name)),
            validUntilMillis = validUntil,
            lastVerifiedAtMillis = json.optLong("lastVerifiedAtMillis", now),
            offlineExpiresAtMillis = minOf(json.optLong("offlineExpiresAtMillis", offlineUntil), offlineUntil)
        )
    }
}
