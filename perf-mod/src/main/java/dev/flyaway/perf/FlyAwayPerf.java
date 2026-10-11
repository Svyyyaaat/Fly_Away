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
        if (FMLEnvironment.dist == Dist.CLIENT && System.getProperty("flyaway.harness") != null) {
            dev.flyaway.perf.harness.BenchHarness.init();
        }
    }
}
