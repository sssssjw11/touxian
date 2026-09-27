package com.attentionguard.app.calendar

import com.attentionguard.app.core.AttentionEvent
import com.attentionguard.app.core.DeadlineParser
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.security.MessageDigest

/** A user-reviewed draft, not a background scheduling rule. */
data class CalendarDraft(
    val eventKey: String,
    val title: String,
    val description: String,
    val day: LocalDate?,
    val time: LocalTime?,
    val allDay: Boolean,
    val zone: ZoneId = ZoneId.systemDefault(),
    val reminderMinutes: Int? = 0
) {
    fun interval(): Pair<Long, Long> {
        val date = requireNotNull(day) { "请选择日期" }
        require(title.isNotBlank()) { "请填写待办标题" }
        if (allDay) return date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() to
            date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val local = date.atTime(requireNotNull(time) { "请选择时间，或开启全天" })
        require(zone.rules.getValidOffsets(local).size == 1) { "所选时间处于夏令时切换区间，请重新选择" }
        val start = local.atZone(zone).toInstant().toEpochMilli()
        return start to (start + 30 * 60_000L)
    }

    val marker: String get() {
        val hash = MessageDigest.getInstance("SHA-256").digest(eventKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "[AttentionGuard:$hash]"
    }

    companion object {
        private val DEFAULT_TIME = LocalTime.of(10, 0)

        // Only an absolute date returned by the local deadline parser may prefill
        // the draft. Relative labels such as "明天" still require review.
        fun from(event: AttentionEvent, demo: Boolean = false): CalendarDraft {
            val label = event.dueLabel.orEmpty()
            val normalized = DeadlineParser.parse(label, null, LocalDate.now()).label
            val parts = Regex("^(\\d{4}-\\d{2}-\\d{2})(?: (\\d{2}:\\d{2}))?$").matchEntire(normalized.orEmpty())
            val day = parts?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val clock = parts?.groupValues?.get(2)?.takeIf { it.isNotBlank() }?.let {
                runCatching {
                    LocalTime.parse(
                        it,
                        DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT)
                    )
                }.getOrNull()
            }
            val valid = day != null && (parts!!.groupValues[2].isBlank() || clock != null)
            return CalendarDraft(
                eventKey = (if (demo) "demo:" else "") + event.id,
                title = (if (demo) "示例 · " else "") + event.title,
                description = "由偷闲手动确认添加\n来源：${if (demo) "示例数据" else event.captureOrigin.label}\n会话：${event.sourceGroup}\n发送者：${event.sourcePerson}",
                day = day.takeIf { valid },
                // A recognized date without a clock is a timed task by default.
                // The user can still switch it to an all-day event in the preview.
                time = if (valid) clock ?: DEFAULT_TIME else null,
                allDay = false,
                reminderMinutes = 0
            )
        }
    }
}
