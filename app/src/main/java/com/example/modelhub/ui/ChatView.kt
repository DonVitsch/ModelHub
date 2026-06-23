package com.example.modelhub.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import com.example.modelhub.R
import com.example.modelhub.data.ChatMessage
import com.example.modelhub.data.MessageRole
import com.example.modelhub.network.OpenRouterClient
import com.example.modelhub.util.UiUtils
import com.google.android.material.bottomsheet.BottomSheetDialog

enum class ChatMode {
    OR_API,
    MAC_LOCAL_TEXT,
    WINDOWS_LOCAL_IMAGE
}

class ChatView(
    context: Context,
    private val activityWindow: android.view.Window,
    initialDarkMode: Boolean = false
) {
    val root: LinearLayout
    val apiKeyInput: EditText
    val modelInput: EditText
    val checkpointInput: EditText
    val localHostInput: EditText
    val comfyHostInput: EditText
    val questionInput: EditText
    val sendButton: Button
    val progressBar: ProgressBar
    val scrollView: NestedScrollView
    val messageContainer: LinearLayout
    val clearButton: TextView
    val themeButton: TextView
    val settingsButton: TextView
    val orModelRow: LinearLayout
    val orModelSpinner: Spinner
    val fetchOpenRouterModelsButton: Button
    val localModelRow: LinearLayout
    val localModelSpinner: Spinner
    val refreshLocalModelsButton: Button
    val checkpointRow: LinearLayout
    val inputBar: LinearLayout

    private val statusText: TextView
    private val modelPickerCard: LinearLayout
    private val modelBadgeView: TextView
    private val modelTitleView: TextView
    private val modelProviderView: TextView
    private val settingsPanel: LinearLayout
    private val orApiButton: LinearLayout
    private val macLocalButton: LinearLayout
    private val windowsImageButton: LinearLayout
    private val welcomeView: LinearLayout
    private lateinit var welcomeTitleView: TextView
    private lateinit var welcomeSubtitleView: TextView
    private lateinit var loadingStatusView: TextView
    private val renderedMessages = mutableListOf<ChatMessage>()
    private val renderedMessageTexts = mutableMapOf<Int, TextView>()
    private val localModelNames = mutableListOf<String>()
    private val openRouterModels = mutableListOf<OpenRouterClient.PresetModel>()
    private var currentMode: ChatMode = ChatMode.OR_API
    private var isDarkMode = initialDarkMode

    var onSettingsChanged: ((String, String) -> Unit)? = null
    var onClearMessages: (() -> Unit)? = null
    var onModeChanged: ((ChatMode) -> Unit)? = null
    var onRefreshLocalModels: (() -> Unit)? = null
    var onFetchOpenRouterModels: (() -> Unit)? = null
    var onCheckpointChanged: ((String) -> Unit)? = null
    var onLocalHostChanged: ((String) -> Unit)? = null
    var onComfyHostChanged: ((String) -> Unit)? = null
    var onSendMessage: ((ChatMode, String, String, String) -> Unit)? = null
    var onThemeModeChanged: ((Boolean) -> Unit)? = null

    private data class Palette(
        val pageBg: Int,
        val surface: Int,
        val inputBg: Int,
        val border: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val accent: Int,
        val onAccent: Int,
        val userBubble: Int,
        val assistantBubble: Int,
        val systemBubble: Int
    )

    private val palette: Palette
        get() = if (isDarkMode) {
            Palette(
                pageBg = Color.rgb(18, 18, 18),
                surface = Color.rgb(25, 25, 25),
                inputBg = Color.rgb(42, 42, 42),
                border = Color.rgb(85, 85, 85),
                textPrimary = Color.rgb(238, 238, 238),
                textSecondary = Color.rgb(158, 158, 158),
                accent = Color.WHITE,
                onAccent = Color.BLACK,
                userBubble = Color.rgb(48, 48, 48),
                assistantBubble = Color.rgb(26, 26, 26),
                systemBubble = Color.rgb(36, 36, 36)
            )
        } else {
            Palette(
                pageBg = Color.rgb(250, 250, 250),
                surface = Color.WHITE,
                inputBg = Color.rgb(247, 247, 247),
                border = Color.rgb(232, 232, 232),
                textPrimary = Color.rgb(35, 35, 35),
                textSecondary = Color.rgb(124, 124, 124),
                accent = Color.BLACK,
                onAccent = Color.WHITE,
                userBubble = Color.rgb(232, 232, 232),
                assistantBubble = Color.WHITE,
                systemBubble = Color.rgb(242, 242, 242)
            )
        }

    init {
        val dp = { value: Int -> UiUtils.dp(context, value) }

        root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.pageBg)
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(12))
            setBackgroundColor(palette.surface)
        }

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val title = TextView(context).apply {
            text = "ModelHub"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.textPrimary)
        }

        val subtitle = TextView(context).apply {
            text = "多模型 AI 工作台"
            textSize = 13f
            setTextColor(palette.textSecondary)
        }

        statusText = TextView(context).apply {
            text = "当前：Fast · OpenAI gpt-oss-20b Nitro\n状态：已连接"
            textSize = 12f
            setTextColor(palette.textSecondary)
            setPadding(0, dp(4), 0, 0)
        }

        titleBox.addView(title)
        titleBox.addView(subtitle)
        titleBox.addView(statusText)
        titleRow.addView(titleBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        themeButton = createHeaderPill(context, if (isDarkMode) "浅色" else "深色")
        settingsButton = createHeaderPill(context, "设置")
        clearButton = createHeaderPill(context, "清空")

        titleRow.addView(settingsButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)).apply {
            setMargins(0, 0, dp(8), 0)
        })
        titleRow.addView(themeButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)).apply {
            setMargins(0, 0, dp(8), 0)
        })
        titleRow.addView(clearButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)))

        modelBadgeView = createBadgeView(context, "OR", badgeColorFor("OR"), dp(42), 13f)
        modelTitleView = TextView(context).apply {
            text = "OpenAI gpt-oss-20b Nitro"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.textPrimary)
        }
        modelProviderView = TextView(context).apply {
            text = "Fast · OpenRouter"
            textSize = 12f
            setTextColor(palette.textSecondary)
        }
        val modelTextBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(modelTitleView)
            addView(modelProviderView)
        }
        val modelChevron = TextView(context).apply {
            text = "⌄"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(palette.textSecondary)
        }
        modelPickerCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(12), dp(12))
            background = UiUtils.roundedStrokeBackground(palette.surface, dp(18).toFloat(), palette.border, dp(1))
            elevation = dp(2).toFloat()
            addView(modelBadgeView)
            addView(modelTextBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(12), 0, dp(8), 0)
            })
            addView(modelChevron, LinearLayout.LayoutParams(dp(28), dp(34)))
        }

        settingsPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        apiKeyInput = createHeaderInput(context, "OpenRouter API Key", true).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    activityWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                }
            }
        }

        modelInput = createHeaderInput(context, "模型名，例如 x-ai/grok-4.3", true).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    activityWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                }
            }
        }

        fetchOpenRouterModelsButton = Button(context).apply {
            text = "获取模型"
            textSize = 13f
            isAllCaps = false
            setTextColor(palette.textPrimary)
            background = UiUtils.roundedStrokeBackground(palette.inputBg, dp(14).toFloat(), palette.border, dp(1))
            visibility = View.GONE
        }

        orModelRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }

        orModelSpinner = Spinner(context).apply {
            background = selectorBackground(context)
        }
        setOpenRouterModels(listOf(OpenRouterClient.PresetModel("点击下方“获取模型”加载列表", "")))

        orModelRow.addView(orModelSpinner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(46)
        ))

        localHostInput = createHeaderInput(context, "请输入本地模型地址，如 http://主机:端口", true).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            visibility = View.GONE
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    activityWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                }
            }
        }

        localModelRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }

        localModelSpinner = Spinner(context).apply {
            background = selectorBackground(context)
        }
        setLocalModels(listOf("点击刷新模型"))

        refreshLocalModelsButton = Button(context).apply {
            text = "刷新"
            textSize = 13f
            isAllCaps = false
            setTextColor(palette.textPrimary)
            background = UiUtils.roundedStrokeBackground(palette.inputBg, dp(14).toFloat(), palette.border, dp(1))
        }

        localModelRow.addView(localModelSpinner, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            setMargins(0, 0, dp(8), 0)
        })
        localModelRow.addView(refreshLocalModelsButton, LinearLayout.LayoutParams(dp(72), dp(46)))

        checkpointInput = createHeaderInput(context, "请输入模型文件名，如 xxx.safetensors", true).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    activityWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                }
            }
        }

        checkpointRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }

        comfyHostInput = createHeaderInput(context, "请输入 ComfyUI 服务器地址，如 http://主机:端口", true).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            visibility = View.GONE
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    activityWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                }
            }
        }

        settingsPanel.addView(apiKeyInput, headerInputParams(context))
        settingsPanel.addView(fetchOpenRouterModelsButton, headerInputParams(context))
        settingsPanel.addView(localHostInput, headerInputParams(context))
        settingsPanel.addView(comfyHostInput, headerInputParams(context))
        settingsPanel.addView(checkpointInput, headerInputParams(context))

        header.addView(titleRow)
        header.addView(modelPickerCard, headerInputParams(context))
        header.addView(settingsPanel)

        messageContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(18), dp(14), dp(18))
        }
        welcomeView = createWelcomeView(context)
        showWelcomeView()

        scrollView = NestedScrollView(context).apply {
            setBackgroundColor(palette.pageBg)
            isFillViewport = true
            isNestedScrollingEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(messageContainer, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            ))
        }

        inputBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(12), dp(10), dp(12), dp(8))
            setBackgroundColor(palette.surface)
            elevation = dp(6).toFloat()
        }

        questionInput = EditText(context).apply {
            hint = "有什么我能帮您的吗？"
            minLines = 1
            maxLines = 5
            textSize = 16f
            setTextColor(palette.textPrimary)
            setHintTextColor(palette.textSecondary)
            gravity = Gravity.CENTER_VERTICAL
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = UiUtils.roundedStrokeBackground(palette.inputBg, dp(24).toFloat(), palette.border, dp(1))
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    activityWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                    scrollView.post {
                        scrollView.fullScroll(View.FOCUS_DOWN)
                    }
                }
            }
        }

        sendButton = Button(context).apply {
            text = "发送"
            textSize = 15f
            isAllCaps = false
            setTextColor(palette.onAccent)
            background = UiUtils.roundedBackground(palette.accent, dp(22).toFloat())
        }

        inputBar.addView(questionInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(0, 0, dp(10), 0)
        })
        inputBar.addView(sendButton, LinearLayout.LayoutParams(dp(76), dp(48)))

        val modeBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(2), dp(18), dp(10))
            setBackgroundColor(palette.surface)
        }

        orApiButton = createModeButton(context, "云端", R.drawable.ic_mode_cloud)
        macLocalButton = createModeButton(context, "本地", R.drawable.ic_mode_laptop)
        windowsImageButton = createModeButton(context, "生图", R.drawable.ic_mode_image)

        modeBar.addView(orApiButton, modeButtonParams(context))
        modeBar.addView(macLocalButton, modeButtonParams(context))
        modeBar.addView(windowsImageButton, modeButtonParams(context))

        progressBar = ProgressBar(context).apply {
            visibility = View.GONE
        }

        loadingStatusView = TextView(context).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(palette.textSecondary)
            visibility = View.GONE
        }

        val loadingGroup = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(progressBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(loadingStatusView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(4), 0, 0)
            })
        }

        root.addView(header)
        root.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        root.addView(loadingGroup, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setMargins(0, dp(4), 0, dp(4))
        })
        root.addView(inputBar)
        root.addView(modeBar)

        apiKeyInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                onSettingsChanged?.invoke(apiKeyInput.text.toString(), modelInput.text.toString())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        modelInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                onSettingsChanged?.invoke(apiKeyInput.text.toString(), modelInput.text.toString())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        checkpointInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                onCheckpointChanged?.invoke(checkpointInput.text.toString())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        localHostInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                onLocalHostChanged?.invoke(localHostInput.text.toString())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        comfyHostInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                onComfyHostChanged?.invoke(comfyHostInput.text.toString())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        clearButton.setOnClickListener {
            onClearMessages?.invoke()
        }

        themeButton.setOnClickListener {
            onThemeModeChanged?.invoke(!isDarkMode)
        }

        settingsButton.setOnClickListener {
            settingsPanel.visibility = if (settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        modelPickerCard.setOnClickListener {
            showModelPicker()
        }

        refreshLocalModelsButton.setOnClickListener {
            onRefreshLocalModels?.invoke()
        }

        fetchOpenRouterModelsButton.setOnClickListener {
            onFetchOpenRouterModels?.invoke()
        }

        sendButton.setOnClickListener {
            sendQuestionFromInput()
        }

        orApiButton.setOnClickListener {
            selectMode(ChatMode.OR_API)
        }

        macLocalButton.setOnClickListener {
            selectMode(ChatMode.MAC_LOCAL_TEXT)
        }

        windowsImageButton.setOnClickListener {
            selectMode(ChatMode.WINDOWS_LOCAL_IMAGE)
        }

        selectMode(ChatMode.OR_API)
    }

    fun setInitialSettings(apiKey: String, model: String) {
        apiKeyInput.setText(apiKey)
        modelInput.setText(model)
    }

    fun setInitialCheckpoint(checkpoint: String) {
        checkpointInput.setText(checkpoint)
    }

    fun setInitialHosts(localHost: String, comfyHost: String) {
        localHostInput.setText(localHost)
        comfyHostInput.setText(comfyHost)
    }

    fun getLocalHostText(): String = localHostInput.text.toString().trim()

    fun getComfyHostText(): String = comfyHostInput.text.toString().trim()

    fun setOpenRouterModels(models: List<OpenRouterClient.PresetModel>) {
        openRouterModels.clear()
        openRouterModels.addAll(models)
        val modelNames = models.map { it.displayName }
        val adapter = object : ArrayAdapter<String>(root.context, android.R.layout.simple_spinner_item, modelNames) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.setTextColor(palette.textPrimary)
                view.textSize = 14f
                view.setPadding(UiUtils.dp(root.context, 12), 0, UiUtils.dp(root.context, 12), 0)
                return view
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent) as TextView
                view.setTextColor(Color.rgb(35, 35, 35))
                view.textSize = 15f
                view.setPadding(UiUtils.dp(root.context, 12), UiUtils.dp(root.context, 12), UiUtils.dp(root.context, 12), UiUtils.dp(root.context, 12))
                return view
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        orModelSpinner.adapter = adapter
        orModelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateCurrentModelCard()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        updateCurrentModelCard()
    }

    fun selectOpenRouterModel(modelId: String) {
        val index = openRouterModels.indexOfFirst { it.modelId == modelId }
        if (index >= 0) {
            orModelSpinner.setSelection(index)
            updateCurrentModelCard()
        }
    }

    fun getSelectedOpenRouterModel(): String {
        val position = orModelSpinner.selectedItemPosition
        return openRouterModels.getOrNull(position)?.modelId ?: ""
    }

    fun setLocalModels(models: List<String>) {
        val displayModels = if (models.isEmpty()) {
            listOf("未读取到模型")
        } else {
            models
        }

        val adapter = object : ArrayAdapter<String>(root.context, android.R.layout.simple_spinner_item, displayModels) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.setTextColor(palette.textPrimary)
                view.textSize = 14f
                view.setPadding(UiUtils.dp(root.context, 12), 0, UiUtils.dp(root.context, 12), 0)
                return view
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent) as TextView
                view.setTextColor(Color.rgb(35, 35, 35))
                view.textSize = 15f
                view.setPadding(UiUtils.dp(root.context, 12), UiUtils.dp(root.context, 12), UiUtils.dp(root.context, 12), UiUtils.dp(root.context, 12))
                return view
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        localModelSpinner.adapter = adapter
        localModelSpinner.setSelection(0)
        localModelNames.clear()
        localModelNames.addAll(displayModels.filter { it != "点击刷新模型" && it != "未读取到模型" })
        updateCurrentModelCard()
    }

    fun getSelectedLocalModel(): String {
        val selected = localModelSpinner.selectedItem?.toString() ?: ""
        return if (selected == "点击刷新模型" || selected == "未读取到模型") {
            ""
        } else {
            selected
        }
    }

    fun addMessage(message: ChatMessage) {
        val messageIndex = renderedMessages.size
        if (renderedMessages.isEmpty()) {
            messageContainer.removeAllViews()
        }
        renderedMessages.add(message)
        val bubble = createMessageBubble(message, messageIndex)
        messageContainer.addView(bubble)
        scrollView.post {
            scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    fun addStreamingMessage(message: ChatMessage): Int {
        addMessage(message)
        return renderedMessages.lastIndex
    }

    fun updateMessageContent(messageIndex: Int, content: String) {
        val oldMessage = renderedMessages.getOrNull(messageIndex) ?: return
        renderedMessages[messageIndex] = oldMessage.copy(content = content)
        renderedMessageTexts[messageIndex]?.text = content
        scrollView.post {
            scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    fun appendMessageContent(messageIndex: Int, delta: String) {
        if (delta.isEmpty()) {
            return
        }

        val oldMessage = renderedMessages.getOrNull(messageIndex) ?: return
        val nextContent = oldMessage.content + delta
        renderedMessages[messageIndex] = oldMessage.copy(content = nextContent)

        renderedMessageTexts[messageIndex]?.let { textView ->
            if (oldMessage.content.isBlank()) {
                textView.text = delta
            } else {
                textView.append(delta)
            }
        }
    }

    fun clearMessages() {
        renderedMessages.clear()
        renderedMessageTexts.clear()
        messageContainer.removeAllViews()
        showWelcomeView()
    }

    fun clearQuestionInput() {
        questionInput.setText("")
    }

    fun setLoading(isLoading: Boolean) {
        progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        sendButton.isEnabled = !isLoading
        sendButton.text = if (isLoading) "..." else if (currentMode == ChatMode.WINDOWS_LOCAL_IMAGE) "生成" else "发送"

        if (isLoading) {
            loadingStatusView.text = when (currentMode) {
                ChatMode.OR_API -> "正在生成回复…"
                ChatMode.MAC_LOCAL_TEXT -> "本地模型思考中，请稍候…"
                ChatMode.WINDOWS_LOCAL_IMAGE -> "正在生成图片，可能需要几十秒到几分钟…"
            }
            loadingStatusView.visibility = View.VISIBLE
        } else {
            loadingStatusView.visibility = View.GONE
        }
    }

    fun getCheckpointText(): String = checkpointInput.text.toString()

    fun withCurrentModelMetadata(message: ChatMessage): ChatMessage {
        if (message.role != MessageRole.ASSISTANT) {
            return message
        }
        val option = currentModelOption()
        return message.copy(
            modelSource = option.sourceLabel,
            modelBadge = option.badge,
            modelBadgeColor = option.badgeColor
        )
    }

    private fun selectMode(mode: ChatMode) {
        currentMode = mode
        updateModeButtonStyle(orApiButton, mode == ChatMode.OR_API)
        updateModeButtonStyle(macLocalButton, mode == ChatMode.MAC_LOCAL_TEXT)
        updateModeButtonStyle(windowsImageButton, mode == ChatMode.WINDOWS_LOCAL_IMAGE)

        when (mode) {
            ChatMode.OR_API -> {
                apiKeyInput.visibility = View.VISIBLE
                fetchOpenRouterModelsButton.visibility = View.VISIBLE
                checkpointInput.visibility = View.GONE
                modelInput.visibility = View.GONE
                localHostInput.visibility = View.GONE
                comfyHostInput.visibility = View.GONE
                orModelRow.visibility = View.GONE
                localModelRow.visibility = View.GONE
                checkpointRow.visibility = View.GONE
                questionInput.hint = "有什么我能帮您的吗？"
                sendButton.text = "发送"
            }
            ChatMode.MAC_LOCAL_TEXT -> {
                apiKeyInput.visibility = View.GONE
                fetchOpenRouterModelsButton.visibility = View.GONE
                checkpointInput.visibility = View.GONE
                modelInput.visibility = View.GONE
                localHostInput.visibility = View.VISIBLE
                comfyHostInput.visibility = View.GONE
                orModelRow.visibility = View.GONE
                localModelRow.visibility = View.VISIBLE
                checkpointRow.visibility = View.GONE
                questionInput.hint = "输入要发送给本地模型的消息..."
                sendButton.text = "发送"
            }
            ChatMode.WINDOWS_LOCAL_IMAGE -> {
                apiKeyInput.visibility = View.GONE
                fetchOpenRouterModelsButton.visibility = View.GONE
                checkpointInput.visibility = View.VISIBLE
                modelInput.visibility = View.GONE
                localHostInput.visibility = View.GONE
                comfyHostInput.visibility = View.VISIBLE
                orModelRow.visibility = View.GONE
                localModelRow.visibility = View.GONE
                checkpointRow.visibility = View.VISIBLE
                questionInput.hint = "描述你想生成的图片..."
                sendButton.text = "生成"
            }
        }

        updateCurrentModelCard()
        onModeChanged?.invoke(mode)
    }

    private fun createMessageBubble(message: ChatMessage, messageIndex: Int): View {
        val context = root.context
        val dp = { value: Int -> UiUtils.dp(context, value) }
        val isUser = message.role == MessageRole.USER
        val isSystem = message.role == MessageRole.SYSTEM
        val meta = messageMetaFor(message)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(7), 0, dp(7))
            }
        }

        if (isSystem) {
            val systemText = TextView(context).apply {
                text = message.content
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(palette.textSecondary)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = UiUtils.roundedBackground(palette.systemBubble, dp(16).toFloat())
            }
            container.addView(systemText, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
            return container
        }

        val contentRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (isUser) Gravity.RIGHT else Gravity.LEFT
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val avatar = if (isUser) {
            createBadgeView(context, "我", Color.rgb(34, 34, 34), dp(36), 13f)
        } else {
            createBadgeView(context, meta.badge, meta.badgeColor, dp(36), 11f)
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = if (!isUser) {
                UiUtils.roundedStrokeBackground(palette.assistantBubble, dp(16).toFloat(), palette.border, dp(1))
            } else {
                UiUtils.roundedBackground(palette.userBubble, dp(16).toFloat())
            }
            elevation = if (message.imageLocalPath != null) dp(2).toFloat() else 0f
            layoutParams = LinearLayout.LayoutParams(
                if (message.imageLocalPath != null) LinearLayout.LayoutParams.MATCH_PARENT else LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (message.imageLocalPath == null) {
                    width = (context.resources.displayMetrics.widthPixels * 0.76f).toInt()
                }
            }
        }

        if (!isUser) {
            val sourceLabel = TextView(context).apply {
                text = meta.sourceLabel
                textSize = 12f
                setTextColor(palette.textSecondary)
                setPadding(0, 0, 0, dp(6))
            }
            card.addView(sourceLabel)
        }

        val textView = TextView(context).apply {
            text = message.content.ifBlank { "正在生成..." }
            textSize = 15f
            setTextColor(palette.textPrimary)
            setLineSpacing(dp(3).toFloat(), 1f)
        }

        card.addView(textView)
        renderedMessageTexts[messageIndex] = textView

        if (message.imageLocalPath != null) {
            addImageToCard(context, card, message.imageLocalPath)
        }

        if (isUser) {
            contentRow.addView(card, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(40), 0, dp(8), 0)
            })
            contentRow.addView(avatar)
        } else {
            contentRow.addView(avatar)
            contentRow.addView(card, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(8), 0, dp(40), 0)
            })
        }

        container.addView(contentRow)

        container.addView(createMessageActions(context, messageIndex, isUser))

        return container
    }

    private fun createMessageActions(context: Context, messageIndex: Int, alignEnd: Boolean): View {
        val dp = { value: Int -> UiUtils.dp(context, value) }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (alignEnd) Gravity.END else Gravity.START
            setPadding(0, dp(4), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        row.addView(createActionButton(context, "复制") {
            copyMessage(renderedMessages.getOrNull(messageIndex)?.content.orEmpty())
        })
        row.addView(createActionButton(context, "编辑") {
            editMessage(renderedMessages.getOrNull(messageIndex)?.content.orEmpty())
        })
        row.addView(createActionButton(context, "重试") {
            retryMessage(messageIndex)
        })

        return row
    }

    private fun createActionButton(context: Context, label: String, onClick: () -> Unit): TextView {
        val dp = { value: Int -> UiUtils.dp(context, value) }
        return TextView(context).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(palette.textSecondary)
            setPadding(dp(9), dp(5), dp(9), dp(5))
            background = UiUtils.roundedStrokeBackground(Color.TRANSPARENT, dp(14).toFloat(), palette.border, dp(1))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(4), 0, 0, 0)
            }
        }
    }

    private fun addImageToCard(context: Context, card: LinearLayout, imagePath: String) {
        val dp = { value: Int -> UiUtils.dp(context, value) }
        try {
            val displayWidth = context.resources.displayMetrics.widthPixels - dp(48)
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(imagePath, options)

            val scaleFactor = (options.outWidth.toFloat() / displayWidth.toFloat())
                .coerceAtLeast(1f)
                .toInt()

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = scaleFactor.coerceAtLeast(1)
            }
            val bitmap = BitmapFactory.decodeFile(imagePath, decodeOptions)

            if (bitmap != null) {
                val imageView = ImageView(context).apply {
                    setImageBitmap(bitmap)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    maxHeight = dp(420)
                    adjustViewBounds = true
                    setPadding(0, dp(10), 0, 0)
                }
                card.addView(imageView)

                imageView.layoutParams = LinearLayout.LayoutParams(
                    displayWidth.coerceAtMost(dp(480)),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
        } catch (_: Exception) {
            val errorText = TextView(context).apply {
                text = "[图片加载失败]"
                textSize = 13f
                setTextColor(palette.textSecondary)
                setPadding(0, dp(4), 0, 0)
            }
            card.addView(errorText)
        }
    }

    private fun sendQuestionFromInput() {
        val question = questionInput.text.toString()
        if (question.isBlank()) {
            return
        }
        sendPrompt(question)
    }

    private fun sendPrompt(prompt: String) {
        val apiKey = apiKeyInput.text.toString()
        val model = when (currentMode) {
            ChatMode.OR_API -> getSelectedOpenRouterModel()
            ChatMode.MAC_LOCAL_TEXT -> getSelectedLocalModel()
            ChatMode.WINDOWS_LOCAL_IMAGE -> checkpointInput.text.toString()
        }

        if (currentMode == ChatMode.OR_API && apiKey.isBlank()) {
            addMessage(ChatMessage("请先输入 OpenRouter API Key。", MessageRole.SYSTEM))
            return
        }

        if (currentMode == ChatMode.OR_API && model.isBlank()) {
            addMessage(ChatMessage("请先点击“获取模型”并选择一个模型。", MessageRole.SYSTEM))
            return
        }

        if (currentMode == ChatMode.WINDOWS_LOCAL_IMAGE && model.isBlank()) {
            addMessage(ChatMessage("请输入模型文件名。", MessageRole.SYSTEM))
            return
        }

        onSendMessage?.invoke(currentMode, apiKey, model, prompt)
    }

    private fun copyMessage(content: String) {
        val clipboard = root.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ModelHub message", content))
        Toast.makeText(root.context, "已复制", Toast.LENGTH_SHORT).show()
    }

    private fun editMessage(content: String) {
        questionInput.setText(content)
        questionInput.setSelection(content.length)
        questionInput.requestFocus()
    }

    private fun retryMessage(messageIndex: Int) {
        val message = renderedMessages.getOrNull(messageIndex) ?: return
        val retryPrompt = if (message.role == MessageRole.ASSISTANT) {
            renderedMessages
                .take(messageIndex)
                .lastOrNull { it.role == MessageRole.USER }
                ?.content
        } else {
            message.content
        }

        if (retryPrompt.isNullOrBlank()) {
            Toast.makeText(root.context, "没有可重试的上一条消息", Toast.LENGTH_SHORT).show()
            return
        }

        sendPrompt(retryPrompt)
    }

    private fun showModelPicker() {
        val context = root.context
        val dp = { value: Int -> UiUtils.dp(context, value) }
        val dialog = BottomSheetDialog(context)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(20))
            setBackgroundColor(palette.surface)
        }

        val title = TextView(context).apply {
            text = "选择模型"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.textPrimary)
            setPadding(0, 0, 0, dp(14))
        }
        content.addView(title)

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val options = buildModelOptions()
        val relevantGroups = relevantGroupsForCurrentMode()
        ModelOptionGroup.values().forEach { group ->
            if (group !in relevantGroups) return@forEach
            val groupOptions = options.filter { it.group == group }
            if (groupOptions.isNotEmpty()) {
                list.addView(createModelGroupLabel(context, group.title))
                groupOptions.forEach { option ->
                    list.addView(createModelOptionRow(context, option, isModelSelected(option)) {
                        applyModelOption(option)
                        dialog.dismiss()
                    })
                }
            }
        }

        if (currentMode == ChatMode.OR_API && openRouterModels.none { it.modelId.isNotBlank() }) {
            list.addView(createModelGroupLabel(context, ModelOptionGroup.QUALITY.title))
            list.addView(createModelOptionRow(
                context = context,
                option = ModelOption(
                    id = "__fetch_openrouter__",
                    title = "获取云端模型列表",
                    provider = "OpenRouter",
                    group = ModelOptionGroup.QUALITY,
                    badge = "OR",
                    badgeColor = badgeColorFor("OR")
                ),
                selected = false
            ) {
                selectMode(ChatMode.OR_API)
                onFetchOpenRouterModels?.invoke()
                dialog.dismiss()
            })
        }

        if (currentMode == ChatMode.MAC_LOCAL_TEXT && localModelNames.isEmpty()) {
            list.addView(createModelGroupLabel(context, ModelOptionGroup.LOCAL.title))
            list.addView(createModelOptionRow(
                context = context,
                option = ModelOption(
                    id = "__refresh_local__",
                    title = "刷新本地模型列表",
                    provider = "Ollama",
                    group = ModelOptionGroup.LOCAL,
                    badge = "OL",
                    badgeColor = badgeColorFor("OL")
                ),
                selected = false
            ) {
                selectMode(ChatMode.MAC_LOCAL_TEXT)
                onRefreshLocalModels?.invoke()
                dialog.dismiss()
            })
        }

        val scroller = NestedScrollView(context).apply {
            isFillViewport = false
            isNestedScrollingEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(list)
        }
        content.addView(scroller, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (context.resources.displayMetrics.heightPixels * 0.72f).toInt()
        ))
        dialog.setContentView(content)
        dialog.show()
    }

    private fun buildModelOptions(): List<ModelOption> {
        val cloudOptions = openRouterModels.filter { it.modelId.isNotBlank() }.map { preset ->
            modelOptionForOpenRouter(preset)
        }
        val localOptions = localModelNames.map { modelName ->
            ModelOption(
                id = modelName,
                title = modelName,
                provider = "Ollama",
                group = ModelOptionGroup.LOCAL,
                badge = "OL",
                badgeColor = badgeColorFor("OL")
            )
        }
        val imageOptions = listOf(
            ModelOption(
                id = "__windows_image__",
                title = getCheckpointText().ifBlank { "切换到生图模式" },
                provider = "Windows",
                group = ModelOptionGroup.IMAGE,
                badge = "IMG",
                badgeColor = badgeColorFor("IMG")
            )
        )
        return cloudOptions + localOptions + imageOptions
    }

    private fun createModelGroupLabel(context: Context, label: String): TextView {
        return TextView(context).apply {
            text = label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.textSecondary)
            setPadding(0, UiUtils.dp(context, 12), 0, UiUtils.dp(context, 8))
        }
    }

    private fun createModelOptionRow(
        context: Context,
        option: ModelOption,
        selected: Boolean,
        onClick: () -> Unit
    ): View {
        val dp = { value: Int -> UiUtils.dp(context, value) }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = UiUtils.roundedStrokeBackground(palette.surface, dp(16).toFloat(), palette.border, dp(1))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(8))
            }
        }

        row.addView(createBadgeView(context, option.badge, option.badgeColor, dp(40), 12f))

        val textBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        textBox.addView(TextView(context).apply {
            text = option.title
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.textPrimary)
        })
        textBox.addView(TextView(context).apply {
            text = option.provider
            textSize = 12f
            setTextColor(palette.textSecondary)
        })
        row.addView(textBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(12), 0, dp(8), 0)
        })

        row.addView(TextView(context).apply {
            text = if (selected) "✓" else ""
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(palette.textPrimary)
        }, LinearLayout.LayoutParams(dp(28), dp(36)))

        return row
    }

    private fun applyModelOption(option: ModelOption) {
        when (option.group) {
            ModelOptionGroup.FAST,
            ModelOptionGroup.QUALITY -> {
                val index = openRouterModels.indexOfFirst { it.modelId == option.id }
                if (index >= 0) {
                    orModelSpinner.setSelection(index)
                }
                selectMode(ChatMode.OR_API)
            }
            ModelOptionGroup.LOCAL -> {
                val index = localModelNames.indexOf(option.id)
                if (index >= 0) {
                    localModelSpinner.setSelection(index)
                }
                selectMode(ChatMode.MAC_LOCAL_TEXT)
            }
            ModelOptionGroup.IMAGE -> {
                selectMode(ChatMode.WINDOWS_LOCAL_IMAGE)
            }
        }
        updateCurrentModelCard()
    }

    private fun isModelSelected(option: ModelOption): Boolean {
        return when (option.group) {
            ModelOptionGroup.FAST,
            ModelOptionGroup.QUALITY -> currentMode == ChatMode.OR_API && option.id == getSelectedOpenRouterModel()
            ModelOptionGroup.LOCAL -> currentMode == ChatMode.MAC_LOCAL_TEXT && option.id == getSelectedLocalModel()
            ModelOptionGroup.IMAGE -> currentMode == ChatMode.WINDOWS_LOCAL_IMAGE
        }
    }

    private fun updateCurrentModelCard() {
        val option = currentModelOption()
        modelBadgeView.text = option.badge
        modelBadgeView.background = UiUtils.roundedBackground(option.badgeColor, UiUtils.dp(root.context, 21).toFloat())
        modelTitleView.text = option.title
        modelProviderView.text = when (option.group) {
            ModelOptionGroup.FAST -> "Fast · ${option.provider}"
            ModelOptionGroup.QUALITY -> option.provider
            ModelOptionGroup.LOCAL -> "Local · ${option.provider}"
            ModelOptionGroup.IMAGE -> "${option.provider} · ${option.title}"
        }
        statusText.text = "当前：${modelProviderView.text}\n状态：已连接"
    }

    private fun currentModelOption(): ModelOption {
        return when (currentMode) {
            ChatMode.OR_API -> {
                val preset = openRouterModels.getOrNull(orModelSpinner.selectedItemPosition)
                    ?: OpenRouterClient.PresetModel("点击获取模型", "")
                modelOptionForOpenRouter(preset)
            }
            ChatMode.MAC_LOCAL_TEXT -> {
                val modelName = getSelectedLocalModel().ifBlank { "点击刷新模型" }
                ModelOption(
                    id = modelName,
                    title = modelName,
                    provider = "Ollama",
                    group = ModelOptionGroup.LOCAL,
                    badge = "OL",
                    badgeColor = badgeColorFor("OL")
                )
            }
            ChatMode.WINDOWS_LOCAL_IMAGE -> {
                val checkpointName = getCheckpointText().ifBlank { "请输入模型文件名" }
                ModelOption(
                    id = checkpointName,
                    title = checkpointName,
                    provider = "Windows",
                    group = ModelOptionGroup.IMAGE,
                    badge = "IMG",
                    badgeColor = badgeColorFor("IMG")
                )
            }
        }
    }

    private fun modelOptionForOpenRouter(preset: OpenRouterClient.PresetModel): ModelOption {
        val display = preset.displayName.substringAfter(":").trim()
        val provider = providerFor(preset.displayName, preset.modelId)
        val badge = badgeFor(preset.displayName, preset.modelId)
        val title = display
            .removePrefix("OpenAI ")
            .removePrefix("GLM ")
            .let { if (badge == "GLM" && !it.startsWith("GLM")) "GLM $it" else it }
        val group = if (preset.displayName.startsWith("Fast:")) ModelOptionGroup.FAST else ModelOptionGroup.QUALITY
        return ModelOption(
            id = preset.modelId,
            title = title,
            provider = provider,
            group = group,
            badge = badge,
            badgeColor = badgeColorFor(badge)
        )
    }

    private fun messageMetaFor(message: ChatMessage): ModelOption {
        val fallback = currentModelOption()
        return ModelOption(
            id = fallback.id,
            title = fallback.title,
            provider = message.modelSource?.substringBefore(" · ") ?: fallback.provider,
            group = fallback.group,
            badge = message.modelBadge ?: fallback.badge,
            badgeColor = message.modelBadgeColor ?: fallback.badgeColor
        ).let {
            if (message.modelSource != null) {
                it.copy(title = message.modelSource.substringAfter(" · ", fallback.title))
            } else {
                it
            }
        }
    }

    private fun providerFor(displayName: String, modelId: String): String {
        return when {
            modelId.contains("qwen", true) -> "Qwen"
            modelId.contains("deepseek", true) -> "DeepSeek"
            modelId.contains("moonshot", true) || displayName.contains("kimi", true) -> "Kimi"
            modelId.contains("x-ai", true) || displayName.contains("grok", true) -> "Grok"
            modelId.contains("z-ai", true) || displayName.contains("glm", true) -> "Z.ai"
            modelId.contains("openai", true) -> "OpenAI"
            modelId.contains("inclusion", true) -> "OpenRouter"
            else -> "OpenRouter"
        }
    }

    private fun badgeFor(displayName: String, modelId: String): String {
        return when {
            modelId.contains("qwen", true) -> "QW"
            modelId.contains("deepseek", true) -> "DS"
            modelId.contains("moonshot", true) || displayName.contains("kimi", true) -> "KM"
            modelId.contains("x-ai", true) || displayName.contains("grok", true) -> "GX"
            modelId.contains("z-ai", true) || displayName.contains("glm", true) -> "GLM"
            modelId.contains("openai", true) -> "OR"
            else -> "OR"
        }
    }

    private fun badgeColorFor(badge: String): Int {
        return when (badge) {
            "GLM" -> Color.rgb(236, 243, 255)
            "QW" -> Color.rgb(231, 246, 255)
            "DS" -> Color.rgb(235, 241, 255)
            "KM" -> Color.rgb(248, 239, 255)
            "GX" -> Color.rgb(238, 238, 238)
            "OL" -> Color.rgb(232, 246, 238)
            "IMG" -> Color.rgb(255, 243, 230)
            else -> Color.rgb(242, 242, 242)
        }
    }

    private fun createBadgeView(context: Context, textValue: String, color: Int, size: Int, textSp: Float): TextView {
        return TextView(context).apply {
            text = textValue
            textSize = textSp
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(34, 34, 34))
            background = UiUtils.roundedBackground(color, size / 2f)
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
    }

    private fun createHeaderInput(context: Context, hintText: String, singleLine: Boolean): EditText {
        val dp = { value: Int -> UiUtils.dp(context, value) }
        return EditText(context).apply {
            hint = hintText
            textSize = 14f
            setTextColor(palette.textPrimary)
            setHintTextColor(palette.textSecondary)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            setSingleLine(singleLine)
            background = selectorBackground(context)
        }
    }

    private fun createWelcomeView(context: Context): LinearLayout {
        val dp = { value: Int -> UiUtils.dp(context, value) }
        val logo = ImageView(context).apply {
            setImageResource(R.drawable.modelhub_logo_1024)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        welcomeTitleView = TextView(context).apply {
            text = "嗨，我们进入正题吧"
            textSize = 28f
            typeface = Typeface.DEFAULT
            gravity = Gravity.CENTER
            setTextColor(palette.textPrimary)
            setPadding(0, dp(20), 0, 0)
        }
        welcomeSubtitleView = TextView(context).apply {
            text = "今天想聊点什么？"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(palette.textSecondary)
            setPadding(dp(18), dp(10), dp(18), 0)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), 0, dp(18), dp(28))
            addView(logo, LinearLayout.LayoutParams(dp(88), dp(88)))
            addView(welcomeTitleView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(welcomeSubtitleView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun updateWelcomeContent() {
        val (title, subtitle) = when (currentMode) {
            ChatMode.OR_API -> "嗨，我们进入正题吧" to "先在设置里填好 API Key，点击\"获取模型\"后即可开始对话"
            ChatMode.MAC_LOCAL_TEXT -> "连接你的本地模型" to "先在设置里填好本地模型地址，点击\"刷新模型\"后即可开始对话"
            ChatMode.WINDOWS_LOCAL_IMAGE -> "用 ComfyUI 生成图片" to "先在设置里填好服务器地址和模型文件名，描述你想要的画面"
        }
        welcomeTitleView.text = title
        welcomeSubtitleView.text = subtitle
    }

    private fun showWelcomeView() {
        updateWelcomeContent()
        if (welcomeView.parent != null) {
            (welcomeView.parent as? LinearLayout)?.removeView(welcomeView)
        }
        messageContainer.addView(welcomeView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ))
    }

    private fun createHeaderPill(context: Context, textValue: String): TextView {
        val dp = { value: Int -> UiUtils.dp(context, value) }
        return TextView(context).apply {
            text = textValue
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(palette.textPrimary)
            setPadding(dp(13), 0, dp(13), 0)
            background = UiUtils.roundedStrokeBackground(palette.inputBg, dp(18).toFloat(), palette.border, dp(1))
        }
    }

    private fun selectorBackground(context: Context) =
        UiUtils.roundedStrokeBackground(
            palette.inputBg,
            UiUtils.dp(context, 14).toFloat(),
            palette.border,
            UiUtils.dp(context, 1)
        )

    private fun headerInputParams(context: Context): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, UiUtils.dp(context, 10), 0, 0)
        }
    }

    private fun createModeButton(context: Context, label: String, iconRes: Int): LinearLayout {
        val dp = { value: Int -> UiUtils.dp(context, value) }
        val icon = ImageView(context).apply {
            setImageResource(iconRes)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = UiUtils.roundedStrokeBackground(Color.TRANSPARENT, dp(24).toFloat(), palette.border, dp(1))
        }
        val text = TextView(context).apply {
            this.text = label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            visibility = View.INVISIBLE
            setTextColor(palette.textPrimary)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = label
            foreground = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless)).let {
                val drawable = it.getDrawable(0)
                it.recycle()
                drawable
            }
            addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(text, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(22)).apply {
                setMargins(0, dp(2), 0, 0)
            })
        }
    }

    private fun modeButtonParams(context: Context): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, UiUtils.dp(context, 72), 1f).apply {
            setMargins(UiUtils.dp(context, 10), 0, UiUtils.dp(context, 10), 0)
        }
    }

    private fun updateModeButtonStyle(button: LinearLayout, selected: Boolean) {
        val icon = button.getChildAt(0) as ImageView
        val label = button.getChildAt(1) as TextView
        val iconDrawable: Drawable? = icon.drawable
        val dp = { value: Int -> UiUtils.dp(button.context, value) }

        if (selected) {
            icon.background = UiUtils.roundedBackground(palette.accent, dp(24).toFloat())
            iconDrawable?.setTint(palette.onAccent)
            label.visibility = View.VISIBLE
            label.setTextColor(palette.textPrimary)
        } else {
            icon.background = UiUtils.roundedStrokeBackground(Color.TRANSPARENT, dp(24).toFloat(), palette.border, dp(1))
            iconDrawable?.setTint(palette.textSecondary)
            label.visibility = View.INVISIBLE
            label.setTextColor(palette.textSecondary)
        }
    }
}
