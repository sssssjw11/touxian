package com.attentionguard.app.capture

import com.attentionguard.app.core.ChatRecording
import com.attentionguard.app.core.ChatSnapshot
import com.attentionguard.app.core.ConversationIdentity
import com.attentionguard.app.core.RecordingState

interface RecordingActions {
    fun startRecording(): Boolean
    fun pauseRecording()
    fun resumeRecording(id: String): Boolean
    fun stopRecording()
}

/** A recording accepts only a freshly confirmed, readable single conversation. */
internal object IntentRecording {
    fun accepts(recording: ChatRecording, snapshot: ChatSnapshot): Boolean =
        recording.state == RecordingState.ACTIVE && snapshot.sourcePackage == "com.tencent.mm" &&
            !snapshot.title.isNullOrBlank() && ConversationIdentity.sameTitle(recording.title, snapshot.title) &&
            snapshot.messages.isNotEmpty()
}
