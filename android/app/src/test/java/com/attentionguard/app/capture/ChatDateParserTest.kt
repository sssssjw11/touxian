package com.attentionguard.app.capture

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ChatDateParserTest {
    private val today = LocalDate.of(2026, 9, 22)
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun parse(text: String) = ChatDateParser.parse(text, today, zone)
    @Test fun relativeDaysAndAfternoonAreNotMistakenForToday() {
        val value = parse("昨天 下午3:25")!!
        assertEquals(today.minusDays(1), value.day)
        assertEquals(today.minusDays(1).atTime(15, 25).atZone(zone).toInstant().toEpochMilli(), value.epochMillis)
        assertEquals(today.minusDays(2), parse("前天")!!.day)
    }
    @Test fun absoluteDatesAndWeekdaysAreAccepted() {
        assertEquals(LocalDate.of(2025, 12, 30), parse("2025年12月30日 09:00")!!.day)
        assertEquals(LocalDate.of(2026, 9, 18), parse("2026-09-18 09:00")!!.day)
        assertEquals(today.minusDays(1), parse("星期一 09:00")!!.day)
        assertEquals(today.minusDays(2), parse("周日 09:00")!!.day)
    }
    @Test fun bareClockUsesTodayAndYearlessDecemberUsesPastYear() {
        assertEquals(today, parse("09:05")!!.day)
        assertEquals(LocalDate.of(2025, 12, 30), parse("12月30日 09:00")!!.day)
        assertNull(parse("今天")!!.epochMillis)
    }
    @Test fun noonAndMidnightAreHandled() {
        assertEquals(today.atTime(11, 30).atZone(zone).toInstant().toEpochMilli(), parse("中午11:30")!!.epochMillis)
        assertEquals(today.atTime(0, 10).atZone(zone).toInstant().toEpochMilli(), parse("凌晨12:10")!!.epochMillis)
    }
    @Test fun bodyDatesAndInvalidClockAreNotSeparators() {
        listOf("请在昨天 14:00 前提交", "Meeting 09:30", "", "26:71", "昨天 24:01", "2026年2月30日 10:00", "下午18:00").forEach { assertNull(it, parse(it)) }
    }
}
