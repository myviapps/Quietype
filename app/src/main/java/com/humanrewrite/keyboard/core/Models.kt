package com.humanrewrite.keyboard.core

enum class RewriteMode(val title: String) {
    GRAMMAR_ONLY("Grammar Only"),
    NATURAL_CASUAL("Natural Casual"),
    PROFESSIONAL("Professional");

    fun next(): RewriteMode = when (this) {
        GRAMMAR_ONLY -> NATURAL_CASUAL
        NATURAL_CASUAL -> PROFESSIONAL
        PROFESSIONAL -> GRAMMAR_ONLY
    }
}

/** Which rewrite entry point(s) the user wants set up: the keyboard, the floating shortcut, or both. */
enum class AppMode(val title: String) {
    KEYBOARD_ONLY("Keyboard"),
    SHORTCUT_ONLY("Shortcut"),
    BOTH("Both");

    val needsKeyboard: Boolean get() = this != SHORTCUT_ONLY
    val needsShortcut: Boolean get() = this != KEYBOARD_ONLY
}

enum class CloudPolicy {
    ON_DEVICE_ONLY,
    ASK_BEFORE_CLOUD,
    DISABLE_CLOUD
}

// Users only ever see "Low" and "High"; which model sits behind each stays internal.
enum class LocalModelOption(val title: String, val detail: String, val fileName: String, val url: String, val sha256: String) {
    SMOLLM2_360M(
        "Low",
        "Faster · 271 MB download",
        "SmolLM2-360M-Instruct-Q4_K_M.gguf",
        // Pinned to a repo commit, and verified against this digest after download.
        "https://huggingface.co/bartowski/SmolLM2-360M-Instruct-GGUF/resolve/7be6f65f1db715fe5dc5a4634c0d459b4eed42ec/SmolLM2-360M-Instruct-Q4_K_M.gguf",
        "2fa3f013dcdd7b99f9b237717fa0b12d75bbb89984cc1274be1471a465bac9c2"
    ),
    LLAMA32_1B(
        "High",
        "Better, but slower · 808 MB download",
        "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
        "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/067b946cf014b7c697f3654f621d577a3e3afd1c/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
        "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83"
    )
}

enum class EntitlementStatus {
    ACTIVE,
    EXPIRED,
    GRACE
}

data class Entitlement(
    val status: EntitlementStatus,
    val validUntilMillis: Long,
    val lastVerifiedAtMillis: Long,
    val offlineExpiresAtMillis: Long
) {
    fun isPaidFeatureActive(nowMillis: Long): Boolean {
        if (status != EntitlementStatus.ACTIVE && status != EntitlementStatus.GRACE) return false
        if (nowMillis > validUntilMillis) return false
        if (nowMillis > offlineExpiresAtMillis) return false
        return true
    }
}

data class Clip(
    val id: String,
    val text: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val pinned: Boolean,
    val type: String
)

data class Template(
    val id: String,
    val title: String,
    val body: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

data class RewriteRequest(
    val text: String,
    val mode: RewriteMode,
    val localModel: LocalModelOption = LocalModelOption.SMOLLM2_360M,
    // Only read by the dormant CloudRewriteEngine (future plan) — RewriteCoordinator never sets it true.
    val allowCloudFallback: Boolean = false
)

sealed class RewriteResult {
    data class Success(val text: String, val source: String) : RewriteResult()
    data class Locked(val reason: String) : RewriteResult()
    data class NeedsCloudConsent(val reason: String) : RewriteResult()
    data class Failed(val reason: String) : RewriteResult()
}
