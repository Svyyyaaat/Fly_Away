package dev.flyaway.perf;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(FlyAwayPerf.MOD_ID)
public final class FlyAwayPerf {
    public static final String MOD_ID = "flyaway_perf";
    public static final Logger LOGGER = LoggerFactory.getLogger("FlyAwayPerf");

    public FlyAwayPerf(IEventBus modBus) {
        LOGGER.info("Fly Away performance patches loaded ({} mixins active)", PerfConfig.appliedCount());
        if (FMLEnvironment.dist == Dist.CLIENT) {
            dev.flyaway.perf.debug.HitchLogger.init();
        }
        if (FMLEnvironment.dist == Dist.CLIENT && net.neoforged.fml.ModList.get().isLoaded("immersive_portals_core")
                && PerfConfig.isEnabled("imm_ptl", "IrisCompatPostcardMixin")) {
            dev.flyaway.perf.imm_ptl.postcard.PostcardEvents.register(modBus);
        }
        if (FMLEnvironment.dist == Dist.CLIENT && System.getProperty("flyaway.harness") != null) {
            dev.flyaway.perf.harness.BenchHarness.init();
        }
    }
}
