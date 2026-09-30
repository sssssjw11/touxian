package com.attentionguard.app.core

enum class RecordingState(val label: String) { ACTIVE("记录中"), PAUSED("已暂停"), FINISHED("已结束") }

data class ChatRecording(
    val id: String,
    val title: String,
    val createdAt: Long,
    val endedAt: Long? = null,
    val state: RecordingState = RecordingState.ACTIVE,
    val reason: String = "",
    val gaps: Int = 0
)
