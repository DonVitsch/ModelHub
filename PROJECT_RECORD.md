# ModelHub2 项目记录

## 1. Mac / Android 如何通过局域网连接 Windows ComfyUI

本项目的 Win 生图功能连接的是 Windows 电脑上运行的 ComfyUI LAN 服务：

```text
http://<YOUR_WINDOWS_LAN_IP>:8188
```

连接链路是：

```text
Android App -> 局域网/Wi-Fi -> Windows IP <YOUR_WINDOWS_LAN_IP> -> ComfyUI 8188 端口
```

Windows 端需要让 ComfyUI 监听局域网地址，而不是只监听 `127.0.0.1`。当前 Windows 启动脚本/命令行显示 LAN 地址后，Mac 侧可以通过下面方式验证：

```bash
ping <YOUR_WINDOWS_LAN_IP>
nc -vz <YOUR_WINDOWS_LAN_IP> 8188
curl -I http://<YOUR_WINDOWS_LAN_IP>:8188
```

验证结果：

- `ping <YOUR_WINDOWS_LAN_IP>` 有响应，说明 Mac 和 Windows 在网络层互通。
- `nc -vz <YOUR_WINDOWS_LAN_IP> 8188` 显示 succeeded，说明 ComfyUI 端口可访问。
- `curl -I http://<YOUR_WINDOWS_LAN_IP>:8188` 返回 `HTTP/1.1 200 OK`，说明 ComfyUI Web/API 服务在线。

Android 端允许 HTTP 明文访问，本项目已在 `AndroidManifest.xml` 中配置：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<application
    android:usesCleartextTraffic="true"
    ...>
```

因此 App 可以直接请求 `http://<YOUR_WINDOWS_LAN_IP>:8188`。

注意事项：

- Windows 和 Android 设备需要在同一个局域网，或网络之间允许访问。
- Windows 防火墙需要允许 8188 端口入站。
- 如果 Windows IP 变化，需要同步修改 App 中的 `windowsImageBaseUrl`。

## 2. 如何把 Windows 的 Z-Image ComfyUI 接入 Android

Win 生图模式在 Android 端对应：

```kotlin
ChatMode.WINDOWS_LOCAL_IMAGE
```

入口文件：

```text
app/src/main/java/com/example/modelhub/MainActivity.kt
app/src/main/java/com/example/modelhub/network/ComfyUIClient.kt
app/src/main/java/com/example/modelhub/ui/ChatView.kt
app/src/main/java/com/example/modelhub/storage/SettingsStorage.kt
```

### 连接地址

在 `MainActivity.kt` 中固定 Windows ComfyUI 地址：

```kotlin
private val windowsImageBaseUrl = "http://<YOUR_WINDOWS_LAN_IP>:8188"
```

启动时创建 ComfyUI 客户端：

```kotlin
comfyUiClient = ComfyUIClient(windowsImageBaseUrl, this)
```

当用户切换到 `Win 生图` 并发送提示词时，代码走：

```kotlin
ChatMode.WINDOWS_LOCAL_IMAGE -> sendComfyUIMessage(model, question)
```

`sendComfyUIMessage()` 会：

1. 把用户提示词加入聊天记录。
2. 调用 `ComfyUIClient.generateImage()`。
3. 向 ComfyUI 提交 `/prompt`。
4. 轮询 `/history/{prompt_id}` 等待生成结果。
5. 通过 `/view?filename=...` 下载图片。
6. 把图片保存到 App cache 并显示在聊天气泡中。

### Z-Image 模型和工作流

用户提供的工作流文件：

```text
/Users/don/Downloads/image_z_image.json
```

Z-Image 模型文件：

```text
z_image_bf16.safetensors
```

对应 Hugging Face 地址：

```text
https://huggingface.co/Comfy-Org/z_image/resolve/main/split_files/diffusion_models/z_image_bf16.safetensors
```

ComfyUI 侧模型存放位置应类似：

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

Android 端生成的 ComfyUI API prompt 使用这些节点：

- `UNETLoader`: 加载 `z_image_bf16.safetensors`
- `CLIPLoader`: 加载 `qwen_3_4b.safetensors`，类型为 `lumina2`
- `VAELoader`: 加载 `ae.safetensors`
- `CLIPTextEncode`: 正向/负向提示词编码
- `EmptySD3LatentImage`: 创建 latent
- `ModelSamplingAuraFlow`: Z-Image 采样模型设置
- `KSampler`: 采样
- `VAEDecode`: 解码图片
- `SaveImage`: 保存输出图片

### 当前测试参数

为了方便快速测试，当前参数调低为：

```text
width: 512
height: 512
steps: 8
cfg: 3
sampler_name: res_multistep
scheduler: simple
denoise: 1
```

生产或追求画质时可以提高：

```text
steps: 30-50
cfg: 3-5
```

### 默认模型名

`SettingsStorage.kt` 中默认 checkpoint 已改为：

```kotlin
z_image_bf16.safetensors
```

如果旧版本 App 曾保存过：

```text
sd_xl_base_1.0.safetensors
v1-5-pruned-emaonly-fp16.safetensors
```

会自动迁移为：

```text
z_image_bf16.safetensors
```

### 等待超时

Z-Image 生成可能超过 60 秒，所以 `ComfyUIClient.kt` 中轮询等待已放宽：

```text
maxPollAttempts: 600
pollDelayMs: 1000
```

也就是最多等待约 10 分钟。

HTTP 超时也已调整：

```text
readTimeout: 10 minutes
writeTimeout: 2 minutes
```

### ComfyUI 端验证命令

可以用这些命令确认 Windows ComfyUI 已具备 Z-Image 必需节点和模型：

```bash
curl http://<YOUR_WINDOWS_LAN_IP>:8188/object_info/UNETLoader
curl http://<YOUR_WINDOWS_LAN_IP>:8188/object_info/CLIPLoader
curl http://<YOUR_WINDOWS_LAN_IP>:8188/object_info/VAELoader
curl http://<YOUR_WINDOWS_LAN_IP>:8188/object_info/KSampler
```

已验证到：

```text
UNETLoader: z_image_bf16.safetensors
CLIPLoader: qwen_3_4b.safetensors
VAELoader: ae.safetensors
KSampler: res_multistep
```

### 构建验证

每次修改后用下面命令验证 Android 工程可编译：

```bash
./gradlew assembleDebug
```

当前状态：`BUILD SUCCESSFUL`。
