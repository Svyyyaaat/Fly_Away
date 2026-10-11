package dev.flyaway.perf.mixin.vanilla.iris;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.flyaway.perf.iris.IrisFboDeferral;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips glUseProgram(p) when p is already in use, but ONLY inside an Iris entity-batch scope
 * ({@link IrisFboDeferral#depth} != 0, opened by mixin.iris.IrisBatchFboScopeMixin). Iris' ExtendedShader.apply
 * calls glUseProgram on every draw (vanilla ShaderInstance.apply dedups via lastProgramId, Iris does not), and a
 * batch draws many render types sharing one Iris program in a row. Within the scope program changes go through
 * GlStateManager (render-type setup + BufferUploader), so the tracked value is exact; it is reset on scope entry
 * and exit. Flywheel's RETURN tracker and Iris' own HEAD hook stay consistent (a skipped call changes nothing).
 *
 * Profile: glUseProgram 1.62% self, 0.36% from ExtendedShader.apply.
 * Disable: mixin.vanilla.GlStateManagerProgramDedupMixin=false.
 */
@Mixin(value = GlStateManager.class, remap = false)
public abstract class GlStateManagerProgramDedupMixin {
    @Inject(method = "_glUseProgram(I)V", at = @At("HEAD"), cancellable = true)
    private static void flyaway$dedupUseProgram(int program, CallbackInfo ci) {
        if (IrisFboDeferral.depth == 0) return;
        if (program == IrisFboDeferral.physicalProgram) {
            if (IrisFboDeferral.STATS) IrisFboDeferral.statSkippedProgram++;
            ci.cancel();
        } else {
            IrisFboDeferral.physicalProgram = program;
        }
    }
}
