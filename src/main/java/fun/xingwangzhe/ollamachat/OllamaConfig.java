package fun.xingwangzhe.ollamachat;

import java.nio.file.Path;
import java.nio.file.Paths;

public class OllamaConfig {
    private static int contextRounds = 5;        // 上下文轮数（每轮=1条玩家消息+1条AI回复）
    private static int maxHistoryRecords = 200;   // 最大历史记录条数
    private static String historyDirName = "ollamachat_history";

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
    }
}
