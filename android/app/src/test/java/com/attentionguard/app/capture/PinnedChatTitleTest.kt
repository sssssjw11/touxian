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

    @Test fun emptyTreeDoesNotExpireButThreeUnmatchedScreensDo() {
        val pin = PinnedChatTitle("课程群", 7, initial)
        repeat(4) { assertNull(pin.resolve(initial.copy(messages = emptyList()), 7)) }
        assertFalse(pin.shouldExpire())
        repeat(3) { assertNull(pin.resolve(initial.copy(messages = listOf(Msg("other", "不匹配"))), 7)) }
        assertTrue(pin.shouldExpire())
        assertNull(pin.resolve(initial, 7))
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
