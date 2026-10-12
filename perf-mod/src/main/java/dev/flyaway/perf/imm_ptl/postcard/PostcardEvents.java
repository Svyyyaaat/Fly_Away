package dev.flyaway.perf.imm_ptl.postcard;

import dev.flyaway.perf.imm_ptl.ImmPtlTunables;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Postcard eviction hooks (client only): resource reload and logout clear everything, a client level unload drops
 * the postcards of portals that lived in it. No Immersive Portals / Iris types here, safe without those mods.
 */
public final class PostcardEvents {
    private PostcardEvents() {}

    public static void register(IEventBus modBus) {
        if (!ImmPtlTunables.POSTCARDS) return;
        modBus.addListener(RegisterClientReloadListenersEvent.class,
                e -> e.registerReloadListener((ResourceManagerReloadListener) rm -> PostcardCache.requestClear()));
        NeoForge.EVENT_BUS.addListener(LevelEvent.Unload.class, e -> {
            if (e.getLevel().isClientSide() && e.getLevel() instanceof Level level) PostcardCache.onLevelUnload(level.dimension());
        });
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, e -> PostcardCache.requestClear());
    }
}
