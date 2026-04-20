package fun.aichat.server;

import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.network.message.MessageType;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;

public class ServerMessageHandler {

    /**
     * 处理服务端聊天消息（多人服务器场景）
     * 识别 "ai " 前缀，触发 AI 流式请求
     *
     * 注意：单人游戏中客户端已处理，此方法会跳过（isIntegratedServer 检查）
     */
    public static void onChatMessage(SignedMessage signedMessage, ServerPlayerEntity player, MessageType.Parameters parameters) {
        String messageText = signedMessage.getSignedContent();

        if (messageText.startsWith("ai ")) {
            String userInput = messageText.substring(3);
            if (userInput.trim().isEmpty()) return;

            // 避免双重处理：单人游戏时客户端已处理
            if (isIntegratedServer(player)) {
                return;
            }

            java.util.UUID playerUuid = player.getUuid();

            // 发送"思考中"占位消息
            Text thinkingMsg = Text.literal("[AI] 思考中...")
                    .styled(style -> style.withColor(0xAAAAAA)); // 灰色
            player.sendMessage(thinkingMsg, false);

            fun.aichat.AiHttpClient.handleAIRequestStream(userInput, playerUuid,
                    new fun.aichat.AiHttpClient.AIStreamCallback() {

                        @Override
                        public void onToken(String token) {
                            // 不逐字显示，避免消息堆叠
                        }

                        @Override
                        public void onThinkingToken(String token) {
                            // 静默收集思考内容
                        }

                        @Override
                        public void onComplete(fun.aichat.AiHttpClient.AIResponse response) {
                            player.getServer().execute(() -> {
                                String rawText = response.response;
                                if (rawText == null || rawText.trim().isEmpty()) {
                                    rawText = "(AI 返回了空响应)";
                                }

                                // 将 Markdown 转为 Minecraft 富文本
                                Text contentText = fun.aichat.AiHttpClient.renderMarkdownToText(rawText);

                                net.minecraft.text.MutableText result = Text.literal("")
                                        .append(Text.literal("[AI] ").styled(s -> s.withColor(0x55FF55).withBold(true)))
                                        .append(contentText);

                                if (response.hasThinking()) {
                                    Text thinkingText = Text.literal(response.thinking);
                                    result.styled(style -> style.withHoverEvent(
                                            new HoverEvent(HoverEvent.Action.SHOW_TEXT, thinkingText)));
                                }

                                player.sendMessage(result, false);
                            });
                        }

                        @Override
                        public void onError(String error) {
                            player.getServer().execute(() -> {
                                player.sendMessage(Text.literal("[AI] " + error)
                                        .styled(style -> style.withColor(0xFF5555)), false);
                            });
                        }
                    });
        }
    }

    /**
     * 判断是否为集成服务器（单人游戏）
     * 集成服务器中客户端已处理 AI 请求，服务端无需重复处理
     * 
     * IntegratedServer 在客户端 sourceSet，main sourceSet 无法直接引用，
     * 所以用类名字符串比较来检测
     */
    private static boolean isIntegratedServer(ServerPlayerEntity player) {
        if (player.getServer() == null) return false;
        String serverClassName = player.getServer().getClass().getName();
        return serverClassName.contains("IntegratedServer");
    }
}
