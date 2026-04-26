# SeeNot

大多数屏幕时间管理工具靠的是规则和限制。SeeNot 不一样——它靠的是你自己的意图。

你告诉它你想做什么、不想做什么，它就盯着你的屏幕，帮你说到做到。

> "打开微博，但别让我刷热搜。"
> "我可以用 B 站 15 分钟——但只看编程教程。"
> "查一下微信，处理完工作消息就退出。"

跑偏了？SeeNot 会把你拉回来。

> **注：我们目前正在提供免费 API 供测试；请联系我，如通过 scrisqiu at hotmail.com 来获取。**

---

## 怎么用

1. **说出你的意图** — 点悬浮按钮，用自然语言说你想干嘛（中文、英文都行）。
2. **SeeNot 来解析** — 大模型把你说的话转成结构化规则：哪个 App、能做什么、不能做什么、用多久。
3. **开始监控** — SeeNot 通过无障碍服务在后台盯着你的屏幕。
4. **偏了就拉回来** — 一旦你进了不该进的地方，SeeNot 直接介入。
5. **判断错了可以纠正** — 标记误报、加个备注，SeeNot 下次遇到类似情况会参考你的反馈。

---

## 功能

- **语音输入** — 说人话，AI 搞定剩下的
- **AI 看屏幕** — 视觉模型理解屏幕内容，不只是看 App 名字
- **三级干预**：通知提醒 / 自动返回 / 直接回桌面
- **灵活限时** — 单次会话、单内容、或每日总量都能限
- **多语言** — 界面和意图解析支持中英文，可扩展到 20+ 种语言
- **数据不出手机** — 所有记录存在本地，不上传

---

## 开始使用

### 普通用户

在 [Releases](../../releases) 页面下载 APK，安装后按引导完成设置。

需要授权的几件事：

1. 无障碍服务权限 — SeeNot 靠这个知道你在用哪个 App、看什么内容。
2. 悬浮窗权限 — 用来显示悬浮按钮和干预弹层。
3. 填一个兼容 OpenAI 格式的视觉模型 API Key。推荐阿里云百炼的通义千问，便宜、快。
4. 语音转文字 API Key 可选，不填也能用文字输入。

建议在真机上测试，模拟器上无障碍和悬浮窗行为可能不一样。

### 去哪里获取 API Key

SeeNot 需要 AI API Key 才能工作。我们目前正在提供免费 API 供测试；请联系我，如通过 scrisqiu at hotmail.com 来获取。或者，你也可以注册自己的 AI Key：

注册 AI 服务商的账号，进入 API Key 页面，新建一个 key，然后粘贴到 SeeNot 里。

- OpenAI：在 OpenAI 的 API keys 页面创建：[https://platform.openai.com/api-keys](https://platform.openai.com/api-keys)
- Anthropic：登录 Anthropic Console，在 Account Settings / API Keys 里创建：[https://console.anthropic.com/](https://console.anthropic.com/)；文档：[https://docs.anthropic.com/en/api/getting-started](https://docs.anthropic.com/en/api/getting-started)
- Gemini：在 Google AI Studio 的 API Keys 页面创建和管理：[https://aistudio.google.com/app/apikey](https://aistudio.google.com/app/apikey)；文档：[https://ai.google.dev/gemini-api/docs/api-key](https://ai.google.dev/gemini-api/docs/api-key)
- Qwen（通义千问，**便宜**）：先开通阿里云百炼 / DashScope，再到 API-KEY 页面创建；官方说明：[https://help.aliyun.com/zh/dashscope/opening-service](https://help.aliyun.com/zh/dashscope/opening-service)
  - 如果你是中国学生，可以前往[这里](https://university.aliyun.com/buycenter/)申请免费的 Qwen API。
- GLM（智谱，**便宜**）：登录智谱开放平台，在 API Keys 页面创建；文档入口：[https://docs.bigmodel.cn/](https://docs.bigmodel.cn/)；示例说明：[https://docs.bigmodel.cn/cn/guide/develop/claude/introduction](https://docs.bigmodel.cn/cn/guide/develop/claude/introduction)

你也可以使用任何兼容 OpenAI 模式，且提供视觉大模型的 AI 服务商，或者自建 AI 服务。


### 开发者

```bash
git clone https://github.com/RoderickQiu/seenot-app.git
cd seenot-app
```

用 Android Studio 打开，跑在设备或模拟器上（API 30+）。

命令行构建：

```bash
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Fastlane（构建 + Metadata）

仓库已加入 Fastlane，用于统一 Android 发版构建流程，以及维护
IzzyOnDroid/F-Droid 风格的 metadata 文件。

先安装依赖：

```bash
bundle install --path vendor/bundle
```

常用命令：

```bash
# 构建 Debug APK
bundle exec fastlane android build_debug

# 校验当前 VERSION_CODE 对应的 metadata
bundle exec fastlane android metadata_check

# 构建已签名 Release APK + AAB
# 默认从 local.properties 读取签名参数。
bundle exec fastlane android release
```

---

## 项目结构

```
seenot/
├── app/                    # Android 应用
│   └── src/main/java/com/seenot/app/
│       ├── ai/             # 意图解析、屏幕分析、语音转文字
│       ├── domain/         # 会话管理、业务逻辑
│       ├── data/           # Room 数据库
│       ├── service/        # 无障碍服务和前台服务
│       └── ui/             # Jetpack Compose 页面和弹层
└── ai-debugger/            # AI 提示词调试工具（CLI）
```

---

## 隐私

SeeNot 没有后端服务器。截图只发给你自己配置的 AI 服务商，会话记录存在本地数据库，不会上传到任何地方。

---

## 许可证

Mozilla Public License 2.0 — 详见 [LICENSE](LICENSE)。

可以自由使用、修改、分发。修改了 MPL 授权的文件，需要以相同许可证开放修改内容。可以和其他许可证的代码组合使用。
