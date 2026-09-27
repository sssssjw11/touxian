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
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DeepSeekAttentionClientTest {
    private val base = DemoAttentionData.events.first().copy(id = "real-event")
    private val snapshot = ChatSnapshot("Test group", listOf(Msg("other", "Please submit", "Teacher")), "com.tencent.mm")

    private fun eventJson() = JSONObject()
        .put("title", "Report submission").put("summary", "Submit the report")
        .put("category", "course").put("priority", "P1").put("status", "action_required")
        .put("attention_score", 72).put("due_label", "Friday")
        .put("action_label", "Submit").put("consequence", "").put("source_person", "Teacher")

    private fun response(content: Any = eventJson().toString(), finish: String = "stop") =
        JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", finish)
            .put("message", JSONObject().put("content", content)))).toString()

    private fun client(connection: FakeConnection) = DeepSeekAttentionClient("test-key", "test-model") { connection }

    @Test fun validResponseEnrichesEventWithoutChangingIdentityOrEvidence() {
        val connection = FakeConnection(response())
        val enriched = client(connection).enrich(snapshot, base, "context")
        assertEquals("DeepSeek", enriched.analysisSource)
        assertEquals("Report submission", enriched.title)
        assertEquals(EventPriority.P1, enriched.priority)
        assertEquals(EventCategory.COURSE, enriched.category)
        assertEquals(72, enriched.attentionScore)
        assertEquals(base.id, enriched.id)
        assertEquals(base.evidence, enriched.evidence)
        assertEquals(base.sourceGroup, enriched.sourceGroup)
        assertTrue(connection.disconnected)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals("POST", connection.requestMethod)
        assertEquals("Bearer test-key", connection.getRequestProperty("Authorization"))
        assertEquals(12000, connection.connectTimeout)
        assertEquals(22000, connection.readTimeout)
    }

    @Test fun requestContainsOnlyLastTwelveBoundedMessagesAndContext() {
        val connection = FakeConnection(response())
        val messages = (0..15).map { Msg("other", "$it:" + "x".repeat(2100)) }
        client(connection).enrich(snapshot.copy(messages = messages),
            base.copy(evidence = messages.map { it.text.take(500) }), "c".repeat(2200))
        val body = JSONObject(connection.request.toString("UTF-8"))
        assertEquals("test-model", body.getString("model"))
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
        val user = JSONObject(body.getJSONArray("messages").getJSONObject(1).getString("content"))
        val captured = user.getJSONArray("messages")
        assertEquals(12, captured.length())
        assertTrue(captured.getJSONObject(0).getString("text").startsWith("4:"))
        assertEquals(2000, captured.getJSONObject(0).getString("text").length)
        assertEquals(2000, user.getString("context").length)
        assertFalse(body.toString().contains("test-key"))
    }

    @Test fun independentNoticesAreExcludedFromTheSelectedEventRequest() {
        val connection = FakeConnection(response())
        val selected = "Please submit"
        client(connection).enrich(snapshot.copy(messages = snapshot.messages + Msg("other", "Another unrelated meeting")),
            base.copy(evidence = listOf(selected)), "")
        val body = JSONObject(connection.request.toString("UTF-8"))
        val user = JSONObject(body.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals(1, user.getJSONArray("messages").length())
        assertEquals(selected, user.getJSONArray("messages").getJSONObject(0).getString("text"))
    }

    @Test fun validatedEmptyOptionalFieldsClearHeuristicGuesses() {
        val content = eventJson().put("due_label", "").put("action_label", "").put("consequence", "")
        val enriched = client(FakeConnection(response(content.toString()))).enrich(snapshot, base, "")
        assertNull(enriched.dueLabel)
        assertNull(enriched.actionLabel)
        assertNull(enriched.consequence)
    }

    @Test fun modelCannotPromoteWeakEvidenceOrInjectDeadline() {
        val weak = base.copy(priority = EventPriority.P2, dueLabel = null)
        val proposed = eventJson().put("priority", "P0").put("due_label", "39/30 12:00")
        val enriched = client(FakeConnection(response(proposed.toString()))).enrich(snapshot, weak, "")
        assertEquals(EventPriority.P2, enriched.priority)
        assertNull(enriched.dueLabel)
        assertTrue(enriched.reviewNotes.any { it.contains("未满足") })
    }

    @Test fun rejectsMissingChoicesAndNonStringContent() {
        listOf("{}", "{\"choices\":[]}", response(JSONObject()), response("")).forEach { assertInvalid(it) }
    }

    @Test fun rejectsTruncatedAndFilteredCompletions() {
        listOf("length", "content_filter", "tool_calls").forEach { assertInvalid(response(finish = it)) }
    }

    @Test fun rejectsMissingFieldsAndIncorrectTypes() {
        assertInvalid(response(eventJson().apply { remove("summary") }.toString()))
        assertInvalid(response(eventJson().put("source_person", JSONObject.NULL).toString()))
        assertInvalid(response(eventJson().put("title", 12).toString()))
        assertInvalid(response(eventJson().put("summary", "  ").toString()))
    }

    @Test fun modelCannotMarkUserWorkCompletedOrInventEnums() {
        assertInvalid(response(eventJson().put("status", "completed").toString()))
        assertInvalid(response(eventJson().put("priority", "P9").toString()))
        assertInvalid(response(eventJson().put("category", "secret").toString()))
    }

    @Test fun rejectsNonIntegralAndOutOfRangeScores() {
        listOf<Any>("72", 0, 100, 72.5).forEach { score ->
            assertInvalid(response(eventJson().put("attention_score", score).toString()))
        }
    }

    @Test fun rejectsOverlongFieldsAndOversizeResponses() {
        assertInvalid(response(eventJson().put("title", "x".repeat(161)).toString()))
        assertInvalid(" ".repeat(65_537))
    }

    @Test fun invalidJsonDoesNotLeakProviderContentOrCause() {
        assertInvalid("sensitive-provider-body")
        assertInvalid(response("sensitive-inner-body"))
    }

    @Test fun httpErrorsAndRedirectsNeverReadProviderBody() {
        listOf(301, 401, 402, 429, 500).forEach { status ->
            val connection = FakeConnection("sensitive-provider-body", status)
            val failure = assertThrows(IllegalStateException::class.java) { client(connection).enrich(snapshot, base, "") }
            assertEquals("HTTP $status", failure.message)
            assertFalse(connection.inputRead)
            assertFalse(connection.errorRead)
            assertTrue(connection.disconnected)
            assertFalse(DeepSeekAttentionClient.readableError(failure).contains("sensitive"))
        }
    }

    @Test fun cancellationBeforeStartDoesNotOpenConnection() {
        var opened = false
        val client = DeepSeekAttentionClient("test-key", "test-model") { opened = true; FakeConnection(response()) }
        client.cancel()
        val failure = assertThrows(IllegalStateException::class.java) { client.enrich(snapshot, base, "") }
        assertEquals("cancelled", failure.message)
        assertFalse(opened)
    }

    @Test fun cancellationDuringReadDisconnectsAndDiscardsResult() {
        val connection = FakeConnection(response())
        val client = client(connection)
        connection.onRead = { client.cancel() }
        val failure = assertThrows(IllegalStateException::class.java) { client.enrich(snapshot, base, "") }
        assertEquals("cancelled", failure.message)
        assertTrue(connection.disconnected)
    }

    @Test fun threadInterruptionCancelsBeforeNetworkAndKeepsInterruptFlag() {
        val connection = FakeConnection(response())
        Thread.currentThread().interrupt()
        try {
            val failure = assertThrows(IllegalStateException::class.java) { client(connection).enrich(snapshot, base, "") }
            assertEquals("cancelled", failure.message)
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(0, connection.request.size())
        } finally {
            Thread.interrupted()
        }
    }

    private fun assertInvalid(raw: String) {
        val connection = FakeConnection(raw)
        val failure = assertThrows(RuntimeException::class.java) { client(connection).enrich(snapshot, base, "") }
        assertEquals("invalid_response", failure.message)
        assertNull(failure.cause)
        assertTrue(connection.disconnected)
    }

    private class FakeConnection(private val body: String, private val status: Int = 200) :
        HttpURLConnection(URL("https://example.invalid/test")) {
        val request = ByteArrayOutputStream()
        var disconnected = false
        var inputRead = false
        var errorRead = false
        var onRead: (() -> Unit)? = null
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
        override fun getOutputStream() = request
        override fun getResponseCode() = status
        override fun getErrorStream(): InputStream { errorRead = true; return ByteArrayInputStream(body.toByteArray()) }
        override fun getInputStream(): InputStream {
            inputRead = true
            return object : ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    onRead?.invoke()
                    return super.read(buffer, offset, length)
                }
            }
        }
    }
}
