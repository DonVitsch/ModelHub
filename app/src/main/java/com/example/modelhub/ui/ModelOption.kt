package com.example.modelhub.ui

data class ModelOption(
    val id: String,
    val title: String,
    val provider: String,
    val group: ModelOptionGroup,
    val badge: String,
    val badgeColor: Int
) {
    val sourceLabel: String
        get() = "$provider · $title"
}

enum class ModelOptionGroup(val title: String) {
    FAST("⚡ 快速模型"),
    QUALITY("🧠 高质量模型"),
    LOCAL("💻 本地模型"),
    IMAGE("🎨 生图模型")
}
