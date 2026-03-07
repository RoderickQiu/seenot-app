package com.seenot.debugger

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.types.file
import kotlinx.coroutines.runBlocking
import java.io.File

class AiDebugger : CliktCommand(name = "ai-debugger") {
    override fun run() = Unit
}

class IntentDebugCommand : CliktCommand(name = "intent", help = "调试意图解析器") {
    private val input by option("-i", "--input", help = "用户输入语句").default("")
    private val file by option("-f", "--file", help = "批量测试文件").file()
    private val interactive by option("--interactive", help = "交互模式")
    private val packageName by option("-p", "--package", help = "应用包名").default("com.example.app")
    private val appName by option("-a", "--app", help = "应用名称").default("测试应用")

    override fun run() = runBlocking {
        val debugger = IntentParserDebugger()

        when {
            file != null -> {
                echo("📂 批量测试模式: ${file!!.absolutePath}")
                debugger.batchTest(file!!, packageName, appName)
            }
            input.isNotEmpty() -> {
                echo("🎯 单句测试: $input")
                debugger.singleTest(input, packageName, appName)
            }
            else -> {
                echo("💬 交互模式 (输入 'exit' 退出)")
                debugger.interactiveMode(packageName, appName)
            }
        }
    }
}

class ScreenDebugCommand : CliktCommand(name = "screen", help = "调试屏幕分析器") {
    private val image by option("-i", "--image", help = "截图路径").file(mustExist = true)
    private val constraint by option("-c", "--constraint", help = "约束描述").default("购物车")
    private val type by option("-t", "--type", help = "约束类型 (ALLOW/DENY/TIME_CAP)").default("DENY")
    private val dir by option("-d", "--dir", help = "批量测试目录").file()

    override fun run() = runBlocking {
        val debugger = ScreenAnalyzerDebugger()

        when {
            dir != null -> {
                echo("📂 批量测试目录: ${dir!!.absolutePath}")
                echo("🎯 约束类型: $type")
                echo("🎯 约束描述: $constraint")
                debugger.batchTest(dir!!, constraint, type)
            }
            image != null -> {
                echo("🖼️  单图测试: ${image!!.name}")
                echo("🎯 约束类型: $type")
                echo("🎯 约束描述: $constraint")
                debugger.singleTest(image!!, constraint, type)
            }
            else -> {
                echo("❌ 请指定 --image 或 --dir")
            }
        }
    }
}

fun main(args: Array<String>) = AiDebugger()
    .subcommands(IntentDebugCommand(), ScreenDebugCommand())
    .main(args)
