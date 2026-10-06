package com.attentionguard.app.core

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class LocalContextAnalysisTest {
    private fun read(text: String, scene: AnalysisScene = AnalysisScene.GENERAL) =
        LocalContextAnalysis.live(AnalysisInput.manual(text, scene))

    @Test fun ambiguousClosingUsesBusyContextAndSceneWithoutInventingDisappointment() {
        val text = "我：现在在开会，晚点聊可以吗？\n对方：算了，你忙吧"
        val friend = read(text, AnalysisScene.FRIEND)
        val work = read(text, AnalysisScene.WORK)
        val intimate = read(text, AnalysisScene.INTIMATE)
        assertTrue(friend.sections.last().title.contains("留空间"))
        assertEquals(2, friend.sections.last().evidence.size)
        assertTrue(friend.sections.last().alternatives.isNotEmpty())
        assertTrue(work.replies.single().text.contains("事项"))
        assertTrue(intimate.replies.single().text.contains("希望我回应"))
        assertNotEquals(friend.replies, work.replies)
        assertTrue(friend.sections.none { it.kind == InsightKind.PORTRAIT })
    }
    @Test fun missingContextStatesExactlyWhatIsMissing() {
        val result = read("算了，你忙吧")
        assertTrue(result.limitations.any { it.contains("此前提出的请求") })
        assertTrue(result.limitations.any { it.contains("只有对方消息") })
        assertTrue(result.sections.last().title.contains("结束"))
        assertEquals(EvidenceLevel.LIMITED, result.sections.last().level)
    }
    @Test fun refusalDoesNotBecomeAnInvitationToPursue() {
        for (text in listOf("对方：不要再联系我", "对方：我不想你现在回复", "对方：别打断我")) {
            val result = read(text)
            assertEquals(text, EvidenceLevel.SUPPORTED, result.sections.last().level)
            assertTrue(result.replies.single().text.contains("尊重"))
            assertFalse(result.replies.single().text.contains("为什么"))
        }
    }
    @Test fun reportedJokingAndNegatedEmotionDoNotTriggerDirectEmotionalAdvice() {
        for (text in listOf("她说“算了，你忙吧”", "对方：开玩笑，算了，你忙吧", "对方：我不难过")) {
            val result = read(text)
            assertTrue(text, result.replies.isEmpty())
            assertEquals(EvidenceLevel.INSUFFICIENT, result.sections.last().level)
        }
    }
    @Test fun manualInputPreservesSpeakerAndMultilineBodyWithinBoundedSendingScope() {
        val text = "我：第一条\n这是续行\n对方：第二条"
        val input = AnalysisInput.manual(text, AnalysisScene.WORK)
        assertEquals(listOf("me", "other"), input.messages.map { it.side })
        assertEquals("第一条\n这是续行", input.messages.first().text)
        assertTrue(input.messages.all { it.ref.startsWith("manual:") && it.day == null && it.messageTime == null })
        assertTrue(AnalysisInput.manual((1..30).joinToString("\n") { "对方：第$it 条" }, AnalysisScene.GENERAL).messages.size <= 20)
    }
    @Test fun profileNeedsComeFromCounterpartOriginalsAndNeverFromUnconfirmedOrGroupIdentity() {
        val rows = listOf(
            ArchivedMessage(1, "记录", Msg("me", "我希望先听我说"), 1, "r"),
            ArchivedMessage(2, "记录", Msg("other", "我想先说说，不用急着给建议。", "甲"), 2, "r")
        )
        val profile = ConversationProfile("p", "甲", kind = ProfileKind.PERSON)
        val report = RelationshipAnalysis.analyze(rows, "甲", profile = profile)
        val preference = report.context!!.sections.single { it.title.contains("沟通需要") }
        assertEquals(listOf("archive:2"), preference.evidence.map { it.ref })
        assertEquals(1, report.context!!.replies.size)
        assertTrue(RelationshipAnalysis.analyze(rows, "记录").context!!.sections.none { it.kind == InsightKind.PORTRAIT })
        val group = rows + ArchivedMessage(3, "记录", Msg("other", "我希望你提前告诉我。", "乙"), 3, "r")
        assertTrue(RelationshipAnalysis.analyze(group, "甲", profile = profile).context!!.sections.none { it.kind == InsightKind.PORTRAIT })
    }
    @Test fun quotedOrDeniedPreferencesDoNotBecomePortraitFacts() {
        val profile = ConversationProfile("p", "甲", kind = ProfileKind.PERSON)
        for (text in listOf("她说我希望先听我说", "我不需要你给建议", "“我希望你提前告诉我”", "我希望先听我说，开玩笑")) {
            val rows = listOf(ArchivedMessage(1, "记录", Msg("other", text, "甲"), 1, "r"))
            assertTrue(text, RelationshipAnalysis.analyze(rows, "甲", profile = profile).context!!.sections.none { it.title.contains("沟通需要") })
        }
    }
    @Test fun unverifiedOcrDoesNotBecomeACommunicationPortrait() {
        val profile = ConversationProfile("p", "甲", kind = ProfileKind.PERSON)
        val rows = listOf(ArchivedMessage(1, "记录", Msg("other", "我陪你，我希望先听我说。", "甲", captureMethod = "ocr"), 1, "r"))
        val report = RelationshipAnalysis.analyze(rows, "甲", profile = profile)
        assertTrue(report.context!!.sections.none { it.kind == InsightKind.PORTRAIT })
        assertEquals("ocr", AnalysisInput.archived(rows.single()).captureMethod)
        assertTrue(AnalysisInput.deep(rows, report, profile).messages.isEmpty())
    }
}
