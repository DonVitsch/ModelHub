# 从 Android 连接 Windows ComfyUI：把 Z-Image 生图接入 ModelHub2

这次 ModelHub2 新增的第三个功能是 `Win 生图`。它的目标很直接：Android App 不在手机本地跑模型，而是通过局域网访问 Windows 电脑上的 ComfyUI，让 Windows 机器负责 Z-Image 生图，Android 只负责提交提示词、等待结果、下载并展示图片。

最终接入的 Windows ComfyUI 地址是：

```text
http://<YOUR_WINDOWS_LAN_IP>:8188
```

## 一、局域网是怎么连通的

Windows 上的 ComfyUI 默认常见地址是：

```text
http://127.0.0.1:8188
```

这个地址只允许 Windows 本机访问。要让 Mac 或 Android 访问，ComfyUI 必须以局域网模式启动，让它监听 Windows 的局域网 IP。当前 Windows 机器的可用地址是：

```text
<YOUR_WINDOWS_LAN_IP>
```

所以局域网访问地址就是：

```text
http://<YOUR_WINDOWS_LAN_IP>:8188
```

整个链路可以理解为：

```text
Android App
  -> 同一 Wi-Fi / 局域网
  -> Windows IP: <YOUR_WINDOWS_LAN_IP>
  -> ComfyUI 端口: 8188
```

在 Mac 上先做了三步验证。

第一步，确认网络层能互通：

```bash
ping <YOUR_WINDOWS_LAN_IP>
```

能持续收到返回，说明 Mac 能看到这台 Windows。

第二步，确认 ComfyUI 的端口开着：

```bash
nc -vz <YOUR_WINDOWS_LAN_IP> 8188
```

返回 `succeeded`，说明 8188 端口可以连接。

第三步，确认 ComfyUI Web/API 服务正常：

```bash
curl -I http://<YOUR_WINDOWS_LAN_IP>:8188
```

返回 `HTTP/1.1 200 OK`，说明这个地址不仅端口通，而且 ComfyUI 服务本身可用。

Android 侧还需要允许网络访问和 HTTP 明文请求。本项目已经在 `AndroidManifest.xml` 里配置：

```xml
<uses-permission android:name="android.permission.INTERNET" />

<application
    android:usesCleartextTraffic="true"
    ...>
```

如果后续 Windows IP 变化，只需要把 App 中的 `windowsImageBaseUrl` 改成新的地址。

## 二、Android 端如何接入 Windows ComfyUI

ModelHub2 里第三个模式叫：

```kotlin
ChatMode.WINDOWS_LOCAL_IMAGE
```

它对应底部按钮：

```text
Win 生图
```

在 `MainActivity.kt` 中，Windows ComfyUI 地址被固定为：

```kotlin
private val windowsImageBaseUrl = "http://<YOUR_WINDOWS_LAN_IP>:8188"
```

启动 Activity 时创建 ComfyUI 客户端：

```kotlin
comfyUiClient = ComfyUIClient(windowsImageBaseUrl, this)
```

用户切到 `Win 生图` 后输入提示词并点击发送，代码会进入：

```kotlin
ChatMode.WINDOWS_LOCAL_IMAGE -> sendComfyUIMessage(model, question)
```

这里的 `model` 是界面上的模型文件名，默认是：

```text
z_image_bf16.safetensors
```

`question` 是用户输入的生图提示词。

发送流程是：

1. 把提示词作为用户消息显示在聊天区。
2. 调用 `ComfyUIClient.generateImage()`。
3. 生成 ComfyUI API prompt。
4. POST 到 Windows ComfyUI 的 `/prompt`。
5. 从响应中拿到 `prompt_id`。
6. 轮询 `/history/{prompt_id}` 等待生成完成。
7. 找到输出图片信息。
8. 通过 `/view?filename=...` 下载图片。
9. 保存到 Android App cache。
10. 在聊天气泡中显示生成图。

也就是说，Android 没有直接打开 ComfyUI 网页，而是调用 ComfyUI 的 HTTP API，把生图体验做进了 App 自己的聊天界面。

## 三、Z-Image 工作流是怎样接进去的

这次使用的工作流来自：

```text
image_z_image.json
```

工作流对应的核心模型是：

```text
z_image_bf16.safetensors
```

模型下载地址：

```text
https://huggingface.co/Comfy-Org/z_image/resolve/main/split_files/diffusion_models/z_image_bf16.safetensors
```

Z-Image 不是普通的 `CheckpointLoaderSimple` 工作流。它需要拆成 ComfyUI API 能理解的一组节点，主要包括：

```text
UNETLoader
CLIPLoader
VAELoader
CLIPTextEncode
EmptySD3LatentImage
ModelSamplingAuraFlow
KSampler
VAEDecode
SaveImage
```

Windows ComfyUI 侧模型文件放置结构类似：

```text
ComfyUI/
└── models/
    ├── diffusion_models/
    │   └── z_image_bf16.safetensors
    ├── text_encoders/
    │   └── qwen_3_4b.safetensors
    └── vae/
        └── ae.safetensors
```

Android 发给 ComfyUI 的工作流里，三个加载节点分别是：

```text
UNETLoader  -> z_image_bf16.safetensors
CLIPLoader  -> qwen_3_4b.safetensors, type=lumina2
VAELoader   -> ae.safetensors
```

然后：

```text
CLIPTextEncode 编码正向提示词
CLIPTextEncode 编码空负向提示词
EmptySD3LatentImage 创建 latent
ModelSamplingAuraFlow 设置 Z-Image 采样模型
KSampler 执行采样
VAEDecode 解码图片
SaveImage 保存结果
```

为了先快速验证链路，目前参数故意调低：

```text
width: 512
height: 512
steps: 8
cfg: 3
sampler_name: res_multistep
scheduler: simple
denoise: 1
```

这样出图会更快，适合测试 App 到 ComfyUI 的完整闭环。等功能稳定后，可以把参数提高到更适合画质的范围：

```text
steps: 30-50
cfg: 3-5
```

## 四、为什么要把等待时间改长

一开始 ComfyUIClient 的轮询是：

```text
60 次 * 1 秒
```

也就是 60 秒后就判定超时。Z-Image 首次加载模型或者显存压力较大时，经常超过 60 秒，这会导致 Windows 还在生成，Android 已经提示超时。

后来改成：

```text
600 次 * 1 秒
```

也就是最多等待 10 分钟。

同时 OkHttp 的超时也放宽：

```text
readTimeout: 10 minutes
writeTimeout: 2 minutes
```

这样 Android 不会太早放弃请求，更符合本地大模型生图的实际耗时。

## 五、如何确认 Windows ComfyUI 已经准备好

可以通过 ComfyUI 的 `/object_info` 接口检查节点和模型是否可用。

检查 UNET：

```bash
curl http://<YOUR_WINDOWS_LAN_IP>:8188/object_info/UNETLoader
```

应该能看到：

```text
z_image_bf16.safetensors
```

检查 CLIP：

```bash
curl http://<YOUR_WINDOWS_LAN_IP>:8188/object_info/CLIPLoader
```

应该能看到：

```text
qwen_3_4b.safetensors
```

检查 VAE：

```bash
curl http://<YOUR_WINDOWS_LAN_IP>:8188/object_info/VAELoader
```

应该能看到：

```text
ae.safetensors
```

检查采样器：

```bash
curl http://<YOUR_WINDOWS_LAN_IP>:8188/object_info/KSampler
```

应该支持：

```text
res_multistep
```

这些都确认后，Android 端再提交 prompt，成功率会高很多。

## 六、这次接入改动了哪些代码

主要文件是：

```text
app/src/main/java/com/example/modelhub/MainActivity.kt
app/src/main/java/com/example/modelhub/network/ComfyUIClient.kt
app/src/main/java/com/example/modelhub/ui/ChatView.kt
app/src/main/java/com/example/modelhub/storage/SettingsStorage.kt
```

`MainActivity.kt` 负责模式切换、ComfyUIClient 创建和消息发送。

`ComfyUIClient.kt` 负责真正的 ComfyUI API 调用，包括：

```text
/prompt
/history/{prompt_id}
/view?filename=...
```

`ChatView.kt` 负责 Win 生图模式的界面，包括模型文件输入框、提示词输入框和图片展示。

`SettingsStorage.kt` 负责保存默认模型名和聊天历史。默认模型名已经改成：

```text
z_image_bf16.safetensors
```

旧版本保存过的：

```text
sd_xl_base_1.0.safetensors
v1-5-pruned-emaonly-fp16.safetensors
```

会自动迁移到：

```text
z_image_bf16.safetensors
```

## 七、最后的验证

每次修改后，用 Gradle 编译 Debug 包：

```bash
./gradlew assembleDebug
```

当前状态已经验证通过：

```text
BUILD SUCCESSFUL
```

至此，ModelHub2 的 `Win 生图` 模式已经完成了从 Android 到 Windows ComfyUI，再到 Z-Image 工作流的完整接入。Android 端只需要输入提示词，Windows 端负责模型推理，生成后的图片会回到 App 聊天界面中展示。
