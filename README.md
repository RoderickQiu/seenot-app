# SeeNot

**SeeNot** is the mobile screen time management assistant that you never saw before. [中文版](README.zh-CN.md)

You tell it what you want to do — and what you want to avoid — and it watches your screen to keep you honest.

> "Open Instagram, but don't let me scroll Reels."
> "I can use Reddit for 15 minutes — but only my programming subreddits."
> "Let me check YouTube only for learning purposes."

SeeNot listens, understands, and intervenes if you drift dynamically.

---

## How It Works

1. **Speak your intention** — tap the floating button and say what you want to do in natural language (English, Chinese, or other languages).
2. **SeeNot parses it** — an LLM converts your words into a structured rule: which app, what's allowed, what's off-limits, and for how long.
3. **Your session begins** — SeeNot monitors your screen in the background using Android's Accessibility Service.
4. **It intervenes when needed** — if you wander into territory you said you'd avoid, or isn't related to your intent, SeeNot nudges you back with enforced actions.
5. **You can correct it** — if SeeNot gets a screen wrong, mark it as a false positive and add a short note. SeeNot uses that correction in later judgments for similar screens.

---

## Features

- **Voice-first intent input** — speak naturally; the AI figures out the rest
- **AI screen analysis** — vision model reads your screen to detect violations in context, not just by app name
- **Multiple enforcement levels**: Reminder notification / automatic navigation back / home return
- **Flexible time limits** — per-session caps, per-content caps, or daily totals
- **Multilingual** — UI and intent parsing support English and Chinese; designed to extend to 20+ languages
- **Local-first** — all session data stays on your device

---

## Getting Started

### For regular users

Pre-built APKs are available on the [Releases](../../releases) page. Download and install it, then follow the in-app setup.

You'll need to:

1. Grant Accessibility Service permission so SeeNot can detect the current app and screen state.
2. Grant "Display over other apps" permission so SeeNot can show the floating button and intervention overlays.
3. Enter an OpenAI-compatible vision model API key. DashScope Qwen is recommended for lower cost and latency.
4. Optionally enter a speech-to-text API key if you want voice input. Text input works without STT.

SeeNot is best tested on a real Android device. Some accessibility and overlay behaviors may differ on emulators.

### For developers

```bash
git clone https://github.com/RoderickQiu/seenot-app.git
cd seenot-app
```

Then open the project in Android Studio and run it on a device or emulator (API 30+).

Or build from the command line:

```bash
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Project Structure

```
seenot/
├── app/                    # Android application
│   └── src/main/java/com/seenot/app/
│       ├── ai/             # LLM intent parsing, screen analysis, STT
│       ├── domain/         # Session management, business logic
│       ├── data/           # Room database, repositories
│       ├── service/        # Accessibility & foreground services
│       └── ui/             # Jetpack Compose screens and overlays
└── ai-debugger/            # CLI tool for AI prompt development
```

---

## Privacy

SeeNot has no hosted backend. Screenshots taken for screen analysis are sent only to the AI provider you configure, and session history stays on your device in a local database.

---

## License

Mozilla Public License 2.0 — see [LICENSE](LICENSE) for details.

You can use, modify, and distribute this software. If you modify MPL-licensed files, you must share those changes under the same license. You may combine this code with code under other licenses in a larger work.
