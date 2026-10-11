package dev.flyaway.perf.mixin.vss;

import dev.flyaway.perf.vss.MixedLongKeyMap;
import dev.xantha.vss.networking.client.LodRequestManager;
import java.util.concurrent.ConcurrentHashMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fixes the key hashing of {@code LodRequestManager.strictSections} (ConcurrentHashMap&lt;Long, int[]&gt; keyed by
 * packed chunk column {@code cx<<32|cz}).
 *
 * <p>Profile: {@code VSSClientNetworking.onClientTick -> LodRequestManager.tick} 1.09%, of which
 * {@code ConcurrentHashMap$TreeNode.findTreeNode} is 0.60% SELF (from {@code shouldRequestColumn} /
 * {@code strictColumnReady} / {@code restoreKnownColumn}). {@code Long.hashCode} of a packed column is
 * {@code cx ^ cz}: a whole LOD window maps to only ~(window width) hash values, CHM bins overflow into TreeBins and
 * each containsKey becomes a red-black tree walk with Long.compareTo.
 *
 * <p>Fix: right after the (only) constructor that initialises the field, swap the still-empty map for a
 * {@link MixedLongKeyMap}, which multiplies keys by an odd constant before hashing (bijective - semantics unchanged).
 * The field is only used with point operations + clear (verified with javap), so iteration order is irrelevant.
 * Expected: findTreeNode cost disappears (~0.5% of the render thread), plus the same on any other thread using it.
 */
@Mixin(value = LodRequestManager.class, remap = false)
public abstract class LodRequestManagerStrictKeysMixin {
    @Shadow @Final @Mutable private ConcurrentHashMap<Long, int[]> strictSections;

    @Inject(
            method = "<init>(Ljava/lang/String;Ldev/xantha/vss/networking/client/ClientRequestTracker;Ljava/lang/Runnable;)V",
            at = @At("RETURN"))
    // ctor args omitted from the handler: ClientRequestTracker is package-private.
    private void flyaway$mixStrictSectionKeys(CallbackInfo ci) {
        if (!(this.strictSections instanceof MixedLongKeyMap) && this.strictSections.isEmpty()) {
            this.strictSections = new MixedLongKeyMap<>();
        }
    }
}
