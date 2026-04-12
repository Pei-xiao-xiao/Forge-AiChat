package fun.ollamachat.server;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;

public class OllamachatServer implements DedicatedServerModInitializer {

    @Override
    public void onInitializeServer() {
        // 注册服务端命令
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            ServerCommandHandler.registerCommands(dispatcher);
        });

        // 注册服务端消息监听
        ServerMessageEvents.CHAT_MESSAGE.register(ServerMessageHandler::onChatMessage);
    }
}
