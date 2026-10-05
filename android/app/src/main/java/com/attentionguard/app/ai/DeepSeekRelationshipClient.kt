package com.attentionguard.app.ai

import com.attentionguard.app.core.*
import java.net.HttpURLConnection
import java.net.URL

/** Statistics and boundary/group facts remain local; grounded semantic additions are still useful. */
class DeepSeekRelationshipClient internal constructor(key: String, model: String, openConnection: () -> HttpURLConnection) {
    constructor(key: String, model: String) : this(key, model, {
        URL("https://api.deepseek.com/chat/completions").openConnection() as HttpURLConnection
    })
    private val client = DeepSeekContextClient(key, model, openConnection)
    fun cancel() = client.cancel()

    fun analyze(messages: List<ArchivedMessage>, base: RelationshipReport, profile: ConversationProfile? = null): RelationshipReport {
        val scoped = if (profile != null) messages else messages.filter { ConversationIdentity.sameTitle(it.group, base.title) }
        val input = AnalysisInput.deep(scoped, base, profile)
        val insight = client.analyze(input)
        val protected = input.group || base.label in setOf("群体互动线索", "边界表达需要尊重", "单侧互动候选") || base.messageCount < 6
        return base.copy(summary = if (protected) base.summary else insight.summary,
            source = "DeepSeek · 全量统计 + 原话语境",
            context = insight, limitations = (base.limitations + insight.limitations).distinct())
    }
}
