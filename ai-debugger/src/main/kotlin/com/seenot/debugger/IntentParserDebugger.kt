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

    suspend fun singleTest(utterance: String) {
        println("\n" + "=".repeat(60))
        println("📝 输入: $utterance")
        println("=".repeat(60))

        val startTime = System.currentTimeMillis()

        try {
            val response = parseIntent(utterance)
            val elapsed = System.currentTimeMillis() - startTime

            println("\n✅ 解析成功 (${elapsed}ms)")
            println("\n📊 结果:")
            println(response)

            // 尝试解析JSON结构
            try {
                val json = JsonParser.parseString(response).asJsonObject
                println("\n🔍 结构化数据:")
                println("  目标应用: ${json.get("targetApp")?.asString ?: "未指定"}")

                val constraints = json.getAsJsonArray("constraints")
                if (constraints != null && constraints.size() > 0) {
                    println("  约束条件:")
                    constraints.forEach { c ->
                        val obj = c.asJsonObject
                        val type = obj.get("type")?.asString
                        val desc = obj.get("description")?.asString
                        val time = obj.get("timeLimitMinutes")?.asInt
                        println("    - [$type] $desc ${if (time != null) "($time 分钟)" else ""}")
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

    suspend fun batchTest(file: File) {
        val lines = file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        println("\n📋 批量测试: ${lines.size} 条语句\n")

        val results = mutableListOf<TestResult>()

        lines.forEachIndexed { index, line ->
            println("[${index + 1}/${lines.size}] $line")

            try {
                val startTime = System.currentTimeMillis()
                val response = parseIntent(line)
                val elapsed = System.currentTimeMillis() - startTime

                results.add(TestResult(
                    input = line,
                    success = true,
                    response = response,
                    elapsedMs = elapsed
                ))
                println("  ✅ ${elapsed}ms\n")

            } catch (e: Exception) {
                results.add(TestResult(
                    input = line,
                    success = false,
                    error = e.message,
                    elapsedMs = 0
                ))
                println("  ❌ ${e.message}\n")
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

    suspend fun interactiveMode() {
        while (true) {
            print("\n> ")
            val input = readLine()?.trim() ?: break

            if (input.isEmpty()) continue
            if (input == "exit" || input == "quit") break

            singleTest(input)
        }
        println("\n👋 再见！")
    }

    private suspend fun parseIntent(utterance: String): String = withContext(Dispatchers.IO) {
        val systemPrompt = """你是一个意图解析助手。用户会告诉你他们打开某个应用的意图，你需要解析出结构化的约束条件。

输出JSON格式：
{
  "targetApp": "应用名称",
  "constraints": [
    {
      "type": "ALLOW|DENY|TIME_CAP",
      "description": "约束描述",
      "timeLimitMinutes": 10  // 仅TIME_CAP需要
    }
  ]
}

示例：
输入："我想刷小红书，但只能看10分钟"
输出：{"targetApp":"小红书","constraints":[{"type":"TIME_CAP","description":"限制使用时长","timeLimitMinutes":10}]}

输入："打开淘宝但不要让我看直播"
输出：{"targetApp":"淘宝","constraints":[{"type":"DENY","description":"禁止观看直播"}]}"""

        val messages = listOf(
            Message.builder().role(Role.SYSTEM.value).content(systemPrompt).build(),
            Message.builder().role(Role.USER.value).content(utterance).build()
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
