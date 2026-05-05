/*
Should sync with the app/src/main/java/com/seenot/app/ai/parser/IntentParser.kt
*/

package com.seenot.debugger

import com.alibaba.dashscope.aigc.generation.Generation
import com.alibaba.dashscope.aigc.generation.GenerationParam
import com.alibaba.dashscope.common.Message
import com.alibaba.dashscope.common.Role
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class IntentParserDebugger {
    private val apiKey = System.getenv("DASHSCOPE_API_KEY")
        ?: run {
            val possiblePaths = listOf(
                File("local.properties"),
                File("../local.properties"),
                File(System.getProperty("user.dir"), "local.properties")
            )

            possiblePaths.firstOrNull { it.exists() }
                ?.readLines()
                ?.firstOrNull { line ->
                    val trimmed = line.trim()
                    trimmed.startsWith("sk-") || trimmed.startsWith("DASHSCOPE_API_KEY=")
                }
                ?.let { line ->
                    if (line.contains("=")) {
                        line.substringAfter("=").trim()
                    } else {
                        line.trim()
                    }
                }
        }
        ?: throw IllegalStateException("未找到API Key，请在local.properties中添加或设置DASHSCOPE_API_KEY环境变量")

    private val generation = Generation()
    private val gson = Gson()

    companion object {
        private const val MODEL = "qwen-plus"
    }

    private fun outputLanguageCode(): String {
        return if (Locale.getDefault().language.equals("zh", ignoreCase = true)) "zh" else "en"
    }

    private fun outputLanguageName(): String {
        return if (outputLanguageCode() == "zh") "Simplified Chinese" else "English"
    }

    private fun buildLanguageAwareExamples(): String {
        return if (outputLanguageCode() == "zh") {
            """
输入："刷微信但不能看朋友圈"
输出：{"constraints":[{"type":"DENY","description":"朋友圈","timeLimitMinutes":null,"timeScope":"SESSION","intervention":"MODERATE","effectiveIntent":{"raw":"朋友圈","type":"DENY","prohibitedSet":"朋友圈功能或内容","allowedSet":null,"evaluationScope":"feature_or_behavior","aggregatePagePolicy":"aggregate_container_can_violate","decisionRule":"用户进入或使用朋友圈功能时判定为违反；仅看到朋友圈入口或链接时不判定为违反。"}}]}

输入："只看微信消息"
输出：{"constraints":[{"type":"DENY","description":"除微信消息外的其他内容","timeLimitMinutes":null,"timeScope":"SESSION","intervention":"MODERATE","effectiveIntent":{"raw":"除微信消息外的其他内容","type":"DENY","prohibitedSet":"非微信消息的单个内容或功能","allowedSet":"微信消息","evaluationScope":"feature_or_behavior","aggregatePagePolicy":"follow_constraint_target","decisionRule":"只有当前明确进入非微信消息的功能或正在消费非微信消息内容时才判定违反；缺少微信消息证据本身不构成违反。"}}]}

输入："only look at messages"
输出：{"constraints":[{"type":"DENY","description":"除消息外的其他内容","timeLimitMinutes":null,"timeScope":"SESSION","intervention":"MODERATE","effectiveIntent":{"raw":"除消息外的其他内容","type":"DENY","prohibitedSet":"非消息的单个内容或功能","allowedSet":"消息","evaluationScope":"feature_or_behavior","aggregatePagePolicy":"follow_constraint_target","decisionRule":"只有当前明确进入非消息功能或正在消费非消息内容时才判定违反；缺少消息证据本身不构成违反。"}}]}

输入："每天最多10分钟"
输出：{"constraints":[],"unsupportedMode":"DAILY_TOTAL"}

输入："这次不监控"
输出：{"constraints":[{"type":"NO_MONITOR","description":"本次不监控","timeLimitMinutes":null,"timeScope":"SESSION","intervention":"GENTLE","effectiveIntent":{"raw":"本次不监控","type":"NO_MONITOR","prohibitedSet":"","allowedSet":null,"evaluationScope":"not_monitored","aggregatePagePolicy":"not_applicable","decisionRule":"本次会话不进行屏幕分析、计时判断或干预。"}}]}
            """.trimIndent()
        } else {
            """
Input: "刷微信但不能看朋友圈"
Output: {"constraints":[{"type":"DENY","description":"Moments","timeLimitMinutes":null,"timeScope":"SESSION","intervention":"MODERATE","effectiveIntent":{"raw":"Moments","type":"DENY","prohibitedSet":"Moments feature or content","allowedSet":null,"evaluationScope":"feature_or_behavior","aggregatePagePolicy":"aggregate_container_can_violate","decisionRule":"Violation only when the user enters or uses Moments; merely seeing an entry point or link is safe."}}]}

Input: "只看微信消息"
Output: {"constraints":[{"type":"DENY","description":"all other content except WeChat messages","timeLimitMinutes":null,"timeScope":"SESSION","intervention":"MODERATE","effectiveIntent":{"raw":"all other content except WeChat messages","type":"DENY","prohibitedSet":"single content or feature outside WeChat messages","allowedSet":"WeChat messages","evaluationScope":"feature_or_behavior","aggregatePagePolicy":"follow_constraint_target","decisionRule":"Violation only when the current screen clearly enters a non-message feature or actively consumes non-message content; missing message evidence alone is not a violation."}}]}

Input: "only look at messages"
Output: {"constraints":[{"type":"DENY","description":"all other content except messages","timeLimitMinutes":null,"timeScope":"SESSION","intervention":"MODERATE","effectiveIntent":{"raw":"all other content except messages","type":"DENY","prohibitedSet":"single content or feature outside messages","allowedSet":"messages","evaluationScope":"feature_or_behavior","aggregatePagePolicy":"follow_constraint_target","decisionRule":"Violation only when the current screen clearly enters a non-message feature or actively consumes non-message content; missing message evidence alone is not a violation."}}]}

Input: "每天最多10分钟"
Output: {"constraints":[],"unsupportedMode":"DAILY_TOTAL"}

Input: "do not monitor this time"
Output: {"constraints":[{"type":"NO_MONITOR","description":"no monitoring this time","timeLimitMinutes":null,"timeScope":"SESSION","intervention":"GENTLE","effectiveIntent":{"raw":"no monitoring this time","type":"NO_MONITOR","prohibitedSet":"","allowedSet":null,"evaluationScope":"not_monitored","aggregatePagePolicy":"not_applicable","decisionRule":"Do not analyze, time, or intervene for this session intent."}}]}
            """.trimIndent()
        }
    }

    suspend fun singleTest(utterance: String, packageName: String = "com.example.app", appName: String = "测试应用") {
        println("\n" + "=".repeat(60))
        println("📝 输入: $utterance")
        println("📦 应用: $appName ($packageName)")
        println("=".repeat(60))

        val startTime = System.currentTimeMillis()

        try {
            val response = parseIntent(utterance, packageName, appName)
            val elapsed = System.currentTimeMillis() - startTime

            println("\n✅ 解析成功 (${elapsed}ms)")
            println("\n📊 结果:")
            println(response)

            // 尝试解析JSON结构
            try {
                val jsonStr = Regex("""\{[\s\S]*\}""").find(response)?.value ?: response
                val json = JsonParser.parseString(jsonStr).asJsonObject
                println("\n🔍 结构化数据:")

                val constraints = json.getAsJsonArray("constraints")
                if (constraints != null && constraints.size() > 0) {
                    println("  约束条件:")
                    constraints.forEach { c ->
                        val obj = c.asJsonObject
                        val type = obj.get("type")?.asString
                        val desc = obj.get("description")?.asString
                        val timeLimitElem = obj.get("timeLimitMinutes")
                        val time = if (timeLimitElem != null && !timeLimitElem.isJsonNull) timeLimitElem.asInt else null
                        val timeScope = obj.get("timeScope")?.asString
                        val intervention = obj.get("intervention")?.asString
                        println("    - [$type] $desc ${if (time != null) "($time 分钟, $timeScope)" else ""} [干预: $intervention]")
                        printEffectiveIntent(obj, "      ")
                    }
                }
            } catch (e: Exception) {
                println("\n⚠️  无法解析为JSON: ${e.message}")
            }

        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            println("\n❌ 解析失败 (${elapsed}ms)")
            println("错误: ${e.message}")
            e.printStackTrace()
        }
    }

    suspend fun batchTest(file: File, packageName: String = "com.example.app", appName: String = "测试应用") {
        val lines = file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        println("\n📋 批量测试: ${lines.size} 条语句\n")

        val results = mutableListOf<TestResult>()

        lines.forEachIndexed { index, line ->
            println("\n[${index + 1}/${lines.size}] $line")

            try {
                val startTime = System.currentTimeMillis()
                val response = parseIntent(line, packageName, appName)
                val elapsed = System.currentTimeMillis() - startTime

                results.add(TestResult(
                    input = line,
                    success = true,
                    response = response,
                    elapsedMs = elapsed
                ))

                // 打印解析结果
                try {
                    val jsonStr = Regex("""\{[\s\S]*\}""").find(response)?.value ?: response
                    val json = JsonParser.parseString(jsonStr).asJsonObject
                    val constraints = json.getAsJsonArray("constraints")
                    if (constraints != null && constraints.size() > 0) {
                        constraints.forEach { c ->
                            val obj = c.asJsonObject
                            val type = obj.get("type")?.asString
                            val desc = obj.get("description")?.asString
                            val timeLimitElem = obj.get("timeLimitMinutes")
                            val time = if (timeLimitElem != null && !timeLimitElem.isJsonNull) timeLimitElem.asInt else null
                            val timeScope = obj.get("timeScope")?.asString
                            val intervention = obj.get("intervention")?.asString
                            println("  → [$type] $desc ${if (time != null) "(${time}分钟, $timeScope)" else ""} [$intervention]")
                            printEffectiveIntent(obj, "     ")
                        }
                    } else {
                        println("  → 无约束")
                    }
                } catch (e: Exception) {
                    println("  → $response")
                }
                println("  ✅ ${elapsed}ms")

            } catch (e: Exception) {
                results.add(TestResult(
                    input = line,
                    success = false,
                    error = e.message,
                    elapsedMs = 0
                ))
                println("  ❌ ${e.message}")
            }
        }

        // 统计报告
        println("\n" + "=".repeat(60))
        println("📊 测试报告")
        println("=".repeat(60))
        println("总数: ${results.size}")
        println("成功: ${results.count { it.success }}")
        println("失败: ${results.count { !it.success }}")
        println("平均耗时: ${results.filter { it.success }.map { it.elapsedMs }.average().toInt()}ms")

        // 保存详细结果
        val reportFile = File("results/test-results-${System.currentTimeMillis()}.json")
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(gson.toJson(results))
        println("\n💾 详细结果已保存: ${reportFile.absolutePath}")
    }

    suspend fun interactiveMode(packageName: String = "com.example.app", appName: String = "测试应用") {
        while (true) {
            print("\n> ")
            val input = readLine()?.trim() ?: break

            if (input.isEmpty()) continue
            if (input == "exit" || input == "quit") break

            singleTest(input, packageName, appName)
        }
        println("\n👋 再见！")
    }

    private suspend fun parseIntent(utterance: String, packageName: String, appName: String): String = withContext(Dispatchers.IO) {
        val outputLanguageName = outputLanguageName()
        val examples = buildLanguageAwareExamples()
        val prompt = """
请将用户的意图解析为结构化的规则。

应用: $appName ($packageName)
用户说: "$utterance"
当前 SeeNot 软件语言: $outputLanguageName

⚠️ 重要原则：
1. 只生成一个约束，将用户的所有限制合并到这一个约束中
2. 用户可能使用任何语言表达同一个意图。你必须按语义理解，而不是只匹配中文措辞。
3. 区分两种语义：
   - Exclusive / allowlist intent：用户只允许自己看/做某类内容，例如 "只看X"、"只想看X"、"only X"、"just X"、"nothing but X"。由于当前 schema 没有 ALLOW，这类语义也要编码为 DENY，但 description 必须改写成“会触发干预的补集内容”，即“除X外的其他内容”。
   - Blocklist intent：用户不允许某类内容，例如 "不能看X"、"不要看X"、"don't look at X"。这类也使用 DENY，description 直接写被禁止内容。
   - Pure time limit：只有时间限制、没有内容限制时，使用 TIME_CAP。
   - No-monitor intent：用户明确表示这次/本次/当前会话不要 SeeNot 监控、分析、提醒或干预这个 app，例如“不监控这个 app”、“这次不管我”、“don't monitor this app”。这类使用 NO_MONITOR，表示这是一个合法会话意图，不是解析失败。
3. 时间限制直接加在约束的 timeLimitMinutes 字段上
4. 如果既有内容限制又有时间限制，使用 DENY，时间加在同一个约束上
5. **多条件合并**：如果用户提到多个内容（如"不能看朋友圈和视频号"），将它们合并到一个约束的 description 中
6. 对于 DENY，description 必须始终写“什么内容会触发干预”，不能直接复述 allowlist 原话
7. **语言规则（高优先级）**：description 必须使用当前 SeeNot 软件语言（当前设置：$outputLanguageName），而不是跟随用户输入语言。
   - 如果当前 SeeNot 是英文，即使用户输入中文，description 也必须输出英文。
   - 如果当前 SeeNot 是中文，即使用户输入英文，description 也必须输出中文。
   - 不要输出中英混杂 description。
8. 规则必须保持泛化，不要把推理建立在某个具体 app、品牌或中文固定短语上；同类语义在淘宝/京东/Amazon、微信/WhatsApp/Telegram 中都应一致处理

规则类型:
- DENY: 黑名单，禁止使用某功能/内容
- TIME_CAP: 纯时间限制（无内容限制）
- NO_MONITOR: 本次会话不做屏幕分析、计时判断或干预，但仍记录为用户声明的合法意图

时间范围类型 (timeScope):
- SESSION: 整个会话计时，无论看什么内容都在倒计时
- PER_CONTENT: 只有在目标内容时才计时，切换到其他内容时暂停
- CONTINUOUS: 只有在同一内容持续停留时才计时

⚠️ 如何判断 timeScope：
- "X只能看Y分钟" → PER_CONTENT（只有看X时才计时）
- "不能看X，最多Y分钟" → SESSION（整个会话限时，看到X违规）
- "最多Y分钟" → SESSION（纯时间限制）
- "每天最多Y分钟" / "今天只能看Y分钟" / "today total" / "daily total" 这类“每日累计”语义当前不支持。
  对这类输入，不要改写成 SESSION 或 PER_CONTENT；返回空 constraints，并额外返回 "unsupportedMode":"DAILY_TOTAL"。

干预级别:
- GENTLE: 温和提醒
- MODERATE: 中等提醒
- STRICT: 严格阻止

请用以下JSON格式回复（只返回一个约束）:
{
  "constraints": [
    {
      "type": "DENY|TIME_CAP|NO_MONITOR",
      "description": "规则描述",
      "timeLimitMinutes": null或数字,
      "timeScope": "SESSION|PER_CONTENT|CONTINUOUS",
      "intervention": "GENTLE|MODERATE|STRICT",
      "effectiveIntent": {
        "raw": "与 description 完全一致的用户可见规则",
        "type": "DENY|TIME_CAP|NO_MONITOR",
        "prohibitedSet": "会触发干预或计入范围的功能、内容类型、主题或行为",
        "allowedSet": "允许的功能、内容类型、主题或行为；没有则为 null",
        "evaluationScope": "single_content_only|aggregate_container|feature_or_behavior|session|not_monitored|...",
        "aggregatePagePolicy": "candidate_exposure_is_safe|aggregate_container_can_violate|follow_constraint_target|not_applicable",
        "decisionRule": "给屏幕分析器使用的简短操作规则"
      }
    }
  ],
  "unsupportedMode": null或"DAILY_TOTAL"
}

effectiveIntent 是内部语义表示，用户不会看到：
- 不要用它改写 description；description 仍是唯一用户可见规则。
- 如果 DENY 约束禁止的是候选内容/主题/内容类型，聚合页/推荐页/列表页只曝光候选卡片时 aggregatePagePolicy 应为 candidate_exposure_is_safe；只有进入单个详情、播放、文章、商品等内容页并明确落入 prohibitedSet 时才违反。
- 如果 DENY 约束禁止的是聚合容器、推荐列表、信息流、某个功能模块本身，aggregatePagePolicy 应为 aggregate_container_can_violate。
- 如果 DENY 是“只允许 X / 除 X 外都不看”的补集语义，allowedSet 写 X，decisionRule 必须保守：不能仅因缺少 X 的证据就判定违反。
- TIME_CAP 只表达 in_scope/out_of_scope 计时范围，不表达 violates/safe。

示例：
$examples

如果用户没有明确表达规则，则返回空constraints。
                """.trimIndent()

        val messages = listOf(
            Message.builder().role(Role.SYSTEM.value).content("You are a multilingual intent parser. 你要按语义理解用户意图，而不是依赖某一种语言或某个具体 app 的固定说法。").build(),
            Message.builder().role(Role.USER.value).content(prompt).build()
        )

        val param = GenerationParam.builder()
            .apiKey(apiKey)
            .model(MODEL)
            .messages(messages)
            .resultFormat(GenerationParam.ResultFormat.MESSAGE)
            .build()

        val result = generation.call(param)
        result.output.choices[0].message.content
    }

    private fun printEffectiveIntent(constraintObject: com.google.gson.JsonObject, indent: String) {
        val effective = constraintObject.getAsJsonObject("effectiveIntent")
            ?: constraintObject.getAsJsonObject("effective_intent")
            ?: run {
                println("${indent}effectiveIntent: <missing>")
                return
            }
        val prohibitedSet = effective.get("prohibitedSet")?.takeIf { !it.isJsonNull }?.asString
            ?: effective.get("prohibited_set")?.takeIf { !it.isJsonNull }?.asString
        val allowedSet = effective.get("allowedSet")?.takeIf { !it.isJsonNull }?.asString
            ?: effective.get("allowed_set")?.takeIf { !it.isJsonNull }?.asString
        val evaluationScope = effective.get("evaluationScope")?.takeIf { !it.isJsonNull }?.asString
            ?: effective.get("evaluation_scope")?.takeIf { !it.isJsonNull }?.asString
        val aggregatePolicy = effective.get("aggregatePagePolicy")?.takeIf { !it.isJsonNull }?.asString
            ?: effective.get("aggregate_page_policy")?.takeIf { !it.isJsonNull }?.asString
        val decisionRule = effective.get("decisionRule")?.takeIf { !it.isJsonNull }?.asString
            ?: effective.get("decision_rule")?.takeIf { !it.isJsonNull }?.asString
        println("${indent}effectiveIntent:")
        println("${indent}  prohibitedSet: $prohibitedSet")
        println("${indent}  allowedSet: ${allowedSet ?: "null"}")
        println("${indent}  evaluationScope: $evaluationScope")
        println("${indent}  aggregatePagePolicy: $aggregatePolicy")
        println("${indent}  decisionRule: $decisionRule")
    }

    data class TestResult(
        val input: String,
        val success: Boolean,
        val response: String? = null,
        val error: String? = null,
        val elapsedMs: Long
    )
}
