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
     * AI 聊天：发送请求，完成后一次性显示完整回复
     * （Minecraft 聊天框不支持替换已有行，流式中间更新会导致消息堆叠）
     */
    private static void handleStreamChat(String userInput, UUID playerUuid) {
        MinecraftClient client = MinecraftClient.getInstance();

        // 发送一个"思考中"的占位消息
        client.execute(() -> {
            if (client.player == null) return;
            Text thinkingMsg = Text.literal("[AI] 思考中...")
                    .styled(style -> style.withColor(0xAAAAAA)); // 灰色
            client.player.sendMessage(thinkingMsg, false);
        });

        fun.aichat.AiHttpClient.handleAIRequestStream(userInput, playerUuid,
                new fun.aichat.AiHttpClient.AIStreamCallback() {

                    @Override
                    public void onToken(String token) {
                        // 收到 token 但不在聊天框逐字显示（避免消息堆叠）
                        // token 已由 AiHttpClient 内部拼接为完整响应
                    }

                    @Override
                    public void onThinkingToken(String token) {
                        // 思考过程静默收集
                    }

                    @Override
                    public void onComplete(fun.aichat.AiHttpClient.AIResponse response) {
                        // 最终一次性显示完整回复（Markdown 渲染为富文本）
                        client.execute(() -> {
                            if (client.player == null) return;

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

                            client.player.sendMessage(result, false);
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
                });
    }
}
