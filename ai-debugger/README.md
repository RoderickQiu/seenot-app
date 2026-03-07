# AI Debugger - 快速调试工具

独立的命令行工具，用于快速调试AI算法，无需打包到真机。

## 快速开始

### 1. 配置API Key

确保项目根目录的 `local.properties` 包含你的DashScope API Key：
```
DASHSCOPE_API_KEY=sk-your-api-key-here
```

或设置环境变量：
```bash
export DASHSCOPE_API_KEY=sk-your-api-key-here
```

### 2. 运行调试

```bash
cd ai-debugger

# 意图解析 - 交互模式
../gradlew :ai-debugger:run --args="intent" --console=plain

# 意图解析 - 单句测试
../gradlew :ai-debugger:run --args="intent -i '我想刷小红书10分钟'" --console=plain

# 意图解析 - 批量测试
../gradlew :ai-debugger:run --args="intent -f test-data/utterances.txt" --console=plain

# 屏幕分析 - 单图测试
../gradlew :ai-debugger:run --args="screen -i test-data/screenshots/shopping.png -c '购物车'" --console=plain

# 屏幕分析 - 批量测试
../gradlew :ai-debugger:run --args="screen -d test-data/screenshots -c '直播间'" --console=plain
```

## 典型工作流程

### 场景1：调试意图解析prompt

1. **修改prompt**
   ```bash
   # 编辑这个文件
   vim src/main/kotlin/com/seenot/debugger/IntentParserDebugger.kt
   # 找到 systemPrompt 变量，修改提示词
   ```

2. **快速测试**
   ```bash
   ../gradlew :ai-debugger:run --args="intent" --console=plain
   > 我想刷抖音但只看学习视频
   ```

3. **批量验证**
   ```bash
   ../gradlew :ai-debugger:run --args="intent -f test-data/utterances.txt" --console=plain
   # 查看成功率和详细结果
   ```

4. **同步到app**
   ```bash
   # 确认效果后，复制prompt到Android代码
   # ../app/src/main/java/com/seenot/app/ai/parser/IntentParser.kt
   ```

### 场景2：测试新的约束类型

1. **添加测试用例**
   ```bash
   echo "打开B站只看技术区" >> test-data/utterances.txt
   echo "刷知乎但不要看热榜" >> test-data/utterances.txt
   ```

2. **运行测试**
   ```bash
   ../gradlew :ai-debugger:run --args="intent -f test-data/utterances.txt" --console=plain
   ```

3. **查看结果**
   ```bash
   # 自动生成的JSON报告
   cat results/test-results-*.json | jq
   ```

### 场景3：对比不同版本

```bash
# 版本A
../gradlew :ai-debugger:run --args="intent -f test-data/utterances.txt" --console=plain
# 结果保存在 results/test-results-{timestamp}.json

# 修改prompt后测试版本B
../gradlew :ai-debugger:run --args="intent -f test-data/utterances.txt" --console=plain

# 对比两个版本
diff results/test-results-*.json
```

## 项目结构

```
ai-debugger/
├── build.gradle.kts                    # Gradle配置
├── src/main/kotlin/com/seenot/debugger/
│   ├── Main.kt                         # CLI入口
│   ├── IntentParserDebugger.kt        # 意图解析调试器（修改这里的prompt）
│   └── ScreenAnalyzerDebugger.kt      # 屏幕分析调试器
├── test-data/
│   ├── utterances.txt                  # 测试语句（添加你的测试用例）
│   └── screenshots/                    # 测试截图目录
└── results/                            # 测试结果输出目录（已gitignore）
    └── test-results-*.json             # 批量测试结果
```

## 优势

- ⚡ **2-3秒反馈** - 修改prompt后立即看到效果
- 📊 **批量测试** - 一次运行测试所有用例，自动生成JSON报告
- 🔄 **快速迭代** - 无需编译打包到真机
- 💾 **结果记录** - 自动保存详细结果，方便对比
- 🎯 **专注算法** - 不用管Android权限、生命周期等

## 测试数据准备

### 意图解析测试

编辑 `test-data/utterances.txt`，添加测试语句：
```
我想刷小红书10分钟
打开淘宝但不要看直播
微信只看工作群
```

### 屏幕分析测试

1. 从真机截图或模拟器导出截图
2. 放到 `test-data/screenshots/` 目录
3. 运行批量测试

```bash
# 从真机导出截图
adb pull /sdcard/Pictures/Screenshots/ test-data/screenshots/

# 批量测试
../gradlew :ai-debugger:run --args="screen -d test-data/screenshots -c '购物车'" --console=plain
```

## 注意事项

- 首次运行需要下载依赖，可能需要几分钟
- API调用会产生费用，批量测试前注意用例数量
- 测试结果JSON文件会累积在 `results/` 目录，定期清理
- 所有测试结果已在 `.gitignore` 中排除，不会提交到仓库
