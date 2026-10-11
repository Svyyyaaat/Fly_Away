package dev.flyaway.perf.xaero;

/**
 * Implemented (via mixin) by xaero.map.highlight.DimensionHighlighterHandler: a counter that changes every time the
 * handler's region-hash cache loses an entry (clearCachedHash / clearCachedHashes). While it is unchanged, the hash a
 * caller got from getRegionHash(x, z) earlier is still exactly what getRegionHash would return now.
 */
public interface HighlightHashVersioned {
    int flyaway$hashVersion();
}
