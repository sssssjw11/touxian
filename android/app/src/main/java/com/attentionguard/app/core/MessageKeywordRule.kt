package com.attentionguard.app.core

import java.text.Normalizer
import java.util.Locale
import java.util.UUID

/** A user-selected message-content rule, separate from conversation scope. */
data class MessageKeywordRule(
    val keyword: String,
    val category: EventCategory = EventCategory.ACADEMIC_ADMIN,
    val priority: EventPriority = EventPriority.P2,
    val id: String = UUID.randomUUID().toString(),
    val enabled: Boolean = true
) {
    fun matches(text: String): Boolean = matchingClauses(text).isNotEmpty()

    internal fun matchingClauses(text: String): List<String> {
        val query = normalizedKeyword(keyword)
        if (!enabled || query.isBlank() || keyword.trim().length > 80 || NoticeRules.isReported(text)) return emptyList()
        // A request elsewhere does not make an unrelated quoted topic its object.
        val source = quotedText.replace(text) { quote ->
            val prefix = text.substring(0, quote.range.first).takeLast(32)
            if (requestObject.containsMatchIn(prefix)) quote.value else " "
        }
        return findMatchingClauses(normalizedKeyword(source), query)
    }

    private fun findMatchingClauses(source: String, query: String): List<String> {
        val matches = mutableListOf<String>()
        var start = source.indexOf(query)
        while (start >= 0) {
            val end = start + query.length
            val left = !query.first().isAsciiWord() || start == 0 || !source[start - 1].isAsciiWord()
            val right = !query.last().isAsciiWord() || end == source.length || !source[end].isAsciiWord()
            val prefix = source.substring(maxOf(0, start - 16), start)
            val deniedEvent = eventTopic.containsMatchIn(query) && absentEvent.containsMatchIn(prefix)
            if (left && right && !negation.containsMatchIn(prefix) && !deniedEvent) {
                val from = clauseSeparator.findAll(source.substring(0, start)).lastOrNull()?.range?.last?.plus(1) ?: 0
                val to = clauseSeparator.find(source, end)?.range?.first ?: source.length
                matches.add(source.substring(from, to).trim())
            }
            start = source.indexOf(query, start + 1)
        }
        return matches.distinct()
    }

    companion object {
        const val EVIDENCE_PREFIX = "消息关键词命中："
        private val quotedText = Regex("“[^”]*”|「[^」]*」|\"[^\"]*\"")
        private val negation = Regex("(?:不是|并非|不会|未涉及)[^，,。！？!?；;\\n]{0,4}$")
        private val clauseSeparator = Regex("[，,。！？!?；;\\n]")
        private val requestObject = Regex("(?:请|麻烦|务必|需要|记得|帮我|尽快|抓紧)[^，,。！？!?；;\\n“”\"「」]{0,12}(?:提交|报名|参加|填写|填报|申请|完成|上传|查看|阅读|下载|转发|确认|回复|发给|领取|登记)[^，,。！？!?；;\\n“”\"「」]{0,4}$")
        private val eventTopic = Regex("考试|会议|班会|讲座|活动|竞赛|比赛|培训|训练营|作业|报名|提交|停电|停水")
        private val absentEvent = Regex("(?:没有|未有|无|不(?!用|必|需要))(?:(?:安排|组织|举行|举办|进行))?[^，,。！？!?；;\\n]{0,2}$")

        fun normalizedKeyword(value: String): String =
            Normalizer.normalize(value, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT)

        internal fun legacyId(keyword: String): String = UUID.nameUUIDFromBytes(
            ("message-keyword:" + normalizedKeyword(keyword)).toByteArray(Charsets.UTF_8)).toString()

        private fun Char.isAsciiWord(): Boolean = this in 'a'..'z' || this in '0'..'9' || this == '_'
    }
}
