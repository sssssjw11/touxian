package com.attentionguard.app.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Calendar-validated evidence, never a substring of a URL, year range or room number. */
object DeadlineParser {
    data class Result(val label: String?, val at: LocalDateTime? = null, val note: String? = null)
    private val url = Regex("https?://\\S+")
    private val date = Regex("(?<![\\d./-])(?:(\\d{4})\\s*[年/.-]\\s*)?(\\d{1,2})\\s*[月/.]\\s*(\\d{1,2})\\s*[日号]?(?![\\d/])|(?<![\\d/-])(\\d{4})-(\\d{1,2})-(\\d{1,2})(?!\\d)")
    private val dayOnly = Regex("(?<![\\d月])([0-3]?\\d)\\s*号")
    private const val NUMBER = "\\d{1,2}|[零〇一二两三四五六七八九十]{1,3}"
    private val time = Regex("(?<![\\d零〇一二两三四五六七八九十])(凌晨|早上|上午|中午|下午|晚上|今晚|今夜|明晚)?\\s*($NUMBER)(?:[:：](\\d{2})(?!\\d)|点(?:(半|一刻|三刻)|($NUMBER)分?)?)")
    private val relative = Regex("今天|今日|今晚|今夜|明天|明日|明晚|后天|后日")
    private val deadline = Regex("截止|截至|最晚|不晚于|之前|前提交|前完成|前报名|前交|前发|前回复")
    private val pastDeadline = Regex("(?:早已|已经|已)(?:经)?(?:截止|结束|过期)|(?:截止|结束|过期)了")

    fun parse(text: String, messageDay: LocalDate?, today: LocalDate): Result {
        val clean = text.replace(url, "")
        val matches = date.findAll(clean).toList()
        val chosen = when {
            matches.size <= 1 -> matches.firstOrNull()
            else -> {
                val marker = Regex("截止|截至|最晚|不晚于").find(clean)
                val after = marker?.let { m -> matches.firstOrNull { it.range.first >= m.range.last && it.range.first - m.range.last < 16 } }
                val bridge = clean.substring(matches.first().range.last + 1, matches.last().range.first)
                    after ?: if (Regex("至|到|[—~～]").containsMatchIn(bridge)) matches.last() else
                    return Result("时间待核对", note = "同一通知存在多个日期，未自动认定截止时间")
            }
        }
        // Campus notices often omit the month when the deadline is close:
        // “视频提交时间28号下午5点截止”. Resolve that day against the
        // message day without guessing a date for ordinary room numbers.
        val chosenDayOnly = if (chosen == null && deadline.containsMatchIn(clean)) dayOnly.find(clean) else null
        var day: LocalDate? = null
        var label: String? = null
        if (chosen != null) {
            val g = chosen.groupValues
            day = runCatching {
                if (g[4].isNotEmpty()) LocalDate.of(g[4].toInt(), g[5].toInt(), g[6].toInt())
                else LocalDate.of(g[1].toIntOrNull() ?: (messageDay ?: today).year, g[2].toInt(), g[3].toInt())
            }.getOrNull() ?: return Result("时间待核对", note = "日期不合法，未用于优先级升级")
            label = day.toString()
        } else if (chosenDayOnly != null) {
            val dayValue = chosenDayOnly.groupValues[1].toIntOrNull()
                ?: return Result("时间待核对", note = "日期不合法，未用于优先级升级")
            val base = messageDay ?: return Result("${chosenDayOnly.value} · 日期待核对",
                note = "仅有日号且缺少消息日期，未按观察日猜测月份")
            val thisMonth = runCatching { base.withDayOfMonth(dayValue) }.getOrNull()
            val past = pastDeadline.containsMatchIn(clean)
            day = when {
                thisMonth != null && (if (past) !thisMonth.isAfter(base) else !thisMonth.isBefore(base)) -> thisMonth
                else -> runCatching { base.plusMonths(if (past) -1 else 1).withDayOfMonth(dayValue) }.getOrNull()
            } ?: return Result("时间待核对", note = "日期不合法，未用于优先级升级")
            label = day.toString()
        } else {
            relative.find(clean)?.let { match ->
                val days = when (match.value) {
                    "今天", "今日", "今晚", "今夜" -> 0L
                    "明天", "明日", "明晚" -> 1L
                    else -> 2L
                }
                if (messageDay == null) return Result("${match.value} · 日期待核对", note = "相对日期缺少消息日期，未按今天推算")
                day = messageDay.plusDays(days)
                label = day.toString()
            }
        }
        val timeText = if (chosen == null) clean else clean.substring(chosen.range.last + 1).take(28)
        val timeMatch = time.find(timeText)
        val clock = timeMatch?.let { match ->
            runCatching {
                var h = chineseNumber(match.groupValues[2])
                val period = match.groupValues[1]
                require(h in 0..23)
                if (period in listOf("上午", "早上", "凌晨")) require(h <= 12)
                if (period in listOf("下午", "晚上", "今晚", "今夜", "明晚") && h < 12) h += 12
                if (period in listOf("上午", "早上", "凌晨") && h == 12) h = 0
                if (period == "中午" && h in 1..5) h += 12
                if (period.isEmpty() && h in 1..11 && match.groupValues[3].isEmpty())
                    return Result("时间待核对", note = "未说明上午或下午，不推测具体时刻")
                val minute = when {
                    match.groupValues[3].isNotEmpty() -> match.groupValues[3].toInt()
                    match.groupValues[5].isNotEmpty() -> chineseNumber(match.groupValues[5])
                    else -> mapOf("半" to 30, "一刻" to 15, "三刻" to 45)[match.groupValues[4]] ?: 0
                }
                LocalTime.of(h, minute)
            }.getOrNull() ?: return Result("时间待核对", note = "时间不合法，未用于优先级升级")
        }
        if (day == null) return if (deadline.containsMatchIn(clean) || clock != null)
            Result("截止日期待核对", note = "未确认完整截止日期") else Result(null)
        return Result(label + (clock?.let { " $it" } ?: ""), day!!.atTime(clock ?: LocalTime.of(23, 59)))
    }

    fun displayLabel(label: String?): String? {
        if (label == null) return null
        val parsed = parse(label, null, LocalDate.now())
        return if (parsed.note?.contains("不合法") == true) "时间待核对" else label
    }

    private fun chineseNumber(value: String): Int {
        if (value.all { it.isDigit() }) return value.toInt()
        val digits = mapOf(
            '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3,
            '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
        )
        if (value == "十") return 10
        if (value.startsWith("十")) return 10 + (digits[value.getOrNull(1)] ?: 0)
        if (value.endsWith("十")) return (digits[value.firstOrNull()] ?: 1) * 10
        val ten = value.indexOf('十')
        if (ten >= 0) return (digits[value.firstOrNull()] ?: 0) * 10 + (digits[value.getOrNull(ten + 1)] ?: 0)
        require(value.length == 1)
        return requireNotNull(digits[value.single()])
    }
}
