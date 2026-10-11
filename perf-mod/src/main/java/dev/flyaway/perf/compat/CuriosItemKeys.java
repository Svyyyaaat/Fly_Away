package dev.flyaway.perf.compat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.minecraft.world.item.Item;
import top.theillusivec4.curios.Curios;

/**
 * Memo of {@code Curios.itemCacheKey(item.getDefaultInstance())} per Item.
 * Curios builds this String (registry id + toString() of EVERY default data component, joined by a stream reduce) plus a
 * fresh default ItemStack on every findFirstCurio(Item)/findCurios(Item) call, only to use it as a key for its own
 * per-game-tick result cache. The default instance of an Item is fixed once registries are frozen (default components
 * are set at registration / ModifyDefaultComponentsEvent, never by datapacks), so the key is a constant per Item.
 * Used from both client and server threads, hence ConcurrentHashMap.
 */
public final class CuriosItemKeys {
    private static final Map<Item, String> KEYS = new ConcurrentHashMap<>();
    private static final Function<Item, String> COMPUTE = item -> Curios.itemCacheKey(item.getDefaultInstance());

    private CuriosItemKeys() {}

    public static String key(Item item) {
        String k = KEYS.get(item);
        return k != null ? k : KEYS.computeIfAbsent(item, COMPUTE);
    }
}
