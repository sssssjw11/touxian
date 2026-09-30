package com.attentionguard.app.ai

import com.attentionguard.app.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DeepSeekRelationshipClientTest {
    private val rows = listOf(ArchivedMessage(1, "group", Msg("me", "我陪你", date = "2026-09-30"), 1),
        ArchivedMessage(2, "group", Msg("other", "谢谢你", "friend", date = "2026-09-30"), 2))
    private val base = RelationshipAnalysis.analyze(rows, "group")
    private fun answer(id: Long = 1, quote: String = "我陪你") = JSONObject().put("label", "相互支持候选")
        .put("summary", "可见关切与回应").put("confidence", 85)
        .put("findings", JSONArray().put(JSONObject().put("label", "支持").put("detail", "我方表达支持")
            .put("evidence", JSONArray().put(JSONObject().put("messageId", id).put("quote", quote)))))
        .put("suggestions", JSONArray().put("结合更多真实上下文核对"))
    private class Connection(result: JSONObject) : HttpURLConnection(URL("https://example.invalid")) {
        val request = ByteArrayOutputStream()
        private val response = JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("finish_reason", "stop").put("message", JSONObject().put("content", result.toString())))).toString()
        var disconnected = false
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
        override fun getOutputStream() = request
        override fun getResponseCode() = 200
        override fun getInputStream() = ByteArrayInputStream(response.toByteArray())
    }
    @Test fun semanticFindingsKeepStatisticsAndEvidenceIdentity() {
        val connection = Connection(answer())
        val report = DeepSeekRelationshipClient("test", "model") { connection }.analyze(rows, base.copy(messageCount = 20))
        assertEquals(base.metrics, report.metrics)
        assertEquals(1L, report.findings.single().evidence.single().messageId)
        assertEquals("我陪你", report.findings.single().evidence.single().quote)
        assertTrue(report.confidence <= base.confidence + 10)
        assertTrue(connection.disconnected)
        val body = JSONObject(connection.request.toString("UTF-8"))
        val payload = JSONObject(body.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertFalse(payload.getJSONObject("local_report").has("findings"))
        assertEquals(2, payload.getJSONArray("windows").length())
    }
    @Test fun inventedEvidenceOrUnsuppliedIdsAreRejected() {
        for (answer in listOf(answer(999), answer(1, "我最爱你"))) {
            val client = DeepSeekRelationshipClient("test", "model") { Connection(answer) }
            try { client.analyze(rows, base); fail("must reject invented evidence") }
            catch (expected: IllegalStateException) { assertEquals("invalid_response", expected.message) }
        }
    }
    @Test fun explicitBoundaryAndGroupCannotBeRelabeledAsRomance() {
        for (label in listOf("群体互动线索", "边界表达需要尊重")) {
            val contrary = answer().put("summary", "拒绝是喜欢的试探")
            val client = DeepSeekRelationshipClient("test", "model") { Connection(contrary) }
            val report = client.analyze(rows, base.copy(label = label))
            assertEquals(label, report.label)
            assertEquals(base.summary, report.summary)
            assertEquals(base.confidence, report.confidence)
            assertEquals(base.findings, report.findings)
        }
    }
    @Test fun cancellationDoesNotSendARequest() {
        val connection = Connection(answer())
        val client = DeepSeekRelationshipClient("test", "model") { connection }
        client.cancel()
        try { client.analyze(rows, base); fail("must cancel") }
        catch (expected: IllegalStateException) { assertEquals("cancelled", expected.message) }
        assertEquals(0, connection.request.size())
    }
}
