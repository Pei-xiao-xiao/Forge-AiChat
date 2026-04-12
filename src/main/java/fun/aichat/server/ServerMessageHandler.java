package fun.aichat.server;

import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.network.message.MessageType;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;

public class ServerMessageHandler {

    /**
     * 处理服务端聊天消息
     * 识别 "ai " 前缀，触发 AI 流式请求
     */
    public static void onChatMessage(SignedMessage signedMessage, ServerPlayerEntity player, MessageType.Parameters parameters) {
        String messageText = signedMessage.getSignedContent();

        if (messageText.startsWith("ai ")) {
            String userInput = messageText.substring(3);
            if (userInput.trim().isEmpty()) return;

            java.util.UUID playerUuid = player.getUuid();

            // 发送初始占位消息
            Text initialMsg = Text.literal("[AI] \u258C")
                    .styled(style -> style.withColor(0x55FF55));
            player.sendMessage(initialMsg, false);

            fun.aichat.AiHttpClient.handleAIRequestStream(userInput, playerUuid,
                    new fun.aichat.AiHttpClient.AIStreamCallback() {
                        private final StringBuilder responseBuilder = new StringBuilder();
                        private final StringBuilder thinkingBuilder = new StringBuilder();
                        private long lastUpdateTime = 0;
                        private static final long UPDATE_INTERVAL_MS = 100;

                        @Override
                        public void onToken(String token) {
                            responseBuilder.append(token);
                            throttledUpdate(player);
                        }

                        @Override
                        public void onThinkingToken(String token) {
                            thinkingBuilder.append(token);
                        }

                        @Override
                        public void onComplete(fun.aichat.AiHttpClient.AIResponse response) {
                            player.getServer().execute(() -> {
                                Text finalText;
                                if (response.hasThinking()) {
                                    Text thinkingText = Text.literal(response.thinking);
                                    finalText = Text.literal("[AI] " + response.response)
                                            .styled(style -> style.withHoverEvent(
                                                    new HoverEvent(HoverEvent.Action.SHOW_TEXT, thinkingText)));
                                } else {
                                    finalText = Text.literal("[AI] " + response.response)
                                            .styled(style -> style.withColor(0x55FF55));
                                }
                                player.sendMessage(finalText, false);
                            });
                        }

                        @Override
                        public void onError(String error) {
                            player.getServer().execute(() -> {
                                player.sendMessage(Text.literal("[AI] " + error)
                                        .styled(style -> style.withColor(0xFF5555)), false);
                            });
                        }

                        private void throttledUpdate(ServerPlayerEntity player) {
                            long now = System.currentTimeMillis();
                            if (now - lastUpdateTime < UPDATE_INTERVAL_MS) return;
                            lastUpdateTime = now;

                            player.getServer().execute(() -> {
                                String currentText = responseBuilder.toString();
                                if (currentText.length() > 200) {
                                    currentText = currentText.substring(currentText.length() - 200);
                                }

                                Text progressMsg = Text.literal("[AI] " + currentText + "\u258C")
                                        .styled(style -> style.withColor(0x55FF55));
                                player.sendMessage(progressMsg, false);
                            });
                        }
                    });
        }
    }
}
