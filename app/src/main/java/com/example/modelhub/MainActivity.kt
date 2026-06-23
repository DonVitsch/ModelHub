package com.example.modelhub

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.example.modelhub.data.ChatMessage
import com.example.modelhub.data.MessageRole
import com.example.modelhub.network.ComfyUIClient
import com.example.modelhub.network.LocalModelClient
import com.example.modelhub.network.OpenRouterClient
import com.example.modelhub.storage.SettingsStorage
import com.example.modelhub.ui.ChatMode
import com.example.modelhub.ui.ChatView

class MainActivity : AppCompatActivity() {
    private companion object {
        const val TAG = "ModelHubPerf"
        const val OPENROUTER_HISTORY_LIMIT = 4
        const val OPENROUTER_MESSAGE_CHAR_LIMIT = 1600
        const val STREAM_UI_FLUSH_MS = 80L
    }

    private lateinit var chatView: ChatView
    private lateinit var settingsStorage: SettingsStorage

    private var macLocalBaseUrl = ""
    private var windowsImageBaseUrl = ""
    private var comfyUiClient: ComfyUIClient? = null
    private val chatMessages = mutableListOf<ChatMessage>()
    private var currentMode = ChatMode.OR_API
    private var lastOpenRouterWarmUpAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        val darkMode = getSharedPreferences("modelhub_settings", MODE_PRIVATE)
            .getBoolean("dark_mode", false)
        AppCompatDelegate.setDefaultNightMode(
            if (darkMode) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        )
        super.onCreate(savedInstanceState)

        settingsStorage = SettingsStorage(this)
        chatView = ChatView(this, window, darkMode)
        macLocalBaseUrl = settingsStorage.loadLocalModelHost()
        windowsImageBaseUrl = settingsStorage.loadComfyUiHost()
        updateComfyUiClient()

        setContentView(chatView.root)
        setupChatView()
    }

    private fun updateComfyUiClient() {
        comfyUiClient = if (windowsImageBaseUrl.isBlank()) {
            null
        } else {
            ComfyUIClient(windowsImageBaseUrl, this)
        }
    }

    private fun setupChatView() {
        chatView.setInitialSettings(
            apiKey = settingsStorage.loadApiKey(),
            model = settingsStorage.loadModelName()
        )

        chatView.setInitialCheckpoint(
            checkpoint = settingsStorage.loadCheckpoint()
        )

        chatView.setInitialHosts(
            localHost = macLocalBaseUrl,
            comfyHost = windowsImageBaseUrl
        )

        loadMessagesForMode(currentMode)
        warmOpenRouterConnection()

        chatView.onSettingsChanged = { apiKey, model ->
            settingsStorage.saveSettings(apiKey, model)
        }

        chatView.onCheckpointChanged = { checkpoint ->
            settingsStorage.saveCheckpoint(checkpoint)
        }

        chatView.onLocalHostChanged = { host ->
            macLocalBaseUrl = host.trim()
            settingsStorage.saveLocalModelHost(macLocalBaseUrl)
        }

        chatView.onComfyHostChanged = { host ->
            windowsImageBaseUrl = host.trim()
            settingsStorage.saveComfyUiHost(windowsImageBaseUrl)
            updateComfyUiClient()
        }

        chatView.onClearMessages = {
            chatView.clearMessages()
            chatMessages.clear()
            settingsStorage.clearChatMessages(historyKeyFor(currentMode))
        }

        chatView.onThemeModeChanged = { darkMode ->
            settingsStorage.saveDarkMode(darkMode)
            AppCompatDelegate.setDefaultNightMode(
                if (darkMode) {
                    AppCompatDelegate.MODE_NIGHT_YES
                } else {
                    AppCompatDelegate.MODE_NIGHT_NO
                }
            )
        }

        chatView.onModeChanged = { mode ->
            currentMode = mode
            loadMessagesForMode(mode)
            if (mode == ChatMode.OR_API) {
                warmOpenRouterConnection()
            }
            if (mode == ChatMode.MAC_LOCAL_TEXT) {
                refreshLocalModels(mode)
            }
            if (mode == ChatMode.WINDOWS_LOCAL_IMAGE) {
                comfyUiClient?.setCurrentModeKey(mode.name)
            }
        }

        chatView.onRefreshLocalModels = {
            refreshLocalModels(currentMode)
        }

        chatView.onFetchOpenRouterModels = {
            fetchOpenRouterModels()
        }

        chatView.onSendMessage = { mode, apiKey, model, question ->
            when (mode) {
                ChatMode.OR_API -> sendOpenRouterMessage(apiKey, model, question)
                ChatMode.MAC_LOCAL_TEXT -> sendLocalModelMessage(macLocalBaseUrl, model, question)
                ChatMode.WINDOWS_LOCAL_IMAGE -> sendComfyUIMessage(model, question)
            }
        }
    }

    private fun loadMessagesForMode(mode: ChatMode) {
        chatView.clearMessages()
        chatMessages.clear()

        val savedMessages = settingsStorage.loadChatMessages(
            historyKey = historyKeyFor(mode),
            includeLegacyMessages = mode == ChatMode.OR_API
        ).filterNot { isLegacyWelcomeMessage(it, mode) }

        if (savedMessages.isNotEmpty()) {
            chatMessages.addAll(savedMessages)
            savedMessages.forEach { chatView.addMessage(it) }
        }
    }

    private fun fetchOpenRouterModels() {
        val mode = ChatMode.OR_API
        val apiKey = settingsStorage.loadApiKey()
        if (apiKey.isBlank()) {
            addAndSaveMessage(ChatMessage("请先输入 OpenRouter API Key。", MessageRole.SYSTEM), mode)
            return
        }

        val savedModel = settingsStorage.loadModelName()
        OpenRouterClient(apiKey).fetchModels { models, error ->
            runOnUiThread {
                when {
                    error != null -> {
                        addAndSaveMessage(ChatMessage(error, MessageRole.SYSTEM), mode)
                    }
                    models.isEmpty() -> {
                        addAndSaveMessage(ChatMessage("没有获取到可用模型。", MessageRole.SYSTEM), mode)
                    }
                    else -> {
                        chatView.setOpenRouterModels(models)
                        chatView.selectOpenRouterModel(savedModel)
                    }
                }
            }
        }
    }

    private fun refreshLocalModels(mode: ChatMode) {
        if (macLocalBaseUrl.isBlank()) {
            addAndSaveMessage(ChatMessage("请先在设置中输入本地模型地址。", MessageRole.SYSTEM), mode)
            return
        }

        val client = LocalModelClient(macLocalBaseUrl)

        client.fetchModels { models, error ->
            runOnUiThread {
                when {
                    error != null -> {
                        addAndSaveMessage(ChatMessage(error, MessageRole.SYSTEM), mode)
                    }
                    models.isEmpty() -> {
                        addAndSaveMessage(ChatMessage("没有读取到本地 Ollama 模型。", MessageRole.SYSTEM), mode)
                    }
                    else -> {
                        chatView.setLocalModels(models)
                    }
                }
            }
        }
    }

    private fun sendOpenRouterMessage(apiKey: String, model: String, question: String) {
        val mode = ChatMode.OR_API
        addAndSaveMessage(ChatMessage(question, MessageRole.USER), mode)
        chatView.clearQuestionInput()
        chatView.setLoading(true)

        val client = OpenRouterClient(apiKey)
        val messages = loadRequestMessagesFor(mode)
        val answerBuilder = StringBuilder()
        val pendingDelta = StringBuilder()
        val assistantSeedMessage = chatView.withCurrentModelMetadata(ChatMessage("", MessageRole.ASSISTANT))
        val assistantMessageIndex = chatView.addStreamingMessage(assistantSeedMessage)
        var lastUiUpdateAt = 0L
        val requestStartedAt = android.os.SystemClock.uptimeMillis()
        var firstDeltaLogged = false

        Log.d(TAG, "OR request start model=$model messages=${messages.size}")

        client.streamMessage(
            model = model,
            messages = messages,
            onDelta = { delta ->
                answerBuilder.append(delta)
                pendingDelta.append(delta)
                val now = android.os.SystemClock.uptimeMillis()
                if (!firstDeltaLogged) {
                    firstDeltaLogged = true
                    Log.d(TAG, "OR first token in ${now - requestStartedAt}ms")
                }
                if (now - lastUiUpdateAt >= STREAM_UI_FLUSH_MS) {
                    lastUiUpdateAt = now
                    val deltaSnapshot = pendingDelta.toString()
                    pendingDelta.clear()
                    runOnUiThread {
                        if (currentMode == mode) {
                            chatView.appendMessageContent(assistantMessageIndex, deltaSnapshot)
                        }
                    }
                }
            },
            onComplete = {
                val answer = answerBuilder.toString()
                runOnUiThread {
                    chatView.setLoading(false)
                    Log.d(TAG, "OR complete in ${android.os.SystemClock.uptimeMillis() - requestStartedAt}ms chars=${answer.length}")
                    if (answer.isNotBlank()) {
                        if (currentMode == mode) {
                            chatView.updateMessageContent(assistantMessageIndex, answer)
                        }
                        saveMessageOnly(assistantSeedMessage.copy(content = answer), mode)
                    } else {
                        if (currentMode == mode) {
                            chatView.updateMessageContent(assistantMessageIndex, "未返回内容")
                        }
                    }
                }
            },
            onError = { error ->
                val partialAnswer = answerBuilder.toString()
                runOnUiThread {
                    chatView.setLoading(false)
                    Log.d(TAG, "OR error after ${android.os.SystemClock.uptimeMillis() - requestStartedAt}ms partialChars=${partialAnswer.length}")
                    if (partialAnswer.isNotBlank()) {
                        if (currentMode == mode) {
                            chatView.updateMessageContent(assistantMessageIndex, partialAnswer)
                        }
                        saveMessageOnly(assistantSeedMessage.copy(content = partialAnswer), mode)
                    } else {
                        if (currentMode == mode) {
                            chatView.updateMessageContent(assistantMessageIndex, "生成失败")
                        }
                    }
                    addAndSaveMessage(ChatMessage("出错了：$error", MessageRole.SYSTEM), mode)
                }
            }
        )
    }

    private fun sendLocalModelMessage(baseUrl: String, model: String, question: String) {
        val mode = ChatMode.MAC_LOCAL_TEXT
        if (baseUrl.isBlank()) {
            addAndSaveMessage(ChatMessage("请先在设置中输入本地模型地址。", MessageRole.SYSTEM), mode)
            return
        }
        if (model.isBlank()) {
            addAndSaveMessage(ChatMessage("请先点击刷新模型，并选择一个本地模型。", MessageRole.SYSTEM), mode)
            return
        }

        addAndSaveMessage(ChatMessage(question, MessageRole.USER), mode)
        chatView.clearQuestionInput()
        chatView.setLoading(true)

        val client = LocalModelClient(baseUrl)
        val messages = loadRequestMessagesFor(mode)
        val assistantSeedMessage = chatView.withCurrentModelMetadata(ChatMessage("", MessageRole.ASSISTANT))

        client.sendMessage(model, messages) { answer, error ->
            runOnUiThread {
                chatView.setLoading(false)

                when {
                    error != null -> {
                        addAndSaveMessage(ChatMessage("本地模型出错了：$error", MessageRole.SYSTEM), mode)
                    }
                    answer != null -> {
                        addAndSaveMessage(assistantSeedMessage.copy(content = answer), mode)
                    }
                }
            }
        }
    }

    private fun sendComfyUIMessage(checkpointName: String, prompt: String) {
        val mode = ChatMode.WINDOWS_LOCAL_IMAGE
        val client = comfyUiClient
        if (client == null) {
            addAndSaveMessage(ChatMessage("请先在设置中输入 ComfyUI 服务器地址。", MessageRole.SYSTEM), mode)
            return
        }

        addAndSaveMessage(ChatMessage(prompt, MessageRole.USER), mode)
        chatView.clearQuestionInput()
        chatView.setLoading(true)

        val assistantSeedMessage = chatView.withCurrentModelMetadata(ChatMessage("", MessageRole.ASSISTANT))
        client.setCurrentModeKey(mode.name)
        client.generateImage(prompt, checkpointName) { result, error ->
            runOnUiThread {
                chatView.setLoading(false)
                when {
                    error != null -> {
                        addAndSaveMessage(ChatMessage("生图出错：$error", MessageRole.SYSTEM), mode)
                    }
                    result != null -> {
                        addAndSaveMessage(
                            result.copy(
                                modelSource = assistantSeedMessage.modelSource,
                                modelBadge = assistantSeedMessage.modelBadge,
                                modelBadgeColor = assistantSeedMessage.modelBadgeColor
                            ),
                            mode
                        )
                    }
                }
            }
        }
    }

    private fun addAndSaveMessage(message: ChatMessage, mode: ChatMode = currentMode) {
        val historyKey = historyKeyFor(mode)
        val messages = if (mode == currentMode) {
            chatMessages
        } else {
            settingsStorage.loadChatMessages(historyKey).toMutableList()
        }

        messages.add(message)
        settingsStorage.saveChatMessages(historyKey, messages)

        if (mode == currentMode) {
            chatView.addMessage(message)
        }
    }

    private fun saveMessageOnly(message: ChatMessage, mode: ChatMode = currentMode) {
        val historyKey = historyKeyFor(mode)
        val messages = if (mode == currentMode) {
            chatMessages
        } else {
            settingsStorage.loadChatMessages(historyKey).toMutableList()
        }

        messages.add(message)
        settingsStorage.saveChatMessages(historyKey, messages)
    }

    private fun loadRequestMessagesFor(mode: ChatMode): List<ChatMessage> {
        return settingsStorage.loadChatMessages(historyKeyFor(mode))
            .filter { it.role != MessageRole.SYSTEM }
            .takeLast(if (mode == ChatMode.OR_API) OPENROUTER_HISTORY_LIMIT else 8)
            .map { message ->
                if (mode == ChatMode.OR_API && message.content.length > OPENROUTER_MESSAGE_CHAR_LIMIT) {
                    message.copy(content = message.content.takeLast(OPENROUTER_MESSAGE_CHAR_LIMIT))
                } else {
                    message
                }
            }
    }

    private fun warmOpenRouterConnection() {
        val apiKey = settingsStorage.loadApiKey()
        if (apiKey.isBlank()) {
            return
        }

        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastOpenRouterWarmUpAt < 120_000L) {
            return
        }

        lastOpenRouterWarmUpAt = now
        OpenRouterClient(apiKey).warmUp()
    }

    private fun historyKeyFor(mode: ChatMode): String {
        return when (mode) {
            ChatMode.OR_API -> "chat_messages_or_api"
            ChatMode.MAC_LOCAL_TEXT -> "chat_messages_mac_local_text"
            ChatMode.WINDOWS_LOCAL_IMAGE -> "chat_messages_windows_local_image"
        }
    }

    private fun welcomeMessageFor(mode: ChatMode): String {
        return when (mode) {
            ChatMode.OR_API -> "已连接到 ModelHub。请选择 OpenRouter 模型后开始使用。"
            ChatMode.MAC_LOCAL_TEXT -> "Mac 本地模型对话已准备好。请刷新并选择本地模型。"
            ChatMode.WINDOWS_LOCAL_IMAGE -> "Win 生图已配置为连接：$windowsImageBaseUrl"
        }
    }

    private fun isLegacyWelcomeMessage(message: ChatMessage, mode: ChatMode): Boolean {
        return message.role == MessageRole.SYSTEM &&
            (message.content == welcomeMessageFor(mode) || message.content == "新的对话已开始。")
    }
}
