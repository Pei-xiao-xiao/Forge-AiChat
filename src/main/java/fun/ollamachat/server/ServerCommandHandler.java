package fun.ollamachat.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.UUID;
import java.util.concurrent.*;

public class ServerCommandHandler {
    private static final ExecutorService COMMAND_EXECUTOR = Executors.newFixedThreadPool(2);

    private static final SuggestionProvider<ServerCommandSource> MODEL_SUGGESTIONS = (context, builder) -> {
        for (String model : fun.ollamachat.OllamaModelManager.getCachedModels()) {
            builder.suggest(model);
        }
        return CompletableFuture.completedFuture(builder.build());
    };

    public static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("ollama")
                .requires(source -> source.hasPermissionLevel(0)) // 所有玩家可用
                // list - 列出模型
                .then(CommandManager.literal("list")
                        .executes(context -> {
                            COMMAND_EXECUTOR.submit(() -> {
                                // 先尝试从 API 获取模型列表
                                int count = fun.ollamachat.OllamaModelManager.fetchModelsFromApi();
                                if (count > 0) {
                                    // API 获取成功，直接显示缓存的模型列表
                                    for (String model : fun.ollamachat.OllamaModelManager.getCachedModels()) {
                                        final String modelName = model;
                                        context.getSource().sendFeedback(() -> Text.literal("  - " + modelName), false);
                                    }
                                    context.getSource().sendFeedback(() -> Text.translatable("command.ollama.status.list_success"), false);
                                } else {
                                    // API 获取失败，回退到 ollama list 命令行
                                    listModels(context.getSource());
                                }
                            });
                            return 1;
                        }))
                // serve - 启动服务
                .then(CommandManager.literal("serve")
                        .executes(context -> {
                            COMMAND_EXECUTOR.submit(() -> serveOllama(context.getSource()));
                            context.getSource().sendFeedback(() -> Text.translatable("command.ollama.status.serve_starting"), false);
                            return 1;
                        }))
                // ps - 列出运行中的模型
                .then(CommandManager.literal("ps")
                        .executes(context -> {
                            COMMAND_EXECUTOR.submit(() -> listRunningModels(context.getSource()));
                            context.getSource().sendFeedback(() -> Text.translatable("command.ollama.status.ps_running"), false);
                            return 1;
                        }))
                // refresh - 刷新模型列表
                .then(CommandManager.literal("refresh")
                        .executes(context -> {
                            COMMAND_EXECUTOR.submit(() -> {
                                int count = fun.ollamachat.OllamaModelManager.fetchModelsFromApi();
                                if (count >= 0) {
                                    context.getSource().sendFeedback(() -> Text.translatable("command.ollama.status.models_refreshed_api", count), false);
                                } else {
                                    // API 获取失败，回退到命令行方式
                                    fun.ollamachat.OllamaModelManager.updateModelsFromSystem();
                                    context.getSource().sendFeedback(() -> Text.translatable("command.ollama.status.models_refreshed"), false);
                                }
                            });
                            return 1;
                        }))
                // model - 设置模型
                .then(CommandManager.literal("model")
                        .then(CommandManager.argument("modelname", StringArgumentType.greedyString())
                                .suggests(MODEL_SUGGESTIONS)
                                .executes(context -> {
                                    String modelName = StringArgumentType.getString(context, "modelname").trim();
                                    if (fun.ollamachat.OllamaModelManager.isModelValid(modelName)) {
                                        fun.ollamachat.OllamaModelManager.setCurrentModel(modelName);
                                        context.getSource().sendFeedback(() -> Text.translatable("command.ollama.status.model_set", modelName), false);
                                    } else {
                                        context.getSource().sendError(Text.translatable("command.ollama.error.model_not_found"));
                                    }
                                    return 1;
                                })))
                // api - 设置 API 地址
                .then(CommandManager.literal("api")
                        .executes(context -> {
                            String currentUrl = fun.ollamachat.OllamaConfig.getApiUrl();
                            context.getSource().sendFeedback(() -> Text.translatable("command.ollama.status.api_status", currentUrl), false);
                            return 1;
                        })
                        .then(CommandManager.argument("url", StringArgumentType.greedyString())
                                .executes(context -> {
                                    String url = StringArgumentType.getString(context, "url").trim();
                                    fun.ollamachat.OllamaConfig.setApiUrl(url);
                                    String provider = fun.ollamachat.OllamaConfig.getApiProvider().name();
                                    context.getSource().sendFeedback(() -> Text.translatable("command.ollama.status.api_set_with_provider", url, provider), false);
                                    return 1;
                                })))
                // provider - 设置 API 提供者类型
                .then(CommandManager.literal("provider")
                        .executes(context -> {
                            fun.ollamachat.OllamaConfig.ApiProvider current = fun.ollamachat.OllamaConfig.getApiProvider();
                            context.getSource().sendFeedback(() -> Text.translatable("command.ollama.status.provider_status", current.name()), false);
                            return 1;
                        })
                        .then(CommandManager.literal("ollama")
                                .executes(context -> {
                                    fun.ollamachat.OllamaConfig.setApiProvider(fun.ollamachat.OllamaConfig.ApiProvider.OLLAMA);
                                    context.getSource().sendFeedback(() -> Text.translatable("command.ollama.status.provider_set", "Ollama"), false);
                                    return 1;
                                }))
                        .then(CommandManager.literal("openai")
                                .executes(context -> {
                                    fun.ollamachat.OllamaConfig.setApiProvider(fun.ollamachat.OllamaConfig.ApiProvider.OPENAI);
                                    context.getSource().sendFeedback(() -> Text.translatable("command.ollama.status.provider_set", "OpenAI Compatible"), false);
                                    return 1;
                                }))
                        .then(CommandManager.literal("lmstudio")
                                .executes(context -> {
                                    fun.ollamachat.OllamaConfig.setApiProvider(fun.ollamachat.OllamaConfig.ApiProvider.LMSTUDIO);
                                    context.getSource().sendFeedback(() -> Text.translatable("command.ollama.status.provider_set", "LM Studio"), false);
                                    return 1;
                                })))
                // key - 设置 API Key
                .then(CommandManager.literal("key")
                        .executes(context -> {
                            String masked = fun.ollamachat.OllamaConfig.getMaskedApiKey();
                            context.getSource().sendFeedback(() -> Text.translatable("command.ollama.status.key_status", masked), false);
                            return 1;
                        })
                        .then(CommandManager.argument("apikey", StringArgumentType.greedyString())
                                .executes(context -> {
                                    String key = StringArgumentType.getString(context, "apikey").trim();
                                    fun.ollamachat.OllamaConfig.setApiKey(key);
                                    context.getSource().sendFeedback(() -> Text.translatable("command.ollama.status.key_set"), false);
                                    return 1;
                                })))
                // think - 深度思考开关
                .then(CommandManager.literal("think")
                        .executes(context -> {
                            boolean enabled = fun.ollamachat.OllamaModelManager.isThinkEnabled();
                            context.getSource().sendFeedback(() -> Text.translatable("command.ollama.status.think_status", enabled ? "ON" : "OFF"), false);
                            return 1;
                        })
                        .then(CommandManager.literal("on")
                                .executes(context -> {
                                    fun.ollamachat.OllamaModelManager.setThinkEnabled(true);
                                    context.getSource().sendFeedback(() -> Text.translatable("command.ollama.status.think_enabled"), false);
                                    return 1;
                                }))
                        .then(CommandManager.literal("off")
                                .executes(context -> {
                                    fun.ollamachat.OllamaModelManager.setThinkEnabled(false);
                                    context.getSource().sendFeedback(() -> Text.translatable("command.ollama.status.think_disabled"), false);
                                    return 1;
                                })))
                // search - 在线搜索开关
                .then(CommandManager.literal("search")
                        .executes(context -> {
                            boolean enabled = fun.ollamachat.OllamaModelManager.isSearchEnabled();
                            context.getSource().sendFeedback(() -> Text.translatable("command.ollama.status.search_status", enabled ? "ON" : "OFF"), false);
                            return 1;
                        })
                        .then(CommandManager.literal("on")
                                .executes(context -> {
                                    fun.ollamachat.OllamaModelManager.setSearchEnabled(true);
                                    context.getSource().sendFeedback(() -> Text.translatable("command.ollama.status.search_enabled"), false);
                                    return 1;
                                }))
                        .then(CommandManager.literal("off")
                                .executes(context -> {
                                    fun.ollamachat.OllamaModelManager.setSearchEnabled(false);
                                    context.getSource().sendFeedback(() -> Text.translatable("command.ollama.status.search_disabled"), false);
                                    return 1;
                                })))
                // context - 设置上下文轮数
                .then(CommandManager.literal("context")
                        .executes(context -> {
                            int rounds = fun.ollamachat.OllamaConfig.getContextRounds();
                            context.getSource().sendFeedback(() -> Text.translatable("command.ollama.status.context_status", rounds), false);
                            return 1;
                        })
                        .then(CommandManager.argument("rounds", IntegerArgumentType.integer(0, 50))
                                .executes(context -> {
                                    int rounds = IntegerArgumentType.getInteger(context, "rounds");
                                    fun.ollamachat.OllamaConfig.setContextRounds(rounds);
                                    context.getSource().sendFeedback(() -> Text.translatable("command.ollama.status.context_set", rounds), false);
                                    return 1;
                                })))
                // history - 聊天历史管理
                .then(CommandManager.literal("history")
                        .executes(context -> {
                            UUID playerUuid = context.getSource().getPlayer().getUuid();
                            int count = fun.ollamachat.OllamaChatHistory.getHistoryCount(playerUuid);
                            context.getSource().sendFeedback(() -> Text.translatable("command.ollama.status.history_count", count), false);
                            return 1;
                        })
                        .then(CommandManager.literal("clear")
                                .executes(context -> {
                                    UUID playerUuid = context.getSource().getPlayer().getUuid();
                                    boolean success = fun.ollamachat.OllamaChatHistory.clearHistory(playerUuid);
                                    // 同时清除 LM Studio 的 response_id
                                    fun.ollamachat.OllamaChatHistory.clearResponseId(playerUuid);
                                    if (success) {
                                        context.getSource().sendFeedback(() -> Text.translatable("command.ollama.status.history_cleared"), false);
                                    } else {
                                        context.getSource().sendError(Text.translatable("command.ollama.error.history_clear_failed"));
                                    }
                                    return 1;
                                })))
        );
    }

    private static void listModels(ServerCommandSource source) {
        executeCommand(source, "list", "command.ollama.status.list_success");
    }

    private static void serveOllama(ServerCommandSource source) {
        executeCommand(source, "serve", "command.ollama.status.service_started");
    }

    private static void listRunningModels(ServerCommandSource source) {
        executeCommand(source, "ps", "command.ollama.status.ps_success");
    }

    private static void executeCommand(ServerCommandSource source, String subCommand, String successMessage) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("ollama", subCommand);
            Process process = processBuilder.start();
            CountDownLatch streamsLatch = new CountDownLatch(2);

            Thread errorThread = new Thread(() -> {
                try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String errorLine;
                    while ((errorLine = errorReader.readLine()) != null) {
                        final String errLine = errorLine;
                        source.sendFeedback(() -> Text.of("[Ollama Error] " + errLine), false);
                    }
                } catch (Exception e) {
                    source.sendFeedback(() -> Text.translatable("command.ollama.error.generic"), false);
                } finally {
                    streamsLatch.countDown();
                }
            });
            errorThread.start();

            Thread outputThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        final String outLine = line;
                        source.sendFeedback(() -> Text.of(outLine), false);
                    }
                } catch (Exception e) {
                    // ignore
                } finally {
                    streamsLatch.countDown();
                }
            });
            outputThread.start();

            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                source.sendFeedback(() -> Text.translatable("command.ollama.error.timeout"), false);
                process.destroy();
                errorThread.interrupt();
                outputThread.interrupt();
                return;
            }

            if (!streamsLatch.await(2, TimeUnit.SECONDS)) {
                errorThread.interrupt();
                outputThread.interrupt();
            }

            if (process.exitValue() == 0) {
                source.sendFeedback(() -> Text.translatable(successMessage), false);
            } else {
                source.sendFeedback(() -> Text.translatable("command.ollama.error.generic"), false);
            }
        } catch (Exception e) {
            source.sendFeedback(() -> Text.translatable("command.ollama.error.generic"), false);
        }
    }
}
