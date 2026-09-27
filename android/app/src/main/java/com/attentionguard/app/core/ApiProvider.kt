package com.attentionguard.app.core

/**
 * Kept as a migration enum for old installs. Attention Guard only exposes
 * DeepSeek in the product UI and all new requests use the official endpoint.
 */
enum class ApiProvider(val storageValue: String, val displayName: String) {
    @Deprecated("Legacy installs only; no longer exposed in the UI")
    OPENROUTER("openrouter", "旧版 OpenRouter"),
    DEEPSEEK_OFFICIAL("deepseek_official", "DeepSeek 官方");

    companion object {
        fun fromStorage(value: String?): ApiProvider =
            if (value == DEEPSEEK_OFFICIAL.storageValue) DEEPSEEK_OFFICIAL else DEEPSEEK_OFFICIAL
    }
}
