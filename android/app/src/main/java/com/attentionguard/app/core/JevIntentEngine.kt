package com.attentionguard.app.core

import kotlin.math.exp
import kotlin.math.roundToInt

enum class IntentImportance(val label: String) { HIGH("高"), MEDIUM("中"), LOW("低") }

data class AffectInsight(
    val label: String,
    val confidence: Int,
    val evidence: String,
    val valence: Int = 0,
    val intensity: Int = 0,
    val basis: String? = null
)

data class ConfidenceTrace(
    val prior: Int,
    val evidence: Int,
    val context: Int,
    val ambiguity: Int,
    val dataQuality: Int,
    val explanation: String
)

/** A local, explainable reading of a visible conversation. */
data class IntentInsight(
    val label: String,
    val nextStep: String,
    val evidence: String,
    val sender: String,
    val group: String,
    val capturedAt: Long,
    val eventId: String? = null,
    val sourceLabel: String = "微信当前会话 · 可见节点",
    val importance: IntentImportance = IntentImportance.LOW,
    val confidence: Int = 42,
    val contextEvidence: String? = null,
    val contextSummary: String? = null,
    val affect: AffectInsight? = null,
    val confidenceTrace: ConfidenceTrace? = null
)

object JevIntentEngine {
    private data class Turn(val side: String, val text: String, val sender: String? = null)
    private data class AffectCue(val kind: String, val phrase: String, val valence: Int, val intensity: Int,
                                val symbolic: Boolean = false)
    private data class WeightedAffectCue(val cue: AffectCue, val weight: Double, val fromCurrent: Boolean)
    private data class ConfidenceAssessment(val score: Int, val trace: ConfidenceTrace)
    private enum class Kind { SYSTEM, CLOSING, REASSURANCE, EMOTION, FOLLOW_UP, QUESTION, ACTION, INFORMATION, NONE }
    private val speaker = Regex("^(我|自己|me|对方|他|她|ta|them)\\s*[:：]\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val closing = Regex("没事了|不用了|解决了|取消|作废|先这样|改天再说")
    private val reassurance = Regex("在乎我|关心我|还爱|想我|是不是不想|是不是忘|还记得")
    private val emotion = Regex("烦死|气死|难过|委屈|受不了|崩溃|生气|吵架|误会|不开心|伤心|被欺负|欺负|难受|心酸|郁闷|无语|焦虑|害怕")
    private val systemNotice = Regex("邀请.{1,80}加入了群聊|加入了群聊|撤回了一条消息|修改群名为|移出了群聊")
    private val meetingAction = Regex("开.{0,4}(会|班会)|进(入)?会议|先进会议|参加(线上)?会议|不要迟到|准时|抓紧时间|不要忘记|会议号|腾讯会议|集合")
    private val action = Regex("(?<!邀|申)请(?!问|假|求|教|示)|麻烦|帮我|记得|务必|需要|提交|报名|回复|确认|发给|完成|到场|${meetingAction.pattern}")
    private val timePressure = Regex("今天|今晚|明天|截止|截至|之前|周[一二三四五六日天]|[0-9]{1,2}[:：][0-9]{2}|[0-9]{1,3}\\s*(分钟|小时|天)|[一二三四五六七八九十]+点")
    private val exactDeadline = Regex("截止|截至|[0-9]{1,2}[:：][0-9]{2}|[0-9]{1,2}点")
    private val addressee = Regex("大家|你们|你|您|@\\S+")
    private val deliverable = Regex("作业|报告|材料|表格|文件|名单|数据|方案|截图|链接|附件|会议|班会|会议号|腾讯会议|线上|任务|订单")
    private val question = Regex("为什么|怎么|如何|什么意思|是否|能否|请问|[？?]")
    private val spokenQuestion = Regex("(?:多久|多长时间|多少[个人份次]?|几点|几号|哪[里天]|什么时候)(?:呢|啊|呀|来着)?[。！!\\s]*$")
    private val durationStatement = Regex("(?:没(?:有)?|没过|没用|用不了|要不了|不管|无论)(?:多久|多长时间|多少)")
    private val questionOpening = Regex("^(请问|为什么|怎么|如何|能否|是否)")
    private val vagueReference = Regex("^(这|那|这个|那个|这样|那样|可以吗|行吗|好[的吧]?|嗯|哦)")
    private val relationCue = Regex("在乎我|关心我|还爱|想我|是不是不想|是不是忘|还记得|喜欢你|想见你")
    private val careCue = Regex("担心你|辛苦了|注意休息|别太累|还好吗|有没有事|陪你|照顾好|支持你|站你这边|我陪你|抱抱|心疼|安慰|相信你|别生气|不要生气|别难过|不要难过|别担心|不要担心|别怕|不是你的错")
    private val positiveCue = Regex("谢谢|感谢|开心|高兴|期待|太好了|哈哈|恭喜|(?<!不)喜欢|好棒|可爱|支持你|站你这边|相信你|抱抱|心疼|😊|🙂|😄|🥰")
    private val playfulCue = Regex(
        "笑死|乐死|臭乐乐|逗你|开玩笑|调侃|哈哈|呵呵|😂|🤣|😆|😹|😜|😝|" +
            "你个[^，。！？\\n]{0,8}(臭|傻|笨|憨|坏|鬼)|[^，。！？\\n]{0,4}(臭|傻|笨|憨|坏)鬼"
    )
    private val contemptCue = Regex("滚|恶心|垃圾|废物|脑残|神经病|去死|闭嘴|你有病|别烦我")
    private val negativeCue = Regex("不喜欢|(?<!没|有)生气|(?<!不|没)难过|委屈|失望|伤心|(?<!麻)烦|讨厌|受不了|误会|不开心|不想理|被欺负|欺负|被骂|被冷落|被忽视|难受|心酸|痛苦|郁闷|无语|无奈|害怕|焦虑|压力|压抑|可怜|哭|流泪|大哭|难堪")
    private val avoidanceCue = Regex("改天|有空再说|再说吧|以后再聊|随便|无所谓|先这样|不想聊|不方便")
    private val stickerNegativeCue = Regex("\\[表情:(呜哇|哭|流泪|大哭|委屈|难过|伤心|崩溃|心碎|裂开)")
    private val stickerPositiveCue = Regex("\\[表情:(哈哈|大笑|开心|鼓掌|庆祝|爱你|比心|赞)")
    private val stickerUnknownCue = Regex("\\[表情:(未命名贴纸|未知贴纸)\\]")
    // Native WeChat emoji arrive as bracketed TEXT, unlike named custom stickers.
    // Only explicit expressions are mapped; e.g. [微笑] remains context-dependent.
    private val emojiPatterns = listOf(
        Triple("负向", Regex("\\[(?:发怒|愤怒|抓狂|大哭|流泪|难过|委屈|心碎)\\]|😠|😡|🤬|😢|😭|💔"), -60 to 62),
        Triple("积极", Regex("\\[(?:愉快|鼓掌|爱心|拥抱)\\]|😄|😊|🥰|❤️?|👏"), 50 to 46),
        Triple("调侃", Regex("\\[(?:偷笑|调皮|呲牙)\\]|😂|🤣|😆|😹|😜|😝"), 0 to 48)
    )
    private val hedge = Regex("可能|也许|或许|有点|有些|似乎|大概|不一定|再看看")
    private val intensifier = Regex("特别|太|非常|真的|极其|好\\.\\.\\.|！！|!!")
    private val softener = Regex("有点|有些|稍微|嗯|哦|哈哈")
    private val negationBeforeCue = Regex("(?:不|没|没有|不会|并不|不是|并没有|不再|从不|别|不要|不用|无需)(?:很|太|那么|特别|非常|真的)?$")
    private val quotedSpeech = Regex("“[^”]*”|「[^」]*」|\"[^\"]*\"")
    private val thirdPersonReport = Regex("^(?:他|她|他们|她们|朋友|同事|同学)(?:说|觉得|感觉|很|特别|非常|真的)|(?:他|她)(?:说|问)[：:]")
    private val emotionInquiry = Regex("^(?:你|您|他|她).{0,12}(?:生气|难过|伤心|不开心|开心|害怕|焦虑).{0,4}(?:吗|么|\\?|？)$")
    private val affectPatterns = listOf(
        Triple("关系确认", relationCue, 18 to 82),
        Triple("关切", careCue, 62 to 68),
        Triple("攻击", contemptCue, -82 to 82),
        Triple("积极", Regex("${positiveCue.pattern}|${stickerPositiveCue.pattern}"), 52 to 46),
        Triple("调侃", playfulCue, 0 to 56),
        Triple("负向", Regex("${negativeCue.pattern}|${stickerNegativeCue.pattern}"), -62 to 68),
        Triple("回避", avoidanceCue, -46 to 60),
        Triple("贴纸", stickerUnknownCue, 0 to 34)
    )

    fun analyze(snapshot: ChatSnapshot): IntentInsight? {
        if (snapshot.sourcePackage != "com.tencent.mm") return null
        val messages = snapshot.messages.filter {
            it.type in setOf(MessageType.TEXT, MessageType.STICKER) &&
                it.text.isNotBlank() && it.captureMethod == "nodes"
        }
        val index = messages.indexOfLast { it.side == "other" }
        if (index < 0) return null
        val message = messages[index]
        return classify(message.text.trim(), message.sender?.takeIf { it.isNotBlank() } ?: "对方",
            snapshot.title.orEmpty(), snapshot.capturedAt, "微信当前聊天 · 可见消息",
            messages.take(index).map { Turn(it.side, it.text.trim(), it.sender) },
            messages.drop(index + 1).map { Turn(it.side, it.text.trim(), it.sender) })
    }

    /** Free text is user-supplied and is never represented as a captured WeChat snapshot. */
    fun analyzeCustom(input: String): IntentInsight? {
        val turns = mutableListOf<Turn>()
        input.takeLast(12_000).lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val match = speaker.matchEntire(line)
            if (match != null) {
                val side = if (match.groupValues[1].lowercase() in setOf("我", "自己", "me")) "me" else "other"
                turns += Turn(side, match.groupValues[2].trim())
            } else if (turns.isEmpty()) turns += Turn("other", line)
            else turns[turns.lastIndex] = turns.last().let { it.copy(text = listOf(it.text, line).filter(String::isNotBlank).joinToString("\n")) }
        }
        val latest = turns.lastOrNull { it.side == "other" && it.text.isNotBlank() } ?: return null
        val latestIndex = turns.indexOfLast { it.side == "other" && it.text.isNotBlank() }
        return classify(latest.text, "对方", "", System.currentTimeMillis(), "手动输入 · 本地规则",
            turns.take(latestIndex), turns.drop(latestIndex + 1))
    }

    private fun classify(text: String, sender: String, group: String, capturedAt: Long, sourceLabel: String,
                         before: List<Turn>, after: List<Turn>): IntentInsight {
        val activeBefore = before.drop(before.indexOfLast {
            sameCounterpart(it, sender) && (closing.containsMatchIn(it.text) || NoticeRules.isCompleted(it.text)) &&
                !NoticeRules.hasIntentAction(it.text) && !NoticeRules.isReported(it.text)
        } + 1)
        val priorRequestIndex = activeBefore.indexOfLast {
            sameCounterpart(it, sender) && NoticeRules.hasIntentAction(it.text) &&
                !closing.containsMatchIn(it.text) && !systemNotice.containsMatchIn(it.text)
        }
        val priorRequest = activeBefore.getOrNull(priorRequestIndex)
        val followUp = text.length <= 30 && priorRequest != null &&
            (isQuestion(text) || timePressure.containsMatchIn(text)) &&
            !action.containsMatchIn(text)
        val visible = before + Turn("other", text) + after
        val meetingContext = NoticeRules.meeting.containsMatchIn(text) &&
            activeBefore.any { sameCounterpart(it, sender) &&
                NoticeRules.meeting.containsMatchIn(it.text) && timePressure.containsMatchIn(it.text) }
        val contextualAction = NoticeRules.hasIntentAction(text) ||
            (meetingContext && meetingAction.containsMatchIn(text) && !NoticeRules.isCancelled(text) &&
                !NoticeRules.isCompleted(text) && !isQuestion(text))
        val directText = ownAffectText(text)
        val directEmotion = !emotionInquiry.matches(text) &&
            emotion.findAll(directText).any { !isNegated(directText, it.range.first) }
        val result = when {
            systemNotice.containsMatchIn(text) -> Result(Kind.SYSTEM, "微信系统提示", "无需立即处理；以实际聊天内容为准。", IntentImportance.LOW)
            ((closing.containsMatchIn(text) || NoticeRules.isCompleted(text)) && !NoticeRules.isReported(text) &&
                !NoticeRules.hasIntentAction(text) && !NoticeRules.isInformation(text)) ->
                Result(Kind.CLOSING, "话题可能已结束", "核对上下文，不自动关闭已有事项。", IntentImportance.LOW)
            reassurance.containsMatchIn(text) -> Result(Kind.REASSURANCE, "可能在确认关系或关注", "先回应对方关切，再决定是否解释具体问题。", IntentImportance.MEDIUM)
            directEmotion -> Result(Kind.EMOTION, "可能在表达情绪", "先确认对方感受，不急于给出结论。", IntentImportance.MEDIUM)
            followUp -> Result(Kind.FOLLOW_UP, "可能在跟进先前请求", "结合上文核对所指事项和期限，再决定是否处理。",
                if (timePressure.containsMatchIn(text) || timePressure.containsMatchIn(priorRequest?.text.orEmpty())) IntentImportance.HIGH else IntentImportance.MEDIUM)
            questionOpening.containsMatchIn(text) || isSpokenQuestion(text) -> Result(Kind.QUESTION, "可能在寻求解释", "先核对问题所指，再简明回答。", IntentImportance.MEDIUM)
            contextualAction -> Result(Kind.ACTION, "可能在提出行动请求", "核对对象和期限；明确与你有关时再处理。",
                if (timePressure.containsMatchIn(text) || meetingContext) IntentImportance.HIGH else IntentImportance.MEDIUM)
            isQuestion(text) -> Result(Kind.QUESTION, "可能在寻求解释", "先核对问题所指，再简明回答。", IntentImportance.MEDIUM)
            NoticeRules.isInformation(text) -> Result(Kind.INFORMATION, "可能在补充事项信息", "查看更新内容；没有明确要求时不必立即操作。", IntentImportance.MEDIUM)
            else -> Result(Kind.NONE, "暂无明确请求", "无需立即行动；继续观察上下文。", IntentImportance.LOW)
        }
        val nearby = listOfNotNull(priorRequest, before.lastOrNull(), after.lastOrNull()).distinct()
        val contextEvidence = nearby.take(3).takeIf { it.isNotEmpty() }?.joinToString("；") {
            val person = if (it.side == "me") "我" else if (sameCounterpart(it, sender)) "对方" else it.sender ?: "身份未确认成员"
            "$person：${it.text.take(48)}"
        }
        val signals = listOfNotNull(
            "行动".takeIf { visible.any { turn -> NoticeRules.hasIntentAction(turn.text) } || contextualAction },
            "时间".takeIf { visible.any { turn -> timePressure.containsMatchIn(turn.text) } },
            "情绪".takeIf { visible.any { turn -> emotion.containsMatchIn(turn.text) } },
            "问答".takeIf { visible.any { turn -> isQuestion(turn.text) } }
        )
        val summary = "${visible.size} 条可读文字" + if (signals.isEmpty()) "" else " · ${signals.joinToString("、") { "${it}线索" }}"
        val adjustedStep = if (after.any { it.side == "me" }) "${result.nextStep} 你已在其后回复，请核对是否仍需行动。" else result.nextStep
        val confidenceAssessment = confidenceFor(result, text, sender, activeBefore, after, priorRequestIndex)
        return IntentInsight(result.label, adjustedStep, text.take(120), sender, group, capturedAt, sourceLabel = sourceLabel,
            importance = result.importance, confidence = confidenceAssessment.score,
            contextEvidence = contextEvidence, contextSummary = summary,
            affect = affectFor(text, sender, before, after),
            confidenceTrace = confidenceAssessment.trace)
    }

    private fun affectFor(text: String, sender: String, before: List<Turn>, after: List<Turn>): AffectInsight {
        fun collect(value: String): List<AffectCue> {
            if (systemNotice.containsMatchIn(value)) return emptyList()
            if (emotionInquiry.matches(value)) return listOf(AffectCue("感受询问", value.take(48), 0, 35))
            val direct = ownAffectText(value)
            val emojiCues = emojiPatterns.flatMap { (kind, pattern, scores) ->
                pattern.findAll(direct).map {
                    AffectCue(kind, it.value, scores.first, scores.second, symbolic = true)
                }.toList()
            }.distinctBy { it.phrase }
            // Do not count the word inside [流泪] again as independent text evidence.
            val words = emojiPatterns.fold(direct) { remaining, (_, pattern, _) -> remaining.replace(pattern, " ") }
            val negativeRanges = negativeCue.findAll(words).filterNot { isNegated(words, it.range.first) }.map { it.range }.toList()
            val multiplier = when {
                intensifier.containsMatchIn(words) -> 1.2
                softener.containsMatchIn(words) -> 0.78
                else -> 1.0
            }
            return emojiCues + affectPatterns.flatMap { (kind, pattern, scores) ->
                pattern.findAll(words).filterNot {
                    kind !in setOf("关切", "贴纸") && (isNegated(words, it.range.first) ||
                        kind == "积极" && negativeRanges.any { range -> it.range.first in range })
                }.take(3).map {
                    AffectCue(
                        kind = kind,
                        phrase = it.value,
                        valence = (scores.first * multiplier).roundToInt().coerceIn(-100, 100),
                        intensity = (scores.second * multiplier).roundToInt().coerceIn(0, 100)
                    )
                }
            }
        }

        val current = collect(text)
        // The counterpart's visible turns are evidence about their tone. The user's
        // own replies remain context but do not get counted as the counterpart's emotion.
        val contextTurns = before.mapIndexed { index, turn ->
            turn to (before.size - index)
        }.filter { (turn, _) -> sameCounterpart(turn, sender) } +
            after.mapIndexed { index, turn ->
            turn to (index + 1)
        }.filter { (turn, _) -> sameCounterpart(turn, sender) }
        val context = contextTurns.flatMap { (turn, distance) ->
            collect(turn.text).map { cue ->
                WeightedAffectCue(cue, exp(-0.34 * distance), false)
            }
        }.filterNot { candidate -> current.any { it.kind == candidate.cue.kind && it.phrase == candidate.cue.phrase } }
            .sortedByDescending { it.weight }.distinctBy { it.cue.kind to it.cue.phrase }.take(8)
        val weighted = current.map { WeightedAffectCue(it, 1.0, true) } + context
        if (weighted.isEmpty()) {
            val neutralConfidence = (23 + text.count { it.isLetterOrDigit() }.coerceAtMost(18) / 3 +
                contextTurns.size.coerceAtMost(4) * 2).coerceIn(22, 42)
            return AffectInsight(
                label = "情绪不明显",
                confidence = neutralConfidence,
                evidence = "当前可见文字缺少明确情绪线索",
                basis = "未匹配到关切、积极、负向或回避词条"
            )
        }

        val totalWeight = weighted.sumOf { it.weight }.coerceAtLeast(0.01)
        val valence = (weighted.sumOf { it.cue.valence * it.weight } / totalWeight).roundToInt().coerceIn(-100, 100)
        val intensity = (weighted.sumOf { it.cue.intensity * it.weight } / totalWeight).roundToInt().coerceIn(0, 100)
        val kinds = weighted.filter { current.isEmpty() || it.fromCurrent || it.weight >= 0.25 }
            .map { it.cue.kind }.toSet()
        val hasPositive = "积极" in kinds || "关切" in kinds || "关系确认" in kinds
        val hasNegative = "攻击" in kinds || "负向" in kinds || "回避" in kinds
        val mixed = hasPositive && hasNegative
        val relation = "关系确认" in kinds
        val symbolicOnly = weighted.all { it.cue.symbolic }
        val hasSymbolic = weighted.any { it.cue.symbolic }
        val label = when {
            mixed -> "情绪线索混合"
            symbolicOnly && "负向" in kinds -> "负向表情线索"
            symbolicOnly && "调侃" in kinds -> "调侃表情线索"
            symbolicOnly -> "积极表情线索"
            relation -> "关系确认线索"
            "关切" in kinds -> "表达关切"
            "攻击" in kinds -> "攻击/蔑视倾向"
            "调侃" in kinds -> "调侃/玩笑倾向"
            "负向" in kinds -> "出现负向情绪"
            "回避" in kinds -> "出现回避或降温"
            "感受询问" in kinds -> "在询问感受"
            "贴纸" in kinds -> "贴纸情绪线索"
            else -> "积极亲近"
        }
        val directCount = current.distinctBy { it.kind }.size
        val contextWeight = weighted.filterNot { it.fromCurrent }.sumOf { it.weight }.coerceAtMost(1.5)
        val ambiguityPenalty = (if (mixed) 0.18 else 0.0) +
            (if (hedge.containsMatchIn(text)) 0.12 else 0.0) +
            (if (relation) 0.08 else 0.0) +
            (if ("调侃" in kinds) 0.08 else 0.0) +
            (if ("贴纸" in kinds) 0.16 else 0.0) +
            (if (symbolicOnly) 0.12 else 0.0)
        val verbalSupport = current.filterNot { it.symbolic }.distinctBy { it.phrase }.size
        val rawConfidence = 0.48 +
            directCount.coerceAtMost(3) * 0.10 +
            (verbalSupport - 1).coerceIn(0, 3) * 0.04 +
            contextWeight * 0.10 +
            (if ("调侃" in kinds) 0.06 else 0.0) +
            (if (intensity >= 70) 0.08 else 0.0) -
            ambiguityPenalty
        val confidence = (rawConfidence * 100).roundToInt().coerceIn(32, if (symbolicOnly) 58 else 86)
        val fromContext = current.isEmpty() || current.all { it.kind == "贴纸" } && context.any { it.cue.kind != "贴纸" }
        val terms = weighted.filter { !fromContext || it.cue.kind != "贴纸" }
            .sortedByDescending { it.weight * it.cue.intensity }
            .map { it.cue.phrase }.distinct().take(3).joinToString("、") { "“$it”" }
        val basis = buildString {
            append(if (fromContext) "当前消息未命中，引用上文衰减线索" else "当前消息直接命中 $directCount 类线索")
            if (mixed) append("；正负信号并存")
            if ("攻击" in kinds) append("；出现攻击或蔑视词")
            if ("调侃" in kinds) append("；调侃/玩笑语气，方向不完全确定")
            if ("贴纸" in kinds) append("；贴纸未提供可读语义，方向待确认")
            if (hasSymbolic) append("；表情含义不等于真实感受，需结合语境")
            append("；情绪强度 $intensity/100")
        }
        return AffectInsight(
            label = if (fromContext) "上文：$label" else label,
            confidence = confidence,
            evidence = if (fromContext) "整屏上文出现$terms" else "当前消息出现$terms",
            valence = valence,
            intensity = intensity,
            basis = basis
        )
    }

    private fun confidenceFor(
        result: Result,
        text: String,
        sender: String,
        before: List<Turn>,
        after: List<Turn>,
        priorRequestIndex: Int
    ): ConfidenceAssessment {
        val length = text.count { it.isLetterOrDigit() }
        val affectText = ownAffectText(text)
        val prior = when (result.kind) {
            Kind.SYSTEM -> 0.84
            Kind.CLOSING -> 0.65
            Kind.REASSURANCE -> 0.60
            Kind.EMOTION -> 0.64
            Kind.FOLLOW_UP -> 0.48
            Kind.QUESTION -> 0.56
            Kind.ACTION -> 0.72
            Kind.INFORMATION -> 0.60
            Kind.NONE -> 0.36
        }

        val evidence = when (result.kind) {
            Kind.SYSTEM -> 0.86
            Kind.CLOSING -> (closing.findAll(text).count() * 0.32 + if (length >= 6) 0.14 else 0.0).coerceAtMost(1.0)
            Kind.REASSURANCE -> (reassurance.findAll(text).count() * 0.38 + if (length >= 8) 0.18 else 0.0).coerceAtMost(1.0)
            Kind.EMOTION -> (emotion.findAll(affectText).count { !isNegated(affectText, it.range.first) } * 0.34 +
                if (length >= 8) 0.20 else 0.0).coerceAtMost(1.0)
            Kind.FOLLOW_UP -> {
                (0.28 +
                    (if (timePressure.containsMatchIn(text)) 0.22 else 0.0) +
                    (if (isQuestion(text)) 0.16 else 0.0) +
                    (if (length >= 8) 0.14 else 0.0)).coerceAtMost(1.0)
            }
            Kind.QUESTION -> (0.34 +
                (if (questionOpening.containsMatchIn(text) || isSpokenQuestion(text)) 0.24 else 0.0) +
                (if (text.contains('？') || text.contains('?')) 0.18 else 0.0) +
                (if (length >= 8) 0.10 else 0.0) -
                (if (action.containsMatchIn(text)) 0.14 else 0.0)).coerceIn(0.0, 1.0)
            Kind.ACTION -> (0.28 +
                (if (addressee.containsMatchIn(text)) 0.18 else 0.0) +
                (if (deliverable.containsMatchIn(text)) 0.18 else 0.0) +
                (if (timePressure.containsMatchIn(text)) 0.12 else 0.0) +
                (if (exactDeadline.containsMatchIn(text)) 0.10 else 0.0) +
                (if (length >= 12) 0.14 else 0.0) -
                (if (isQuestion(text)) 0.12 else 0.0)).coerceIn(0.0, 1.0)
            Kind.INFORMATION -> (0.42 + (if (length >= 12) 0.18 else 0.0) +
                (if (timePressure.containsMatchIn(text)) 0.12 else 0.0)).coerceAtMost(1.0)
            Kind.NONE -> (if (length >= 10) 0.20 else 0.0)
        }

        val relevantBefore = before.filter { sameCounterpart(it, sender) &&
            !systemNotice.containsMatchIn(it.text) && !NoticeRules.isReported(it.text) }.distinctBy { it.text }
        val sameKindSupport = relevantBefore.count { turn ->
            when (result.kind) {
                Kind.ACTION, Kind.FOLLOW_UP -> NoticeRules.hasIntentAction(turn.text)
                Kind.QUESTION -> isQuestion(turn.text)
                Kind.EMOTION -> ownAffectText(turn.text).let { value ->
                    emotion.findAll(value).any { !isNegated(value, it.range.first) } ||
                        negativeCue.findAll(value).any { !isNegated(value, it.range.first) }
                }
                Kind.REASSURANCE -> reassurance.containsMatchIn(turn.text) || careCue.containsMatchIn(turn.text)
                else -> false
            }
        }
        val requestRecency = if (priorRequestIndex >= 0 && result.kind in setOf(Kind.ACTION, Kind.FOLLOW_UP)) {
            exp(-0.38 * (before.lastIndex - priorRequestIndex).coerceAtLeast(0))
        } else 0.0
        val context = (sameKindSupport * 0.16 + requestRecency * 0.58 +
            (if (before.lastOrNull()?.let { sameCounterpart(it, sender) } == true) 0.08 else 0.0) +
            (if (after.any { it.side == "me" }) 0.04 else 0.0)).coerceIn(0.0, 1.0)
        val ambiguity = ((if (length <= 2) 0.26 else if (length <= 4) 0.12 else 0.0) +
            (if (vagueReference.containsMatchIn(text)) 0.18 else 0.0) +
            (if (hedge.containsMatchIn(text)) 0.14 else 0.0) +
            (if (isQuestion(text) && action.containsMatchIn(text)) 0.14 else 0.0) +
            (if (result.kind == Kind.NONE) 0.04 else 0.0)).coerceIn(0.0, 0.9)
        val visibleTurns = (before + after).count { it.side == "me" || sameCounterpart(it, sender) } + 1
        val dataQuality = (0.42 + visibleTurns.coerceAtMost(7) * 0.07).coerceAtMost(0.95)
        val recencyBoost = if (result.kind == Kind.FOLLOW_UP) requestRecency * 0.08 else context * 0.03
        val score = (prior * 65 + evidence * 35 + context * 10 + dataQuality * 8 +
            recencyBoost * 10 - ambiguity * 30).roundToInt().coerceIn(30, 94)
        val trace = ConfidenceTrace(
            prior = (prior * 100).roundToInt(),
            evidence = (evidence * 100).roundToInt(),
            context = (context * 100).roundToInt(),
            ambiguity = (ambiguity * 100).roundToInt(),
            dataQuality = (dataQuality * 100).roundToInt(),
            explanation = "先验${(prior * 100).roundToInt()} · 证据${(evidence * 100).roundToInt()} · 上下文${(context * 100).roundToInt()} · 歧义-${(ambiguity * 100).roundToInt()} · 可见数据${(dataQuality * 100).roundToInt()}"
        )
        return ConfidenceAssessment(score, trace)
    }

    private fun isSpokenQuestion(text: String): Boolean = spokenQuestion.containsMatchIn(text.replace(durationStatement, ""))
    private fun isQuestion(text: String): Boolean = question.containsMatchIn(text) || isSpokenQuestion(text)
    private fun sameCounterpart(turn: Turn, sender: String): Boolean = turn.side == "other" &&
        (turn.sender == sender || turn.sender == null && sender == "对方")
    private fun isNegated(text: String, start: Int): Boolean =
        negationBeforeCue.containsMatchIn(text.substring(maxOf(0, start - 8), start))
    private fun ownAffectText(text: String): String = quotedSpeech.replace(text, " ").split(Regex("(?<=[，,。！!？?；;\\n])"))
        .filterNot { NoticeRules.isReported(it) || thirdPersonReport.containsMatchIn(it.trim()) }
        .joinToString("")

    private data class Result(val kind: Kind, val label: String, val nextStep: String,
                              val importance: IntentImportance)
}
