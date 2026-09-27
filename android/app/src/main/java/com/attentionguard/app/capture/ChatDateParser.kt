package com.attentionguard.app.capture

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Whole separator labels only; never interprets a date inside a message body. */
object ChatDateParser {
    data class Stamp(val day: LocalDate, val epochMillis: Long?, val label: String)
    private val pattern = Regex("^(?:(今天|昨天|前天|星期[一二三四五六日天]|周[一二三四五六日天]|(?:\\d{4}年)?\\d{1,2}月\\d{1,2}日|\\d{4}[-/]\\d{1,2}[-/]\\d{1,2})\\s*)?(?:(凌晨|早上|上午|中午|下午|晚上)?\\s*(\\d{1,2})[:：](\\d{2}))?$")

    fun parse(label: String, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Stamp? {
        val text = label.trim()
        if (text.isEmpty()) return null
        val match = pattern.matchEntire(text) ?: return null
        val (datePart, period, hourPart, minutePart) = match.destructured
        if (datePart.isEmpty() && hourPart.isEmpty()) return null
        return runCatching {
            val day = when (datePart) {
                "", "今天" -> today
                "昨天" -> today.minusDays(1)
                "前天" -> today.minusDays(2)
                else -> {
                    if (datePart.startsWith("星期") || datePart.startsWith("周")) {
                        val ordinal = "一二三四五六日".indexOf(datePart.last().let { if (it == '天') '日' else it }) + 1
                        today.with(TemporalAdjusters.previousOrSame(DayOfWeek.of(ordinal)))
                    } else {
                        val parts = Regex("\\d+").findAll(datePart).map { it.value.toInt() }.toList()
                        if (parts.size == 3) LocalDate.of(parts[0], parts[1], parts[2])
                        else LocalDate.of(today.year, parts[0], parts[1]).let { if (it > today) it.minusYears(1) else it }
                    }
                }
            }
            val time = if (hourPart.isEmpty()) null else {
                var hour = hourPart.toInt()
                if (period.isNotEmpty()) require(hour in 0..12)
                if ((period in listOf("下午", "晚上") && hour < 12) || (period == "中午" && hour in 0..5)) hour += 12
                if (period in listOf("凌晨", "早上", "上午") && hour == 12) hour = 0
                LocalTime.of(hour, minutePart.toInt())
            }
            Stamp(day, time?.let { day.atTime(it).atZone(zone).toInstant().toEpochMilli() }, text)
        }.getOrNull()
    }
}
