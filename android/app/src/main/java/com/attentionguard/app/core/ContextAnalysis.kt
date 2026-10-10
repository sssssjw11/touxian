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
        const val VERSION = 3
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
    val messageTime: Long?, val recordingId: String? = null, val truncated: Boolean = false,
    val captureMethod: String = "nodes"
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
        fun manual(text: String, scene: AnalysisScene): AnalysisInput {
            val input = live(ChatSnapshot(null, JevIntentEngine.customMessages(text)), scene)
            return input.copy(messages = input.messages.map { it.copy(ref = it.ref.replace("live:", "manual:")) })
        }
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
            row.message.side, row.message.sender, row.message.date, row.message.timestamp, row.recordingId,
            captureMethod = row.message.captureMethod)
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
    /** Local candidates are deliberately narrow. They describe expressions, never hidden motives. */
    fun live(input: AnalysisInput): ContextInsight {
        val visible = input.messages.filter { !it.ref.startsWith("archive:") }
        val latest = visible.lastOrNull { it.side == "other" }
        val sections = mutableListOf<ContextSection>()
        val replies = mutableListOf<ReplySuggestion>()
        val limitations = mutableListOf("本地按可见原话提供候选解释；可点按 DeepSeek 结合更多语境核对。")
        fun proof(row: ContextMessage) = ContextEvidence(row.ref, row.text.take(600), row.side, row.sender, row.day)
        if (latest == null) return ContextInsight(input.scene, "缺少对方原话，请补充双方上下文。",
            emptyList(), emptyList(), limitations, input.fingerprint, "本地语境")
        val evidence = listOf(proof(latest))
        sections += ContextSection(InsightKind.FACT, "对方当前表达", latest.text,
            EvidenceLevel.SUPPORTED, evidence)
        val boundary = input.boundaries.firstOrNull { it.ref == latest.ref }
        val direct = !NoticeRules.isReported(latest.text) &&
            !Regex("开玩笑|逗你|反话|才怪|^[“「\"『]").containsMatchIn(latest.text)
        val prior = visible.takeWhile { it.ref != latest.ref }.lastOrNull { it.side == "me" }
        val busy = prior?.takeIf { Regex("在忙|忙着|开会|赶.{0,4}(稿|材料|报告)|稍后|晚点|没空|现在不方便").containsMatchIn(it.text) }
        when {
            boundary != null -> {
                sections += ContextSection(InsightKind.INTENT, "明确的边界应优先尊重",
                    "对方直接表达了限制或拒绝。按原话执行；不要把边界反解成试探。",
                    EvidenceLevel.SUPPORTED, evidence)
                replies += ReplySuggestion("我知道了，会尊重你的意思。", "对方明确拒绝或要求空间时", evidence)
            }
            direct && Regex("算了[，, ]*你忙吧|你忙吧|先这样|以后再聊|改天再说").containsMatchIn(latest.text) -> {
                val basis = evidence + listOfNotNull(busy?.let(::proof))
                sections += ContextSection(InsightKind.INTENT, if (busy != null) "可能是在给忙碌留空间" else "可能是在结束当前话题",
                    if (busy != null) "前文已经说明忙碌，这句话可以理解为暂缓交流。" else
                        "这句话在字面上收束了当前交流。缺少此前的请求和回应，不能确认是在体谅还是失落。",
                    EvidenceLevel.LIMITED, basis,
                    listOf("也可能仍有未回应的期待；需要核对前面的请求、解释与语气。"))
                val timing = when (input.scene) {
                    AnalysisScene.WORK -> "工作场景：确认是否还有待处理的事项"
                    AnalysisScene.FRIEND -> "朋友场景：不确定对方是否还需要倾听"
                    AnalysisScene.INTIMATE -> "亲密场景：不确定是否有未被回应的感受"
                    else -> "不确定对方是在结束话题还是仍有需要时"
                }
                replies += ReplySuggestion(when (input.scene) {
                    AnalysisScene.WORK -> "好的。还有需要我确认的事项吗？"
                    AnalysisScene.INTIMATE -> "我听到了。你是想先暂停，还是有希望我回应的事？"
                    else -> "好的。你是想先聊到这里，还是还有想说的？"
                }, timing, basis)
                if (busy == null) limitations += "缺少此前提出的请求，以及你对这件事的回应。"
            }
            direct && Regex("我.{0,4}(难过|委屈|焦虑|失望|害怕|难受)|今天.{0,3}(很难过|很难受)").containsMatchIn(latest.text) &&
                !Regex("我.{0,3}(不|没|并非|不是)").containsMatchIn(latest.text) -> {
                sections += ContextSection(InsightKind.INTENT, "可能需要先被听见",
                    "对方在描述自己的感受。可以先确认是否愿意继续讲，再决定是否提供建议。",
                    EvidenceLevel.LIMITED, evidence, listOf("也可能只是陈述近况，尚未提出求助。"))
                replies += ReplySuggestion("你愿意多说一点吗？你希望我先听你说，还是一起想办法？",
                    "对方直接描述不舒服的感受，且未拒绝继续交流时", evidence)
            }
            direct && NoticeRules.hasIntentAction(latest.text) && !NoticeRules.isCancelled(latest.text) -> {
                sections += ContextSection(InsightKind.INTENT, "可能需要确认行动安排",
                    "原话包含行动要求。先核对这项要求是否针对你，以及具体事项和期限。",
                    EvidenceLevel.LIMITED, evidence)
                replies += ReplySuggestion("我先确认一下：具体需要我处理哪一项，时间要求是什么？",
                    "任务范围或期限尚不清楚时", evidence)
            }
            else -> {
                sections += ContextSection(InsightKind.INTENT, "还需要核对所指",
                    "仅凭当前表达无法确认更深诉求。补充前文，或直接询问对方希望得到怎样的回应。",
                    EvidenceLevel.INSUFFICIENT, evidence)
            }
        }
        if (visible.none { it.side == "me" }) limitations += "目前只有对方消息，缺少你的回应。"
        if (latest.truncated) limitations += "当前原话只使用发送范围内的正文片段；可打开依据核对完整内容。"
        if (input.group) limitations += "群聊只描述对应发言者的这一段表达，不生成个人画像。"
        return ContextInsight(input.scene, sections.last().detail, sections, replies, limitations,
            input.fingerprint, "本地语境")
    }
    fun fromReport(report: RelationshipReport, profile: ConversationProfile? = null,
                   messages: List<ArchivedMessage> = emptyList()): ContextInsight {
        val sections = report.findings.map { finding ->
            ContextSection(when (finding.label) {
                "边界与分歧" -> InsightKind.RISK
                "支持与倾诉", "修复尝试" -> InsightKind.PATTERN
                else -> InsightKind.FACT
            },
                finding.label, finding.detail, EvidenceLevel.LIMITED, finding.evidence.map {
                    ContextEvidence("archive:${it.messageId}", it.quote, it.side, it.sender, it.day)
                })
        }.toMutableList()
        val direct = messages.filter { row -> row.message.captureMethod == "nodes" &&
            !NoticeRules.isReported(row.message.text) &&
            !Regex("开玩笑|逗你|反话|才怪|[“「『\"]|[？?]").containsMatchIn(row.message.text) }
        fun proof(row: ArchivedMessage) = ContextEvidence("archive:${row.id}", row.message.text.take(600),
            row.message.side, row.message.sender, row.message.date)
        val replies = mutableListOf<ReplySuggestion>()
        if (profile?.kind == ProfileKind.PERSON && report.label != "群体互动线索") {
            report.findings.firstOrNull { it.label == "支持与倾诉" }?.let {
                val verified = it.evidence.filter { proof -> direct.any { row -> row.id == proof.messageId } }
                if (verified.isNotEmpty()) sections.add(ContextSection(InsightKind.PORTRAIT, "当前记录中的支持方式",
                    "这些可核对原话中出现了支持或倾诉表达，只描述本次记录中的交流方式。",
                    EvidenceLevel.LIMITED, verified.map { proof ->
                        ContextEvidence("archive:${proof.messageId}", proof.quote, proof.side, proof.sender, proof.day)
                    }, listOf("这只描述当前记录，不代表稳定人格；可在深化时结合上下文核对。")))
            }
            val preferences = direct.filter { it.message.side == "other" &&
                Regex("我(?:希望|更希望|需要|习惯|喜欢)|请你|不用急着|先听我|别打断|想先|需要独处|需要空间").containsMatchIn(it.message.text) &&
                Regex("直接说|先听|建议|文字|语音|提前|消息|聊|打断|告诉|解释|确认|沟通|独处|空间").containsMatchIn(it.message.text) }
                .distinctBy { it.message.text }.takeLast(4)
            if (preferences.isNotEmpty()) {
                sections += ContextSection(InsightKind.PORTRAIT, "对方明确表达过的沟通需要",
                    "这段记录中，对方曾直接说明希望怎样交流。可在类似情境再次确认，不将一次表达推广为稳定性格。",
                    EvidenceLevel.LIMITED, preferences.map(::proof),
                    listOf("需要可能随情境变化；跨记录的相似表达不代表持续的关系状态。"))
                val listening = preferences.lastOrNull { Regex("先听|不用急着.*建议").containsMatchIn(it.message.text) }
                if (listening != null) replies += ReplySuggestion("好，我先听你说。你希望从哪里开始？",
                    "对方再次明确希望被倾听、暂不需要建议时", listOf(proof(listening)))
            }
        }
        val degrading = direct.filter { Regex("你(?:就是|真是|是个).{0,3}(?:废物|垃圾|没用)|你不.{1,12}就.{1,15}|不然我就").containsMatchIn(it.message.text) }
            .distinctBy { it.message.side to it.message.text }.takeLast(3)
        if (degrading.isNotEmpty()) sections += ContextSection(InsightKind.RISK, "原话中有贬低或附加后果的表达",
            "这些措辞值得核对具体要求、后果与双方边界。这里只指出表达，不推断说话者人格或动机；请打开前后文确认。",
            EvidenceLevel.LIMITED, degrading.map(::proof), listOf("需核对是否在讨论具体工作条件，以及是否存在未采集的补充说明。"))
        return ContextInsight(report.scene, report.summary, sections, replies, report.limitations, "",
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
