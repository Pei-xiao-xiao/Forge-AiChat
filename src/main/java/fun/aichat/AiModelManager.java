package fun.aichat;

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

public class AiModelManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("AiChat");
    private static final CopyOnWriteArrayList<String> cachedModels = new CopyOnWriteArrayList<>();
    private static String currentModel = "";
    private static volatile boolean thinkEnabled = false;
    private static volatile boolean searchEnabled = false;
    private static final int MODEL_UPDATE_INTERVAL = 300;
    // 主客户端：自动协商 HTTP 版本
    private static final HttpClient modelHttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    
    // 回退客户端：强制 HTTP/1.1，用于不支持 HTTP/2 的本地服务
    private static final HttpClient modelHttpClientFallback = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public static List<String> getCachedModels() {
        return new ArrayList<>(cachedModels);
    }

    /**
     * 从 API URL 中提取 base URL（scheme + host + port）
     * 例如 http://127.0.0.1:1234/api/v1/chat → http://127.0.0.1:1234
     * 例如 http://127.0.0.1:1234 → http://127.0.0.1:1234
     */
    private static String getBaseUrl(String apiUrl) {
        return apiUrl.replaceAll("(^https?://[^/]+).*$", "$1");
    }

    /**
     * 尝试从指定 URL 获取模型列表（单次请求）
     * @param url 模型列表端点 URL
     * @param format 解析格式：openai=OpenAI格式{"data":[{"id":"..."}]}，
     *               lmstudio=LM Studio原生格式{"models":[{"key":"..."}]}，
     *               ollama=Ollama格式{"models":[{"name":"..."}]}
     * @return 解析到的模型列表，null 表示请求失败
     */
    /**
     * 从 JSON 数组中提取模型名称
     * @param jsonArray JSON 数组
     * @param idField 模型标识字段名（"id", "key", "name" 等）
     * @param typeField 类型过滤字段名（如 "type"），null 表示不过滤
     * @param typeValue 类型过滤值（如 "llm"），仅提取匹配的模型
     * @return 模型名称列表
     */
    private static List<String> extractModelNames(JsonArray jsonArray, String idField, String typeField, String typeValue) {
        List<String> models = new ArrayList<>();
        for (int i = 0; i < jsonArray.size(); i++) {
            JsonObject modelObj = jsonArray.get(i).getAsJsonObject();
            // 如果指定了类型过滤，只提取匹配的模型
            if (typeField != null) {
                String type = modelObj.has(typeField) ? modelObj.get(typeField).getAsString() : typeValue;
                if (!typeValue.equals(type)) continue;
            }
            if (modelObj.has(idField)) {
                models.add(modelObj.get(idField).getAsString());
            }
        }
        return models;
    }
    
    /**
     * 根据 API 格式解析模型列表 JSON
     * @param jsonObject 响应 JSON
     * @param format 格式类型：openai / lmstudio / ollama
     * @return 模型名称列表
     */
    private static List<String> parseModelsJson(JsonObject jsonObject, String format) {
        switch (format) {
            case "openai":
                // OpenAI 格式：{"data": [{"id": "model-name"}, ...]}
                if (jsonObject.has("data")) {
                    return extractModelNames(jsonObject.getAsJsonArray("data"), "id", null, null);
                }
                return new ArrayList<>();
            case "lmstudio":
                // LM Studio 原生格式：{"models": [{"key": "model-name", "type": "llm"}, ...]}
                // 只提取 type=llm 的模型（排除 embedding 等类型）
                if (jsonObject.has("models")) {
                    return extractModelNames(jsonObject.getAsJsonArray("models"), "key", "type", "llm");
                }
                return new ArrayList<>();
            default:
                // Ollama 格式：{"models": [{"name": "model-name"}, ...]}
                if (jsonObject.has("models")) {
                    return extractModelNames(jsonObject.getAsJsonArray("models"), "name", null, null);
                }
                return new ArrayList<>();
        }
    }

    /**
     * 尝试从指定 URL 获取模型列表（自动回退 HTTP/1.1）
     */
    private static List<String> tryFetchModels(String url, String format) {
        try {
            LOGGER.info("尝试获取模型列表: {} (格式: {})", url, format);
            
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET();
            
            // OpenAI/LM Studio 兼容接口需要 Authorization header
            AiConfig.ApiProvider provider = AiConfig.getApiProvider();
            if ((provider == AiConfig.ApiProvider.OPENAI || provider == AiConfig.ApiProvider.LMSTUDIO)
                    && !AiConfig.getApiKey().isEmpty()) {
                requestBuilder.header("Authorization", "Bearer " + AiConfig.getApiKey());
            }
            
            HttpRequest request = requestBuilder.build();
            
            // 先尝试自动协商版本（优先 HTTP/2）
            HttpResponse<String> response;
            try {
                response = modelHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (java.net.http.HttpTimeoutException e) {
                // HTTP/2 协商超时，回退到 HTTP/1.1
                LOGGER.info("HTTP/2 协商超时，回退到 HTTP/1.1: {}", url);
                HttpRequest fallbackRequest = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .header("Authorization", request.headers().firstValue("Authorization").orElse(""))
                        .build();
                response = modelHttpClientFallback.send(fallbackRequest, HttpResponse.BodyHandlers.ofString());
            }
            
            LOGGER.info("模型列表响应: URL={}, 状态码={}", url, response.statusCode());
            
            if (response.statusCode() != 200) {
                LOGGER.warn("获取模型列表失败，URL={}, 状态码={}, 响应: {}", url, response.statusCode(), 
                        response.body().length() > 200 ? response.body().substring(0, 200) + "..." : response.body());
                return null;
            }
            
            JsonObject jsonObject = JsonParser.parseString(response.body()).getAsJsonObject();
            List<String> models = parseModelsJson(jsonObject, format);
            
            return models.isEmpty() ? null : models;
        } catch (Exception e) {
            LOGGER.warn("获取模型列表异常: URL={}, 异常: {}", url, e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 通过 HTTP API 获取模型列表
     * Ollama: GET /api/tags → {"models": [{"name": "..."}]}
     * OpenAI 兼容: GET /v1/models → {"data": [{"id": "..."}]}
     * LM Studio: 三级回退 → /api/v1/models → /v1/models → /api/v0/models
     * 
     * @return 获取到的模型数量，-1 表示失败
     */
    public static int fetchModelsFromApi() {
        AiConfig.ApiProvider provider = AiConfig.getApiProvider();
        String apiUrl = AiConfig.getApiUrl();

        if (apiUrl == null || apiUrl.isEmpty()) {
            LOGGER.error("API URL is not configured");
            return -1;
        }
        
        LOGGER.info("正在获取模型列表，Provider: {}, API URL: {}", provider, apiUrl);
        
        try {
            List<String> newModels = null;
            
            if (provider == AiConfig.ApiProvider.LMSTUDIO) {
                // LM Studio：三级回退策略
                // 优先级1: /v1/models（OpenAI 兼容端点，所有版本支持，最可靠）
                // 优先级2: /api/v1/models（LM Studio 原生 v1 API，需 0.4.0+，含更多模型信息）
                // 优先级3: /api/v0/models（LM Studio 原生 v0 API，旧版本）
                String baseUrl = getBaseUrl(apiUrl);
                
                String[][] modelUrlFormats = {
                    {baseUrl + "/v1/models", "openai"},
                    {baseUrl + "/api/v1/models", "lmstudio"},
                    {baseUrl + "/api/v0/models", "lmstudio"}
                };
                
                for (String[] entry : modelUrlFormats) {
                    newModels = tryFetchModels(entry[0], entry[1]);
                    if (newModels != null) {
                        LOGGER.info("LM Studio 模型列表获取成功，使用端点: {}", entry[0]);
                        break;
                    }
                }
            } else if (provider == AiConfig.ApiProvider.OPENAI) {
                // OpenAI 兼容接口：推导出 /v1/models 端点
                String modelsUrl;
                int v1Index = apiUrl.indexOf("/v1/");
                if (v1Index >= 0) {
                    modelsUrl = apiUrl.substring(0, v1Index) + "/v1/models";
                } else if (apiUrl.contains("/chat/completions")) {
                    modelsUrl = apiUrl.replace("/chat/completions", "/models");
                } else if (apiUrl.matches("^https?://[^/]+/?$")) {
                    modelsUrl = apiUrl.replaceAll("/+$", "") + "/v1/models";
                } else {
                    String base = getBaseUrl(apiUrl);
                    modelsUrl = base + "/v1/models";
                }
                newModels = tryFetchModels(modelsUrl, "openai");
            } else {
                // Ollama 原生：推导出 /api/tags 端点
                String modelsUrl;
                if (apiUrl.contains("/api/")) {
                    modelsUrl = apiUrl.replaceAll("/api/[^/]*$", "/api/tags");
                } else if (apiUrl.matches("^https?://[^/]+/?$")) {
                    modelsUrl = apiUrl.replaceAll("/+$", "") + "/api/tags";
                } else {
                    String base = getBaseUrl(apiUrl);
                    modelsUrl = base + "/api/tags";
                }
                newModels = tryFetchModels(modelsUrl, "ollama");
            }
            
            if (newModels == null) {
                LOGGER.error("所有模型列表端点均失败，请检查：");
                LOGGER.error("1. API 服务是否已启动？当前 API URL: {}", AiConfig.getApiUrl());
                LOGGER.error("2. API 地址和端口是否正确？");
                LOGGER.error("3. 如果是 LM Studio，请确认已在「Local Server」标签页启动服务器");
                LOGGER.error("4. 防火墙是否阻止了连接？");
                return -1;
            }
            
            cachedModels.clear();
            cachedModels.addAll(newModels);
            LOGGER.info("成功获取 {} 个模型", newModels.size());
            return newModels.size();
        } catch (Exception e) {
            LOGGER.error("获取模型列表时发生异常: {}", e.getMessage());
            LOGGER.error("API URL: {}, 异常类型: {}", AiConfig.getApiUrl(), e.getClass().getSimpleName());
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
                        AiModelManager::refreshModels,
                        0, MODEL_UPDATE_INTERVAL, TimeUnit.SECONDS
                );
    }

    /**
     * 根据当前 Provider 刷新模型列表
     * Ollama → 先尝试 HTTP API，失败回退到 ollama list 命令行
     * OpenAI/LM Studio → 仅使用 HTTP API
     */
    public static void refreshModels() {
        AiConfig.ApiProvider provider = AiConfig.getApiProvider();
        if (provider == AiConfig.ApiProvider.OLLAMA) {
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
