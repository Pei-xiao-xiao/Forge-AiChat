package fun.aichat.client;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.message.MessageType;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public class AiMessageHandler {
    public static void initialize() {
        ClientReceiveMessageEvents.CHAT.register(AiMessageHandler::onReceivedMessage);
        ClientSendMessageEvents.CHAT.register(AiMessageHandler::onSentMessage);
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
            handleStreamChat(messageText.substring(3), playerUuid);
        }
    }

    private static boolean onSentMessage(String message) {
        if (message.startsWith("ai ")) {
            UUID playerUuid = MinecraftClient.getInstance().player.getUuid();
            handleStreamChat(message.substring(3), playerUuid);
            return false; // 拦截原始消息
        }
        return true;
    }

    /**
     * 流式聊天：先显示 [AI] 光标，然后逐字更新文本
     */
    private static void handleStreamChat(String userInput, UUID playerUuid) {
        MinecraftClient client = MinecraftClient.getInstance();

        // 在主线程发送初始占位消息
        client.execute(() -> {
            if (client.player == null) return;

            // 发送带闪烁光标的初始消息
            Text initialMsg = Text.literal("[AI] \u258C")  // ▌ 光标
                    .styled(style -> style.withColor(0x55FF55)); // 绿色
            client.player.sendMessage(initialMsg, false);
        });

        fun.aichat.AiHttpClient.handleAIRequestStream(userInput, playerUuid,
                new fun.aichat.AiHttpClient.AIStreamCallback() {
                    private final StringBuilder responseBuilder = new StringBuilder();
                    private final StringBuilder thinkingBuilder = new StringBuilder();
                    private long lastUpdateTime = 0;
                    private static final long UPDATE_INTERVAL_MS = 80; // 每 80ms 更新一次显示

                    @Override
                    public void onToken(String token) {
                        responseBuilder.append(token);
                        throttledUpdate();
                    }

                    @Override
                    public void onThinkingToken(String token) {
                        thinkingBuilder.append(token);
                    }

                    @Override
                    public void onComplete(fun.aichat.AiHttpClient.AIResponse response) {
                        // 最终完整显示
                        client.execute(() -> {
                            if (client.player == null) return;

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
                            client.player.sendMessage(finalText, false);
                        });
                    }

                    @Override
                    public void onError(String error) {
                        client.execute(() -> {
                            if (client.player != null) {
                                client.player.sendMessage(Text.literal("[AI] " + error)
                                        .styled(style -> style.withColor(0xFF5555)), false);
                            }
                        });
                    }

                    private void throttledUpdate() {
                        long now = System.currentTimeMillis();
                        if (now - lastUpdateTime < UPDATE_INTERVAL_MS) return;
                        lastUpdateTime = now;

                        client.execute(() -> {
                            if (client.player == null) return;

                            String currentText = responseBuilder.toString();
                            // 限制显示长度避免过长
                            if (currentText.length() > 200) {
                                currentText = currentText.substring(currentText.length() - 200);
                            }

                            Text progressMsg = Text.literal("[AI] " + currentText + "\u258C")
                                    .styled(style -> style.withColor(0x55FF55));
                            client.player.sendMessage(progressMsg, false);
                        });
                    }
                });
    }
}
