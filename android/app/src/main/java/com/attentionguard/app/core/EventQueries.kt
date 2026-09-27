package com.attentionguard.app.core

import java.util.Calendar

enum class EventFilter(val label: String) { ALL("全部"), ACTION("待处理"), FOLLOWING("关注中"), COMPLETED("已完成"), ARCHIVED("归档") }

enum class EventDateFilter(val label: String, private val days: Int?) {
    ALL("不限时间", null),
    TODAY("当天", 1),
    LAST_3_DAYS("近3天", 3),
    LAST_7_DAYS("近一周", 7);

    fun matches(timestamp: Long?, now: Long = System.currentTimeMillis()): Boolean {
        if (days == null) return true
        val captured = timestamp ?: return false
        val today = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = today.timeInMillis - (days - 1) * DAY_MILLIS
        return captured in start..now
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    }
}

enum class EventPriorityFilter(val label: String, val priority: EventPriority?) {
    ALL("全部重要性", null),
    P0("P0", EventPriority.P0),
    P1("P1", EventPriority.P1),
    P2("P2", EventPriority.P2),
    P3("P3", EventPriority.P3);

    fun matches(value: EventPriority): Boolean = priority == null || priority == value
}

fun AttentionEvent.needsAction(): Boolean = !archived && status != EventStatus.COMPLETED &&
    (status == EventStatus.ACTION_REQUIRED || priority == EventPriority.P0)

fun AttentionEvent.withArchive(archive: Boolean): AttentionEvent = if (archived == archive) this else copy(archived = archive)

fun AttentionEvent.withCompletion(completed: Boolean): AttentionEvent = when {
    completed && status != EventStatus.COMPLETED -> copy(status = EventStatus.COMPLETED, previousStatus = status)
    !completed && status == EventStatus.COMPLETED -> copy(status = previousStatus ?: EventStatus.ACTION_REQUIRED, previousStatus = null)
    else -> this
}

fun filterEvents(
    events: List<AttentionEvent>,
    filter: EventFilter,
    query: String,
    dateFilter: EventDateFilter = EventDateFilter.ALL,
    priorityFilter: EventPriorityFilter = EventPriorityFilter.ALL,
    now: Long = System.currentTimeMillis()
): List<AttentionEvent> {
    val search = query.trim()
    return events.filter { event ->
        val matchesFilter = when (filter) {
            EventFilter.ALL -> !event.archived
            EventFilter.ACTION -> event.needsAction()
            EventFilter.FOLLOWING -> !event.archived && !event.needsAction() && event.status != EventStatus.COMPLETED
            EventFilter.COMPLETED -> !event.archived && event.status == EventStatus.COMPLETED
            EventFilter.ARCHIVED -> event.archived
        }
        matchesFilter &&
            dateFilter.matches(event.sourceCapturedAt, now) &&
            priorityFilter.matches(event.priority) &&
            (search.isBlank() || listOf(event.title, event.summary, event.sourceGroup, event.sourcePerson)
                .any { it.contains(search, ignoreCase = true) })
    }.sortedWith(
        compareBy<AttentionEvent> { it.status == EventStatus.COMPLETED }
            .thenBy { it.priority.ordinal }
            .thenByDescending { it.sourceCapturedAt ?: Long.MIN_VALUE }
    )
}
