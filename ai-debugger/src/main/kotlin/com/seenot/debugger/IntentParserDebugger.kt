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
                val json = JsonParser.parseString(response).asJsonObject
                println("\n🔍 结构化数据:")

                val constraints = json.getAsJsonArray("constraints")
                if (constraints != null && constraints.size() > 0) {
                    println("  约束条件:")
                    constraints.forEach { c ->
                        val obj = c.asJsonObject
                        val type = obj.get("type")?.asString
                        val desc = obj.get("description")?.asString
                        val time = obj.get("timeLimitMinutes")?.asInt
                        val timeScope = obj.get("timeScope")?.asString
                        val intervention = obj.get("intervention")?.asString
                        println("    - [$type] $desc ${if (time != null) "($time 分钟, $timeScope)" else ""} [干预: $intervention]")
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
                    val json = JsonParser.parseString(response).asJsonObject
                    val constraints = json.getAsJsonArray("constraints")
                    if (constraints != null && constraints.size() > 0) {
                        constraints.forEach { c ->
                            val obj = c.asJsonObject
                            val type = obj.get("type")?.asString
                            val desc = obj.get("description")?.asString
                            val time = obj.get("timeLimitMinutes")?.asInt
                            val timeScope = obj.get("timeScope")?.asString
                            val intervention = obj.get("intervention")?.asString
                            println("  → [$type] $desc ${if (time != null) "(${time}分钟, $timeScope)" else ""} [$intervention]")
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
        reportFile.parentFile.mkdirs()
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
        val prompt = """
请将用户的意图解析为结构化的规则。

应用: $appName ($packageName)
用户说: "$utterance"

⚠️ 重要原则：
1. 只生成一个约束，将用户的所有限制合并到这一个约束中
2. 区分三种场景：
   - "只能看X" → ALLOW（白名单）
   - "不能看X" → DENY（黑名单）
   - "最多X分钟"（无内容限制）→ TIME_CAP
3. 时间限制直接加在约束的 timeLimitMinutes 字段上
4. 如果既有内容限制又有时间限制，优先使用 ALLOW/DENY，时间加在同一个约束上

规则类型:
- ALLOW: 白名单，只允许使用某功能/内容
- DENY: 黑名单，禁止使用某功能/内容
- TIME_CAP: 纯时间限制（无内容限制）

时间范围类型 (timeScope):
- SESSION: 整个会话计时，无论看什么内容都在倒计时
- PER_CONTENT: 只有在目标内容时才计时，切换到其他内容时暂停

⚠️ 如何判断 timeScope：
- "X只能看Y分钟" → PER_CONTENT（只有看X时才计时）
- "不能看X，最多Y分钟" → SESSION（整个会话限时，看到X违规）
- "最多Y分钟" → SESSION（纯时间限制）

干预级别:
- GENTLE: 温和提醒
- MODERATE: 中等提醒
- STRICT: 严格阻止

请用以下JSON格式回复（只返回一个约束）:
{
  "constraints": [
    {
      "type": "ALLOW|DENY|TIME_CAP",
      "description": "规则描述",
      "timeLimitMinutes": null或数字,
      "timeScope": "SESSION|PER_CONTENT",
      "intervention": "GENTLE|MODERATE|STRICT"
    }
  ]
}

示例：
输入："刷微信但不能看朋友圈"
输出：{"constraints":[{"type":"DENY","description":"禁止查看朋友圈","timeLimitMinutes":null,"timeScope":"SESSION","intervention":"MODERATE"}]}

输入："打开小红书，只能看穿搭"
输出：{"constraints":[{"type":"ALLOW","description":"只允许浏览穿搭内容","timeLimitMinutes":null,"timeScope":"SESSION","intervention":"MODERATE"}]}

输入："打开小红书，只能看穿搭5分钟"
输出：{"constraints":[{"type":"ALLOW","description":"只允许浏览穿搭内容","timeLimitMinutes":5,"timeScope":"PER_CONTENT","intervention":"MODERATE"}]}

输入："朋友圈只能看3分钟"
输出：{"constraints":[{"type":"ALLOW","description":"只允许查看朋友圈","timeLimitMinutes":3,"timeScope":"PER_CONTENT","intervention":"MODERATE"}]}

输入："打开抖音，最多刷15分钟"
输出：{"constraints":[{"type":"TIME_CAP","description":"使用时长限制","timeLimitMinutes":15,"timeScope":"SESSION","intervention":"STRICT"}]}

输入："打开小红书看穿搭，但不能看美食，最多10分钟"
输出：{"constraints":[{"type":"DENY","description":"禁止浏览美食内容","timeLimitMinutes":10,"timeScope":"SESSION","intervention":"MODERATE"}]}

如果用户没有明确表达规则，则返回空constraints。
                """.trimIndent()

        val messages = listOf(
            Message.builder().role(Role.SYSTEM.value).content("你是一个智能的意图解析助手。").build(),
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

    data class TestResult(
        val input: String,
        val success: Boolean,
        val response: String? = null,
        val error: String? = null,
        val elapsedMs: Long
    )
}
