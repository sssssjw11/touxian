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
}
