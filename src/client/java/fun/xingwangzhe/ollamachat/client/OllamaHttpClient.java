package fun.xingwangzhe.ollamachat.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class OllamaHttpClient {
    // 修改为生成接口地址
    private static final String OLLAMA_API_URL = "http://localhost:11434/api/generate";
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final AtomicInteger activeRequests = new AtomicInteger(0);
    private static final ExecutorService requestExecutor = Executors.newCachedThreadPool();

    public static void handleAIRequest(String userInput, boolean isClientMessage) {
        String currentModel = OllamaModelManager.getCurrentModel();
        if (currentModel.isEmpty()) {
            sendAsPlayerMessage(Text.translatable("command.ollama.error.no_model_selected").getString());
            return;
        }

        activeRequests.incrementAndGet();

        JsonObject requestJson = new JsonObject();
        requestJson.addProperty("model", currentModel);
        requestJson.addProperty("prompt", userInput);
        requestJson.addProperty("stream", false);
        requestJson.addProperty("num_predict", 60);

        String requestBody = requestJson.toString();
        OllamaDebugTracker.setLastRequest(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OLLAMA_API_URL))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        requestExecutor.submit(() -> {
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        return parseResponse(response.body());
                    } else {
                        return Text.translatable("command.ollama.error.http_code", response.statusCode()).getString();
                    }
                })
                .whenComplete((aiResponse, throwable) -> {
                    try {
                        if (throwable != null) {
                            throwable.printStackTrace();
                            if (throwable instanceof TimeoutException) {
                                sendAsPlayerMessage(Text.translatable("command.ollama.error.timeout").getString());
                            } else {
                                sendAsPlayerMessage(Text.translatable("command.ollama.error.generic").getString());
                            }
                        } else {
                            OllamaDebugTracker.setLastResponse(aiResponse);
                            sendAsPlayerMessage(aiResponse);
                        }
                    } finally {
                        activeRequests.decrementAndGet();
                    }
                });
        });
    }

    private static String parseResponse(String responseBody) {
        try {
            JsonObject jsonObject = JsonParser.parseString(responseBody).getAsJsonObject();
            if (jsonObject.has("response")) {
                String responseText = jsonObject.get("response").getAsString();

                // 增强输出处理
                responseText = responseText
                        .replaceAll("<[^>]*>", "")   // 去除HTML标签
                        .replace("\n", " ")          // 替换换行符为空格
                        .replaceAll("\\s{2,}", " ")  // 合并多个空格
                        .trim();

                return responseText.length() > 500 ?
                        responseText.substring(0, 500) + "..." :
                        responseText;
            }
        } catch (Exception e) {
            return Text.translatable("command.ollama.error.parse_failed").getString();
        }
        return Text.translatable("command.ollama.error.generic").getString();
    }

    private static void sendAsPlayerMessage(String message) {
        MinecraftClient.getInstance().execute(() -> {
            ClientPlayerEntity player = MinecraftClient.getInstance().player;
            if (player != null) {
                String formattedMsg = "[AI] " + message;
                player.networkHandler.sendChatMessage(formattedMsg);
            }
        });
    }

    public static int getActiveRequests() {
        return activeRequests.get();
    }
}