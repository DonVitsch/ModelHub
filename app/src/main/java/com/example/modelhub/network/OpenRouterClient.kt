package com.example.modelhub.network

import android.util.Log
import com.example.modelhub.data.ChatMessage
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.dnsoverhttps.DnsOverHttps
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class OpenRouterClient(private val apiKey: String) {
    private val mediaType = "application/json".toMediaType()

    data class PresetModel(
        val displayName: String,
        val modelId: String
    )

    companion object {
        private const val TAG = "ModelHubPerf"

        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .dns(OpenRouterDns.create())
                .connectionPool(ConnectionPool(6, 5, TimeUnit.MINUTES))
                .connectTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

    }

    fun fetchModels(callback: (List<PresetModel>, String?) -> Unit) {
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/models")
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()

        sharedClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val message = if (e is UnknownHostException) {
                    "无法解析 openrouter.ai，请检查当前网络/DNS，或稍后重试。"
                } else {
                    e.message ?: "获取模型列表失败"
                }
                callback(emptyList(), message)
            }

            override fun onResponse(call: Call, response: Response) {
                val responseText = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    callback(emptyList(), "Error: ${response.code}\n$responseText")
                    return
                }

                try {
                    val dataArray = JSONObject(responseText).getJSONArray("data")
                    val models = mutableListOf<PresetModel>()
                    for (index in 0 until dataArray.length()) {
                        val item = dataArray.getJSONObject(index)
                        val id = item.optString("id")
                        if (id.isBlank()) continue
                        val name = item.optString("name").takeIf { it.isNotBlank() } ?: id
                        models.add(PresetModel(name, id))
                    }
                    callback(models.sortedBy { it.displayName }, null)
                } catch (e: Exception) {
                    callback(emptyList(), "解析模型列表失败：${e.message}")
                }
            }
        })
    }

    fun warmUp() {
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/auth/key")
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()

        sharedClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = Unit
            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }

    fun sendMessage(model: String, messages: List<ChatMessage>, callback: (String?, String?) -> Unit) {
        val jsonMessages = JSONArray()
        messages.forEach { msg ->
            jsonMessages.put(JSONObject().apply {
                put("role", msg.role.name.lowercase())
                put("content", msg.content)
            })
        }

        val json = JSONObject().apply {
            put("model", model)
            put("messages", jsonMessages)
        }

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", "https://example.com")
            .addHeader("X-Title", "ModelHub")
            .post(json.toString().toRequestBody(mediaType))
            .build()

        val requestStartedAt = System.nanoTime()
        Log.d(TAG, "OpenRouter HTTP start model=$model requestChars=${json.toString().length}")

        sharedClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.d(TAG, "OpenRouter HTTP failure in ${elapsedMs(requestStartedAt)}ms: ${e.message}")
                val message = if (e is UnknownHostException) {
                    "无法解析 openrouter.ai，请检查当前网络/DNS，或稍后重试。原始错误：${e.message}"
                } else {
                    e.message
                }
                callback(null, message)
            }

            override fun onResponse(call: Call, response: Response) {
                val responseText = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    callback(null, "Error: ${response.code}\n$responseText")
                    return
                }

                try {
                    val answer = JSONObject(responseText)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                    callback(answer, null)
                } catch (e: Exception) {
                    callback(null, "Parsing error: ${e.message}")
                }
            }
        })
    }

    fun streamMessage(
        model: String,
        messages: List<ChatMessage>,
        onDelta: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        val jsonMessages = JSONArray()
        messages.forEach { msg ->
            jsonMessages.put(JSONObject().apply {
                put("role", msg.role.name.lowercase())
                put("content", msg.content)
            })
        }

        val json = JSONObject().apply {
            put("model", model)
            put("messages", jsonMessages)
            put("stream", true)
            put("temperature", 0.3)
            put("max_tokens", 600)
            put("reasoning", JSONObject().apply {
                put("effort", "low")
                put("exclude", true)
            })
            put("provider", JSONObject().apply {
                put("sort", "throughput")
                put("allow_fallbacks", true)
            })
        }

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .addHeader("HTTP-Referer", "https://example.com")
            .addHeader("X-Title", "ModelHub")
            .post(json.toString().toRequestBody(mediaType))
            .build()

        val requestStartedAt = System.nanoTime()
        Log.d(TAG, "OpenRouter HTTP start model=$model requestChars=${json.toString().length}")

        sharedClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.d(TAG, "OpenRouter HTTP failure in ${elapsedMs(requestStartedAt)}ms: ${e.message}")
                val message = if (e is UnknownHostException) {
                    "无法解析 openrouter.ai，请检查当前网络/DNS，或稍后重试。原始错误：${e.message}"
                } else {
                    e.message ?: "OpenRouter 请求失败"
                }
                onError(message)
            }

            override fun onResponse(call: Call, response: Response) {
                Log.d(TAG, "OpenRouter response headers in ${elapsedMs(requestStartedAt)}ms code=${response.code}")
                if (!response.isSuccessful) {
                    val responseText = response.body?.string() ?: ""
                    onError("Error: ${response.code}\n$responseText")
                    return
                }

                val source = response.body?.source()
                if (source == null) {
                    onError("OpenRouter 没有返回响应内容。")
                    return
                }

                try {
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: continue
                        if (!line.startsWith("data:")) {
                            continue
                        }

                        val payload = line.removePrefix("data:").trim()
                        if (payload == "[DONE]") {
                            Log.d(TAG, "OpenRouter stream done in ${elapsedMs(requestStartedAt)}ms")
                            onComplete()
                            return
                        }

                        val delta = JSONObject(payload)
                            .optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("delta")
                            ?.optString("content")
                            .orEmpty()

                        if (delta.isNotEmpty()) {
                            onDelta(delta)
                        }
                    }
                    onComplete()
                } catch (e: Exception) {
                    Log.d(TAG, "OpenRouter stream parse failure in ${elapsedMs(requestStartedAt)}ms: ${e.message}")
                    onError("解析流式回复失败：${e.message}")
                }
            }
        })
    }

    private fun elapsedMs(startedAt: Long): Long {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
    }

    private class OpenRouterDns(
        private val fallbacks: List<Dns>
    ) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            Dns.SYSTEM.lookupOrNull(hostname)?.takeIf { it.isNotEmpty() }?.let { return it }

            fallbacks.forEach { dns ->
                dns.lookupOrNull(hostname)?.takeIf { it.isNotEmpty() }?.let { return it }
            }

            throw UnknownHostException(hostname)
        }

        private fun Dns.lookupOrNull(hostname: String): List<InetAddress>? {
            return try {
                lookup(hostname)
            } catch (_: UnknownHostException) {
                null
            }
        }

        companion object {
            fun create(): Dns {
                val dnsClient = OkHttpClient.Builder()
                    .retryOnConnectionFailure(true)
                    .build()

                val dnsPod = DnsOverHttps.Builder()
                    .client(dnsClient)
                    .url("https://doh.pub/dns-query".toHttpUrl())
                    .bootstrapDnsHosts(
                        InetAddress.getByAddress("doh.pub", byteArrayOf(1, 12, 12, 12)),
                        InetAddress.getByAddress("doh.pub", byteArrayOf(120, 53, 53, 53))
                    )
                    .build()

                val aliDns = DnsOverHttps.Builder()
                    .client(dnsClient)
                    .url("https://dns.alidns.com/dns-query".toHttpUrl())
                    .bootstrapDnsHosts(
                        InetAddress.getByAddress("dns.alidns.com", byteArrayOf(223.toByte(), 5, 5, 5)),
                        InetAddress.getByAddress("dns.alidns.com", byteArrayOf(223.toByte(), 6, 6, 6))
                    )
                    .build()

                val cloudflare = DnsOverHttps.Builder()
                    .client(dnsClient)
                    .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
                    .bootstrapDnsHosts(
                        InetAddress.getByAddress("cloudflare-dns.com", byteArrayOf(1, 1, 1, 1)),
                        InetAddress.getByAddress("cloudflare-dns.com", byteArrayOf(1, 0, 0, 1))
                    )
                    .build()

                return OpenRouterDns(listOf(dnsPod, aliDns, cloudflare))
            }
        }
    }
}
