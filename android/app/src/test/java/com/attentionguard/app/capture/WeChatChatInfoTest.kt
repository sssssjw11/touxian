package com.attentionguard.app.capture

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class WeChatChatInfoTest {
    @Suppress("DEPRECATION")
    private fun node(text: String? = null, id: String? = null) = AccessibilityNodeInfo.obtain().apply {
        this.text = text; viewIdResourceName = id
        packageName = "com.tencent.mm"; isVisibleToUser = true
    }
    private fun child(parent: AccessibilityNodeInfo, child: AccessibilityNodeInfo) = shadowOf(parent).addChild(child)
    private fun row(label: String, value: String?): AccessibilityNodeInfo = node().apply {
        // Real WeChat nests the label but places the summary beside its container.
        val labelContainer = node()
        child(this, labelContainer)
        child(labelContainer, node(label, "android:id/title"))
        if (value != null) child(this, node(value, "android:id/summary"))
    }

    @Test fun fullGroupNameComesFromItsOwnRowNotTheFirstSummary() {
        val root = node()
        child(root, row("群公告", "课程作业通知"))
        child(root, row("群聊名称", "校园创想课程讨论群"))
        child(root, row("我在本群的昵称", "群内昵称"))
        assertEquals("校园创想课程讨论群", WeChatChatInfo.read(root)?.groupTitle)
    }

    @Test fun missingGroupValueDoesNotBorrowAdjacentSummary() {
        val root = node()
        child(root, row("群聊名称", null))
        child(root, row("我在本群的昵称", "群内昵称"))
        assertNotNull(WeChatChatInfo.read(root))
        assertNull(WeChatChatInfo.read(root)?.groupTitle)
    }

    @Test fun lookupDoesNotClimbOutOfTheSettingsRowToAnUnlabelledValue() {
        val root = node()
        child(root, row("群聊名称", null))
        child(root, node("不属于群名行", "android:id/summary"))
        assertNull(WeChatChatInfo.read(root)?.groupTitle)
    }

    @Test fun hiddenForeignAndEditableSummariesAreNotRead() {
        for (modify in listOf<(AccessibilityNodeInfo) -> Unit>(
            { it.isVisibleToUser = false }, { it.packageName = "other.app" }, { it.isEditable = true }
        )) {
            val root = node()
            val row = row("群聊名称", null)
            child(root, row)
            child(row, node("不能使用的名称", "android:id/summary").also(modify))
            assertNull(WeChatChatInfo.read(root)?.groupTitle)
        }
    }

    @Test fun ambiguousAndTruncatedNamesStayUnconfirmed() {
        val root = node()
        val row = row("群聊名称", "课程群")
        child(root, row)
        child(row, node("另一个名称", "android:id/summary"))
        assertNull(WeChatChatInfo.read(root)?.groupTitle)
        for (value in listOf("课程…通知群", "课程. ..通知群", "未设置", "添加群聊名称")) {
            assertNull(WeChatChatInfo.read(node().apply { child(this, row("群聊名称", value)) })?.groupTitle)
        }
    }

    @Test fun groupNameWordsInsideAChatBubbleAreNotAnInfoPage() {
        val root = node()
        child(root, node("群聊名称", "com.tencent.mm:id/bkl"))
        child(root, node("普通正文", "com.tencent.mm:id/bkl"))
        assertNull(WeChatChatInfo.read(root))
    }

    @Test fun personalChatInfoCanBeRecognizedWithoutInventingAGroupTitle() {
        val root = node()
        child(root, node("聊天信息", "android:id/text1"))
        child(root, row("消息免打扰", null))
        assertNotNull(WeChatChatInfo.read(root))
        assertNull(WeChatChatInfo.read(root)?.groupTitle)
    }
}
