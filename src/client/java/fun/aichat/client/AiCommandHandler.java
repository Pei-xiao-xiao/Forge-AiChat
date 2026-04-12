package fun.aichat.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.UUID;
import java.util.concurrent.*;

public class AiCommandHandler {
    private static final ExecutorService COMMAND_EXECUTOR = Executors.newFixedThreadPool(2);

    private static final SuggestionProvider<FabricClientCommandSource> MODEL_SUGGESTIONS = (context, builder) -> {
        for (String model : fun.aichat.AiModelManager.getCachedModels()) {
            builder.suggest(model);
        }
        return CompletableFuture.completedFuture(builder.build());
    };

    public static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal("ai")
                // 无参数时显示帮助
                .executes(context -> {
                    showHelp(context.getSource());
                    return 1;
                })
                // help / ? - 显示帮助
                .then(ClientCommandManager.literal("help")
                        .executes(context -> {
                            showHelp(context.getSource());
                            return 1;
                        }))
                .then(ClientCommandManager.literal("?")
                        .executes(context -> {
                            showHelp(context.getSource());
                            return 1;
                        }))
                // list - 列出模型
                .then(ClientCommandManager.literal("list")
                        .executes(context -> {
                            COMMAND_EXECUTOR.submit(() -> {
                                fun.aichat.AiModelManager.refreshModels();
                                java.util.List<String> models = fun.aichat.AiModelManager.getCachedModels();
                                if (!models.isEmpty()) {
                                    for (String model : models) {
                                        context.getSource().sendFeedback(Text.literal("  - " + model));
                                    }
                                    context.getSource().sendFeedback(Text.translatable("command.ai.status.list_success"));
                                } else {
                                    // Ollama 模式下回退到命令行
                                    if (fun.aichat.AiConfig.getApiProvider() == fun.aichat.AiConfig.ApiProvider.OLLAMA) {
                                        listModels(context.getSource());
                                    } else {
                                        context.getSource().sendError(Text.translatable("command.ai.error.model_fetch_failed"));
                                    }
                                }
                            });
                            return 1;
                        }))
                // serve - 启动服务
                .then(ClientCommandManager.literal("serve")
                        .executes(context -> {
                            COMMAND_EXECUTOR.submit(() -> serveOllama(context.getSource()));
                            context.getSource().sendFeedback(Text.translatable("command.ai.status.serve_starting"));
                            return 1;
                        }))
                // ps - 列出运行中的模型
                .then(ClientCommandManager.literal("ps")
                        .executes(context -> {
                            COMMAND_EXECUTOR.submit(() -> listRunningModels(context.getSource()));
                            context.getSource().sendFeedback(Text.translatable("command.ai.status.ps_running"));
                            return 1;
                        }))
                // refresh - 刷新模型列表
                .then(ClientCommandManager.literal("refresh")
                        .executes(context -> {
                            COMMAND_EXECUTOR.submit(() -> {
                                fun.aichat.AiModelManager.refreshModels();
                                java.util.List<String> models = fun.aichat.AiModelManager.getCachedModels();
                                context.getSource().sendFeedback(Text.translatable("command.ai.status.models_refreshed_api", models.size()));
                            });
                            return 1;
                        }))
                // model - 设置模型
                .then(ClientCommandManager.literal("model")
                        .then(ClientCommandManager.argument("modelname", StringArgumentType.greedyString())
                                .suggests(MODEL_SUGGESTIONS)
                                .executes(context -> {
                                    String modelName = StringArgumentType.getString(context, "modelname").trim();
                                    if (fun.aichat.AiModelManager.isModelValid(modelName)) {
                                        fun.aichat.AiModelManager.setCurrentModel(modelName);
                                        context.getSource().sendFeedback(Text.translatable("command.ai.status.model_set", modelName));
                                    } else {
                                        context.getSource().sendError(Text.translatable("command.ai.error.model_not_found"));
                                    }
                                    return 1;
                                })))
                // api - 设置 API 地址
                .then(ClientCommandManager.literal("api")
                        .executes(context -> {
                            String currentUrl = fun.aichat.AiConfig.getApiUrl();
                            context.getSource().sendFeedback(Text.translatable("command.ai.status.api_status", currentUrl));
                            return 1;
                        })
                        .then(ClientCommandManager.argument("url", StringArgumentType.greedyString())
                                .executes(context -> {
                                    String url = StringArgumentType.getString(context, "url").trim();
                                    fun.aichat.AiConfig.setApiUrl(url);
                                    String provider = fun.aichat.AiConfig.getApiProvider().name();
                                    context.getSource().sendFeedback(Text.translatable("command.ai.status.api_set_with_provider", url, provider));
                                    return 1;
                                })))
                // provider - 设置 API 提供者类型
                .then(ClientCommandManager.literal("provider")
                        .executes(context -> {
                            fun.aichat.AiConfig.ApiProvider current = fun.aichat.AiConfig.getApiProvider();
                            context.getSource().sendFeedback(Text.translatable("command.ai.status.provider_status", current.name()));
                            return 1;
                        })
                        .then(ClientCommandManager.literal("ollama")
                                .executes(context -> {
                                    fun.aichat.AiConfig.setApiProvider(fun.aichat.AiConfig.ApiProvider.OLLAMA);
                                    context.getSource().sendFeedback(Text.translatable("command.ai.status.provider_set", "Ollama"));
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("openai")
                                .executes(context -> {
                                    fun.aichat.AiConfig.setApiProvider(fun.aichat.AiConfig.ApiProvider.OPENAI);
                                    context.getSource().sendFeedback(Text.translatable("command.ai.status.provider_set", "OpenAI Compatible"));
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("lmstudio")
                                .executes(context -> {
                                    fun.aichat.AiConfig.setApiProvider(fun.aichat.AiConfig.ApiProvider.LMSTUDIO);
                                    context.getSource().sendFeedback(Text.translatable("command.ai.status.provider_set", "LM Studio"));
                                    return 1;
                                })))
                // key - 设置 API Key
                .then(ClientCommandManager.literal("key")
                        .executes(context -> {
                            String masked = fun.aichat.AiConfig.getMaskedApiKey();
                            context.getSource().sendFeedback(Text.translatable("command.ai.status.key_status", masked));
                            return 1;
                        })
                        .then(ClientCommandManager.argument("apikey", StringArgumentType.greedyString())
                                .executes(context -> {
                                    String key = StringArgumentType.getString(context, "apikey").trim();
                                    fun.aichat.AiConfig.setApiKey(key);
                                    context.getSource().sendFeedback(Text.translatable("command.ai.status.key_set"));
                                    return 1;
                                })))
                // think - 深度思考开关
                .then(ClientCommandManager.literal("think")
                        .executes(context -> {
                            boolean enabled = fun.aichat.AiModelManager.isThinkEnabled();
                            context.getSource().sendFeedback(Text.translatable("command.ai.status.think_status", enabled ? "ON" : "OFF"));
                            return 1;
                        })
                        .then(ClientCommandManager.literal("on")
                                .executes(context -> {
                                    fun.aichat.AiModelManager.setThinkEnabled(true);
                                    context.getSource().sendFeedback(Text.translatable("command.ai.status.think_enabled"));
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("off")
                                .executes(context -> {
                                    fun.aichat.AiModelManager.setThinkEnabled(false);
                                    context.getSource().sendFeedback(Text.translatable("command.ai.status.think_disabled"));
                                    return 1;
                                })))
                // search - 在线搜索开关
                .then(ClientCommandManager.literal("search")
                        .executes(context -> {
                            boolean enabled = fun.aichat.AiModelManager.isSearchEnabled();
                            context.getSource().sendFeedback(Text.translatable("command.ai.status.search_status", enabled ? "ON" : "OFF"));
                            return 1;
                        })
                        .then(ClientCommandManager.literal("on")
                                .executes(context -> {
                                    fun.aichat.AiModelManager.setSearchEnabled(true);
                                    context.getSource().sendFeedback(Text.translatable("command.ai.status.search_enabled"));
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("off")
                                .executes(context -> {
                                    fun.aichat.AiModelManager.setSearchEnabled(false);
                                    context.getSource().sendFeedback(Text.translatable("command.ai.status.search_disabled"));
                                    return 1;
                                })))
                // context - 设置上下文轮数
                .then(ClientCommandManager.literal("context")
                        .executes(context -> {
                            int rounds = fun.aichat.AiConfig.getContextRounds();
                            context.getSource().sendFeedback(Text.translatable("command.ai.status.context_status", rounds));
                            return 1;
                        })
                        .then(ClientCommandManager.argument("rounds", IntegerArgumentType.integer(0, 50))
                                .executes(context -> {
                                    int rounds = IntegerArgumentType.getInteger(context, "rounds");
                                    fun.aichat.AiConfig.setContextRounds(rounds);
                                    context.getSource().sendFeedback(Text.translatable("command.ai.status.context_set", rounds));
                                    return 1;
                                })))
                // history - 聊天历史管理
                .then(ClientCommandManager.literal("history")
                        .executes(context -> {
                            UUID playerUuid = MinecraftClient.getInstance().player.getUuid();
                            int count = fun.aichat.AiChatHistory.getHistoryCount(playerUuid);
                            context.getSource().sendFeedback(Text.translatable("command.ai.status.history_count", count));
                            return 1;
                        })
                        .then(ClientCommandManager.literal("clear")
                                .executes(context -> {
                                    UUID playerUuid = MinecraftClient.getInstance().player.getUuid();
                                    boolean success = fun.aichat.AiChatHistory.clearHistory(playerUuid);
                                    // 同时清除 LM Studio 的 response_id
                                    fun.aichat.AiChatHistory.clearResponseId(playerUuid);
                                    if (success) {
                                        context.getSource().sendFeedback(Text.translatable("command.ai.status.history_cleared"));
                                    } else {
                                        context.getSource().sendError(Text.translatable("command.ai.error.history_clear_failed"));
                                    }
                                    return 1;
                                })))
        );
    }

    private static void showHelp(FabricClientCommandSource source) {
        for (int i = 1; i <= 12; i++) {
            source.sendFeedback(Text.translatable("command.ai.help.line" + i));
        }
    }

    private static void listModels(FabricClientCommandSource source) {
        executeCommand(source, "list", "command.ai.status.list_success");
    }

    private static void serveOllama(FabricClientCommandSource source) {
        executeCommand(source, "serve", "command.ai.status.service_started");
    }

    private static void listRunningModels(FabricClientCommandSource source) {
        executeCommand(source, "ps", "command.ai.status.ps_success");
    }

    private static void executeCommand(FabricClientCommandSource source, String subCommand, String successMessage) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("ollama", subCommand);
            Process process = processBuilder.start();
            CountDownLatch streamsLatch = new CountDownLatch(2);

            Thread errorThread = new Thread(() -> {
                try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String errorLine;
                    while ((errorLine = errorReader.readLine()) != null) {
                        source.sendFeedback(Text.of("[Ollama Error] " + errorLine));
                    }
                } catch (Exception e) {
                    source.sendFeedback(Text.translatable("command.ai.error.generic"));
                } finally {
                    streamsLatch.countDown();
                }
            });
            errorThread.start();

            Thread outputThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        source.sendFeedback(Text.of(line));
                    }
                } catch (Exception e) {
                    // ignore
                } finally {
                    streamsLatch.countDown();
                }
            });
            outputThread.start();

            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                source.sendFeedback(Text.translatable("command.ai.error.timeout"));
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
                source.sendFeedback(Text.translatable(successMessage));
            } else {
                source.sendFeedback(Text.translatable("command.ai.error.generic"));
            }
        } catch (Exception e) {
            source.sendFeedback(Text.translatable("command.ai.error.generic"));
        }
    }
}
