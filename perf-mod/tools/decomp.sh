#!/bin/bash
# decomp.sh <jar> -> $S/src/<basename>; also extracts nested jarjar jars and decompiles them
S=/tmp/claude-0/-home-user-Fly-Away/a22993c7-9e20-5b76-9e0a-8a48956651d4/scratchpad
j="$1"; n=$(basename "$j" .jar | tr ' ' '_'); out=$S/src/$n
[ -f "$out/.done" ] && exit 0
rm -rf "$out"; mkdir -p "$out"
java -Xmx2500m -jar $S/dl/vineflower.jar -dgs=1 -log=ERROR "$j" "$out" >/dev/null 2>&1
tmp=$(mktemp -d); (cd $tmp && unzip -qo "$j" 'META-INF/jarjar/*.jar' 'META-INF/jars/*.jar' 2>/dev/null)
for nj in $tmp/META-INF/jarjar/*.jar $tmp/META-INF/jars/*.jar; do [ -f "$nj" ] || continue; mkdir -p "$out/_nested/$(basename $nj .jar)"; java -Xmx2500m -jar $S/dl/vineflower.jar -dgs=1 -log=ERROR "$nj" "$out/_nested/$(basename $nj .jar)" >/dev/null 2>&1; done
rm -rf $tmp; touch "$out/.done"; echo "done $n"
