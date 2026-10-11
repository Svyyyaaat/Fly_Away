package dev.flyaway.perf.vss;

import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@code ConcurrentHashMap<Long, V>} for packed chunk-column keys ({@code (long)cx << 32 | cz}) that stores every
 * key multiplied by an odd 64-bit constant (a bijection on long, so distinct keys stay distinct).
 *
 * <p>Why: {@code Long.hashCode()} is {@code (int)(v ^ v >>> 32)}, i.e. {@code cx ^ cz} for a packed column. Every
 * column on a "diagonal" with the same {@code cx ^ cz} collides; a square LOD window of N x N columns has only ~N
 * distinct hash values, so ConcurrentHashMap degrades its bins into red-black {@code TreeBin}s and every lookup is a
 * tree walk with {@code compareTo} on boxed Longs (profile: {@code TreeNode.findTreeNode} 0.60% self of the render
 * thread under {@code LodRequestManager.shouldRequestColumn}). After multiplication the high and low words are
 * well mixed and bins hold ~1 entry.
 *
 * <p>Only the point operations are remapped (get/containsKey/put/putIfAbsent/remove/getOrDefault/replace). Views
 * and iteration would expose the mixed keys - the only user ({@code LodRequestManager.strictSections}) never
 * iterates, it only uses get/containsKey/put/putIfAbsent/remove/clear (checked with javap).
 */
public final class MixedLongKeyMap<V> extends ConcurrentHashMap<Long, V> {
    private static final long MUL = 0x9E3779B97F4A7C15L; // odd -> invertible mod 2^64

    private static Object mix(Object key) {
        return key instanceof Long l ? (Object) (l * MUL) : key;
    }

    private static Long mix(Long key) {
        return key * MUL;
    }

    @Override
    public V get(Object key) {
        return super.get(mix(key));
    }

    @Override
    public boolean containsKey(Object key) {
        // CHM.containsKey calls the virtual get(); go to super.get directly so the key is mixed exactly once.
        return super.get(mix(key)) != null;
    }

    @Override
    public V getOrDefault(Object key, V defaultValue) {
        V v = super.get(mix(key));
        return v != null ? v : defaultValue;
    }

    @Override
    public V put(Long key, V value) {
        return super.put(mix(key), value);
    }

    @Override
    public V putIfAbsent(Long key, V value) {
        return super.putIfAbsent(mix(key), value);
    }

    @Override
    public V remove(Object key) {
        return super.remove(mix(key));
    }

    @Override
    public boolean remove(Object key, Object value) {
        return super.remove(mix(key), value);
    }

    @Override
    public V replace(Long key, V value) {
        return super.replace(mix(key), value);
    }

    @Override
    public boolean replace(Long key, V oldValue, V newValue) {
        return super.replace(mix(key), oldValue, newValue);
    }
}
