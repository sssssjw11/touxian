package com.attentionguard.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ContentUris
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.attentionguard.app.calendar.CalendarDraft
import com.attentionguard.app.calendar.CalendarWriter
import com.attentionguard.app.core.DemoAttentionData
import com.attentionguard.app.core.EventStore
import com.attentionguard.app.ui.GuardMotion
import com.attentionguard.app.ui.GuardUi
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import java.time.LocalDate
import java.time.LocalTime

/** Only this foreground confirmation screen can call CalendarWriter.confirm. */
class CalendarActivity : AppCompatActivity() {
    private lateinit var ui: GuardUi
    private lateinit var writer: CalendarWriter
    private lateinit var draft: CalendarDraft
    private lateinit var root: FrameLayout
    private lateinit var titleInput: TextInputEditText
    private lateinit var dateButton: MaterialButton
    private lateinit var timeButton: MaterialButton
    private lateinit var calendarButton: MaterialButton
    private lateinit var reminderButton: MaterialButton
    private lateinit var confirmButton: MaterialButton
    private lateinit var status: TextView
    private val editingControls = mutableListOf<View>()
    private var calendarId = -1L
    private var calendarLabel = ""
    private var receiptId = -1L
    private var saving = false
    private var loading = false

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (writer.hasPermission()) loadCalendars()
        else {
            status.text = "未获日历权限，没有写入任何事项。可再次选择日历或前往应用权限设置。"
            calendarButton.text = "重新授权并选择日历"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = GuardUi(this)
        writer = CalendarWriter(this)
        val key = intent.getStringExtra(EXTRA_EVENT_ID)
        val demo = intent.getBooleanExtra(EXTRA_DEMO, false)
        val event = (if (demo) DemoAttentionData.events else EventStore(this).load()).firstOrNull { it.id == key }
        if (event == null) { finish(); return }
        draft = CalendarDraft.from(event, demo)
        writer.preferredCalendar()?.let { preferred ->
            calendarId = preferred.id
            calendarLabel = preferred.label
        }
        savedInstanceState?.let { saved ->
            draft = draft.copy(
                day = saved.getString("day")?.let(LocalDate::parse),
                time = saved.getString("time")?.let(LocalTime::parse),
                allDay = saved.getBoolean("all_day"),
                reminderMinutes = saved.getInt("reminder", -1).takeIf { it >= 0 })
            calendarId = saved.getLong("calendar", -1)
            calendarLabel = saved.getString("calendar_label").orEmpty()
            receiptId = saved.getLong("receipt", -1)
        }
        root = FrameLayout(this).apply { setBackgroundColor(ui.background) }
        val shell = ui.boundedColumn()
        root.addView(shell, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
        shell.addView(ui.row().apply {
            setBackgroundColor(ui.background)
            setPadding(ui.dp(8), ui.dp(8), ui.dp(16), ui.dp(8))
            addView(ui.iconButton(R.drawable.ag_arrow_left, "返回，不写入日历") {
                onBackPressedDispatcher.onBackPressed()
            })
            addView(ui.text("确认日历待办", R.dimen.ag_type_heading, bold = true))
        })
        val body = ui.column().apply { setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(24)) }
        shell.addView(ui.scroll(body), LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(ui.callout("由你确认，不自动同步",
            "以系统日历日程保存，不是系统待办清单；应用内完成或归档不会修改日历。", iconRes = R.drawable.ag_calendar_plus))
        val title = ui.field("待办标题", savedInstanceState?.getString("title") ?: draft.title,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, R.id.ag_calendar_title)
        titleInput = title.second.apply { maxLines = 3; filters = arrayOf(InputFilter.LengthFilter(200)) }
        editingControls.add(titleInput)
        body.addView(title.first)
        body.addView(ui.heading("日期与提醒"))
        dateButton = ui.button("选择日期", R.drawable.ag_calendar_plus, false) {
            val day = draft.day ?: LocalDate.now()
            DatePickerDialog(this, { _, year, month, date ->
                draft = draft.copy(
                    day = LocalDate.of(year, month + 1, date),
                    time = draft.time ?: LocalTime.of(10, 0)
                )
                updateTimeLabels()
            }, day.year, day.monthValue - 1, day.dayOfMonth).show()
        }.apply { id = R.id.ag_calendar_date; layoutParams = ui.lp(12) }
        body.addView(dateButton)
        val allDay = ui.toggle("全天", draft.allDay, R.id.ag_calendar_all_day).apply {
            setOnCheckedChangeListener { _, checked ->
                draft = draft.copy(
                    allDay = checked,
                    time = if (checked) draft.time else draft.time ?: LocalTime.of(10, 0),
                    reminderMinutes = if (checked) null else draft.reminderMinutes ?: 0
                )
                updateTimeLabels()
            }
        }
        body.addView(allDay)
        timeButton = ui.button("选择时间", R.drawable.ag_clock_3, false) {
            val time = draft.time ?: LocalTime.of(9, 0)
            TimePickerDialog(this, { _, hour, minute ->
                draft = draft.copy(time = LocalTime.of(hour, minute)); updateTimeLabels()
            }, time.hour, time.minute, true).show()
        }.apply { id = R.id.ag_calendar_time }
        body.addView(timeButton)
        body.addView(ui.text("时区 · ${draft.zone.id}；定时事项占用 30 分钟。\n识别到截止日期时默认当天 10:00，可手动修改。",
            R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(8) })
        reminderButton = ui.button("提醒", R.drawable.ag_radio, false) {
            val options = arrayOf("不提醒", "开始时", "提前 15 分钟", "提前 1 小时")
            val values = listOf(null, 0, 15, 60)
            MaterialAlertDialogBuilder(this).setTitle("提醒方式")
                .setSingleChoiceItems(options, values.indexOf(draft.reminderMinutes)) { dialog, which ->
                    draft = draft.copy(reminderMinutes = values[which]); updateTimeLabels(); dialog.dismiss()
                }.setNegativeButton("取消", null).show()
        }.apply { id = R.id.ag_calendar_reminder; layoutParams = ui.lp(12) }
        body.addView(reminderButton)
        body.addView(ui.heading("写入位置"))
        calendarButton = ui.button(calendarLabel.ifBlank { "选择日历" }, R.drawable.ag_chevron_down, false) {
            chooseCalendar()
        }.apply { id = R.id.ag_calendar_target; layoutParams = ui.lp(12) }
        body.addView(calendarButton)
        body.addView(ui.text("账号日历可能按手机设置同步到云端。提醒是否响铃由系统日历与通知设置决定。",
            R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(8) })
        body.addView(ui.heading("将写入的来源信息"))
        body.addView(ui.text(draft.description, R.dimen.ag_type_label, ui.sub).apply { layoutParams = ui.lp(10) })
        status = ui.text("", R.dimen.ag_type_label, ui.brand).apply {
            id = R.id.ag_calendar_status
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            layoutParams = ui.lp(16)
        }
        body.addView(status)
        editingControls.addAll(listOf(dateButton, allDay, timeButton, reminderButton, calendarButton))
        confirmButton = ui.button("确认并写入", R.drawable.ag_calendar_plus) { confirm() }.apply { id = R.id.ag_calendar_confirm }
        shell.addView(ui.column().apply {
            setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12)); setBackgroundColor(ui.surface)
            addView(confirmButton)
        })
        updateTimeLabels()
        if (receiptId >= 0) showReceipt(true)
        ui.install(this, root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (saving) ui.feedback(root, "正在确认写入结果，请稍候")
                else finish()
            }
        })
        GuardMotion.revealRows(body)
    }

    private fun updateTimeLabels() {
        dateButton.text = draft.day?.toString() ?: "选择日期"
        if (::timeButton.isInitialized) {
            timeButton.text = if (draft.allDay) "全天 · 无固定时间" else draft.time?.toString() ?: "选择时间"
            timeButton.isEnabled = !draft.allDay && !saving && receiptId < 0
        }
        if (::reminderButton.isInitialized) reminderButton.text = "提醒 · " + when (draft.reminderMinutes) {
            null -> "不提醒"
            0 -> "开始时"
            15 -> "提前 15 分钟"
            else -> "提前 1 小时"
        }
    }

    private fun chooseCalendar() {
        if (writer.hasPermission()) { loadCalendars(); return }
        MaterialAlertDialogBuilder(this).setTitle("允许访问系统日历？")
            .setMessage("用于选择可写日历、核对是否重复和保存你确认的事项。授权本身不会写入。")
            .setNegativeButton("暂不", null)
            .setNeutralButton("权限设置") { _, _ ->
                runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:$packageName"))) }
            }
            .setPositiveButton("继续授权") { _, _ -> permissions.launch(CalendarWriter.PERMISSIONS) }.show()
    }

    private fun loadCalendars() {
        if (loading || saving || receiptId >= 0) return
        loading = true; calendarButton.isEnabled = false
        status.text = "正在读取可写日历…"
        CalendarWriter.worker.execute {
            val result = runCatching { writer.calendars() }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                loading = false; calendarButton.isEnabled = true
                result.onSuccess { choices ->
                    status.text = if (choices.isEmpty()) "没有可写日历，请先在系统日历添加或启用一个日历。" else ""
                    if (choices.isEmpty()) return@onSuccess
                    var selected = choices.indexOfFirst { it.id == calendarId }
                    val dialog = MaterialAlertDialogBuilder(this).setTitle("选择写入的日历")
                        .setSingleChoiceItems(choices.map { it.label }.toTypedArray(), selected) { _, which -> selected = which }
                        .setNegativeButton("取消", null)
                        .setPositiveButton("选用", null).create()
                    dialog.setOnShowListener {
                        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            if (selected < 0) { ui.feedback(root, "请先选择一个日历"); return@setOnClickListener }
                            val choice = choices[selected]
                            calendarId = choice.id
                            calendarLabel = choice.label
                            writer.rememberCalendar(choice)
                            calendarButton.text = calendarLabel
                            if (!choice.canRemind) {
                                draft = draft.copy(reminderMinutes = null); updateTimeLabels()
                                status.text = "该日历不支持提醒，将仅保存日程。"
                            }
                            dialog.dismiss()
                        }
                    }
                    dialog.show()
                }.onFailure { status.text = readable(it) }
            }
        }
    }

    private fun confirm() {
        if (saving || receiptId >= 0) return
        draft = draft.copy(title = titleInput.text.toString().trim())
        val interval = runCatching { draft.interval() }.getOrElse {
            status.text = it.message; return
        }
        val past = if (draft.allDay) draft.day!!.isBefore(LocalDate.now(draft.zone)) else interval.first <= System.currentTimeMillis()
        if (past) { status.text = "所选时间已过去，请核对并调整日期或时间。"; return }
        if (calendarId < 0) { status.text = "请先选择目标日历，再确认写入。"; return }
        if (!writer.hasPermission()) { status.text = "日历权限已关闭，请重新选择日历授权；尚未写入。"; return }
        val pendingDraft = draft
        val target = calendarId
        saving = true
        editingControls.forEach { it.isEnabled = false }
        confirmButton.isEnabled = false; confirmButton.text = "正在写入…"
        status.text = ""
        CalendarWriter.worker.execute {
            val result = runCatching { writer.confirm(pendingDraft, target) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                saving = false; confirmButton.isEnabled = true
                result.onSuccess { receipt -> receiptId = receipt.id; showReceipt(receipt.existed) }
                    .onFailure {
                        editingControls.forEach { control -> control.isEnabled = true }; updateTimeLabels()
                        confirmButton.text = "确认并写入"; status.text = readable(it)
                    }
            }
        }
    }

    private fun showReceipt(existed: Boolean) {
        editingControls.forEach { it.isEnabled = false }
        status.text = if (existed) "该事项已在日历中，不重复创建。修改时间请打开系统日历。" else "已写入选定日历。未修改应用内完成状态。"
        confirmButton.text = "打开日历查看"
        confirmButton.setIconResource(R.drawable.ag_arrow_up_right)
        confirmButton.setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, receiptId)))
            }.onFailure { ui.feedback(root, "未找到日历查看页面，请手动打开系统日历") }
        }
        GuardMotion.acknowledge(confirmButton)
    }

    private fun readable(error: Throwable): String = when (error) {
        is SecurityException -> "日历权限不可用，请重新授权。"
        is IllegalStateException, is IllegalArgumentException -> error.message ?: "无法写入，请核对所选日历。"
        else -> "未确认写入结果，请重试核对；同一事项会先查重。"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::draft.isInitialized && ::titleInput.isInitialized) {
            outState.putString("title", titleInput.text.toString())
            outState.putString("day", draft.day?.toString())
            outState.putString("time", draft.time?.toString())
            outState.putBoolean("all_day", draft.allDay)
            outState.putInt("reminder", draft.reminderMinutes ?: -1)
            outState.putLong("calendar", calendarId)
            outState.putString("calendar_label", calendarLabel)
            outState.putLong("receipt", receiptId)
        }
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val EXTRA_EVENT_ID = "com.attentionguard.app.calendar_event_id"
        const val EXTRA_DEMO = "com.attentionguard.app.calendar_demo"
    }
}
