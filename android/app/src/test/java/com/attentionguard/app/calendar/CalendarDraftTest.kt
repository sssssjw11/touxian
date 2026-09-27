package com.attentionguard.app.calendar

import com.attentionguard.app.core.DemoAttentionData
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class CalendarDraftTest {
    private val event = DemoAttentionData.events.first().copy(id = "calendar-fixture")

    @Test fun resolvedDeadlinePrefillsButAmbiguousOrInvalidDatesRequireManualSelection() {
        val resolved = CalendarDraft.from(event.copy(dueLabel = "2026-10-01 17:00"))
        assertEquals(LocalDate.of(2026, 10, 1), resolved.day)
        assertEquals(LocalTime.of(17, 0), resolved.time)
        assertFalse(resolved.allDay)
        for (label in listOf(null, "明天", "26-20 8:30", "2026-02-30", "2026-10-01 24:00", "时间待核对")) {
            val draft = CalendarDraft.from(event.copy(dueLabel = label))
            assertNull(label, draft.day)
            assertThrows(IllegalArgumentException::class.java) { draft.interval() }
        }
    }

    @Test fun monthDayDeadlineIsNormalizedAndDateOnlyUsesTen() {
        val draft = CalendarDraft.from(event.copy(dueLabel = "12月31日"))
        assertEquals(LocalDate.of(LocalDate.now().year, 12, 31), draft.day)
        assertEquals(LocalTime.of(10, 0), draft.time)
        assertEquals(0, draft.reminderMinutes)
        assertFalse(draft.allDay)
    }

    @Test fun dateOnlyDefaultsToTenAndStartReminder() {
        val draft = CalendarDraft.from(event.copy(dueLabel = "2026-10-01")).copy(zone = ZoneId.of("America/Los_Angeles"))
        assertFalse(draft.allDay)
        assertEquals(LocalTime.of(10, 0), draft.time)
        assertEquals(0, draft.reminderMinutes)
        val (start, end) = draft.interval()
        assertEquals(LocalDate.of(2026, 10, 1).atTime(10, 0)
            .atZone(ZoneId.of("America/Los_Angeles")).toInstant().toEpochMilli(), start)
        assertEquals(1_800_000L, end - start)
    }

    @Test fun timedDraftKeepsUserTimezoneAndRejectsAmbiguousDstTimes() {
        val draft = CalendarDraft.from(event.copy(dueLabel = "2026-10-01 17:00")).copy(zone = ZoneId.of("Asia/Shanghai"))
        val (start, end) = draft.interval()
        assertEquals(draft.day!!.atTime(17, 0).atZone(draft.zone).toInstant().toEpochMilli(), start)
        assertEquals(1_800_000L, end - start)
        assertThrows(IllegalArgumentException::class.java) {
            draft.copy(day = LocalDate.of(2026, 3, 8), time = LocalTime.of(2, 30), zone = ZoneId.of("America/New_York")).interval()
        }
    }

    @Test fun stableMarkerDoesNotIncludeMessageTextAndDemoIsDistinct() {
        val draft = CalendarDraft.from(event)
        assertEquals(draft.marker, CalendarDraft.from(event.copy(title = "Changed", dueLabel = "2026-10-01")).marker)
        assertNotEquals(draft.marker, CalendarDraft.from(event, demo = true).marker)
        assertFalse(draft.marker.contains(event.title))
        assertTrue(draft.description.contains(event.sourceGroup))
        assertFalse(draft.description.contains(event.evidence.joinToString("\n")))
    }
}
