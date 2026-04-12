package fun.ollamachat;

import net.fabricmc.api.ModInitializer;

public class Ollamachat implements ModInitializer {

    @Override
    public void onInitialize() {
        // 加载持久化配置
        OllamaConfig.loadConfig();
        // 公共层初始化，确保历史目录存在
        try {
            java.nio.file.Files.createDirectories(OllamaConfig.getHistoryDir());
        } catch (Exception e) {
            // 目录创建失败不影响主要功能
        }
    }
}
