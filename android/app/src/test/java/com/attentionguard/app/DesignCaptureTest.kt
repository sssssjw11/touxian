package com.attentionguard.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.attentionguard.app.core.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Opt-in native Android renderer captures, using synthetic conversations only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w393dp-h851dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DesignCaptureTest {
    private fun children(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(children(view.getChildAt(i)))
    }
    private fun await(activity: ObjectProfilesActivity) {
        val worker = ObjectProfilesActivity::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(activity) as ExecutorService
        repeat(4) { shadowOf(Looper.getMainLooper()).idle(); worker.submit {}.get(15, TimeUnit.SECONDS) }
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun capture(view: View, dir: File, name: String, dialog: Boolean = false) {
        shadowOf(Looper.getMainLooper()).idle()
        val width = 786
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1702, if (dialog) View.MeasureSpec.AT_MOST else View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, view.measuredHeight)
        val bitmap = Bitmap.createBitmap(width, view.measuredHeight, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(dir, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        File(dir, "$name.txt").writeText(children(view).filterIsInstance<TextView>()
            .filter { it.visibility == View.VISIBLE }.joinToString("\n") { it.text.toString() })
        bitmap.recycle()
    }

    @Test fun captureFlow() {
        val path = System.getenv("TOUXIAN_CAPTURE_DIR")
        assumeTrue("Set TOUXIAN_CAPTURE_DIR to opt into screenshots", !path.isNullOrBlank())
        val dir = File(requireNotNull(path)).apply { mkdirs() }
        val context = RuntimeEnvironment.getApplication()
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        capture(main.get().window.decorView, dir, "01-home")
        main.pause().stop().destroy()
        val manual = Robolectric.buildActivity(CustomIntentActivity::class.java).setup()
        val free = manual.get()
        capture(free.window.decorView, dir, "02-free-input")
        free.findViewById<TextInputEditText>(R.id.ag_custom_chat).setText("我：我现在在开会，晚点聊可以吗？\n对方：算了，你忙吧")
        free.findViewById<MaterialButton>(R.id.ag_custom_analyze).performClick()
        shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
        capture(free.window.decorView, dir, "03-free-result")
        manual.pause().stop().destroy()
        MessageArchive(context).use { archive ->
            archive.createRecording(ChatRecording("design-r", "小林 · 示例聊天", 1))
            archive.append(ChatSnapshot("小林 · 示例聊天", listOf(
                Msg("other", "今天有点难过，我想先说说，不用急着给建议。", "小林", date = "2026-10-06"),
                Msg("me", "我陪你，你愿意讲哪一件事？", date = "2026-10-06"),
                Msg("other", "谢谢你理解。我今晚想先独处，明天再聊。", "小林", date = "2026-10-06")
            )), "design-r", requireRecording = true)
            archive.createProfile(ConversationProfile("design-p", "小林", AnalysisScene.FRIEND, ProfileKind.PERSON), "design-r")
            archive.createProfile(ConversationProfile("design-other", "小林", AnalysisScene.WORK, ProfileKind.UNKNOWN))
            val snapshot = requireNotNull(archive.profileSnapshot("design-p"))
            archive.saveProfileAnalysis("design-p", snapshot.fingerprint,
                RelationshipAnalysis.analyze(snapshot.messages, snapshot.profile.name, snapshot.profile.scene, snapshot.profile))
            archive.append(ChatSnapshot("小林 · 示例聊天", listOf(
                Msg("other", "今天有点难过，我想先说说，不用急着给建议。", "小林", date = "2026-10-06"),
                Msg("me", "我陪你，你愿意讲哪一件事？", date = "2026-10-06"),
                Msg("other", "谢谢你理解。我今晚想先独处，明天再聊。", "小林", date = "2026-10-06"),
                Msg("me", "好的，我们明天再聊。", date = "2026-10-06")
            )), "design-r", requireRecording = true)
        }
        val listing = Robolectric.buildActivity(ObjectProfilesActivity::class.java).setup()
        await(listing.get()); capture(listing.get().window.decorView, dir, "04-profile-list")
        listing.pause().stop().destroy()
        val detail = Robolectric.buildActivity(ObjectProfilesActivity::class.java, Intent(context, ObjectProfilesActivity::class.java)
            .putExtra(ObjectProfilesActivity.EXTRA_PROFILE_ID, "design-p")).setup()
        val activity = detail.get()
        await(activity); capture(activity.window.decorView, dir, "05-profile-pending")
        children(activity.window.decorView).filterIsInstance<MaterialButton>().first { it.text.toString() == "更新本地画像" }.performClick()
        await(activity); capture(activity.window.decorView, dir, "06-profile-updated")
        children(activity.window.decorView).filterIsInstance<MaterialButton>().first { it.text.startsWith("查看原文依据") }.performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        capture(dialog.window!!.decorView, dir, "07-evidence", dialog = true)
        children(dialog.window!!.decorView).filterIsInstance<MaterialButton>().firstOrNull { it.text.toString() == "查看前后文" }?.performClick()
        capture(dialog.window!!.decorView, dir, "08-evidence-context", dialog = true)
        dialog.dismiss(); detail.pause().stop().destroy()
    }
}
