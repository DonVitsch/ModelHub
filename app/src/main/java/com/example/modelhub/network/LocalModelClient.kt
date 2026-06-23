package com.example.modelhub.network

import com.example.modelhub.data.ChatMessage
import com.example.modelhub.data.MessageRole
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class LocalModelClient(private val baseUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()
    private val mediaType = "application/json".toMediaType()

    fun sendMessage(model: String, messages: List<ChatMessage>, callback: (String?, String?) -> Unit) {
        val requestUrl = buildChatUrl(baseUrl)

        val jsonMessages = JSONArray()
        messages.forEach { msg ->
            jsonMessages.put(JSONObject().apply {
                put("role", toOllamaRole(msg.role))
                put("content", msg.content)
            })
        }

        val requestJson = JSONObject().apply {
            put("model", model)
            put("messages", jsonMessages)
            put("stream", false)
        }

        val request = Request.Builder()
            .url(requestUrl)
            .post(requestJson.toString().toRequestBody(mediaType))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(null, "连接本地模型失败：${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val responseText = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    callback(null, "本地模型请求失败：${response.code}\n$responseText")
                    return
                }

                try {
                    val answer = JSONObject(responseText)
                        .getJSONObject("message")
                        .getString("content")

                    callback(answer, null)
                } catch (e: Exception) {
                    callback(null, "解析本地模型回复失败：${e.message}\n$responseText")
                }
            }
        })
    }

    fun fetchModels(callback: (List<String>, String?) -> Unit) {
        val requestUrl = buildTagsUrl(baseUrl)

        val request = Request.Builder()
            .url(requestUrl)
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(emptyList(), "读取本地模型列表失败：${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val responseText = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    callback(emptyList(), "读取本地模型列表失败：${response.code}\n$responseText")
                    return
                }

                try {
                    val modelsJson = JSONObject(responseText).getJSONArray("models")
                    val models = mutableListOf<String>()

                    for (i in 0 until modelsJson.length()) {
                        val modelName = modelsJson.getJSONObject(i).getString("name")
                        models.add(modelName)
                    }

                    callback(models, null)
                } catch (e: Exception) {
                    callback(emptyList(), "解析本地模型列表失败：${e.message}\n$responseText")
                }
            }
        })
    }

    private fun buildChatUrl(inputUrl: String): String {
        val cleanUrl = inputUrl.trim().trimEnd('/')
        return if (cleanUrl.endsWith("/api/chat")) {
            cleanUrl
        } else {
            "$cleanUrl/api/chat"
        }
    }

    private fun buildTagsUrl(inputUrl: String): String {
        val cleanUrl = inputUrl.trim().trimEnd('/')
        return if (cleanUrl.endsWith("/api/tags")) {
            cleanUrl
        } else {
            "$cleanUrl/api/tags"
        }
    }

    private fun toOllamaRole(role: MessageRole): String {
        return when (role) {
            MessageRole.USER -> "user"
            MessageRole.ASSISTANT -> "assistant"
            MessageRole.SYSTEM -> "system"
        }
    }
}
