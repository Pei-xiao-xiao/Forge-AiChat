package fun.ollamachat;

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

public class OllamaConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("OllamaChat");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = Paths.get(System.getProperty("user.dir"), "config", "ollamachat_config.json");
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
    private static String apiUrl = "http://localhost:11434/api/generate";  // Ollama API 地址
    private static String apiKey = "";  // 第三方 API Key（OpenAI 兼容接口需要）
    private static int contextRounds = 5;        // 上下文轮数（每轮=1条玩家消息+1条AI回复）
    private static int maxHistoryRecords = 200;   // 最大历史记录条数
    private static String historyDirName = "ollamachat_history";

    /**
     * 获取 API 提供者类型
     */
    public static ApiProvider getApiProvider() {
        return apiProvider;
    }

    /**
     * 设置 API 提供者类型
     * OLLAMA: 使用 /api/generate 请求格式
     * OPENAI: 使用 /v1/chat/completions 请求格式（兼容 DeepSeek、智谱、通义千问等）
     */
    public static void setApiProvider(ApiProvider provider) {
        apiProvider = provider;
        saveConfig();
    }

    /**
     * 自动检测 API 提供者类型
     * 根据 URL 路径自动判断：
     * - 包含 /api/v1/chat → LMSTUDIO（LM Studio 有状态接口）
     * - 包含 /v1/responses → LMSTUDIO（LM Studio OpenAI Responses API 兼容）
     * - 包含 /v1/ 或 /chat/completions → OPENAI
     * - 其他 → OLLAMA
     */
    public static ApiProvider detectProvider(String url) {
        if (url == null) return ApiProvider.OLLAMA;
        String lower = url.toLowerCase();
        // LM Studio 的有状态接口：/api/v1/chat
        if (lower.contains("/api/v1/chat")) {
            return ApiProvider.LMSTUDIO;
        }
        // LM Studio 的 OpenAI Responses API 兼容接口：/v1/responses
        if (lower.contains("/v1/responses")) {
            return ApiProvider.LMSTUDIO;
        }
        if (lower.contains("/v1/") || lower.contains("/chat/completions")) {
            return ApiProvider.OPENAI;
        }
        return ApiProvider.OLLAMA;
    }

    /**
     * 获取 API URL
     */
    public static String getApiUrl() {
        return apiUrl;
    }

    /**
     * 设置 API URL
     * 支持任意 Ollama 兼容的 API 地址，例如：
     * - http://localhost:11434/api/generate （默认本地 Ollama）
     * - http://192.168.1.100:11434/api/generate （局域网远程 Ollama）
     * - https://api.deepseek.com/v1/chat/completions （DeepSeek）
     * - https://open.bigmodel.cn/api/paas/v4/chat/completions （智谱 GLM）
     * - https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions （通义千问）
     * - https://api.openai.com/v1/chat/completions （OpenAI）
     */
    public static void setApiUrl(String url) {
        if (url != null && !url.trim().isEmpty()) {
            apiUrl = url.trim();
            // 自动检测 provider
            apiProvider = detectProvider(apiUrl);
            saveConfig();
        }
    }

    /**
     * 获取 API Key
     * 注意：仅用于构建 HTTP 请求头，不应在日志或聊天中输出
     */
    public static String getApiKey() {
        return apiKey;
    }

    /**
     * 获取脱敏后的 API Key（用于显示）
     * 仅显示前3位和后3位，中间用 **** 替代
     * 短于8位的 Key 完全遮掩为 ****
     */
    public static String getMaskedApiKey() {
        if (apiKey.isEmpty()) {
            return "(empty)";
        }
        if (apiKey.length() < 8) {
            return "****";
        }
        return apiKey.substring(0, 3) + "****" + apiKey.substring(apiKey.length() - 3);
    }

    /**
     * 设置 API Key（用于第三方 API 鉴权）
     * 本地 Ollama 不需要 API Key
     * 第三方服务（DeepSeek、OpenAI 等）需要在请求头中携带 Authorization: Bearer <apiKey>
     * 
     * ⚠️ 安全警告：API Key 以明文存储在配置文件中（config/ollamachat_config.json）。
     * 请确保该文件的访问权限受限，避免泄露。在共享服务器上使用时尤其注意。
     */
    public static void setApiKey(String key) {
        if (key != null) {
            apiKey = key.trim();
            saveConfig();
        }
    }

    /**
     * 判断当前是否需要 API Key
     */
    public static boolean requiresApiKey() {
        return apiProvider == ApiProvider.OPENAI || apiProvider == ApiProvider.LMSTUDIO;
    }

    /**
     * 获取上下文轮数
     */
    public static int getContextRounds() {
        return contextRounds;
    }

    /**
     * 设置上下文轮数
     */
    public static void setContextRounds(int rounds) {
        contextRounds = Math.max(0, Math.min(rounds, 50));
        saveConfig();
    }

    /**
     * 获取最大历史记录条数
     */
    public static int getMaxHistoryRecords() {
        return maxHistoryRecords;
    }

    /**
     * 设置最大历史记录条数
     */
    public static void setMaxHistoryRecords(int max) {
        maxHistoryRecords = Math.max(10, Math.min(max, 1000));
        saveConfig();
    }

    /**
     * 获取历史文件存储目录
     * 服务端：服务器运行目录/ollamachat_history/
     * 客户端独立运行：游戏运行目录/ollamachat_history/
     */
    public static Path getHistoryDir() {
        return Paths.get(System.getProperty("user.dir")).resolve(historyDirName);
    }

    /**
     * 设置历史目录名
     */
    public static void setHistoryDirName(String dirName) {
        historyDirName = dirName;
        saveConfig();
    }

    // ========== 配置持久化 ==========

    /**
     * 保存配置到文件
     * ⚠️ API Key 以明文存储，请确保配置文件访问权限安全
     */
    public static void saveConfig() {
        try {
            JsonObject config = new JsonObject();
            config.addProperty("apiUrl", apiUrl);
            config.addProperty("apiProvider", apiProvider.name());
            config.addProperty("apiKey", apiKey);
            config.addProperty("contextRounds", contextRounds);
            config.addProperty("maxHistoryRecords", maxHistoryRecords);

            Path parentDir = CONFIG_PATH.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }

            // 原子写入：先写临时文件，再重命名
            Path tempPath = CONFIG_PATH.resolveSibling(CONFIG_PATH.getFileName() + ".tmp");
            Files.writeString(tempPath, GSON.toJson(config));
            Files.move(tempPath, CONFIG_PATH,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.error("Failed to save config", e);
        }
    }

    /**
     * 从文件加载配置
     * 在模组初始化时调用
     */
    public static void loadConfig() {
        try {
            if (!Files.exists(CONFIG_PATH)) {
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

            LOGGER.info("Config loaded from {}", CONFIG_PATH);
        } catch (Exception e) {
            LOGGER.error("Failed to load config", e);
        }
    }
}
