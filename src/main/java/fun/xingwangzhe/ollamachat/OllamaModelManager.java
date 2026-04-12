package fun.xingwangzhe.ollamachat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class OllamaModelManager {
    private static final CopyOnWriteArrayList<String> cachedModels = new CopyOnWriteArrayList<>();
    private static String currentModel = "";
    private static volatile boolean thinkEnabled = false;
    private static volatile boolean searchEnabled = false;
    private static final int MODEL_UPDATE_INTERVAL = 300;

    public static List<String> getCachedModels() {
        return new ArrayList<>(cachedModels);
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
        Executors.newSingleThreadScheduledExecutor()
                .scheduleAtFixedRate(
                        OllamaModelManager::updateModelsFromSystem,
                        0, MODEL_UPDATE_INTERVAL, TimeUnit.SECONDS
                );
    }
}
