package fun.ollamachat.server;

import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.network.message.MessageType;
import net.minecraft.text.Text;

public class ServerMessageHandler {

    /**
     * 处理服务端聊天消息
     * 识别 "ai " 前缀，触发 AI 请求
     */
    public static void onChatMessage(SignedMessage signedMessage, ServerPlayerEntity player, MessageType.Parameters parameters) {
        String messageText = signedMessage.getSignedContent();

        if (messageText.startsWith("ai ")) {
            String userInput = messageText.substring(3);
            if (userInput.trim().isEmpty()) return;

            java.util.UUID playerUuid = player.getUuid();

            fun.ollamachat.OllamaHttpClient.handleAIRequestAsync(userInput, playerUuid,
                    new fun.ollamachat.OllamaHttpClient.AIResponseCallback() {
                        @Override
                        public void onSuccess(fun.ollamachat.OllamaHttpClient.AIResponse response) {
                            player.getServer().execute(() -> {
                                if (response.hasThinking()) {
                                    Text aiText = fun.ollamachat.OllamaHttpClient.buildAIText(response);
                                    player.sendMessage(aiText);
                                } else {
                                    player.sendMessage(Text.literal("[AI] " + response.response));
                                }
                            });
                        }

                        @Override
                        public void onError(String error) {
                            player.getServer().execute(() -> {
                                player.sendMessage(Text.literal("[AI] " + error));
                            });
                        }
                    });
        }
    }
}
