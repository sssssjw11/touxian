package com.attentionguard.app.capture

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.attentionguard.app.ai.ContextAnswer
import com.attentionguard.app.ai.DeepSeekContextClient
import com.attentionguard.app.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class LiveIntentControllerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<LiveIntentController>()
    private var state = LiveAnalysisState()
    private var recordRequests = 0
    private val snapshot = ChatSnapshot("聊天", listOf(Msg("other", "算了，你忙吧", "甲")))
    private class Connection(private val code: Int = 200, private val offline: Boolean = false) : HttpURLConnection(URL("https://example.invalid")) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val request = ByteArrayOutputStream()
        var disconnected = false
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
        override fun getOutputStream() = request
        override fun getResponseCode(): Int { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); return code }
        override fun getInputStream(): ByteArrayInputStream {
            if (offline) throw IOException("offline")
            return ByteArrayInputStream(JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
                .put("message", JSONObject().put("content", ContextAnswer.json().toString())))).toString().toByteArray())
        }
    }
    private fun controller(connection: Connection = Connection()): LiveIntentController =
        LiveIntentController(context, { state = it }, {}, { recordRequests++ }, { null }, {},
            { key, model -> DeepSeekContextClient(key, model) { connection } }, { "test-only" to "model" })
            .also { controllers.add(it); it.observe(snapshot) }
    private fun await(controller: LiveIntentController, field: String = "storage") {
        val worker = LiveIntentController::class.java.getDeclaredField(field).apply { isAccessible = true }.get(controller) as ExecutorService
        worker.submit {}.get(10, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun send(controller: LiveIntentController, connection: Connection) {
        controller.deepen(); await(controller)
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        assertEquals(0, connection.request.size())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(connection.entered.await(10, TimeUnit.SECONDS))
    }
    @After fun close() { controllers.forEach { it.close() }; shadowOf(Looper.getMainLooper()).idle() }
    private fun children(view: View): Sequence<View> = sequence {
        yield(view); if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(children(view.getChildAt(i)))
    }

    @Test fun observationNeverRecordsOrSendsAndManualConfirmationIsRequired() {
        val connection = Connection(); val controller = controller(connection)
        await(controller)
        assertEquals(0, recordRequests); assertEquals(0, connection.request.size())
        send(controller, connection)
        connection.release.countDown(); await(controller, "network")
        assertNotNull(state.insight); assertFalse(state.busy)
        MessageArchive(context).use { assertEquals(0, it.count().total); assertTrue(it.profiles().isEmpty()) }
    }
    @Test fun profileFormSurvivesMessagesInSameChatAndIsInvalidatedByChatSwitch() {
        val recording = ChatRecording("form-r", "聊天", 1)
        MessageArchive(context).use { it.createRecording(recording) }
        val controller = LiveIntentController(context, { state = it }, {}, { callback -> recordRequests++; callback(recording) },
            { recording }, {}).also { controllers.add(it); it.observe(snapshot) }
        controller.createProfile()
        var dialog = ShadowDialog.getLatestDialog() as AlertDialog
        controller.observe(snapshot.copy(messages = snapshot.messages + Msg("other", "新的消息", "甲")))
        assertTrue(dialog.isShowing)
        children(dialog.window!!.decorView).filterIsInstance<EditText>().single().setText("同一对象")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); await(controller)
        MessageArchive(context).use {
            assertEquals("同一对象", it.profiles().single().name)
            assertEquals(it.profiles().single().id, it.profileForRecording(recording.id)!!.id)
        }
        controller.createProfile()
        dialog = ShadowDialog.getLatestDialog() as AlertDialog
        controller.observe(snapshot.copy(title = "其他聊天"))
        assertFalse(dialog.isShowing)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); await(controller)
        MessageArchive(context).use { assertEquals(1, it.profiles().size) }
        assertEquals(1, recordRequests)
    }
    @Test fun changedChatMessageSceneCancelAndCloseCannotReceiveOldResponse() {
        for (change in listOf<(LiveIntentController) -> Unit>(
            { it.observe(snapshot.copy(title = "其他聊天")) },
            { it.observe(snapshot.copy(messages = listOf(Msg("other", "新的信息", "甲")))) },
            { it.chooseScene()
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                dialog.listView.performItemClick(null, AnalysisScene.WORK.ordinal, 0)
                shadowOf(Looper.getMainLooper()).idle()
            }, { it.cancel() }, { it.reset() }
        )) {
            state = LiveAnalysisState()
            val connection = Connection(); val controller = controller(connection)
            send(controller, connection); change(controller)
            connection.release.countDown(); await(controller, "network")
            assertNull(state.insight); assertFalse(state.busy); assertTrue(connection.disconnected)
            controller.close(); controllers.remove(controller)
        }
    }
    @Test fun responseAlreadyQueuedToMainThreadIsSuppressedAfterClosing() {
        val connection = Connection(); val controller = controller(connection)
        send(controller, connection); connection.release.countDown()
        val worker = LiveIntentController::class.java.getDeclaredField("network").apply { isAccessible = true }.get(controller) as ExecutorService
        worker.submit {}.get(10, TimeUnit.SECONDS)
        controller.close(); controllers.remove(controller)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(state.insight)
    }
    @Test fun offlineAndRateLimitKeepLocalStateAndPermitRetry() {
        for (connection in listOf(Connection(429), Connection(200, true))) {
            val controller = controller(connection)
            send(controller, connection); connection.release.countDown(); await(controller, "network")
            assertNull(state.insight); assertFalse(state.busy)
            assertTrue(state.status.contains("本地判断保留"))
            controller.deepen(); await(controller)
            assertTrue((ShadowDialog.getLatestDialog() as AlertDialog).isShowing)
            controller.cancel()
        }
    }
}
