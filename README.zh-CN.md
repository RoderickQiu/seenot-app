# SeeNot

SeeNot 是一款 Android 屏幕注意力管理助手。

大多数屏幕时间工具按 App、网站、时间段或总时长来限制。SeeNot 管的是这次意图：先说这次要做什么，它会根据当前页面实时判断；你一偏，就提醒你、退回上一页，或直接回到桌面。

> “上淘宝是为了买手机壳。”
> “回个微信就出来，不刷朋友圈和公众号。”
> “我可以用 B 站 15 分钟，但只看编程教程。”

---

## 怎么用

1. **直接说出来** — 用自然语言说你想做什么、哪些内容不要碰。
2. **SeeNot 来解析** — AI 把你的意图转成 App、允许内容、限制和介入等级。
3. **开始会话** — SeeNot 通过 Android 无障碍服务识别当前 App 和页面状态。
4. **偏了就介入** — 判断跑偏后，SeeNot 可以提醒你、自动返回，或直接回桌面。
5. **判断错了可以纠正** — 标记误报、加一句备注，后续类似判断会参考这次反馈。

---

## 功能

- **自然语言意图** — 直接说人话，不用先配一堆规则。
- **理解当前页面** — 视觉模型判断眼前这页，不只是看 App 名字。
- **三级干预** — 通知提醒、自动返回、直接回桌面。
- **时间和内容限制** — 支持单次会话、单类内容和每日总量。
- **受控 App 和历史规则** — 选择要管理的 App，也可以复用过去用过的规则。
- **SeeNot Plus 和账户** — 可使用托管 SeeNot AI，并在登录设备之间同步账户状态。
- **多语言设计** — 目前支持中文和英文，解析能力按更多语言扩展。

---

## 开始使用

从 [GitHub Releases](https://github.com/RoderickQiu/seenot-app/releases) 下载 APK，或使用[蓝奏云快速下载](https://www.lanwp.com/b0pnkdfuh)。安装后按 App 内引导完成设置。

首次设置会带你完成：

1. **悬浮窗权限** — 显示悬浮输入按钮和干预界面。
2. **通知权限** — 发送提醒和状态提示。
3. **无障碍服务** — 检测 App 和页面变化，为屏幕分析截图，并在需要时执行返回或回桌面。
4. **后台运行不受限** — 减少 Android 在后台停止监测的概率。
5. **AI 方案** — 使用 [SeeNot Plus](https://seenot.site/zh/#seenot-plus)，或填入你自己的 AI 服务商 Key。
6. **受控 App** — 选择 SeeNot 要在哪些 App 中生效。

可选权限用于增强体验：

- **使用情况访问**：Android 漏掉 App 切换事件时作为备用判断。
- **通知读取**：在部分 App 流程中补充上下文。
- **麦克风**：只用于语音输入；不用语音时可以只打字。

建议在真机上测试。模拟器上的无障碍、悬浮窗和后台行为可能不完全一致。

---

## AI 方案

SeeNot 需要 AI 模型来解析意图和分析屏幕。你可以二选一：

- **[SeeNot Plus](https://seenot.site/zh/#seenot-plus)**：由 SeeNot 处理 AI 访问和设置，更适合长期使用，也支持登录设备之间同步账户状态。
- **自带 API Key**：使用 Qwen/DashScope、GPT、Claude、Gemini、GLM、其他 OpenAI 兼容服务商，或自定义端点。

如果选择自带 Key，可以在服务商控制台创建后填入 SeeNot：

- OpenAI：[API keys](https://platform.openai.com/api-keys)
- Anthropic：[Console](https://console.anthropic.com/) 和 [入门文档](https://docs.anthropic.com/en/api/getting-started)
- Gemini：[Google AI Studio keys](https://aistudio.google.com/app/apikey) 和 [API Key 说明](https://ai.google.dev/gemini-api/docs/api-key)
- Qwen/DashScope：阿里云百炼 [国内说明](https://www.alibabacloud.com/help/zh/model-studio/get-api-key) 或 [国际说明](https://www.alibabacloud.com/help/en/model-studio/get-api-key)
- GLM：智谱 [BigModel 文档](https://docs.bigmodel.cn/) 或 [Z.ai keys](https://z.ai/manage-apikey/apikey-list)

---

## 开发

```bash
git clone https://github.com/RoderickQiu/seenot-app.git
cd seenot-app
```

用 Android Studio 打开，或用命令行构建：

```bash
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

构建相关说明：

- Java 17
- Android API 30+
- 版本号来自 `app/version.properties`
- 可选 Gradle 属性：`SEENOT_BACKEND_API_BASE_URL` 和 `SEENOT_WEBSITE_BASE_URL`

---

## 项目结构

```text
seenot/
├── app/                    # Android 应用
│   └── src/main/java/com/seenot/app/
│       ├── account/        # 账户、设备、Plus、版本服务
│       ├── ai/             # 意图解析、屏幕分析、语音转文字、托管 AI
│       ├── config/         # App 配置和服务商设置
│       ├── data/           # Room 数据库、仓库、内置预设
│       ├── domain/         # 会话、规则和干预逻辑
│       ├── observability/  # 运行诊断
│       ├── receiver/       # Android 系统事件接收
│       ├── service/        # 无障碍服务和前台服务
│       └── ui/             # Jetpack Compose 页面和弹层
└── ai-debugger/            # AI 提示词调试工具（CLI）
```

---

## 隐私

SeeNot 是本地优先设计。Android 会话历史保存在你的设备上，除非你主动导出或分享。

账户、设备、Plus、支付和更新功能会使用 SeeNot 服务。AI 请求会从 App 直接发送给你选择的服务商；使用托管 SeeNot AI 时，也会直接发送给对应的 AI 服务商。SeeNot 不会通过自己的屏幕分析服务器中转你的提示词、截图或音频。

正式说明以网站为准：[隐私政策](https://seenot.site/zh/privacy/)。

---

## 许可证

Mozilla Public License 2.0 — 详见 [LICENSE](LICENSE)。

可以自由使用、修改、分发。修改了 MPL 授权的文件，需要以相同许可证开放修改内容。可以和其他许可证的代码组合使用。
