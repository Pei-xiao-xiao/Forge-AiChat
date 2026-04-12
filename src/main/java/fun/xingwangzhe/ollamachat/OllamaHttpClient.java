package fun.xingwangzhe.ollamachat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;

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
    private static final String OLLAMA_API_URL = "http://localhost:11434/api/generate";
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
     * @param userInput 用户输入
     * @param playerUuid 玩家 UUID（用于获取历史上下文）
     * @param callback 回调函数，接收 AIResponse
     */
    public static void handleAIRequestAsync(String userInput, UUID playerUuid, AIResponseCallback callback) {
        String currentModel = OllamaModelManager.getCurrentModel();
        if (currentModel.isEmpty()) {
            callback.onError(Text.translatable("command.ollama.error.no_model_selected").getString());
            return;
        }

        activeRequests.incrementAndGet();

        // 构建带上下文的 prompt
        String promptWithContext = buildPromptWithContext(userInput, playerUuid);

        JsonObject requestJson = new JsonObject();
        requestJson.addProperty("model", currentModel);
        requestJson.addProperty("prompt", promptWithContext);
        requestJson.addProperty("stream", false);
        requestJson.addProperty("num_predict", 60);

        // 添加 think 和 search 参数
        if (OllamaModelManager.isThinkEnabled()) {
            requestJson.addProperty("think", true);
        }
        if (OllamaModelManager.isSearchEnabled()) {
            requestJson.addProperty("search", true);
        }

        String requestBody = requestJson.toString();

        // 动态超时：深度思考时延长到 180 秒
        int timeoutSeconds = OllamaModelManager.isThinkEnabled() ? 180 : 60;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OLLAMA_API_URL))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        requestExecutor.submit(() -> {
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, throwable) -> {
                    try {
                        if (throwable != null) {
                            throwable.printStackTrace();
                            if (throwable instanceof TimeoutException) {
                                callback.onError(Text.translatable("command.ollama.error.timeout").getString());
                            } else {
                                callback.onError(Text.translatable("command.ollama.error.generic").getString());
                            }
                        } else if (response.statusCode() == 200) {
                            AIResponse aiResponse = parseResponse(response.body());
                            // 记录到聊天历史
                            OllamaChatHistory.addMessage(playerUuid, "player", userInput);
                            OllamaChatHistory.addMessage(playerUuid, "ai", aiResponse.response);
                            callback.onSuccess(aiResponse);
                        } else {
                            callback.onError(Text.translatable("command.ollama.error.http_code", response.statusCode()).getString());
                        }
                    } finally {
                        activeRequests.decrementAndGet();
                    }
                });
        });
    }

    /**
     * 构建带历史上下文的 prompt
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
