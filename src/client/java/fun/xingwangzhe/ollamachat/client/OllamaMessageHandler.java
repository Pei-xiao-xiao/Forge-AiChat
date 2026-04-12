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

        OllamaDebugTracker.setMessageSource(isClientMessage);

        if (!isClientMessage && messageText.startsWith("ai ")) {
            OllamaHttpClient.handleAIRequest(messageText.substring(3), isClientMessage);
        }
    }

    private static boolean onSentMessage(String message) {
        if (message.startsWith("ai ")) {
            OllamaDebugTracker.setMessageSource(true);
            OllamaHttpClient.handleAIRequest(message.substring(3), true);
            return false;
        }
        return true;
    }
}