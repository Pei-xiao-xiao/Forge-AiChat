package fun.ollamachat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class OllamaHttpClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("OllamaChat");
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final AtomicInteger activeRequests = new AtomicInteger(0);
    private static final ExecutorService requestExecutor = Executors.newCachedThreadPool();

    /**
     * AI 响应结果，包含回复文本和思考过程
     */
    public static class AIResponse {
        public final String response;
        public final String thinking;

        public AIResponse(String response, String thinking) {
            this.response = response;
            this.thinking = thinking;
        }

        public boolean hasThinking() {
            return thinking != null && !thinking.isEmpty();
        }
    }

    /**
     * 异步发送 AI 请求，结果通过 callback 返回
     * 根据 OllamaConfig.getApiProvider() 自动选择请求格式：
     * - OLLAMA: /api/generate 格式
     * - OPENAI: /v1/chat/completions 格式
     */
    public static void handleAIRequestAsync(String userInput, UUID playerUuid, AIResponseCallback callback) {
        String currentModel = OllamaModelManager.getCurrentModel();
        if (currentModel.isEmpty()) {
            callback.onError(Text.translatable("command.ollama.error.no_model_selected").getString());
            return;
        }

        activeRequests.incrementAndGet();

        OllamaConfig.ApiProvider provider = OllamaConfig.getApiProvider();
        String requestBody;
        int timeoutSeconds;

        if (provider == OllamaConfig.ApiProvider.OPENAI) {
            requestBody = buildOpenAIRequestBody(userInput, playerUuid, currentModel);
            timeoutSeconds = OllamaModelManager.isThinkEnabled() ? 180 : 60;
        } else {
            requestBody = buildOllamaRequestBody(userInput, playerUuid, currentModel);
            timeoutSeconds = OllamaModelManager.isThinkEnabled() ? 180 : 60;
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(OllamaConfig.getApiUrl()))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));

        // 第三方 API 需要 Authorization header
        if (provider == OllamaConfig.ApiProvider.OPENAI && !OllamaConfig.getApiKey().isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + OllamaConfig.getApiKey());
        }

        HttpRequest request = requestBuilder.build();
        final OllamaConfig.ApiProvider finalProvider = provider;

        requestExecutor.submit(() -> {
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, throwable) -> {
                    try {
                        if (throwable != null) {
                            LOGGER.error("AI request failed", throwable);
                            if (throwable instanceof TimeoutException) {
                                callback.onError(Text.translatable("command.ollama.error.timeout").getString());
                            } else {
                                callback.onError(Text.translatable("command.ollama.error.generic").getString());
                            }
                        } else if (response.statusCode() == 200) {
                            AIResponse aiResponse;
                            if (finalProvider == OllamaConfig.ApiProvider.OPENAI) {
                                aiResponse = parseOpenAIResponse(response.body());
                            } else {
                                aiResponse = parseResponse(response.body());
                            }
                            // 记录到聊天历史
                            OllamaChatHistory.addMessage(playerUuid, "player", userInput);
                            OllamaChatHistory.addMessage(playerUuid, "ai", aiResponse.response);
                            callback.onSuccess(aiResponse);
                        } else {
                            // 尝试提取错误信息
                            String errorDetail = "";
                            try {
                                JsonObject errorObj = JsonParser.parseString(response.body()).getAsJsonObject();
                                if (errorObj.has("error")) {
                                    JsonObject err = errorObj.getAsJsonObject("error");
                                    errorDetail = err.has("message") ? err.get("message").getAsString() : errorObj.get("error").toString();
                                }
                            } catch (Exception ignored) {}
                            if (!errorDetail.isEmpty()) {
                                callback.onError(Text.translatable("command.ollama.error.http_with_detail", response.statusCode(), errorDetail).getString());
                            } else {
                                callback.onError(Text.translatable("command.ollama.error.http_code", response.statusCode()).getString());
                            }
                        }
                    } finally {
                        activeRequests.decrementAndGet();
                    }
                });
        });
    }

    /**
     * 构建 Ollama 格式的请求体 (/api/generate)
     */
    private static String buildOllamaRequestBody(String userInput, UUID playerUuid, String model) {
        String promptWithContext = buildPromptWithContext(userInput, playerUuid);

        JsonObject requestJson = new JsonObject();
        requestJson.addProperty("model", model);
        requestJson.addProperty("prompt", promptWithContext);
        requestJson.addProperty("stream", false);
        requestJson.addProperty("num_predict", 60);

        if (OllamaModelManager.isThinkEnabled()) {
            requestJson.addProperty("think", true);
        }
        if (OllamaModelManager.isSearchEnabled()) {
            requestJson.addProperty("search", true);
        }

        return requestJson.toString();
    }

    /**
     * 构建 OpenAI 兼容格式的请求体 (/v1/chat/completions)
     * 支持 DeepSeek、智谱、通义千问、OpenAI 等第三方 API
     */
    private static String buildOpenAIRequestBody(String userInput, UUID playerUuid, String model) {
        JsonObject requestJson = new JsonObject();
        requestJson.addProperty("model", model);
        requestJson.addProperty("stream", false);

        // 构建 messages 数组
        JsonArray messages = new JsonArray();

        // 添加历史上下文
        int contextRounds = OllamaConfig.getContextRounds();
        if (contextRounds > 0) {
            List<OllamaChatHistory.ChatRecord> history = OllamaChatHistory.getRecentHistory(playerUuid, contextRounds * 2);
            for (OllamaChatHistory.ChatRecord record : history) {
                JsonObject msg = new JsonObject();
                if ("player".equals(record.role)) {
                    msg.addProperty("role", "user");
                } else {
                    msg.addProperty("role", "assistant");
                }
                msg.addProperty("content", record.content);
                messages.add(msg);
            }
        }

        // 添加当前用户输入
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userInput);
        messages.add(userMsg);

        requestJson.add("messages", messages);

        // OpenAI 兼容接口的额外参数
        // 一些第三方接口支持 search/reasoning 相关参数，这里通过扩展字段传递
        // 大多数 OpenAI 兼容接口会忽略不认识的字段
        if (OllamaModelManager.isSearchEnabled()) {
            // DeepSeek 等支持 search 参数
            requestJson.addProperty("search_enabled", true);
        }

        return requestJson.toString();
    }

    /**
     * 构建带历史上下文的 prompt（Ollama 格式用）
     */
    private static String buildPromptWithContext(String userInput, UUID playerUuid) {
        int contextRounds = OllamaConfig.getContextRounds();
        if (contextRounds <= 0) {
            return userInput;
        }

        List<OllamaChatHistory.ChatRecord> history = OllamaChatHistory.getRecentHistory(playerUuid, contextRounds * 2);
        if (history.isEmpty()) {
            return userInput;
        }

        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("The following is a conversation history:\n\n");
        for (OllamaChatHistory.ChatRecord record : history) {
            if ("player".equals(record.role)) {
                promptBuilder.append("[Player]: ").append(record.content).append("\n");
            } else {
                promptBuilder.append("[AI]: ").append(record.content).append("\n");
            }
        }
        promptBuilder.append("\n[Player]: ").append(userInput).append("\n[AI]:");
        return promptBuilder.toString();
    }

    /**
     * 解析 Ollama API 响应，提取 response 和 thinking 字段
     */
    public static AIResponse parseResponse(String responseBody) {
        try {
            JsonObject jsonObject = JsonParser.parseString(responseBody).getAsJsonObject();
            String responseText = "";
            String thinkingText = null;

            if (jsonObject.has("response")) {
                responseText = jsonObject.get("response").getAsString();
                responseText = responseText
                        .replaceAll("<[^>]*>", "")
                        .replace("\n", " ")
                        .replaceAll("\\s{2,}", " ")
                        .trim();
                if (responseText.length() > 500) {
                    responseText = responseText.substring(0, 500) + "...";
                }
            }

            // 提取思考过程
            if (jsonObject.has("thinking")) {
                thinkingText = jsonObject.get("thinking").getAsString();
                thinkingText = thinkingText
                        .replaceAll("<[^>]*>", "")
                        .replaceAll("\\s{2,}", " ")
                        .trim();
                if (thinkingText.length() > 500) {
                    thinkingText = thinkingText.substring(0, 500) + "...";
                }
            }

            return new AIResponse(responseText, thinkingText);
        } catch (Exception e) {
            return new AIResponse(Text.translatable("command.ollama.error.parse_failed").getString(), null);
        }
    }

    /**
     * 解析 OpenAI 兼容格式的响应 (/v1/chat/completions)
     * 响应格式：{"choices": [{"message": {"content": "...", "reasoning_content": "..."}}]}
     */
    public static AIResponse parseOpenAIResponse(String responseBody) {
        try {
            JsonObject jsonObject = JsonParser.parseString(responseBody).getAsJsonObject();
            String responseText = "";
            String thinkingText = null;

            if (jsonObject.has("choices")) {
                JsonArray choices = jsonObject.getAsJsonArray("choices");
                if (choices.size() > 0) {
                    JsonObject firstChoice = choices.get(0).getAsJsonObject();
                    if (firstChoice.has("message")) {
                        JsonObject message = firstChoice.getAsJsonObject("message");

                        // 提取回复内容
                        if (message.has("content") && !message.get("content").isJsonNull()) {
                            responseText = message.get("content").getAsString();
                            responseText = responseText
                                    .replaceAll("<[^>]*>", "")
                                    .replace("\n", " ")
                                    .replaceAll("\\s{2,}", " ")
                                    .trim();
                            if (responseText.length() > 500) {
                                responseText = responseText.substring(0, 500) + "...";
                            }
                        }

                        // 提取思考过程（DeepSeek 使用 reasoning_content 字段）
                        if (message.has("reasoning_content") && !message.get("reasoning_content").isJsonNull()) {
                            thinkingText = message.get("reasoning_content").getAsString();
                            thinkingText = thinkingText
                                    .replaceAll("<[^>]*>", "")
                                    .replaceAll("\\s{2,}", " ")
                                    .trim();
                            if (thinkingText.length() > 500) {
                                thinkingText = thinkingText.substring(0, 500) + "...";
                            }
                        }
                        // 兼容其他可能用 thinking_content 字段的 API
                        else if (message.has("thinking_content") && !message.get("thinking_content").isJsonNull()) {
                            thinkingText = message.get("thinking_content").getAsString();
                            thinkingText = thinkingText
                                    .replaceAll("<[^>]*>", "")
                                    .replaceAll("\\s{2,}", " ")
                                    .trim();
                            if (thinkingText.length() > 500) {
                                thinkingText = thinkingText.substring(0, 500) + "...";
                            }
                        }
                    }
                }
            }

            return new AIResponse(responseText, thinkingText);
        } catch (Exception e) {
            return new AIResponse(Text.translatable("command.ollama.error.parse_failed").getString(), null);
        }
    }

    /**
     * 构建带 hover tooltip 的富文本消息
     * 鼠标悬停时显示思考过程
     */
    public static Text buildAIText(AIResponse aiResponse) {
        Text mainText = Text.literal("[AI] " + aiResponse.response);
        if (aiResponse.hasThinking()) {
            Text thinkingText = Text.literal(aiResponse.thinking);
            mainText.getStyle().withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, thinkingText));
            // 需要用 withStyle 来应用 hoverEvent
            return Text.literal("[AI] " + aiResponse.response)
                    .styled(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, thinkingText)));
        }
        return mainText;
    }

    public static int getActiveRequests() {
        return activeRequests.get();
    }

    /**
     * AI 响应回调接口
     */
    public interface AIResponseCallback {
        void onSuccess(AIResponse response);
        void onError(String error);
    }
}
