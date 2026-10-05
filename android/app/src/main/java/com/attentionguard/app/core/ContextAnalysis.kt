package com.attentionguard.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

enum class AnalysisScene(val label: String, val guidance: String) {
    GENERAL("通用", "围绕明确请求、情绪、边界和澄清问题理解语境。"),
    FRIEND("朋友", "关注支持、倾诉、相处边界，不把友好直接解释成恋爱。"),
    WORK("工作", "关注任务、分工、期限和表达方式，不把事务性简短回复解释成冷淡。"),
    INTIMATE("亲密", "关注明确的亲近、需要、边界和修复；关系状态须有原话依据。");
    companion object {
        fun read(value: String?) = entries.firstOrNull { it.name == value } ?: GENERAL
    }
}

enum class ProfileKind(val label: String) { UNKNOWN("类型未确认"), PERSON("单人聊天"), GROUP("群聊") }
enum class InsightKind(val label: String) {
    FACT("明确表达"), INTENT("可能诉求"), PORTRAIT("沟通画像"), PATTERN("互动模式"), RISK("需要留意")
}
enum class EvidenceLevel(val label: String) { SUPPORTED("依据较充分"), LIMITED("依据有限"), INSUFFICIENT("依据不足") }

data class ContextEvidence(val ref: String, val quote: String, val side: String, val sender: String?, val day: String?) {
    fun json() = JSONObject().put("ref", ref).put("quote", quote).put("side", side)
        .put("sender", sender ?: JSONObject.NULL).put("day", day ?: JSONObject.NULL)
    companion object {
        fun read(json: JSONObject) = ContextEvidence(json.getString("ref"), json.getString("quote"), json.getString("side"),
            json.nullableString("sender"), json.nullableString("day"))
    }
}

data class ContextSection(
    val kind: InsightKind, val title: String, val detail: String, val level: EvidenceLevel,
    val evidence: List<ContextEvidence>, val alternatives: List<String> = emptyList()
) {
    fun json() = JSONObject().put("kind", kind.name).put("title", title).put("detail", detail).put("level", level.name)
        .put("evidence", JSONArray(evidence.map { it.json() })).put("alternatives", JSONArray(alternatives))
}

data class ReplySuggestion(val text: String, val timing: String, val evidence: List<ContextEvidence>) {
    fun json() = JSONObject().put("text", text).put("timing", timing).put("evidence", JSONArray(evidence.map { it.json() }))
}

data class ContextInsight(
    val scene: AnalysisScene, val summary: String, val sections: List<ContextSection>,
    val replies: List<ReplySuggestion>, val limitations: List<String>, val fingerprint: String,
    val source: String = "DeepSeek 深入理解", val version: Int = VERSION
) {
    fun json() = JSONObject().put("version", version).put("scene", scene.name).put("summary", summary)
        .put("sections", JSONArray(sections.map { it.json() })).put("replies", JSONArray(replies.map { it.json() }))
        .put("limitations", JSONArray(limitations)).put("fingerprint", fingerprint).put("source", source)
    companion object {
        const val VERSION = 2
        fun read(json: JSONObject): ContextInsight = ContextInsight(
            AnalysisScene.read(json.optString("scene")), json.getString("summary"),
            json.getJSONArray("sections").objects().map { section ->
                ContextSection(InsightKind.valueOf(section.getString("kind")), section.getString("title"),
                    section.getString("detail"), EvidenceLevel.valueOf(section.getString("level")),
                    section.getJSONArray("evidence").objects().map(ContextEvidence::read),
                    section.optJSONArray("alternatives").strings())
            },
            json.optJSONArray("replies").objects().map { reply ->
                ReplySuggestion(reply.getString("text"), reply.getString("timing"),
                    reply.getJSONArray("evidence").objects().map(ContextEvidence::read))
            }, json.optJSONArray("limitations").strings(), json.optString("fingerprint"),
            json.optString("source", "DeepSeek 深入理解"), json.optInt("version", VERSION))
    }
}

data class ContextMessage(
    val ref: String, val text: String, val side: String, val sender: String?, val day: String?,
    val messageTime: Long?, val recordingId: String? = null, val truncated: Boolean = false
) {
    fun json() = JSONObject().put("ref", ref).put("text", text).put("side", side)
        .put("sender", sender ?: JSONObject.NULL).put("day", day ?: JSONObject.NULL)
        .put("message_time", messageTime ?: JSONObject.NULL).put("recording_id", recordingId ?: JSONObject.NULL)
        .put("truncated", truncated)
}

data class AnalysisInput(
    val scene: AnalysisScene, val messages: List<ContextMessage>, val totalSelected: Int,
    val statistics: JSONObject?, val group: Boolean, val identityKnown: Boolean,
    val live: Boolean, val profileSummary: String = "", val profileKey: String = ""
) {
    val fingerprint: String get() = AnalysisFingerprint.text(payload().toString())
    val boundaries: List<ContextMessage> get() {
        val rows = messages.mapIndexed { i, row -> ArchivedMessage(i.toLong(), "", Msg(row.side, row.text, row.sender), 0) }
        return RelationshipAnalysis.explicitBoundaries(rows).map { messages[it.toInt()] }
    }
    fun payload() = JSONObject().put("analysis_version", ContextInsight.VERSION).put("scene", scene.name)
        .put("mode", if (live) "live" else "history").put("total_selected", totalSelected)
        .put("sample_count", messages.size).put("group", group).put("identity_known", identityKnown)
        .put("local_statistics", statistics ?: JSONObject.NULL).put("profile_background", profileSummary)
        .put("profile_version", profileKey).put("messages", JSONArray(messages.map { it.json() }))
        .put("explicit_boundary_refs", JSONArray(boundaries.map { it.ref }))
    companion object {
        const val LIVE_LIMIT = 20
        const val HISTORY_LIMIT = 90
        const val LIVE_CHAR_LIMIT = 16_000
        const val HISTORY_CHAR_LIMIT = 40_000
        fun live(snapshot: ChatSnapshot, scene: AnalysisScene, history: List<ArchivedMessage> = emptyList(),
                 profile: ConversationProfile? = null, summary: String = ""): AnalysisInput {
            val visible = snapshot.messages.filter { eligible(it) }.takeLast(LIVE_LIMIT)
            val current = visible.mapIndexed { i, m ->
                ContextMessage("live:$i", m.text, m.side, m.sender, m.date, m.timestamp)
            }
            val background = summary.take(1500)
            val historySamples = RelationshipAnalysis.keyWindows(history.filter { eligible(it.message) }, 6, allowLinked = true).map(::archived)
            val reserved = minOf(4000, historySamples.sumOf { minOf(it.text.length, 600) })
            val recent = fit(current, LIVE_CHAR_LIMIT - background.length - reserved, newestFirst = true)
            val remaining = LIVE_CHAR_LIMIT - background.length - recent.sumOf { it.text.length }
            val historical = fit(historySamples, remaining)
            val all = recent + historical
            val group = profile?.kind == ProfileKind.GROUP || isGroup(all)
            return AnalysisInput(scene, all, visible.size, null, group,
                !group && profile?.kind == ProfileKind.PERSON, true, background, profile?.cacheKey.orEmpty())
        }
        fun deep(messages: List<ArchivedMessage>, base: RelationshipReport, profile: ConversationProfile? = null): AnalysisInput {
            val eligible = messages.filter { eligible(it.message) }
            val samples = RelationshipAnalysis.keyWindows(eligible, HISTORY_LIMIT, allowLinked = profile != null)
            val bounded = fit(samples.map(::archived), HISTORY_CHAR_LIMIT)
            val group = profile?.kind == ProfileKind.GROUP || isGroup(eligible.map(::archived))
            val statistics = JSONObject(base.toJson()).apply { remove("findings"); remove("context") }
            return AnalysisInput(base.scene, bounded, base.messageCount, statistics, group,
                !group && profile?.kind == ProfileKind.PERSON, false, profileKey = profile?.cacheKey.orEmpty())
        }
        fun archived(row: ArchivedMessage) = ContextMessage("archive:${row.id}", row.message.text,
            row.message.side, row.message.sender, row.message.date, row.message.timestamp, row.recordingId)
        private fun eligible(message: Msg) = message.side in setOf("me", "other") &&
            message.type in setOf(MessageType.TEXT, MessageType.STICKER) &&
            message.captureMethod == "nodes" && message.text.isNotBlank()
        private fun isGroup(messages: List<ContextMessage>) = messages.filter { it.side == "other" }
            .mapNotNull { it.sender?.takeIf { name -> name.isNotBlank() && name !in setOf("对方", "未知", "other", "them") } }
            .distinct().size > 1
        /** Select whole source prefixes within the budget; the sent prefix is the validation boundary. */
        private fun fit(messages: List<ContextMessage>, budget: Int, newestFirst: Boolean = false): List<ContextMessage> {
            var remaining = budget.coerceAtLeast(0)
            var count = messages.size
            val selected = (if (newestFirst) messages.reversed() else messages).mapNotNull { message ->
                val allowance = minOf(2000, remaining / count.coerceAtLeast(1))
                count--
                if (allowance == 0) null else message.copy(text = message.text.take(allowance),
                    truncated = message.text.length > allowance).also {
                    remaining -= it.text.length
                }
            }
            return if (newestFirst) selected.reversed() else selected
        }
    }
}

object LocalContextAnalysis {
    fun fromReport(report: RelationshipReport, profile: ConversationProfile? = null): ContextInsight {
        val sections = report.findings.map { finding ->
            ContextSection(if (finding.label == "边界与分歧") InsightKind.RISK else InsightKind.FACT,
                finding.label, finding.detail, EvidenceLevel.LIMITED, finding.evidence.map {
                    ContextEvidence("archive:${it.messageId}", it.quote, it.side, it.sender, it.day)
                })
        }.toMutableList()
        if (profile?.kind == ProfileKind.PERSON && report.label != "群体互动线索") {
            report.findings.firstOrNull { it.label == "支持与倾诉" }?.let {
                sections.add(ContextSection(InsightKind.PORTRAIT, "当前记录中的支持方式", it.detail,
                    EvidenceLevel.LIMITED, it.evidence.map { proof ->
                        ContextEvidence("archive:${proof.messageId}", proof.quote, proof.side, proof.sender, proof.day)
                    }, listOf("这只描述当前记录，不代表稳定人格；可在深化时结合上下文核对。")))
            }
        }
        return ContextInsight(report.scene, report.summary, sections, emptyList(), report.limitations, "",
            source = "本地原话与统计")
    }
}

object AnalysisFingerprint {
    fun text(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    fun messages(rows: List<ArchivedMessage>): String = text(JSONArray(rows.map { row ->
        AnalysisInput.archived(row).json().put("kind", row.message.type.name)
            .put("method", row.message.captureMethod).put("time_label", row.message.timeLabel ?: JSONObject.NULL)
            .put("conversation", row.group)
    }).toString())
}

internal fun JSONObject.nullableString(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
