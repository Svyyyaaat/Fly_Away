#!/bin/bash
# Extract jar-in-jar libraries from every pack mod so mixins can compile against them.
src="$1"; out="$2"; mkdir -p "$out"
for j in "$src"/*.jar; do
  n=$(basename "$j" .jar)
  [ -d "$out/$n" ] && continue
  mkdir -p "$out/$n"
  (cd "$out/$n" && unzip -qo -j "$j" 'META-INF/jarjar/*.jar' 'META-INF/jars/*.jar' >/dev/null 2>&1)
  # one more level (nested-of-nested)
  for nj in "$out/$n"/*.jar; do [ -f "$nj" ] || continue; (cd "$out/$n" && unzip -qo -j "$nj" 'META-INF/jarjar/*.jar' 'META-INF/jars/*.jar' >/dev/null 2>&1); done
done
exit 0
