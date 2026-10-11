package dev.flyaway.perf.mixin.imm_ptl;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.lang.management.GarbageCollectorMXBean;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.miscellaneous.GcMonitor;

/**
 * What: Immersive Portals' {@code GcMonitor.update()} runs before every rendered frame (IP's PreGameRenderEvent) and
 * calls {@code ManagementFactory.getGarbageCollectorMXBeans()} each time, only to iterate the beans and compare their
 * collection counts with the previous frame.
 *
 * <p>Why: that JDK call goes through the PlatformComponent / MXBean lookup machinery and builds a fresh list every
 * time (~14 us per call measured on JDK 21 with G1). Profile: 0.14% (2026-10-11) and 0.18% (2026-10-03) of the
 * render thread, all of it in {@code getPlatformMXBeans} streams.
 *
 * <p>Fix: return one cached, unmodifiable copy of the list. The set of GC beans is fixed for the JVM's lifetime, and
 * the JDK hands out the same bean objects on every call (checked: element identity is equal across calls, only the
 * ArrayList is new). So IP's WeakHashMap keyed on the beans sees exactly the same keys, and every
 * {@code getCollectionCount()} is still read live. The list is only iterated. Identical behaviour.
 * Disable: {@code mixin.imm_ptl.GcMonitorBeanListCacheMixin=false}.
 */
@Mixin(value = GcMonitor.class, remap = false)
public abstract class GcMonitorBeanListCacheMixin {
    @Unique
    private static volatile List<GarbageCollectorMXBean> flyaway$gcBeans;

    @WrapOperation(
            method = "update",
            at = @At(value = "INVOKE", target = "Ljava/lang/management/ManagementFactory;getGarbageCollectorMXBeans()Ljava/util/List;"))
    private static List<GarbageCollectorMXBean> flyaway$cachedBeans(Operation<List<GarbageCollectorMXBean>> original) {
        List<GarbageCollectorMXBean> beans = flyaway$gcBeans;
        if (beans == null) {
            beans = List.copyOf(original.call());
            flyaway$gcBeans = beans;
        }
        return beans;
    }
}
