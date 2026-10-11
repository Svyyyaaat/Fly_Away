package dev.flyaway.perf.compat;

import dev.ryanhcode.sable.companion.SableCompanion;
import net.minecraft.core.Position;
import net.minecraft.world.level.Level;
import net.neoforged.fml.loading.LoadingModList;

/** True when a position lies inside a Sable sub-level plot (plot space). Safe without Sable installed. */
public final class SablePlotCheck {
    private static final boolean SABLE = LoadingModList.get() != null && LoadingModList.get().getModFileById("sable") != null;

    private SablePlotCheck() {}

    public static boolean inPlot(Level level, Position pos) {
        return SABLE && Impl.inPlot(level, pos);
    }

    private static final class Impl {
        static boolean inPlot(Level level, Position pos) {
            return SableCompanion.INSTANCE.getContaining(level, pos) != null;
        }
    }
}
