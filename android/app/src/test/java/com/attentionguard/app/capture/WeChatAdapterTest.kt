package com.attentionguard.app.capture

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.attentionguard.app.core.MessageType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w360dp-h800dp-mdpi")
class WeChatAdapterTest {
    private val resources = RuntimeEnvironment.getApplication().resources
    private val adapter = WeChatAdapter()

    @Suppress("DEPRECATION") // API 30 node creation; the replacement constructor requires API 33.
    private fun node(text: String? = null, bounds: Rect = Rect(0, 0, 360, 800),
                     id: String? = null, visible: Boolean = true, pkg: String = adapter.pkg) =
        AccessibilityNodeInfo.obtain().apply {
            this.text = text
            packageName = pkg
            viewIdResourceName = id
            isVisibleToUser = visible
            setBoundsInScreen(bounds)
        }

    private fun child(parent: AccessibilityNodeInfo, child: AccessibilityNodeInfo) {
        shadowOf(parent).addChild(child)
    }

    private fun bubble(text: String, top: Int = 220, left: Int = 30) =
        node(text, Rect(left, top, left + 100, top + 44), "com.tencent.mm:id/bkl")

    @Test fun readsOnlyVisibleExactBubbleNodesInScreenOrder() {
        val root = node()
        child(root, node("Course group", Rect(100, 35, 250, 62)))
        val later = bubble("My reply", 330, 230)
        child(root, later)
        child(root, bubble("@all submit the report", 220))
        child(root, bubble("@all submit the report", 220))
        val result = adapter.extract(root, resources)!!
        assertEquals("Course group", result.title)
        assertEquals(listOf("@all submit the report", "My reply"), result.messages.map { it.text })
        assertEquals(listOf("other", "me"), result.messages.map { it.side })
        assertEquals(listOf("@all"), result.messages.first().mentions)
        assertEquals(adapter.pkg, result.sourcePackage)
        assertTrue(shadowOf(later).performedActions.isEmpty())
    }

    @Test fun actionBarTitleWinsOverPromotionalBannerText() {
        val root = node()
        val actionBar = node(bounds = Rect(0, 0, 360, 150), id = "com.tencent.mm:id/ei")
        child(root, actionBar)
        child(actionBar, node("测试联系人", Rect(120, 45, 240, 90), id = "com.tencent.mm:id/obn"))
        child(root, node("本地热搜头条", Rect(80, 165, 300, 210)))
        child(root, bubble("你上去一个"))
        assertEquals("测试联系人", adapter.extract(root, resources)!!.title)
    }

    @Test fun repeatedAvatarOfOneGroupSpeakerMustNotBecomeTheChatTitle() {
        val root = node()
        child(root, node(bounds = Rect(0, 0, 360, 150), id = "com.tencent.mm:id/ei"))
        repeat(2) { index ->
            val row = node(bounds = Rect(0, 220 + index * 100, 360, 300 + index * 100))
            child(root, row)
            child(row, node(bounds = Rect(8, 225 + index * 100, 48, 265 + index * 100))
                .apply { contentDescription = "测试联系人头像" })
            child(row, bubble(if (index == 0) "你上去一个" else "可爱不", 220 + index * 100))
        }
        val result = adapter.inspect(root, resources)
        assertNull(result.snapshot!!.title)
        assertEquals(2, result.snapshot!!.messages.size)
        assertTrue(result.reason.contains("名称未确认"))
    }

    @Test fun distinctAvatarLabelsDoNotBecomeAGroupTitle() {
        val root = node()
        child(root, node(bounds = Rect(0, 0, 360, 150), id = "com.tencent.mm:id/ei"))
        repeat(2) { index ->
            val row = node(bounds = Rect(0, 220 + index * 100, 360, 300 + index * 100))
            child(root, row)
            child(row, node(bounds = Rect(8, 225 + index * 100, 48, 265 + index * 100))
                .apply { contentDescription = if (index == 0) "甲头像" else "乙头像" })
            child(row, bubble("普通消息", 220 + index * 100))
        }
        assertNull(adapter.inspect(root, resources).snapshot!!.title)
    }

    @Test fun rejectsOtherPackagesEmptyRootsAndOffscreenRoots() {
        for (root in listOf(node(pkg = "other.app"), node(bounds = Rect()), node(bounds = Rect(0, 810, 360, 900)))) {
            child(root, bubble("Not readable"))
            assertNull(adapter.extract(root, resources))
        }
    }

    @Test fun ignoresHiddenOffscreenForeignAndLookalikeBubbles() {
        val root = node()
        child(root, bubble("Visible"))
        child(root, bubble("Hidden").apply { isVisibleToUser = false })
        child(root, bubble("Offscreen", 900))
        child(root, bubble("Foreign").apply { packageName = "other.app" })
        child(root, bubble("Wrong id").apply { viewIdResourceName = "other.app:id/bkl" })
        child(root, node("Input text", Rect(20, 720, 300, 770), "com.tencent.mm:id/input"))
        assertEquals(listOf("Visible"), adapter.extract(root, resources)!!.messages.map { it.text })
    }

    @Test fun visibleDescendantsCanBeReadThroughNonTextContainers() {
        val root = node()
        val container = node(visible = false)
        child(root, container)
        child(container, bubble("Visible child"))
        assertEquals("Visible child", adapter.extract(root, resources)!!.messages.single().text)
    }

    @Test fun rootWindowBoundsExcludeNodesOutsideSplitScreenWindow() {
        val root = node(bounds = Rect(0, 0, 360, 400))
        child(root, bubble("Inside"))
        child(root, bubble("Outside window", 480))
        assertEquals(listOf("Inside"), adapter.extract(root, resources)!!.messages.map { it.text })
    }

    @Test fun readsVisibleSenderAndTimeButNotHiddenMetadata() {
        val root = node()
        val row = node()
        child(root, row)
        child(row, node("Hidden sender", Rect(30, 185, 130, 208), visible = false))
        child(row, node("Teacher", Rect(30, 185, 130, 208)))
        child(row, node("09:45", Rect(30, 154, 130, 178)))
        child(row, bubble("Submit report"))
        val message = adapter.extract(root, resources)!!.messages.single()
        assertEquals("Teacher", message.sender)
        assertNotNull(message.timestamp)
    }

    @Test fun hiddenTitleAndTimestampAreNotUsed() {
        val root = node()
        child(root, node("Private title", Rect(100, 35, 250, 62), visible = false))
        child(root, node("09:45", Rect(30, 154, 130, 178), visible = false))
        child(root, bubble("Visible text"))
        val result = adapter.extract(root, resources)!!
        assertNull(result.title)
        assertNull(result.messages.single().timestamp)
    }

    @Test fun mediaUsesPlaceholdersAndNeverGuessesImageContents() {
        val root = node()
        child(root, bubble("").apply { contentDescription = "\u56fe\u7247" })
        val message = adapter.extract(root, resources)!!.messages.single()
        assertEquals(MessageType.IMAGE, message.type)
        assertEquals("[\u56fe\u7247]", message.text)
    }

    @Test fun stickerDescriptionIsPreservedAsASeparateLowConfidenceCue() {
        val root = node()
        child(root, bubble("").apply { contentDescription = "呜哇" })
        val message = adapter.extract(root, resources)!!.messages.single()
        assertEquals(MessageType.STICKER, message.type)
        assertEquals("[表情:呜哇]", message.text)
    }

    @Test fun nestedStickerDescriptionIsPreservedFromTheBubbleContainer() {
        val root = node()
        val bubble = bubble("")
        child(bubble, node(bounds = Rect(30, 220, 160, 350)).apply { contentDescription = "呜哇" })
        child(root, bubble)
        val message = adapter.extract(root, resources)!!.messages.single()
        assertEquals(MessageType.STICKER, message.type)
        assertEquals("[表情:呜哇]", message.text)
    }

    @Test fun bkmStickerContainerIsCapturedAsAStickerCue() {
        val root = node()
        val sticker = node(bounds = Rect(30, 220, 180, 360), id = "com.tencent.mm:id/bkm")
        child(sticker, node(bounds = Rect(30, 220, 180, 360)).apply { contentDescription = "呜哇" })
        child(root, sticker)
        val message = adapter.extract(root, resources)!!.messages.single()
        assertEquals(MessageType.STICKER, message.type)
        assertEquals("[表情:呜哇]", message.text)
    }

    @Test fun unnamedBkmStickerGetsLowConfidencePlaceholderButImageKeepsMediaType() {
        val root = node()
        val sticker = node(bounds = Rect(30, 220, 180, 360), id = "com.tencent.mm:id/bkm")
        child(root, sticker)
        val imageParent = node(bounds = Rect(190, 220, 340, 360)).apply { contentDescription = "图片" }
        child(imageParent, node(bounds = Rect(190, 220, 340, 360), id = "com.tencent.mm:id/bkm"))
        child(root, imageParent)
        val messages = adapter.extract(root, resources)!!.messages
        assertEquals(listOf("[表情:未命名贴纸]", "[图片]"), messages.map { it.text })
        assertEquals(listOf(MessageType.STICKER, MessageType.IMAGE), messages.map { it.type })
    }

    @Test fun readsNestedBubbleBodyAndLongGroupTitles() {
        val root = node()
        val title = "A group name longer than twenty four characters (128)"
        child(root, node(title, Rect(70, 35, 310, 82)))
        val container = bubble("")
        child(container, node("Nested text", Rect(35, 224, 128, 258)).apply { packageName = null })
        child(root, container)
        val result = adapter.inspect(root, resources)
        assertEquals(title, result.snapshot!!.title)
        assertEquals("Nested text", result.snapshot!!.messages.single().text)
    }

    @Test fun bottomComposerListAvatarAndLongClickProveTheStructuralFallback() {
        val root = node()
        child(root, node("Group (30)", Rect(100, 35, 250, 62)))
        child(root, node("Do not read my draft", Rect(50, 720, 300, 772)).apply { isEditable = true })
        val list = node(bounds = Rect(0, 100, 360, 700)).apply { isScrollable = true; className = "android.widget.ListView" }
        val row = node(bounds = Rect(0, 190, 360, 280))
        child(root, list); child(list, row)
        child(row, node(bounds = Rect(5, 210, 45, 250)).apply { contentDescription = "Teacher头像" })
        val body = node("Version changed message", Rect(55, 220, 260, 275), "com.tencent.mm:id/new_body").apply { isLongClickable = true }
        child(row, body)
        val result = adapter.inspect(root, resources)
        assertEquals(1, result.structuralBubbles)
        assertEquals(list, result.scrollTarget)
        assertEquals(listOf("Version changed message"), result.snapshot!!.messages.map { it.text })
        assertTrue(shadowOf(body).performedActions.isEmpty())
    }

    @Test fun structuralBubbleWithoutReadableTitleStillSupportsContentOnlyAnalysis() {
        val root = node()
        child(root, node("Draft", Rect(50, 720, 300, 772)).apply { isEditable = true })
        val list = node(bounds = Rect(0, 100, 360, 700)).apply { isScrollable = true }
        val row = node(bounds = Rect(0, 190, 360, 280))
        child(root, list); child(list, row)
        child(row, node(bounds = Rect(5, 210, 45, 250)).apply { contentDescription = "同学头像" })
        child(row, node("明天之前可以吗？", Rect(55, 220, 260, 275)).apply { isLongClickable = true })
        val result = adapter.inspect(root, resources)
        assertEquals(1, result.structuralBubbles)
        assertNull(result.snapshot?.title)
        assertEquals("明天之前可以吗？", result.snapshot?.messages?.single()?.text)
    }

    @Test fun searchAndContactListsCannotTriggerFallbackOrOcr() {
        val root = node()
        child(root, node("Search", Rect(45, 35, 300, 90)).apply { isEditable = true })
        val list = node(bounds = Rect(0, 110, 360, 700)).apply { isScrollable = true }
        child(root, list)
        child(list, node("List preview", Rect(65, 200, 250, 260)).apply { isLongClickable = true })
        val result = adapter.inspect(root, resources)
        assertNull(result.snapshot); assertFalse(OnDeviceChatOcr.eligible(result))
    }

    @Test fun blockedTextBubblesHaveASeparateOcrEligibleResult() {
        val root = node()
        child(root, node("Group", Rect(100, 35, 250, 62)))
        child(root, bubble(""))
        val result = adapter.inspect(root, resources)
        assertNotNull(result.snapshot); assertTrue(result.snapshot!!.messages.isEmpty())
        assertEquals(1, result.ocrRegions.size); assertTrue(OnDeviceChatOcr.eligible(result))
        assertFalse(OnDeviceChatOcr.eligible(result.copy(snapshot = result.snapshot!!.copy(title = null))))
    }

    @Test fun dateLikeBodyIsNotMistakenForASeparatorAndMetadataStaysOrdered() {
        val root = node()
        child(root, node("昨天 09:00", Rect(120, 150, 240, 180)))
        child(root, bubble("2026年1月1日 10:00", 220))
        child(root, bubble("next", 330))
        val result = adapter.extract(root, resources)!!
        assertTrue(result.messages.all { it.date == java.time.LocalDate.now().minusDays(1).toString() })
        assertEquals("昨天 09:00", result.messages.last().timeLabel)
    }

    @Test fun identicalLastEightMessagesDoNotMaskChangedTopOfScreen() {
        val old = com.attentionguard.app.core.ChatSnapshot("group", (1..10).map { com.attentionguard.app.core.Msg("other", "text $it") })
        val changed = old.copy(messages = listOf(com.attentionguard.app.core.Msg("other", "new first")) + old.messages.drop(1))
        assertNotEquals(old.signature(), changed.signature())
    }

    @Test fun clippedLongBubbleDoesNotInvalidateToolbarTitle() {
        val root = node()
        child(root, node("Synthetic group", Rect(100, 35, 250, 62)))
        child(root, node("Long visible message", Rect(30, -100, 300, 420), "com.tencent.mm:id/bkl"))
        assertEquals("Synthetic group", adapter.inspect(root, resources).snapshot!!.title)
    }

    @Test fun androidToolbarTextIsAcceptedButTruncatedNamesRequireConfirmation() {
        for (title in listOf("课程通知群(93)", "课程…通知群(93)", "课程. ..通知群(93)")) {
            val root = node()
            val toolbar = node(bounds = Rect(0, 0, 360, 100), id = "com.tencent.mm:id/ei")
            child(root, toolbar)
            child(toolbar, node(title, Rect(100, 35, 280, 68), "android:id/text1"))
            child(root, bubble("请在周三之前提交课程作业"))
            assertEquals(if (title == "课程通知群(93)") title else null, adapter.inspect(root, resources).snapshot!!.title)
        }
    }

    @Test fun senderAtTopOfMessageListDoesNotBecomeATitle() {
        val root = node()
        val list = node(bounds = Rect(0, 70, 360, 700)).apply { isScrollable = true }
        child(root, list)
        child(list, node("群成员昵称", Rect(100, 85, 230, 112), "android:id/text1"))
        child(list, bubble("请提交课程报告", 130))
        assertNull(adapter.inspect(root, resources).snapshot!!.title)
    }

    @Test fun nativeTitleWinsOverSecondaryToolbarLabel() {
        val root = node()
        val toolbar = node(bounds = Rect(0, 0, 360, 120), id = "com.tencent.mm:id/ei")
        child(root, toolbar)
        child(toolbar, node("23网工2(49)", Rect(90, 35, 280, 68), "com.tencent.mm:id/obn"))
        child(toolbar, node("3条新消息", Rect(110, 80, 250, 108), "android:id/text1"))
        child(root, bubble("请提交课程报告"))
        assertEquals("23网工2(49)", adapter.inspect(root, resources).snapshot!!.title)
    }

    @Test fun unreadableHeaderStillProvidesOnlyItsMiddleTitleRegion() {
        val root = node()
        child(root, node(bounds = Rect(0, 24, 360, 104), id = "com.tencent.mm:id/ei"))
        child(root, bubble("请提交课程报告"))
        val result = adapter.inspect(root, resources)
        assertNull(result.snapshot!!.title)
        assertEquals(Rect(61, 24, 309, 104), result.titleBounds)
    }

    @Test fun headerWithoutChatEvidenceCannotEnableTitleOcr() {
        val root = node()
        child(root, node(bounds = Rect(0, 24, 360, 104), id = "com.tencent.mm:id/ei"))
        assertNull(adapter.inspect(root, resources).titleBounds)
    }
}
