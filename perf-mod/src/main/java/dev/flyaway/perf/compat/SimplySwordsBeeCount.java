package dev.flyaway.perf.compat;

/**
 * Duck interface added to {@code EntityLookup} by {@code compat.simplyswords.EntityLookupBeeCountMixin}: the number of
 * {@code SimplySwordsBeeEntity} values currently stored in the lookup's {@code byId} map (= what
 * {@code getAllEntities()} iterates).
 */
public interface SimplySwordsBeeCount {
    int flyaway$beeCount();
}
