package com.attentionguard.app

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.CheckBox
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputLayout
import com.attentionguard.app.core.*
import com.attentionguard.app.capture.CaptureDiagnostics
import com.attentionguard.app.ui.GuardMotion
import com.attentionguard.app.ui.GuardUi
import com.attentionguard.app.ui.GuardSegments
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var ui: GuardUi
    private lateinit var prefs: Prefs
    private lateinit var store: EventStore
    private lateinit var shell: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var nav: BottomNavigationView
    private var events = emptyList<AttentionEvent>()
    private var stopObservingEvents: (() -> Unit)? = null
    private var demoEvents = DemoAttentionData.events
    private var tab = R.id.ag_attention
    private var filter = EventFilter.ALL
    private var dateFilter = EventDateFilter.ALL
    private var priorityFilter = EventPriorityFilter.ALL
    private var query = ""
    private var limit = 20
    private var detailId: String? = null
    private var evidenceOpen = false
    private var selectionMode = false
    private val selectedEventIds = linkedSetOf<String>()
    private val positions = mutableMapOf<Int, Int>()
    private var rendered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val state = savedInstanceState
        ui = GuardUi(this)
        prefs = Prefs(this)
        store = EventStore(this)
        tab = state?.getInt("tab", R.id.ag_attention) ?: R.id.ag_attention
        filter = EventFilter.values().getOrElse(state?.getInt("filter") ?: 0) { EventFilter.ALL }
        dateFilter = EventDateFilter.values().getOrElse(state?.getInt("dateFilter") ?: 0) { EventDateFilter.ALL }
        priorityFilter = EventPriorityFilter.values().getOrElse(state?.getInt("priorityFilter") ?: 0) { EventPriorityFilter.ALL }
        query = state?.getString("query").orEmpty()
        detailId = state?.getString("detail")
        evidenceOpen = state?.getBoolean("evidence") ?: false
        limit = state?.getInt("limit", 20) ?: 20
        state?.getIntArray("scroll")?.let { values ->
            TABS.forEachIndexed { i, id -> positions[id] = values.getOrElse(i) { 0 } }
        }
        shell = ui.boundedColumn()
        val root = FrameLayout(this).apply {
            id = R.id.ag_root
            setBackgroundColor(ui.background)
            addView(shell, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
        }
        ui.install(this, root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    detailId != null -> { detailId = null; evidenceOpen = false; render() }
                    tab != R.id.ag_attention -> switchTab(R.id.ag_attention)
                    else -> finish()
                }
            }
        })
        if (state == null) readEventIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (readEventIntent(intent)) render()
    }

    private fun readEventIntent(intent: Intent): Boolean {
        val id = intent.getStringExtra(EXTRA_EVENT_ID)?.takeIf { it.isNotBlank() } ?: return false
        prefs.demoMode = false
        events = store.load()
        tab = R.id.ag_ledger
        detailId = id
        evidenceOpen = false
        selectionMode = false
        selectedEventIds.clear()
        positions[tab] = 0
        return true
    }

    override fun onStart() {
        super.onStart()
        stopObservingEvents = store.observe {
            if (prefs.demoMode) return@observe
            val current = store.load()
            if (current != events || store.readFailed) {
                if (rendered && detailId == null) positions[tab] = scroll.scrollY
                events = current
                render()
            }
        }
    }

    override fun onStop() {
        stopObservingEvents?.invoke(); stopObservingEvents = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (rendered && detailId == null) positions[tab] = scroll.scrollY
        events = if (prefs.demoMode) demoEvents else store.load()
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (detailId == null && rendered) positions[tab] = scroll.scrollY
        outState.putInt("tab", tab)
        outState.putInt("filter", filter.ordinal)
        outState.putInt("dateFilter", dateFilter.ordinal)
        outState.putInt("priorityFilter", priorityFilter.ordinal)
        outState.putString("query", query)
        outState.putString("detail", detailId)
        outState.putBoolean("evidence", evidenceOpen)
        outState.putInt("limit", limit)
        outState.putIntArray("scroll", TABS.map { positions[it] ?: 0 }.toIntArray())
        super.onSaveInstanceState(outState)
    }

    private fun render(animate: Boolean = false) {
        shell.removeAllViews()
        shell.addView(toolbar())
        body = ui.column().apply { setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(24)) }
        scroll = ui.scroll(body)
        shell.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val event = detailId?.let { id -> events.firstOrNull { it.id == id } }
        if (event != null) {
            renderDetail(event)
        } else {
            detailId = null
            if (prefs.demoMode) demoBanner()
            if (!prefs.demoMode && store.readFailed) {
                body.addView(ui.text("本地记录读取失败，原数据未被覆盖。", tint = ui.color(R.color.ag_danger)))
            }
            when (tab) {
                R.id.ag_attention -> attention()
                R.id.ag_ledger -> ledger()
                R.id.ag_sources -> sources()
                else -> profile()
            }
            buildNavigation()
            scroll.post { scroll.scrollTo(0, positions[tab] ?: 0) }
        }
        rendered = true
        CaptureDiagnostics(this).appList(events.size,
            if (tab == R.id.ag_ledger) ledgerMatches().size else events.count { !it.archived },
            filter != EventFilter.ALL || dateFilter != EventDateFilter.ALL || priorityFilter != EventPriorityFilter.ALL || query.isNotBlank(),
            prefs.demoMode, store.readFailed)
        if (animate) GuardMotion.revealRows(body)
    }

    private fun toolbar(): View = ui.row().apply {
        setBackgroundColor(ui.background)
        setPadding(ui.dp(20), ui.dp(4), ui.dp(8), ui.dp(4))
        if (detailId != null) {
            addView(ui.iconButton(R.drawable.ag_arrow_left, "返回事件列表") { onBackPressedDispatcher.onBackPressed() })
            addView(ui.text("事件详情", R.dimen.ag_type_heading, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        } else {
            addView(ui.brandMark(32))
            addView(ui.text(getString(R.string.app_name), R.dimen.ag_type_body, bold = true).apply {
                setPadding(ui.dp(10), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        addView(ui.iconButton(R.drawable.ag_settings_2, "打开设置") { settings() })
    }

    private fun title(value: String, subtitle: String) {
        body.addView(ui.text(value, R.dimen.ag_type_title, bold = true))
        body.addView(ui.text(subtitle, R.dimen.ag_type_label, ui.sub).apply { layoutParams = ui.lp(8) })
    }

    private fun demoBanner() {
        body.addView(ui.row().apply {
            setPadding(0, 0, 0, ui.dp(16))
            addView(ui.badge("示例模式", ui.color(R.color.ag_info), ui.color(R.color.ag_info_soft)))
            addView(ui.text("不计入真实记录", R.dimen.ag_type_caption, ui.sub).apply {
                setPadding(ui.dp(8), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.iconButton(R.drawable.ag_x, "退出示例模式") { setDemo(false) })
        })
    }

    private fun attention() {
        title("把注意力留给重要的事", SimpleDateFormat("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE).format(Date()))
        val stats = attentionStatsFrom(events)
        val white = ui.surface
        val mint = ui.color(R.color.ag_mint)
        body.addView(ui.panel(ui.color(R.color.ag_graphite), null, 18).apply {
            layoutParams = ui.lp(18)
            addView(ui.row().apply {
                addView(ui.column().apply {
                    addView(ui.text("待处理", R.dimen.ag_type_label, mint))
                    addView(ui.text(stats.actionRequired.toString(), R.dimen.ag_type_display, white, true).apply {
                        layoutParams = ui.lp(4); fontFeatureSettings = "tnum"
                    })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ui.button("处理事项", R.drawable.ag_arrow_up_right, false) {
                    filter = EventFilter.ACTION; switchTab(R.id.ag_ledger)
                }.apply {
                    backgroundTintList = ColorStateList.valueOf(mint)
                    setTextColor(ui.ink); iconTint = ColorStateList.valueOf(ui.ink); strokeWidth = 0
                    layoutParams = LinearLayout.LayoutParams(-2, -2)
                })
            })
            addView(ui.row().apply {
                layoutParams = ui.lp(10)
                listOf(
                    Triple(stats.observed - stats.actionRequired - stats.completed, "关注中", EventFilter.FOLLOWING),
                    Triple(stats.completed, "已完成", EventFilter.COMPLETED),
                    Triple(events.count { it.archived }, "归档", EventFilter.ARCHIVED)
                ).forEach { (count, label, kind) ->
                    addView(ui.row().apply {
                        minimumHeight = ui.dp(48)
                        addView(ui.text(count.toString(), R.dimen.ag_type_heading, white, true).apply { fontFeatureSettings = "tnum" })
                        addView(ui.text(label, R.dimen.ag_type_caption, mint).apply { setPadding(ui.dp(6), 0, 0, 0) })
                        ui.accessibleAction(this, "$label，$count 个，查看列表") { filter = kind; switchTab(R.id.ag_ledger) }
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                }
            })
        })
        body.addView(ui.row().apply {
            layoutParams = ui.lp(12)
            addView(ui.quickAction(R.drawable.ag_messages_square, "回到微信", "悬浮窗 · 持续观测", true) { openWeChat() },
                LinearLayout.LayoutParams(0, -1, 1f))
            addView(ui.quickAction(R.drawable.ag_scan_text, "自由分析", "输入内容 · 不记入事件") {
                startActivity(Intent(this@MainActivity, CustomIntentActivity::class.java))
            }.apply { contentDescription = "打开自定义意图分析" },
                LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = ui.dp(10) })
        })
        val status = CaptureDiagnostics(this).healthLabel(prefs.enabled, isA11yEnabled())
        body.addView(ui.navigationRow(R.drawable.ag_notebook_tabs, "会话分析", "已保存聊天 · 关系与语境") {
            startActivity(Intent(this, ConversationAnalysisActivity::class.java))
        }.apply { layoutParams = ui.lp(12) })
        body.addView(ui.row().apply {
            minimumHeight = ui.dp(48)
            addView(ui.icon(R.drawable.ag_activity, ui.brand, 16))
            addView(ui.text(status, R.dimen.ag_type_caption, ui.sub).apply { setPadding(ui.dp(7), 0, 0, 0) },
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.text(if (prefs.cloudEnabled && prefs.hasKey()) "联网增强" else "本地运行", R.dimen.ag_type_caption, ui.brand))
            ui.accessibleAction(this, "$status，查看运行状态") { switchTab(R.id.ag_profile) }
        })
        if (events.none { !it.archived }) {
            if (events.isEmpty()) empty("记录本还是空的", "尚未发现需要保留的事项", true)
            else {
                empty("当前事件已处理", "${events.size} 个事件在归档中", false)
                body.addView(ui.button("查看归档", R.drawable.ag_archive, false) {
                    filter = EventFilter.ARCHIVED; switchTab(R.id.ag_ledger)
                }.apply { layoutParams = ui.lp(12) })
            }
            return
        }
        body.addView(ui.heading("优先处理"))
        val actionable = filterEvents(events, EventFilter.ACTION, "")
        if (actionable.isEmpty()) {
            body.addView(ui.text("暂时没有待处理事项", tint = ui.sub).apply { layoutParams = ui.lp(16) })
        } else actionable.take(3).forEach { body.addView(eventRow(it, true)) }
        body.addView(ui.heading("继续关注"))
        val watched = filterEvents(events, EventFilter.FOLLOWING, "")
        if (watched.isEmpty()) body.addView(ui.text("暂无关注中的事件", tint = ui.sub).apply { layoutParams = ui.lp(16) })
        else watched.take(3).forEach { body.addView(eventRow(it)) }
        body.addView(ui.button("全部事件", R.drawable.ag_notebook_tabs, false) { switchTab(R.id.ag_ledger) }.apply { layoutParams = ui.lp(20) })
    }

    private fun ledger() {
        title("观测簿", "${events.count { !it.archived }} 个当前事件 · ${events.count { it.archived }} 个归档")
        val (searchBox, search) = ui.field("搜索事件或群名", query, InputType.TYPE_CLASS_TEXT, R.id.ag_search)
        search.setSingleLine()
        searchBox.startIconDrawable = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ag_search)
        searchBox.endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
        searchBox.setEndIconContentDescription("清空搜索")
        body.addView(searchBox)
        val segmented = GuardSegments(this,
            EventFilter.values().map { GuardSegments.Option(500 + it.ordinal, it.label) },
            500 + filter.ordinal, itemWidth = ui.dp(80), textSize = R.dimen.ag_type_caption)
        body.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(segmented)
            layoutParams = ui.lp(16)
        })
        body.addView(ui.text("时间范围", R.dimen.ag_type_caption, ui.sub, true).apply { layoutParams = ui.lp(10) })
        val dateSegments = GuardSegments(this,
            EventDateFilter.values().map { GuardSegments.Option(700 + it.ordinal, it.label) },
            700 + dateFilter.ordinal, itemWidth = ui.dp(82), textSize = R.dimen.ag_type_caption)
        body.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(dateSegments)
            layoutParams = ui.lp(4)
        })
        body.addView(ui.text("重要程度", R.dimen.ag_type_caption, ui.sub, true).apply { layoutParams = ui.lp(10) })
        val prioritySegments = GuardSegments(this,
            EventPriorityFilter.values().map { GuardSegments.Option(800 + it.ordinal, it.label) },
            800 + priorityFilter.ordinal, itemWidth = ui.dp(82), textSize = R.dimen.ag_type_caption)
        body.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(prioritySegments)
            layoutParams = ui.lp(4)
        })
        val batchBar = ui.column().apply {
            minimumHeight = ui.dp(52)
            layoutParams = ui.lp(12)
        }
        body.addView(batchBar)
        val list = ui.column()
        body.addView(list)
        fun refresh() {
            list.removeAllViews()
            val matches = ledgerMatches()
            selectedEventIds.retainAll(matches.map { it.id }.toSet())
            renderBatchControls(batchBar, matches)
            list.addView(ui.text("${matches.size} 个结果", R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(16) })
            if (matches.isEmpty()) {
                val emptyLabel = when {
                    query.isNotBlank() || dateFilter != EventDateFilter.ALL || priorityFilter != EventPriorityFilter.ALL ->
                        "没有匹配的事件"
                    else -> "这里还没有事件"
                }
                list.addView(ui.text(emptyLabel, R.dimen.ag_type_heading, bold = true).apply { layoutParams = ui.lp(32) })
                list.addView(ui.button("清除筛选", R.drawable.ag_rotate_ccw, false) {
                    query = ""
                    filter = EventFilter.ALL
                    dateFilter = EventDateFilter.ALL
                    priorityFilter = EventPriorityFilter.ALL
                    selectionMode = false
                    selectedEventIds.clear()
                    limit = 20
                    positions[tab] = 0; render()
                }.apply { layoutParams = ui.lp(20) })
            } else {
                matches.take(limit).forEach { list.addView(eventRow(it)) }
                if (matches.size > limit) list.addView(ui.button("加载更多", R.drawable.ag_chevron_down, false) {
                    limit += 20; refresh()
                }.apply { layoutParams = ui.lp(16) })
            }
        }
        segmented.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                filter = EventFilter.values()[id - 500]
                selectionMode = false
                selectedEventIds.clear()
                limit = 20; positions[tab] = 0
                refresh()
                GuardMotion.revealRows(list)
                scroll.post { scroll.scrollTo(0, 0) }
            }
        }
        dateSegments.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                dateFilter = EventDateFilter.values()[id - 700]
                selectionMode = false
                selectedEventIds.clear()
                limit = 20; positions[tab] = 0
                refresh()
                GuardMotion.revealRows(list)
                scroll.post { scroll.scrollTo(0, 0) }
            }
        }
        prioritySegments.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                priorityFilter = EventPriorityFilter.values()[id - 800]
                selectionMode = false
                selectedEventIds.clear()
                limit = 20; positions[tab] = 0
                refresh()
                GuardMotion.revealRows(list)
                scroll.post { scroll.scrollTo(0, 0) }
            }
        }
        search.doAfterTextChanged {
            query = it.toString()
            selectionMode = false
            selectedEventIds.clear()
            limit = 20; positions[tab] = 0; refresh()
            scroll.post { scroll.scrollTo(0, 0) }
        }
        refresh()
    }

    private fun ledgerMatches(): List<AttentionEvent> =
        filterEvents(events, filter, query, dateFilter, priorityFilter)

    private fun renderBatchControls(container: LinearLayout, matches: List<AttentionEvent>) {
        container.removeAllViews()
        val header = ui.row()
        container.addView(header, LinearLayout.LayoutParams(-1, -2))
        if (!selectionMode) {
            header.addView(ui.text("批量操作", R.dimen.ag_type_caption, ui.sub, true),
                LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(ui.button("批量归档", R.drawable.ag_archive, false) {
                selectionMode = true
                selectedEventIds.clear()
                render()
            }, LinearLayout.LayoutParams(-2, -2))
            return
        }
        val allSelected = matches.isNotEmpty() && matches.all { it.id in selectedEventIds }
        header.addView(CheckBox(this).apply {
            text = "全选"
            minHeight = ui.dp(48)
            setTextColor(ui.ink)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.ag_type_label))
            isChecked = allSelected
            setOnCheckedChangeListener { _, checked ->
                if (checked) selectedEventIds.addAll(matches.map { it.id })
                else selectedEventIds.removeAll(matches.map { it.id }.toSet())
                render()
            }
        }, LinearLayout.LayoutParams(-2, -2))
        header.addView(ui.text("${selectedEventIds.size} 个已选", R.dimen.ag_type_caption, ui.sub).apply {
            setPadding(ui.dp(6), 0, ui.dp(8), 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(ui.iconButton(R.drawable.ag_x, "退出批量选择") {
            selectionMode = false
            selectedEventIds.clear()
            render()
        })
        val restoring = filter == EventFilter.ARCHIVED
        container.addView(ui.button(if (restoring) "移出归档" else "归档", if (restoring) R.drawable.ag_undo_2 else R.drawable.ag_archive, false) {
            batchArchive()
        }.apply {
            contentDescription = if (restoring) "移出已选事件的归档" else "归档已选事件"
            isEnabled = selectedEventIds.isNotEmpty()
            layoutParams = ui.lp(4)
        })
    }

    private fun batchArchive() {
        val ids = selectedEventIds.toList()
        if (ids.isEmpty()) return
        val archive = filter != EventFilter.ARCHIVED
        runCatching {
            if (prefs.demoMode) {
                demoEvents = demoEvents.map { event ->
                    if (event.id in ids) event.withArchive(archive) else event
                }
                demoEvents
            } else {
                store.setArchived(ids, archive)
            }
        }.onSuccess { updated ->
            events = updated
            selectionMode = false
            selectedEventIds.clear()
            render()
            ui.feedback(shell, if (archive) "已批量归档 ${ids.size} 个事件" else "已恢复 ${ids.size} 个事件")
        }.onFailure {
            ui.feedback(shell, "保存失败，原记录未修改")
        }
    }

    private fun eventRow(event: AttentionEvent, featured: Boolean = false): View {
        val (tone, tint) = ui.priority(event.priority)
        val content = ui.column().apply { setPadding(ui.dp(14), ui.dp(14), ui.dp(10), ui.dp(14)) }
        content.addView(ui.row().apply {
            addView(ui.badge(event.priority.label, tone, tint))
            addView(ui.text(if (event.archived) "已归档" else event.status.label, R.dimen.ag_type_caption, ui.sub).apply {
                setPadding(ui.dp(8), 0, ui.dp(8), 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.icon(R.drawable.ag_chevron_right, size = 18))
        })
        content.addView(ui.text(event.title, R.dimen.ag_type_body, bold = true).apply { layoutParams = ui.lp(10); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        if (featured) content.addView(ui.text(event.summary, R.dimen.ag_type_label, ui.sub).apply { layoutParams = ui.lp(8); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        DeadlineParser.displayLabel(event.dueLabel)?.let { due ->
            content.addView(ui.text(due, R.dimen.ag_type_label, tone, true).apply { layoutParams = ui.lp(12) })
        }
        content.addView(ui.text("${if (prefs.demoMode) "示例数据" else event.captureOrigin.label} · ${event.sourceGroup}", R.dimen.ag_type_caption, ui.sub).apply {
            layoutParams = ui.lp(8); maxLines = 2
        })
        event.sourceCapturedAt?.let { captured ->
            content.addView(ui.text("采集 · ${SimpleDateFormat("M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE).format(Date(captured))}",
                R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(5) })
        }
        ui.accessibleAction(content,
            listOfNotNull(event.title, event.priority.label, if (event.archived) "已归档" else event.status.label,
                event.dueLabel, event.sourceGroup, "查看详情").joinToString("，")) {
            positions[tab] = scroll.scrollY
            detailId = event.id; evidenceOpen = false; render(true)
        }
        return MaterialCardView(this).apply {
            radius = ui.dp(12).toFloat()
            cardElevation = 0f
            strokeWidth = ui.dp(1)
            strokeColor = ui.line
            setCardBackgroundColor(ui.surface)
            addView(ui.row().apply {
                if (selectionMode) {
                    addView(CheckBox(this@MainActivity).apply {
                        contentDescription = "选择：${event.title}"
                        isChecked = event.id in selectedEventIds
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) selectedEventIds.add(event.id) else selectedEventIds.remove(event.id)
                            render()
                        }
                    }, LinearLayout.LayoutParams(ui.dp(48), -2))
                }
                addView(content, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ui.column().apply {
                    addView(ui.iconButton(if (event.status == EventStatus.COMPLETED) R.drawable.ag_circle_check else R.drawable.ag_check,
                        "${if (event.status == EventStatus.COMPLETED) "恢复" else "标记完成"}：${event.title}") { toggleCompleted(event) }.apply {
                        setTag(R.id.ag_event_action, event.id)
                        imageTintList = ColorStateList.valueOf(if (event.status == EventStatus.COMPLETED) ui.brand else ui.sub)
                    })
                    addView(ui.iconButton(if (event.archived) R.drawable.ag_undo_2 else R.drawable.ag_archive,
                        "${if (event.archived) "移出归档" else "归档"}：${event.title}") { toggleArchived(event) })
                })
            })
            layoutParams = ui.lp(12)
        }
    }

    private fun updateEvent(event: AttentionEvent, updated: AttentionEvent, persist: () -> List<AttentionEvent>, message: String,
                            acknowledge: Boolean = false) {
        runCatching {
            if (prefs.demoMode) {
                demoEvents = demoEvents.map { if (it.id == event.id) updated else it }
                events = demoEvents
            } else events = persist()
        }.onSuccess {
            if (detailId == null) positions[tab] = scroll.scrollY
            render()
            if (acknowledge) {
                findEventAction(shell, event.id)?.let { GuardMotion.acknowledge(it) }
            }
            ui.feedback(shell, message)
        }.onFailure { ui.feedback(shell, "保存失败，原记录未修改") }
    }

    private fun findEventAction(view: View, eventId: String): View? {
        if (view.getTag(R.id.ag_event_action) == eventId) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findEventAction(view.getChildAt(index), eventId)?.let { return it }
        }
        return null
    }

    private fun toggleCompleted(event: AttentionEvent) {
        val complete = event.status != EventStatus.COMPLETED
        updateEvent(event, event.withCompletion(complete), { store.setCompleted(event.id, complete) },
            if (complete) "已标记完成" else "已恢复原状态", acknowledge = true)
    }

    private fun toggleArchived(event: AttentionEvent) {
        val archive = !event.archived
        updateEvent(event, event.withArchive(archive), { store.setArchived(event.id, archive) },
            if (archive) "已归档，可在归档中恢复" else "已移出归档")
    }

    private fun renderDetail(event: AttentionEvent) {
        val (tone, tint) = ui.priority(event.priority)
        body.addView(ui.badge(if (prefs.demoMode) "示例事件" else event.analysisSource))
        body.addView(ui.text(event.title, R.dimen.ag_type_title, bold = true).apply { layoutParams = ui.lp(18) })
        body.addView(ui.text(event.summary, tint = ui.sub).apply { layoutParams = ui.lp(12) })
        body.addView(ui.row().apply {
            layoutParams = ui.lp(16)
            addView(ui.badge(event.priority.label, tone, tint))
            addView(ui.text(if (event.archived) "已归档 · ${event.status.label}" else event.status.label, tint = ui.brand).apply { setPadding(ui.dp(12), 0, 0, 0) })
        })
        body.addView(ui.button("加入日历待办", R.drawable.ag_calendar_plus, false) {
            startActivity(Intent(this, CalendarActivity::class.java)
                .putExtra(CalendarActivity.EXTRA_EVENT_ID, event.id)
                .putExtra(CalendarActivity.EXTRA_DEMO, prefs.demoMode))
        }.apply { layoutParams = ui.lp(16) })
        body.addView(ui.heading("判定摘要"))
        body.addView(ui.callout(
            "为什么是 ${event.priority.label}",
            priorityExplanation(event),
            tone,
            tint,
            if (event.priority == EventPriority.P0) R.drawable.ag_circle_alert else R.drawable.ag_shield_check
        ).apply { layoutParams = ui.lp(8) })
        DeadlineParser.displayLabel(event.dueLabel)?.let { body.addView(ui.heading("时间")); body.addView(ui.text(it, tint = tone).apply { layoutParams = ui.lp(10) }) }
        if (event.reviewNotes.isNotEmpty()) {
            body.addView(ui.heading("复核提示"))
            event.reviewNotes.forEach { body.addView(ui.text(it, R.dimen.ag_type_label, ui.sub).apply { layoutParams = ui.lp(8) }) }
        }
        event.actionLabel?.let { body.addView(ui.heading("下一步")); body.addView(ui.text(it).apply { layoutParams = ui.lp(10) }) }
        event.consequence?.let { body.addView(ui.text(it, R.dimen.ag_type_label, ui.sub).apply { layoutParams = ui.lp(8) }) }
        body.addView(ui.divider(24))
        body.addView(ui.heading("来源"))
        body.addView(ui.text(event.sourceGroup).apply { layoutParams = ui.lp(10) })
        body.addView(ui.text("${event.sourcePerson} · ${event.updatedLabel}", R.dimen.ag_type_label, ui.sub).apply { layoutParams = ui.lp(6) })
        body.addView(ui.text(if (prefs.demoMode) "示例数据" else event.captureOrigin.label,
            R.dimen.ag_type_label, ui.sub).apply { layoutParams = ui.lp(6) })
        event.sourceCapturedAt?.let { captured ->
            body.addView(ui.text("采集于 ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE).format(Date(captured))}",
                R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(6) })
        }
        body.addView(ui.heading("事件记录"))
        event.updates.forEach { update ->
            body.addView(ui.row().apply {
                gravity = Gravity.TOP
                layoutParams = ui.lp(16)
                addView(ui.text(update.time, R.dimen.ag_type_caption, ui.sub), LinearLayout.LayoutParams(ui.dp(80), -2))
                addView(ui.column().apply {
                    addView(ui.text(update.title, bold = true))
                    addView(ui.text(update.detail, R.dimen.ag_type_label, ui.sub).apply { layoutParams = ui.lp(6) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
            })
        }
        body.addView(ui.button(if (evidenceOpen) "收起原始依据" else "原始依据 · ${event.evidence.size}", R.drawable.ag_eye, false) {
            val y = scroll.scrollY
            evidenceOpen = !evidenceOpen; render(); scroll.post { scroll.scrollTo(0, y) }
        }.apply { layoutParams = ui.lp(24) })
        if (evidenceOpen) {
            if (event.evidence.isEmpty()) body.addView(ui.text("暂无保留的原始消息", tint = ui.sub).apply { layoutParams = ui.lp(16) })
            event.evidence.forEach { body.addView(ui.text(it, tint = ui.sub).apply { layoutParams = ui.lp(16); setTextIsSelectable(true) }) }
        }
        val complete = event.status == EventStatus.COMPLETED
        shell.addView(ui.column().apply {
            setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12))
            setBackgroundColor(ui.surface)
            addView(ui.row().apply {
                addView(ui.button(if (complete) "恢复原状态" else "标记完成", if (complete) R.drawable.ag_undo_2 else R.drawable.ag_check) {
                    toggleCompleted(event)
                }.apply { setTag(R.id.ag_event_action, event.id) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ui.button(if (event.archived) "移出归档" else "归档", if (event.archived) R.drawable.ag_undo_2 else R.drawable.ag_archive, false) {
                    toggleArchived(event)
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ui.dp(8) })
            })
        })
    }

    private fun priorityExplanation(event: AttentionEvent): String = when (event.priority) {
        EventPriority.P0 -> "已同时确认明确行动、日历有效且 24 小时内的截止时间，以及老师/管理员或全体通知信号。"
        EventPriority.P1 -> "存在明确行动，并且有未来截止时间或可靠的来源信号；仍建议打开原始依据核对。"
        EventPriority.P2 -> "检测到行动或时间线索，但证据还不足以自动升级为紧急事项。"
        EventPriority.P3 -> "当前证据不足、已过期或出现取消/作废信号，因此不会自动升级。"
    } + if (event.analysisSource != "本地规则") " DeepSeek 只补充解释，不能越过本地等级门槛。" else " 本地规则优先于模型建议。"

    private fun sources() {
        title("来源", "每个判断，都能找到出处")
        body.addView(ui.panel().apply {
            layoutParams = ui.lp(18)
            addView(ui.row().apply {
                addView(ui.icon(R.drawable.ag_messages_square, ui.brand, 24))
                addView(ui.text("WeChat", R.dimen.ag_type_heading, bold = true).apply { setPadding(ui.dp(10), 0, 0, 0) },
                    LinearLayout.LayoutParams(0, -2, 1f))
                addView(ui.badge("当前屏幕"))
            })
            addView(ui.navigationRow(R.drawable.ag_bookmark_plus, "会话范围",
                if (prefs.whitelist.isEmpty()) "所有当前会话" else "${prefs.whitelist.size} 个识别词条") { settings() })
            addView(ui.text("只读取可见内容，不填写或发送消息。", R.dimen.ag_type_caption, ui.sub))
        })
        body.addView(actionRow(R.drawable.ag_radio, "采集与回溯", "原始消息、日期范围与运行诊断", false) {
            startActivity(Intent(this, CaptureActivity::class.java))
        })
        body.addView(ui.heading("当前事件来源"))
        val groups = events.filterNot { it.archived }.groupBy { it.sourceGroup }
        if (groups.isEmpty()) body.addView(ui.callout("暂无来源", "在微信中识别一条事件后，这里会按会话聚合。",
            iconRes = R.drawable.ag_messages_square).apply { layoutParams = ui.lp(12) })
        groups.forEach { (name, items) ->
            body.addView(actionRow(R.drawable.ag_messages_square, name, "${items.size} 个事件 · ${items.count { it.needsAction() }} 个待处理") {
                query = name; filter = EventFilter.ALL; switchTab(R.id.ag_ledger)
            })
        }
    }

    private fun profile() {
        title("我的", "把工具调成顺手的样子")
        body.addView(ui.panel().apply {
            layoutParams = ui.lp(18)
            addView(ui.toggle("观测开关", prefs.enabled).apply {
                setOnCheckedChangeListener { _, checked ->
                    prefs.enabled = checked
                    ui.feedback(shell, if (checked) "已开启观测" else "已暂停观测")
                }
            })
            addView(ui.text("当前模式 · ${if (prefs.captureMode == CaptureMode.INTENT) "意图分析" else "事件监测"}",
                R.dimen.ag_type_label, ui.sub))
        })
        body.addView(ui.heading("偏好"))
        body.addView(actionRow(R.drawable.ag_sliders_horizontal, "规则与外观", "会话词条 · 背景透明度 · 联网增强") { settings() })
        body.addView(ui.toggle("示例模式", prefs.demoMode).apply {
            setOnCheckedChangeListener { _, checked -> setDemo(checked) }
        })
        body.addView(ui.heading("连接与运行"))
        body.addView(actionRow(R.drawable.ag_radio, "采集诊断与消息记录", "连接状态、读取原因与落盘时间") {
            startActivity(Intent(this, CaptureActivity::class.java).putExtra(CaptureActivity.EXTRA_SECTION, 2))
        })
        body.addView(actionRow(R.drawable.ag_eye, "无障碍采集", CaptureDiagnostics(this).healthLabel(prefs.enabled, isA11yEnabled())) {
            launchSystem(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        if (prefs.enabled && isA11yEnabled() && !CaptureDiagnostics(this).isConnected()) {
            body.addView(ui.text("请在系统无障碍页面关闭后重新开启 ${getString(R.string.app_name)}。授权存在不代表服务仍在运行。", R.dimen.ag_type_label, ui.color(R.color.ag_warning)).apply { layoutParams = ui.lp(8) })
        }
        body.addView(actionRow(R.drawable.ag_settings_2, "后台运行", "系统管理") {
            launchSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        body.addView(ui.divider(24))
        body.addView(ui.row().apply {
            layoutParams = ui.lp(20)
            addView(ui.brandMark(36))
            addView(ui.column().apply {
                setPadding(ui.dp(12), 0, 0, 0)
                addView(ui.text(getString(R.string.app_name), bold = true))
                addView(ui.text("版本 ${BuildConfig.VERSION_NAME} · 本地消息与事件簿", R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(6) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
    }

    private fun actionRow(icon: Int, title: String, subtitle: String, divider: Boolean = true, action: () -> Unit): View =
        ui.column().apply {
            addView(ui.navigationRow(icon, title, subtitle, action))
            if (divider) addView(ui.divider(0))
            layoutParams = ui.lp(4)
        }

    private fun empty(title: String, subtitle: String, actions: Boolean) {
        body.addView(ui.panel().apply {
            layoutParams = ui.lp(8)
            addView(ui.row().apply {
                addView(ui.icon(R.drawable.ag_notebook_tabs, ui.brand, 24))
                addView(ui.text(title, R.dimen.ag_type_body, bold = true).apply { setPadding(ui.dp(10), 0, 0, 0) },
                    LinearLayout.LayoutParams(0, -2, 1f))
            })
            addView(ui.text(subtitle, R.dimen.ag_type_label, ui.sub).apply { layoutParams = ui.lp(8) })
            if (actions) {
                addView(ui.row().apply {
                    layoutParams = ui.lp(16)
                    addView(ui.button("配置观测", primary = false) { switchTab(R.id.ag_profile) }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(ui.button("浏览示例", primary = false) { setDemo(true) },
                        LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ui.dp(8) })
                })
            }
        })
    }

    private fun buildNavigation() {
        nav = BottomNavigationView(this).apply {
            setBackgroundColor(ui.surface)
            labelVisibilityMode = com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_LABELED
            itemIconTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(ui.brand, ui.sub))
            itemTextColor = itemIconTintList
            itemActiveIndicatorColor = ColorStateList.valueOf(ui.color(R.color.ag_brand_soft))
            listOf(Triple(R.id.ag_attention, "注意力", R.drawable.ag_focus), Triple(R.id.ag_ledger, "观测簿", R.drawable.ag_notebook_tabs),
                Triple(R.id.ag_sources, "来源", R.drawable.ag_messages_square), Triple(R.id.ag_profile, "我的", R.drawable.ag_sliders_horizontal)).forEach {
                menu.add(0, it.first, 0, it.second).setIcon(it.third)
            }
            selectedItemId = tab
            setOnItemSelectedListener { if (it.itemId != tab) switchTab(it.itemId); true }
        }
        shell.addView(nav)
    }

    private fun switchTab(id: Int) {
        if (detailId == null && rendered) positions[tab] = scroll.scrollY
        tab = id; detailId = null; limit = 20
        selectionMode = false
        selectedEventIds.clear()
        render(true)
    }
    private fun setDemo(enabled: Boolean) {
        prefs.demoMode = enabled; events = if (enabled) demoEvents else store.load()
        detailId = null; positions.clear(); render(true)
    }
    private fun settings() = startActivity(Intent(this, SettingsActivity::class.java))
    private fun openWeChat() {
        val target = packageManager.getLaunchIntentForPackage("com.tencent.mm")
        if (target == null) ui.feedback(shell, "未找到微信，请先安装或手动打开")
        else runCatching { startActivity(target) }.onFailure { ui.feedback(shell, "请手动打开微信") }
    }
    private fun launchSystem(intent: Intent) { runCatching { startActivity(intent) }.onFailure { ui.feedback(shell, "系统页面不可用，请在系统设置中授权") } }
    private fun isA11yEnabled(): Boolean = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        .orEmpty().split(':').any { it == "$packageName/com.google.android.accessibility.selecttospeak.SelectToSpeakService" }

    companion object {
        const val EXTRA_EVENT_ID = "com.attentionguard.app.event_id"
        private val TABS = intArrayOf(R.id.ag_attention, R.id.ag_ledger, R.id.ag_sources, R.id.ag_profile)
    }
}

