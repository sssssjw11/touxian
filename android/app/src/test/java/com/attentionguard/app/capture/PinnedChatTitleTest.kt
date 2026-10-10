package com.attentionguard.app.capture

import com.attentionguard.app.core.ChatSnapshot
import com.attentionguard.app.core.Msg
import com.attentionguard.app.core.MessageType
import org.junit.Assert.*
import org.junit.Test

class PinnedChatTitleTest {
    private val initial = ChatSnapshot(null, listOf(
        Msg("other", "请在周三下午之前提交课程作业"), Msg("me", "收到"), Msg("other", "提交后请在群内确认完成")
    ), "com.tencent.mm")

    @Test fun knownDifferentTitlesNeverInheritAPinEvenWithTheSameBubbles() {
        val pin = PinnedChatTitle("A-B", 7, initial)
        assertNull(pin.resolve(initial.copy(title = "AB"), 7))
        assertTrue(pin.shouldExpire())
    }

    @Test fun confirmedTitleFollowsOverlappingVisibleBubblesOnly() {
        val pin = PinnedChatTitle("课程群", 7, initial)
        assertEquals("课程群", pin.resolve(initial, 7)?.title)
        val updated = initial.copy(messages = initial.messages.drop(1) + Msg("other", "第三条通知"))
        assertEquals("课程群", pin.resolve(updated, 7)?.title)
        assertNull(pin.resolve(updated.copy(messages = listOf(Msg("other", "完全不同的会话"))), 7))
        assertEquals("课程群", pin.resolve(updated, 8)?.title)
    }

    @Test fun changingReadableTitleImmediatelyReleasesTheOldBinding() {
        val pin = PinnedChatTitle("课程群", 7, initial)
        assertNull(pin.resolve(initial.copy(title = "另一个群"), 7))
        assertTrue(pin.shouldExpire())
        assertNull(pin.resolve(initial, 7))
    }

    @Test fun titleFormattingAndNewMemberCountDoNotBreakBinding() {
        val pin = PinnedChatTitle("课程群(48)", 7, initial)
        assertEquals("课程群(48)", pin.resolve(initial.copy(title = "课程群（49）"), 8)?.title)
    }

    @Test fun scrollingInTheSameWindowContinuesWithoutVerifyingOrExpiring() {
        val pin = PinnedChatTitle("课程群", 7, initial)
        repeat(6) { page ->
            val scrolled = initial.copy(messages = listOf(Msg("other", "滚动后的第 $page 屏"), Msg("me", "好")))
            assertNull(pin.resolve(scrolled, 7))
            assertTrue(pin.continues(scrolled, 7))
        }
        assertFalse(pin.shouldExpire())
        assertEquals("课程群", pin.resolve(initial, 7)?.title)
    }

    @Test fun navigationAnotherWindowOrAnotherHeaderEndsContinuation() {
        val scrolled = initial.copy(messages = listOf(Msg("other", "完全没有重叠的另一屏")))
        assertFalse(PinnedChatTitle("课程群", 7, initial).continues(scrolled, 8))
        assertFalse(PinnedChatTitle("课程群", 7, initial).continues(scrolled.copy(title = "另一个群"), 7))
        assertTrue(PinnedChatTitle("课程群(48)", 7, initial).continues(scrolled.copy(title = "课程群(49)"), 7))
        val paused = PinnedChatTitle("课程群", 7, initial).also { it.pauseContinuity() }
        assertFalse(paused.continues(scrolled, 7))
        assertEquals("课程群", paused.resolve(initial, 7)?.title)
        assertTrue(paused.continues(scrolled, 7))
    }

    @Test fun emptyTreeDoesNotExpireButThreeDistinctUnmatchedScreensAfterNavigationDo() {
        val pin = PinnedChatTitle("课程群", 7, initial)
        repeat(4) { assertNull(pin.resolve(initial.copy(messages = emptyList()), 7)) }
        assertFalse(pin.shouldExpire())
        pin.pauseContinuity()
        repeat(3) { page ->
            assertNull(pin.resolve(initial.copy(messages = listOf(Msg("other", "不匹配页面 $page"))), 7))
        }
        assertTrue(pin.shouldExpire())
        assertNull(pin.resolve(initial, 7))
    }

    @Test fun pollingTheSameUnmatchedScreenDoesNotExpireTheConfirmedTitle() {
        val pin = PinnedChatTitle("课程群", 7, initial)
        pin.pauseContinuity()
        val unmatched = initial.copy(messages = listOf(Msg("other", "完全没有重叠的另一屏")))
        repeat(20) { poll ->
            assertNull(pin.resolve(unmatched.copy(capturedAt = poll.toLong()), 7))
            assertFalse(pin.shouldExpire())
        }
        assertEquals("课程群", pin.resolve(initial, 7)?.title)
    }

    @Test fun revisitingTwoUnmatchedScreensDoesNotCreateAThirdFailedPage() {
        val pin = PinnedChatTitle("课程群", 7, initial)
        val first = initial.copy(messages = listOf(Msg("other", "未匹配页面一")))
        val second = initial.copy(messages = listOf(Msg("other", "未匹配页面二")))
        pin.pauseContinuity()
        repeat(4) {
            assertNull(pin.resolve(first, 7))
            assertNull(pin.resolve(second, 7))
        }
        assertFalse(pin.shouldExpire())
        assertNull(pin.resolve(initial.copy(messages = listOf(Msg("other", "未匹配页面三"))), 7))
        assertTrue(pin.shouldExpire())
    }

    @Test fun successfulOverlapResetsTheFailedPageSet() {
        val pin = PinnedChatTitle("课程群", 7, initial)
        val first = initial.copy(messages = listOf(Msg("other", "未匹配页面一")))
        val second = initial.copy(messages = listOf(Msg("other", "未匹配页面二")))
        pin.pauseContinuity()
        assertNull(pin.resolve(first, 7))
        assertNull(pin.resolve(second, 7))
        val overlapping = initial.copy(messages = initial.messages.drop(1) + Msg("other", "另一条课程通知"))
        assertEquals("课程群", pin.resolve(overlapping, 7)?.title)
        pin.pauseContinuity()
        assertNull(pin.resolve(first, 7))
        assertNull(pin.resolve(second, 7))
        assertFalse(pin.shouldExpire())
        assertEquals("课程群", pin.resolve(overlapping, 7)?.title)
    }

    @Test fun genericRepliesCannotRebindARecreatedWindow() {
        val generic = initial.copy(messages = listOf(Msg("other", "收到"), Msg("me", "好的")))
        val pin = PinnedChatTitle("课程群", 7, generic)
        assertEquals("课程群", pin.resolve(generic, 7)?.title)
        assertNull(pin.resolve(generic, 8))
    }

    @Test fun oneDistinctiveMessageCanSurviveARecreatedWindow() {
        val long = initial.copy(messages = initial.messages.take(1))
        assertEquals("课程群", PinnedChatTitle("课程群", 7, long).resolve(long, 8)?.title)
    }

    @Test fun leavingAndReturningWithGenericRepliesDoesNotReuseTheWindowIdAsProof() {
        val generic = initial.copy(messages = listOf(Msg("other", "收到")))
        val pin = PinnedChatTitle("课程群", 7, generic)
        pin.pauseContinuity()
        assertNull(pin.resolve(generic, 7))
    }

    @Test fun distinctiveContextSurvivesATemporaryGap() {
        val pin = PinnedChatTitle("课程群", 7, initial)
        pin.pauseContinuity()
        assertEquals("课程群", pin.resolve(initial, 7)?.title)
    }

    @Test fun sameTextInADifferentOrderIsNotTheSameConversation() {
        assertNull(PinnedChatTitle("课程群", 7, initial).resolve(initial.copy(messages = initial.messages.reversed()), 8))
    }

    @Test fun senderTypeAndProvenDateRemainPartOfTheMessageIdentity() {
        for (changed in listOf(
            initial.messages.map { it.copy(sender = "另一个人") },
            initial.messages.map { it.copy(type = MessageType.STICKER) },
            initial.messages.map { it.copy(date = "2026-09-28") }
        )) {
            val dated = initial.copy(messages = initial.messages.map { it.copy(date = "2026-09-27") })
            assertNull(PinnedChatTitle("课程群", 7, dated).resolve(initial.copy(messages = changed), 8))
        }
    }

    @Test fun messageSignatureIgnoresRecoveredTitleButKeepsWholeScreenContext() {
        assertEquals(initial.messagesSignature(), initial.copy(title = "课程群").messagesSignature())
        assertNotEquals(initial.signature(), initial.copy(title = "课程群").signature())
        assertNotEquals(initial.messagesSignature(), initial.copy(messages = initial.messages.drop(1)).messagesSignature())
    }
}
