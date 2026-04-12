[中文](README.md)

# AiChat Mod (Fabric)

Empower Minecraft players to interact with locally deployed AI models through in-game commands! This mod supports Ollama, LM Studio, and OpenAI-compatible APIs (such as DeepSeek, Zhipu AI, Qwen, etc.).

Thanks to [DeepSeek Chat](https://chat.deepseek.com/), [Qwen](https://tongyi.aliyun.com/) and [Zhipu AI](https://www.zhipuai.cn/) for helping me solve various problems and writing most of the network communication code and other code.

---

## 🛠️ Prerequisites

- **Must have an AI service installed**, choose one of the following:
  - **[Ollama](https://ollama.ai/)**: Run `ollama serve` in your terminal to start the local service, use `ollama pull <model-name>` to download models (e.g., `llama3`)
  - **[LM Studio](https://lmstudio.ai/)**: Load a model in the GUI and start the Local Server
  - **Third-party APIs**: Such as DeepSeek, Zhipu AI, Qwen, etc., requires a valid API Key

---

## ✨ Key Features

1. **Multi-Platform Support**
   - Ollama native API support
   - LM Studio local server support
   - OpenAI-compatible third-party APIs (DeepSeek, Zhipu, Qwen, etc.)

2. **Model Management**
   - View and switch downloaded models in-game
   - Automatic model list refresh

3. **Real-time Chat**
   - Send messages starting with `ai ` to receive AI-generated responses
   - Context memory support for coherent conversations

4. **Advanced Features**
   - Deep thinking toggle (supported by some models)
   - Online search function (Ollama native API only)
   - Customizable context rounds
   - Chat history management

---

## 📜 Command List

### Basic Commands

| Command | Description |
|---------|-------------|
| `/ai list` | List all available models |
| `/ai model <model-name>` | Switch to a specific model |
| `/ai refresh` | Refresh the model list |

### API Configuration Commands

| Command | Description |
|---------|-------------|
| `/ai api [URL]` | View or set API address |
| `/ai provider [type]` | Set API provider type (ollama/openai/lmstudio) |
| `/ai key [key]` | View or set API Key (required for third-party APIs) |

### Feature Toggle Commands

| Command | Description |
|---------|-------------|
| `/ai think [on\|off]` | Deep thinking toggle (supported by some models) |
| `/ai search [on\|off]` | Online search toggle (Ollama native API only) |
| `/ai context [rounds]` | Set context memory rounds (0-50) |
| `/ai history [clear]` | View or clear chat history |

### Service Management Commands (Ollama Only)

| Command | Description |
|---------|-------------|
| `/ai serve` | Start the local Ollama service |
| `/ai ps` | View active model processes |

---

## 🎮 Usage Examples

### 1. Configure API Address

**Using Local Ollama:**
```
/ai api http://localhost:11434/api/generate
```

**Using LM Studio:**
```
/ai api http://localhost:1234/api/v1/chat
```

**Using DeepSeek:**
```
/ai api https://api.deepseek.com/v1/chat/completions
/ai key your-api-key-here
```

### 2. Set Model

```
/ai model llama3
```
Switch to the "llama3" model.

### 3. Send Request

Type in chat:
```
ai How to build a house in Minecraft?
```
The AI will respond with an answer in-game.

### 4. Enable Deep Thinking (if supported by model)

```
/ai think on
```

### 5. Adjust Context Memory

```
/ai context 10
```
Set to retain 10 rounds of conversation history.

---

## ⚠️ Important Notes

- **Ollama Users**: Ensure Ollama is properly installed and models are downloaded via CLI before first use
- **LM Studio Users**: Make sure you've loaded a model and started the Local Server in the GUI
- **Third-party API Users**: Need a valid API Key
- Check if the API service is running if you encounter timeout errors
- Response speed depends on local hardware performance or network conditions
- Different API providers may support different features (e.g., deep thinking, online search)

---

## 🔧 FAQ

**Q: Getting "Failed to fetch model list" error?**
A: Check if the API address is correct and ensure the AI service is running. LM Studio users should confirm the server is started in the "Local Server" tab.

**Q: How to use third-party APIs?**
A: Use `/ai api <URL>` to set the API address, then use `/ai key <key>` to set the API Key, and finally use `/ai provider openai` to set OpenAI-compatible mode.

**Q: How many context rounds should I set?**
A: The default 5 rounds is suitable for most scenarios. For longer conversation memory, you can increase to 10-20 rounds, but this will increase token consumption.
