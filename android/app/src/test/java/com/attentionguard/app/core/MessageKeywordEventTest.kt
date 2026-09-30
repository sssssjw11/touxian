package com.attentionguard.app.core

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class MessageKeywordEventTest {
    private val day = LocalDate.of(2026, 9, 30)
    private val capturedAt = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun snapshot(text: String) = ChatSnapshot("普通聊天", listOf(Msg("other", text, "同学", date = day.toString())),
        "com.tencent.mm", capturedAt)
    private val rule = MessageKeywordRule("训练营", EventCategory.ACTIVITY, EventPriority.P1, id = "rule-a")
    private fun event(text: String, rules: List<MessageKeywordRule> = listOf(rule)) =
        AttentionEngine.buildEvents(snapshot(text), keywordRules = rules).single()

    @Test fun userKeywordAddsUsefulInformationWithoutInventingARequiredAction() {
        val text = "训练营的资料链接在这里"
        assertTrue(AttentionEngine.buildEvents(snapshot(text)).isEmpty())
        val result = event(text)
        assertEquals(EventStatus.MONITORING, result.status)
        assertEquals(EventCategory.ACTIVITY, result.category)
        assertEquals(EventPriority.P1, result.priority)
        assertTrue(result.reviewNotes.contains(MessageKeywordRule.EVIDENCE_PREFIX + "训练营"))
        assertFalse(result.reviewNotes.any { it.contains("24 小时内") })
    }

    @Test fun existingActionKeepsItsStableIdentityAndOneEventWhenARuleOverridesMetadata() {
        val current = snapshot("请在明天17:00前提交课程作业")
        val original = AttentionEngine.buildEvents(current).single()
        val custom = MessageKeywordRule("作业", EventCategory.ACTIVITY, EventPriority.P3, id = "explicit")
        val result = AttentionEngine.buildEvents(current, keywordRules = listOf(custom)).single()
        assertEquals(original.id, result.id)
        assertEquals(EventStatus.ACTION_REQUIRED, result.status)
        assertEquals(EventCategory.ACTIVITY, result.category)
        assertEquals(EventPriority.P3, result.priority)
        assertEquals(original.dueLabel, result.dueLabel)
    }

    @Test fun ruleCannotMatchOnlyTheConversationTitleOrUnsupportedMessages() {
        val byTitle = snapshot("聊天内容完全不同").copy(title = "训练营交流群")
        assertTrue(AttentionEngine.buildEvents(byTitle, keywordRules = listOf(rule)).isEmpty())
        for (message in listOf(
            Msg("me", "训练营资料"), Msg("other", "训练营资料", type = MessageType.IMAGE),
            Msg("other", "训练营资料", captureMethod = "ocr")
        )) assertTrue(AttentionEngine.buildEvents(snapshot("unused").copy(messages = listOf(message)), keywordRules = listOf(rule)).isEmpty())
        assertTrue(AttentionEngine.buildEvents(snapshot("训练营资料"), keywordRules = listOf(rule.copy(enabled = false))).isEmpty())
    }

    @Test fun completionCancellationAndExpiredTimeDoNotInheritUrgentRulePriority() {
        val urgent = listOf(rule.copy(priority = EventPriority.P0))
        for (text in listOf("我已经完成训练营报名了", "本次训练营报名取消")) {
            val result = event(text, urgent)
            assertEquals(text, EventPriority.P3, result.priority)
            assertEquals(text, EventStatus.MONITORING, result.status)
            assertNull(result.actionLabel)
            assertNull(result.dueLabel)
            assertFalse(AttentionEngine.shouldNotify(result))
        }
        val expired = event("训练营截止2026年9月20日17:00", urgent)
        assertEquals(EventPriority.P2, expired.priority)
        assertEquals(EventStatus.MONITORING, expired.status)
        assertEquals(EventPriority.P3, event("训练营截止2026年9月20日17:00", listOf(rule.copy(priority = EventPriority.P3))).priority)
    }

    @Test fun highestPriorityThenLongestKeywordThenIdSelectsMetadataDeterministically() {
        val text = "训练营学习资料链接在这里"
        val low = rule.copy(id = "low", keyword = "训练营学习资料", priority = EventPriority.P2)
        val short = rule.copy(id = "short", category = EventCategory.COURSE)
        val long = rule.copy(id = "b", keyword = "训练营学习", category = EventCategory.COMPETITION)
        val same = long.copy(id = "a", category = EventCategory.EMPLOYMENT)
        val result = event(text, listOf(low, short, long, same))
        assertEquals(EventPriority.P1, result.priority)
        assertEquals(EventCategory.EMPLOYMENT, result.category)
        assertEquals(result.id, event(text, listOf(same, long, short, low)).id)
        assertEquals(result.category, event(text, listOf(same, long, short, low)).category)
    }

    @Test fun negativeFacilitiesFactIsObservedButAnExplicitlyDeniedEventIsNotCreated() {
        val facilities = MessageKeywordRule("热水", EventCategory.ACTIVITY, id = "water")
        assertEquals(EventStatus.MONITORING, event("今晚没有热水", listOf(facilities)).status)
        assertTrue(AttentionEngine.buildEvents(snapshot("今晚不会停水"), keywordRules = listOf(MessageKeywordRule("停水"))).isEmpty())
        assertTrue(AttentionEngine.buildEvents(snapshot("今天没有考试"), keywordRules = listOf(MessageKeywordRule("考试", priority = EventPriority.P0))).isEmpty())
    }

    @Test fun unrelatedQuotedTopicCannotOverrideARealCourseworkRequest() {
        val result = event("“训练营”只是闲聊话题，请大家明天提交课程作业", listOf(rule.copy(priority = EventPriority.P0)))
        assertEquals(EventCategory.COURSE, result.category)
        assertEquals(EventStatus.ACTION_REQUIRED, result.status)
        assertFalse(result.reviewNotes.any { it.startsWith(MessageKeywordRule.EVIDENCE_PREFIX) })
    }

    @Test fun terminatedKeywordDoesNotWinMetadataOfAnotherActiveTopic() {
        val scholarship = MessageKeywordRule("奖学金", EventCategory.ACADEMIC_ADMIN, EventPriority.P1, id = "live")
        val cancelled = rule.copy(priority = EventPriority.P0)
        val result = event("训练营报名取消，奖学金申请请明天17点前提交材料", listOf(cancelled, scholarship))
        assertEquals(EventCategory.ACADEMIC_ADMIN, result.category)
        assertEquals(EventPriority.P1, result.priority)
        assertEquals(EventStatus.ACTION_REQUIRED, result.status)
        assertTrue(result.reviewNotes.contains(MessageKeywordRule.EVIDENCE_PREFIX + "奖学金"))
        assertFalse(result.reviewNotes.contains(MessageKeywordRule.EVIDENCE_PREFIX + "训练营"))
        val onlyTerminalRule = event("训练营报名取消，请明天17点前提交课程作业", listOf(cancelled))
        assertEquals(EventCategory.COURSE, onlyTerminalRule.category)
        assertEquals(EventStatus.ACTION_REQUIRED, onlyTerminalRule.status)
    }

    @Test fun explicitNegativeActionUsesSharedCancellationRatherThanUrgentKeywordPriority() {
        for (text in listOf("不需要提交课程作业", "不用交作业", "无需提交课程作业")) {
            val result = event(text, listOf(MessageKeywordRule("作业", priority = EventPriority.P0)))
            assertEquals(text, EventPriority.P3, result.priority)
            assertEquals(text, EventStatus.MONITORING, result.status)
            assertFalse(text, AttentionEngine.shouldNotify(result))
        }
        assertTrue(NoticeRules.hasAction("无需打印但需要线上提交课程作业"))
        assertTrue(NoticeRules.hasAction("不需要提交纸质材料，但请明天提交线上课程作业"))
        assertFalse(NoticeRules.isCancelled("不需要提交纸质材料，但请明天提交线上课程作业"))
    }

    @Test fun decidingKeywordSurvivesTheLastSixStoredReviewNotes() {
        val text = "@所有人 训练营课程资料学习活动报名截止2026年9月20日17:00"
        val current = snapshot(text).copy(messages = listOf(Msg("other", text, "老师", date = day.toString())))
        val rules = listOf("训练营", "课程", "资料", "学习", "活动", "报名").mapIndexed { index, keyword ->
            rule.copy(keyword = keyword, id = "rule-$index", priority = if (index == 0) EventPriority.P0 else EventPriority.P2)
        }
        val result = AttentionEngine.buildEvents(current, keywordRules = rules).single()
        assertTrue(result.reviewNotes.takeLast(6).contains(MessageKeywordRule.EVIDENCE_PREFIX + "训练营"))
        assertEquals(3, result.reviewNotes.count { it.startsWith(MessageKeywordRule.EVIDENCE_PREFIX) })
        assertTrue(result.reviewNotes.any { it.contains("另有 3 条规则命中") })
    }
}
