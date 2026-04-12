package fun.aichat.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AichatClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Executors.newSingleThreadScheduledExecutor().schedule(
                fun.aichat.AiModelManager::refreshModels,
                1, TimeUnit.SECONDS
        );

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            AiCommandHandler.registerCommands(dispatcher);
        });
        AiMessageHandler.initialize();
    }
}
