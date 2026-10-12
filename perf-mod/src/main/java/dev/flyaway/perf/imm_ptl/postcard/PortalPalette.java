package dev.flyaway.perf.imm_ptl.postcard;

import dev.flyaway.perf.PerfConfig;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Colours of the "paint" portal surface (docs/portal_postcards.md), per destination dimension: c1 = main paint,
 * c2 = second paint, c3 = the dark gaps between the strokes. Hand-made for the overworld, the Nether, the End and
 * the Aether; any other dimension gets a palette derived from its fog colour (when its client world is loaded) or
 * from a hash of its id. Override: {@code imm_ptl.postcards.palette.<namespace>.<path>=RRGGBB,RRGGBB,RRGGBB}.
 */
final class PortalPalette {
    private static final Map<ResourceKey<Level>, float[]> CACHE = new HashMap<>();

    private PortalPalette() {}

    /** 9 floats (c1, c2, c3). {@code fog} = destination fog colour, or -1 when unknown. */
    static float[] get(ResourceKey<Level> dim, int fog) {
        float[] p = CACHE.get(dim);
        if (p != null && (p[9] != 0f || fog == -1)) return p;
        p = make(dim, fog);
        CACHE.put(dim, p);
        return p;
    }

    private static float[] make(ResourceKey<Level> dim, int fog) {
        String id = dim.location().toString();
        String over = PerfConfig.getString("imm_ptl.postcards.palette." + dim.location().getNamespace() + "." + dim.location().getPath().replace('/', '.'), "");
        if (!over.isEmpty()) {
            String[] parts = over.split(",");
            if (parts.length == 3) {
                try {
                    return pal(Integer.parseInt(parts[0].trim().replace("#", ""), 16), Integer.parseInt(parts[1].trim().replace("#", ""), 16),
                            Integer.parseInt(parts[2].trim().replace("#", ""), 16), true);
                } catch (NumberFormatException ignored) {
                    // fall through to the defaults
                }
            }
        }
        switch (id) {
            case "minecraft:overworld": return pal(0x4F9BE8, 0x5DB33A, 0x0E1A26, true);   // sky, grass, deep night
            case "minecraft:the_nether": return pal(0xE8541C, 0x8E1328, 0x1A0507, true);  // lava, crimson, basalt
            case "minecraft:the_end": return pal(0xA35BD6, 0xE6E39A, 0x07040D, true);     // chorus, end stone, void
            case "aether:the_aether": return pal(0x8CCBFF, 0xF2C94C, 0x31507A, true);     // sky, glowstone gold, deep sky
            default: break;
        }
        float[] hsv;
        boolean known = fog != -1;
        if (known) {
            hsv = rgbToHsv(fog);
        } else {
            hsv = new float[]{(id.hashCode() & 0xFFFF) / 65536f, 0.6f, 0.8f};
        }
        float h = hsv[0];
        float s = Math.max(0.55f, hsv[1]);
        int c1 = hsvToRgb(h, s, Math.max(0.75f, hsv[2]));
        int c2 = hsvToRgb((h + 0.42f) % 1f, s * 0.85f, 0.65f);
        int c3 = hsvToRgb(h, Math.min(1f, s + 0.1f), 0.1f);
        return pal(c1, c2, c3, known);
    }

    private static float[] pal(int a, int b, int c, boolean final_) {
        return new float[]{r(a), g(a), bl(a), r(b), g(b), bl(b), r(c), g(c), bl(c), final_ ? 1f : 0f};
    }

    private static float r(int c) { return ((c >> 16) & 255) / 255f; }
    private static float g(int c) { return ((c >> 8) & 255) / 255f; }
    private static float bl(int c) { return (c & 255) / 255f; }

    private static float[] rgbToHsv(int c) {
        float r = r(c), g = g(c), b = bl(c);
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        float h;
        if (d < 1e-5f) h = 0f;
        else if (max == r) h = ((g - b) / d + 6f) % 6f / 6f;
        else if (max == g) h = ((b - r) / d + 2f) / 6f;
        else h = ((r - g) / d + 4f) / 6f;
        return new float[]{h, max <= 0f ? 0f : d / max, max};
    }

    private static int hsvToRgb(float h, float s, float v) {
        float c = v * s, x = c * (1 - Math.abs((h * 6f) % 2f - 1)), m = v - c;
        float r, g, b;
        int k = (int) (h * 6f) % 6;
        switch (k) {
            case 0 -> { r = c; g = x; b = 0; }
            case 1 -> { r = x; g = c; b = 0; }
            case 2 -> { r = 0; g = c; b = x; }
            case 3 -> { r = 0; g = x; b = c; }
            case 4 -> { r = x; g = 0; b = c; }
            default -> { r = c; g = 0; b = x; }
        }
        return (Math.round((r + m) * 255) << 16) | (Math.round((g + m) * 255) << 8) | Math.round((b + m) * 255);
    }

    static void clear() {
        CACHE.clear();
    }
}
