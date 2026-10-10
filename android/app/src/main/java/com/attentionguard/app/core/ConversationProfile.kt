package com.attentionguard.app.core

data class ConversationProfile(
    val id: String, val name: String, val scene: AnalysisScene = AnalysisScene.GENERAL,
    val kind: ProfileKind = ProfileKind.UNKNOWN, val createdAt: Long = System.currentTimeMillis(),
    val revision: Long = 0, val analysis: String? = null, val analysisFingerprint: String? = null
) {
    val cacheKey: String get() = "$id:$revision:${scene.name}:${kind.name}:${ContextInsight.VERSION}"
}

data class ProfileSnapshot(val profile: ConversationProfile, val recordings: List<ChatRecording>, val messages: List<ArchivedMessage>) {
    val fingerprint: String get() = AnalysisFingerprint.text(profile.cacheKey + AnalysisFingerprint.messages(messages))
    private fun report(key: String): RelationshipReport? {
        if (profile.analysisFingerprint != fingerprint) return null
        return profile.analysis?.let { raw -> runCatching {
            val json = org.json.JSONObject(raw)
            if (!json.has("localReport")) {
                if (key == "localReport") RelationshipReport.fromJson(raw) else null
            } else if (json.optInt("version") != ContextInsight.VERSION) null
            else json.optJSONObject(key)?.let { RelationshipReport.fromJson(it.toString()) }
        }.getOrNull() }
    }
    val freshLocalReport: RelationshipReport? get() = report("localReport")
    val freshCloudReport: RelationshipReport? get() = report("cloudReport")
    val freshReport: RelationshipReport? get() = freshCloudReport ?: freshLocalReport
    private val stored: org.json.JSONObject? get() = profile.analysis?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
    val lastAnalyzedAt: Long? get() = stored?.optLong("analyzedAt")?.takeIf { it > 0 }
    val lastMessageCount: Int? get() = stored?.optInt("sourceMessageCount", -1)?.takeIf { it >= 0 }
    /** A stale summary is for explicit review only; it never enters a new analysis input. */
    val previousSummary: String? get() = stored?.let { json ->
        val report = json.optJSONObject("cloudReport") ?: json.optJSONObject("localReport") ?: json
        report.optString("summary").takeIf { it.isNotBlank() }
    }
    val updateLabel: String get() = when {
        freshReport != null -> "画像已更新"
        profile.analysis == null -> "尚未生成画像"
        stored?.optInt("version", 1) != ContextInsight.VERSION -> "分析方式已升级 · 画像待更新"
        lastMessageCount != null && messages.size > requireNotNull(lastMessageCount) ->
            "新增 ${messages.size - requireNotNull(lastMessageCount)} 条消息 · 画像待更新"
        else -> "原文、关联或场景有变化 · 画像待更新"
    }
}
