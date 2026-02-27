# SeeNot Next - 需求与技术文档

> 本文档旨在为一个全新的、可从零开始由 AI 自治实现的版本提供完整的需求与技术指引,以与当前版本 (V1) 做效果对比。

---

## 一、SeeNot V1 核心思想与技术要素总结

### 1.1 核心哲学

SeeNot V1 的核心主张是:**分心不是 App 级别的问题,而是屏幕级别的、依赖于意图的上下文漂移**。用户带着目的打开 App,却滑入了注意力优化的信息流。传统的屏幕时间工具(如 iOS Screen Time)在 App 边界上做限制,粒度太粗 - 封锁整个 App 会破坏正常工作流,放行整个 App 则让成瘾页面畅通无阻。

V1 的解法是**人-AI协作的注意力调节模型**:
- **人类**负责定义高层意图边界(什么算分心页面)
- **AI**(视觉大模型)负责实时感知当前屏幕内容是否越界
- **系统**负责渐进式干预 - 从温和提醒到可逆自动化操作

### 1.2 技术架构概览

| 层级 | 技术选型 |
|------|----------|
| 语言 | Kotlin |
| UI | Jetpack Compose (Material 3) |
| 最低 SDK | 30 (Android 11) |
| AI 后端 | DashScope API (Qwen3 VL Plus/Flash), OpenAI-compatible |
| 数据持久化 | SharedPreferences + Gson JSON |
| 核心引擎 | Accessibility Service |
| 异步框架 | Kotlin Coroutines |

### 1.3 核心功能清单

| 功能 | 描述 |
|------|------|
| **AI 视觉分类** | 每 5 秒截图,通过视觉大模型判断当前页面是否匹配用户定义的条件(ON_PAGE), 基于置信度阈值(94%)做决策 |
| **规则系统** | 每个被监控 App 可挂载多条规则,每条规则 = 条件(Condition) + 动作(Action) + 可选时间约束(TimeConstraint) |
| **条件类型** | `TIME_INTERVAL`(每 X 分钟触发), `ON_ENTER`(进入 App 时), `ON_PAGE`(在特定页面时, AI 驱动) |
| **动作类型** | `REMIND`(Toast 提醒), `AUTO_BACK`(自动返回), `AUTO_CLICK`(自动点击坐标), `AUTO_SCROLL_UP/DOWN`(自动滚动), `ASK`(弹出管理面板) |
| **时间约束** | `Continuous`(连续匹配 X 分钟), `DailyTotal`(当日累计), `RecentTotal`(最近 Y 小时内累计) |
| **衰减机制** | 亚线性衰减(离开匹配状态时)、指数衰减(动作执行后),降低噪声检测的脆性 |
| **悬浮控制** | MonitoringIndicatorOverlay(悬浮监控指示器)、AskOverlay(规则管理面板)、CoordPickOverlay(坐标选取)、ToastOverlay |
| **内容标签** | AI 自动标注屏幕内容类别,归一化标签系统 |
| **记录与反思** | 完整记录 AI 每次判定(截图、置信度、耗时),可按日/小时浏览、标记、导出 |
| **导入/导出** | 规则 JSON 导出分享,日志 ZIP 导出 |
| **双语支持** | 中英文完整本地化 |

### 1.4 核心数据模型 (V1)

```kotlin
data class MonitoringApp(
    val id: String,
    val name: String,
    val isEnabled: Boolean,
    val askOnEnter: Boolean,
    val rules: List<Rule>
)

data class Rule(
    val id: String,
    val condition: RuleCondition,
    val action: RuleAction,
    val timeConstraint: TimeConstraint?
)

data class RuleCondition(
    val type: ConditionType,       // TIME_INTERVAL, ON_ENTER, ON_PAGE
    val timeInterval: Int?,         // 分钟数
    val parameter: String?          // ON_PAGE 的自然语言描述
)

data class RuleAction(
    val type: ActionType,           // REMIND, AUTO_CLICK, AUTO_SCROLL_*, AUTO_BACK, ASK
    val parameter: String?          // 坐标或消息文本
)

sealed class TimeConstraint {
    data class Continuous(val minutes: Double)
    data class DailyTotal(val minutes: Double)
    data class RecentTotal(val hours: Int, val minutes: Double)
}
```

### 1.5 V1 面临的挑战

1. **海外用户适配问题**: 海外用户的 App 生态不像中国那样"大而全" - 单个 App 往往功能更聚焦,V1 的"App 内页面级管控"的核心价值主张需要重新评估在这类场景下的说服力。
2. **AI-Native 不足**: 当前的 AI 应用仅限于视觉分类,用户仍需通过传统 UI 手动配置规则,缺乏真正 AI-Native 的交互范式。对于一个想要引爆市场的新品,需要更强的 AI-Native 体感。
3. **规则配置门槛**: 用户需要理解条件类型、动作类型、时间约束的组合逻辑,对普通用户有学习成本。

---

## 二、SeeNot Next 新版本 - 需求定义

### 2.1 核心理念转变

SeeNot Next 的核心主张: **每次打开 App 时,用语音声明本次意图,系统实时守护你的承诺。**

这是从 V1 的"预配置规则库"模式到 SeeNot Next 的"**会话式意图声明**"模式的范式转换:

| 维度 | V1 | SeeNot Next |
|------|----|----|
| 规则创建方式 | 手动配置 (UI 表单) | 语音自然语言声明 |
| 规则生命周期 | 持久化,长期生效 | **会话级** - 本次打开 App 生效,可叠加/修改 |
| AI 角色 | 视觉分类器(被动) | **意图解析器 + 视觉分类器 + 实时守护者**(主动) |
| 用户门槛 | 需理解规则结构 | 自然语言,零学习成本 |
| 交互频率 | 一次配置,长期使用 | **每次使用时交互**,高频、轻量 |
| 反馈形式 | 规则触发后干预 | **实时悬浮窗计时 + 干预**,持续可见 |

### 2.2 用户场景 (User Stories)

#### 场景 1: 基本意图声明
> 用户打开微信,弹出语音框,说:"这次只看工作消息,不刷朋友圈。"
> 系统解析为:允许聊天界面,禁止朋友圈页面。
> 进入微信后,悬浮窗显示"本次会话: 不看朋友圈",若用户滑入朋友圈,触发干预。

#### 场景 2: 带时间约束的意图
> 用户打开抖音,说:"只刷 5 分钟。"
> 悬浮窗实时倒计时:"剩余 4:32"
> 5 分钟到,触发提醒或自动返回桌面。

#### 场景 3: 复合意图
> 用户打开小红书,说:"我可以看穿搭推荐,但不能看美食内容,总共 10 分钟。"
> 系统解析为:允许穿搭类内容(ON_PAGE 允许),禁止美食类内容(ON_PAGE 禁止),全局 10 分钟限制。
> 悬浮窗显示:"穿搭 OK / 美食 X | 剩余 9:15"

#### 场景 4: 意图叠加与修改
> 用户正在刷微博(已声明"只看热搜,10 分钟"),3 分钟后说:"再加 5 分钟。"
> 系统更新时间约束为 15 分钟,悬浮窗更新。
> 或者说:"现在也可以看关注的人的动态。"
> 系统叠加新的允许规则。

#### 场景 5: 只声明限制(仅禁止)
> 用户打开 Instagram,说:"Don't let me go to Reels."
> 系统仅监控 Reels 页面,其余不干涉。

#### 场景 6: 只声明用途(仅允许)
> 用户打开 YouTube,说:"I only want to watch coding tutorials."
> 系统监控非编程教程内容,触发干预。

#### 场景 7: 无意图 - 跳过
> 用户打开 App,直接按跳过 / 不说话,系统不介入。

#### 场景 8: 继续使用上次意图
> 用户再次打开同一个 App,系统弹出一个轻量选择: "继续上次意图" 或 "新建意图"。
> 用户点"继续上次意图",悬浮窗直接恢复上次会话的限制与计时策略,无需再次口述。

### 2.3 功能需求 (Functional Requirements)

#### FR-0: 触发策略与受控应用选择

| ID | 需求 | 优先级 |
|----|------|--------|
| FR-0.1 | 仅对用户在 SeeNot 内手动选择的 App 触发 VoiceInputOverlay 和 SessionStatusHUD | P0 |
| FR-0.2 | 提供受控应用选择 UI(列表搜索,勾选启用/停用),默认不启用任何 App | P0 |
| FR-0.3 | 受控应用选择支持快捷操作: 全选、全不选、仅高风险 App 推荐(可选) | P1 |

#### FR-1: 语音意图输入系统

| ID | 需求 | 优先级 |
|----|------|--------|
| FR-1.1 | 当用户进入受控应用时,自动弹出语音输入悬浮窗 | P0 |
| FR-1.2 | 支持语音识别 (STT) 将用户语音转为文本 | P0 |
| FR-1.3 | 支持手动文本输入作为语音的备选 | P1 |
| FR-1.4 | 支持中英文语音识别 | P0 |
| FR-1.5 | 提供"跳过"按钮,用户可选择不声明意图 | P0 |
| FR-1.6 | 语音输入悬浮窗在 3 秒无语音后自动结束录音 | P1 |
| FR-1.7 | 在语音输入悬浮窗中,为有历史意图的 App 提供"继续上次意图"快捷入口 | P0 |

#### FR-2: 意图解析引擎 (NLU -> 结构化 JSON)

| ID | 需求 | 优先级 |
|----|------|--------|
| FR-2.1 | 将自然语言意图转换为预定义的 JSON Schema | P0 |
| FR-2.2 | 支持解析以下意图类型: 允许内容、禁止内容、时间限制、功能限制 | P0 |
| FR-2.3 | 支持单次语音中包含多个意图的解析 | P0 |
| FR-2.4 | 支持意图叠加 - 新意图与现有意图合并 | P0 |
| FR-2.5 | 支持意图修改 - 用户可覆盖之前的声明 | P0 |
| FR-2.6 | 解析失败时,向用户确认理解是否正确 | P1 |
| FR-2.7 | 不依赖任何人工预设的分类体系,完全由 LLM 理解自然语言语义 | P0 |

#### FR-3: 实时悬浮窗 (Session HUD)

| ID | 需求 | 优先级 |
|----|------|--------|
| FR-3.1 | 进入受控应用且会话已创建后,悬浮窗常驻显示当前会话规则摘要 | P0 |
| FR-3.2 | 如有时间限制,实时倒计时显示 | P0 |
| FR-3.3 | 悬浮窗可拖动、可最小化 | P1 |
| FR-3.4 | 点击悬浮窗可展开,显示完整规则列表,支持单条暂停/修改 | P1 |
| FR-3.5 | 悬浮窗支持快捷语音按钮,可随时追加/修改意图 | P0 |
| FR-3.6 | 当 AI 检测到违规内容时,悬浮窗高亮警告 | P0 |
| FR-3.7 | 悬浮窗的视觉威慑效果: 显示已用时间/剩余时间,颜色随剩余时间变化(绿->黄->红) | P0 |

#### FR-4: AI 视觉守护 (继承自 V1 并增强)

| ID | 需求 | 优先级 |
|----|------|--------|
| FR-4.1 | 周期性截图并通过视觉大模型分析当前屏幕内容 | P0 |
| FR-4.2 | 将截图内容与当前会话的意图规则做匹配 | P0 |
| FR-4.3 | 支持"允许型"规则: 只有匹配的内容才允许,其余触发干预 | P0 |
| FR-4.4 | 支持"禁止型"规则: 仅匹配的内容触发干预,其余放行 | P0 |
| FR-4.5 | 截图去重机制(哈希对比),避免重复 AI 调用 | P1 |
| FR-4.6 | 基于置信度阈值的判定,减少误判 | P0 |
| FR-4.7 | 置信度分段与降级: 低置信度仅提示,中置信度温和干预,高置信度才允许强制动作 | P0 |
| FR-4.8 | 连续确认策略: 需要连续 N 次(如 2 次)判定违规才执行强制动作,降低误判伤害 | P0 |

#### FR-5: 干预动作系统

| ID | 需求 | 优先级 |
|----|------|--------|
| FR-5.1 | 提醒: Toast / 悬浮窗高亮 | P0 |
| FR-5.2 | 自动返回: 执行系统返回键 | P0 |
| FR-5.3 | 时间到自动返回桌面 | P0 |
| FR-5.4 | 干预强度可在意图中声明(如"提醒我就行" vs "直接给我退出去") | P1 |
| FR-5.5 | 动作去重,防止循环触发 | P0 |
| FR-5.6 | 误判容错优先: 在不确定时优先提醒,并提供"这次放行"的即时撤销入口 | P1 |

#### FR-6: 会话管理

| ID | 需求 | 优先级 |
|----|------|--------|
| FR-6.1 | 每次进入 App 为一个新"会话" | P0 |
| FR-6.2 | 离开 App 时会话自动结束(或挂起,短时间内返回可恢复) | P0 |
| FR-6.3 | 会话内意图可随时追加、修改 | P0 |
| FR-6.4 | 会话历史记录(可选),用于复盘 | P2 |
| FR-6.5 | 支持"继续使用上次同 App 的意图声明"(可一键复用,无需再次口述) | P0 |
| FR-6.6 | 支持复用策略配置: 每次询问、默认复用、默认新建 | P1 |

#### FR-7: 零预配置原则

| ID | 需求 | 优先级 |
|----|------|--------|
| FR-7.1 | 用户不需要预先为任何 App 配置规则 | P0 |
| FR-7.2 | 对已选择为受控应用的 App,打开时都可以即时声明意图 | P0 |
| FR-7.3 | 系统不内置任何 App 的功能分类或页面映射 | P0 |
| FR-7.4 | 用户可选择为特定 App 设置默认意图(可选的持久化) | P2 |

### 2.4 非功能需求

| ID | 需求 | 指标 |
|----|------|------|
| NFR-1 | 语音识别延迟 | < 2 秒 |
| NFR-2 | 意图解析延迟 | < 3 秒 |
| NFR-3 | 截图分析延迟 | < 5 秒 (与 V1 一致) |
| NFR-4 | 悬浮窗渲染性能 | 不造成可感知的 App 卡顿 |
| NFR-5 | 电池消耗 | 后台监控不超过总电量的 5%/小时 |
| NFR-6 | 多语言支持 | 中文、英文 |
| NFR-7 | 最低 Android 版本 | Android 11 (API 30) |
| NFR-8 | 误判伤害控制 | 在置信度不足或未连续确认前,不得执行强制动作(返回桌面等),优先提醒并可撤销 |

---

## 三、技术设计

### 3.1 系统架构概览

```
+------------------------------------------------------+
|                    用户交互层                          |
|  +----------------+  +-----------------------------+  |
|  | VoiceInputHUD  |  | SessionStatusHUD (悬浮窗)    |  |
|  | (语音输入面板)  |  | - 规则摘要                   |  |
|  | - 录音/文本    |  | - 倒计时                     |  |
|  | - 跳过        |  | - 快捷语音按钮               |  |
|  +-------+--------+  | - 违规警告                   |  |
|          |            +-------------+---------------+  |
+----------|--------------------------|------------------+
           v                          ^
+------------------------------------------------------+
|                    意图处理层                          |
|  +----------------+  +-----------------------------+  |
|  | STT Engine     |  | IntentParser (LLM)          |  |
|  | (语音转文字)    |  | NL -> SessionIntent JSON    |  |
|  +-------+--------+  +-------------+---------------+  |
|          |                          |                  |
|          v                          v                  |
|  +----------------------------------------------+     |
|  | SessionManager                                |     |
|  | - 管理当前会话的 intent 列表                    |     |
|  | - 意图叠加/修改/合并逻辑                        |     |
|  | - 时间约束计时                                  |     |
|  +----------------------------------------------+     |
+------------------------------------------------------+
           |
           v
+------------------------------------------------------+
|                    感知与执行层                        |
|  +--------------------+  +-----------------------+   |
|  | ScreenAnalyzer     |  | ActionExecutor        |   |
|  | - 周期截图          |  | - REMIND              |   |
|  | - 视觉大模型分析    |  | - AUTO_BACK           |   |
|  | - 截图去重          |  | - GO_HOME             |   |
|  | - 结果与规则匹配    |  | - 动作去重            |   |
|  +--------------------+  +-----------------------+   |
+------------------------------------------------------+
           |
           v
+------------------------------------------------------+
|                    平台服务层                          |
|  +--------------------------------------------------+|
|  | AccessibilityService                              ||
|  | - App 切换检测                                     ||
|  | - 截图 API                                        ||
|  | - 手势执行 API                                     ||
|  | - 全局动作 (BACK, HOME)                            ||
|  +--------------------------------------------------+|
+------------------------------------------------------+
```

### 3.2 核心数据模型 (SeeNot Next)

#### 3.2.1 SessionIntent - 会话意图 (核心新增)

这是 SeeNot Next 的核心数据结构,由 LLM 从自然语言解析生成:

```kotlin
/**
 * 一次会话中用户声明的完整意图集合
 */
data class SessionIntent(
    val sessionId: String,
    val appPackageName: String,
    val appDisplayName: String,
    val createdAt: Long,
    val constraints: List<IntentConstraint>,
    val rawUtterances: List<Utterance>  // 保留原始语音文本用于调试
)

/**
 * 单条意图约束
 */
data class IntentConstraint(
    val id: String,
    val type: ConstraintType,
    val description: String,          // 自然语言描述,用于 AI 视觉匹配
    val timeLimit: TimeLimitSpec?,     // 可选的时间限制
    val intervention: InterventionLevel,
    val isActive: Boolean = true
)

enum class ConstraintType {
    ALLOW,    // 只允许做这个 - 出现其他内容则干预
    DENY,     // 不允许做这个 - 出现该内容则干预
    TIME_CAP  // 纯时间约束,不针对特定内容
}

data class TimeLimitSpec(
    val durationMinutes: Int,
    val scope: TimeScope
)

enum class TimeScope {
    SESSION,          // 本次会话总时长
    PER_CONTENT       // 针对特定内容的累计时长
}

enum class InterventionLevel {
    GENTLE,    // 仅提醒 (Toast / 悬浮窗高亮)
    MODERATE,  // 提醒 + 自动返回
    STRICT     // 直接返回桌面
}

data class Utterance(
    val text: String,
    val timestamp: Long,
    val source: UtteranceSource    // VOICE or TEXT
)

enum class UtteranceSource {
    VOICE, TEXT
}
```

#### 3.2.2 LLM 意图解析的 JSON Schema

当用户说 "这次只看工作消息,不刷朋友圈,最多 10 分钟" 时,LLM 应输出:

```json
{
  "constraints": [
    {
      "type": "ALLOW",
      "description": "Work-related chat messages and conversations",
      "time_limit": null,
      "intervention": "MODERATE"
    },
    {
      "type": "DENY",
      "description": "Moments feed / friend circle / social feed with photos and status updates",
      "time_limit": null,
      "intervention": "MODERATE"
    },
    {
      "type": "TIME_CAP",
      "description": "Overall session time limit",
      "time_limit": {
        "duration_minutes": 10,
        "scope": "SESSION"
      },
      "intervention": "STRICT"
    }
  ]
}
```

当用户说 "I can browse fashion content but no food posts, 10 minutes total":

```json
{
  "constraints": [
    {
      "type": "ALLOW",
      "description": "Fashion, clothing, outfit, and style related content",
      "time_limit": null,
      "intervention": "MODERATE"
    },
    {
      "type": "DENY",
      "description": "Food, cooking, restaurant, and recipe related content",
      "time_limit": null,
      "intervention": "MODERATE"
    },
    {
      "type": "TIME_CAP",
      "description": "Overall session time limit",
      "time_limit": {
        "duration_minutes": 10,
        "scope": "SESSION"
      },
      "intervention": "STRICT"
    }
  ]
}
```

当用户说 "只能刷5分钟公众号":

```json
{
  "constraints": [
    {
      "type": "ALLOW",
      "description": "WeChat Official Accounts / articles from subscribed public accounts",
      "time_limit": {
        "duration_minutes": 5,
        "scope": "PER_CONTENT"
      },
      "intervention": "STRICT"
    }
  ]
}
```

#### 3.2.3 会话状态模型

```kotlin
/**
 * 运行时会话状态 (内存中维护,不需要持久化)
 */
data class ActiveSession(
    val intent: SessionIntent,
    val startTime: Long,
    val timers: Map<String, TimerState>,  // constraintId -> timer
    val lastScreenAnalysis: ScreenAnalysisResult?,
    val violationCount: Int = 0
)

data class TimerState(
    val constraintId: String,
    val limitMinutes: Int,
    val elapsedMs: Long,
    val isRunning: Boolean,
    val lastTickAt: Long
)

data class ScreenAnalysisResult(
    val timestamp: Long,
    val screenshotHash: String,
    val matchedConstraints: List<ConstraintMatch>
)

data class ConstraintMatch(
    val constraintId: String,
    val isViolation: Boolean,
    val confidence: Double,
    val aiReason: String?
)
```

### 3.3 关键模块技术设计

#### 3.3.1 语音输入模块 (VoiceInputModule)

**技术选型:**

使用阿里云 qwen3-asr-flash-realtime 模型,使用 Android 的 MediaRecorder 录音,然后上传到 DashScope 中国站进行语音转文字。

**实现流程:**

```
用户进入受控应用 (用户已在 SeeNot 内选择)
    |
    v
弹出 VoiceInputOverlay (悬浮窗)
    |
    +---> [继续上次意图] ---> 直接恢复上次 SessionIntent,跳过 STT + IntentParser
    |
    +---> [录音按钮] ---> MediaRecorder/AudioRecord 录音
    |                         |
    |                         v
    |                    上传音频到 STT API
    |                         |
    |                         v
    |                    获得文本 transcription
    |                         |
    +---> [文本输入框] -------+
    |                         |
    +---> [跳过按钮] ---> 关闭, 不创建会话
                              |
                              v
                    发送文本到 IntentParser (LLM)
                              |
                              v
                    获得 SessionIntent JSON
                              |
                              v
                    SessionManager 创建/更新会话
                              |
                              v
                    显示 SessionStatusHUD
```

#### 3.3.2 意图解析引擎 (IntentParser)

**核心职责**: 将自然语言文本转换为结构化的 `SessionIntent` JSON。

**技术方案**: 使用 DashScope Qwen3 Plus/Flash 的 structured output 能力,通过精心设计的 system prompt 实现。

**System Prompt 设计要点:**

```
你是一个意图解析器。用户会告诉你他们在某个 App 中想做什么或不想做什么。
你需要将用户的自然语言转换为以下 JSON 格式。

规则:
1. 如果用户说"只做X",转换为 ALLOW 类型
2. 如果用户说"不做X",转换为 DENY 类型
3. 如果用户提到时间限制,添加 time_limit
4. description 字段用英文撰写,要足够详细以便视觉 AI 能从截图中识别该内容
5. 如果用户没有明确干预强度,默认 MODERATE
6. 如果用户说"提醒我就行",设为 GENTLE
7. 如果用户说"直接退出/强制关闭",设为 STRICT

输出严格遵循 JSON Schema,不要输出任何其他内容。
```

**意图叠加逻辑:**

```
收到新 utterance
    |
    v
解析为 constraints[]
    |
    v
与现有 session.constraints 合并:
    - 新增: 直接追加
    - 修改时间: 匹配 description 相似的 constraint, 更新 time_limit
    - 取消: 如果用户说"取消X限制", 将对应 constraint 设为 isActive=false
    - 冲突: ALLOW 与 DENY 冲突时,以最新声明为准
```

#### 3.3.3 会话管理器 (SessionManager)

**职责:**
- 维护每个 App 的当前 ActiveSession
- 管理计时器(倒计时和正计时)
- 协调 ScreenAnalyzer 的分析结果与当前意图的匹配
- 在会话结束时清理状态

**会话生命周期:**

```
App 进入 (AccessibilityService 检测到)
    |
    v
判断是否为受控应用:
    +--- 否 ---> 不弹窗,不创建会话,不进行截图分析
    |
    +--- 是 ---> 继续
    |
    v
检查是否有活跃会话 (短时间内返回则恢复)
    |
    +--- 有活跃会话 ---> 恢复会话, 更新 HUD
    |
    +--- 无活跃会话 ---> 弹出 VoiceInputOverlay
                              |
                              +--- 用户选择继续上次意图 ---> 创建 ActiveSession (from last intent)
                              |
                              +--- 用户声明意图 ---> 创建 ActiveSession
                              |
                              +--- 用户跳过 ---> 无会话, 不监控
    |
    v
会话运行中:
    - ScreenAnalyzer 周期分析 (5s)
    - 计时器更新 (1s)
    - HUD 刷新
    - 违规检测与干预
    |
    v
App 离开 (切换到其他 App / 回到桌面)
    |
    +--- 短暂离开 (< 30s) ---> 会话挂起, 计时暂停
    |
    +--- 长时间离开 (>= 30s) ---> 会话结束, 清理状态
```

#### 3.3.4 屏幕分析器 (ScreenAnalyzer) - 继承 V1 并适配

SeeNot Next 的 ScreenAnalyzer 需要适配新的意图模型:

**V1 的 prompt 模式** (单条规则判定):
```
"这个截图是否匹配以下描述: {rule.condition.parameter}? 回答 yes/no + confidence"
```

**SeeNot Next 的 prompt 模式** (多约束批量判定):
```
"分析此截图中的界面内容。根据以下约束列表,判定每条约束是否被违反:
约束列表:
1. [ALLOW] "Work-related chat messages" 
2. [DENY] "Moments feed / social feed"
3. [TIME_CAP] "Overall session" (剩余 7:23)

对每条约束输出:
{ "constraint_id": "...", "is_violation": true/false, "confidence": 0.0-1.0, "reason": "..." }
"
```

**优化**: 将多条约束合并到一次 API 调用中,而非 V1 的每条规则一次调用,降低延迟和成本。

**误判容错策略 (必须强调)**:
- **置信度分段与动作降级**:
  - 低置信度: 仅 HUD 警告 + 轻提示,不执行强制动作
  - 中置信度: 温和干预(如提醒或返回上一页),避免回桌面
  - 高置信度: 才允许执行严格干预(如回桌面)
- **连续确认**: 对强制动作采用连续 N 次违规确认(例如 2 次连续截图均违规)后才执行
- **冷却时间**: 强制动作执行后进入冷却期,避免误判导致的反复打断
- **可撤销与解释**: HUD 提供"这次放行"按钮,并展示简短理由(模型 reason),帮助建立信任与纠错

#### 3.3.5 悬浮窗系统 (HUD)

**VoiceInputOverlay** (语音输入悬浮窗):
- 使用 `WindowManager` + `TYPE_APPLICATION_OVERLAY` 
- 简洁 UI: 麦克风按钮(录音) + 文本输入框 + 发送按钮 + 跳过按钮
- 录音时显示波形动画
- 解析中显示 loading 状态
- 解析完成后显示识别结果预览,用户确认后生效

**SessionStatusHUD** (会话状态悬浮窗):
- 最小化状态: 小圆形悬浮球,显示剩余时间或状态图标
- 展开状态: 
  - 当前会话规则摘要(一行一条)
  - 每条规则的状态(正常/违规)
  - 如有时间约束,显示倒计时(大字体醒目)
  - 颜色编码: 绿色(>50%时间) -> 黄色(20%-50%) -> 红色(<20%)
  - 快捷语音按钮(追加意图)
  - 暂停/结束会话按钮
- 可拖动,不遮挡关键操作区域
- 违规时短暂放大 + 震动 + 颜色闪烁

### 3.4 AI API 调用设计

SeeNot Next 涉及三类 AI API 调用:

| 调用类型 | 模型 | 输入 | 输出 | 频率 |
|----------|------|------|------|------|
| STT (语音转文字) | Whisper / Paraformer | 音频文件 | 文本 | 每次语音输入 (~1次/会话) |
| Intent Parsing (意图解析) | Qwen3 / GPT-4o-mini (文本模型) | 用户文本 + App 上下文 | SessionIntent JSON | 每次语音输入 (~1次/会话) |
| Screen Analysis (截图分析) | Qwen3 VL Plus/Flash (视觉模型) | 截图 + 约束列表 | 违规判定 JSON | 每 5 秒 (会话期间) |

**成本优化策略:**
1. STT 和 Intent Parsing 都是低频操作(每次打开 App 一次),成本可忽略
2. Screen Analysis 是主要成本来源,继承 V1 的截图哈希去重策略
3. 将多约束合并为一次 API 调用 (V1 是每条规则一次)
4. 无意图声明时不启动截图分析

### 3.5 Android 权限需求

| 权限 | 用途 | 同 V1? |
|------|------|--------|
| `BIND_ACCESSIBILITY_SERVICE` | 检测 App 切换、截图、手势执行 | 是 |
| `SYSTEM_ALERT_WINDOW` | 悬浮窗 (VoiceInput + SessionHUD) | 是 |
| `FOREGROUND_SERVICE` | 保活后台服务 | 是 |
| `POST_NOTIFICATIONS` | 通知 | 是 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 防止被杀 | 是 |
| `RECORD_AUDIO` | 录音用于 STT | **新增** |
| `INTERNET` | API 调用 | 是 |

### 3.6 技术栈选型

| 组件 | 选型 | 理由 |
|------|------|------|
| 语言 | Kotlin | 与 V1 一致,Android 原生开发首选 |
| UI | Jetpack Compose (Material 3) | 与 V1 一致,现代声明式 UI |
| 数据持久化 | Room (SQLite) | SeeNot Next 的会话历史等数据更结构化,比 SharedPreferences 更适合 |
| 网络 | Ktor / OkHttp | API 调用 |
| JSON | Kotlinx.serialization | 比 Gson 更 Kotlin-idiomatic |
| 录音 | MediaRecorder / AudioRecord | Android 原生 API |
| STT | DashScope Paraformer API / Whisper API | 云端高质量语音识别 |
| LLM (文本) | DashScope (Qwen3) / OpenAI-compatible | 意图解析 |
| LLM (视觉) | DashScope (Qwen3 VL Plus/Flash) | 截图分析, 与 V1 一致 |
| Min SDK | 30 (Android 11) | 与 V1 一致 |
| Target SDK | 36 | 与 V1 一致 |

---

## 四、与 V1 的对比实验设计

### 4.1 对比维度

| 维度 | 衡量指标 | 方法 |
|------|----------|------|
| **上手门槛** | 首次使用到成功配置限制的时间 | A/B 测试 |
| **使用频率** | 每日主动使用次数 | 埋点统计 |
| **规则覆盖面** | 用户实际创建的限制覆盖了多少 App | 数据分析 |
| **威慑效果** | 用户在限制时间内的实际 App 使用时长 vs 声明时长 | 数据对比 |
| **AI 准确度** | 意图解析正确率,截图判定准确率 | 人工标注对比 |
| **留存率** | 7日/14日/30日留存 | 数据分析 |
| **用户满意度** | NPS 评分, 用户访谈 | 问卷 + 访谈 |
| **市场吸引力** | 概念测试反馈, "AI-Native" 感知度 | 概念测试 |

### 4.2 假设

1. **H1**: SeeNot Next 的语音意图声明方式相比 V1 的手动规则配置,首次上手时间缩短 70% 以上
2. **H2**: SeeNot Next 的实时悬浮窗倒计时对用户的威慑效果(实际使用时间/声明时间)优于 V1 的事后干预
3. **H3**: SeeNot Next 的"每次使用时声明意图"模式相比 V1 的"预配置"模式,用户感知更 AI-Native,更适合作为市场传播卖点
4. **H4**: SeeNot Next 的零预配置设计在海外用户(App 功能相对单一的生态)中接受度更高

---

## 五、实现路线图 (建议)

### Phase 1: 基础框架
- [ ] 项目脚手架 (Kotlin + Compose + Room)
- [ ] Accessibility Service 基础搭建 (App 切换检测)
- [ ] 数据模型定义 (SessionIntent, ActiveSession 等)
- [ ] 基础 UI: 主界面 + 设置页

### Phase 2: 语音输入与意图解析
- [ ] 录音模块 (MediaRecorder)
- [ ] STT API 集成
- [ ] IntentParser (LLM 调用 + prompt 工程)
- [ ] VoiceInputOverlay 悬浮窗 UI
- [ ] 意图解析结果确认流程

### Phase 3: 会话管理与悬浮窗
- [ ] SessionManager 实现 (创建/恢复/结束会话)
- [ ] 计时器系统
- [ ] SessionStatusHUD 悬浮窗 (最小化/展开/拖动)
- [ ] 倒计时显示 + 颜色编码
- [ ] 意图叠加/修改逻辑

### Phase 4: AI 视觉守护
- [ ] 截图模块 (Accessibility API)
- [ ] ScreenAnalyzer (批量约束判定)
- [ ] 截图去重
- [ ] 违规检测 -> 干预触发
- [ ] 动作执行 (REMIND, AUTO_BACK, GO_HOME)

### Phase 5: 打磨与优化
- [ ] 双语支持 (中/英)
- [ ] 权限引导流程
- [ ] 会话历史记录
- [ ] 快速复用上次意图
- [ ] 边界情况处理 (网络异常, API 失败, 权限缺失等)

---

## 六、附录

### A. 术语表

| 术语 | 定义 |
|------|------|
| **Session (会话)** | 用户一次进入某 App 到离开的完整过程 |
| **Intent (意图)** | 用户通过语音/文本声明的本次使用目的和限制 |
| **Constraint (约束)** | 意图中的单条具体限制或许可 |
| **HUD** | Head-Up Display,悬浮窗信息面板 |
| **STT** | Speech-to-Text,语音转文字 |
| **NLU** | Natural Language Understanding,自然语言理解 |
| **Intervention (干预)** | 系统在检测到违规时采取的动作 |

### B. V1 -> SeeNot Next 概念映射

| V1 概念 | SeeNot Next 对应 | 变化 |
|---------|---------|------|
| MonitoringApp | 任意 App (无需预注册) | 去掉预配置 |
| Rule | IntentConstraint | 从静态规则变为会话级动态约束 |
| RuleCondition (ON_PAGE) | IntentConstraint.description | AI 视觉匹配描述 |
| RuleAction | InterventionLevel | 简化为三级干预强度 |
| TimeConstraint | TimeLimitSpec | 统一为会话级或内容级时间限制 |
| MonitoringIndicatorOverlay | SessionStatusHUD | 从简单指示器升级为完整 HUD |
| AskOverlay | VoiceInputOverlay | 从按钮面板变为语音交互 |
| 手动规则配置 UI | 语音 + LLM 解析 | 核心交互范式变化 |
