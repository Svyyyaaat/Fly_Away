package dev.flyaway.perf.mixin.imm_ptl;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import qouteall.imm_ptl.core.render.GlQueryObject;

/** Read access to the GL query name, for a non-blocking GL_QUERY_RESULT_AVAILABLE check. */
@Mixin(value = GlQueryObject.class, remap = false)
public interface GlQueryObjectIdAccessor {
    @Accessor("idQueryObject")
    int flyaway$id();
}
