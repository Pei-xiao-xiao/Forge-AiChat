package fun.aichat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
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

public class AiHttpClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("AiChat");
    
    // 主客户端：自动协商 HTTP 版本（优先 HTTP/2，兼容 HTTP/1.1）
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    
    // 回退客户端：强制 HTTP/1.1，用于不支持 HTTP/2 的本地服务（如 LM Studio）
    private static final HttpClient httpClientFallback = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
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
     * 流式响应回调接口
     */
    public interface AIStreamCallback {
        /** 收到增量文本时调用，在主线程或请求线程中调用 */
        void onToken(String token);
        /** 思考过程增量 */
        void onThinkingToken(String token);
        /** 流式响应完成，提供完整结果 */
        void onComplete(AIResponse response);
        /** 发生错误 */
        void onError(String error);
    }

    /**
     * 保留兼容性的非流式回调（内部转换为流式）
     */
    public interface AIResponseCallback {
        void onSuccess(AIResponse response);
        void onError(String error);
    }

    /**
     * 异步发送 AI 请求（流式），结果通过 AIStreamCallback 增量返回
     */
    public static void handleAIRequestStream(String userInput, UUID playerUuid, AIStreamCallback callback) {
        String currentModel = AiModelManager.getCurrentModel();
        if (currentModel.isEmpty()) {
            callback.onError(Text.translatable("command.ai.error.no_model_selected").getString());
            return;
        }

        activeRequests.incrementAndGet();

        AiConfig.ApiProvider provider = AiConfig.getApiProvider();
        String requestBody;
        int timeoutSeconds;

        boolean lmStudioUseStatefulApi = false;
        if (provider == AiConfig.ApiProvider.LMSTUDIO) {
            String apiUrl = AiConfig.getApiUrl().toLowerCase();
            lmStudioUseStatefulApi = apiUrl.contains("/api/v1/") || apiUrl.contains("/v1/responses");
        }

        if (provider == AiConfig.ApiProvider.LMSTUDIO && lmStudioUseStatefulApi) {
            requestBody = buildLMStudioRequestBody(userInput, playerUuid, currentModel, false);
            timeoutSeconds = AiModelManager.isThinkEnabled() ? 180 : 60;
        } else if (provider == AiConfig.ApiProvider.OPENAI ||
                   (provider == AiConfig.ApiProvider.LMSTUDIO && !lmStudioUseStatefulApi)) {
            requestBody = buildOpenAIRequestBody(userInput, playerUuid, currentModel, true);
            timeoutSeconds = AiModelManager.isThinkEnabled() ? 180 : 60;
        } else {
            requestBody = buildOllamaRequestBody(userInput, playerUuid, currentModel, true);
            timeoutSeconds = AiModelManager.isThinkEnabled() ? 180 : 60;
        }

        String chatUrl = resolveChatUrl(AiConfig.getApiUrl(), provider);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(chatUrl))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));

        if ((provider == AiConfig.ApiProvider.OPENAI || provider == AiConfig.ApiProvider.LMSTUDIO)
                && !AiConfig.getApiKey().isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + AiConfig.getApiKey());
        }

        HttpRequest request = requestBuilder.build();
        final AiConfig.ApiProvider finalProvider = provider;
        final boolean finalLmStudioStateful = lmStudioUseStatefulApi;

        requestExecutor.submit(() -> {
            try {
                HttpResponse<java.io.InputStream> response;
                try {
                    // 先尝试自动协商版本（优先 HTTP/2）
                    response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                } catch (java.net.http.HttpTimeoutException e) {
                    // HTTP/2 协商超时，回退到 HTTP/1.1
                    LOGGER.info("聊天请求 HTTP/2 协商超时，回退到 HTTP/1.1: {}", chatUrl);
                    HttpRequest fallbackRequest = HttpRequest.newBuilder()
                            .uri(URI.create(chatUrl))
                            .timeout(Duration.ofSeconds(timeoutSeconds))
                            .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                            .header("Content-Type", "application/json")
                            .header("Authorization", request.headers().firstValue("Authorization").orElse(""))
                            .build();
                    response = httpClientFallback.send(fallbackRequest, HttpResponse.BodyHandlers.ofInputStream());
                }

                if (response.statusCode() != 200) {
                    String errorBody = "";
                    try (BufferedReader er = new BufferedReader(
                            new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = er.readLine()) != null) sb.append(line);
                        errorBody = sb.toString();
                    } catch (Exception ignored) {}

                    String errorDetail = "";
                    try {
                        JsonObject errorObj = JsonParser.parseString(errorBody).getAsJsonObject();
                        if (errorObj.has("error")) {
                            JsonObject err = errorObj.getAsJsonObject("error");
                            errorDetail = err.has("message") ? err.get("message").getAsString() : errorObj.get("error").toString();
                        }
                    } catch (Exception ignored) {}

                    if (!errorDetail.isEmpty()) {
                        callback.onError(Text.translatable("command.ai.error.http_with_detail", response.statusCode(), errorDetail).getString());
                    } else {
                        callback.onError(Text.translatable("command.ai.error.http_code", response.statusCode()).getString());
                    }
                    return;
                }

                // 流式读取响应
                StringBuilder fullResponse = new StringBuilder();
                StringBuilder fullThinking = new StringBuilder();
                String lmStudioResponseId = null;

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isEmpty()) continue;

                        // 处理 SSE 格式：data: {...} 或纯 JSON 行
                        String jsonStr;
                        if (line.startsWith("data: ")) {
                            jsonStr = line.substring(6).trim();
                            if (jsonStr.equals("[DONE]")) break;
                        } else if (line.startsWith("{")) {
                            jsonStr = line;
                        } else {
                            continue;
                        }

                        try {
                            JsonObject chunk = JsonParser.parseString(jsonStr).getAsJsonObject();

                            if (finalProvider == AiConfig.ApiProvider.LMSTUDIO && finalLmStudioStateful) {
                                // LM Studio 有状态 API 流式解析
                                lmStudioResponseId = parseLMStudioStreamChunk(chunk, fullResponse, fullThinking, callback);
                            } else if (finalProvider == AiConfig.ApiProvider.OPENAI ||
                                       (finalProvider == AiConfig.ApiProvider.LMSTUDIO && !finalLmStudioStateful)) {
                                // OpenAI 兼容格式流式解析
                                parseOpenAIStreamChunk(chunk, fullResponse, fullThinking, callback);
                            } else {
                                // Ollama 格式流式解析
                                parseOllamaStreamChunk(chunk, fullResponse, fullThinking, callback);
                            }
                        } catch (Exception e) {
                            LOGGER.debug("Failed to parse stream chunk: {}", jsonStr);
                        }
                    }
                }

                // 保存 LM Studio response_id
                if (lmStudioResponseId != null) {
                    AiChatHistory.setResponseId(playerUuid, lmStudioResponseId);
                }

                // 流式完成，发送完整结果
                String responseText = cleanText(fullResponse.toString());
                String thinkingText = cleanThinking(fullThinking.toString());

                AiChatHistory.addMessage(playerUuid, "player", userInput);
                AiChatHistory.addMessage(playerUuid, "ai", responseText);

                callback.onComplete(new AIResponse(responseText, thinkingText));
            } catch (Exception e) {
                LOGGER.error("AI stream request failed", e);
                if (e instanceof TimeoutException || e instanceof java.net.http.HttpTimeoutException) {
                    callback.onError(Text.translatable("command.ai.error.timeout").getString());
                } else {
                    callback.onError(Text.translatable("command.ai.error.generic").getString());
                }
            } finally {
                activeRequests.decrementAndGet();
            }
        });
    }

    /**
     * 兼容旧的非流式接口（内部使用流式实现，但只通过 onSuccess 返回完整结果）
     */
    public static void handleAIRequestAsync(String userInput, UUID playerUuid, AIResponseCallback callback) {
        handleAIRequestStream(userInput, playerUuid, new AIStreamCallback() {
            @Override
            public void onToken(String token) {
                // 非流式模式忽略增量 token
            }

            @Override
            public void onThinkingToken(String token) {
                // 非流式模式忽略增量 token
            }

            @Override
            public void onComplete(AIResponse response) {
                callback.onSuccess(response);
            }

            @Override
            public void onError(String error) {
                callback.onError(error);
            }
        });
    }

    // ==================== 流式解析方法 ====================

    /**
     * 解析 Ollama 流式 chunk
     * 格式：{"response":"token","thinking":"...","done":false}
     */
    private static void parseOllamaStreamChunk(JsonObject chunk, StringBuilder fullResponse,
                                                StringBuilder fullThinking, AIStreamCallback callback) {
        // 提取增量文本
        if (chunk.has("response") && !chunk.get("response").isJsonNull()) {
            String token = chunk.get("response").getAsString();
            fullResponse.append(token);
            callback.onToken(token);
        }

        // 提取思考过程
        if (chunk.has("thinking") && !chunk.get("thinking").isJsonNull()) {
            String thinkToken = chunk.get("thinking").getAsString();
            fullThinking.append(thinkToken);
            callback.onThinkingToken(thinkToken);
        }
    }

    /**
     * 解析 OpenAI 兼容格式流式 chunk
     * 格式：{"choices":[{"delta":{"content":"token","reasoning_content":"..."}}]}
     */
    private static void parseOpenAIStreamChunk(JsonObject chunk, StringBuilder fullResponse,
                                                StringBuilder fullThinking, AIStreamCallback callback) {
        if (!chunk.has("choices")) return;
        JsonArray choices = chunk.getAsJsonArray("choices");
        if (choices.size() == 0) return;

        JsonObject firstChoice = choices.get(0).getAsJsonObject();
        if (!firstChoice.has("delta")) return;
        JsonObject delta = firstChoice.getAsJsonObject("delta");

        // 提取增量文本
        if (delta.has("content") && !delta.get("content").isJsonNull()) {
            String token = delta.get("content").getAsString();
            fullResponse.append(token);
            callback.onToken(token);
        }

        // 提取思考过程（DeepSeek reasoning_content）
        if (delta.has("reasoning_content") && !delta.get("reasoning_content").isJsonNull()) {
            String thinkToken = delta.get("reasoning_content").getAsString();
            fullThinking.append(thinkToken);
            callback.onThinkingToken(thinkToken);
        }
        // 兼容 thinking_content 字段
        if (delta.has("thinking_content") && !delta.get("thinking_content").isJsonNull()) {
            String thinkToken = delta.get("thinking_content").getAsString();
            fullThinking.append(thinkToken);
            callback.onThinkingToken(thinkToken);
        }
    }

    /**
     * 解析 LM Studio 有状态 API 流式 chunk
     * /api/v1/chat 流式格式：
     *   文本 delta: {"type":"content","delta":{"type":"text","text":"token"}}
     *   思考 delta: {"type":"reasoning.delta","delta":{"type":"text","text":"token"}}
     *   完成: {"type":"response.completed","response":{"id":"resp_xxx"}}
     *
     * /v1/responses 流式格式类似
     *
     * @return response_id（如果有的话）
     */
    private static String parseLMStudioStreamChunk(JsonObject chunk, StringBuilder fullResponse,
                                                    StringBuilder fullThinking, AIStreamCallback callback) {
        String type = chunk.has("type") ? chunk.get("type").getAsString() : "";

        if ("content".equals(type) || "response.output_text.delta".equals(type)) {
            // 文本增量
            if (chunk.has("delta")) {
                JsonObject delta = chunk.getAsJsonObject("delta");
                if (delta.has("text") && !delta.get("text").isJsonNull()) {
                    String token = delta.get("text").getAsString();
                    fullResponse.append(token);
                    callback.onToken(token);
                }
            }
        } else if ("reasoning.delta".equals(type) || "response.reasoning.delta".equals(type)) {
            // 思考增量
            if (chunk.has("delta")) {
                JsonObject delta = chunk.getAsJsonObject("delta");
                if (delta.has("text") && !delta.get("text").isJsonNull()) {
                    String thinkToken = delta.get("text").getAsString();
                    fullThinking.append(thinkToken);
                    callback.onThinkingToken(thinkToken);
                }
            }
        } else if ("response.completed".equals(type)) {
            // 完成，提取 response_id
            if (chunk.has("response")) {
                JsonObject resp = chunk.getAsJsonObject("response");
                if (resp.has("id") && !resp.get("id").isJsonNull()) {
                    return resp.get("id").getAsString();
                }
            }
        }

        // 顶层可能有 id 字段
        if (chunk.has("id") && !chunk.get("id").isJsonNull()) {
            return chunk.get("id").getAsString();
        }

        return null;
    }

    // ==================== URL 推导 ====================

    /**
     * 根据 API URL 和 Provider 类型推导实际的聊天请求 URL
     */
    private static String resolveChatUrl(String apiUrl, AiConfig.ApiProvider provider) {
        if (apiUrl == null || apiUrl.isEmpty()) return apiUrl;
        String lower = apiUrl.toLowerCase();

        if (provider == AiConfig.ApiProvider.LMSTUDIO) {
            if (lower.contains("/api/v1/") || lower.contains("/v1/responses")) return apiUrl;
            if (lower.contains("/v1/chat/completions")) return apiUrl;
            return apiUrl.replaceAll("/+$", "") + "/v1/chat/completions";
        } else if (provider == AiConfig.ApiProvider.OPENAI) {
            if (lower.contains("/v1/") || lower.contains("/chat/completions")) return apiUrl;
            return apiUrl.replaceAll("/+$", "") + "/v1/chat/completions";
        } else {
            if (lower.contains("/api/")) return apiUrl;
            return apiUrl.replaceAll("/+$", "") + "/api/generate";
        }
    }

    // ==================== 请求构建 ====================

    /**
     * 构建 Ollama 格式的请求体
     */
    private static String buildOllamaRequestBody(String userInput, UUID playerUuid, String model, boolean stream) {
        String promptWithContext = buildPromptWithContext(userInput, playerUuid);

        JsonObject requestJson = new JsonObject();
        requestJson.addProperty("model", model);
        requestJson.addProperty("prompt", promptWithContext);
        requestJson.addProperty("stream", stream);
        requestJson.addProperty("num_predict", 60);

        if (AiModelManager.isThinkEnabled()) {
            requestJson.addProperty("think", true);
        }
        if (AiModelManager.isSearchEnabled()) {
            requestJson.addProperty("search", true);
        }

        return requestJson.toString();
    }

    /**
     * 构建 OpenAI 兼容格式的请求体
     */
    private static String buildOpenAIRequestBody(String userInput, UUID playerUuid, String model, boolean stream) {
        JsonObject requestJson = new JsonObject();
        requestJson.addProperty("model", model);
        requestJson.addProperty("stream", stream);

        JsonArray messages = new JsonArray();

        int contextRounds = AiConfig.getContextRounds();
        if (contextRounds > 0) {
            List<AiChatHistory.ChatRecord> history = AiChatHistory.getRecentHistory(playerUuid, contextRounds * 2);
            for (AiChatHistory.ChatRecord record : history) {
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

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userInput);
        messages.add(userMsg);

        requestJson.add("messages", messages);

        if (AiModelManager.isThinkEnabled()) {
            // OpenAI 兼容格式的思考/推理参数
            // 兼容：DeepSeek (自动支持)、OpenAI o1/o3 (reasoning_effort)、智谱GLM等
            requestJson.addProperty("reasoning_effort", "high");
        }

        if (AiModelManager.isSearchEnabled()) {
            // 使用 tools 格式添加联网搜索支持
            // 兼容：OpenAI (web_search_preview)、智谱GLM (web_search)、通义千问等
            JsonArray tools = new JsonArray();
            JsonObject searchTool = new JsonObject();
            searchTool.addProperty("type", "web_search");
            JsonObject searchConfig = new JsonObject();
            searchConfig.addProperty("enable", true);
            searchTool.add("web_search", searchConfig);
            tools.add(searchTool);
            requestJson.add("tools", tools);
        }

        return requestJson.toString();
    }

    /**
     * 构建 LM Studio 有状态格式的请求体
     */
    private static String buildLMStudioRequestBody(String userInput, UUID playerUuid, String model, boolean stream) {
        JsonObject requestJson = new JsonObject();
        requestJson.addProperty("model", model);
        requestJson.addProperty("input", userInput);
        requestJson.addProperty("stream", stream);

        String previousResponseId = AiChatHistory.getResponseId(playerUuid);
        if (previousResponseId != null && !previousResponseId.isEmpty()) {
            requestJson.addProperty("previous_response_id", previousResponseId);
        }

        String apiUrl = AiConfig.getApiUrl().toLowerCase();
        boolean isThinkEnabled = AiModelManager.isThinkEnabled();

        if (apiUrl.contains("/v1/responses")) {
            if (isThinkEnabled) {
                JsonObject reasoning = new JsonObject();
                reasoning.addProperty("effort", "high");
                requestJson.add("reasoning", reasoning);
            }
            // /v1/responses 端点搜索支持
            if (AiModelManager.isSearchEnabled()) {
                JsonArray tools = new JsonArray();
                JsonObject searchTool = new JsonObject();
                searchTool.addProperty("type", "web_search_preview");
                tools.add(searchTool);
                requestJson.add("tools", tools);
            }
        } else {
            requestJson.addProperty("store", true);
            if (isThinkEnabled) {
                requestJson.addProperty("reasoning", "high");
            }
            // /api/v1/chat 端点搜索支持
            if (AiModelManager.isSearchEnabled()) {
                JsonArray tools = new JsonArray();
                JsonObject searchTool = new JsonObject();
                searchTool.addProperty("type", "web_search");
                JsonObject searchConfig = new JsonObject();
                searchConfig.addProperty("enable", true);
                searchTool.add("web_search", searchConfig);
                tools.add(searchTool);
                requestJson.add("tools", tools);
            }
        }

        return requestJson.toString();
    }

    /**
     * 构建带历史上下文的 prompt（Ollama 格式用）
     */
    private static String buildPromptWithContext(String userInput, UUID playerUuid) {
        int contextRounds = AiConfig.getContextRounds();
        if (contextRounds <= 0) return userInput;

        List<AiChatHistory.ChatRecord> history = AiChatHistory.getRecentHistory(playerUuid, contextRounds * 2);
        if (history.isEmpty()) return userInput;

        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("The following is a conversation history:\n\n");
        for (AiChatHistory.ChatRecord record : history) {
            if ("player".equals(record.role)) {
                promptBuilder.append("[Player]: ").append(record.content).append("\n");
            } else {
                promptBuilder.append("[AI]: ").append(record.content).append("\n");
            }
        }
        promptBuilder.append("\n[Player]: ").append(userInput).append("\n[AI]:");
        return promptBuilder.toString();
    }

    // ==================== 文本处理 ====================

    private static String cleanText(String text) {
        if (text == null) return "";
        text = text.replaceAll("<[^>]*>", "").replace("\n", " ").replaceAll("\\s{2,}", " ").trim();
        if (text.length() > 500) text = text.substring(0, 500) + "...";
        return text;
    }

    private static String cleanThinking(String text) {
        if (text == null || text.isEmpty()) return null;
        text = text.replaceAll("<[^>]*>", "").replaceAll("\\s{2,}", " ").trim();
        if (text.length() > 500) text = text.substring(0, 500) + "...";
        return text;
    }

    /**
     * 构建带 hover tooltip 的富文本消息
     */
    public static Text buildAIText(AIResponse aiResponse) {
        if (aiResponse.hasThinking()) {
            Text thinkingText = Text.literal(aiResponse.thinking);
            return Text.literal("[AI] " + aiResponse.response)
                    .styled(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, thinkingText)));
        }
        return Text.literal("[AI] " + aiResponse.response);
    }

    public static int getActiveRequests() {
        return activeRequests.get();
    }
}
