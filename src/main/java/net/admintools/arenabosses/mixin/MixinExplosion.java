package net.admintools.arenabosses.mixin;

import net.admintools.arenabosses.ArenaBossesMod;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.explosion.Explosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;

@Mixin(Explosion.class)
public class MixinExplosion {
    @Inject(method = "affectWorld", at = @At("HEAD"))
    private void onExplode(boolean bl, CallbackInfo ci) {
        Explosion explosion = (Explosion) (Object) this;
        List<BlockPos> affectedBlocks = explosion.getAffectedBlocks();

        // Фильтруем список блоков, которые взрыв хочет уничтожить
        // Если этот блок находится в сохраненной JSON-сетке оригинальной арены,
        // мы удаляем его из списка разрушений, полностью спасая от взрывов ТНТ и боссов!
        affectedBlocks.removeIf(ArenaBossesMod.currentRegion::isBlockProtected);
    }
}