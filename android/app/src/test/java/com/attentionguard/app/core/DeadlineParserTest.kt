package com.attentionguard.app.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class DeadlineParserTest {
    private val today = LocalDate.of(2026, 9, 23)
    private fun parse(text: String, day: LocalDate? = today) = DeadlineParser.parse(text, day, today)

    @Test fun rejectsImpossibleDatesAndTimes() {
        listOf("39/30 12:00", "2026-02-30", "9月24日25:61").forEach { assertNull(parse(it).at) }
        assertEquals("时间待核对", DeadlineParser.displayLabel("39/30 12:00"))
        assertNull(parse("2026-2027学年，教室3-105，https://example.invalid/39/30").label)
    }
    @Test fun rangeUsesEndDateAndAssociatedClock() {
        val result = parse("报名时间：9 月 22 日 10:00 — 9 月 27 日 23:00")
        assertEquals("2026-09-27 23:00", result.label)
    }
    @Test fun relativeDatesRequireMessageDayNotObservationDay() {
        assertNull(parse("明天17:00前提交", null).at)
        assertEquals("2026-09-21 17:00", parse("明天17:00前提交", today.minusDays(3)).label)
    }
    @Test fun ambiguousDatesRequireReview() {
        assertNull(parse("9月24日通知，9月28日另行安排").at)
        assertEquals("2026-09-28 17:00", parse("9月24日通知，截止9月28日17:00").label)
    }
    @Test fun validatesLeapYearsAndChinesePeriods() {
        assertNull(parse("2026年2月29日").at)
        assertEquals("2028-02-29 18:30", parse("2028年2月29日晚上6:30").label)
    }

    @Test fun spokenClocksAndRedundantAfternoonPeriodAreRecognized() {
        assertEquals("2026-09-23 19:00", parse("今晚七点开班会").label)
        assertEquals("2026-09-23 19:30", parse("今晚7点半开班会").label)
        assertEquals("2026-09-23 13:30", parse("今天下午13:30截止").label)
        assertEquals("2026-09-23 17:00", parse("今晚五点前完成表格").label)
        assertEquals("2026-09-24 19:15", parse("明晚七点一刻开会").label)
        assertNull(parse("今晚七点开班会", null).at)
        assertNull(parse("今天七点开班会").at)
        assertNull(parse("今天下午二十五点").at)
    }

    @Test fun nearbyDayOnlyDeadlineUsesTheMessageMonth() {
        assertEquals("2026-09-28 17:00", parse("视频提交时间28号下午5点截止").label)
        assertEquals("2026-10-05 17:00", parse("视频提交时间5号下午5点截止").label)
    }

    @Test fun dayOnlyDeadlineCannotBorrowObservationMonthWhenMessageDayIsUnknown() {
        val result = parse("视频提交时间28号下午5点截止", null)
        assertNull(result.at)
        assertTrue(result.note.orEmpty().contains("缺少消息日期"))
    }

    @Test fun pastDeadlineStaysPastIncludingMonthBoundaries() {
        val day = LocalDate.of(2026, 9, 27)
        assertEquals("2026-09-25 17:00", DeadlineParser.parse("视频25号下午5点就已经截止了", day, day).label)
        assertEquals("2026-08-31 17:00", DeadlineParser.parse("31号下午5点已经截止了", day.withDayOfMonth(1), day).label)
        assertNull(DeadlineParser.parse("32号下午5点已经截止了", day, day).at)
    }
}
