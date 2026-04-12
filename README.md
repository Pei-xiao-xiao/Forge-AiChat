[中文](README_CN.md)
# OllamaChat Mod (Forge)

Empower Minecraft players to interact with locally deployed AI models through in-game commands! Built on [Ollama](https://ollama.ai/), this mod enables dynamic AI-generated conversations.

Thank [DeepSeek Chat](https://chat.deepseek.com/) for helping me solve various problems and writing most of the network communication code and other code.
---

## 🛠️ Prerequisites
- **Must have [Ollama](https://ollama.ai/) installed locally** with at least one model deployed.
- Run `ollama serve` in your terminal to start the local service.
- Download models using `ollama pull <model-name>` (e.g., `llama3`).

---

## ✨ Key Features
1. **Model Switching**
   Seamlessly switch between downloaded models in-game.
2. **Real-time Chat**
   Send messages starting with `ai ` to receive AI-generated responses.
3. **Service Control**
   Manage Ollama service status directly via commands.

---

## 📜 Command List
| Command                       | Description                  |
|-------------------------------|------------------------------|
| `/ollama list`                | List all downloaded models   |
| `/ollama model <model-name>`  | Switch to a specific model   |
| `/ollama serve`               | Start the Ollama service     |
| `/ollama ps`                  | View active model processes  |

---

## 🎮 Usage Examples
1. **Set Model**
   `/ollama model llama3`
   *Switch to the "llama3" model.*

2. **Send Request**
   Type in chat:
   `ai How to build a house in Minecraft?`
   The AI will respond with an answer in-game.

---

## ⚠️ Important Notes
- Ensure Ollama is properly installed and models are downloaded via CLI before use.
- Check if the Ollama service is running if you encounter timeout errors.
- Response speed depends on your local hardware performance.
