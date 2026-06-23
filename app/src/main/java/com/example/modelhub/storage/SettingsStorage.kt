package com.example.modelhub.storage

import android.content.Context
import android.content.SharedPreferences
import com.example.modelhub.data.ChatMessage
import com.example.modelhub.data.MessageRole
import org.json.JSONArray
import org.json.JSONObject

class SettingsStorage(context: Context) {
    private val prefsName = "modelhub_settings"
    private val apiKeyPref = "api_key"
    private val modelPref = "model_name"
    private val chatMessagesPref = "chat_messages"
    private val checkpointPref = "comfyui_checkpoint"
    private val darkModePref = "dark_mode"
    private val localModelHostPref = "local_model_host"
    private val comfyUiHostPref = "comfyui_host"
    private val prefs: SharedPreferences = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    fun saveSettings(apiKey: String, modelName: String) {
        prefs.edit()
            .putString(apiKeyPref, apiKey.trim())
            .putString(modelPref, modelName.trim())
            .apply()
    }

    fun loadApiKey(): String {
        return prefs.getString(apiKeyPref, "") ?: ""
    }

    fun loadModelName(): String {
        return prefs.getString(modelPref, "") ?: ""
    }

    fun saveCheckpoint(checkpointName: String) {
        prefs.edit()
            .putString(checkpointPref, checkpointName.trim())
            .apply()
    }

    fun loadCheckpoint(): String {
        return prefs.getString(checkpointPref, "") ?: ""
    }

    fun saveDarkMode(enabled: Boolean) {
        prefs.edit()
            .putBoolean(darkModePref, enabled)
            .apply()
    }

    fun loadDarkMode(): Boolean {
        return prefs.getBoolean(darkModePref, false)
    }

    fun saveLocalModelHost(host: String) {
        prefs.edit()
            .putString(localModelHostPref, host.trim())
            .apply()
    }

    fun loadLocalModelHost(): String {
        return prefs.getString(localModelHostPref, "") ?: ""
    }

    fun saveComfyUiHost(host: String) {
        prefs.edit()
            .putString(comfyUiHostPref, host.trim())
            .apply()
    }

    fun loadComfyUiHost(): String {
        return prefs.getString(comfyUiHostPref, "") ?: ""
    }

    fun saveChatMessages(historyKey: String, messages: List<ChatMessage>) {
        val jsonMessages = JSONArray()
        messages.forEach { message ->
            jsonMessages.put(
                JSONObject()
                    .put("content", message.content)
                    .put("role", message.role.name)
                    .put("imageLocalPath", message.imageLocalPath)
                    .put("imageUrl", message.imageUrl)
                    .put("modelSource", message.modelSource)
                    .put("modelBadge", message.modelBadge)
                    .put("modelBadgeColor", message.modelBadgeColor)
            )
        }

        prefs.edit()
            .putString(historyKey, jsonMessages.toString())
            .apply()
    }

    fun loadChatMessages(historyKey: String, includeLegacyMessages: Boolean = false): List<ChatMessage> {
        val savedMessages = prefs.getString(historyKey, null)
        val legacyMessages = if (includeLegacyMessages) prefs.getString(chatMessagesPref, null) else null
        val rawMessages = savedMessages ?: legacyMessages ?: return emptyList()

        return runCatching {
            val jsonMessages = JSONArray(rawMessages)
            val messages = mutableListOf<ChatMessage>()
            for (index in 0 until jsonMessages.length()) {
                val jsonMessage = jsonMessages.getJSONObject(index)
                val role = MessageRole.valueOf(jsonMessage.getString("role"))
                messages.add(
                    ChatMessage(
                        content = jsonMessage.getString("content"),
                        role = role,
                        imageLocalPath = jsonMessage.optString("imageLocalPath").takeIf { it.isNotBlank() },
                        imageUrl = jsonMessage.optString("imageUrl").takeIf { it.isNotBlank() },
                        modelSource = jsonMessage.optString("modelSource").takeIf { it.isNotBlank() },
                        modelBadge = jsonMessage.optString("modelBadge").takeIf { it.isNotBlank() },
                        modelBadgeColor = if (jsonMessage.has("modelBadgeColor") && !jsonMessage.isNull("modelBadgeColor")) {
                            jsonMessage.optInt("modelBadgeColor")
                        } else {
                            null
                        }
                    )
                )
            }
            if (savedMessages == null && legacyMessages != null) {
                saveChatMessages(historyKey, messages)
                prefs.edit()
                    .remove(chatMessagesPref)
                    .apply()
            }
            messages
        }.getOrDefault(emptyList())
    }

    fun clearChatMessages(historyKey: String) {
        prefs.edit()
            .remove(historyKey)
            .apply()
    }
}
