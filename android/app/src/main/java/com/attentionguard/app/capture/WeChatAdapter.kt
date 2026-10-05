package com.attentionguard.app.capture

import android.content.res.Resources
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.attentionguard.app.core.ChatSnapshot
import com.attentionguard.app.core.MessageType
import com.attentionguard.app.core.Msg
import kotlin.math.abs

data class ChatInspection(
    val snapshot: ChatSnapshot?, val nodeCount: Int, val knownBubbles: Int,
    val structuralBubbles: Int, val reason: String,
    val scrollTarget: AccessibilityNodeInfo? = null,
    val ocrRegions: List<ChatOcrRegion> = emptyList(),
    val titleBounds: Rect? = null,
    /** Title came from a known title id rather than the generic header scan. */
    val titleFromId: Boolean = false
)

data class ChatOcrRegion(val bounds: Rect, val side: String, val date: String?, val timeLabel: String?)

/** Accept only visible message bodies, never a whole-screen text scrape. */
class WeChatAdapter {
    val pkg = "com.tencent.mm"
    private data class Entry(val node: AccessibilityNodeInfo, val bounds: Rect, val parent: Int,
                             val visible: Boolean, val blocked: Boolean)
    private data class Bubble(val index: Int, val text: String, val side: String, val sender: String?)

    fun extract(root: AccessibilityNodeInfo, res: Resources) = inspect(root, res).snapshot

    fun inspect(root: AccessibilityNodeInfo, res: Resources): ChatInspection {
        fun failure(reason: String) = ChatInspection(null, 0, 0, 0, reason)
        if (root.packageName?.toString() != pkg) return failure("非微信前台窗口")
        val viewport = Rect(0, 0, res.displayMetrics.widthPixels, res.displayMetrics.heightPixels)
        val rootBounds = Rect().also { root.getBoundsInScreen(it) }
        if (rootBounds.isEmpty || !viewport.intersect(rootBounds)) return failure("窗口边界暂不可用")
        val density = res.displayMetrics.density
        val entries = ArrayList<Entry>()
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to -1)
        while (stack.isNotEmpty() && entries.size < 6000) {
            val (node, parent) = stack.removeLast()
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            val foreign = node.packageName?.toString()?.let { it != pkg } ?: false
            val blocked = foreign || node.isEditable || (parent >= 0 && entries[parent].blocked)
            val visible = !foreign && node.isVisibleToUser && !bounds.isEmpty && Rect.intersects(viewport, bounds)
            val index = entries.size
            entries.add(Entry(node, bounds, parent, visible, blocked))
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it to index) }
        }
        fun inside(index: Int, ancestor: Int): Boolean {
            var current = index
            while (current >= 0) { if (current == ancestor) return true; current = entries[current].parent }
            return false
        }
        fun body(index: Int): String {
            val direct = entries[index].node.text?.toString()?.trim().orEmpty()
            if (direct.isNotEmpty()) return direct
            val parts = entries.indices.filter { it != index && inside(it, index) && entries[it].visible && !entries[it].blocked }
                .filter { !entries[it].node.text.isNullOrBlank() }
            val leaves = parts.filter { item -> parts.none { other -> other != item && inside(item, other) } }
            val text = leaves.sortedBy { entries[it].bounds.top }.joinToString("\n") { entries[it].node.text.toString().trim() }
            if (text.isNotEmpty()) return text
            val descriptions = buildList {
                entries[index].node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
                entries.indices
                    .filter { it != index && inside(it, index) && entries[it].visible && !entries[it].blocked }
                    .mapNotNull { entries[it].node.contentDescription?.toString()?.trim() }
                    .filter { it.isNotBlank() }
                    .forEach(::add)
            }
            val desc = descriptions.firstOrNull().orEmpty()
            val viewId = entries[index].node.viewIdResourceName
            if (viewId == STICKER_ID) {
                // WeChat uses the same bkm id for image media and custom stickers.
                // An ancestor labelled "图片" is a media placeholder; otherwise
                // keep the sticker as a low-confidence, unnamed affect cue.
                var ancestor = entries[index].parent
                var ancestorDescription = ""
                while (ancestor >= 0) {
                    ancestorDescription = entries[ancestor].node.contentDescription?.toString()?.trim().orEmpty()
                    if (ancestorDescription.isNotBlank()) break
                    ancestor = entries[ancestor].parent
                }
                if (ancestorDescription.contains("图片") ||
                    ancestorDescription.equals("image", ignoreCase = true)) return "[图片]"
            }
            return when {
                desc.contains("图片") -> "[图片]"
                desc.contains("文件") -> "[文件]"
                desc.contains("语音") -> "[语音]"
                desc.contains("视频") -> "[视频]"
                desc.contains("表情") -> "[表情]"
                desc.contains("头像") -> ""
                desc.isNotBlank() && desc.length <= 24 -> "[表情:${desc.take(20)}]"
                viewId == STICKER_ID -> "[表情:未命名贴纸]"
                else -> ""
            }
        }
        val known = entries.indices.filter {
            entries[it].node.viewIdResourceName in setOf(BUBBLE_ID, STICKER_ID) &&
                entries[it].visible && !entries[it].blocked
        }
        val candidates = known.toMutableList()
        val inputs = entries.filter { it.visible && it.node.isEditable && it.bounds.top > viewport.top + viewport.height() * .35 }
        val lists = entries.indices.filter { i ->
            val e = entries[i]
            val cls = e.node.className?.toString().orEmpty()
            e.visible && !e.blocked && (e.node.isScrollable || cls.endsWith("ListView") || cls.endsWith("RecyclerView")) &&
                e.bounds.height() > viewport.height() * .28
        }
        // Version fallback needs a bottom composer, message list, avatar and a
        // long-clickable message body in the same row. A search/list page fails.
        if (known.isEmpty() && inputs.isNotEmpty()) {
            for (list in lists) {
                if (inputs.none { entries[list].bounds.bottom <= it.bounds.bottom }) continue
                for (i in entries.indices) {
                    val e = entries[i]
                    if (!e.visible || e.blocked || !inside(i, list) || !e.node.isLongClickable ||
                        e.bounds.width() >= viewport.width() * .90 || body(i).isBlank()) continue
                    var row = i
                    while (entries[row].parent >= 0 && entries[row].parent != list) row = entries[row].parent
                    val avatar = entries.indices.any { j ->
                        val a = entries[j]
                        val desc = a.node.contentDescription?.toString().orEmpty()
                        a.visible && !a.blocked && !inside(j, i) && inside(j, row) &&
                            (desc.contains("头像") || desc.contains("avatar", true)) &&
                            abs(a.bounds.centerY() - e.bounds.centerY()) < maxOf(e.bounds.height(), (80 * density).toInt())
                    }
                    if (avatar) candidates.add(i)
                }
            }
        }
        val selected = candidates.distinct().filter { item -> candidates.none { other -> other != item && inside(item, other) } }
        val titleLimit = viewport.top + minOf((140 * density).toInt(), viewport.height() / 4)
        val actionBar = entries.filter { e ->
            e.visible && !e.blocked && e.node.viewIdResourceName in ACTION_BAR_IDS &&
                e.bounds.top <= viewport.top + (24 * density).toInt() + 160
        }.maxByOrNull { it.bounds.bottom - it.bounds.top }
        val actionBarIndex = actionBar?.let(entries::indexOf)
        val actionBarTop = actionBar?.bounds?.top ?: viewport.top
        val actionBarBottom = actionBar?.bounds?.bottom ?: titleLimit

        fun textValue(entry: Entry): String =
            entry.node.text?.toString()?.trim().orEmpty().ifBlank {
                entry.node.contentDescription?.toString()?.trim().orEmpty()
            }

        fun isPromotionalLabel(value: String): Boolean =
            PROMOTIONAL_LABEL.containsMatchIn(value)

        // Recent WeChat builds expose the ActionBar title as `obn`; the older
        // generic scan could instead select a "本地热搜头条" banner below it.
        val exactTitleEntry = entries.filterIndexed { index, e ->
            val text = textValue(e)
            e.visible && !e.blocked && e.node.viewIdResourceName in TITLE_IDS &&
                text.isNotEmpty() && text.length <= 120 &&
                ChatDateParser.parse(text) == null && selected.none { inside(index, it) } &&
                lists.none { inside(index, it) } &&
                (e.node.viewIdResourceName == "com.tencent.mm:id/obn" || actionBarIndex == null || inside(index, actionBarIndex)) &&
                e.bounds.top >= actionBarTop && e.bounds.bottom <= actionBarBottom
        }.minWithOrNull(compareBy<Entry> { if (it.node.viewIdResourceName == "com.tencent.mm:id/obn") 0 else 1 }
            .thenBy { it.bounds.top }.thenByDescending { it.bounds.width() })

        val titleEntry = exactTitleEntry ?: entries.filterIndexed { index, e ->
            val text = textValue(e)
            e.visible && !e.blocked && text.isNotEmpty() && text.length <= 120 &&
                ChatDateParser.parse(text) == null &&
                text !in listOf("返回", "微信", "搜索", "聊天信息", "更多") &&
                !isPromotionalLabel(text) && selected.none { inside(index, it) } &&
                lists.none { inside(index, it) } &&
                (actionBarIndex == null || inside(index, actionBarIndex)) &&
                !(e.node.text.isNullOrBlank() && text.contains("头像")) &&
                e.bounds.top >= actionBarTop && e.bounds.bottom <= actionBarBottom &&
                e.bounds.centerX() in (viewport.left + viewport.width() / 4)..(viewport.right - viewport.width() / 5)
        }.minWithOrNull(compareBy<Entry> { it.bounds.top }.thenByDescending { it.bounds.width() })

        // A single visible sender can still be a group member, not the chat name.
        val title = titleEntry?.let(::textValue)?.takeUnless(::isTruncatedTitle)
        val structural = selected.size - known.size
        if (selected.isEmpty())
            return ChatInspection(null, entries.size, known.size, 0, if (entries.size <= 2) "微信未开放可读节点" else "未确认聊天气泡，可能不是聊天页或当前版本不兼容")
        val bubbles = selected.mapNotNull { index ->
            val e = entries[index]
            val text = body(index)
            if (text.isBlank()) return@mapNotNull null
            val side = if (e.bounds.left - viewport.left > viewport.right - e.bounds.right) "me" else "other"
            var parent = e.parent
            var sender: String? = null
            repeat(3) {
                if (parent >= 0 && sender == null) {
                    sender = entries.indices.firstOrNull { j ->
                        val a = entries[j]; val value = a.node.text?.toString()?.trim().orEmpty()
                        a.visible && !a.blocked && inside(j, parent) && j != parent && value.isNotBlank() && value.length <= 60 &&
                            value != title && value != text && selected.none { inside(j, it) } && ChatDateParser.parse(value) == null &&
                            a.bounds.bottom <= e.bounds.top && e.bounds.top - a.bounds.bottom < 56 * density &&
                            abs(a.bounds.left - e.bounds.left) < 32 * density
                    }?.let { entries[it].node.text.toString().trim() }
                    parent = entries[parent].parent
                }
            }
            Bubble(index, text, side, if (side == "other") sender else null)
        }.sortedBy { entries[it.index].bounds.top }.distinctBy { "${entries[it.index].bounds}|${it.text}" }
        val separators = entries.indices.filter { j ->
            entries[j].visible && !entries[j].blocked && entries[j].bounds.top >= actionBarBottom && selected.none { inside(j, it) }
        }.mapNotNull { j -> ChatDateParser.parse(entries[j].node.text?.toString().orEmpty())?.let { entries[j].bounds to it } }
        val messages = bubbles.map { b ->
            val stamp = separators.filter { (rect, _) -> rect.bottom <= entries[b.index].bounds.top }
                .maxByOrNull { it.first.bottom }?.second
            Msg(b.side, b.text, b.sender, stamp?.epochMillis,
                when {
                    b.text == "[图片]" -> MessageType.IMAGE
                    b.text.startsWith("[表情") -> MessageType.STICKER
                    b.text == "[文件]" -> MessageType.FILE
                    b.text == "[语音]" || b.text == "[视频]" -> MessageType.UNKNOWN
                    else -> MessageType.TEXT
                },
                Regex("@[^\\s:：,，。！？]+").findAll(b.text).map { it.value }.distinct().toList(),
                stamp?.day?.toString(), stamp?.label)
        }
        val scroll = lists.filter { list -> selected.any { inside(it, list) } }.maxByOrNull { entries[it].bounds.height() }?.let { entries[it].node }
        val reason = when {
            messages.isEmpty() -> "聊天页已识别，但正文不可读"
            title.isNullOrBlank() -> "气泡可读，但会话名称未确认"
            structural > 0 -> "已读取（结构兼容模式）"
            else -> "已读取（气泡节点）"
        }
        val ocrRegions = known.filter { body(it).isBlank() }.mapNotNull { i ->
            val e = entries[i]
            val stamp = separators.filter { it.first.bottom <= e.bounds.top }.maxByOrNull { it.first.bottom }?.second
            val rect = Rect(e.bounds)
            if (!rect.intersect(viewport)) null else ChatOcrRegion(rect,
                if (rect.left - viewport.left > viewport.right - rect.right) "me" else "other", stamp?.day?.toString(), stamp?.label)
        }.sortedBy { it.bounds.top }
        val titleBounds = actionBar?.bounds?.let { bar ->
            val center = Rect(viewport.left + (viewport.width() * .17f).toInt(), viewport.top,
                viewport.left + (viewport.width() * .86f).toInt(), viewport.bottom)
            Rect(bar).takeIf { it.intersect(center) && !it.isEmpty }
        }
        return ChatInspection(ChatSnapshot(title, messages, pkg), entries.size, known.size, structural.coerceAtLeast(0), reason, scroll, ocrRegions, titleBounds,
            titleFromId = title != null && exactTitleEntry != null)
    }

    companion object {
        private const val BUBBLE_ID = "com.tencent.mm:id/bkl"
        private const val STICKER_ID = "com.tencent.mm:id/bkm"
        private val TITLE_IDS = setOf("com.tencent.mm:id/obn", "android:id/text1")
        private val ACTION_BAR_IDS = setOf(
            "com.tencent.mm:id/ei",
            "com.tencent.mm:id/ef",
            "com.tencent.mm:id/cob",
            "com.tencent.mm:id/gp",
            "com.tencent.mm:id/obq"
        )
        private val PROMOTIONAL_LABEL = Regex(
            "热搜|头条|广告|推荐|推广|点击|本地热|地点\\s*[:：]|优惠|商城|直播|小程序",
            RegexOption.IGNORE_CASE
        )
        private val ELLIPSIS = Regex("\\.(?:\\s*\\.){2,}")
        internal fun isTruncatedTitle(value: String): Boolean = value.contains('…') || ELLIPSIS.containsMatchIn(value)
    }
}
