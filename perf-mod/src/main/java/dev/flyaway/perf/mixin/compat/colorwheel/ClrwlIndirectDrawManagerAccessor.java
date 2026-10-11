package dev.flyaway.perf.mixin.compat.colorwheel;

import dev.djefrey.colorwheel.indirect.ClrwlIndirectCullingGroup;
import dev.djefrey.colorwheel.indirect.ClrwlIndirectDrawManager;
import dev.engine_room.flywheel.api.instance.InstanceType;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ClrwlIndirectDrawManager.class, remap = false)
public interface ClrwlIndirectDrawManagerAccessor {
    @Accessor("cullingGroups")
    Map<InstanceType<?>, ClrwlIndirectCullingGroup<?>> flyaway$cullingGroups();
}
