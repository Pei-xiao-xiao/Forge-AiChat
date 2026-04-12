package fun.xingwangzhe.ollamachat.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class OllamachatClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Executors.newSingleThreadScheduledExecutor().schedule(
                OllamaModelManager::updateModelsFromSystem,
                1, TimeUnit.SECONDS
        );

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            OllamaCommandHandler.registerCommands(dispatcher);
        });
        OllamaMessageHandler.initialize();
    }
}