package dev.flyaway.perf.imm_ptl;

import net.minecraft.world.level.Level;

/** Cache key: level by identity (Level does not override equals), box compared exactly. */
public record LevelBoxKey(Level level, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
}
