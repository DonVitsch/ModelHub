package com.example.modelhub.network

import android.content.Context
import android.os.Handler
import android.os.Looper
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
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class ComfyUIClient(private val baseUrl: String, private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .build()
    private val mediaType = "application/json".toMediaType()
    private val handler = Handler(Looper.getMainLooper())
    private val maxPollAttempts = 600
    private val pollDelayMs = 1000L

    private var currentModeKey: String? = null

    fun setCurrentModeKey(key: String?) {
        currentModeKey = key
    }

    fun isModeStillValid(modeKey: String): Boolean {
        return currentModeKey == modeKey
    }

    /**
     * 生成图片：构建 workflow → POST /prompt → 轮询 /history → 下载图片
     */
    fun generateImage(
        promptText: String,
        checkpointName: String,
        seed: Long = System.currentTimeMillis(),
        callback: (ChatMessage?, String?) -> Unit
    ) {
        val modeKey = currentModeKey ?: UUID.randomUUID().toString()

        if (checkpointName.isBlank()) {
            callback(null, "请输入模型文件名。")
            return
        }

        val cleanUrl = baseUrl.trim().trimEnd('/')
        val workflow = buildWorkflow(promptText, checkpointName, seed)

        val requestBody = JSONObject().apply {
            put("prompt", workflow)
        }

        val request = Request.Builder()
            .url("$cleanUrl/prompt")
            .post(requestBody.toString().toRequestBody(mediaType))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!isModeStillValid(modeKey)) return
                callback(null, "无法连接 ComfyUI 服务器：${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!isModeStillValid(modeKey)) return

                val responseText = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    callback(null, "ComfyUI 请求失败（${response.code}）：\n$responseText")
                    return
                }

                try {
                    val json = JSONObject(responseText)
                    val promptId = json.getString("prompt_id")
                    pollForResult(promptId, promptText, modeKey, callback)
                } catch (e: Exception) {
                    callback(null, "解析 ComfyUI 响应失败：${e.message}")
                }
            }
        })
    }

    private fun pollForResult(
        promptId: String,
        originalPrompt: String,
        modeKey: String,
        callback: (ChatMessage?, String?) -> Unit,
        attempt: Int = 0
    ) {
        if (!isModeStillValid(modeKey)) return

        if (attempt >= maxPollAttempts) {
            callback(null, "ComfyUI 生成超时（已等待 ${maxPollAttempts / 60} 分钟），请检查服务器状态。")
            return
        }

        val cleanUrl = baseUrl.trim().trimEnd('/')
        val request = Request.Builder()
            .url("$cleanUrl/history/$promptId")
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!isModeStillValid(modeKey)) return
                handler.postDelayed({
                    pollForResult(promptId, originalPrompt, modeKey, callback, attempt + 1)
                }, pollDelayMs)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!isModeStillValid(modeKey)) return

                val responseText = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    handler.postDelayed({
                        pollForResult(promptId, originalPrompt, modeKey, callback, attempt + 1)
                    }, pollDelayMs)
                    return
                }

                try {
                    val history = JSONObject(responseText)

                    if (!history.has(promptId)) {
                        // 还未完成，继续轮询
                        handler.postDelayed({
                            pollForResult(promptId, originalPrompt, modeKey, callback, attempt + 1)
                        }, pollDelayMs)
                        return
                    }

                    val promptData = history.getJSONObject(promptId)
                    val status = promptData.getJSONObject("status")
                    val statusStr = status.optString("status_str", "")
                    val completed = status.optBoolean("completed", false)

                    if (!completed || statusStr != "success") {
                        handler.postDelayed({
                            pollForResult(promptId, originalPrompt, modeKey, callback, attempt + 1)
                        }, pollDelayMs)
                        return
                    }

                    // 获取输出的图片
                    val outputs = promptData.optJSONObject("outputs")
                    if (outputs == null || outputs.length() == 0) {
                        callback(null, "ComfyUI 没有返回输出。")
                        return
                    }

                    // 遍历所有输出节点，找到第一个 SaveImage 的输出
                    var foundImage: JSONObject? = null
                    val keys = outputs.keys()
                    while (keys.hasNext()) {
                        val nodeId = keys.next()
                        val nodeOutput = outputs.getJSONObject(nodeId)
                        val images = nodeOutput.optJSONArray("images")
                        if (images != null && images.length() > 0) {
                            foundImage = images.getJSONObject(0)
                            break
                        }
                    }

                    val image = foundImage
                    if (image == null) {
                        callback(null, "ComfyUI 生成成功但未找到图片输出。")
                        return
                    }

                    val filename = image.getString("filename")
                    val subfolder = image.optString("subfolder", "")
                    val type = image.optString("type", "output")
                    val imageUrl = "$cleanUrl/view?filename=$filename&type=$type&subfolder=$subfolder"

                    // 下载图片
                    downloadImage(imageUrl, originalPrompt, modeKey, callback)

                } catch (e: Exception) {
                    callback(null, "解析 ComfyUI 生成结果失败：${e.message}")
                }
            }
        })
    }

    private fun downloadImage(
        imageUrl: String,
        originalPrompt: String,
        modeKey: String,
        callback: (ChatMessage?, String?) -> Unit
    ) {
        if (!isModeStillValid(modeKey)) return

        val request = Request.Builder()
            .url(imageUrl)
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!isModeStillValid(modeKey)) return
                callback(null, "下载图片失败：${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!isModeStillValid(modeKey)) return

                if (!response.isSuccessful) {
                    callback(null, "下载图片失败：HTTP ${response.code}")
                    return
                }

                try {
                    val bytes = response.body?.bytes()
                    if (bytes == null || bytes.isEmpty()) {
                        callback(null, "下载的图片为空。")
                        return
                    }

                    // 保存到应用缓存目录
                    val cacheDir = File(context.cacheDir, "comfyui_images")
                    if (!cacheDir.exists()) {
                        cacheDir.mkdirs()
                    }

                    val filename = imageUrl.substringAfter("filename=").substringBefore("&")
                    val imageFile = File(cacheDir, "${UUID.randomUUID()}_$filename")
                    FileOutputStream(imageFile).use { fos ->
                        fos.write(bytes)
                    }

                    callback(
                        ChatMessage(
                            content = originalPrompt,
                            role = MessageRole.ASSISTANT,
                            imageLocalPath = imageFile.absolutePath,
                            imageUrl = imageUrl
                        ),
                        null
                    )
                } catch (e: Exception) {
                    callback(null, "保存图片失败：${e.message}")
                }
            }
        })
    }

    /**
     * 构建一个基础的文生图 workflow
     */
    private fun buildWorkflow(prompt: String, checkpointName: String, seed: Long): JSONObject {
        val workflow = JSONObject()

        // Z-Image workflow from image_z_image.json.
        workflow.put("62", JSONObject().apply {
            put("inputs", JSONObject().apply {
                put("clip_name", "qwen_3_4b.safetensors")
                put("type", "lumina2")
                put("device", "default")
            })
            put("class_type", "CLIPLoader")
        })

        workflow.put("63", JSONObject().apply {
            put("inputs", JSONObject().apply {
                put("vae_name", "ae.safetensors")
            })
            put("class_type", "VAELoader")
        })

        workflow.put("66", JSONObject().apply {
            put("inputs", JSONObject().apply {
                put("unet_name", checkpointName)
                put("weight_dtype", "default")
            })
            put("class_type", "UNETLoader")
        })

        workflow.put("67", JSONObject().apply {
            put("inputs", JSONObject().apply {
                put("text", prompt)
                put("clip", JSONArray(listOf("62", 0)))
            })
            put("class_type", "CLIPTextEncode")
        })

        workflow.put("68", JSONObject().apply {
            put("inputs", JSONObject().apply {
                put("width", 512)
                put("height", 512)
                put("batch_size", 1)
            })
            put("class_type", "EmptySD3LatentImage")
        })

        workflow.put("70", JSONObject().apply {
            put("inputs", JSONObject().apply {
                put("model", JSONArray(listOf("66", 0)))
                put("shift", 3)
            })
            put("class_type", "ModelSamplingAuraFlow")
        })

        workflow.put("71", JSONObject().apply {
            put("inputs", JSONObject().apply {
                put("text", "")
                put("clip", JSONArray(listOf("62", 0)))
            })
            put("class_type", "CLIPTextEncode")
        })

        workflow.put("69", JSONObject().apply {
            put("inputs", JSONObject().apply {
                put("seed", seed)
                put("steps", 8)
                put("cfg", 3)
                put("sampler_name", "res_multistep")
                put("scheduler", "simple")
                put("denoise", 1)
                put("model", JSONArray(listOf("70", 0)))
                put("positive", JSONArray(listOf("67", 0)))
                put("negative", JSONArray(listOf("71", 0)))
                put("latent_image", JSONArray(listOf("68", 0)))
            })
            put("class_type", "KSampler")
        })

        workflow.put("65", JSONObject().apply {
            put("inputs", JSONObject().apply {
                put("samples", JSONArray(listOf("69", 0)))
                put("vae", JSONArray(listOf("63", 0)))
            })
            put("class_type", "VAEDecode")
        })

        workflow.put("9", JSONObject().apply {
            put("inputs", JSONObject().apply {
                put("images", JSONArray(listOf("65", 0)))
                put("filename_prefix", "z-image-base")
            })
            put("class_type", "SaveImage")
        })

        return workflow
    }
}
