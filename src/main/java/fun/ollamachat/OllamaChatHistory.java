package fun.ollamachat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class OllamaChatHistory {
    private static final Logger LOGGER = LoggerFactory.getLogger("OllamaChat");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object FILE_LOCK = new Object();

    /**
     * 单条聊天记录
     */
    public static class ChatRecord {
        public String role;    // "player" 或 "ai"
        public String content;
        public long timestamp;

        public ChatRecord(String role, String content, long timestamp) {
            this.role = role;
            this.content = content;
            this.timestamp = timestamp;
        }
    }

    /**
     * 获取历史文件路径
     */
    private static Path getHistoryFilePath(UUID playerUuid) {
        return OllamaConfig.getHistoryDir().resolve(playerUuid.toString() + ".json");
    }

    /**
     * 读取玩家的完整聊天历史
     */
    public static List<ChatRecord> getHistory(UUID playerUuid) {
        synchronized (FILE_LOCK) {
            Path filePath = getHistoryFilePath(playerUuid);
            if (!Files.exists(filePath)) {
                return new ArrayList<>();
            }
            try (FileReader reader = new FileReader(filePath.toFile())) {
                Type listType = new TypeToken<List<ChatRecord>>() {}.getType();
                List<ChatRecord> history = GSON.fromJson(reader, listType);
                return history != null ? history : new ArrayList<>();
            } catch (Exception e) {
                return new ArrayList<>();
            }
        }
    }

    /**
     * 获取最近 N 条聊天历史
     */
    public static List<ChatRecord> getRecentHistory(UUID playerUuid, int count) {
        List<ChatRecord> fullHistory = getHistory(playerUuid);
        if (fullHistory.size() <= count) {
            return fullHistory;
        }
        return fullHistory.subList(fullHistory.size() - count, fullHistory.size());
    }

    /**
     * 添加一条消息到聊天历史
     */
    public static void addMessage(UUID playerUuid, String role, String content) {
        synchronized (FILE_LOCK) {
            List<ChatRecord> history = getHistory(playerUuid);
            history.add(new ChatRecord(role, content, System.currentTimeMillis()));

            // 限制历史记录数量，防止文件过大
            int maxRecords = OllamaConfig.getMaxHistoryRecords();
            while (history.size() > maxRecords) {
                history.remove(0);
            }

            saveHistory(playerUuid, history);
        }
    }

    /**
     * 清除玩家的聊天历史
     */
    public static boolean clearHistory(UUID playerUuid) {
        synchronized (FILE_LOCK) {
            Path filePath = getHistoryFilePath(playerUuid);
            try {
                Files.deleteIfExists(filePath);
                return true;
            } catch (IOException e) {
                return false;
            }
        }
    }

    /**
     * 保存聊天历史到 JSON 文件（原子写入：临时文件+重命名）
     */
    private static void saveHistory(UUID playerUuid, List<ChatRecord> history) {
        Path filePath = getHistoryFilePath(playerUuid);
        try {
            // 确保目录存在
            Files.createDirectories(filePath.getParent());

            // 先写入临时文件
            Path tempFile = filePath.resolveSibling(playerUuid.toString() + ".tmp");
            try (FileWriter writer = new FileWriter(tempFile.toFile())) {
                GSON.toJson(history, writer);
            }

            // 原子重命名
            Files.move(tempFile, filePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // 写入失败不影响主要功能
            LOGGER.error("Failed to save chat history for player", e);
        }
    }

    /**
     * 获取玩家的历史记录条数
     */
    public static int getHistoryCount(UUID playerUuid) {
        return getHistory(playerUuid).size();
    }
}
