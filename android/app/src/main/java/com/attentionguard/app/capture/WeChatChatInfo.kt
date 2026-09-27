package com.attentionguard.app.capture

import android.view.accessibility.AccessibilityNodeInfo

/** Reads only the value in WeChat's group-name settings row. */
internal object WeChatChatInfo {
    data class Page(val groupTitle: String?)
    private data class Entry(val node: AccessibilityNodeInfo, val parent: Int, val blocked: Boolean) {
        val text: String get() = node.text?.toString()?.trim().orEmpty()
        val readable: Boolean get() = !blocked && node.isVisibleToUser
    }

    fun read(root: AccessibilityNodeInfo): Page? {
        if (root.packageName?.toString() != "com.tencent.mm") return null
        val entries = mutableListOf<Entry>()
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to -1)
        while (stack.isNotEmpty() && entries.size < 3000) {
            val (node, parent) = stack.removeLast()
            val blocked = node.isEditable || node.packageName?.toString()?.let { it != "com.tencent.mm" } == true ||
                (parent >= 0 && entries[parent].blocked)
            val index = entries.size
            entries.add(Entry(node, parent, blocked))
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it to index) }
        }
        fun inside(index: Int, ancestor: Int): Boolean {
            var current = index
            while (current >= 0) {
                if (current == ancestor) return true
                current = entries[current].parent
            }
            return false
        }
        val labels = entries.indices.filter {
            entries[it].readable && entries[it].node.viewIdResourceName == "android:id/title"
        }
        val groupLabels = labels.filter { entries[it].text == "群聊名称" }
        val isInfoPage = groupLabels.isNotEmpty() ||
            (entries.any { it.readable && it.text.startsWith("聊天信息") } &&
                labels.any { entries[it].text in setOf("查找聊天记录", "消息免打扰", "置顶聊天") })
        if (!isInfoPage) return null
        for (label in groupLabels) {
            var row = entries[label].parent
            var depth = 0
            while (row >= 0 && depth++ < 2) {
                // Stop before the shared list; a neighbouring nickname/announcement
                // summary must never be paired with the group-name label.
                if (labels.count { inside(it, row) } != 1) break
                val values = entries.indices.filter {
                    entries[it].readable && inside(it, row) &&
                        entries[it].node.viewIdResourceName == "android:id/summary"
                }
                if (values.isNotEmpty()) {
                    val title = values.singleOrNull()?.let { entries[it].text }?.takeIf {
                        it.isNotBlank() && it.length <= 120 && it !in setOf("未设置", "添加群聊名称") &&
                            !WeChatAdapter.isTruncatedTitle(it)
                    }
                    return Page(title)
                }
                row = entries[row].parent
            }
        }
        return Page(null)
    }
}
