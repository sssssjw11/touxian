package com.attentionguard.app.core

import java.text.Normalizer
import java.util.Locale

/** Matches user-entered title keywords without relying on WeChat's display formatting. */
internal object ConversationScope {
    private val memberCount = Regex("\\s*\\(\\s*\\d{1,5}\\s*\\)\\s*$")
    private val invisibleSpacing = Regex("[\u200B\uFEFF]")
    private val separators = setOf('-', '_', '.', '·', '•', '—', '–', '(', ')', '[', ']', '【', '】', '，', ',', '、', '/', '／')

    fun equivalent(first: String, second: String): Boolean = normalize(first).let { it.isNotEmpty() && it == normalize(second) }

    fun matches(title: String, term: String): Boolean {
        val target = normalize(title)
        val query = normalize(term)
        if (query.isEmpty() || target.isEmpty()) return false
        if (target == query) return true
        val foldedTerm = Normalizer.normalize(term.trim(), Normalizer.Form.NFKC)
        if (memberCount.containsMatchIn(foldedTerm)) return false
        if (query.length < 2) return false

        val original = fold(title)
        val positions = original.indices.filter { !original[it].isSeparator() }
        var index = target.indexOf(query)
        while (index >= 0) {
            val end = index + query.length
            val leftOk = !query.first().isAsciiWord() || index == 0 || !target[index - 1].isAsciiWord() ||
                positions[index] - positions[index - 1] > 1
            val rightOk = !query.last().isAsciiWord() || end == target.length || !target[end].isAsciiWord() ||
                positions[end] - positions[end - 1] > 1
            if (leftOk && rightOk) return true
            index = target.indexOf(query, index + 1)
        }
        return false
    }

    private fun fold(value: String): String {
        val folded = invisibleSpacing.replace(Normalizer.normalize(value.trim(), Normalizer.Form.NFKC), "").lowercase(Locale.ROOT)
        return memberCount.replace(folded, "")
    }

    private fun normalize(value: String): String = fold(value).filterNot { it.isSeparator() }
    private fun Char.isSeparator(): Boolean = isWhitespace() || this in separators

    private fun Char.isAsciiWord(): Boolean = this in 'a'..'z' || this in '0'..'9'
}
