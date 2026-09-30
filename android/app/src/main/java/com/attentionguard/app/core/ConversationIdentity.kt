package com.attentionguard.app.core

import java.text.Normalizer

/** Recording identity must preserve meaningful punctuation, spaces and letter case. */
internal object ConversationIdentity {
    private val memberCount = Regex("\\s*\\(\\s*\\d{1,5}\\s*\\)\\s*$")
    private val invisible = Regex("[\u200B\uFEFF]")
    fun sameTitle(first: String, second: String): Boolean {
        fun canonical(value: String) = memberCount.replace(
            invisible.replace(Normalizer.normalize(value, Normalizer.Form.NFKC), "").trim(), "").trim()
        val a = canonical(first)
        val b = canonical(second)
        return a.isNotBlank() && a == b
    }
}
