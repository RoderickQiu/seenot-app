package com.seenot.debugger

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam
import com.alibaba.dashscope.common.MultiModalMessage
import com.alibaba.dashscope.common.Role
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*

class ScreenAnalyzerDebugger {
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

    private val conversation = MultiModalConversation()
    private val gson = Gson()

    companion object {
        private const val MODEL = "qwen-vl-plus"
    }

    suspend fun singleTest(imageFile: File, constraintDesc: String) {
        println("\n" + "=".repeat(60))
        println("🖼️  图片: ${imageFile.name}")
        println("🎯 约束: $constraintDesc")
        println("=".repeat(60))

        val startTime = System.currentTimeMillis()

        try {
            val result = analyzeScreen(imageFile, constraintDesc)
            val elapsed = System.currentTimeMillis() - startTime

            println("\n✅ 分析完成 (${elapsed}ms)")
            println("\n📊 结果:")
            println(result)

        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            println("\n❌ 分析失败 (${elapsed}ms)")
            println("错误: ${e.message}")
            e.printStackTrace()
        }
    }

    suspend fun batchTest(dir: File, constraintDesc: String) {
        val images = dir.listFiles { file ->
            file.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp")
        }?.toList() ?: emptyList()

        println("\n📋 批量测试: ${images.size} 张图片\n")

        val results = mutableListOf<TestResult>()

        images.forEachIndexed { index, imageFile ->
            println("[${index + 1}/${images.size}] ${imageFile.name}")

            try {
                val startTime = System.currentTimeMillis()
                val response = analyzeScreen(imageFile, constraintDesc)
                val elapsed = System.currentTimeMillis() - startTime

                results.add(TestResult(
                    imageName = imageFile.name,
                    success = true,
                    response = response,
                    elapsedMs = elapsed
                ))
                println("  ✅ ${elapsed}ms\n")

            } catch (e: Exception) {
                results.add(TestResult(
                    imageName = imageFile.name,
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
        val reportFile = File("results/screen-test-results-${System.currentTimeMillis()}.json")
        reportFile.parentFile.mkdirs()
        reportFile.writeText(gson.toJson(results))
        println("\n💾 详细结果已保存: ${reportFile.absolutePath}")
    }

    private suspend fun analyzeScreen(imageFile: File, constraintDesc: String): String = withContext(Dispatchers.IO) {
        // 转换图片为base64
        val imageBytes = imageFile.readBytes()
        val base64Image = Base64.getEncoder().encodeToString(imageBytes)

        val prompt = """分析这个屏幕截图，判断是否违反了以下约束：

约束: $constraintDesc

请回答：
1. 是否违反约束？(是/否)
2. 置信度 (0.0-1.0)
3. 理由

格式：
违反: 是/否
置信度: 0.95
理由: [具体说明]"""

        val userMessage = MultiModalMessage.builder()
            .role(Role.USER.value)
            .content(listOf(
                mapOf("image" to "data:image/jpeg;base64,$base64Image"),
                mapOf("text" to prompt)
            ))
            .build()

        val param = MultiModalConversationParam.builder()
            .apiKey(apiKey)
            .model(MODEL)
            .message(userMessage)
            .build()

        val result = conversation.call(param)
        result.output.choices[0].message.content[0]["text"] as String
    }

    data class TestResult(
        val imageName: String,
        val success: Boolean,
        val response: String? = null,
        val error: String? = null,
        val elapsedMs: Long
    )
}
