package com.attentionguard.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.attentionguard.app.ai.DeepSeekRelationshipClient
import com.attentionguard.app.core.*
import com.attentionguard.app.ui.AnalysisViews
import com.attentionguard.app.ui.GuardUi
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Profiles are explicit collections of recording IDs, never inferred from matching display names. */
class ObjectProfilesActivity : AppCompatActivity() {
    private lateinit var ui: GuardUi
    private lateinit var archive: MessageArchive
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private var profileId: String? = null
    private var recordingToLink: String? = null
    private var previousLink: ConversationProfile? = null
    private var returnResult = false
    private var searchQuery = ""
    private val actions = mutableListOf<View>()
    private var cancelButton: View? = null
    private var current: ProfileSnapshot? = null
    private var busy = false
    @Volatile private var closed = false
    @Volatile private var client: DeepSeekRelationshipClient? = null
    private val generation = AtomicInteger()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = GuardUi(this); archive = MessageArchive(this)
        profileId = savedInstanceState?.getString("profile_id") ?: intent.getStringExtra(EXTRA_PROFILE_ID)
        recordingToLink = savedInstanceState?.getString("link_recording") ?: intent.getStringExtra(EXTRA_RECORDING_ID)
        returnResult = intent.getBooleanExtra(EXTRA_RETURN_RESULT, false)
        val root = FrameLayout(this).apply { setBackgroundColor(ui.background) }
        val shell = ui.boundedColumn()
        root.addView(shell, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
        shell.addView(ui.row().apply {
            addView(ui.iconButton(R.drawable.ag_arrow_left, "返回") { back() })
            addView(ui.text("对象档案", R.dimen.ag_type_title, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.iconButton(R.drawable.ag_rotate_ccw, "刷新对象档案") { load() })
        })
        status = ui.text("", R.dimen.ag_type_caption, ui.sub).apply {
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8))
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        shell.addView(status)
        body = ui.column().apply { setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(28)) }
        shell.addView(ui.scroll(body), LinearLayout.LayoutParams(-1, 0, 1f))
        ui.install(this, root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = back() })
    }

    override fun onResume() { super.onResume(); load() }
    private fun valid(token: Int) = !closed && generation.get() == token
    private fun post(token: Int, block: () -> Unit) { main.post { if (valid(token)) block() } }
    private fun begin(text: String): Int {
        client?.cancel(); client = null
        busy = true; status.text = text
        actions.forEach { it.isEnabled = false }
        cancelButton?.visibility = View.VISIBLE
        return generation.incrementAndGet()
    }

    private fun load() {
        val token = begin("正在读取档案…")
        val id = profileId
        worker.execute {
            runCatching {
                val snapshot = id?.let(archive::profileSnapshot)
                val profiles = if (snapshot == null) archive.profiles().mapNotNull { archive.profileSnapshot(it.id) } else emptyList()
                val linkTitle = recordingToLink?.let { archive.recording(it)?.title }
                val previous = recordingToLink?.let(archive::profileForRecording)
                post(token) {
                    busy = false; current = snapshot; previousLink = previous
                    if (snapshot == null) { profileId = null; renderList(profiles, linkTitle) } else renderDetail(snapshot)
                }
            }.onFailure { post(token) { busy = false; status.text = "档案读取失败，请刷新重试" } }
        }
    }

    private fun renderList(profiles: List<ProfileSnapshot>, linkTitle: String?) {
        body.removeAllViews(); actions.clear(); cancelButton = null
        status.text = if (recordingToLink != null) "选择档案，关联记录：${linkTitle ?: "记录已不可用"}" else "明确关联的聊天记录与沟通画像"
        if (recordingToLink != null) body.addView(ui.text("请选择实际聊天对象；同名档案按关联记录区分。关联后可返回会话继续分析。",
            R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(8) })
        body.addView(ui.button("新建对象档案", R.drawable.ag_bookmark_plus) {
            if (!busy) AnalysisViews.editProfile(this, initialName = linkTitle.orEmpty()) { name, scene, kind ->
                confirmTransfer(name, null) { mutate("正在建档…") {
                    val profile = ConversationProfile(UUID.randomUUID().toString(), name, scene, kind)
                    archive.createProfile(profile, recordingToLink)
                    profile.id
                } }
            }
        }.apply { layoutParams = ui.lp(12); actions.add(this) })
        if (profiles.isEmpty()) {
            body.addView(ui.heading("从一段聊天开始"))
            body.addView(ui.text("1. 新建档案，选择场景与对话类型。\n2. 在微信悬浮窗开始记录，或关联已有记录。\n3. 点击更新画像，核对原文后再用于后续分析。", tint = ui.sub).apply { layoutParams = ui.lp(8) })
            return
        }
        val (searchBox, search) = ui.field("查找名称或关联会话", searchQuery, android.text.InputType.TYPE_CLASS_TEXT, R.id.ag_profile_search)
        body.addView(searchBox, ui.lp(12))
        val entries = ui.column()
        body.addView(entries, ui.lp(8))
        val permanentActions = actions.toList()
        fun renderMatches() {
            actions.retainAll(permanentActions.toSet())
            entries.removeAllViews()
            val query = search.text?.toString().orEmpty().trim()
            searchQuery = query
            val matches = profiles.filter { it.profile.name.contains(query, ignoreCase = true) ||
                it.recordings.any { record -> record.title.contains(query, ignoreCase = true) } ||
                it.profile.id.takeLast(6).contains(query, ignoreCase = true) }
            entries.addView(ui.text("${matches.size} 个档案", R.dimen.ag_type_caption, ui.sub))
            if (matches.isEmpty()) entries.addView(ui.text("没有匹配档案，可调整名称或会话关键词。", tint = ui.sub).apply { layoutParams = ui.lp(12) })
            matches.forEach { snapshot ->
                val profile = snapshot.profile
                val card = ui.panel().apply { layoutParams = ui.lp(12) }
                card.addView(ui.text(profile.name, R.dimen.ag_type_heading, bold = true))
                card.addView(ui.text("${profile.scene.label} · ${profile.kind.label} · 档案 ${profile.id.takeLast(6)}",
                    R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(6) })
                card.addView(ui.text("${snapshot.recordings.size} 份记录 · ${snapshot.messages.size} 条消息\n${snapshot.updateLabel}",
                    R.dimen.ag_type_label, ui.brand).apply { layoutParams = ui.lp(8) })
                if (snapshot.recordings.isNotEmpty()) card.addView(ui.text(
                    snapshot.recordings.take(2).joinToString(" / ") { it.title }, R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(6) })
                card.addView(ui.button(if (recordingToLink != null) "关联到这个档案" else "查看档案", R.drawable.ag_chevron_right, false) {
                    if (busy) return@button
                    if (recordingToLink != null) confirmTransfer(profile.name, profile.id) { mutate("正在关联记录…") {
                        archive.linkRecording(profile.id, requireNotNull(recordingToLink)); profile.id
                    } } else { profileId = profile.id; load() }
                }.apply { layoutParams = ui.lp(8); actions.add(this) })
                entries.addView(card)
            }
        }
        search.doAfterTextChanged { renderMatches() }
        renderMatches()
    }

    private fun confirmTransfer(name: String, id: String?, operation: () -> Unit) {
        val previous = previousLink
        if (recordingToLink == null || previous == null || previous.id == id) operation()
        else MaterialAlertDialogBuilder(this).setTitle("改为关联到 $name？")
            .setMessage("这份记录目前关联「${previous.name}」。改关联后，它将从原档案移除；两个档案的画像都需要更新。原始消息保留。")
            .setNegativeButton("取消", null).setPositiveButton("确认改关联") { _, _ -> operation() }.show()
    }

    private fun mutate(message: String, operation: () -> String?) {
        val token = begin(message)
        worker.execute {
            if (!valid(token)) return@execute
            runCatching(operation).fold(onSuccess = { id -> post(token) {
                if (returnResult && recordingToLink != null && id != null) {
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_PROFILE_ID, id)); finish(); return@post
                }
                profileId = id; recordingToLink = null; busy = false; load()
            } }, onFailure = { post(token) { busy = false; status.text = "未能保存，请刷新后重试" } })
        }
    }

    private fun renderDetail(snapshot: ProfileSnapshot) {
        val profile = snapshot.profile
        body.removeAllViews(); actions.clear()
        val report = snapshot.freshReport
        status.text = "${snapshot.recordings.size} 份记录 · ${snapshot.messages.size} 条已保存消息 · ${snapshot.updateLabel}"
        body.addView(ui.text(profile.name, R.dimen.ag_type_title, bold = true))
        body.addView(ui.text("${profile.scene.label} · ${profile.kind.label} · 档案 ${profile.id.takeLast(6)}", R.dimen.ag_type_caption, ui.sub)
            .apply { layoutParams = ui.lp(8) })
        snapshot.lastAnalyzedAt?.let { body.addView(ui.text("上次更新 · " + SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(it)),
            R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(8) }) }
        if (profile.kind == ProfileKind.UNKNOWN) body.addView(ui.text("对话类型尚未确认。编辑为「单人聊天」后可生成沟通画像；群聊只分析群体互动。",
            R.dimen.ag_type_label, ui.sub).apply { layoutParams = ui.lp(12) })
        body.addView(ui.button("更新本地画像", R.drawable.ag_scan_text) { analyze(false) }.apply {
            isEnabled = snapshot.messages.isNotEmpty(); layoutParams = ui.lp(16); actions.add(this)
        })
        body.addView(ui.button("DeepSeek 深化档案", R.drawable.ag_activity, false) { confirmCloud() }.apply {
            isEnabled = snapshot.messages.isNotEmpty(); layoutParams = ui.lp(8); actions.add(this)
        })
        cancelButton = ui.button("取消分析", R.drawable.ag_x, false) {
            generation.incrementAndGet(); client?.cancel(); client = null; busy = false; load()
        }.apply { layoutParams = ui.lp(8); visibility = View.GONE; body.addView(this) }
        if (snapshot.messages.isEmpty()) {
            body.addView(ui.text("先关联会话记录，或到微信悬浮窗选择这个档案并开始记录。", tint = ui.sub).apply { layoutParams = ui.lp(12) })
            body.addView(ui.button("查看会话记录", R.drawable.ag_notebook_tabs, false) {
                startActivity(Intent(this, ConversationAnalysisActivity::class.java))
            }.apply { layoutParams = ui.lp(8); actions.add(this) })
        }
        if (report == null && snapshot.previousSummary != null) {
            val previous = ui.text("上次摘要已过期，仅供回顾；本次分析不会引用它。\n\n" + snapshot.previousSummary, tint = ui.sub)
                .apply { visibility = View.GONE; layoutParams = ui.lp(8) }
            val toggle = ui.button("回顾上次摘要", R.drawable.ag_eye, false) {}
            toggle.setOnClickListener {
                previous.visibility = if (previous.visibility == View.GONE) View.VISIBLE else View.GONE
                toggle.text = if (previous.visibility == View.VISIBLE) "收起上次摘要" else "回顾上次摘要"
            }
            body.addView(toggle, ui.lp(12)); body.addView(previous)
        }
        listOfNotNull(snapshot.freshCloudReport?.let { "DeepSeek 深化" to it } ?:
            snapshot.freshLocalReport?.let { "本地画像" to it }).forEach { (label, it) ->
            body.addView(ui.heading(label))
            it.context?.let { context -> AnalysisViews.append(body, context,
                snapshot.messages.associate { row -> "archive:${row.id}" to row.message.text },
                snapshot.messages.map(AnalysisInput::archived), snapshot.recordings.associate { row -> row.id to row.title }) }
                ?: body.addView(ui.text(it.summary))
            AnalysisViews.metrics(body, it.metrics)
        }
        body.addView(ui.heading("档案与记录"))
        body.addView(ui.button("编辑名称与场景", R.drawable.ag_settings_2, false) {
            if (!busy) AnalysisViews.editProfile(this, profile) { name, scene, kind ->
                mutate("正在保存…") { archive.updateProfile(profile.id, name, scene, kind); profile.id }
            }
        }.apply { layoutParams = ui.lp(12); actions.add(this) })
        body.addView(ui.button("关联已有会话记录", R.drawable.ag_notebook_tabs, false) { chooseRecording(snapshot) }
            .apply { layoutParams = ui.lp(8); actions.add(this) })
        body.addView(ui.heading("关联记录"))
        snapshot.recordings.forEach { recording ->
            body.addView(ui.button(recording.title, R.drawable.ag_notebook_tabs, false) {
                if (!busy) startActivity(Intent(this, ConversationAnalysisActivity::class.java)
                    .putExtra(ConversationAnalysisActivity.EXTRA_RECORDING_ID, recording.id))
            }.apply { layoutParams = ui.lp(8); actions.add(this) })
            body.addView(ui.button("解除关联", R.drawable.ag_x, false) {
                if (!busy) MaterialAlertDialogBuilder(this).setTitle("解除「${recording.title}」的关联？")
                    .setMessage("原始记录保留，档案画像将标记为待更新。").setNegativeButton("取消", null)
                    .setPositiveButton("解除关联") { _, _ ->
                        mutate("正在解除关联…") { archive.unlinkRecording(profile.id, recording.id); profile.id }
                    }.show()
            }.apply { actions.add(this) })
        }
        body.addView(ui.button("删除档案", R.drawable.ag_x, false) {
            if (!busy) MaterialAlertDialogBuilder(this).setTitle("删除这个对象档案？")
                .setMessage("删除画像和关联关系，原始聊天仍保留在会话记录中。")
                .setNegativeButton("取消", null).setPositiveButton("删除档案") { _, _ ->
                    mutate("正在删除…") { archive.deleteProfile(profile.id); null }
                }.show()
        }.apply { layoutParams = ui.lp(24); actions.add(this) })
    }

    private fun chooseRecording(snapshot: ProfileSnapshot) {
        if (busy) return
        val token = begin("正在读取可关联记录…")
        worker.execute {
            val rows = runCatching { archive.recordings().filter { row -> snapshot.recordings.none { it.id == row.id } }
                .map { it to archive.profileForRecording(it.id) } }
            post(token) {
                busy = false
                renderDetail(snapshot)
                rows.fold(onSuccess = { records ->
                    if (records.isEmpty()) { status.text = "没有其他可关联记录"; return@fold }
                    MaterialAlertDialogBuilder(this).setTitle("选择同一对象的会话记录")
                        .setItems(records.map { (record, owner) -> "${record.title} · ${record.id.takeLast(6)}" +
                            (owner?.let { " · 已关联 ${it.name}" } ?: " · 未关联") }.toTypedArray()) { _, index ->
                            val (record, owner) = records[index]
                            fun link() = mutate("正在关联…") { archive.linkRecording(snapshot.profile.id, record.id); snapshot.profile.id }
                            if (owner == null) link()
                            else MaterialAlertDialogBuilder(this).setTitle("转移这份记录的档案关联？")
                                .setMessage("从「${owner.name}」转到「${snapshot.profile.name}」。两个档案的画像都将待更新，原始记录保留。")
                                .setNegativeButton("取消", null).setPositiveButton("确认转移") { _, _ -> link() }.show()
                        }.setNegativeButton("取消", null).show()
                }, onFailure = { status.text = "记录读取失败，请刷新重试" })
            }
        }
    }

    private fun confirmCloud() {
        if (busy) return
        val snapshot = current ?: return
        val prefs = Prefs(this)
        if (!prefs.cloudEnabled || !prefs.hasKey()) { status.text = "请先在设置中启用并配置 DeepSeek"; return }
        val token = begin("正在准备发送范围…")
        worker.execute {
            runCatching {
                val base = RelationshipAnalysis.analyze(snapshot.messages, snapshot.profile.name, snapshot.profile.scene, snapshot.profile)
                val input = AnalysisInput.deep(snapshot.messages, base, snapshot.profile)
                post(token) {
                    busy = false; renderDetail(snapshot)
                    if (input.messages.isEmpty()) { status.text = "没有可发送的原文，请先核对自动识别的文字，或使用自由分析"; return@post }
                    AnalysisViews.confirmInput(this, input, "对象档案 · ${snapshot.profile.name}") {
                        if (valid(token)) analyze(true)
                    }
                }
            }.onFailure { post(token) { busy = false; renderDetail(snapshot); status.text = "发送范围读取失败，请刷新重试" } }
        }
    }

    private fun analyze(cloud: Boolean) {
        if (busy) return
        val snapshot = current ?: return
        if (snapshot.messages.isEmpty()) { status.text = "请先关联已保存的会话记录"; return }
        val token = begin(if (cloud) "正在深化档案，可取消…" else "正在更新本地画像…")
        worker.execute {
            runCatching {
                if (!valid(token)) return@execute
                val profile = snapshot.profile
                val base = RelationshipAnalysis.analyze(snapshot.messages, profile.name, profile.scene, profile)
                    .let { report -> report.copy(limitations = report.limitations + "档案只包含明确关联的记录，跨记录不代表连续对话。") }
                check(archive.saveProfileAnalysis(profile.id, snapshot.fingerprint, base, canWrite = { valid(token) })) { "stale" }
                if (cloud) {
                    val prefs = Prefs(this)
                    check(prefs.cloudEnabled && prefs.hasKey())
                    val network = DeepSeekRelationshipClient(prefs.activeKey(), prefs.activeModel())
                    client = network
                    if (!valid(token)) { network.cancel(); return@execute }
                    val enhanced = try { network.analyze(snapshot.messages, base, profile) } finally { if (client === network) client = null }
                    if (!valid(token)) return@execute
                    check(archive.saveProfileAnalysis(profile.id, snapshot.fingerprint, enhanced, cloud = true,
                        canWrite = { valid(token) })) { "stale" }
                }
                post(token) { busy = false; load() }
            }.onFailure { error ->
                val updated = runCatching { archive.profileSnapshot(snapshot.profile.id) }.getOrNull()
                post(token) {
                    busy = false
                    updated?.let { current = it; renderDetail(it) }
                    status.text = if (error.message == "stale") "记录有变化，请刷新后重新分析" else "深化未完成，本地结果保留；可刷新或重试"
                }
            }
        }
    }

    private fun back() {
        generation.incrementAndGet(); client?.cancel(); client = null; busy = false
        if (profileId == null || returnResult || intent.hasExtra(EXTRA_PROFILE_ID)) finish() else { profileId = null; current = null; load() }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("profile_id", profileId); outState.putString("link_recording", recordingToLink)
        super.onSaveInstanceState(outState)
    }
    override fun onStop() {
        generation.incrementAndGet(); client?.cancel(); client = null; busy = false
        super.onStop()
    }
    override fun onDestroy() {
        closed = true; generation.incrementAndGet(); client?.cancel(); main.removeCallbacksAndMessages(null)
        worker.execute { archive.close() }; worker.shutdown()
        super.onDestroy()
    }
    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_RECORDING_ID = "link_recording_id"
        const val EXTRA_RETURN_RESULT = "return_link_result"
    }
}
