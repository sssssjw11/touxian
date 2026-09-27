package com.attentionguard.app.calendar

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import com.attentionguard.app.core.DemoAttentionData
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CalendarWriterTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var provider: FakeCalendarProvider
    private lateinit var writer: CalendarWriter
    private val draft = CalendarDraft.from(DemoAttentionData.events.first().copy(id = "calendar-fixture", dueLabel = "2026-10-01 17:00"))

    @Before fun setup() {
        context.getSharedPreferences(CalendarWriter.DEFAULT_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        provider = FakeCalendarProvider().apply {
            attachInfo(context, ProviderInfo().apply { authority = CalendarContract.AUTHORITY })
        }
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        shadowOf(context).grantPermissions(*CalendarWriter.PERMISSIONS)
        writer = CalendarWriter(context)
    }

    @Test fun selectedCalendarIsRememberedForTheNextConfirmation() {
        val choice = writer.calendars().single()
        assertNull(writer.preferredCalendar())
        writer.rememberCalendar(choice)
        assertEquals(CalendarWriter.PreferredCalendar(8L, choice.label), writer.preferredCalendar())
    }

    @Test fun listingDoesNotWriteAndConfirmWritesOneEventAndOneLinkedReminder() {
        assertEquals(listOf(8L), writer.calendars().map { it.id })
        assertTrue(provider.events.isEmpty())
        val receipt = writer.confirm(draft, 8)
        assertFalse(receipt.existed)
        val row = provider.events.single()
        assertEquals(draft.title, row.getAsString(Events.TITLE))
        assertEquals(8L, row.getAsLong(Events.CALENDAR_ID))
        assertTrue(row.getAsString(Events.DESCRIPTION).contains(draft.marker))
        assertEquals(draft.interval().first, row.getAsLong(Events.DTSTART))
        assertEquals(receipt.id, provider.reminders.single().getAsLong(Reminders.EVENT_ID))
        assertEquals(0, provider.reminders.single().getAsInteger(Reminders.MINUTES))
    }

    @Test fun repeatAndRetryAfterLostLocalReceiptNeverDuplicate() {
        val first = writer.confirm(draft, 8)
        assertTrue(writer.confirm(draft, 8).existed)
        context.getSharedPreferences("attention_guard_calendar_links", Context.MODE_PRIVATE).edit().clear().commit()
        val recovered = CalendarWriter(context).confirm(draft.copy(title = "Edited preview"), 8)
        assertTrue(recovered.existed)
        assertEquals(first.id, recovered.id)
        assertEquals(1, provider.events.size)
        assertEquals(1, provider.reminders.size)
        assertEquals(draft.title, provider.events.single().getAsString(Events.TITLE))
    }

    @Test fun deletedCalendarEventCanBeReaddedAndOtherEventsAreNotTouched() {
        val first = writer.confirm(draft, 8)
        provider.events.single().put(Events.DELETED, 1)
        val second = writer.confirm(draft, 8)
        assertFalse(second.existed)
        assertNotEquals(first.id, second.id)
        assertEquals(2, provider.events.size)
        assertEquals(1, provider.events.first().getAsInteger(Events.DELETED))
    }

    @Test fun deniedPermissionOrMissingCalendarCannotWrite() {
        shadowOf(context).denyPermissions(*CalendarWriter.PERMISSIONS)
        assertThrows(SecurityException::class.java) { writer.confirm(draft, 8) }
        assertTrue(provider.events.isEmpty())
        shadowOf(context).grantPermissions(*CalendarWriter.PERMISSIONS)
        assertThrows(IllegalStateException::class.java) { writer.confirm(draft, 99) }
        assertTrue(provider.events.isEmpty())
    }

    @Test fun explicitAllDayWithoutReminderUsesUtcAndNoReminderRow() {
        val allDay = CalendarDraft.from(DemoAttentionData.events.first().copy(id = "all-day", dueLabel = "2026-10-01"))
            .copy(allDay = true, reminderMinutes = null)
        writer.confirm(allDay, 8)
        val row = provider.events.single()
        assertEquals(1, row.getAsInteger(Events.ALL_DAY))
        assertEquals("UTC", row.getAsString(Events.EVENT_TIMEZONE))
        assertEquals(0, row.getAsInteger(Events.HAS_ALARM))
        assertTrue(provider.reminders.isEmpty())
    }

    class FakeCalendarProvider : ContentProvider() {
        val events = mutableListOf<ContentValues>()
        val reminders = mutableListOf<ContentValues>()
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor {
            val columns = requireNotNull(projection)
            val cursor = MatrixCursor(columns)
            if (uri.pathSegments.first() == "calendars") {
                val row = mapOf(Calendars._ID to 8L, Calendars.CALENDAR_DISPLAY_NAME to "Test calendar",
                    Calendars.ACCOUNT_NAME to "Local", Calendars.ACCOUNT_TYPE to CalendarContract.ACCOUNT_TYPE_LOCAL,
                    Calendars.MAX_REMINDERS to 5, Calendars.ALLOWED_REMINDERS to "0,1")
                cursor.addRow(columns.map { row[it] }.toTypedArray())
            } else {
                val id = uri.lastPathSegment?.toLongOrNull()
                val marker = args?.firstOrNull()?.takeIf { it.startsWith("%") }?.trim('%')
                events.filter { it.getAsInteger(Events.DELETED) != 1 }
                    .filter { row -> if (id != null) row.getAsLong(Events._ID) == id
                        else marker != null && row.getAsString(Events.DESCRIPTION).contains(marker) }
                    .forEach { row -> cursor.addRow(columns.map { row[it] }.toTypedArray()) }
            }
            return cursor
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val rows = if (uri.pathSegments.first() == "events") events else reminders
            val row = ContentValues(requireNotNull(values))
            val id = rows.size + 1L
            row.put("_id", id)
            rows.add(row)
            return ContentUris.withAppendedId(uri, id)
        }
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = error("Must not delete calendar records")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("Must not update calendar records")
    }
}
