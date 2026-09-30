package com.attentionguard.app.core

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class EventQueriesTest {
    private val events = DemoAttentionData.events
    @Test fun completionRestoresMonitoringInsteadOfInventingAnAction() {
        val original = events.first { it.status == EventStatus.MONITORING }
        val completed = original.withCompletion(true)
        assertEquals(EventStatus.COMPLETED, completed.status)
        assertEquals(original.status, completed.withCompletion(false).status)
    }
    @Test fun completionIsIdempotent() {
        val completed = events[0].withCompletion(true)
        assertEquals(completed, completed.withCompletion(true))
        assertEquals(events[0], completed.withCompletion(false))
    }
    @Test fun completedP0IsNeverActionable() {
        val completed = events[0].withCompletion(true)
        assertFalse(completed.needsAction())
        assertTrue(filterEvents(listOf(completed), EventFilter.ACTION, "").isEmpty())
        assertEquals(1, filterEvents(listOf(completed), EventFilter.COMPLETED, "").size)
    }
    @Test fun archiveIsReversibleAndIndependentOfCompletion() {
        val completed = events[0].withCompletion(true)
        val archived = completed.withArchive(true)
        assertEquals(EventStatus.COMPLETED, archived.status)
        assertFalse(archived.needsAction())
        assertTrue(filterEvents(listOf(archived), EventFilter.ALL, "").isEmpty())
        assertTrue(filterEvents(listOf(archived), EventFilter.COMPLETED, "").isEmpty())
        assertEquals(listOf(archived), filterEvents(listOf(archived), EventFilter.ARCHIVED, ""))
        assertEquals(completed, archived.withArchive(false))
        val stats = attentionStatsFrom(listOf(events[0], archived))
        assertEquals(1, stats.observed)
        assertEquals(1, stats.actionRequired)
        assertEquals(0, stats.completed)
    }
    @Test fun searchUsesGroupAndTitleAndTrimmedQuery() {
        assertEquals(1, filterEvents(events, EventFilter.ALL, "  双选会 ").size)
        assertEquals(1, filterEvents(events, EventFilter.ALL, "计算机网络课程群").size)
        assertTrue(filterEvents(events, EventFilter.ALL, "不存在的事件").isEmpty())
    }
    @Test fun emptyAndFollowingFiltersAreConsistent() {
        assertTrue(filterEvents(emptyList(), EventFilter.ALL, "").isEmpty())
        assertEquals(3, filterEvents(events, EventFilter.FOLLOWING, "").size)
        assertEquals(1, filterEvents(events, EventFilter.ACTION, "").size)
    }
    @Test fun dateAndPriorityFiltersUseCaptureTimeAndKeepUnknownDatesOutOfDateBuckets() {
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val day = 24L * 60L * 60L * 1000L
        val today = events[0].copy(id = "today", priority = EventPriority.P0, sourceCapturedAt = now - 60 * 60 * 1000L)
        val recent = events[1].copy(id = "recent", priority = EventPriority.P1, sourceCapturedAt = now - 2 * day)
        val old = events[2].copy(id = "old", priority = EventPriority.P2, sourceCapturedAt = now - 8 * day)
        val unknown = events[3].copy(id = "unknown", priority = EventPriority.P3, sourceCapturedAt = null)
        val all = listOf(today, recent, old, unknown)
        assertEquals(listOf(today), filterEvents(all, EventFilter.ALL, "", EventDateFilter.TODAY, now = now))
        assertEquals(2, filterEvents(all, EventFilter.ALL, "", EventDateFilter.LAST_3_DAYS, now = now).size)
        assertEquals(2, filterEvents(all, EventFilter.ALL, "", EventDateFilter.LAST_7_DAYS, now = now).size)
        assertFalse(EventDateFilter.LAST_7_DAYS.matches(old.sourceCapturedAt, now))
        assertEquals(listOf(today), filterEvents(all, EventFilter.ALL, "", priorityFilter = EventPriorityFilter.P0, now = now))
        assertEquals(listOf(unknown), filterEvents(all, EventFilter.ALL, "", priorityFilter = EventPriorityFilter.P3, now = now))
        assertEquals(4, filterEvents(all, EventFilter.ALL, "", now = now).size)
    }
    @Test fun signatureIncludesConversationAndApplication() {
        val sample = ChatSnapshot("群 A", listOf(Msg("other", "同一条消息")), "wechat")
        assertNotEquals(sample.signature(), sample.copy(title = "群 B").signature())
        assertNotEquals(sample.signature(), sample.copy(sourcePackage = "other").signature())
        assertEquals(sample.signature(), sample.copy(capturedAt = 1).signature())
    }
}
