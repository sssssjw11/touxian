package com.attentionguard.app.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.Duration
import java.security.MessageDigest

/** Fast, offline attention gate used before any DeepSeek request. */
object AttentionEngine {

    private val deadlinePattern = Regex("(截止|最晚|不晚于|截至|之前|前提交|前完成|前报名|前交|前发|前回复|今晚|今天|明天|周[一二三四五六日天]|[0-9一二三四五六七八九十]+点|[0-9]+分钟)")
    private val locationPattern = Regex("(教室|会议室|地点|校区|\\d+号楼|(?<![\\d-])\\d{1,2}[-—]\\d{3}(?![\\d-])|\\d+室)")
    private val correction = Regex("更正|改为|改到|调整为|以.{0,12}为准")
    private val reminder = Regex("抓紧时间|不要迟到|不要忘记|没进的同学|准时|^#?腾讯会议|^会议号")
    private val namedTopic = Regex("综测|综合测评|奖学金|学分|医保|重修|作业|报告|表格|竞赛|比赛|报名|班会|会议|讲座|招聘|宣讲")

    fun buildEvent(snapshot: ChatSnapshot, context: String = "", origin: CaptureOrigin = CaptureOrigin.UNKNOWN,
                   keywordRules: List<MessageKeywordRule> = emptyList()): AttentionEvent? =
        buildEvents(snapshot, context, origin, keywordRules).lastOrNull()

    @Suppress("UNUSED_PARAMETER")
    fun buildEvents(snapshot: ChatSnapshot, context: String = "", origin: CaptureOrigin = CaptureOrigin.UNKNOWN,
                    keywordRules: List<MessageKeywordRule> = emptyList()): List<AttentionEvent> {
        if (snapshot.title.isNullOrBlank()) return emptyList()
        val visible = snapshot.messages.filter {
            it.side == "other" && it.type == MessageType.TEXT && it.captureMethod == "nodes" &&
                !NoticeRules.isNoise(it.text) && !NoticeRules.isReported(it.text)
        }
        val groups = mutableListOf<MutableList<Msg>>()
        for (message in visible) {
            val previous = groups.lastOrNull()
            if (previous != null && continues(previous, message, snapshot.capturedAt, snapshot.title.orEmpty())) previous.add(message)
            else groups.add(mutableListOf(message))
        }
        return groups.mapNotNull { buildTopicEvent(snapshot, it, origin, keywordRules) }
    }

    fun primary(events: List<AttentionEvent>): AttentionEvent? =
        events.minWithOrNull(compareBy<AttentionEvent> { it.priority.ordinal }.thenByDescending { it.attentionScore })

    private fun buildTopicEvent(snapshot: ChatSnapshot, messages: List<Msg>, origin: CaptureOrigin,
                                keywordRules: List<MessageKeywordRule>): AttentionEvent? {
        val anchor = messages.firstOrNull { hasAction(it.text) } ?: messages.first()
        val latestCorrection = messages.drop(1).lastOrNull { correction.containsMatchIn(it.text) || NoticeRules.isCancelled(it.text) }
        val joined = messages.joinToString("\n") { it.text.trim() }
        val ruleClauses = keywordRules.mapNotNull { rule ->
            val clauses = messages.flatMap { rule.matchingClauses(it.text) }
            if (clauses.isEmpty()) null else rule to clauses.last()
        }
        val activeRuleClauses = ruleClauses.filterNot { (_, clause) ->
            NoticeRules.isCancelled(clause) || NoticeRules.isCompleted(clause)
        }
        // A terminal topic cannot take the metadata of another live task in the same message.
        val eligibleRuleClauses = activeRuleClauses.ifEmpty {
            if (messages.any { hasAction(it.text) }) emptyList() else ruleClauses
        }
        val matchedRules = eligibleRuleClauses.map { it.first }
            .sortedWith(compareBy<MessageKeywordRule> { it.priority.ordinal }
                .thenByDescending { MessageKeywordRule.normalizedKeyword(it.keyword).length }.thenBy { it.id })
        val keywordRule = matchedRules.firstOrNull()
        val latestText = (latestCorrection ?: messages.last()).text
        val keywordStateText = keywordRule?.matchingClauses(latestText)?.lastOrNull() ?: latestText
        val cancelled = NoticeRules.isCancelled(keywordStateText)
        val completed = NoticeRules.isCompleted(keywordStateText)
        val actionable = messages.any { hasAction(it.text) } && !cancelled && !completed
        val informational = NoticeRules.isInformation(joined)
        val authoritative = messages.any { message -> AUTHORITY_WORDS.any { message.sender?.contains(it) == true } }
        val mentionsAll = joined.contains("@所有人") || joined.contains("@所有成员") ||
            joined.contains("@全体") || joined.contains("全体成员")
        val hasDeadlineWord = deadlinePattern.containsMatchIn(joined)
        val nowDateTime = Instant.ofEpochMilli(snapshot.capturedAt).atZone(ZoneId.systemDefault()).toLocalDateTime()
        val deadlines = (latestCorrection?.let(::listOf) ?: messages).map { message ->
            val day = message.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            DeadlineParser.parse(message.text, day, nowDateTime.toLocalDate())
        }
        val deadline = deadlines.lastOrNull { it.at != null } ?: deadlines.firstOrNull { it.label != null } ?: DeadlineParser.Result(null)
        val hasDeadline = deadline.at != null || hasDeadlineWord
        val hours = deadline.at?.let { Duration.between(nowDateTime, it).toMinutes() / 60.0 }
        val expired = hours != null && hours < 0
        val hasLocation = locationPattern.containsMatchIn(joined)
        val category = keywordRule?.category ?: categoryOf(joined) ?: categoryOf(snapshot.title.orEmpty()) ?: EventCategory.ACADEMIC_ADMIN
        if (!actionable && !informational && !cancelled &&
            keywordRule == null &&
            !(authoritative && NoticeRules.topic.containsMatchIn(joined) && !NoticeRules.isCompleted(joined))) return null
        val score = (scoreOf(actionable, authoritative, mentionsAll, hasDeadline, hasLocation, category) +
            (if (informational) 10 else 0) + (if (messages.size >= 3) 5 else 0) -
            (if (cancelled) 12 else 0)).coerceIn(0, 99)

        val priority = when {
            cancelled || completed -> EventPriority.P3
            expired -> keywordRule?.priority?.takeIf { it.ordinal > EventPriority.P2.ordinal } ?: EventPriority.P2
            keywordRule != null -> keywordRule.priority
            actionable && hours != null && hours in 0.0..24.0 && (authoritative || mentionsAll) -> EventPriority.P0
            actionable && (deadline.at != null || authoritative || mentionsAll) -> EventPriority.P1
            actionable || informational || hasDeadline || score >= 42 -> EventPriority.P2
            else -> EventPriority.P3
        }
        val latest = messages.last()
        val title = titleOf(joined)
        val now = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val updates = messages.takeLast(4).map { message ->
            val sender = message.sender ?: if (message.side == "me") "我" else "群成员"
            EventUpdate(
                time = message.timestamp?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) } ?: now,
                title = if (message == latest) "最新观测" else "上下文消息",
                detail = "$sender：${message.text.take(72)}",
                tone = if (hasDeadline && deadlinePattern.containsMatchIn(message.text)) UpdateTone.WARNING else UpdateTone.NEUTRAL
            )
        }
        val sourcePerson = messages.mapNotNull { it.sender }.distinct().take(2).joinToString(" / ")
            .ifBlank { if (authoritative) "老师 / 班委" else "群成员" }
        val dueLabel = if (cancelled || completed) null else deadline.label
        val eventId = stableId(snapshot.title + "|" + anchor.sender.orEmpty() + "|" + anchor.date.orEmpty() + "|" + anchor.text.trim())
        val summary = listOfNotNull(anchor.text, latest.takeIf { it != anchor }?.text).joinToString("；")

        return AttentionEvent(
            id = eventId,
            title = title,
            summary = summary.take(240),
            sourceGroup = requireNotNull(snapshot.title),
            sourcePerson = sourcePerson,
            priority = priority,
            status = if (cancelled || completed || expired) EventStatus.MONITORING else if (actionable) EventStatus.ACTION_REQUIRED else EventStatus.MONITORING,
            category = category,
            attentionScore = score.coerceIn(1, 99),
            dueLabel = dueLabel,
            actionLabel = when {
                cancelled || completed -> null
                !actionable -> if (category == EventCategory.EMPLOYMENT) "查看岗位与参加条件" else "查看信息更新，无需立即操作"
                else -> actionLabelOf(joined) ?: "按通知要求处理"
            },
            consequence = consequenceOf(joined),
            updatedLabel = "$now 更新",
            updates = updates,
            evidence = (listOf(messages.first()) + messages.takeLast(5)).distinct().map { it.text.take(500) },
            captureOrigin = origin,
            sourceCapturedAt = snapshot.capturedAt,
            reviewNotes = buildList {
                if (authoritative) add("发送者名称包含管理方信号，身份尚未验证")
                if (actionable) add("检测到明确行动要求")
                if (informational && !actionable) add("有用的信息更新，不强制生成待办")
                if (messages.size > 1) add("合并同一发送者、同日同主题的 ${messages.size} 条可见消息")
                if (deadline.at != null) add("日期已通过日历校验")
                deadline.note?.let { add(it) }
                if (expired) add("截止时间已过，保留记录而非升级紧急提醒")
                if (priority == EventPriority.P0 && keywordRule == null) add("明确行动且截止在 24 小时内，存在来源或全体通知信号")
                if (latestCorrection != null) add("同一发送者的相邻更正覆盖旧时间，保留原始依据")
                if (cancelled) add("存在取消或作废信号，待人工核对，不自动标记完成")
                if (completed) add("存在已完成表述，仅保留观测，不自动勾选完成")
                if (mentionsAll) add("消息面向全体成员")
                if (priority == EventPriority.P3 && keywordRule == null) add("证据较弱，仅作为低优先级记录")
                if (keywordRule != null) {
                    val detail = buildString {
                        append("类型与重要性采用自定义消息规则；关键词不等于行动要求")
                        if (cancelled || completed || expired) append("；已取消、完成或截止不升级紧急优先级")
                        if (matchedRules.size > 3) append("；另有 ${matchedRules.size - 3} 条规则命中")
                    }
                    add(detail)
                    // EventStore keeps the last six notes; retain the deciding rule as final evidence.
                    matchedRules.take(3).asReversed().forEach {
                        add(MessageKeywordRule.EVIDENCE_PREFIX + it.keyword.trim())
                    }
                }
            }
        )
    }

    private fun continues(previous: List<Msg>, next: Msg, capturedAt: Long, title: String): Boolean {
        val last = previous.last()
        if (last.sender.isNullOrBlank() || last.sender != next.sender ||
            (last.date != null && next.date != null && last.date != next.date)) return false
        val priorCategory = categoryOf(previous.joinToString("\n") { it.text }) ?: categoryOf(title)
        val nextCategory = categoryOf(next.text)
        if (priorCategory != null && nextCategory != null && priorCategory != nextCategory) return false
        val priorTopics = topicsOf(previous.joinToString("\n") { it.text })
        val nextTopics = topicsOf(next.text)
        if (priorTopics.isNotEmpty() && nextTopics.isNotEmpty() && priorTopics.intersect(nextTopics).isEmpty()) return false
        if (Regex("另外|另一场|另一个").containsMatchIn(next.text)) return false
        if (correction.containsMatchIn(next.text) || NoticeRules.isCancelled(next.text) ||
            next.text.startsWith("逾期") || next.text.startsWith("否则")) return true
        if (!NoticeRules.meeting.containsMatchIn(last.text) ||
            !NoticeRules.meeting.containsMatchIn(next.text) && !reminder.containsMatchIn(next.text)) return false
        val today = Instant.ofEpochMilli(capturedAt).atZone(ZoneId.systemDefault()).toLocalDate()
        fun deadline(message: Msg) = DeadlineParser.parse(message.text,
            message.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }, today).at
        val oldTime = previous.mapNotNull(::deadline).lastOrNull()
        val newTime = deadline(next)
        return oldTime == null || newTime == null || oldTime == newTime
    }

    fun shouldNotify(event: AttentionEvent): Boolean =
        event.priority == EventPriority.P0 || event.status == EventStatus.ACTION_REQUIRED || event.dueLabel != null

    private fun scoreOf(
        actionable: Boolean,
        authoritative: Boolean,
        mentionsAll: Boolean,
        hasDeadline: Boolean,
        hasLocation: Boolean,
        category: EventCategory
    ): Int {
        var score = 18
        if (actionable) score += 27
        if (authoritative) score += 17
        if (mentionsAll) score += 15
        if (hasDeadline) score += 18
        if (hasLocation) score += 6
        if (category == EventCategory.EMPLOYMENT || category == EventCategory.ACADEMIC_ADMIN) score += 5
        return score
    }

    private fun titleOf(text: String): String = when {
        text.contains("综测") || text.contains("综合测评") -> "综合测评材料提交"
        text.contains("奖学金") -> "奖学金申请"
        text.contains("学分") -> "学分申请与进度更新"
        text.contains("表格") || text.contains("填报") -> "信息填报通知"
        text.contains("调课") || text.contains("教室") -> "课程安排更新"
        text.contains("作业") || text.contains("实验报告") -> "课程作业与实验要求"
        NoticeRules.meeting.containsMatchIn(text) -> if (Regex("线上|腾讯会议|入会|进会议").containsMatchIn(text)) "线上班会 / 会议提醒" else "班会 / 会议安排"
        text.contains("招聘") || text.contains("双选会") || text.contains("宣讲") -> "就业活动提醒"
        text.contains("重修") -> "重修报名安排"
        text.contains("竞赛") || text.contains("比赛") || text.contains("黑客松") -> "竞赛报名"
        text.contains("报名") -> "报名安排"
        else -> text.replace(Regex("https?://\\S+|@所有人|@所有成员"), "").trim(' ', '：', ':').replace(Regex("\\s+"), " ").take(26)
            .ifBlank { if (text.contains(Regex("https?://"))) "链接消息 · 请核对原文" else "待核对事项" }
    }

    private fun categoryOf(text: String): EventCategory? = when {
        NoticeRules.meeting.containsMatchIn(text) -> EventCategory.MEETING
        text.contains("就业") || text.contains("招聘") || text.contains("双选") || text.contains("宣讲") -> EventCategory.EMPLOYMENT
        text.contains("竞赛") || text.contains("比赛") || text.contains("黑客松") -> EventCategory.COMPETITION
        text.contains("调课") || text.contains("作业") || text.contains("实验") || text.contains("课程") || text.contains("重修") -> EventCategory.COURSE
        text.contains("学分") || text.contains("教务") || text.contains("综测") || text.contains("奖学金") || text.contains("综合测评") -> EventCategory.ACADEMIC_ADMIN
        text.contains("活动") || text.contains("讲座") || text.contains("志愿") ||
            text.contains("班会") || text.contains("会议") -> EventCategory.ACTIVITY
        else -> null
    }

    private fun hasAction(text: String) = NoticeRules.hasAction(text)
    private fun topicsOf(text: String): Set<String> = namedTopic.findAll(text).map {
        when (it.value) {
            "综合测评" -> "综测"
            "班会" -> "会议"
            "比赛" -> "竞赛"
            else -> it.value
        }
    }.toSet()

    private fun actionLabelOf(text: String): String? = when {
        NoticeRules.meeting.containsMatchIn(text) -> if (Regex("线上|腾讯会议|入会|进会议").containsMatchIn(text)) "按时加入线上会议" else "按通知时间参加会议"
        text.contains("填写") || text.contains("填报") || text.contains("表格") || text.contains("申请") -> "填写并提交材料"
        text.contains("报名") -> "完成报名"
        text.contains("提交") || text.contains("发送") || text.contains("发我") -> "提交或发送材料"
        text.contains("到场") || text.contains("到课") || text.contains("参加") || text.contains("出席") -> "按通知到场"
        text.contains("确认") || text.contains("回复") -> "回复确认"
        else -> null
    }

    private fun consequenceOf(text: String): String? = when {
        text.contains("逾期") -> text.substringAfter("逾期").take(32).ifBlank { "逾期可能影响办理" }
        text.contains("否则") -> text.substringAfter("否则").take(32)
        text.contains("不要迟到") -> "请按时进入会议"
        text.contains("不要去") || text.contains("不要错过") -> text.substringAfter("不要").take(32)
        else -> null
    }

    private fun stableId(value: String): String = "event_v2_" + MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }

    private val AUTHORITY_WORDS = listOf("老师", "辅导员", "班委", "班长", "团支书", "学习委员", "学院", "教务", "就业中心", "管理员")
}


