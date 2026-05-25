package net.admintools.arenabosses.server;

// ИМПОРТ ИЗ КОРНЯ ПАКЕТА
import net.admintools.arenabosses.BossConfig;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;

public class ModConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File(FabricLoader.getInstance().getConfigDir().resolve("arenabosses").toFile(), "arenabosses_config.json");
    private static final File PLAYER_DATA_FILE = new File(FabricLoader.getInstance().getConfigDir().resolve("arenabosses").toFile(), "arenabosses_players.json");

    public static class ModConfig {
        public boolean hasSpawnPoint = false;
        public int globalSpawnLimitPerPlayer = 1;
        public String spawnWorld = "minecraft:overworld";
        public int spawnX = 0;
        public int spawnY = 0;
        public int spawnZ = 0;
        public List<BossConfig> bosses = new ArrayList<>();
        public int cooldownSeconds = 60;
    }

    public static ModConfig config = new ModConfig();
    public static Map<UUID, Map<String, Integer>> playerSpawnCounts = new HashMap<>();

    public static void loadConfig() {
        if (!CONFIG_FILE.exists()) {
            // Конфиг изначально чистый, без хардкодных боссов. Администратор настроит всё сам.
            saveConfig();
            return;
        }
        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            config = GSON.fromJson(reader, ModConfig.class);
            if (config == null) {
                config = new ModConfig();
            }
            // Страховка от NullPointerException, если файл был поврежден или криво отредактирован
            if (config.bosses == null) {
                config.bosses = new ArrayList<>();
            }
        } catch (IOException e) {
            System.err.println("[ArenaBosses] Не удалось загрузить конфиг: " + e.getMessage());
            if (config.bosses == null) {
                config.bosses = new ArrayList<>();
            }
        }
    }

    public static void saveConfig() {
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(config, writer);
        } catch (IOException e) {
            System.err.println("[ArenaBosses] Не удалось сохранить конфиг: " + e.getMessage());
        }
    }

    public static void loadPlayerData() {
        if (!PLAYER_DATA_FILE.exists()) return;
        try (FileReader reader = new FileReader(PLAYER_DATA_FILE)) {
            Type type = new TypeToken<HashMap<UUID, HashMap<String, Integer>>>(){}.getType();
            Map<UUID, Map<String, Integer>> loadedData = GSON.fromJson(reader, type);
            if (loadedData != null) playerSpawnCounts = loadedData;
        } catch (IOException e) {
            System.err.println("[ArenaBosses] Не удалось загрузить данные игроков: " + e.getMessage());
        }
    }

    public static void savePlayerData() {
        try (FileWriter writer = new FileWriter(PLAYER_DATA_FILE)) {
            GSON.toJson(playerSpawnCounts, writer);
        } catch (IOException e) {
            System.err.println("[ArenaBosses] Не удалось сохранить данные игроков: " + e.getMessage());
        }
    }

    public static boolean canPlayerSpawn(UUID playerUuid, String bossId) {
        Map<String, Integer> counts = playerSpawnCounts.getOrDefault(playerUuid, Collections.emptyMap());
        int spawned = counts.getOrDefault(bossId, 0);
        return spawned < config.globalSpawnLimitPerPlayer;
    }

    public static void incrementSpawnCount(UUID playerUuid, String bossId) {
        playerSpawnCounts.computeIfAbsent(playerUuid, k -> new HashMap<>()).merge(bossId, 1, Integer::sum);
        savePlayerData();
    }

    public static BlockPos getSpawnPosition() {
        return new BlockPos(config.spawnX, config.spawnY, config.spawnZ);
    }

    /**
     * Добавляет босса в конфигурацию и сохраняет файл.
     * Если босс с таким ID уже существовал, он будет обновлен.
     */
    public static void addBossToList(String id, String displayName, String description) {
        // Удаляем старую запись с таким же ID, если она есть, чтобы избежать дубликатов
        config.bosses.removeIf(boss -> boss.id.equals(id));

        // Добавляем нового босса
        config.bosses.add(new BossConfig(id, displayName, description));

        // Сохраняем обновленный конфиг на диск
        saveConfig();
    }

    /**
     * Удаляет босса из конфигурации по его ID и сохраняет файл.
     * @return true, если босс был найден и удален.
     */
    public static boolean removeBossByName(String name) {
        // Ищем босса, у которого displayName совпадает с введенным (без учета регистра)
        boolean removed = config.bosses.removeIf(boss -> boss.displayName.equalsIgnoreCase(name));

        if (removed) {
            saveConfig(); // Сохраняем изменения в файл
        }
        return removed;
    }

    // Метод очистки счётчика призывов
    public static void clearAllPlayerData() {
        playerSpawnCounts.clear();
        savePlayerData();
    }
}