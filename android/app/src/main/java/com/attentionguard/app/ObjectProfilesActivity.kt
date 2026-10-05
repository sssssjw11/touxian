package com.attentionguard.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.attentionguard.app.ai.DeepSeekRelationshipClient
import com.attentionguard.app.core.*
import com.attentionguard.app.ui.AnalysisViews
import com.attentionguard.app.ui.GuardUi
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Profiles are explicit collections of recording IDs, never inferred from matching display names. */
class ObjectProfilesActivity : AppCompatActivity() {
    private lateinit var ui: GuardUi
    private lateinit var archive: MessageArchive
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private var profileId: String? = null
    private var recordingToLink: String? = null
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
        return generation.incrementAndGet()
    }

    private fun load() {
        val token = begin("正在读取档案…")
        val id = profileId
        worker.execute {
            runCatching {
                val snapshot = id?.let(archive::profileSnapshot)
                val profiles = if (snapshot == null) archive.profiles() else emptyList()
                val linkTitle = recordingToLink?.let { archive.recording(it)?.title }
                post(token) {
                    busy = false; current = snapshot
                    if (snapshot == null) { profileId = null; renderList(profiles, linkTitle) } else renderDetail(snapshot)
                }
            }.onFailure { post(token) { busy = false; status.text = "档案读取失败，请刷新重试" } }
        }
    }

    private fun renderList(profiles: List<ConversationProfile>, linkTitle: String?) {
        body.removeAllViews()
        status.text = if (recordingToLink != null) "选择档案，关联记录：${linkTitle ?: "记录已不可用"}" else "明确关联的聊天记录与沟通画像"
        body.addView(ui.button("新建对象档案", R.drawable.ag_bookmark_plus) {
            if (!busy) AnalysisViews.editProfile(this, initialName = linkTitle.orEmpty()) { name, scene, kind ->
                mutate("正在建档…") {
                    val profile = ConversationProfile(UUID.randomUUID().toString(), name, scene, kind)
                    archive.createProfile(profile, recordingToLink)
                    profile.id
                }
            }
        }.apply { layoutParams = ui.lp(12) })
        if (profiles.isEmpty()) body.addView(ui.text("暂无对象档案。可从微信悬浮窗建档，也可关联已有会话记录。", tint = ui.sub).apply { layoutParams = ui.lp(20) })
        profiles.forEach { profile ->
            body.addView(ui.button("${profile.name} · ${profile.scene.label}", R.drawable.ag_notebook_tabs, false) {
                if (busy) return@button
                if (recordingToLink != null) mutate("正在关联记录…") {
                    archive.linkRecording(profile.id, requireNotNull(recordingToLink)); profile.id
                } else { profileId = profile.id; load() }
            }.apply { layoutParams = ui.lp(12) })
            body.addView(ui.text("${profile.kind.label} · 档案 ${profile.id.takeLast(6)}", R.dimen.ag_type_caption, ui.sub))
        }
    }

    private fun mutate(message: String, operation: () -> String?) {
        val token = begin(message)
        worker.execute {
            if (!valid(token)) return@execute
            runCatching(operation).fold(onSuccess = { id -> post(token) {
                profileId = id; recordingToLink = null; busy = false; load()
            } }, onFailure = { post(token) { busy = false; status.text = "未能保存，请刷新后重试" } })
        }
    }

    private fun renderDetail(snapshot: ProfileSnapshot) {
        val profile = snapshot.profile
        body.removeAllViews()
        val report = snapshot.freshReport
        status.text = "${snapshot.recordings.size} 份记录 · ${snapshot.messages.size} 条已保存消息 · " +
            if (report != null) "画像已更新" else if (profile.analysis != null) "原文或场景有变化，画像待更新" else "尚未生成画像"
        body.addView(ui.text(profile.name, R.dimen.ag_type_title, bold = true))
        body.addView(ui.text("${profile.scene.label} · ${profile.kind.label} · 档案 ${profile.id.takeLast(6)}", R.dimen.ag_type_caption, ui.sub)
            .apply { layoutParams = ui.lp(8) })
        body.addView(ui.button("编辑名称与场景", R.drawable.ag_settings_2, false) {
            if (!busy) AnalysisViews.editProfile(this, profile) { name, scene, kind ->
                mutate("正在保存…") { archive.updateProfile(profile.id, name, scene, kind); profile.id }
            }
        }.apply { layoutParams = ui.lp(12) })
        body.addView(ui.button("关联已有会话记录", R.drawable.ag_notebook_tabs, false) { chooseRecording(snapshot) }
            .apply { layoutParams = ui.lp(8) })
        body.addView(ui.button("更新本地画像", R.drawable.ag_scan_text) { analyze(false) }.apply {
            isEnabled = snapshot.messages.isNotEmpty(); layoutParams = ui.lp(12)
        })
        body.addView(ui.button("DeepSeek 深化档案", R.drawable.ag_activity, false) { confirmCloud() }.apply {
            isEnabled = snapshot.messages.isNotEmpty(); layoutParams = ui.lp(8)
        })
        body.addView(ui.button("取消分析", R.drawable.ag_x, false) {
            generation.incrementAndGet(); client?.cancel(); client = null; busy = false
            status.text = "分析已取消"; load()
        }.apply { layoutParams = ui.lp(8) })
        listOfNotNull(snapshot.freshLocalReport?.let { "本地画像" to it },
            snapshot.freshCloudReport?.let { "DeepSeek 深化" to it }).forEach { (label, it) ->
            body.addView(ui.heading(label))
            it.metrics.forEach { metric -> body.addView(ui.statusRow(metric.label, metric.value, ui.brand).apply { layoutParams = ui.lp(8) }) }
            it.context?.let { context -> AnalysisViews.append(body, context,
                snapshot.messages.associate { row -> "archive:${row.id}" to row.message.text }) }
                ?: body.addView(ui.text(it.summary))
        }
        body.addView(ui.heading("关联记录"))
        snapshot.recordings.forEach { recording ->
            body.addView(ui.button(recording.title, R.drawable.ag_notebook_tabs, false) {
                if (!busy) startActivity(Intent(this, ConversationAnalysisActivity::class.java)
                    .putExtra(ConversationAnalysisActivity.EXTRA_RECORDING_ID, recording.id))
            }.apply { layoutParams = ui.lp(8) })
            body.addView(ui.button("解除关联", R.drawable.ag_x, false) {
                if (!busy) mutate("正在解除关联…") { archive.unlinkRecording(profile.id, recording.id); profile.id }
            })
        }
        body.addView(ui.button("删除档案", R.drawable.ag_x, false) {
            if (!busy) MaterialAlertDialogBuilder(this).setTitle("删除这个对象档案？")
                .setMessage("删除画像和关联关系，原始聊天仍保留在会话记录中。")
                .setNegativeButton("取消", null).setPositiveButton("删除档案") { _, _ ->
                    mutate("正在删除…") { archive.deleteProfile(profile.id); null }
                }.show()
        }.apply { layoutParams = ui.lp(24) })
    }

    private fun chooseRecording(snapshot: ProfileSnapshot) {
        if (busy) return
        val token = begin("正在读取可关联记录…")
        worker.execute {
            val rows = runCatching { archive.recordings().filter { row -> snapshot.recordings.none { it.id == row.id } } }
            post(token) {
                busy = false
                rows.fold(onSuccess = { records ->
                    if (records.isEmpty()) { status.text = "没有其他可关联记录"; return@fold }
                    MaterialAlertDialogBuilder(this).setTitle("关联记录（转移其原有档案关联）")
                        .setItems(records.map { "${it.title} · 记录 ${it.id.takeLast(6)}" }.toTypedArray()) { _, index ->
                            mutate("正在关联…") { archive.linkRecording(snapshot.profile.id, records[index].id); snapshot.profile.id }
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
        MaterialAlertDialogBuilder(this).setTitle("发送档案选定内容进行深化？")
            .setMessage("对象：${snapshot.profile.name}\n场景：${snapshot.profile.scene.label}\n" +
                "统计覆盖 ${snapshot.messages.size} 条已关联消息；发送最多 90 条原话、正文最多 40,000 字符。本地分析会保留。")
            .setNegativeButton("取消", null).setPositiveButton("发送并分析") { _, _ -> analyze(true) }.show()
    }

    private fun analyze(cloud: Boolean) {
        if (busy) return
        val snapshot = current ?: return
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
        if (profileId == null) finish() else { profileId = null; current = null; load() }
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
    }
}
