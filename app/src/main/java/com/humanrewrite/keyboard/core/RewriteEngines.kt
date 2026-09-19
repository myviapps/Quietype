package com.humanrewrite.keyboard.core

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

interface RewriteEngine {
    fun rewrite(request: RewriteRequest): RewriteResult
}

/**
 * Future-plan cloud fallback: built and working (backend calls a real model, see
 * backend/src/rewrite.js), but disconnected from RewriteCoordinator for now — the product's
 * current pitch is "100% on-device, nothing ever leaves your phone." Re-wire this once cloud
 * fallback becomes an explicit opt-in feature again.
 */
class CloudRewriteEngine(
    private val settingsStore: SettingsStore
) : RewriteEngine {
    override fun rewrite(request: RewriteRequest): RewriteResult {
        val baseUrl = settingsStore.backendBaseUrl.trimEnd('/')
        if (baseUrl.isEmpty()) return RewriteResult.Failed("Cloud rewrite backend is not configured")

        return runCatching {
            val connection = (URL("$baseUrl/rewrite").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                settingsStore.backendSessionToken.takeIf { it.isNotBlank() }?.let {
                    setRequestProperty("Authorization", "Bearer $it")
                }
            }
            val payload = JSONObject()
                .put("text", request.text)
                .put("mode", request.mode.name)
                .put("allowCloudFallback", request.allowCloudFallback)
                .put("localModel", request.localModel.name)
                .toString()
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(payload) }

            val responseCode = connection.responseCode
            val body = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }
            if (responseCode !in 200..299) {
                RewriteResult.Failed("Cloud rewrite failed: HTTP $responseCode")
            } else {
                val rewritten = JSONObject(body).optString("text").trim()
                if (rewritten.isEmpty()) RewriteResult.Failed("Cloud rewrite returned empty text")
                else RewriteResult.Success(rewritten, "cloud")
            }
        }.getOrElse {
            RewriteResult.Failed("Cloud rewrite unavailable")
        }
    }
}

// On-device only for now: no cloud fallback is called, by design (see CloudRewriteEngine).
class RewriteCoordinator(
    private val entitlementStore: EntitlementStore,
    private val settingsStore: SettingsStore,
    private val onDeviceAiGateway: OnDeviceAiGateway
) {
    fun warmUp() {
        if (entitlementStore.isPaidFeatureActive() && onDeviceAiGateway.isRewriteSupported()) onDeviceAiGateway.warmUp()
    }

    fun rewrite(text: String): RewriteResult {
        if (!entitlementStore.isPaidFeatureActive()) {
            // See EntitlementStore.looksNeverVerified(): a cache wiped by "Clear data" looks
            // identical to "never subscribed" locally — word the message for the real-subscriber
            // case since re-opening the app silently restores it via Play Billing.
            val reason = if (entitlementStore.looksNeverVerified()) {
                "Subscription required. Already subscribed? Open Quietype once to restore it."
            } else {
                "Subscription required. Reconnect and verify your monthly plan."
            }
            return RewriteResult.Locked(reason)
        }

        val option = settingsStore.localModelOption
        val request = RewriteRequest(text, settingsStore.rewriteMode, localModel = option)
        return if (onDeviceAiGateway.isRewriteSupported()) {
            onDeviceAiGateway.rewrite(request)
        } else {
            RewriteResult.Failed("Download the ${option.title} model in Quietype settings first")
        }
    }
}
