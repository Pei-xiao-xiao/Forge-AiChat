# 运行说明

## 前置要求

在运行此项目之前，您需要安装以下软件:

### 1. Java 17
此项目需要 Java 17 才能运行。

**下载和安装:**
- 访问 [Adoptium](https://adoptium.net/) 或 [Oracle JDK](https://www.oracle.com/java/technologies/downloads/)
- 下载并安装 Java 17 (JDK 17)
- 安装后，设置 JAVA_HOME 环境变量

### 2. Gradle 8.11.1
您需要安装 Gradle 构建工具。

**选项 1: 使用 Gradle Wrapper (推荐)**
```bash
# 如果已安装 Java，可以直接运行
.\gradlew.bat runClient
```

**选项 2: 手动安装 Gradle**
- 访问 [Gradle 官网](https://gradle.org/install/)
- 下载 Gradle 8.11.1
- 解压并添加到系统 PATH

### 3. Ollama (用于 AI 功能)
- 访问 [Ollama 官网](https://ollama.ai/)
- 安装 Ollama
- 运行 `ollama pull llama3` 下载模型

## 运行步骤

### 方法 1: 如果您已经安装了 Java 和 Gradle

1. 打开 PowerShell 或命令提示符
2. 进入项目目录:
   ```bash
   cd F:\Forge-ollamachat-main
   ```
3. 运行:
   ```bash
   gradle runClient
   ```

### 方法 2: 使用 Gradle Wrapper (需要先有 Java)

1. 打开 PowerShell
2. 进入项目目录
3. 运行:
   ```powershell
   .\gradlew.bat runClient
   ```

### 方法 3: 手动构建并运行 Minecraft

1. 构建项目:
   ```bash
   gradle build
   ```
2. 构建完成后，在 `build/libs` 目录找到生成的 JAR 文件
3. 将 JAR 文件放入 Minecraft 的 mods 文件夹
4. 安装 Fabric Loader 和 Fabric API
5. 启动 Minecraft

## 常见问题

### 问题：找不到 java 命令
**解决:** 安装 Java 17 并设置 JAVA_HOME 环境变量

### 问题：找不到 gradle 命令
**解决:** 安装 Gradle 或使用 Gradle Wrapper

### 问题：网络连接错误
**解决:** 检查您的网络连接，或配置代理

## 游戏内使用

启动 Minecraft 后，您可以使用以下命令:

- `/ollama list` - 列出所有模型
- `/ollama model <模型名>` - 切换到指定模型
- `/ollama serve` - 启动 Ollama 服务
- `/ollama ps` - 查看活跃进程

发送消息时以 `ai ` 开头即可与 AI 对话，例如:
```
ai 如何在 Minecraft 中建造房子？
```
