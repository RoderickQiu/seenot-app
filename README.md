# SeeNot

**SeeNot** is an intent-aware screen time intervention app for Android. [中文版](README.zh-CN.md)

Tell SeeNot what you are trying to do, and it watches the current screen for this session to pull you back when you drift. It does not just block an entire app.

> "Let me open Reddit for 15 minutes, but only my programming subreddits."
> "I need Amazon for one replacement charger, not random shopping."
> "Open LinkedIn to reply to that recruiter, not scroll the feed."

---

## How It Works

1. **Say the task** — use plain language to describe what you want to do and what should be avoided.
2. **SeeNot parses it** — AI turns your intent into a rule for the app, allowed content, limits, and intervention level.
3. **Your session begins** — SeeNot watches the current app and screen through Android Accessibility Service.
4. **It steps in on drift** — SeeNot can remind you, go back, or return home when the current screen no longer matches your intent.
5. **You can correct it** — mark a false positive and add a short note so later judgments can use that feedback.

---

## Features

- **Natural-language intents** — type or speak what you mean, without building rule lists first.
- **AI screen analysis** — a vision model judges the current page, not just the app name.
- **Flexible interventions** — reminder, back action, or home return.
- **Time and content limits** — set limits for a session, a type of content, or daily use.
- **Controlled apps and presets** — choose which apps SeeNot should watch and reuse common intents.
- **SeeNot Plus and account support** — use managed SeeNot AI, keep account-backed state across signed-in devices, and receive update prompts.
- **Multilingual by design** — English and Chinese are supported now, with the parser designed for more languages.

---

## Getting Started

Download the APK from [GitHub Releases](https://github.com/RoderickQiu/seenot-app/releases), install it, and follow the in-app setup.

The first setup flow will guide you through:

1. **Overlay permission** for the floating input button and intervention UI.
2. **Notification permission** for reminders and status messages.
3. **Accessibility Service** so SeeNot can detect app and screen changes, take screenshots for analysis, and perform back/home actions when needed.
4. **Unrestricted background running** so Android is less likely to stop monitoring.
5. **AI setup** through [SeeNot Plus](https://seenot.site/#seenot-plus) or your own AI provider key.
6. **Controlled apps** so SeeNot knows where to watch.

Optional permissions add convenience:

- **Usage Stats** helps as a backup when Android misses an app-switch event.
- **Notification access** can improve context in some app flows.
- **Microphone** is only needed for voice input. Text input works without it.

SeeNot is best tested on a real Android device. Accessibility, overlays, and background behavior can differ on emulators.

---

## AI Options

SeeNot needs an AI model for intent parsing and screen analysis. You can choose either path:

- **[SeeNot Plus](https://seenot.site/#seenot-plus)** — SeeNot handles AI access and setup. This is the easiest path for long-term use and signed-in device continuity.
- **Bring your own key** — use Qwen/DashScope, GPT, Claude, Gemini, GLM, another OpenAI-compatible provider, or a custom endpoint.

If you bring your own key, create it from the provider console and paste it into SeeNot:

- OpenAI: [API keys](https://platform.openai.com/api-keys)
- Anthropic: [Console](https://console.anthropic.com/) and [getting started docs](https://docs.anthropic.com/en/api/getting-started)
- Gemini: [Google AI Studio keys](https://aistudio.google.com/app/apikey) and [API key guide](https://ai.google.dev/gemini-api/docs/api-key)
- Qwen/DashScope: [international guide](https://www.alibabacloud.com/help/en/model-studio/get-api-key) or [China guide](https://www.alibabacloud.com/help/zh/model-studio/get-api-key)
- GLM: [Z.ai keys](https://z.ai/manage-apikey/apikey-list) or [BigModel docs](https://docs.bigmodel.cn/)

Testing support may be available while the project is in preview. Contact `scrisqiu at hotmail.com` if you need help getting started.

---

## For Developers

```bash
git clone https://github.com/RoderickQiu/seenot-app.git
cd seenot-app
```

Open the project in Android Studio, or build from the command line:

```bash
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Requirements and useful settings:

- Java 17
- Android API 30+
- Version values are read from `app/version.properties`
- Optional Gradle properties: `SEENOT_BACKEND_API_BASE_URL` and `SEENOT_WEBSITE_BASE_URL`

---

## Project Structure

```text
seenot/
├── app/                    # Android application
│   └── src/main/java/com/seenot/app/
│       ├── account/        # Account, device, Plus, and version service calls
│       ├── ai/             # Intent parsing, screen analysis, STT, managed AI
│       ├── config/         # App configuration and provider settings
│       ├── data/           # Room database, repositories, built-in presets
│       ├── domain/         # Sessions, rules, and intervention behavior
│       ├── observability/  # Runtime diagnostics
│       ├── receiver/       # Android system event receivers
│       ├── service/        # Accessibility and foreground services
│       └── ui/             # Jetpack Compose screens and overlays
└── ai-debugger/            # CLI tool for AI prompt development
```

---

## Privacy

SeeNot is local-first. Android session history stays on your device unless you export or share it.

Account, device, Plus, billing, and update features use SeeNot services. AI requests are sent from the app to the provider you choose, or to the disclosed provider used by managed SeeNot AI. SeeNot does not proxy your prompts, screenshots, or audio through a hosted screen-analysis server.

The formal policy is on the website: [Privacy Policy](https://seenot.site/privacy/).

---

## License

Mozilla Public License 2.0 — see [LICENSE](LICENSE) for details.

You can use, modify, and distribute this software. If you modify MPL-licensed files, you must share those changes under the same license. You may combine this code with code under other licenses in a larger work.
