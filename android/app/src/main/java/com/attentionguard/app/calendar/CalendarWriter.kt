package com.attentionguard.app.calendar

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/** Explicit-confirmation writer. No service, listener, periodic sync, or bulk export. */
class CalendarWriter(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val links = app.getSharedPreferences("attention_guard_calendar_links", Context.MODE_PRIVATE)
    private val defaults = app.getSharedPreferences(DEFAULT_PREFS, Context.MODE_PRIVATE)

    data class CalendarChoice(val id: Long, val name: String, val account: String,
                              val local: Boolean, val canRemind: Boolean) {
        val label get() = "$name · ${if (local) "本机" else account}"
    }
    data class PreferredCalendar(val id: Long, val label: String)
    data class Receipt(val id: Long, val existed: Boolean)

    fun preferredCalendar(): PreferredCalendar? {
        val id = defaults.getLong(KEY_DEFAULT_ID, -1L)
        if (id < 0L) return null
        return PreferredCalendar(id, defaults.getString(KEY_DEFAULT_LABEL, "").orEmpty())
    }

    fun rememberCalendar(choice: CalendarChoice) {
        defaults.edit()
            .putLong(KEY_DEFAULT_ID, choice.id)
            .putString(KEY_DEFAULT_LABEL, choice.label)
            .apply()
    }

    fun hasPermission(): Boolean = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED
    }

    fun calendars(): List<CalendarChoice> {
        checkPermission()
        val fields = arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME,
            Calendars.ACCOUNT_TYPE, Calendars.MAX_REMINDERS, Calendars.ALLOWED_REMINDERS)
        val cursor = resolver.query(Calendars.CONTENT_URI, fields,
            "${Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${Calendars.VISIBLE} = ?",
            arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString(), "1"), null)
            ?: error("无法读取日历，请重试")
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val methods = it.getString(5).orEmpty().split(",").map(String::trim)
                    add(CalendarChoice(it.getLong(0), it.getString(1).orEmpty().ifBlank { "未命名日历" },
                        it.getString(2).orEmpty(), it.getString(3) == CalendarContract.ACCOUNT_TYPE_LOCAL,
                        it.getInt(4) > 0 && Reminders.METHOD_ALERT.toString() in methods))
                }
            }.sortedWith(compareByDescending<CalendarChoice> { it.local }.thenBy { it.name })
        }
    }

    fun confirm(draft: CalendarDraft, calendarId: Long): Receipt = synchronized(LOCK) {
        checkPermission()
        val interval = draft.interval()
        require(draft.reminderMinutes == null || draft.reminderMinutes in setOf(0, 15, 60)) { "无效提醒时间" }
        existing(draft)?.let { return@synchronized Receipt(it, true) }
        val calendar = calendars().firstOrNull { it.id == calendarId } ?: error("目标日历已不可写，请重新选择")
        check(draft.reminderMinutes == null || calendar.canRemind) { "该日历不支持提醒，请选择“不提醒”" }
        val operations = arrayListOf(ContentProviderOperation.newInsert(Events.CONTENT_URI)
            .withValue(Events.CALENDAR_ID, calendarId)
            .withValue(Events.TITLE, draft.title.trim())
            .withValue(Events.DESCRIPTION, "${draft.description}\n${draft.marker}")
            .withValue(Events.DTSTART, interval.first)
            .withValue(Events.DTEND, interval.second)
            .withValue(Events.ALL_DAY, if (draft.allDay) 1 else 0)
            .withValue(Events.EVENT_TIMEZONE, if (draft.allDay) "UTC" else draft.zone.id)
            .withValue(Events.HAS_ALARM, if (draft.reminderMinutes == null) 0 else 1)
            .build())
        draft.reminderMinutes?.let { minutes ->
            operations.add(ContentProviderOperation.newInsert(Reminders.CONTENT_URI)
                .withValueBackReference(Reminders.EVENT_ID, 0)
                .withValue(Reminders.MINUTES, minutes)
                .withValue(Reminders.METHOD, Reminders.METHOD_ALERT)
                .build())
        }
        // Provider transaction: the reminder and event succeed or fail together.
        val results = resolver.applyBatch(CalendarContract.AUTHORITY, operations)
        val id = ContentUris.parseId(requireNotNull(results.firstOrNull()?.uri) { "日历未返回写入结果，请重试核对" })
        links.edit().putLong(draft.eventKey, id).commit()
        Receipt(id, false)
    }

    private fun existing(draft: CalendarDraft): Long? {
        val known = links.getLong(draft.eventKey, -1)
        val projection = arrayOf(Events._ID)
        if (known >= 0) {
            val cursor = resolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, known), projection,
                "${Events.DELETED} = ?", arrayOf("0"), null) ?: error("无法核对已写入事项，请重试")
            cursor.use { if (it.moveToFirst()) return it.getLong(0) }
        }
        // A marker makes retry safe even if the process died after provider commit
        // but before the local link was persisted. Never matches unrelated titles.
        val cursor = resolver.query(Events.CONTENT_URI, projection,
            "${Events.DESCRIPTION} LIKE ? AND ${Events.DELETED} = ?",
            arrayOf("%${draft.marker}%", "0"), null) ?: error("无法核对重复事项，请重试")
        return cursor.use { if (it.moveToFirst()) it.getLong(0) else null }?.also {
            links.edit().putLong(draft.eventKey, it).commit()
        }
    }

    private fun checkPermission() {
        if (!hasPermission()) throw SecurityException("需要允许日历权限")
    }

    companion object {
        val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        const val DEFAULT_PREFS = "attention_guard_calendar_defaults"
        const val KEY_DEFAULT_ID = "calendar_id"
        const val KEY_DEFAULT_LABEL = "calendar_label"
        // Shared across activity recreation; the lock + marker prevents double-tap writes.
        val worker = Executors.newSingleThreadExecutor()
        private val LOCK = Any()
    }
}
