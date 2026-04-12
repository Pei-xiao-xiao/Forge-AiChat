package fun.xingwangzhe.ollamachat.client;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.message.MessageType;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public class OllamaMessageHandler {
    public static void initialize() {
        ClientReceiveMessageEvents.CHAT.register(OllamaMessageHandler::onReceivedMessage);
        ClientSendMessageEvents.CHAT.register(OllamaMessageHandler::onSentMessage);
    }

    private static void onReceivedMessage(Text text, @Nullable SignedMessage signedMessage,
                                          @Nullable GameProfile gameProfile,
                                          MessageType.Parameters parameters, Instant instant) {
        if (signedMessage == null) return;

        String senderName = gameProfile != null ? gameProfile.getName() : "Unknown";
        if (senderName.equals("[AI]")) return;

        String messageText = signedMessage.getSignedContent();
        boolean isClientMessage = senderName.equals(MinecraftClient.getInstance().getSession().getUsername());

        if (!isClientMessage && messageText.startsWith("ai ")) {
            UUID playerUuid = gameProfile != null ? gameProfile.getId() : UUID.nameUUIDFromBytes(senderName.getBytes());
            fun.xingwangzhe.ollamachat.OllamaHttpClient.handleAIRequestAsync(
                    messageText.substring(3), playerUuid,
                    new fun.xingwangzhe.ollamachat.OllamaHttpClient.AIResponseCallback() {
                        @Override
                        public void onSuccess(fun.xingwangzhe.ollamachat.OllamaHttpClient.AIResponse response) {
                            MinecraftClient.getInstance().execute(() -> {
                                if (response.hasThinking()) {
                                    // 有思考内容时发送带 tooltip 的富文本
                                    Text aiText = fun.xingwangzhe.ollamachat.OllamaHttpClient.buildAIText(response);
                                    MinecraftClient.getInstance().player.sendMessage(aiText);
                                } else {
                                    // 无思考内容时用普通聊天消息发送
                                    MinecraftClient.getInstance().player.networkHandler.sendChatMessage("[AI] " + response.response);
                                }
                            });
                        }

                        @Override
                        public void onError(String error) {
                            MinecraftClient.getInstance().execute(() -> {
                                MinecraftClient.getInstance().player.networkHandler.sendChatMessage("[AI] " + error);
                            });
                        }
                    });
        }
    }

    private static boolean onSentMessage(String message) {
        if (message.startsWith("ai ")) {
            UUID playerUuid = MinecraftClient.getInstance().player.getUuid();
            fun.xingwangzhe.ollamachat.OllamaHttpClient.handleAIRequestAsync(
                    message.substring(3), playerUuid,
                    new fun.xingwangzhe.ollamachat.OllamaHttpClient.AIResponseCallback() {
                        @Override
                        public void onSuccess(fun.xingwangzhe.ollamachat.OllamaHttpClient.AIResponse response) {
                            MinecraftClient.getInstance().execute(() -> {
                                if (response.hasThinking()) {
                                    Text aiText = fun.xingwangzhe.ollamachat.OllamaHttpClient.buildAIText(response);
                                    MinecraftClient.getInstance().player.sendMessage(aiText);
                                } else {
                                    MinecraftClient.getInstance().player.networkHandler.sendChatMessage("[AI] " + response.response);
                                }
                            });
                        }

                        @Override
                        public void onError(String error) {
                            MinecraftClient.getInstance().execute(() -> {
                                MinecraftClient.getInstance().player.networkHandler.sendChatMessage("[AI] " + error);
                            });
                        }
                    });
            return false; // 拦截原始消息，不发送到服务器
        }
        return true;
    }
}
