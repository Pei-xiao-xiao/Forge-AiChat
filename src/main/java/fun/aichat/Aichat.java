package fun.aichat;

import net.fabricmc.api.ModInitializer;

public class Aichat implements ModInitializer {

    @Override
    public void onInitialize() {
        // 加载持久化配置
        AiConfig.loadConfig();
        // 恢复运行时状态（模型名、think、search 开关）
        AiModelManager.restoreFromConfig();
        // 公共层初始化，确保历史目录存在
        try {
            java.nio.file.Files.createDirectories(AiConfig.getHistoryDir());
        } catch (Exception e) {
            // 目录创建失败不影响主要功能
        }
    }
}
