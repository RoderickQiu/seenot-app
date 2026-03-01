# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

SeeNot Next is an Android app for AI-powered attention management. It shifts from V1's "pre-configured rules" model to a "conversational intent declaration" model - users declare their intent via voice when opening an app, and AI monitors screen content in real-time to enforce those intentions.

Key paradigm shift:
- **V1**: Manual rule configuration via UI forms
- **SeeNot Next**: Voice-based natural language intent declaration per session

## Tech Stack

| Component | Technology |
|-----------|------------|
| Language | Kotlin |
| UI | Jetpack Compose (Material 3) |
| Min SDK | 30 (Android 11) |
| Target SDK | 36 |
| AI Backend | DashScope API (Qwen3 VL Plus/Flash) |
| Data | Room (SQLite) |
| Network | Ktor / OkHttp |
| Async | Kotlin Coroutines |

## Development Commands

### Setup
```bash
# Install dependencies
./gradlew assembleDebug

# Run the app
./gradlew installDebug
```

### Building
```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Clean build
./gradlew clean
```

### Testing
```bash
# Run all unit tests
./gradlew test

# Run a specific test class
./gradlew test --tests "com.seenot.app.SomeTestClass"

# Run instrumented tests
./gradlew connectedAndroidTest
```

## Architecture

The app follows a layered architecture as defined in PRD.md:

```
User Interaction Layer
├── VoiceInputHUD (voice input overlay)
└── SessionStatusHUD (session status floating window)

Intent Processing Layer
├── STT Engine (voice to text)
├── IntentParser (LLM-based NLU)
└── SessionManager (session lifecycle, intent merging)

Perception & Execution Layer
├── ScreenAnalyzer (periodic screenshots + AI vision)
└── ActionExecutor (interventions: remind, auto-back, go-home)

Platform Service Layer
└── AccessibilityService (app switch detection, screenshots, gestures)
```

### Key Data Models

- **SessionIntent**: User's declared intent for a session (parsed from voice)
- **IntentConstraint**: Single constraint (ALLOW/DENY/TIME_CAP)
- **ActiveSession**: Runtime session state
- **ScreenAnalysisResult**: AI vision analysis result

## Core Modules

### Voice Input System
- MediaRecorder for audio capture
- DashScope STT (Paraformer) for speech-to-text
- Fallback to manual text input
- Max recording duration: 30 seconds
- Silence detection: 2 seconds triggers auto-stop
- Audio format: M4A (AAC), 16kHz, 128kbps

### Intent Parser
- Uses DashScope Qwen3 for NLU
- Converts natural language to structured SessionIntent JSON
- Supports intent stacking and modification
- No hardcoded classification - fully LLM-driven

### Screen Analyzer
- Periodic screenshots (5 second interval)
- Hash-based deduplication to reduce API calls
- Batch constraint checking (multiple constraints per single API call)
- Confidence-based thresholds with fallback strategies

### Intervention System
- Three levels: GENTLE, MODERATE, STRICT
- Continuous confirmation (2 consecutive violations for forced actions)
- Cooldown period after forced actions
- One-click override for false positives

## Coding Conventions

### API Design
- Use real APIs only - no mocks or stubs
- No legacy code - remove unused methods immediately
- All API integrations must work end-to-end

## Project Structure

Once initialized, the project should follow standard Android architecture:

```
app/
├── src/main/
│   ├── java/com/seenot/app/
│   │   ├── data/           # Room database, repositories
│   │   ├── domain/         # Use cases, business logic
│   │   ├── ui/             # Compose screens
│   │   ├── service/        # AccessibilityService
│   │   ├── overlay/        # Floating window implementations
│   │   └── ai/             # STT, IntentParser, ScreenAnalyzer
│   └── res/
├── build.gradle.kts
└── proguard-rules.pro
```

## Key Files

- `PRD.md` - Complete product requirements and technical design
- `TASKS.md` - Implementation task list organized by feature
