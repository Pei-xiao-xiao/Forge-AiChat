package fun.ollamachat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OllamaModelManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("OllamaChat");
    private static final CopyOnWriteArrayList<String> cachedModels = new CopyOnWriteArrayList<>();
    private static String currentModel = "";
    private static volatile boolean thinkEnabled = false;
    private static volatile boolean searchEnabled = false;
    private static final int MODEL_UPDATE_INTERVAL = 300;
    private static final HttpClient modelHttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static List<String> getCachedModels() {
        return new ArrayList<>(cachedModels);
    }

    /**
     * 通过 HTTP API 获取模型列表
     * Ollama: GET /api/tags → {"models": [{"name": "..."}]}
     * OpenAI 兼容: GET /v1/models → {"data": [{"id": "..."}]}
     * LM Studio: GET /api/v1/models → {"data": [{"id": "..."}]}
     * 
     * @return 获取到的模型数量，-1 表示失败
     */
    public static int fetchModelsFromApi() {
        try {
            OllamaConfig.ApiProvider provider = OllamaConfig.getApiProvider();
            String apiUrl = OllamaConfig.getApiUrl();
            String modelsUrl;

            if (apiUrl == null || apiUrl.isEmpty()) {
                LOGGER.error("API URL is not configured");
                return -1;
            }
            
            LOGGER.info("正在获取模型列表，Provider: {}, API URL: {}", provider, apiUrl);
            
            if (provider == OllamaConfig.ApiProvider.LMSTUDIO) {
                // LM Studio：推导出 /api/v1/models 端点
                // 例如 http://localhost:1234/api/v1/chat → http://localhost:1234/api/v1/models
                // 例如 http://localhost:1234/v1/responses → http://localhost:1234/api/v1/models（LM Studio 原生优先）
                // 例如 http://localhost:1234 → http://localhost:1234/api/v1/models
                if (apiUrl.contains("/api/v1/")) {
                    modelsUrl = apiUrl.replaceAll("/api/v1/[^/]*$", "/api/v1/models");
                } else if (apiUrl.contains("/v1/")) {
                    // /v1/responses 或 /v1/chat/completions → 使用 LM Studio 原生 /api/v1/models
                    String base = apiUrl.replaceAll("/v1/.*$", "");
                    modelsUrl = base + "/api/v1/models";
                } else if (apiUrl.matches("^https?://[^/]+/?$")) {
                    // 纯基础地址（如 http://127.0.0.1:1234 或 http://127.0.0.1:1234/）
                    modelsUrl = apiUrl.replaceAll("/+$", "") + "/api/v1/models";
                } else {
                    // 其他情况：提取 base URL 然后拼接
                    String base = apiUrl.replaceAll("(^https?://[^/]+).*$", "$1");
                    modelsUrl = base + "/api/v1/models";
                }
            } else if (provider == OllamaConfig.ApiProvider.OPENAI) {
                // OpenAI 兼容接口：推导出 /v1/models 端点
                // 例如 https://api.deepseek.com/v1/chat/completions → https://api.deepseek.com/v1/models
                // 例如 http://localhost:1234/v1/chat/completions → http://localhost:1234/v1/models
                // 例如 https://api.openai.com → https://api.openai.com/v1/models
                int v1Index = apiUrl.indexOf("/v1/");
                if (v1Index >= 0) {
                    modelsUrl = apiUrl.substring(0, v1Index) + "/v1/models";
                } else if (apiUrl.contains("/chat/completions")) {
                    modelsUrl = apiUrl.replace("/chat/completions", "/models");
                } else if (apiUrl.matches("^https?://[^/]+/?$")) {
                    // 纯基础地址
                    modelsUrl = apiUrl.replaceAll("/+$", "") + "/v1/models";
                } else {
                    // 无法推导，提取 base 拼接默认路径
                    String base = apiUrl.replaceAll("(^https?://[^/]+).*$", "$1");
                    modelsUrl = base + "/v1/models";
                }
            } else {
                // Ollama 原生：推导出 /api/tags 端点
                // 例如 http://localhost:11434/api/generate → http://localhost:11434/api/tags
                // 例如 http://localhost:11434 → http://localhost:11434/api/tags
                if (apiUrl.contains("/api/")) {
                    modelsUrl = apiUrl.replaceAll("/api/[^/]*$", "/api/tags");
                } else if (apiUrl.matches("^https?://[^/]+/?$")) {
                    // 纯基础地址
                    modelsUrl = apiUrl.replaceAll("/+$", "") + "/api/tags";
                } else {
                    // URL 不含 /api/，提取 base 拼接默认路径
                    String base = apiUrl.replaceAll("(^https?://[^/]+).*$", "$1");
                    modelsUrl = base + "/api/tags";
                }
            }
            
            LOGGER.info("推导出的模型列表 URL: {}", modelsUrl);
            
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(modelsUrl))
                    .timeout(Duration.ofSeconds(20))
                    .GET();
            
            // OpenAI/LM Studio 兼容接口需要 Authorization header
            if ((provider == OllamaConfig.ApiProvider.OPENAI || provider == OllamaConfig.ApiProvider.LMSTUDIO)
                    && !OllamaConfig.getApiKey().isEmpty()) {
                requestBuilder.header("Authorization", "Bearer " + OllamaConfig.getApiKey());
            }
            
            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = modelHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            LOGGER.info("模型列表请求 URL: {}, 响应状态码：{}", modelsUrl, response.statusCode());
            
            if (response.statusCode() != 200) {
                LOGGER.error("获取模型列表失败，状态码：{}, 响应内容：{}", response.statusCode(), response.body());
                return -1;
            }
            
            List<String> newModels = new ArrayList<>();
            JsonObject jsonObject = JsonParser.parseString(response.body()).getAsJsonObject();
            
            if (provider == OllamaConfig.ApiProvider.OPENAI || provider == OllamaConfig.ApiProvider.LMSTUDIO) {
                // OpenAI / LM Studio 格式：{"data": [{"id": "model-name"}, ...]}
                if (jsonObject.has("data")) {
                    JsonArray data = jsonObject.getAsJsonArray("data");
                    for (int i = 0; i < data.size(); i++) {
                        JsonObject modelObj = data.get(i).getAsJsonObject();
                        if (modelObj.has("id")) {
                            newModels.add(modelObj.get("id").getAsString());
                        }
                    }
                }
            } else {
                // Ollama 格式：{"models": [{"name": "model-name"}, ...]}
                if (jsonObject.has("models")) {
                    JsonArray models = jsonObject.getAsJsonArray("models");
                    for (int i = 0; i < models.size(); i++) {
                        JsonObject modelObj = models.get(i).getAsJsonObject();
                        if (modelObj.has("name")) {
                            newModels.add(modelObj.get("name").getAsString());
                        }
                    }
                }
            }
            
            cachedModels.clear();
            cachedModels.addAll(newModels);
            LOGGER.info("成功获取 {} 个模型", newModels.size());
            return newModels.size();
        } catch (java.net.http.HttpTimeoutException e) {
            LOGGER.error("获取模型列表超时（20 秒），请检查：");
            LOGGER.error("1. LM Studio 是否已启动并正在运行？");
            LOGGER.error("2. API 地址是否正确？当前为：{}", OllamaConfig.getApiUrl());
            LOGGER.error("3. 防火墙是否阻止了连接？");
            LOGGER.error("4. 如果是远程服务器，网络连接是否正常？");
            return -1;
        } catch (java.net.ConnectException e) {
            LOGGER.error("无法连接到 API 服务器，请检查：");
            LOGGER.error("1. LM Studio 是否已启动？");
            LOGGER.error("2. API 地址和端口是否正确？当前为：{}", OllamaConfig.getApiUrl());
            LOGGER.error("3. LM Studio 的服务器地址是否设置为监听所有接口（0.0.0.0）？");
            return -1;
        } catch (Exception e) {
            LOGGER.error("获取模型列表时发生异常：{}", e.getMessage());
            LOGGER.error("API URL: {}", OllamaConfig.getApiUrl());
            LOGGER.error("异常类型：{}", e.getClass().getSimpleName());
            return -1;
        }
    }

    public static synchronized void updateModelsFromSystem() {
        try {
            Process process = new ProcessBuilder("ollama", "list").start();
            List<String> newModels = new ArrayList<>();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                boolean isFirstLine = true;
                while ((line = reader.readLine()) != null) {
                    if (isFirstLine) {
                        isFirstLine = false;
                        continue;
                    }
                    if (line.matches("^\\S+\\s+.*")) {
                        String modelName = line.split("\\s+")[0];
                        newModels.add(modelName);
                    }
                }
            }

            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroy();
                throw new TimeoutException("Process timed out");
            }

            cachedModels.clear();
            cachedModels.addAll(newModels);
        } catch (Exception e) {
            cachedModels.clear();
        }
    }

    public static boolean isModelValid(String modelName) {
        return cachedModels.contains(modelName);
    }

    public static String getCurrentModel() {
        return currentModel;
    }

    public static void setCurrentModel(String model) {
        currentModel = model;
    }

    // 深度思考开关
    public static boolean isThinkEnabled() {
        return thinkEnabled;
    }

    public static void setThinkEnabled(boolean enabled) {
        thinkEnabled = enabled;
    }

    // 在线搜索开关
    public static boolean isSearchEnabled() {
        return searchEnabled;
    }

    public static void setSearchEnabled(boolean enabled) {
        searchEnabled = enabled;
    }

    static {
        // 根据当前 API 提供商类型定期刷新模型列表
        Executors.newSingleThreadScheduledExecutor()
                .scheduleAtFixedRate(
                        OllamaModelManager::refreshModels,
                        0, MODEL_UPDATE_INTERVAL, TimeUnit.SECONDS
                );
    }

    /**
     * 根据当前 Provider 刷新模型列表
     * Ollama → 先尝试 HTTP API，失败回退到 ollama list 命令行
     * OpenAI/LM Studio → 仅使用 HTTP API
     */
    public static void refreshModels() {
        OllamaConfig.ApiProvider provider = OllamaConfig.getApiProvider();
        if (provider == OllamaConfig.ApiProvider.OLLAMA) {
            // Ollama：先尝试 HTTP API，失败回退命令行
            int count = fetchModelsFromApi();
            if (count < 0) {
                updateModelsFromSystem();
            }
        } else {
            // OpenAI / LM Studio：仅使用 HTTP API
            fetchModelsFromApi();
        }
    }
}
