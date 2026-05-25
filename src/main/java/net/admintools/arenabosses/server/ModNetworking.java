package net.admintools.arenabosses.server;

import com.google.gson.Gson;
import net.admintools.arenabosses.ArenaBossesMod;
import net.admintools.arenabosses.BossConfig;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ModNetworking {
    // Уникальные каналы пакетов (ID)
    public static final Identifier OPEN_MENU_PACKET = new Identifier("arenabosses", "open_menu");
    public static final Identifier SPAWN_BOSS_PACKET = new Identifier("arenabosses", "spawn_boss");

    private static final Gson GSON = new Gson();
    // Логгер для безопасного и информативного вывода ошибок в консоль сервера
    private static final Logger LOGGER = LoggerFactory.getLogger("ArenaBosses");

    /**
     * Контейнер для отправки данных. Переводит список боссов,
     * счетчики конкретного игрока и лимиты в единый JSON.
     * Исправлен на современный и компактный Java Record.
     */
    public record MenuDataPacket(List<BossConfig> bosses, Map<String, Integer> playerSpawns, int limitPerBoss) {}

    /**
     * Вызывается сервером (в команде /arena menu), чтобы упаковать данные
     * и отправить их на клиент для открытия GUI.
     */
    public static void sendOpenMenuPacket(ServerPlayerEntity player) {
        PacketByteBuf buf = PacketByteBufs.create();

        List<BossConfig> currentBosses = ModConfigManager.config.bosses;
        Map<String, Integer> playerSpawns = ModConfigManager.playerSpawnCounts.getOrDefault(player.getUuid(), Collections.emptyMap());
        int limit = ModConfigManager.config.globalSpawnLimitPerPlayer;

        MenuDataPacket data = new MenuDataPacket(currentBosses, playerSpawns, limit);
        String jsonString = GSON.toJson(data);

        // Записываем JSON строку в буфер пакета
        buf.writeString(jsonString);

        // Отправляем пакет игроку (Server-to-Client)
        ServerPlayNetworking.send(player, OPEN_MENU_PACKET, buf);
    }

    /**
     * Регистрирует на сервере "приемник" кликов из GUI клиента.
     * Срабатывает, когда игрок нажимает на доступного босса.
     */
    public static void registerServerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(SPAWN_BOSS_PACKET, (server, player, handler, buf, responseSender) -> {
            // Читаем ID босса, присланный клиентом
            String bossId = buf.readString();

            // Перенаправляем выполнение в основной поток сервера, чтобы избежать асинхронных багов
            server.execute(() -> {
                // 1. Проверяем, на арене ли игрок
                if (!ArenaBossesMod.currentRegion.isPlayerInside(player.getBlockPos())) {
                    player.sendMessage(Text.literal("❌ Вы должны находиться на территории арены!").formatted(Formatting.RED), false);
                    return;
                }

                // 2. Проверяем, задана ли точка спавна
                if (!ModConfigManager.config.hasSpawnPoint) {
                    player.sendMessage(Text.literal("❌ Точка спавна боссов не определена администратором!").formatted(Formatting.RED), false);
                    return;
                }

                // 3. Проверяем глобальный кулдаун арены (5 минут между боссами)
                if (System.currentTimeMillis() < ArenaBossesMod.cooldownEndTime) {
                    long timeLeft = (ArenaBossesMod.cooldownEndTime - System.currentTimeMillis()) / 1000;
                    player.sendMessage(Text.literal("⏳ Арена перезаряжается! Осталось: " + timeLeft + " сек.").formatted(Formatting.GOLD), false);
                    return;
                }

                // 4. Защита от читеров: проверяем лимит игрока на этого босса еще раз на сервере
                if (!ModConfigManager.canPlayerSpawn(player.getUuid(), bossId)) {
                    player.sendMessage(Text.literal("❌ Вы исчерпали свой лимит призывов для этого босса!").formatted(Formatting.RED), false);
                    return;
                }

                // Все проверки пройдены успешно, спавним!
                executeSpawn(player, bossId);
            });
        });
    }

    /**
     * Внутренний метод для безопасного вызова команды summon через консоль сервера
     */
    private static void executeSpawn(ServerPlayerEntity player, String bossId) {
        String displayName = bossId;
        for (BossConfig boss : ModConfigManager.config.bosses) {
            if (boss.id.equals(bossId)) {
                displayName = boss.displayName;
                break;
            }
        }

        // Обновляем базу данных призывов игрока
        ModConfigManager.incrementSpawnCount(player.getUuid(), bossId);

        // Устанавливаем кулдаун арены на 5 минут
        long cooldownMillis = ModConfigManager.config.cooldownSeconds * 1000L;
        ArenaBossesMod.cooldownEndTime = System.currentTimeMillis() + cooldownMillis;

        // Берем координаты из нашего конфига через исправленный метод getSpawnPosition()
        net.minecraft.util.math.BlockPos spawnPos = ModConfigManager.getSpawnPosition();

        String command = String.format("summon %s %d %d %d",
                bossId,
                spawnPos.getX(),
                spawnPos.getY(),
                spawnPos.getZ()
        );

        net.minecraft.server.command.ServerCommandSource consoleSource = player.server.getCommandSource().withSilent();
        com.mojang.brigadier.CommandDispatcher<net.minecraft.server.command.ServerCommandSource> dispatcher = player.server.getCommandManager().getDispatcher();
        com.mojang.brigadier.ParseResults<net.minecraft.server.command.ServerCommandSource> parseResults = dispatcher.parse(command, consoleSource);

        try {
            dispatcher.execute(parseResults);

            // Напрямую используем эффективную final-переменную displayName
            player.sendMessage(Text.literal("Вы успешно призвали босса: ").formatted(Formatting.GREEN)
                    .append(Text.literal(displayName).formatted(Formatting.YELLOW, Formatting.UNDERLINE, Formatting.BOLD)), false);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            player.sendMessage(Text.literal("❌ Ошибка призыва! Не удалось вызвать ID: " + bossId).formatted(Formatting.RED), false);
            // Заменили printStackTrace на чистый логируемый вывод
            LOGGER.error("Ошибка выполнения команды призыва босса [{}]:", bossId, e);
        }
    }
}