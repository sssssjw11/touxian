package com.attentionguard.app

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.attentionguard.app.ai.ContextAnswer
import com.attentionguard.app.ai.DeepSeekContextClient
import com.attentionguard.app.core.AnalysisScene
import com.attentionguard.app.core.EventStore
import com.attentionguard.app.core.MessageArchive
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
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
@Config(sdk = [30], qualifiers = "w360dp-h800dp-mdpi")
class CustomContextActivityTest {
    private val context = RuntimeEnvironment.getApplication()
    private val activityController = Robolectric.buildActivity(CustomIntentActivity::class.java)
    private lateinit var activity: CustomIntentActivity
    private class Connection(val code: Int = 200, val offline: Boolean = false) : HttpURLConnection(URL("https://example.invalid")) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val request = ByteArrayOutputStream()
        var cancelled = false
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { cancelled = true }
        override fun getOutputStream() = request
        override fun getResponseCode(): Int { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); return code }
        override fun getInputStream(): ByteArrayInputStream {
            if (offline) throw IOException("offline")
            val content = ContextAnswer.json(ContextAnswer.proof("manual:0", sender = null))
            return ByteArrayInputStream(JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
                .put("message", JSONObject().put("content", content.toString())))).toString().toByteArray())
        }
    }
    private fun children(view: View): Sequence<View> = sequence {
        yield(view); if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(children(view.getChildAt(i)))
    }
    private fun open(connection: Connection): CustomIntentActivity {
        activity = activityController.setup().get()
        activity.cloudCredentials = { "test-only" to "model" }
        activity.connectionFactory = { key, model -> DeepSeekContextClient(key, model) { connection } }
        activity.findViewById<TextInputEditText>(R.id.ag_custom_chat).setText("对方：算了，你忙吧")
        activity.findViewById<MaterialButton>(R.id.ag_custom_analyze).performClick()
        return activity
    }
    private fun await() {
        val worker = CustomIntentActivity::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(activity) as ExecutorService
        worker.submit {}.get(10, TimeUnit.SECONDS); shadowOf(Looper.getMainLooper()).idle()
    }
    private fun send(connection: Connection) {
        activity.findViewById<MaterialButton>(R.id.ag_custom_cloud).performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(0, connection.request.size())
        children(dialog.window!!.decorView).filterIsInstance<MaterialButton>().first { it.text.toString() == "查看将发送的原文" }.performClick()
        assertTrue(children(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == "算了，你忙吧" })
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(connection.entered.await(10, TimeUnit.SECONDS))
    }
    @After fun close() { if (::activity.isInitialized) activityController.pause().stop().destroy() }
    @Test fun explicitSendReturnsGroundedManualResultWithoutSavingAnything() {
        val connection = Connection(); open(connection)
        send(connection); connection.release.countDown(); await()
        assertTrue(children(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString().contains(" · DeepSeek 深入理解") })
        assertTrue(connection.request.toString().contains("manual:0"))
        MessageArchive(context).use { assertEquals(0, it.count().total); assertTrue(it.profiles().isEmpty()) }
        assertTrue(EventStore(context).load().isEmpty())
    }
    @Test fun changedTextDoesNotReceiveAResponseForPreviousInput() {
        val connection = Connection(); open(connection); send(connection)
        activity.findViewById<TextInputEditText>(R.id.ag_custom_chat).setText("对方：新的消息")
        connection.release.countDown(); await()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.ag_custom_result).visibility)
        assertTrue(connection.cancelled)
    }
    @Test fun changedSceneKeepsLocalResultAndRejectsOldCloudResponse() {
        val connection = Connection(); open(connection); send(connection)
        activity.findViewById<MaterialButton>(R.id.ag_custom_scene).performClick()
        (ShadowDialog.getLatestDialog() as AlertDialog).listView.performItemClick(null, AnalysisScene.WORK.ordinal, 0)
        connection.release.countDown(); await()
        assertTrue(children(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("具体需要") || it.text.toString().contains("确认的事项") })
        assertFalse(children(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString().contains(" · DeepSeek 深入理解") })
        assertTrue(connection.cancelled)
    }
    @Test fun cancellationAndBackgroundingPreventOldResponse() {
        val connection = Connection(); open(connection); send(connection)
        activity.findViewById<MaterialButton>(R.id.ag_custom_cancel).performClick()
        activityController.pause().stop()
        connection.release.countDown(); await()
        assertTrue(connection.cancelled)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.ag_custom_cancel).visibility)
    }
    @Test fun rateLimitKeepsLocalResultAndAllowsRetry() {
        val connection = Connection(code = 429); open(connection); send(connection)
        connection.release.countDown(); await()
        assertTrue(activity.findViewById<View>(R.id.ag_custom_cloud).isEnabled)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.ag_custom_result).visibility)
        assertTrue(children(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == "本地语境" || it.text.toString().contains("本地语境") })
    }
    @Test fun offlineFailureRetainsLocalReading() {
        val connection = Connection(offline = true); open(connection); send(connection)
        connection.release.countDown(); await()
        assertTrue(activity.findViewById<View>(R.id.ag_custom_cloud).isEnabled)
        assertTrue(children(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("本地语境") })
        assertFalse(children(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString().contains(" · DeepSeek 深入理解") })
    }
}
