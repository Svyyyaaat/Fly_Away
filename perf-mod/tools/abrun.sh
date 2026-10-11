#!/bin/bash
# abrun.sh <label> <jar> : noop-GL bench with portal + mobs scene
S=/tmp/claude-0/-home-user-Fly-Away/a22993c7-9e20-5b76-9e0a-8a48956651d4/scratchpad
SHADERS=${SHADERS:-0} $S/tools/bench.sh "$1" "$2" -Dflyaway.harness.warmup=${WARM:-75} -Dflyaway.harness.duration=${DUR:-60} -Dflyaway.logMixins=true \
  "-Dflyaway.harness.setup=fill ~-2 ~-2 ~8 ~1 ~2 ~8 minecraft:obsidian;fill ~-1 ~-1 ~8 ~0 ~1 ~8 minecraft:air;setblock ~-1 ~-1 ~8 minecraft:fire"
