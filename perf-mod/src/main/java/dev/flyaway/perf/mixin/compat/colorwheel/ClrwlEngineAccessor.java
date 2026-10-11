package dev.flyaway.perf.mixin.compat.colorwheel;

import dev.djefrey.colorwheel.engine.ClrwlDrawManager;
import dev.djefrey.colorwheel.engine.ClrwlEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ClrwlEngine.class, remap = false)
public interface ClrwlEngineAccessor {
    @Accessor("drawManager")
    ClrwlDrawManager<?> flyaway$drawManager();

    @Accessor("shouldPrepareFrame")
    boolean flyaway$shouldPrepareFrame();
}
