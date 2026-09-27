package com.attentionguard.app.core

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

/** Anonymized wording from missed notices plus counterexamples, not model training. */
class CampusNoticeRegressionTest {
    private val day = LocalDate.of(2026, 9, 26)
    private val now = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun msg(text: String, sender: String = "班委") = Msg("other", text, sender, date = day.toString())
    private fun snapshot(vararg messages: Msg) = ChatSnapshot("班级群", messages.toList(), "com.tencent.mm", now)
    private fun events(vararg messages: Msg) = AttentionEngine.buildEvents(snapshot(*messages))

    @Test fun usefulNoticesHaveTypeSummaryImportanceAndNextStep() {
        val samples = listOf(
            "@所有人\u2005今晚七点要开个简短的线上班会，大家不要忘记" to EventCategory.MEETING,
            "@所有人可以先进会议" to EventCategory.MEETING,
            "@所有人 请各位同学在今晚五点前完成表格，在外实习的同学填离校实习" to EventCategory.ACADEMIC_ADMIN,
            "需要学分冲抵的同学抓紧申请" to EventCategory.ACADEMIC_ADMIN,
            "请于9月27日23:00前完成课程重修报名，逾期不补报" to EventCategory.COURSE,
            "请在明天17:00前提交实验报告" to EventCategory.COURSE,
            "竞赛报名已开放，请大家尽快报名" to EventCategory.COMPETITION
        )
        samples.forEach { (text, category) ->
            val event = events(msg(text)).singleOrNull()
            assertNotNull(text, event)
            assertEquals(text, category, event!!.category)
            assertEquals(text, EventStatus.ACTION_REQUIRED, event.status)
            assertTrue(text, event.priority != EventPriority.P3)
            assertTrue(text, event.title.isNotBlank() && event.summary.isNotBlank() && !event.actionLabel.isNullOrBlank())
            assertTrue(text, event.evidence.any { it.contains(text) })
        }
    }

    @Test fun informationalUpdatesAreUsefulButNotMandatoryTasks() {
        val samples = listOf(
            "@所有人 在钉钉上申请的课外学分冲抵，目前在教务系统是不体现的，因为数据还没导入，大家莫急。" to EventCategory.ACADEMIC_ADMIN,
            "企业招聘宣讲时间：2026-9-29 10:30，宣讲地点：综合楼二楼，面向2027届毕业生。" to EventCategory.EMPLOYMENT
        )
        samples.forEach { (text, category) ->
            val event = events(msg(text)).single()
            assertEquals(category, event.category)
            assertEquals(EventStatus.MONITORING, event.status)
            assertEquals(EventPriority.P2, event.priority)
            assertNotNull(event.actionLabel)
            assertEquals("可能在补充事项信息", JevIntentEngine.analyze(snapshot(msg(text)))?.label)
        }
    }

    @Test fun exemptionForPeopleWhoAlreadyAppliedDoesNotCancelTheNotice() {
        val event = events(msg("还未申请综测加分的同学请填写汇总表。截止时间：今天下午13:30。已自行申请的同学不用重复填写。")).single()
        assertEquals(EventStatus.ACTION_REQUIRED, event.status)
        assertEquals(EventPriority.P0, event.priority)
        assertEquals("2026-09-26 13:30", event.dueLabel)
        assertEquals("可能在提出行动请求", JevIntentEngine.analyze(snapshot(msg(event.summary)))?.label)
    }

    @Test fun meetingRemindersKeepOriginalTimeAndAllVisibleEvidence() {
        val messages = arrayOf(
            msg("@所有人\u2005今晚七点要开个简短的线上班会，大家不要忘记"),
            msg("#腾讯会议：123-456-789"),
            msg("@所有人 等会七点要开班会，请各位同学不要迟到"),
            msg("@所有人 可以先进会议"),
            msg("没进的同学，抓紧时间，这个会议定的30分钟")
        )
        val event = events(*messages).single()
        assertEquals(EventCategory.MEETING, event.category)
        assertEquals("2026-09-26 19:00", event.dueLabel)
        assertEquals(EventPriority.P0, event.priority)
        assertEquals(5, event.evidence.size)
        assertTrue(event.summary.contains("班会") && event.summary.contains("抓紧时间"))
        assertEquals(IntentImportance.HIGH, JevIntentEngine.analyze(snapshot(*messages))?.importance)
        val expired = AttentionEngine.buildEvents(snapshot(*messages).copy(capturedAt = now + 86_400_000)).single()
        assertEquals(EventPriority.P2, expired.priority)
        assertEquals(EventStatus.MONITORING, expired.status)
    }

    @Test fun screenWithIndependentNoticesProducesSeparateEvents() {
        val result = events(
            msg("今晚七点开线上班会"),
            msg("请在明天17:00前提交实验报告"),
            msg("重修报名截止9月30日，请需要的同学抓紧报名")
        )
        assertEquals(3, result.size)
        assertEquals(listOf(EventCategory.MEETING, EventCategory.COURSE, EventCategory.COURSE), result.map { it.category })
        assertTrue(result[1].evidence.none { it.contains("班会") })
        assertNotEquals(result[0].id, result[1].id)
    }

    @Test fun differentSendersAndDatesNeverShareMeetingEvidence() {
        val first = msg("今晚七点开线上班会", "甲")
        val second = msg("请参加线上会议", "乙")
        assertEquals(2, events(first, second).size)
        assertNull(events(first, second).last().dueLabel)
        assertEquals(2, events(first, first.copy(date = day.plusDays(1).toString())).size)
    }

    @Test fun ordinaryRepliesQuestionsAndMarkersDoNotCreateEvents() {
        listOf("收到", "@所有人", "[图片]", "今天晚饭吃什么？", "请问明天要提交作业吗？",
            "我已经提交实验报告了", "申请已经完成", "大家不要忘记吃饭").forEach { text ->
            assertTrue(text, events(msg(text, "同学")).isEmpty())
        }
    }

    @Test fun unrelatedSmallTalkIsNotReclassifiedAsMeetingAction() {
        val insight = JevIntentEngine.analyze(snapshot(msg("今晚七点开班会", "甲"), msg("吃什么？", "乙")))
        assertEquals("可能在寻求解释", insight?.label)
    }

    @Test fun explicitBodyCategoryWinsButUnspecifiedVideoCanUseGroupContext() {
        fun event(text: String) = AttentionEngine.buildEvents(snapshot(msg(text)).copy(title = "校园黑客松")).single()
        assertEquals(EventCategory.COURSE, event("请大家明天17:00前提交课程作业").category)
        assertEquals(EventCategory.ACADEMIC_ADMIN, event("需要学分冲抵的同学抓紧申请").category)
        assertEquals(EventCategory.COMPETITION, event("视频提交时间28号下午5点截止，提交到指定邮箱").category)
    }
}
