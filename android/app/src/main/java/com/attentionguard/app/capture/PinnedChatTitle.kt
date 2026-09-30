package com.attentionguard.app.capture

import com.attentionguard.app.core.ChatSnapshot
import com.attentionguard.app.core.ConversationIdentity
import com.attentionguard.app.core.MessageType
import com.attentionguard.app.core.ScreenOverlap

/** A manually confirmed title remains tied to overlapping bubbles across window recreation. */
internal class PinnedChatTitle(val title: String, initialWindowId: Int, initial: ChatSnapshot) {
    private var visible = initial
    private var lastWindowId = initialWindowId
    private var misses = 0
    private val unmatchedScreens = mutableSetOf<String>()
    private var continuous = true

    fun pauseContinuity() { continuous = false }

    fun resolve(snapshot: ChatSnapshot, currentWindowId: Int): ChatSnapshot? {
        if (shouldExpire()) return null
        if (snapshot.sourcePackage != "com.tencent.mm" ||
            (!snapshot.title.isNullOrBlank() && !ConversationIdentity.sameTitle(title, snapshot.title))) {
            misses = 3
            return null
        }
        if (snapshot.messages.isEmpty()) { pauseContinuity(); return null }
        if (!matchesMessages(visible, snapshot, continuous && currentWindowId == lastWindowId)) {
            pauseContinuity()
            // Re-reading one blocked viewport is not a new failed transition.
            if (unmatchedScreens.add(snapshot.messagesSignature())) misses++
            return null
        }
        misses = 0
        unmatchedScreens.clear()
        continuous = true
        lastWindowId = currentWindowId
        visible = snapshot
        return snapshot.copy(title = title)
    }

    fun shouldExpire(): Boolean = misses >= 3

    companion object {
        internal fun matchesMessages(previous: ChatSnapshot, next: ChatSnapshot, sameWindow: Boolean): Boolean {
            if (previous.sourcePackage != next.sourcePackage || next.messages.isEmpty()) return false
            if (sameWindow && previous.messagesSignature() == next.messagesSignature()) return true
            val overlap = ScreenOverlap.match(previous.messages, next.messages)
            // A window ID can change inside one chat, and can be reused by another.
            // Require ordered, distinctive text rather than a set of generic replies.
            return overlap.keys.any { index ->
                val message = next.messages[index]
                val text = message.text.filter { it.isLetterOrDigit() }
                message.type == MessageType.TEXT && text.length >= (if (overlap.size >= 2) 6 else 12) &&
                    text.toSet().size >= 4
            }
        }
    }
}
