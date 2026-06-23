package com.example.modelhub.data

data class ChatMessage(
    val content: String,
    val role: MessageRole,
    val imageLocalPath: String? = null,
    val imageUrl: String? = null,
    val modelSource: String? = null,
    val modelBadge: String? = null,
    val modelBadgeColor: Int? = null
)

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}
