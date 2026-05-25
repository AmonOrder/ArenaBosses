package net.admintools.arenabosses;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.admintools.arenabosses.server.ArenaRegion;
import net.admintools.arenabosses.server.ModConfigManager;
import net.admintools.arenabosses.server.ModNetworking;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.io.IOException;
import java.nio.file.Path;

public class ArenaBossesMod implements ModInitializer {
    public static final ArenaRegion currentRegion = new ArenaRegion();
    public static long cooldownEndTime = 0;
    private int tickCounter = 0;

    @Override
    public void onInitialize() {
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("arenabosses");
        if (!java.nio.file.Files.exists(configDir)) {
            try {
                java.nio.file.Files.createDirectories(configDir);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        ModConfigManager.loadConfig();
        ModConfigManager.loadPlayerData(); // Сначала загружаем то, что есть
        currentRegion.loadFromFile();

        // Переносим сброс на момент полного старта сервера
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ModConfigManager.clearAllPlayerData();
            System.out.println("[ArenaBosses] Данные игроков сброшены в связи с рестартом.");
        });

        // Подключаем наш сетевой менеджер на стороне сервера!
        ModNetworking.registerServerReceivers();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (player.isCreative() || player.hasPermissionLevel(4)) {
                return true;
            }
            if (currentRegion.isBlockProtected(pos)) {
                player.sendMessage(Text.literal("❌ Нельзя разрушать оригинальные конструкции Арены!").formatted(Formatting.RED), true);
                return false;
            }
            return true;
        });

        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayerEntity targetPlayer && source.getAttacker() instanceof ServerPlayerEntity attackerPlayer) {
                if (currentRegion.isPlayerInside(targetPlayer.getBlockPos()) && currentRegion.isPlayerInside(attackerPlayer.getBlockPos())) {
                    attackerPlayer.sendMessage(Text.literal("PvP отключено на территории arena боссов!").formatted(Formatting.RED), true);
                    return false;
                }
            }
            return true;
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;
            // 200 тиков = 10 секунд
            if (tickCounter >= 200) {
                tickCounter = 0; // Сбрасываем счетчик сразу

                if (currentRegion.isSet()) {
                    for (ServerWorld world : server.getWorlds()) {
                        currentRegion.autoRestoreBlocks(world);
                    }
                }
            }
        });
    }

    private void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("arena")
                .then(CommandManager.literal("clear")
                        .executes(context -> {
                            ServerPlayerEntity player = context.getSource().getPlayer();
                            if (player == null) return 0;

                            if (!currentRegion.isSet()) {
                                context.getSource().sendError(Text.literal("Зона арены еще не задана!"));
                                return 0;
                            }

                            ServerWorld world = player.getServerWorld();
                            // 1. Сначала удаляем ВЕСЬ мусор, который не является фундаментом
                            currentRegion.forceClean(world);

                            // 2. Затем восстанавливаем оригинальные блоки (фундамент)
                            currentRegion.autoRestoreBlocks(world);

                            // 3. Чистим сущности (ваш текущий код)
                            BlockPos min = currentRegion.getMinPoints();
                            BlockPos max = currentRegion.getMaxPoints();
                            Box entitySearchBox = new Box(min, max.add(1, 1, 1));
                            java.util.List<Entity> entities = world.getOtherEntities(null, entitySearchBox);

                            int discardedCount = 0;
                            for (Entity entity : entities) {
                                if (!(entity instanceof ServerPlayerEntity)) {
                                    entity.discard();
                                    discardedCount++;
                                }
                            }

                            int finalCount = discardedCount;
                            context.getSource().sendFeedback(() -> Text.literal("Арена полностью очищена! Удалено сущностей: " + finalCount)
                                    .formatted(Formatting.GREEN), true);
                            return 1;
                        }))
                .then(CommandManager.literal("menu")
                        .executes(context -> {
                            ServerPlayerEntity player = context.getSource().getPlayer();
                            if (player == null) return 0;

                            if (!currentRegion.isPlayerInside(player.getBlockPos())) {
                                player.sendMessage(Text.literal("Вы должны находиться на территории арены, чтобы открыть меню!").formatted(Formatting.RED), false);
                                return 0;
                            }

                            if (!ModConfigManager.config.hasSpawnPoint) {
                                player.sendMessage(Text.literal("Ошибка: Точка спавна боссов не определена администратором!").formatted(Formatting.RED), false);
                                return 0;
                            }

                            if (System.currentTimeMillis() < cooldownEndTime) {
                                long timeLeftSeconds = (cooldownEndTime - System.currentTimeMillis()) / 1000;
                                player.sendMessage(Text.literal("Арена перезаряжается! Подождите еще " + timeLeftSeconds + " сек.").formatted(Formatting.GOLD), false);
                                return 0;
                            }

                            // ОТПРАВЛЯЕМ СЕТЕВОЙ ПАКЕТ НА КЛИЕНТ ДЛЯ ОТКРЫТИЯ КАСТОМНОГО GUI
                            ModNetworking.sendOpenMenuPacket(player);
                            return 1;
                        }))
                .then(CommandManager.literal("set")
                        .requires(source -> source.hasPermissionLevel(4))
                        .then(CommandManager.argument("pos1", BlockPosArgumentType.blockPos())
                                .then(CommandManager.argument("pos2", BlockPosArgumentType.blockPos())
                                        .executes(context -> {
                                            BlockPos p1 = BlockPosArgumentType.getBlockPos(context, "pos1");
                                            BlockPos p2 = BlockPosArgumentType.getBlockPos(context, "pos2");
                                            ServerWorld world = context.getSource().getWorld();
                                            currentRegion.setPoints(p1, p2, world);
                                            context.getSource().sendFeedback(() -> Text.literal("Территория арены успешно установлена!").formatted(Formatting.GREEN), true);
                                            return 1;
                                        }))))
                .then(CommandManager.literal("delete")
                        .requires(source -> source.hasPermissionLevel(4))
                        .executes(context -> {
                            currentRegion.deleteRegion();
                            context.getSource().sendFeedback(() -> Text.literal("Зона арены успешно удалена!").formatted(Formatting.RED), true);
                            return 1;
                        }))
                .then(CommandManager.literal("reload")
                        .requires(source -> source.hasPermissionLevel(4))
                        .executes(context -> {
                            ModConfigManager.loadConfig();
                            currentRegion.loadFromFile();
                            context.getSource().sendFeedback(() -> Text.literal("Конфигурация успешно перезагружена!")
                                    .formatted(Formatting.GREEN), true);
                            return 1;
                        }))
                .then(CommandManager.literal("reset")
                        .requires(source -> source.hasPermissionLevel(4))
                        .executes(context -> {
                            ModConfigManager.playerSpawnCounts.clear();
                            ModConfigManager.savePlayerData();

                            // СБРОС КУЛДАУНА
                            ArenaBossesMod.cooldownEndTime = 0;

                            context.getSource().sendFeedback(() -> Text.literal("Все лимиты призывов и кулдаун сброшены!").formatted(Formatting.GOLD), true);
                            return 1;
                        }))
                .then(CommandManager.literal("spawn")
                        .requires(source -> source.hasPermissionLevel(4))
                        .then(CommandManager.literal("set")
                                .executes(context -> {
                                    ServerPlayerEntity player = context.getSource().getPlayer();
                                    if (player == null) return 0;

                                    BlockPos pos = player.getBlockPos();
                                    ModConfigManager.config.spawnX = pos.getX();
                                    ModConfigManager.config.spawnY = pos.getY();
                                    ModConfigManager.config.spawnZ = pos.getZ();
                                    ModConfigManager.config.spawnWorld = player.getWorld().getRegistryKey().getValue().toString();
                                    ModConfigManager.config.hasSpawnPoint = true;
                                    ModConfigManager.saveConfig();

                                    context.getSource().sendFeedback(() -> Text.literal("Точка спавна установлена!").formatted(Formatting.GREEN), true);
                                    return 1;
                                }))
                        .then(CommandManager.literal("delete")
                                .executes(context -> {
                                    ModConfigManager.config.hasSpawnPoint = false;
                                    ModConfigManager.saveConfig();
                                    context.getSource().sendFeedback(() -> Text.literal("Точка спавна удалена.").formatted(Formatting.RED), true);
                                    return 1;
                                })))
                .then(CommandManager.literal("boss")
                        .requires(source -> source.hasPermissionLevel(4))
                        .then(CommandManager.literal("add") // Упростил путь: было "list add", сделал "add"
                                .then(CommandManager.argument("id", IdentifierArgumentType.identifier()) // ИСПОЛЬЗУЕМ IDENTIFIER
                                        .then(CommandManager.argument("name", StringArgumentType.string())
                                                .then(CommandManager.argument("description", StringArgumentType.greedyString())
                                                        .executes(context -> {
                                                            // ПРАВИЛЬНОЕ ПОЛУЧЕНИЕ
                                                            Identifier id = IdentifierArgumentType.getIdentifier(context, "id");
                                                            String name = StringArgumentType.getString(context, "name");
                                                            String description = StringArgumentType.getString(context, "description");

                                                            ModConfigManager.addBossToList(id.toString(), name, description);

                                                            context.getSource().sendFeedback(() -> Text.literal("✅ Босс ")
                                                                    .append(Text.literal(name).formatted(Formatting.YELLOW))
                                                                    .append(" [" + id + "] успешно добавлен в список!")
                                                                    .formatted(Formatting.GREEN), true);
                                                            return 1;
                                                        })))))
                        .then(CommandManager.literal("remove")
                                .then(CommandManager.argument("name", StringArgumentType.greedyString()) // Используем greedyString для поддержки пробелов
                                        .executes(context -> {
                                            String name = StringArgumentType.getString(context, "name");
                                            boolean success = ModConfigManager.removeBossByName(name); // Вызываем новый метод

                                            if (success) {
                                                context.getSource().sendFeedback(() -> Text.literal("❌ Босс \"" + name + "\" успешно удален из списка.")
                                                        .formatted(Formatting.RED), true);
                                                return 1;
                                            } else {
                                                context.getSource().sendError(Text.literal("Босс с именем \"" + name + "\" не найден в списке!"));
                                                return 0;
                                            }
                                        })))));
    }
}