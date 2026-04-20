package fun.aichat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class AiConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("AiChat");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = Paths.get(System.getProperty("user.dir"), "config", "aichat_config.json");
    /**
     * API 提供者类型
     * OLLAMA: Ollama 原生 /api/generate 格式
     * OPENAI: OpenAI 兼容 /v1/chat/completions 格式（DeepSeek、智谱、通义千问等）
     * LMSTUDIO: LM Studio /api/v1/chat 有状态格式（通过 response_id 管理上下文）
     */
    public enum ApiProvider {
        OLLAMA,
        OPENAI,
        LMSTUDIO
    }

    private static ApiProvider apiProvider = ApiProvider.OLLAMA;
    private static String apiUrl = "http://localhost:11434/api/generate";
    private static String apiKey = "";
    private static int contextRounds = 5;
    private static int maxHistoryRecords = 200;
    private static String historyDirName = "aichat_history";
    /** 当前选中的模型名称 */
    private static String currentModel = "";
    /** 深度思考开关 */
    private static boolean thinkEnabled = false;
    /** 在线搜索开关 */
    private static boolean searchEnabled = false;

    public static ApiProvider getApiProvider() {
        return apiProvider;
    }

    public static void setApiProvider(ApiProvider provider) {
        apiProvider = provider;
        saveConfig();
    }

    public static ApiProvider detectProvider(String url) {
        if (url == null) return ApiProvider.OLLAMA;
        String lower = url.toLowerCase();
        if (lower.contains("/api/v1/chat")) {
            return ApiProvider.LMSTUDIO;
        }
        if (lower.contains("/v1/responses")) {
            return ApiProvider.LMSTUDIO;
        }
        if (lower.contains("/v1/") || lower.contains("/chat/completions")) {
            return ApiProvider.OPENAI;
        }
        if (lower.matches("^https?://[^/]+:1234/?$")) {
            return ApiProvider.LMSTUDIO;
        }
        return ApiProvider.OLLAMA;
    }

    public static String getApiUrl() {
        return apiUrl;
    }

    public static void setApiUrl(String url) {
        if (url != null && !url.trim().isEmpty()) {
            String trimmedUrl = url.trim();
            if (!trimmedUrl.toLowerCase().startsWith("http://") && !trimmedUrl.toLowerCase().startsWith("https://")) {
                trimmedUrl = "http://" + trimmedUrl;
                LOGGER.info("API URL 自动补全协议头：{} → {}", url.trim(), trimmedUrl);
            }
            apiUrl = trimmedUrl;
            apiProvider = detectProvider(apiUrl);
            saveConfig();
        }
    }

    public static String getApiKey() {
        return apiKey;
    }

    public static String getMaskedApiKey() {
        if (apiKey.isEmpty()) {
            return "(empty)";
        }
        if (apiKey.length() < 8) {
            return "****";
        }
        return apiKey.substring(0, 3) + "****" + apiKey.substring(apiKey.length() - 3);
    }

    public static void setApiKey(String key) {
        if (key != null) {
            apiKey = key.trim();
            saveConfig();
        }
    }

    public static boolean requiresApiKey() {
        return apiProvider == ApiProvider.OPENAI || apiProvider == ApiProvider.LMSTUDIO;
    }

    public static int getContextRounds() {
        return contextRounds;
    }

    public static void setContextRounds(int rounds) {
        contextRounds = Math.max(0, Math.min(rounds, 50));
        saveConfig();
    }

    public static int getMaxHistoryRecords() {
        return maxHistoryRecords;
    }

    public static void setMaxHistoryRecords(int max) {
        maxHistoryRecords = Math.max(10, Math.min(max, 1000));
        saveConfig();
    }

    public static Path getHistoryDir() {
        return Paths.get(System.getProperty("user.dir")).resolve(historyDirName);
    }

    public static void setHistoryDirName(String dirName) {
        historyDirName = dirName;
        saveConfig();
    }

    // ========== 模型 / 开关持久化 ==========

    public static String getCurrentModel() {
        return currentModel;
    }

    public static void setCurrentModel(String model) {
        currentModel = model;
        saveConfig();
    }

    public static boolean isThinkEnabled() {
        return thinkEnabled;
    }

    public static void setThinkEnabled(boolean enabled) {
        thinkEnabled = enabled;
        saveConfig();
    }

    public static boolean isSearchEnabled() {
        return searchEnabled;
    }

    public static void setSearchEnabled(boolean enabled) {
        searchEnabled = enabled;
        saveConfig();
    }

    public static void saveConfig() {
        try {
            JsonObject config = new JsonObject();
            config.addProperty("apiUrl", apiUrl);
            config.addProperty("apiProvider", apiProvider.name());
            config.addProperty("apiKey", apiKey);
            config.addProperty("contextRounds", contextRounds);
            config.addProperty("maxHistoryRecords", maxHistoryRecords);
            config.addProperty("currentModel", currentModel);
            config.addProperty("thinkEnabled", thinkEnabled);
            config.addProperty("searchEnabled", searchEnabled);

            Path parentDir = CONFIG_PATH.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }

            Path tempPath = CONFIG_PATH.resolveSibling(CONFIG_PATH.getFileName() + ".tmp");
            Files.writeString(tempPath, GSON.toJson(config));
            Files.move(tempPath, CONFIG_PATH,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.error("Failed to save config", e);
        }
    }

    public static void loadConfig() {
        try {
            if (!Files.exists(CONFIG_PATH)) {
                LOGGER.info("配置文件不存在，使用默认值: {}", CONFIG_PATH);
                return;
            }
            String content = Files.readString(CONFIG_PATH);
            JsonObject config = JsonParser.parseString(content).getAsJsonObject();

            if (config.has("apiUrl")) {
                apiUrl = config.get("apiUrl").getAsString();
            }
            if (config.has("apiProvider")) {
                try {
                    apiProvider = ApiProvider.valueOf(config.get("apiProvider").getAsString());
                } catch (IllegalArgumentException e) {
                    apiProvider = detectProvider(apiUrl);
                }
            }
            if (config.has("apiKey")) {
                apiKey = config.get("apiKey").getAsString();
            }
            if (config.has("contextRounds")) {
                contextRounds = Math.max(0, Math.min(config.get("contextRounds").getAsInt(), 50));
            }
            if (config.has("maxHistoryRecords")) {
                maxHistoryRecords = Math.max(10, Math.min(config.get("maxHistoryRecords").getAsInt(), 1000));
            }

            // 模型和开关持久化
            if (config.has("currentModel")) {
                currentModel = config.get("currentModel").getAsString();
            }
            if (config.has("thinkEnabled")) {
                thinkEnabled = config.get("thinkEnabled").getAsBoolean();
            }
            if (config.has("searchEnabled")) {
                searchEnabled = config.get("searchEnabled").getAsBoolean();
            }

            // 打印完整配置状态
            String maskedKey = apiKey.isEmpty() ? "(empty)" :
                    (apiKey.length() < 8 ? "****" : apiKey.substring(0, 3) + "****" + apiKey.substring(apiKey.length() - 3));
            LOGGER.info("========== AiChat 配置已加载 ==========");
            LOGGER.info("API URL:   {}", apiUrl);
            LOGGER.info("Provider:  {}", apiProvider.name());
            LOGGER.info("API Key:   {}", maskedKey);
            LOGGER.info("Model:     {}", currentModel.isEmpty() ? "(未选择)" : currentModel);
            LOGGER.info("Think:     {}", thinkEnabled ? "ON" : "OFF");
            LOGGER.info("Search:    {}", searchEnabled ? "ON" : "OFF");
            LOGGER.info("Context:    {} 轮", contextRounds);
            LOGGER.info("========================================");
        } catch (Exception e) {
            LOGGER.error("Failed to load config", e);
        }
    }
}
