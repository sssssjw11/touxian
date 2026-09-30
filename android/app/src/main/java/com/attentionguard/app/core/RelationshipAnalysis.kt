package com.attentionguard.app.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

data class RelationshipMetric(val label: String, val value: String, val detail: String)
data class RelationshipEvidence(val messageId: Long, val quote: String, val side: String, val day: String?)
data class RelationshipFinding(val label: String, val detail: String, val evidence: List<RelationshipEvidence>)

data class RelationshipReport(
    val title: String,
    val summary: String,
    val label: String,
    val confidence: Int,
    val source: String = "本地多维分析",
    val messageCount: Int,
    val metrics: List<RelationshipMetric>,
    val findings: List<RelationshipFinding>,
    val limitations: List<String>,
    val suggestions: List<String>
) {
    fun toJson(): String = JSONObject().apply {
        put("title", title); put("summary", summary); put("label", label); put("confidence", confidence)
        put("source", source); put("messageCount", messageCount)
        put("metrics", JSONArray().apply { metrics.forEach { metric -> put(JSONObject().apply {
            put("label", metric.label); put("value", metric.value); put("detail", metric.detail)
        }) } })
        put("findings", JSONArray().apply { findings.forEach { finding -> put(JSONObject().apply {
            put("label", finding.label); put("detail", finding.detail)
            put("evidence", JSONArray().apply { finding.evidence.forEach { evidence -> put(JSONObject().apply {
                put("messageId", evidence.messageId); put("quote", evidence.quote); put("side", evidence.side)
                put("day", evidence.day ?: JSONObject.NULL)
            }) } })
        }) } })
        put("limitations", JSONArray(limitations)); put("suggestions", JSONArray(suggestions))
    }.toString()

    companion object {
        fun fromJson(raw: String): RelationshipReport {
            val json = JSONObject(raw)
            fun objects(array: JSONArray?): List<JSONObject> = if (array == null) emptyList() else
                (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            fun strings(key: String): List<String> = json.optJSONArray(key)?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
            }.orEmpty()
            return RelationshipReport(
                title = json.optString("title"), summary = json.optString("summary"), label = json.optString("label"),
                confidence = json.optInt("confidence").coerceIn(0, 100),
                source = json.optString("source", "本地多维分析"), messageCount = json.optInt("messageCount").coerceAtLeast(0),
                metrics = objects(json.optJSONArray("metrics")).map {
                    RelationshipMetric(it.optString("label"), it.optString("value"), it.optString("detail"))
                },
                findings = objects(json.optJSONArray("findings")).map { finding ->
                    RelationshipFinding(finding.optString("label"), finding.optString("detail"),
                        objects(finding.optJSONArray("evidence")).map { evidence -> RelationshipEvidence(
                            evidence.optLong("messageId"), evidence.optString("quote"), evidence.optString("side"),
                            if (evidence.isNull("day")) null else evidence.optString("day").takeIf(String::isNotBlank)
                        ) })
                }, limitations = strings("limitations"), suggestions = strings("suggestions")
            )
        }
    }
}

/** Full-sample statistics plus contextual windows, inspired by she-love-me's two analysis layers. */
object RelationshipAnalysis {
    private enum class Signal { AFFECTION, SUPPORT, DISCLOSURE, PLAN, REPAIR, CONFLICT, BOUNDARY, POSITIVE, NEGATIVE }
    private data class Read(val row: ArchivedMessage, val text: String, val signals: Set<Signal>, val day: LocalDate?)
    private val quote = Regex("“[^”]*”|「[^」]*」|『[^』]*』|\"[^\"]*\"|‘[^’]*’")
    private val reported = Regex("(?:他说|她说|你说|对方说|有人说|朋友说|歌词|台词|引用|转发|举例)[^，,。！？!?\\n]*(?:[，,。！？!?\\n]|$)")
    private val negation = Regex("不|没|并非|别|无须|无需|假如|如果|要是|据说|听说")
    private val uncertain = Regex("(?:吗|么|是不是|是否|开玩笑|逗你|反话|才怪|呵呵|[？?])")
    private val sarcasm = Regex("开玩笑|逗你|反话|才怪|呵呵")
    private val nonPersonalObject = Regex("^(?:们|的|这个|这份|这次|这套|这张|这种|这段|这样|把|能|会|帮|明天|今天|后天|负责|处理|提交|发给|同学|朋友|同事|室友|家人|姐姐|妹妹|哥哥|弟弟|妈妈|爸爸)")
    private val explicitBoundary = Regex("不喜欢你|不爱你|不想.{0,3}见你|别再联系|不要再联系|只是朋友|只想做朋友|不要越界|需要独处|先冷静|需要空间")
    private val concreteTime = Regex("明天|后天|今晚|下周|周[一二三四五六日天]|星期[一二三四五六日天]|[0-9]{1,2}月|[0-9]{1,2}[号日]|[0-9]{1,2}[:：][0-9]{2}")
    private val patterns = mapOf(
        Signal.AFFECTION to Regex("喜欢你|爱你|想你|想念你|想见你|舍不得你|在乎你|想和你在一起"),
        Signal.SUPPORT to Regex("辛苦了|注意休息|别太累|照顾好自己|我会陪你|我陪你|支持你|站在你这边|我理解你|抱抱|担心你|还好吗|我可以帮你|愿意帮你"),
        Signal.DISCLOSURE to Regex("我.{0,4}(?:难过|害怕|焦虑|委屈|压力|失望|伤心|难受|孤独|不开心)|今天.{0,3}(?:很难过|很难受)"),
        Signal.PLAN to Regex("(?:我们|咱们|和你|陪你|一起)[^。！？\\n]{0,24}(?:见面|吃饭|散步|旅行|看电影|见家长|结婚|生日|住在一起)"),
        Signal.REPAIR to Regex("对不起|我错了|抱歉|是我.{0,3}不对|好好聊|我们谈谈|重新开始|谢谢你理解"),
        Signal.CONFLICT to Regex("你根本|你从来|闭嘴|滚开|别烦我|你有病|你骗我|我讨厌你|不想理你|你总是|凭什么|你不在乎我"),
        Signal.BOUNDARY to explicitBoundary,
        Signal.POSITIVE to Regex("开心|高兴|太好了|期待|谢谢|感谢|恭喜|哈哈|\\[(?:愉快|鼓掌|爱心|拥抱)\\]|😊|😄|🥰"),
        Signal.NEGATIVE to Regex("生气|难过|委屈|害怕|焦虑|失望|伤心|难受|孤独|不开心|\\[(?:发怒|愤怒|抓狂|大哭|流泪|难过|委屈|心碎)\\]|😠|😡|😭|💔")
    )

    fun analyze(messages: List<ArchivedMessage>, title: String): RelationshipReport {
        val scoped = messages.filter { title.isBlank() || ConversationIdentity.sameTitle(it.group, title) }
        val groups = scoped.map { it.group }.distinct()
        if (!sameConversation(groups)) return emptyReport(title, "多个会话需要分开分析", "选择一个会话后再分析，避免把不同对象的消息合并。")
        val rows = eligible(scoped)
        if (rows.isEmpty()) return emptyReport(title, "日常互动候选", "当前范围没有可分析的双方文字或具名表情记录。")
        val reads = rows.map(::read)
        val others = reads.filter { it.row.message.side == "other" }
        val mine = reads.filter { it.row.message.side == "me" }
        val names = others.mapNotNull { it.row.message.sender?.trim()?.takeIf { name ->
            name.isNotBlank() && name !in setOf("对方", "未知", "other", "them")
        } }.distinct()
        val group = names.size > 1
        val otherName = if (group) "群成员" else "对方"
        val distinctive = reads.distinctBy { Triple(it.row.message.side, it.row.message.sender, it.text) }
        fun count(side: List<Read>, signal: Signal) = side.filter { signal in it.signals }
            .distinctBy { Triple(it.row.message.side, it.row.message.sender, it.text) }.size
        val meAffection = count(mine, Signal.AFFECTION); val otherAffection = count(others, Signal.AFFECTION)
        val meSupport = count(mine, Signal.SUPPORT); val otherSupport = count(others, Signal.SUPPORT)
        val boundaries = count(reads, Signal.BOUNDARY); val conflicts = count(reads, Signal.CONFLICT)
        val repairs = count(reads, Signal.REPAIR)
        val reciprocal = mine.isNotEmpty() && others.isNotEmpty()
        val balance = (200.0 * minOf(mine.size, others.size) / rows.size).roundToInt()
        val signalTypes = distinctive.flatMap { it.signals }.distinct().size
        val bilateralAffection = meAffection > 0 && otherAffection > 0
        val bilateralSupport = meSupport > 0 && otherSupport > 0
        val relationScore = (minOf(meAffection, 3) * 6 + minOf(otherAffection, 3) * 6 +
            minOf(meSupport, 3) * 4 + minOf(otherSupport, 3) * 4 +
            minOf(count(reads, Signal.DISCLOSURE), 3) * 3 + minOf(count(reads, Signal.PLAN), 3) * 4 +
            if (bilateralAffection) 10 else 0).coerceIn(0, 100)
        val knownDays = reads.mapNotNull { it.day }.distinct()
        var confidence = (18 + minOf(distinctive.size, 40) + minOf(signalTypes, 5) * 4 +
            (if (reciprocal) 10 else 0) + minOf(knownDays.size, 8)).coerceIn(12, 89)
        if (rows.size < 6) confidence = minOf(confidence, 40)
        if (!reciprocal) confidence = minOf(confidence, 38)
        if (reads.any { it.day == null }) confidence = minOf(confidence, 70)
        if (!group && names.isEmpty()) confidence = minOf(confidence, 65)
        if (distinctive.size < rows.size / 3) confidence = minOf(confidence, 42)
        if (signalTypes == 0) confidence = minOf(confidence, 45)
        val label = when {
            group -> "群体互动线索"
            boundaries > 0 -> "边界表达需要尊重"
            conflicts > repairs && conflicts >= 2 -> "紧张互动候选"
            bilateralAffection -> "双向亲近候选"
            bilateralSupport -> "相互支持候选"
            !reciprocal -> "单侧互动候选"
            rows.size >= 20 && balance <= 40 -> "互动分布偏一侧"
            relationScore >= 18 -> "亲近表达候选"
            else -> "日常互动候选"
        }
        val metrics = mutableListOf(
            RelationshipMetric("消息分布", "我方 ${mine.size} · $otherName ${others.size}", "仅统计已保存的记录；消息数量不代表喜欢或付出程度。"),
            RelationshipMetric("互动对等", if (group) "群聊不作双方评分" else "$balance / 100", "按双方消息份额计算均衡程度，不评判谁更在乎。"),
            RelationshipMetric("关系程度线索", if (group) "不作单人关系评分" else "$relationScore / 100", "亲近表达、具体共同安排、支持与倾诉的组合线索；不是恋爱概率，明确边界优先。"),
            RelationshipMetric("支持与修复", "支持 ${meSupport + otherSupport} · 修复表达 $repairs", "按不同原话统计；道歉或支持不等于已解决冲突。"),
            RelationshipMetric("记录日期", if (knownDays.isEmpty()) "日期未确认" else "${knownDays.minOrNull()} 至 ${knownDays.maxOrNull()}", "可确认日期 ${reads.count { it.day != null }} / ${rows.size} 条；采集时间不等于消息时间。")
        )
        metrics.add(intervalMetric(reads, group))
        val findings = mutableListOf<RelationshipFinding>()
        fun addFinding(labelText: String, detail: String, selected: List<Read>) {
            if (selected.isNotEmpty()) findings.add(RelationshipFinding(labelText, detail,
                selected.distinctBy { Triple(it.row.message.side, it.row.message.sender, it.text) }.take(4).map(::evidence)))
        }
        addFinding("亲近与具体安排", if (group) "以下表达来自不同群成员，不能拼成一个人的态度。" else
            "我方亲近表达 $meAffection 条，$otherName $otherAffection 条。是否为恋爱关系还需结合实际关系和明确表达。",
            reads.filter { Signal.AFFECTION in it.signals || Signal.PLAN in it.signals })
        addFinding("支持与倾诉", "我方支持 $meSupport 条，${otherName}支持 $otherSupport 条；仅陈述可见关切，避免由短回复推断冷淡。",
            reads.filter { Signal.SUPPORT in it.signals || Signal.DISCLOSURE in it.signals })
        addFinding("边界与分歧", "发现 $boundaries 条边界表达、$conflicts 条分歧表达。边界按字面尊重，不反向解释为隐藏的喜欢。",
            reads.filter { Signal.BOUNDARY in it.signals || Signal.CONFLICT in it.signals })
        addFinding("修复尝试", "发现 $repairs 条修复表达。${repairDetail(reads)}", reads.filter { Signal.REPAIR in it.signals })
        addFinding("我方情绪表达", emotionDetail(mine), mine.filter { Signal.NEGATIVE in it.signals || Signal.POSITIVE in it.signals })
        addFinding("${otherName}情绪表达", emotionDetail(others), others.filter { Signal.NEGATIVE in it.signals || Signal.POSITIVE in it.signals })
        trend(reads)?.let { findings.add(it) }
        val limitations = buildList {
            add("只覆盖已保存的可见记录，未采集的消息和线下互动不在结论内。")
            add("分数是可核对的文本线索，不是被爱概率、人格或心理诊断。")
            if (scoped.size != messages.size) add("已排除其他会话的 ${messages.size - scoped.size} 条记录。")
            if (rows.size < 20) add("样本较短，当前结论是低把握度候选。")
            if (!reciprocal) add("记录只覆盖一侧，无法判断双向投入。")
            if (group) add("检测到 ${names.size} 位不同群成员，仅分析群互动，不能判断单人的关系程度。")
            else if (names.isEmpty()) add("对方姓名未确认，双方划分只依据气泡方向。")
            if (reads.any { it.day == null }) add("部分记录日期未知，未用采集顺序推断升温、冷淡或修复过程。")
            if (!completeExactTime(reads)) add("缺少完整精确消息时间，不计算回复速度、沉默时间或主动发起次数。")
            if (distinctive.size < reads.size) add("相同表达不会反复增加关系线索；数量统计保留不同消息记录。")
        }
        val summary = if (group) "已分析 ${rows.size} 条群互动记录。不同成员的支持、情绪和分歧分别作为证据，不合并为一个对方。" else
            "已分析 ${rows.size} 条记录，当前更接近「$label」。" + when {
                boundaries > 0 -> "出现明确边界时，应优先按原话理解，即使同时存在关切或亲近表达。"
                bilateralAffection -> "双方均有直接亲近表达；结论来自表达组合，仍需核对语境和现实关系。"
                bilateralSupport -> "双方均有可见关切，能够支持互相帮助的候选判断。"
                !reciprocal -> "当前缺少另一侧记录，结论仅描述这一段可见表达。"
                else -> "目前主要依据消息分布和可见表达，不能单凭聊天量判断喜欢程度。"
            }
        return RelationshipReport(title, summary, label, confidence, messageCount = rows.size, metrics = metrics,
            findings = findings, limitations = limitations, suggestions = buildList {
                if (boundaries > 0) add("先尊重对方明确表达的边界；有疑问时直接确认，不把拒绝当作试探。")
                if (conflicts > 0) add("围绕具体分歧确认各自需要，观察后续是否真正回应修复表达。")
                if (!reciprocal || rows.size < 20) add("补充同一会话双方的连续上下文，再比较关系线索。")
                add("打开原话证据核对引用、玩笑与真实经历，再作决定。")
            })
    }

    /** Keep source IDs and context; never turn capture order into a fictional conversation timeline. */
    fun keyWindows(messages: List<ArchivedMessage>, limit: Int = 90): List<ArchivedMessage> {
        if (limit <= 0 || !sameConversation(messages.map { it.group }.distinct())) return emptyList()
        val reads = eligible(messages).map(::read)
        if (reads.size <= limit) return chronological(reads).map { it.row }
        val ordered = chronological(reads)
        val selected = linkedSetOf<Int>()
        fun add(indices: Iterable<Int>, cap: Int) { indices.take(cap.coerceAtLeast(0)).forEach { selected.add(it) } }
        val quarter = (limit / 4).coerceAtLeast(1)
        add(ordered.indices.take(quarter), quarter)
        add(ordered.indices.toList().takeLast(quarter), quarter)
        val important = ordered.indices.sortedByDescending { index ->
            ordered[index].signals.fold(0) { total, signal ->
                total + if (signal in setOf(Signal.CONFLICT, Signal.BOUNDARY, Signal.REPAIR)) 4 else 1
            }
        }.filter { ordered[it].signals.isNotEmpty() }
        add(important.flatMap { i -> ((i - 2).coerceAtLeast(0)..(i + 2).coerceAtMost(ordered.lastIndex)).toList() }, quarter * 2)
        if (selected.size < limit) {
            val stride = ordered.size.toDouble() / (limit - selected.size).coerceAtLeast(1)
            add((0 until (limit - selected.size)).map { (it * stride).toInt().coerceAtMost(ordered.lastIndex) }, limit - selected.size)
        }
        if (selected.size < limit) add(ordered.indices.filter { it !in selected }, limit - selected.size)
        return selected.take(limit).sorted().map { ordered[it].row }
    }

    private fun emptyReport(title: String, label: String, summary: String) = RelationshipReport(title, summary, label, 12,
        messageCount = 0, metrics = emptyList(), findings = emptyList(),
        limitations = listOf("当前仅提供低把握度候选，尚无可分析的单会话双方记录。"),
        suggestions = listOf("选择同一会话的一段连续文字记录。"))

    private fun eligible(messages: List<ArchivedMessage>): List<ArchivedMessage> = messages
        .filter { it.message.side in setOf("me", "other") && it.message.type in setOf(MessageType.TEXT, MessageType.STICKER) && it.message.text.isNotBlank() }
        .distinctBy { it.id }

    private fun sameConversation(titles: List<String>): Boolean = titles.size <= 1 ||
        titles.all { ConversationIdentity.sameTitle(titles.first(), it) }

    private fun read(row: ArchivedMessage): Read {
        val text = row.message.text.lineSequence().filterNot { it.trimStart().startsWith(">") }.joinToString("\n")
            .replace(quote, " ").replace(reported, " ").trim()
        val signals = patterns.filter { (signal, pattern) -> pattern.findAll(text).any { match ->
            val prefix = text.substring((match.range.first - 7).coerceAtLeast(0), match.range.first)
                .substringAfterLast('，').substringAfterLast(',').substringAfterLast('。').substringAfterLast('\n')
            val suffix = text.substring(match.range.last + 1, minOf(text.length, match.range.last + 9))
                .substringBefore('，').substringBefore(',').substringBefore('。').substringBefore('\n')
            val negated = negation.containsMatchIn(prefix)
            val questioned = uncertain.containsMatchIn(prefix + suffix) || sarcasm.containsMatchIn(text)
            val deniedDisclosure = signal == Signal.DISCLOSURE && Regex("我.{0,2}(?:不|没|并非|不是|没有)").containsMatchIn(match.value)
            val deniedPlan = signal == Signal.PLAN && negation.containsMatchIn(match.value)
            val personalPhrase = signal == Signal.AFFECTION || (signal == Signal.BOUNDARY &&
                Regex("(?:不喜欢|不爱|不想.{0,3}见)你$").containsMatchIn(match.value))
            val differentObject = personalPhrase && nonPersonalObject.containsMatchIn(suffix)
            val explicitJoke = signal in setOf(Signal.CONFLICT, Signal.DISCLOSURE, Signal.NEGATIVE) &&
                Regex("开玩笑|逗你|反话|才怪").containsMatchIn(text)
            !negated && !deniedDisclosure && !deniedPlan && !differentObject && !explicitJoke &&
                !(questioned && signal in setOf(Signal.AFFECTION, Signal.PLAN, Signal.POSITIVE)) &&
                !(signal == Signal.PLAN && !concreteTime.containsMatchIn(text))
        } }.keys
        return Read(row, text, signals, day(row.message))
    }

    private fun exactTime(message: Msg): Long? = message.timestamp?.takeIf { it in 946684800000L..4102444800000L }
    private fun day(message: Msg): LocalDate? {
        val stated = message.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val exact = exactTime(message)?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
        return if (stated != null && exact != null && stated != exact) null else stated ?: exact
    }
    private fun completeExactTime(reads: List<Read>) = reads.isNotEmpty() && reads.all { exactTime(it.row.message) != null && it.day != null }
    private fun chronological(reads: List<Read>): List<Read> = when {
        completeExactTime(reads) -> reads.sortedBy { exactTime(it.row.message) }
        reads.isNotEmpty() && reads.all { it.day != null } -> reads.sortedBy { it.day }
        else -> reads
    }
    private fun evidence(read: Read) = RelationshipEvidence(read.row.id, read.row.message.text.take(600),
        read.row.message.side, read.day?.toString())

    private fun intervalMetric(reads: List<Read>, group: Boolean): RelationshipMetric {
        if (group || !completeExactTime(reads)) return RelationshipMetric("异方消息间隔", "未计算",
            if (group) "群成员不同，不能合并计算一个对方的回复间隔。" else "需要完整精确消息时间；屏幕时间标签和采集时间不能替代。")
        val intervals = chronological(reads).zipWithNext().filter { (a, b) -> a.row.message.side != b.row.message.side }
            .mapNotNull { (a, b) -> (exactTime(b.row.message)!! - exactTime(a.row.message)!!).takeIf { it in 1L..21_600_000L } }
            .sorted()
        return RelationshipMetric("异方消息间隔", if (intervals.isEmpty()) "未计算" else "中位 ${duration(intervals[intervals.size / 2])}",
            "仅 ${intervals.size} 个已记录相邻异方间隔；不是已读时长或实际回复速度，不推断冷淡。")
    }
    private fun duration(milliseconds: Long): String = when {
        milliseconds < 60_000 -> "${milliseconds / 1000} 秒"
        milliseconds < 3_600_000 -> "${milliseconds / 60_000} 分钟"
        else -> "${milliseconds / 3_600_000} 小时"
    }
    private fun emotionDetail(reads: List<Read>): String {
        val positive = reads.filter { Signal.POSITIVE in it.signals }.distinctBy { it.text }.size
        val negative = reads.filter { Signal.NEGATIVE in it.signals }.distinctBy { it.text }.size
        return "正向表达 $positive 条，负向表达 $negative 条；可同时存在，原话中的情绪不等于对你的态度。"
    }
    private fun repairDetail(reads: List<Read>): String {
        if (!completeExactTime(reads)) return "时间顺序未完整确认，不能断言修复发生在争执后或已经被接受。"
        val ordered = chronological(reads)
        var linked = 0
        var acknowledged = 0
        ordered.forEachIndexed { index, current ->
            if (Signal.REPAIR !in current.signals) return@forEachIndexed
            val earlier = ordered.subList((index - 6).coerceAtLeast(0), index).any {
                Signal.CONFLICT in it.signals && exactTime(current.row.message)!! - exactTime(it.row.message)!! <= 86_400_000
            }
            if (earlier) {
                linked++
                val later = ordered.subList(index + 1, (index + 5).coerceAtMost(ordered.size)).any {
                    it.row.message.side != current.row.message.side && (Signal.SUPPORT in it.signals || Signal.REPAIR in it.signals) &&
                        exactTime(it.row.message)!! - exactTime(current.row.message)!! <= 86_400_000
                }
                if (later) acknowledged++
            }
        }
        return "其中 $linked 条可与此前的分歧关联，$acknowledged 条后有另一方支持或修复表达；仍不能等同于冲突解决。"
    }
    private fun trend(reads: List<Read>): RelationshipFinding? {
        if (reads.size < 20 || reads.any { it.day == null }) return null
        val days = reads.mapNotNull { it.day }.distinct().sorted()
        if (days.size < 6) return null
        val middle = days[days.size / 2]
        val earlier = reads.filter { it.day!! < middle }
        val later = reads.filter { it.day!! >= middle }
        if (minOf(earlier.size, later.size) < 6) return null
        fun ratio(part: List<Read>) = part.count { Signal.SUPPORT in it.signals || Signal.AFFECTION in it.signals }.toDouble() / part.size
        val delta = ratio(later) - ratio(earlier)
        val direction = if (abs(delta) < .12) "相近" else if (delta > 0) "增加" else "减少"
        return RelationshipFinding("已记录范围的变化", "以 $middle 分段，后段亲近与支持表达占比$direction；采集覆盖可能不同，不等同于关系升温或降温。",
            listOfNotNull(earlier.firstOrNull(), later.firstOrNull()).map(::evidence))
    }
}
