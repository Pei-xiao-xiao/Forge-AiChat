package fun.ollamachat.client;

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

public class OllamaCommandHandler {
    private static final ExecutorService COMMAND_EXECUTOR = Executors.newFixedThreadPool(2);

    private static final SuggestionProvider<FabricClientCommandSource> MODEL_SUGGESTIONS = (context, builder) -> {
        try {
            Process process = new ProcessBuilder("ollama", "list").start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                reader.readLine(); // Skip header
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        String modelName = line.split("\\s+")[0];
                        builder.suggest(modelName);
                    }
                }
            }
        } catch (Exception e) {
            builder.suggest(Text.translatable("command.ollama.error.list_failed").getString());
        }
        return CompletableFuture.completedFuture(builder.build());
    };

    public static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal("ollama")
                // list - 列出模型
                .then(ClientCommandManager.literal("list")
                        .executes(context -> {
                            COMMAND_EXECUTOR.submit(() -> listModels(context.getSource()));
                            context.getSource().sendFeedback(Text.translatable("command.ollama.status.list_running"));
                            return 1;
                        }))
                // serve - 启动服务
                .then(ClientCommandManager.literal("serve")
                        .executes(context -> {
                            COMMAND_EXECUTOR.submit(() -> serveOllama(context.getSource()));
                            context.getSource().sendFeedback(Text.translatable("command.ollama.status.serve_starting"));
                            return 1;
                        }))
                // ps - 列出运行中的模型
                .then(ClientCommandManager.literal("ps")
                        .executes(context -> {
                            COMMAND_EXECUTOR.submit(() -> listRunningModels(context.getSource()));
                            context.getSource().sendFeedback(Text.translatable("command.ollama.status.ps_running"));
                            return 1;
                        }))
                // refresh - 刷新模型列表
                .then(ClientCommandManager.literal("refresh")
                        .executes(context -> {
                            COMMAND_EXECUTOR.submit(() -> {
                                fun.ollamachat.OllamaModelManager.updateModelsFromSystem();
                                context.getSource().sendFeedback(Text.translatable("command.ollama.status.models_refreshed"));
                            });
                            return 1;
                        }))
                // model - 设置模型
                .then(ClientCommandManager.literal("model")
                        .then(ClientCommandManager.argument("modelname", StringArgumentType.greedyString())
                                .suggests(MODEL_SUGGESTIONS)
                                .executes(context -> {
                                    String modelName = StringArgumentType.getString(context, "modelname").trim();
                                    if (fun.ollamachat.OllamaModelManager.isModelValid(modelName)) {
                                        fun.ollamachat.OllamaModelManager.setCurrentModel(modelName);
                                        context.getSource().sendFeedback(Text.translatable("command.ollama.status.model_set", modelName));
                                    } else {
                                        context.getSource().sendError(Text.translatable("command.ollama.error.model_not_found"));
                                    }
                                    return 1;
                                })))
                // api - 设置 API 地址
                .then(ClientCommandManager.literal("api")
                        .executes(context -> {
                            String currentUrl = fun.ollamachat.OllamaConfig.getApiUrl();
                            context.getSource().sendFeedback(Text.translatable("command.ollama.status.api_status", currentUrl));
                            return 1;
                        })
                        .then(ClientCommandManager.argument("url", StringArgumentType.greedyString())
                                .executes(context -> {
                                    String url = StringArgumentType.getString(context, "url").trim();
                                    fun.ollamachat.OllamaConfig.setApiUrl(url);
                                    String provider = fun.ollamachat.OllamaConfig.getApiProvider().name();
                                    context.getSource().sendFeedback(Text.translatable("command.ollama.status.api_set_with_provider", url, provider));
                                    return 1;
                                })))
                // provider - 设置 API 提供者类型
                .then(ClientCommandManager.literal("provider")
                        .executes(context -> {
                            fun.ollamachat.OllamaConfig.ApiProvider current = fun.ollamachat.OllamaConfig.getApiProvider();
                            context.getSource().sendFeedback(Text.translatable("command.ollama.status.provider_status", current.name()));
                            return 1;
                        })
                        .then(ClientCommandManager.literal("ollama")
                                .executes(context -> {
                                    fun.ollamachat.OllamaConfig.setApiProvider(fun.ollamachat.OllamaConfig.ApiProvider.OLLAMA);
                                    context.getSource().sendFeedback(Text.translatable("command.ollama.status.provider_set", "Ollama"));
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("openai")
                                .executes(context -> {
                                    fun.ollamachat.OllamaConfig.setApiProvider(fun.ollamachat.OllamaConfig.ApiProvider.OPENAI);
                                    context.getSource().sendFeedback(Text.translatable("command.ollama.status.provider_set", "OpenAI Compatible"));
                                    return 1;
                                })))
                // key - 设置 API Key
                .then(ClientCommandManager.literal("key")
                        .executes(context -> {
                            String masked = fun.ollamachat.OllamaConfig.getMaskedApiKey();
                            context.getSource().sendFeedback(Text.translatable("command.ollama.status.key_status", masked));
                            return 1;
                        })
                        .then(ClientCommandManager.argument("apikey", StringArgumentType.greedyString())
                                .executes(context -> {
                                    String key = StringArgumentType.getString(context, "apikey").trim();
                                    fun.ollamachat.OllamaConfig.setApiKey(key);
                                    context.getSource().sendFeedback(Text.translatable("command.ollama.status.key_set"));
                                    return 1;
                                })))
                // think - 深度思考开关
                .then(ClientCommandManager.literal("think")
                        .executes(context -> {
                            boolean enabled = fun.ollamachat.OllamaModelManager.isThinkEnabled();
                            context.getSource().sendFeedback(Text.translatable("command.ollama.status.think_status", enabled ? "ON" : "OFF"));
                            return 1;
                        })
                        .then(ClientCommandManager.literal("on")
                                .executes(context -> {
                                    fun.ollamachat.OllamaModelManager.setThinkEnabled(true);
                                    context.getSource().sendFeedback(Text.translatable("command.ollama.status.think_enabled"));
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("off")
                                .executes(context -> {
                                    fun.ollamachat.OllamaModelManager.setThinkEnabled(false);
                                    context.getSource().sendFeedback(Text.translatable("command.ollama.status.think_disabled"));
                                    return 1;
                                })))
                // search - 在线搜索开关
                .then(ClientCommandManager.literal("search")
                        .executes(context -> {
                            boolean enabled = fun.ollamachat.OllamaModelManager.isSearchEnabled();
                            context.getSource().sendFeedback(Text.translatable("command.ollama.status.search_status", enabled ? "ON" : "OFF"));
                            return 1;
                        })
                        .then(ClientCommandManager.literal("on")
                                .executes(context -> {
                                    fun.ollamachat.OllamaModelManager.setSearchEnabled(true);
                                    context.getSource().sendFeedback(Text.translatable("command.ollama.status.search_enabled"));
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("off")
                                .executes(context -> {
                                    fun.ollamachat.OllamaModelManager.setSearchEnabled(false);
                                    context.getSource().sendFeedback(Text.translatable("command.ollama.status.search_disabled"));
                                    return 1;
                                })))
                // context - 设置上下文轮数
                .then(ClientCommandManager.literal("context")
                        .executes(context -> {
                            int rounds = fun.ollamachat.OllamaConfig.getContextRounds();
                            context.getSource().sendFeedback(Text.translatable("command.ollama.status.context_status", rounds));
                            return 1;
                        })
                        .then(ClientCommandManager.argument("rounds", IntegerArgumentType.integer(0, 50))
                                .executes(context -> {
                                    int rounds = IntegerArgumentType.getInteger(context, "rounds");
                                    fun.ollamachat.OllamaConfig.setContextRounds(rounds);
                                    context.getSource().sendFeedback(Text.translatable("command.ollama.status.context_set", rounds));
                                    return 1;
                                })))
                // history - 聊天历史管理
                .then(ClientCommandManager.literal("history")
                        .executes(context -> {
                            UUID playerUuid = MinecraftClient.getInstance().player.getUuid();
                            int count = fun.ollamachat.OllamaChatHistory.getHistoryCount(playerUuid);
                            context.getSource().sendFeedback(Text.translatable("command.ollama.status.history_count", count));
                            return 1;
                        })
                        .then(ClientCommandManager.literal("clear")
                                .executes(context -> {
                                    UUID playerUuid = MinecraftClient.getInstance().player.getUuid();
                                    boolean success = fun.ollamachat.OllamaChatHistory.clearHistory(playerUuid);
                                    if (success) {
                                        context.getSource().sendFeedback(Text.translatable("command.ollama.status.history_cleared"));
                                    } else {
                                        context.getSource().sendError(Text.translatable("command.ollama.error.history_clear_failed"));
                                    }
                                    return 1;
                                })))
        );
    }

    private static void listModels(FabricClientCommandSource source) {
        executeCommand(source, "list", "command.ollama.status.list_success");
    }

    private static void serveOllama(FabricClientCommandSource source) {
        executeCommand(source, "serve", "command.ollama.status.service_started");
    }

    private static void listRunningModels(FabricClientCommandSource source) {
        executeCommand(source, "ps", "command.ollama.status.ps_success");
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
                    source.sendFeedback(Text.translatable("command.ollama.error.generic"));
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
                source.sendFeedback(Text.translatable("command.ollama.error.timeout"));
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
                source.sendFeedback(Text.translatable("command.ollama.error.generic"));
            }
        } catch (Exception e) {
            source.sendFeedback(Text.translatable("command.ollama.error.generic"));
        }
    }
}
