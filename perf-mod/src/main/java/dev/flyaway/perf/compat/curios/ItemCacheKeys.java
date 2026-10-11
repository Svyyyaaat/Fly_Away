package dev.flyaway.perf.compat.curios;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.minecraft.world.item.Item;
import top.theillusivec4.curios.Curios;

/**
 * Memoizes {@code Curios.itemCacheKey(item.getDefaultInstance())} per Item.
 * <p>
 * The key of an item's default instance only depends on the item's registry id and its default data components,
 * which are fixed once the registries are frozen (before any world/entity exists). Curios recomputes it on every
 * {@code findFirstCurio(Item)} call: a fresh ItemStack (plus owo/accessories component hooks that stream over the
 * components) and a stream that toString()s and concatenates every component. Shared by the server and client
 * threads, hence the concurrent map.
 */
public final class ItemCacheKeys {
    private static final ConcurrentHashMap<Item, String> KEYS = new ConcurrentHashMap<>();
    private static final Function<Item, String> COMPUTE = item -> Curios.itemCacheKey(item.getDefaultInstance());

    private ItemCacheKeys() {}

    public static String get(Item item) {
        String key = KEYS.get(item);
        if (key == null) {
            key = KEYS.computeIfAbsent(item, COMPUTE);
        }
        return key;
    }
}
