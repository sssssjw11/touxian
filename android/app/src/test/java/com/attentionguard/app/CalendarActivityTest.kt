package com.attentionguard.app

import android.content.Intent
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.attentionguard.app.calendar.CalendarWriter
import com.attentionguard.app.core.DemoAttentionData
import com.attentionguard.app.core.EventStore
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CalendarActivityTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun previewDoesNotRequestPermissionsOrWriteAndRequiresCalendarSelection() {
        context.getSharedPreferences(CalendarWriter.DEFAULT_PREFS, 0).edit().clear().commit()
        val event = DemoAttentionData.events.first().copy(id = "calendar-preview", dueLabel = LocalDate.now().plusDays(3).toString())
        EventStore(context).upsert(event)
        val controller = Robolectric.buildActivity(CalendarActivity::class.java,
            Intent(context, CalendarActivity::class.java).putExtra(CalendarActivity.EXTRA_EVENT_ID, event.id)).setup()
        try {
            val activity = controller.get()
            assertFalse(activity.findViewById<SwitchCompat>(R.id.ag_calendar_all_day).isChecked)
            assertEquals("10:00", activity.findViewById<MaterialButton>(R.id.ag_calendar_time).text.toString())
            assertEquals("提醒 · 开始时", activity.findViewById<MaterialButton>(R.id.ag_calendar_reminder).text.toString())
            assertEquals("选择日历", activity.findViewById<MaterialButton>(R.id.ag_calendar_target).text.toString())
            activity.findViewById<MaterialButton>(R.id.ag_calendar_confirm).performClick()
            assertEquals("请先选择目标日历，再确认写入。", activity.findViewById<TextView>(R.id.ag_calendar_status).text.toString())
            assertTrue(context.getSharedPreferences("attention_guard_calendar_links", 0).all.isEmpty())
            assertEquals(listOf(event), EventStore(context).load())
            activity.findViewById<TextInputEditText>(R.id.ag_calendar_title).setText("User title")
            controller.recreate()
            assertEquals("User title", controller.get().findViewById<TextInputEditText>(R.id.ag_calendar_title).text.toString())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun ambiguousDeadlineStaysUnselectedAndBackCancelsWithoutWriting() {
        context.getSharedPreferences(CalendarWriter.DEFAULT_PREFS, 0).edit().clear().commit()
        val event = DemoAttentionData.events.first().copy(id = "calendar-missing", dueLabel = "明天 · 日期待核对")
        EventStore(context).upsert(event)
        val controller = Robolectric.buildActivity(CalendarActivity::class.java,
            Intent(context, CalendarActivity::class.java).putExtra(CalendarActivity.EXTRA_EVENT_ID, event.id)).setup()
        try {
            val activity = controller.get()
            assertEquals("选择日期", activity.findViewById<MaterialButton>(R.id.ag_calendar_date).text.toString())
            activity.findViewById<MaterialButton>(R.id.ag_calendar_confirm).performClick()
            assertEquals("请选择日期", activity.findViewById<TextView>(R.id.ag_calendar_status).text.toString())
            activity.onBackPressedDispatcher.onBackPressed()
            assertTrue(activity.isFinishing)
            assertEquals(listOf(event), EventStore(context).load())
        } finally { controller.pause().stop().destroy() }
    }
}
