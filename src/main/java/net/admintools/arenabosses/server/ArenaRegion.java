package net.admintools.arenabosses.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class ArenaRegion {
    private BlockPos minPoints;
    private BlockPos maxPoints;
    private boolean isSet = false;

    private final HashMap<Long, String> protectedOriginalBlocks = new HashMap<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File(FabricLoader.getInstance().getConfigDir().resolve("arenabosses").toFile(),
            "arenabosses_region.json");

    // Внутренний класс-контейнер для монолитного сохранения региона в JSON
    private static class RegionSaveData {
        public final int minX, minY, minZ;
        public final int maxX, maxY, maxZ;
        public final HashMap<Long, String> blocks;

        public RegionSaveData(BlockPos min, BlockPos max, HashMap<Long, String> blocks) {
            this.minX = min.getX(); this.minY = min.getY(); this.minZ = min.getZ();
            this.maxX = max.getX(); this.maxY = max.getY(); this.maxZ = max.getZ();
            this.blocks = blocks;
        }
    }

    public BlockPos getMinPoints() { return this.minPoints; }
    public BlockPos getMaxPoints() { return this.maxPoints; }
    public boolean isSet() { return this.isSet; }

    public void setPoints(BlockPos p1, BlockPos p2, ServerWorld world) {
        int minX = Math.min(p1.getX(), p2.getX());
        int minY = Math.min(p1.getY(), p2.getY());
        int minZ = Math.min(p1.getZ(), p2.getZ());

        int maxX = Math.max(p1.getX(), p2.getX());
        int maxY = Math.max(p1.getY(), p2.getY());
        int maxZ = Math.max(p1.getZ(), p2.getZ());

        this.minPoints = new BlockPos(minX, minY, minZ);
        this.maxPoints = new BlockPos(maxX, maxY, maxZ);
        this.isSet = true;

        this.protectedOriginalBlocks.clear();

        // Сканируем регион. Барьеры здесь тоже сохранятся!
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos currentPos = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(currentPos);

                    if (!state.isAir()) {
                        String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
                        this.protectedOriginalBlocks.put(currentPos.asLong(), blockId);
                    }
                }
            }
        }
        saveToFile();
    }

    public boolean isBlockProtected(BlockPos pos) {
        if (!isSet) return false;
        return pos.getX() >= minPoints.getX() && pos.getX() <= maxPoints.getX() &&
                pos.getY() >= minPoints.getY() && pos.getY() <= maxPoints.getY() &&
                pos.getZ() >= minPoints.getZ() && pos.getZ() <= maxPoints.getZ();
    }

    public boolean isPlayerInside(BlockPos pos) {
        return isBlockProtected(pos);
    }

    public void autoRestoreBlocks(ServerWorld world) {
        if (!isSet) return;

        // Проходим только по списку защищенных блоков (фундаменту)
        for (Map.Entry<Long, String> entry : protectedOriginalBlocks.entrySet()) {
            BlockPos pos = BlockPos.fromLong(entry.getKey());

            if (world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                BlockState currentState = world.getBlockState(pos);
                // Получаем ID блока, который должен быть тут по нашему списку
                Block originalBlock = Registries.BLOCK.get(new Identifier(entry.getValue()));

                // Если текущий блок в мире НЕ совпадает с оригиналом:
                if (!currentState.isOf(originalBlock)) {
                    // Восстанавливаем оригинальный блок
                    world.setBlockState(pos, originalBlock.getDefaultState());
                }
            }
        }
    }

    public void deleteRegion() {
        this.isSet = false;
        this.protectedOriginalBlocks.clear();
        this.minPoints = null;
        this.maxPoints = null;

        if (CONFIG_FILE.exists()) {
            boolean deleted = CONFIG_FILE.delete();
            if (!deleted) {
                System.err.println("[ArenaBosses] Не удалось удалить файл региона арены! Проверьте права доступа.");
            }
        }
    }

    private void saveToFile() {
        if (!isSet) return;
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            RegionSaveData data = new RegionSaveData(minPoints, maxPoints, protectedOriginalBlocks);
            GSON.toJson(data, writer);
        } catch (IOException e) {
            System.err.println("[ArenaBosses] Ошибка сохранения региона: " + e.getMessage());
        }
    }

    public void loadFromFile() {
        if (!CONFIG_FILE.exists()) {
            this.isSet = false;
            return;
        }

        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            RegionSaveData data = GSON.fromJson(reader, RegionSaveData.class);

            if (data != null && data.blocks != null) {
                this.protectedOriginalBlocks.clear();
                this.protectedOriginalBlocks.putAll(data.blocks);

                // Границы всегда железно соответствуют тому, что выделил админ
                this.minPoints = new BlockPos(data.minX, data.minY, data.minZ);
                this.maxPoints = new BlockPos(data.maxX, data.maxY, data.maxZ);
                this.isSet = true;
            }
        } catch (Exception e) {
            System.err.println("[ArenaBosses] Ошибка загрузки региона: " + e.getMessage());
            deleteRegion();
        }
    }
    public void forceClean(ServerWorld world) {
        if (!isSet) return;

        for (int x = minPoints.getX(); x <= maxPoints.getX(); x++) {
            for (int y = minPoints.getY(); y <= maxPoints.getY(); y++) {
                for (int z = minPoints.getZ(); z <= maxPoints.getZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);

                    // Если блок НЕ является частью нашего «бессмертного» фундамента
                    if (!protectedOriginalBlocks.containsKey(pos.asLong())) {
                        // Ставим воздух (удаляем всё, что не фундамент)
                        if (!world.getBlockState(pos).isAir()) {
                            world.setBlockState(pos, net.minecraft.block.Blocks.AIR.getDefaultState());
                        }
                    }
                }
            }
        }
    }
}