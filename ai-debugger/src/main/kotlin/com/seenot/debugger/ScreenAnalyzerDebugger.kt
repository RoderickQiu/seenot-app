/*
Should sync with the app/src/main/java/com/seenot/app/ai/screen/ScreenAnalyzer.kt
*/

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
        private const val MODEL = "qwen3.6-plus"
    }

    private fun outputLanguageName(): String {
        return if (Locale.getDefault().language.equals("zh", ignoreCase = true)) {
            "Simplified Chinese"
        } else {
            "English"
        }
    }

    suspend fun singleTest(imageFile: File, constraintDesc: String, constraintType: String = "DENY") {
        println("\n" + "=".repeat(60))
        println("🖼️  图片: ${imageFile.name}")
        println("🎯 约束类型: $constraintType")
        println("🎯 约束描述: $constraintDesc")
        println("=".repeat(60))

        val startTime = System.currentTimeMillis()

        try {
            val result = analyzeScreen(imageFile, constraintDesc, constraintType)
            val elapsed = System.currentTimeMillis() - startTime

            println("\n✅ 分析完成 (${elapsed}ms)")
            println("\n📊 AI原始返回:")
            println(result)

        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            println("\n❌ 分析失败 (${elapsed}ms)")
            println("错误: ${e.message}")
            e.printStackTrace()
        }
    }

    suspend fun batchTest(dir: File, constraintDesc: String, constraintType: String = "DENY") {
        val images = dir.listFiles { file ->
            file.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp")
        }?.toList() ?: emptyList()

        println("\n📋 批量测试: ${images.size} 张图片")
        println("🎯 约束类型: $constraintType")
        println("🎯 约束描述: $constraintDesc\n")

        val results = mutableListOf<TestResult>()

        images.forEachIndexed { index, imageFile ->
            println("[${index + 1}/${images.size}] ${imageFile.name}")

            try {
                val startTime = System.currentTimeMillis()
                val response = analyzeScreen(imageFile, constraintDesc, constraintType)
                val elapsed = System.currentTimeMillis() - startTime

                results.add(TestResult(
                    imageName = imageFile.name,
                    success = true,
                    response = response,
                    elapsedMs = elapsed
                ))
                println("  ✅ ${elapsed}ms")
                println("  📊 AI原始返回:")
                println("  $response\n")

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

    private suspend fun analyzeScreen(imageFile: File, constraintDesc: String, constraintType: String): String = withContext(Dispatchers.IO) {
        // 转换图片为base64
        val imageBytes = imageFile.readBytes()
        val base64Image = Base64.getEncoder().encodeToString(imageBytes)

        // Build type-specific rules
        val typeLabel = when (constraintType.uppercase()) {
            "DENY" -> "禁止"
            "TIME_CAP" -> "时间限制"
            else -> "禁止"
        }

        val typeSpecificRules = when (constraintType.uppercase()) {
            "DENY" -> """
3. **违规判断规则（针对 [禁止] 约束）：**
   - [禁止] 约束：用户在被禁止的功能 → violates
     例：[禁止] QQ空间 → 只有在QQ空间才违规，QQ群聊不违规
   - 必须精确匹配功能名称，不要泛化
            """.trimIndent()

            "TIME_CAP" -> """
3. **范围判断规则（针对 [时间限制] 约束）：**
   - 判断用户是否在目标功能范围内（用于计时，不是违规判断）
   - 在目标范围内 → in_scope
   - 不在目标范围内 → out_of_scope
   - 例：[时间限制] 小红书首页 → 在小红书首页返回 in_scope，在其他页面返回 out_of_scope
   - 注意：时间限制类型永远不返回 violates/safe，只返回 in_scope/out_of_scope
   - 必须精确匹配功能名称，不要泛化
            """.trimIndent()

            else -> ""
        }

        val decisionValues = when (constraintType.uppercase()) {
            "DENY" -> """
- violates: 违反约束（用于 [禁止]）
- safe: 未违反约束（用于 [禁止]）
- unknown: 无法判断
            """.trimIndent()

            "TIME_CAP" -> """
- in_scope: 在目标范围内（用于 [时间限制]）
- out_of_scope: 不在目标范围内（用于 [时间限制]）
- unknown: 无法判断
            """.trimIndent()

            else -> """
- violates: 违反约束
- safe: 未违反约束
- unknown: 无法判断
            """.trimIndent()
        }

        val outputLanguageName = outputLanguageName()
        val reasonExample = if (outputLanguageName == "English") {
            "User is on WeChat Moments"
        } else {
            "用户在微信-朋友圈页面"
        }

        val prompt = """
你是屏幕场景识别AI，判断用户当前行为与约束的关系。

**输出语言规则（最高优先级）：**
- `reason` 必须使用 $outputLanguageName
- 不要因为截图内容语言变化而切换输出语言
- `reason` 示例：$reasonExample

**核心任务：**
1. 识别用户当前所在的具体功能模块
2. 根据约束类型进行相应判断

**判断规则：**

1. **精确识别功能模块**：
   - QQ群聊 ≠ QQ空间（完全不同的功能）
   - 微信群聊 ≠ 微信朋友圈 ≠ 微信公众号
   - 小红书图文 ≠ 小红书短视频
   - 必须准确识别当前界面属于哪个具体功能

2. **区分"入口" vs "使用中"**：
   - 看到入口/图标 → 不算使用
   - 进入详情页/播放页 → 才算使用
   - 例外：抖音/快手首页本身就是短视频流，算使用

$typeSpecificRules

**当前约束：**
[$typeLabel] $constraintDesc

**输出格式（JSON数组）：**
[
  {
    "constraint_id": "1",
    "reason": "$reasonExample",
    "decision": "见下方说明"
  }
]

decision取值：
$decisionValues
        """.trimIndent()

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
