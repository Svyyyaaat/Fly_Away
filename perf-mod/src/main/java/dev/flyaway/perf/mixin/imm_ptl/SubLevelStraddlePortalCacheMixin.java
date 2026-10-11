package dev.flyaway.perf.mixin.imm_ptl;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.flyaway.perf.imm_ptl.FramePortalQueryCache;
import dev.flyaway.perf.imm_ptl.LevelBoxKey;
import dev.flyaway.perf.imm_ptl.PortalFrameScope;
import java.util.List;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * Per-frame memo for make-it-compatible's {@code SubLevelStraddle.portalsIn(Level, AABB)}.
 *
 * <p>Profile: 0.60% of the render thread directly (part of the 1.35% EntitySectionStorage.getEntities / 1.62%
 * SubLevelInclusiveLevelEntityGetter.get totals). The Sable-hull clip code ({@code clipHullAtPortal},
 * {@code drawHullsComingThroughThisPortal}, ...) asks "which portals are near this hull" for every hull, for every
 * chunk layer, in every pass (main, shadow, each portal pass). Each answer is a full entity-section box scan
 * (inflated by 32 blocks, through Sable's sub-level-inclusive entity getter) via {@code McHelper.findEntitiesByBox},
 * and the answer is identical for the whole frame: the hull render pose is fixed for the frame and no entity moves
 * while {@code GameRenderer.render} runs.
 *
 * <p>Fix: cache the result list per (level identity, exact box) for the current frame, only on the render thread
 * and only inside {@code GameRenderer.render} ({@link PortalFrameScope}). Callers in SubLevelStraddle only iterate
 * the returned list (verified with javap: candidates/isCrossedFace iterate, transitablePortalsIn copies). Outside a
 * frame (client tick, collision code) the original method runs unchanged. {@code @Pseudo}: no-op when micimmptl is
 * absent.
 */
@Pseudo
@Mixin(targets = "dev.micimmptl.sable.render.SubLevelStraddle", remap = false)
public abstract class SubLevelStraddlePortalCacheMixin {
    @WrapMethod(method = "portalsIn(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;")
    private static List<Portal> flyaway$cachedPortalsIn(Level level, AABB box, Operation<List<Portal>> original) {
        if (!PortalFrameScope.isActiveOnThisThread()) {
            return original.call(level, box);
        }
        LevelBoxKey key = new LevelBoxKey(level, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
        List<Portal> cached = FramePortalQueryCache.get(key);
        if (cached == null) {
            cached = original.call(level, box);
            FramePortalQueryCache.put(key, cached);
        }
        return cached;
    }
}
