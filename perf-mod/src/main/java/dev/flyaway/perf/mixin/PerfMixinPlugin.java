package dev.flyaway.perf.mixin;

import dev.flyaway.perf.PerfConfig;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Shared plugin for every flyaway_perf mixin config.
 * Mixins live in dev.flyaway.perf.mixin.&lt;area&gt;[.&lt;modid&gt;].Name; a mixin only applies when the mods its
 * area needs are loaded and it is not disabled in config/flyaway_perf.properties. For the "compat" area the
 * next package segment is the required mod id.
 */
public final class PerfMixinPlugin implements IMixinConfigPlugin {
    private static final String ROOT = "dev.flyaway.perf.mixin.";
    private static final Map<String, String[]> AREA_MODS = Map.ofEntries(
            Map.entry("vanilla", new String[0]),
            Map.entry("iris", new String[]{"iris"}),
            Map.entry("imm_ptl", new String[]{"immersive_portals_core"}),
            Map.entry("epicfight", new String[]{"epicfight"}),
            Map.entry("xaero", new String[0]), // per-mod check below via sub package
            Map.entry("sodium", new String[]{"sodium"}),
            Map.entry("flywheel", new String[]{"flywheel"}),
            Map.entry("voxy", new String[]{"voxy"}),
            Map.entry("sable", new String[]{"sable"}),
            Map.entry("vss", new String[]{"vss"}));

    @Override
    public void onLoad(String mixinPackage) {
        PerfConfig.load(FMLPaths.GAMEDIR.get());
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    static boolean modLoaded(String id) {
        LoadingModList list = LoadingModList.get();
        return list != null && list.getModFileById(id) != null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!mixinClassName.startsWith(ROOT)) return true;
        String rest = mixinClassName.substring(ROOT.length());
        String[] parts = rest.split("\\.");
        String area = parts[0];
        String simple = parts[parts.length - 1];
        String[] needed = AREA_MODS.get(area);
        if (needed != null) {
            for (String id : needed) if (!modLoaded(id)) return false;
        }
        if ((area.equals("compat") || area.equals("xaero")) && parts.length > 2 && !modLoaded(parts[1])) return false;
        boolean on = PerfConfig.isEnabled(area, simple);
        if (on) PerfConfig.countApplied();
        return on;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
