package com.attentionguard.app.core

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class RelationshipAnalysisTest {
    private fun row(id: Long, side: String, text: String, date: String? = "2026-09-01",
                    sender: String? = if (side == "other") "联系人" else null,
                    timestamp: Long? = null, capturedAt: Long = 1_800_000_000_000L, group: String = "会话") =
        ArchivedMessage(id, group, Msg(side, text, sender, timestamp, date = date), capturedAt)
    private fun metric(report: RelationshipReport, label: String) = report.metrics.single { it.label == label }
    private fun score(report: RelationshipReport) = metric(report, "关系程度线索").value.substringBefore(' ').toInt()
    private fun time(day: String) = LocalDate.parse(day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun balancedVolumeIsNotProofOfRomanceOrColdness() {
        val records = (1L..30L).map { row(it, if (it % 2L == 0L) "me" else "other", "项目进度 $it 已收到") }
        val report = RelationshipAnalysis.analyze(records, "会话")
        assertEquals("日常互动候选", report.label)
        assertEquals("100 / 100", metric(report, "互动对等").value)
        assertEquals(0, score(report))
        assertTrue(report.limitations.any { it.contains("不是被爱概率") })
        val terse = RelationshipAnalysis.analyze(listOf(row(1, "me", "出门吗？"), row(2, "other", "好")), "会话")
        assertFalse(terse.label.contains("冷淡"))
    }

    @Test fun reciprocalAffectionAndSupportProducesEvidenceWithLowConfidenceForShortSamples() {
        val records = listOf(row(1, "me", "我喜欢你，注意休息"), row(2, "other", "我也喜欢你，我会陪你"))
        val report = RelationshipAnalysis.analyze(records, "会话")
        assertEquals("双向亲近候选", report.label)
        assertTrue(report.confidence <= 40)
        assertTrue(score(report) > 20)
        assertTrue(report.findings.flatMap { it.evidence }.all { evidence -> records.any { it.id == evidence.messageId && it.message.text == evidence.quote } })
    }

    @Test fun boundaryIsNotDecodedAsHiddenAffection() {
        val report = RelationshipAnalysis.analyze(listOf(row(1, "me", "我喜欢你"), row(2, "other", "我不喜欢你，我们只是朋友")), "会话")
        assertEquals("边界表达需要尊重", report.label)
        assertTrue(report.summary.contains("优先按原话理解"))
        assertTrue(report.findings.single { it.label == "亲近与具体安排" }.detail.contains("对方 0 条"))
        assertFalse(report.suggestions.any { it.contains("追求") })
    }

    @Test fun quotationsReportedSpeechQuestionsAndSarcasmDoNotBecomeRomanticEvidence() {
        listOf("她说我喜欢你", "电影台词：‘我爱你’", "引用：\"我喜欢你\"", "> 我喜欢你", "我喜欢你才怪", "我喜欢你，才怪", "你喜欢你自己吗？", "我不是不喜欢你", "如果我喜欢你呢").forEachIndexed { i, text ->
            val report = RelationshipAnalysis.analyze(listOf(row(i.toLong() + 1, "other", text)), "会话")
            assertEquals(text, 0, score(report))
        }
        val report = RelationshipAnalysis.analyze(listOf(row(1, "other", "我不难过，没有生气")), "会话")
        assertFalse(report.findings.any { it.label == "对方情绪表达" || it.label == "支持与倾诉" })
    }

    @Test fun differentKnownGroupMembersNeverBecomeOneRomanticCounterpart() {
        val records = listOf(row(1, "me", "我喜欢你"), row(2, "other", "我喜欢你", sender = "成员甲"),
            row(3, "other", "我会陪你", sender = "成员乙"))
        val report = RelationshipAnalysis.analyze(records, "会话")
        assertEquals("群体互动线索", report.label)
        assertEquals("不作单人关系评分", metric(report, "关系程度线索").value)
        assertEquals("未计算", metric(report, "异方消息间隔").value)
        assertTrue(report.limitations.any { it.contains("2 位不同群成员") })
        assertFalse(report.summary.contains("双向亲近"))
    }

    @Test fun exactSelectedConversationScopeExcludesOtherSessions() {
        val records = listOf(row(1, "me", "收到"), row(2, "other", "资料好了"),
            row(3, "other", "我喜欢你", group = "其他人"))
        val report = RelationshipAnalysis.analyze(records, "会话")
        assertEquals(2, report.messageCount)
        assertEquals(0, score(report))
        assertTrue(report.limitations.any { it.contains("已排除其他会话") })
        assertTrue(RelationshipAnalysis.keyWindows(records).isEmpty())
        assertEquals(0, RelationshipAnalysis.analyze(records, "").messageCount)
    }

    @Test fun equivalentCapturedTitlesDoNotDropValidRecordingMessages() {
        val records = listOf(row(1, "me", "收到", group = "同学群(30)"),
            row(2, "other", "资料好了", group = "同学群（31）"))
        val report = RelationshipAnalysis.analyze(records, "同学群(30)")
        assertEquals(2, report.messageCount)
        assertEquals(records, RelationshipAnalysis.keyWindows(records))
    }

    @Test fun punctuationAndInteriorSpacingDoNotMergeDifferentPeople() {
        listOf("A-B" to "AB", "张 三" to "张三", "AB" to "ab").forEach { (first, second) ->
            val records = listOf(row(1, "me", "收到", group = first), row(2, "other", "我喜欢你", group = second))
            val report = RelationshipAnalysis.analyze(records, first)
            assertEquals(1, report.messageCount)
            assertEquals(0, score(report))
            assertTrue(RelationshipAnalysis.keyWindows(records).isEmpty())
        }
    }

    @Test fun likingAnIdeaOrAskingForWorkIsNotPersonalAffection() {
        listOf("我喜欢你的方案", "我喜欢你这个设计", "我爱你们", "我想你明天发给我报告", "我喜欢你同学").forEach { text ->
            assertEquals(text, 0, score(RelationshipAnalysis.analyze(listOf(row(1, "other", text)), "会话")))
        }
        assertNotEquals("边界表达需要尊重", RelationshipAnalysis.analyze(listOf(row(1, "other", "我不喜欢你的方案")), "会话").label)
        val joking = RelationshipAnalysis.analyze(listOf(row(1, "other", "你骗我，逗你玩"),
            row(2, "me", "我讨厌你，开玩笑的")), "会话")
        assertFalse(joking.findings.any { it.label == "边界与分歧" })
    }

    @Test fun captureTimeAndTimeLabelsNeverBecomeReplyDurationsOrTrend() {
        val records = (1L..30L).map { id -> row(id, if (id % 2L == 0L) "me" else "other", "内容 $id", date = null,
            capturedAt = 1_800_000_000_000L + id * 30_000).let { it.copy(message = it.message.copy(timeLabel = "10:30")) } }
        val report = RelationshipAnalysis.analyze(records, "会话")
        assertEquals("日期未确认", metric(report, "记录日期").value)
        assertEquals("未计算", metric(report, "异方消息间隔").value)
        assertFalse(report.findings.any { it.label.contains("变化") })
        assertTrue(report.limitations.any { it.contains("不计算回复速度") })
    }

    @Test fun exactMessageTimeOrdersReverseCaptureIdsWithoutMutatingRecords() {
        val start = time("2026-09-01")
        val original = listOf(row(100, "other", "我理解你", timestamp = start + 90_000),
            row(101, "me", "我难过", timestamp = start))
        val before = original.toList()
        val report = RelationshipAnalysis.analyze(original, "会话")
        assertEquals("中位 1 分钟", metric(report, "异方消息间隔").value)
        assertEquals(listOf(101L, 100L), RelationshipAnalysis.keyWindows(original).map { it.id })
        assertEquals(before, original)
    }

    @Test fun duplicateIdsAreOneRecordAndRepeatedTextDoesNotRaiseAffectionScore() {
        val a = row(1, "me", "我喜欢你")
        val b = row(2, "other", "我喜欢你")
        val single = RelationshipAnalysis.analyze(listOf(a, b, b), "会话")
        val repeated = RelationshipAnalysis.analyze(listOf(a) + (2L..101L).map { b.copy(id = it) }, "会话")
        assertEquals(2, single.messageCount)
        assertEquals(score(single), score(repeated))
        assertTrue(repeated.confidence <= 42)
    }

    @Test fun timelineNeedsKnownDaysAndRejectsContradictoryMessageDates() {
        val records = (1L..30L).map { id -> row(id, if (id % 2L == 0L) "me" else "other", "内容 $id",
            date = "2026-09-${((id - 1) / 3 + 1).toString().padStart(2, '0')}") }
        assertTrue(RelationshipAnalysis.analyze(records.reversed(), "会话").findings.any { it.label.contains("变化") })
        val unknown = records.toMutableList().apply { this[5] = this[5].copy(message = this[5].message.copy(date = "最近")) }
        assertFalse(RelationshipAnalysis.analyze(unknown, "会话").findings.any { it.label.contains("变化") })
        val contradiction = listOf(row(1, "other", "收到", date = "2026-09-03", timestamp = time("2026-09-01")))
        assertEquals("日期未确认", metric(RelationshipAnalysis.analyze(contradiction, "会话"), "记录日期").value)
    }

    @Test fun contextualWindowsKeepConflictRepairNeighborhoodAndRespectLimit() {
        val records = (1L..120L).map { id -> row(id, if (id % 2L == 0L) "me" else "other",
            when (id) { 60L -> "你骗我"; 61L -> "对不起，我错了"; 62L -> "我理解你"; else -> "消息 $id" }) }
        val selected = RelationshipAnalysis.keyWindows(records, 40)
        assertEquals(40, selected.size)
        assertTrue(selected.map { it.id }.containsAll(listOf(1L, 120L, 59L, 60L, 61L, 62L)))
        assertEquals(selected.size, selected.map { it.id }.distinct().size)
        assertTrue(RelationshipAnalysis.keyWindows(records, 0).isEmpty())
    }

    @Test fun reportJsonRoundTripPreservesEvidenceAndNullDate() {
        val report = RelationshipAnalysis.analyze(listOf(row(1, "me", "我喜欢你", date = null),
            row(2, "other", "我喜欢你，我会陪你", date = null)), "会话")
        assertEquals(report, RelationshipReport.fromJson(report.toJson()))
        assertTrue(RelationshipReport.fromJson("{\"title\":\"会话\",\"confidence\":105}").confidence == 100)
    }
}
