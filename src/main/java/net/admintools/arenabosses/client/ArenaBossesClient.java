package net.admintools.arenabosses.client;

import net.admintools.arenabosses.server.ModNetworking;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;

@Environment(EnvType.CLIENT)
public class ArenaBossesClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Ловим пакет открытия меню от сервера
        ClientPlayNetworking.registerGlobalReceiver(ModNetworking.OPEN_MENU_PACKET, (client, handler, buf, responseSender) -> {
            // Читаем JSON данные, присланные сервером
            String jsonString = buf.readString();

            // Открываем экран в основном потоке клиента
            client.execute(() -> MinecraftClient.getInstance().setScreen(new ArenaBossScreen(jsonString)));
        });
    }
}