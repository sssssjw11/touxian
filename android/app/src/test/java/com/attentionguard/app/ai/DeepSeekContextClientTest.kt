package com.attentionguard.app.ai

import com.attentionguard.app.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal object ContextAnswer {
    fun proof(ref: String = "live:0", quote: String = "算了，你忙吧", side: String = "other", sender: String? = "甲") =
        JSONObject().put("ref", ref).put("quote", quote).put("side", side).put("sender", sender ?: JSONObject.NULL)
    fun section(kind: String = "INTENT", proof: JSONObject = proof(), detail: String = "可能希望结束当前话题，也可能是体谅忙碌") =
        JSONObject().put("kind", kind).put("title", "结束或暂停话题").put("detail", detail).put("level", "LIMITED")
            .put("alternatives", JSONArray().put("也可能表达失落，需要前文核对"))
            .put("evidence", JSONArray().put(proof))
    fun json(proof: JSONObject = proof()) = JSONObject().put("summary", "结束、体谅或失落，需要结合前文")
        .put("sections", JSONArray().put(section(proof = proof)))
        .put("replies", JSONArray().put(JSONObject().put("text", "你是想先暂停，还是有些不开心？")
            .put("timing", "不确定对方想暂停还是希望被回应时").put("evidence", JSONArray().put(proof))))
        .put("limitations", JSONArray().put("缺少前一段对话"))
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DeepSeekContextClientTest {
    private val input = AnalysisInput.live(ChatSnapshot("聊天", listOf(Msg("other", "算了，你忙吧", "甲"))), AnalysisScene.FRIEND)
    private fun rejected(json: JSONObject, scope: AnalysisInput = input) {
        try { DeepSeekContextClient.validate(json, scope); fail("must reject unsupplied or mismatched evidence") }
        catch (error: IllegalStateException) { assertEquals("invalid_response", error.message) }
    }

    @Test fun shortOneSidedSampleStillAllowsGroundedLocalExplanationAndReplies() {
        val result = DeepSeekContextClient.validate(ContextAnswer.json(), input)
        assertEquals(1, result.sections.size)
        assertEquals(EvidenceLevel.LIMITED, result.sections.single().level)
        assertEquals("甲", result.sections.single().evidence.single().sender)
        assertEquals(input.fingerprint, result.fingerprint)
        assertEquals(1, result.replies.size)
        assertTrue(result.limitations.any { it.contains("缺少") })
    }
    @Test fun fabricatedIdRewrittenQuoteWrongSideWrongActorAndOutsideRangeAreRejected() {
        listOf(ContextAnswer.proof(ref = "live:999"), ContextAnswer.proof(quote = "我喜欢你"),
            ContextAnswer.proof(side = "me"), ContextAnswer.proof(sender = "乙"),
            ContextAnswer.proof(ref = "archive:1")).forEach { rejected(ContextAnswer.json(it)) }
        val truncated = input.copy(messages = input.messages.map { it.copy(text = "算了", truncated = true) })
        rejected(ContextAnswer.json(), truncated)
    }
    @Test fun unknownIdentityAndGroupsRemovePortraitWithoutDroppingValidIntent() {
        val json = ContextAnswer.json().apply { getJSONArray("sections").put(ContextAnswer.section("PORTRAIT")) }
        assertEquals(listOf(InsightKind.INTENT), DeepSeekContextClient.validate(json, input).sections.map { it.kind })
        val known = input.copy(identityKnown = true)
        assertEquals(2, DeepSeekContextClient.validate(json, known).sections.size)
        assertEquals(1, DeepSeekContextClient.validate(json, known.copy(group = true)).sections.size)
    }
    @Test fun inadequateEvidenceCanExplainMissingContextWithoutInventingSources() {
        val json = ContextAnswer.json().apply {
            put("sections", JSONArray().put(ContextAnswer.section().put("level", "INSUFFICIENT").put("evidence", JSONArray())))
            put("replies", JSONArray())
        }
        assertEquals(EvidenceLevel.INSUFFICIENT, DeepSeekContextClient.validate(json, input).sections.single().level)
        rejected(json.apply { getJSONArray("sections").getJSONObject(0).put("level", "SUPPORTED") })
    }
    @Test fun noMoreThanTwoIntentCandidatesAndTwoRepliesAreAccepted() {
        val json = ContextAnswer.json()
        json.getJSONArray("sections").put(ContextAnswer.section()).put(ContextAnswer.section())
        rejected(json)
        val replyOverflow = ContextAnswer.json().apply {
            val reply = getJSONArray("replies").getJSONObject(0); getJSONArray("replies").put(reply).put(reply)
        }
        rejected(replyOverflow)
    }
    @Test fun explicitRefusalKeepsGroundedAdviceAndSuppressesReverseRomance() {
        val scope = AnalysisInput.live(ChatSnapshot("聊天", listOf(Msg("other", "我不喜欢你，我们只是朋友", "甲"))), AnalysisScene.INTIMATE)
        val proof = ContextAnswer.proof(quote = "我们只是朋友")
        val json = ContextAnswer.json(proof).apply {
            put("summary", "拒绝是喜欢的试探")
            put("sections", JSONArray().put(ContextAnswer.section(proof = proof, detail = "拒绝是喜欢的试探"))
                .put(ContextAnswer.section("FACT", proof, "对方说明当前只希望做朋友")))
            getJSONArray("replies").getJSONObject(0).put("text", "我明白，会尊重朋友边界").put("timing", "尊重明确拒绝时")
        }
        val result = DeepSeekContextClient.validate(json, scope)
        assertTrue(result.summary.contains("尊重"))
        assertTrue(result.sections.any { it.title == "明确边界优先按原话理解" })
        assertFalse(result.sections.any { it.detail.contains("拒绝是喜欢的试探") })
        assertEquals(1, result.replies.size)
    }
}
