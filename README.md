[English](README_EN.md)

# AiChat 模组 (Fabric)

让 Minecraft 玩家通过游戏内命令与本地部署的 AI 模型交互！本模组支持 Ollama、LM Studio 以及 OpenAI 兼容的 API（如 DeepSeek、智谱 AI、通义千问等）。

感谢 [DeepSeek Chat](https://chat.deepseek.com/)、[通义千问](https://tongyi.aliyun.com/) 和 [智谱 AI](https://www.zhipuai.cn/) 帮我解决疑难杂症，并编写大部分网络通信代码和其他代码。

---

## 🛠️ 前置要求

- **必须预先安装 AI 服务**，可选择以下任一方案：
  - **[Ollama](https://ollama.ai/)**：在命令行中运行 `ollama serve` 启动本地服务，使用 `ollama pull <模型名>` 下载所需模型（如 `llama3`）
  - **[LM Studio](https://lmstudio.ai/)**：在图形界面中加载模型并启动 Local Server
  - **第三方 API**：如 DeepSeek、智谱 AI、通义千问等，需要有效的 API Key

---

## ✨ 核心功能

1. **多平台支持**
   - 支持 Ollama 原生 API
   - 支持 LM Studio 本地服务
   - 支持 OpenAI 兼容的第三方 API（DeepSeek、智谱、通义千问等）

2. **模型管理**
   - 在游戏中查看和切换已下载的模型
   - 自动刷新模型列表

3. **即时对话**
   - 发送以 `ai ` 开头的消息，AI 将生成响应
   - 支持上下文记忆，保持对话连贯性

4. **高级功能**
   - 深度思考开关（部分模型支持）
   - 在线搜索功能（Ollama 原生 API 支持）
   - 自定义上下文轮数
   - 聊天历史管理

---

## 📜 命令列表

### 基础命令

| 命令 | 功能描述 |
|------|----------|
| `/ai list` | 列出所有可用模型 |
| `/ai model <模型名称>` | 切换当前使用的模型 |
| `/ai refresh` | 刷新模型列表 |

### API 配置命令

| 命令 | 功能描述 |
|------|----------|
| `/ai api [URL]` | 查看或设置 API 地址 |
| `/ai provider [类型]` | 设置 API 提供者类型（ollama/openai/lmstudio） |
| `/ai key [密钥]` | 查看或设置 API Key（第三方 API 需要） |

### 功能开关命令

| 命令 | 功能描述 |
|------|----------|
| `/ai think [on\|off]` | 深度思考开关（部分模型支持） |
| `/ai search [on\|off]` | 在线搜索开关（仅 Ollama 原生 API 支持） |
| `/ai context [轮数]` | 设置上下文记忆轮数（0-50） |
| `/ai history [clear]` | 查看或清除聊天历史 |

### 服务管理命令（仅 Ollama）

| 命令 | 功能描述 |
|------|----------|
| `/ai serve` | 启动本地 Ollama 服务 |
| `/ai ps` | 查看当前运行的模型进程 |

---

## 🎮 使用示例

### 1. 配置 API 地址

**使用本地 Ollama：**
```
/ai api http://localhost:11434/api/generate
```

**使用 LM Studio：**
```
/ai api http://localhost:1234/api/v1/chat
```

**使用 DeepSeek：**
```
/ai api https://api.deepseek.com/v1/chat/completions
/ai key your-api-key-here
```

### 2. 设置模型

```
/ai model llama3
```
切换到名为 "llama3" 的模型。

### 3. 发送请求

在聊天框输入：
```
ai 怎么在 Minecraft 里造房子？
```
AI 会生成回答并以游戏消息形式返回。

### 4. 开启深度思考（如果模型支持）

```
/ai think on
```

### 5. 调整上下文记忆

```
/ai context 10
```
设置保留 10 轮对话历史。

---

## ⚠️ 注意事项

- **Ollama 用户**：首次使用前，请确保已通过命令行正确安装 Ollama 并下载模型
- **LM Studio 用户**：确保已在图形界面中加载模型并启动 Local Server
- **第三方 API 用户**：需要准备有效的 API Key
- 如果遇到超时错误，请检查 API 服务是否正常运行
- 模型响应速度取决于本地硬件性能或网络状况
- 不同 API 提供者的功能支持可能不同（如深度思考、在线搜索等）

---

## 🔧 常见问题

**Q: 提示"无法获取模型列表"？**
A: 检查 API 地址是否正确，确保 AI 服务已启动。LM Studio 用户请确认已在"Local Server"标签页启动服务器。

**Q: 如何使用第三方 API？**
A: 使用 `/ai api <URL>` 设置 API 地址，然后用 `/ai key <密钥>` 设置 API Key，最后用 `/ai provider openai` 设置为 OpenAI 兼容模式。

**Q: 上下文轮数设置多少合适？**
A: 默认 5 轮适合大多数场景。如果需要更长的对话记忆，可以增加到 10-20 轮，但会增加 token 消耗。
