package com.attentionguard.app.core

enum class MessageType { TEXT, IMAGE, STICKER, FILE, SYSTEM, UNKNOWN }

/** One captured chat bubble. side is "me" (right) or "other" (left). */
data class Msg(
    val side: String,
    val text: String,
    val sender: String? = null,
    val timestamp: Long? = null,
    val type: MessageType = MessageType.TEXT,
    val mentions: List<String> = emptyList(),
    // A separator proves a day, not an exact per-message time.
    val date: String? = null,
    val timeLabel: String? = null,
    val captureMethod: String = "nodes"
)

/** A snapshot of the currently visible WeChat conversation. */
data class ChatSnapshot(
    val title: String?,
    val messages: List<Msg>,
    val sourcePackage: String? = null,
    val capturedAt: Long = System.currentTimeMillis()
) {
    val latestFrom: String? get() = messages.lastOrNull()?.side

    /** Include the entire viewport so scrolling at the top is not discarded. */
    fun signature(): String =
        "${sourcePackage.orEmpty()}|${title.orEmpty()}|${messagesSignature()}"

    /** Title recovery must not make an unchanged message viewport look different. */
    fun messagesSignature(): String = messages.joinToString("|") {
            "${it.side}:${it.sender.orEmpty()}:${it.type}:${it.text.length}:${it.text}:${it.date}:${it.timeLabel}:${it.captureMethod}"
        }
}
