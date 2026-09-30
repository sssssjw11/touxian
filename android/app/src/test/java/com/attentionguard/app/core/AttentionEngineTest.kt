package com.attentionguard.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AttentionEngineTest {

    @Test fun linkOnlyAnchorNeverProducesAnUnreadableBlankTitle() {
        val snapshot = ChatSnapshot("通知群", listOf(Msg("other", "https://example.org/提交", "老师")))
        val event = AttentionEngine.buildEvent(snapshot)
        assertNotNull(event)
        assertTrue(event!!.title.isNotBlank())
    }

    @Test
    fun casualConversationDoesNotCreateEvent() {
        val snapshot = ChatSnapshot(
            title = "宿舍闲聊群",
            messages = listOf(
                Msg("other", "今天晚饭吃什么？", "小王"),
                Msg("other", "我想吃面", "小李")
            )
        )
        assertNull(AttentionEngine.buildEvent(snapshot))
    }

    @Test
    fun rankingAndDisappointmentSmallTalkDoesNotCreateEvent() {
        val snapshot = ChatSnapshot(
            title = "测试联系人A",
            messages = listOf(
                Msg("other", "是不是不满足啊", "同学"),
                Msg("other", "那我是不是还有机会", "同学"),
                Msg("other", "可惜", "同学"),
                Msg("other", "发现下面那个排名相加比我高", "同学")
            )
        )
        assertTrue(AttentionEngine.buildEvents(snapshot).isEmpty())
    }

    @Test
    fun dayOnlyVideoDeadlineBecomesARecognizableActionEvent() {
        val day = LocalDate.of(2026, 9, 27)
        val event = AttentionEngine.buildEvent(ChatSnapshot(
            title = "示例校园黑客松(93)",
            messages = listOf(
                Msg("other", "视频提交时间28号下午5点截止，提交到邮箱submissions@example.org", "测试组织者", date = day.toString())
            ),
            capturedAt = day.atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        ))
        assertNotNull(event)
        assertEquals(EventCategory.COMPETITION, event?.category)
        assertEquals(EventStatus.ACTION_REQUIRED, event?.status)
        assertEquals("2026-09-28 17:00", event?.dueLabel)
        assertTrue(event?.attentionScore ?: 0 >= 60)
        assertTrue(event?.evidence?.singleOrNull()?.contains("视频提交时间28号") == true)
    }

    @Test
    fun verifiedDeadlineWithinTwentyFourHoursBecomesHighPriority() {
        val day = LocalDate.of(2026, 9, 23)
        val snapshot = ChatSnapshot(
            title = "班级通知群",
            messages = listOf(
                Msg("other", "辅导员：请在今天 17:00 前提交综测材料", "辅导员", date = day.toString()),
                Msg("other", "逾期视为放弃", "辅导员")
            ), capturedAt = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
        val event = AttentionEngine.buildEvent(snapshot)
        assertNotNull(event)
        assertEquals(EventPriority.P0, event?.priority)
        assertEquals(EventCategory.ACADEMIC_ADMIN, event?.category)
        assertEquals(EventStatus.ACTION_REQUIRED, event?.status)
        assertEquals("2026-09-23 17:00", event?.dueLabel)
    }

    @Test
    fun locationChangeIsClassifiedAsCourse() {
        val snapshot = ChatSnapshot(
            title = "课程群",
            messages = listOf(Msg("other", "明天下午实验课调整到 3-105，请按新教室到课", "张老师"))
        )
        val event = AttentionEngine.buildEvent(snapshot)
        assertNotNull(event)
        assertEquals(EventCategory.COURSE, event?.category)
        assertNotNull(event?.actionLabel)
    }

    @Test
    fun onlineMeetingNoticeShowsTypeImportanceAndAction() {
        val event = AttentionEngine.buildEvent(ChatSnapshot(
            title = "示例班级群(49)",
            messages = listOf(
                Msg("other", "@所有人 今晚七点要开个简短的线上班会，大家不要忘记", "测试班长"),
                Msg("other", "#腾讯会议：000-000-000", "测试班长"),
                Msg("other", "@所有人 等会七点要开班会，请各位同学不要迟到", "测试班长"),
                Msg("other", "@所有人 可以先进会议", "测试班长"),
                Msg("other", "没进的同学，抓紧时间，这个会议定的30分钟", "测试班长")
            )
        ))
        assertNotNull(event)
        assertEquals(EventCategory.MEETING, event?.category)
        assertTrue(event?.priority == EventPriority.P0 || event?.priority == EventPriority.P1)
        assertEquals("按时加入线上会议", event?.actionLabel)
        assertTrue(event?.title.orEmpty().contains("会议"))
        assertTrue(event?.evidence?.size ?: 0 >= 4)
        assertTrue(event?.summary.orEmpty().contains("抓紧时间"))
    }

    @Test
    fun chineseEveningMeetingTimeIsParsedFromTheWholeVisibleNotice() {
        val day = LocalDate.of(2026, 9, 26)
        val event = AttentionEngine.buildEvent(ChatSnapshot(
            title = "示例班级群(49)",
            messages = listOf(
                Msg("other", "@所有人 今晚七点要开个简短的线上班会，大家不要忘记", "测试班长", date = day.toString()),
                Msg("other", "#腾讯会议：000-000-000", "测试班长", date = day.toString()),
                Msg("other", "@所有人 等会七点要开班会，请各位同学不要迟到", "测试班长", date = day.toString()),
                Msg("other", "@所有人 可以先进会议", "测试班长", date = day.toString()),
                Msg("other", "没进的同学，抓紧时间，这个会议定的30分钟", "测试班长", date = day.toString())
            ),
            capturedAt = day.atTime(15, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        ))
        assertNotNull(event)
        assertEquals("2026-09-26 19:00", event?.dueLabel)
        assertEquals(EventPriority.P0, event?.priority)
    }

    @Test
    fun sameConversationAndTitleUseStableEventId() {
        val first = ChatSnapshot(
            title = "学院通知群",
            messages = listOf(Msg("other", "奖学金申请材料请报名登记", "学院教务"))
        )
        val second = first.copy(messages = first.messages + Msg("other", "请继续关注后续通知", "学院教务"))
        val firstEvent = AttentionEngine.buildEvent(first)
        val secondEvent = AttentionEngine.buildEvent(second)
        assertNotNull(firstEvent)
        assertNotNull(secondEvent)
        assertEquals(firstEvent?.id, secondEvent?.id)
    }

    @Test
    fun weakActionKeepsReviewNoteAndDoesNotEscalate() {
        val event = AttentionEngine.buildEvent(ChatSnapshot(
            title = "课程群",
            messages = listOf(Msg("other", "请关注后续安排，报名意向请回复", "群成员"))
        ))
        assertNotNull(event)
        assertEquals(EventPriority.P2, event?.priority)
        assertTrue(event?.reviewNotes?.any { it.contains("明确行动要求") } == true)
    }

    private fun event(vararg messages: Msg) = AttentionEngine.buildEvent(ChatSnapshot("Synthetic group", messages.toList(),
        capturedAt = LocalDate.of(2026, 9, 23).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()))!!

    @Test fun distantDeadlineAndUnknownRelativeDayCannotBecomeP0() {
        assertEquals(EventPriority.P1, event(Msg("other", "请在2026年10月12日17:00前提交报告", "老师")).priority)
        val unknown = event(Msg("other", "请在明天17:00前提交报告", "老师"))
        assertEquals(EventPriority.P1, unknown.priority)
        assertTrue(unknown.dueLabel!!.contains("待核对"))
    }

    @Test fun expiredNoticeIsNotAnUrgentAction() {
        val past = event(Msg("other", "请在2026年9月20日17:00前提交报告", "老师"))
        assertEquals(EventPriority.P2, past.priority)
        assertEquals(EventStatus.MONITORING, past.status)
    }

    @Test fun unrelatedMessagesCannotSupplyAuthorityOrDeadline() {
        val result = event(Msg("other", "老师：明天17:00活动开始", "群成员"), Msg("other", "请提交报名意向", "同学"))
        assertEquals(EventPriority.P2, result.priority)
        assertNull(result.dueLabel)
    }

    @Test fun correctionKeepsIdentityAndUsesLatestTime() {
        val original = Msg("other", "请于2026年9月23日17:00前提交报告", "老师")
        val first = event(original)
        val corrected = event(original, Msg("other", "更正：改为2026年9月25日17:00", "老师"))
        assertEquals(first.id, corrected.id)
        assertEquals("2026-09-25 17:00", corrected.dueLabel)
        assertEquals(EventPriority.P1, corrected.priority)
        assertEquals(2, corrected.evidence.size)
    }

    @Test fun cancellationIsReviewableAndNeverAutoCompletes() {
        val cancelled = event(Msg("other", "请提交报告", "老师"), Msg("other", "更正：本次提交取消", "老师"))
        assertEquals(EventPriority.P3, cancelled.priority)
        assertEquals(EventStatus.MONITORING, cancelled.status)
        assertNull(cancelled.actionLabel)
    }

    @Test fun separateNoticesInSameCategoryDoNotShareIdentity() {
        val a = event(Msg("other", "请提交网络实验报告", "老师"))
        val b = event(Msg("other", "请提交数据库实验报告", "老师"))
        org.junit.Assert.assertNotEquals(a.id, b.id)
    }

    @Test fun unrelatedCancellationBySameSenderDoesNotCancelEarlierNotice() {
        val result = AttentionEngine.buildEvents(ChatSnapshot("通知群", listOf(
            Msg("other", "请报名数学竞赛", "老师"),
            Msg("other", "更正：今晚班会取消", "老师")
        )))
        assertEquals(2, result.size)
        assertEquals(EventStatus.ACTION_REQUIRED, result.first().status)
        assertEquals(EventCategory.COMPETITION, result.first().category)
        assertEquals(EventCategory.MEETING, result.last().category)
        assertEquals(EventPriority.P3, result.last().priority)
        val sameCategory = AttentionEngine.buildEvents(ChatSnapshot("通知群", listOf(
            Msg("other", "请提交综测材料", "老师"),
            Msg("other", "更正：奖学金申请取消", "老师")
        )))
        assertEquals(2, sameCategory.size)
        assertEquals(EventStatus.ACTION_REQUIRED, sameCategory.first().status)
    }

    @Test fun completedAndExplicitlyReportedTasksDoNotBecomeAuthorityNotices() {
        listOf("我已经提交报告了", "报名已经完成", "她说“请提交报告”").forEach { text ->
            assertTrue(text, AttentionEngine.buildEvents(ChatSnapshot("通知群", listOf(Msg("other", text, "老师")))).isEmpty())
        }
    }

    @Test fun cancellationOfCheckboxDoesNotCancelTheActualSubmissionRequest() {
        val result = event(Msg("other", "请填写表格并取消勾选不适用项", "老师"))
        assertEquals(EventStatus.ACTION_REQUIRED, result.status)
        assertNotNull(result.actionLabel)
    }

    @Test fun usefulDeadlineOnlyNoticeIsRecordedWithoutInventingARequiredAction() {
        val result = event(Msg("other", "课程作业截止时间：2026年9月25日下午五点", "同学"))
        assertEquals(EventStatus.MONITORING, result.status)
        assertEquals(EventCategory.COURSE, result.category)
        assertEquals("2026-09-25 17:00", result.dueLabel)
    }
}



