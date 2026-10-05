package com.attentionguard.app.core

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ContextAnalysisTest {
    private fun row(id: Long, body: String, sender: String? = "甲", stream: String = "r") =
        ArchivedMessage(id, "聊天", Msg(if (id % 2L == 0L) "me" else "other", body, sender), 99, stream)

    @Test fun liveBudgetIncludesOnlyTwentyCurrentMessagesAndSixRealHistoryProofs() {
        val snapshot = ChatSnapshot("聊天", (1..40).map { Msg("other", "$it:" + "长".repeat(2500), "甲") })
        val history = (1L..30L).map { row(it, "$it:" + "历".repeat(2000)) }
        val profile = ConversationProfile("p", "对象", AnalysisScene.FRIEND, ProfileKind.PERSON)
        val input = AnalysisInput.live(snapshot, AnalysisScene.WORK, history, profile, "摘".repeat(2000))
        assertEquals(20, input.messages.count { it.ref.startsWith("live:") })
        assertEquals(6, input.messages.count { it.ref.startsWith("archive:") })
        assertTrue(input.messages.sumOf { it.text.length } + input.profileSummary.length <= 16000)
        assertEquals(1500, input.profileSummary.length)
        assertTrue(input.messages.first().text.startsWith("21:"))
        assertTrue(input.messages[19].text.startsWith("40:"))
        assertTrue(input.messages.any { it.truncated })
        assertEquals(AnalysisScene.WORK, input.scene)
        assertTrue(input.identityKnown)
    }

    @Test fun deepStatisticsCoverAllRowsAndWindowsKeepRecentBoundariesAndRepair() {
        val rows = (1L..200L).map { id -> row(id, when (id) {
            140L -> "别再联系我"; 141L -> "我尊重你的决定"; 142L -> "对不起"; else -> "$id:" + "原".repeat(2000)
        }) }
        val base = RelationshipAnalysis.analyze(rows, "聊天", AnalysisScene.INTIMATE)
        val input = AnalysisInput.deep(rows, base)
        assertEquals(200, input.statistics!!.getInt("messageCount"))
        assertEquals(90, input.messages.size)
        assertTrue(input.messages.sumOf { it.text.length } <= 40000)
        assertTrue(input.messages.any { it.ref == "archive:200" })
        for (id in 140..142) assertTrue(input.messages.any { it.ref == "archive:$id" })
        assertFalse(input.statistics!!.has("findings"))
        assertFalse(input.statistics!!.has("context"))
    }

    @Test fun sameTextInDifferentScenesHasDifferentScopeWithoutChangingFacts() {
        val snapshot = ChatSnapshot("聊天", listOf(Msg("me", "我今天很忙"), Msg("other", "算了，你忙吧")))
        val variants = listOf(AnalysisScene.FRIEND, AnalysisScene.WORK, AnalysisScene.INTIMATE)
            .map { AnalysisInput.live(snapshot, it) }
        assertEquals(3, variants.map { it.fingerprint }.distinct().size)
        assertEquals(1, variants.map { it.messages }.distinct().size)
        assertTrue(variants.all { it.messages.all { msg -> msg.messageTime == null && msg.day == null } })
        assertTrue(variants.all { !it.identityKnown })
    }

    @Test fun knownGroupActorsOverridePersonalProfileAndOcrCannotBecomeCloudEvidence() {
        val snapshot = ChatSnapshot("聊天", listOf(Msg("other", "资料好了", "甲"),
            Msg("other", "我来修改", "乙"), Msg("other", "屏幕猜测", captureMethod = "ocr")))
        val input = AnalysisInput.live(snapshot, AnalysisScene.WORK,
            profile = ConversationProfile("p", "对象", kind = ProfileKind.PERSON))
        assertTrue(input.group); assertFalse(input.identityKnown)
        assertEquals(setOf("甲", "乙"), input.messages.map { it.sender }.toSet())
        assertFalse(input.messages.any { it.text == "屏幕猜测" })
    }

    @Test fun quotationJokesNegationAndExplicitRefusalRemainDistinct() {
        for (text in listOf("她说我不喜欢你", "台词：‘我不喜欢你’", "我不喜欢你，开玩笑的", "我不喜欢你吗？")) {
            assertTrue(text, AnalysisInput.live(ChatSnapshot("聊天", listOf(Msg("other", text))), AnalysisScene.INTIMATE).boundaries.isEmpty())
        }
        val refusal = AnalysisInput.live(ChatSnapshot("聊天", listOf(Msg("other", "我不喜欢你，我们只是朋友"))), AnalysisScene.INTIMATE)
        assertEquals("live:0", refusal.boundaries.single().ref)
    }

    @Test fun contentActorRecordingAndProfileRevisionInvalidateRequestFingerprint() {
        val snapshot = ChatSnapshot("聊天", listOf(Msg("other", "原话", "甲")))
        val base = AnalysisInput.live(snapshot, AnalysisScene.GENERAL)
        assertNotEquals(base.fingerprint, AnalysisInput.live(snapshot.copy(messages = listOf(Msg("other", "修正原话", "甲"))), base.scene).fingerprint)
        assertNotEquals(base.fingerprint, AnalysisInput.live(snapshot.copy(messages = listOf(Msg("other", "原话", "乙"))), base.scene).fingerprint)
        assertNotEquals(AnalysisFingerprint.messages(listOf(row(1, "原话"))), AnalysisFingerprint.messages(listOf(row(1, "原话", stream = "another"))))
        val profile = ConversationProfile("p", "对象")
        assertNotEquals(AnalysisInput.live(snapshot, base.scene, profile = profile).fingerprint,
            AnalysisInput.live(snapshot, base.scene, profile = profile.copy(revision = 1)).fingerprint)
    }
}
