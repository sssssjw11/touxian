package com.attentionguard.app.capture

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import com.attentionguard.app.ObjectProfilesActivity
import com.attentionguard.app.ai.DeepSeekContextClient
import com.attentionguard.app.core.*
import com.attentionguard.app.ui.AnalysisViews
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class LiveAnalysisState(
    val scene: AnalysisScene = AnalysisScene.GENERAL,
    val profileName: String? = null,
    val insight: ContextInsight? = null,
    val busy: Boolean = false,
    val status: String = "",
    val originals: Map<String, String> = emptyMap()
)

/** Ephemeral live analysis. Only explicit recording/profile actions write chat data. */
class LiveIntentController(
    private val context: Context,
    private val publish: (LiveAnalysisState) -> Unit,
    private val notify: (String) -> Unit,
    private val ensureRecording: ((ChatRecording) -> Unit) -> Unit,
    private val currentRecording: () -> ChatRecording?,
    private val pauseRecording: () -> Unit,
    private val clientFactory: (String, String) -> DeepSeekContextClient = { key, model -> DeepSeekContextClient(key, model) },
    private val credentials: () -> Pair<String, String>? = {
        val settings = Prefs(context)
        if (settings.cloudEnabled && settings.hasKey()) settings.activeKey() to settings.activeModel() else null
    }
) {
    private val archive = MessageArchive(context)
    private val main = Handler(Looper.getMainLooper())
    private val storage = Executors.newSingleThreadExecutor()
    private val network = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger()
    @Volatile private var closed = false
    @Volatile private var client: DeepSeekContextClient? = null
    private var current: ChatSnapshot? = null
    private var profile: ConversationProfile? = null
    private var checkingProfile = false
    private var dialog: AlertDialog? = null
    private var state = LiveAnalysisState()

    private fun valid(token: Int) = !closed && generation.get() == token
    private fun post(token: Int, action: () -> Unit) { main.post { if (valid(token)) action() } }
    private fun show(value: LiveAnalysisState) { state = value; if (!closed) publish(value) }
    private fun invalidate() {
        generation.incrementAndGet()
        client?.cancel(); client = null
        dialog?.dismiss(); dialog = null
    }

    fun reset() {
        invalidate(); current = null; profile = null
        show(LiveAnalysisState())
    }

    fun observe(snapshot: ChatSnapshot?) {
        if (closed) return
        if (snapshot == null) { if (current != null) reset(); return }
        val changed = current?.signature() != snapshot.signature()
        if (current?.title != snapshot.title) {
            invalidate(); profile = null
            show(LiveAnalysisState())
        } else if (changed) {
            invalidate()
            show(state.copy(insight = null, busy = false, status = "当前消息已更新", originals = emptyMap()))
        }
        current = snapshot
        val selected = profile ?: return
        if (checkingProfile) return
        checkingProfile = true
        storage.execute {
            val latest = runCatching { archive.profile(selected.id) }.getOrNull()
            main.post {
                checkingProfile = false
                if (closed || profile?.id != selected.id) return@post
                if (latest?.cacheKey != profile?.cacheKey) {
                    invalidate(); profile = latest
                    show(state.copy(profileName = latest?.name, insight = null, busy = false,
                        status = "档案内容已变化，请重新深化", originals = emptyMap()))
                }
            }
        }
    }

    fun cancel() {
        invalidate()
        show(state.copy(busy = false, status = "深化已取消，本地判断保留"))
    }

    fun chooseScene() {
        invalidate()
        show(state.copy(busy = false))
        dialog = AnalysisViews.show(MaterialAlertDialogBuilder(AnalysisViews.context(context))
            .setTitle("本次分析场景")
            .setSingleChoiceItems(AnalysisScene.entries.map { it.label }.toTypedArray(), state.scene.ordinal) { choice, index ->
                choice.dismiss(); invalidate()
                show(state.copy(scene = AnalysisScene.entries[index], insight = null, busy = false,
                    status = "场景已切换，可重新深化", originals = emptyMap()))
            }.setNegativeButton("取消", null).create(), true)
    }

    fun deepen() {
        val snapshot = current ?: run { notify("请先打开可读的聊天"); return }
        if (credentials() == null) { notify("请先在偷闲设置中启用并配置 DeepSeek"); return }
        invalidate()
        val token = generation.get()
        val selected = profile
        val scene = state.scene
        show(state.copy(busy = true, status = "正在准备发送范围"))
        storage.execute {
            runCatching {
                val history = selected?.let { archive.profileSnapshot(it.id) }
                val input = AnalysisInput.live(snapshot, scene, history?.messages.orEmpty(), history?.profile,
                    history?.freshReport?.context?.summary ?: history?.freshReport?.summary.orEmpty())
                require(input.messages.isNotEmpty())
                post(token) {
                    if (selected != null && history?.profile?.cacheKey != selected.cacheKey) {
                        profile = history?.profile
                        show(state.copy(busy = false, insight = null, status = "档案已更新，请重新点按深入理解"))
                        return@post
                    }
                    show(state.copy(busy = false))
                    val liveCount = input.messages.count { it.ref.startsWith("live:") }
                    val savedCount = input.messages.size - liveCount
                    dialog = AnalysisViews.show(MaterialAlertDialogBuilder(AnalysisViews.context(context))
                        .setTitle("发送这些内容到 DeepSeek？")
                        .setMessage("场景：${scene.label}\n当前可见消息 $liveCount 条；历史依据 $savedCount 条。" +
                            (if (input.profileSummary.isNotBlank()) "\n附带已核对档案的摘要背景。" else "") +
                            "\n正文与摘要共 ${input.messages.sumOf { it.text.length } + input.profileSummary.length} 字符。" +
                            "\n实时结果仅在本次悬浮窗显示。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("发送并深入理解") { _, _ ->
                            if (valid(token)) send(token, input, history, snapshot)
                        }.create(), true)
                }
            }.onFailure { post(token) { show(state.copy(busy = false, status = "无法准备原文，请刷新后重试")) } }
        }
    }

    private fun send(token: Int, input: AnalysisInput, history: ProfileSnapshot?, snapshot: ChatSnapshot) {
        if (!valid(token)) return
        val config = credentials() ?: return
        show(state.copy(busy = true, insight = null, status = "正在深入理解，可取消"))
        val connection = clientFactory(config.first, config.second)
        client = connection
        network.execute {
            runCatching {
                if (!valid(token)) return@execute
                val result = connection.analyze(input)
                if (!valid(token)) return@execute
                if (history != null && archive.profileSnapshot(history.profile.id)?.fingerprint != history.fingerprint) {
                    post(token) { show(state.copy(busy = false, status = "档案原文已变化，请重新深化")) }
                    return@execute
                }
                post(token) {
                    show(state.copy(insight = result, busy = false, status = "DeepSeek · 已核对原文引用",
                        originals = sourceTexts(input, snapshot, history)))
                }
            }.onFailure { post(token) { show(state.copy(busy = false, status = "深化未完成，本地判断保留；可点按重试")) } }
            if (client === connection) client = null
        }
    }

    private fun sourceTexts(input: AnalysisInput, snapshot: ChatSnapshot, history: ProfileSnapshot?): Map<String, String> {
        val visible = snapshot.messages.filter { it.captureMethod == "nodes" && it.side in setOf("me", "other") &&
            it.type in setOf(MessageType.TEXT, MessageType.STICKER) && it.text.isNotBlank() }.takeLast(AnalysisInput.LIVE_LIMIT)
        val full = visible.mapIndexed { i, msg -> "live:$i" to msg.text }.toMap() +
            history?.messages.orEmpty().associate { "archive:${it.id}" to it.message.text }
        return input.messages.associate { it.ref to (full[it.ref] ?: it.text) }
    }

    fun showEvidence(title: String, proofs: List<ContextEvidence>) {
        if (state.insight == null || current == null) return
        dialog?.dismiss()
        dialog = AnalysisViews.evidence(context, title, proofs, state.originals, overlay = true)
    }

    fun createProfile() {
        val snapshot = current
        if (snapshot?.title.isNullOrBlank() || WeChatAdapter.isTruncatedTitle(snapshot?.title.orEmpty())) {
            notify("请先标记当前会话名称，再为对象建档"); return
        }
        invalidate(); show(state.copy(busy = false))
        val token = generation.get()
        dialog = AnalysisViews.editProfile(context, initialName = snapshot?.title.orEmpty(), overlay = true) { name, scene, kind ->
            if (!valid(token)) return@editProfile
            ensureRecording { recording ->
                if (!valid(token)) return@ensureRecording
                storage.execute {
                    runCatching {
                        if (!valid(token)) return@execute
                        val created = ConversationProfile(UUID.randomUUID().toString(), name, scene, kind)
                        archive.createProfile(created, recording.id)
                        requireNotNull(archive.profile(created.id))
                    }.onSuccess { created -> post(token) {
                        profile = created
                        show(LiveAnalysisState(scene, created.name, status = "已建档并关联当前记录"))
                    } }.onFailure { post(token) { notify("建档失败，原会话记录保留，请重试") } }
                }
            }
        }
    }

    fun chooseProfile() {
        if (current == null) { notify("请先打开目标聊天"); return }
        invalidate(); show(state.copy(busy = false))
        val token = generation.get()
        storage.execute {
            val result = runCatching { archive.profiles() }
            post(token) {
                result.onSuccess { profiles ->
                    val labels = listOf("本次不使用对象档案") + profiles.map { "${it.name} · ${it.scene.label} · ${it.id.takeLast(6)}" }
                    dialog = AnalysisViews.show(MaterialAlertDialogBuilder(AnalysisViews.context(context))
                        .setTitle("请选择当前聊天对象")
                        .setItems(labels.toTypedArray()) { _, index ->
                            if (!valid(token)) return@setItems
                            if (index == 0) {
                                profile = null; show(LiveAnalysisState(state.scene, status = "本次仅使用当前消息"))
                            } else selectProfile(profiles[index - 1], token)
                        }.setNegativeButton("取消", null).create(), true)
                }.onFailure { notify("档案读取失败，请重试") }
            }
        }
    }

    private fun selectProfile(selected: ConversationProfile, token: Int) {
        val recording = currentRecording()?.takeIf {
            it.state == RecordingState.ACTIVE && ConversationIdentity.sameTitle(it.title, current?.title.orEmpty())
        }
        fun useProfile() {
            if (!valid(token)) return
            storage.execute {
                runCatching {
                    if (!valid(token)) return@execute
                    if (recording != null) archive.linkRecording(selected.id, recording.id)
                    requireNotNull(archive.profile(selected.id))
                }.onSuccess { latest -> post(token) {
                    profile = latest
                    show(LiveAnalysisState(latest.scene, latest.name, status =
                        if (recording == null) "已选择档案；新消息需明确开始记录后关联" else "已关联当前记录"))
                } }.onFailure { post(token) { notify("关联失败，请重试") } }
            }
        }
        if (recording == null) useProfile()
        else dialog = AnalysisViews.show(MaterialAlertDialogBuilder(AnalysisViews.context(context))
            .setTitle("关联当前记录到 ${selected.name}？")
            .setMessage("记录 ${recording.id.takeLast(6)} 的已有消息和后续新增消息都将进入这个档案，并替换它原先的档案关联。请核对确为同一对象。")
            .setNegativeButton("取消", null).setPositiveButton("关联并使用") { _, _ -> useProfile() }.create(), true)
    }

    fun openProfiles() {
        val id = profile?.id
        reset(); pauseRecording()
        runCatching {
            context.startActivity(Intent(context, ObjectProfilesActivity::class.java)
                .putExtra(ObjectProfilesActivity.EXTRA_PROFILE_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { notify("请从偷闲的会话分析页进入对象档案") }
    }

    fun recordingStarted(recording: ChatRecording) {
        val selected = profile ?: return
        val token = generation.get()
        storage.execute {
            runCatching {
                if (!valid(token)) return@execute
                archive.linkRecording(selected.id, recording.id)
                requireNotNull(archive.profile(selected.id))
            }.onSuccess { latest -> post(token) {
                profile = latest
                show(state.copy(insight = null, status = "已关联当前记录，画像待更新"))
            } }.onFailure { post(token) { notify("档案关联失败，请在档案页重新关联") } }
        }
    }

    fun close() {
        closed = true; invalidate(); main.removeCallbacksAndMessages(null)
        network.shutdownNow()
        // The network executor can still unwind after disconnect; do not close its shared helper early.
        storage.execute { network.awaitTermination(25, java.util.concurrent.TimeUnit.SECONDS); archive.close() }
        storage.shutdown()
    }
}
