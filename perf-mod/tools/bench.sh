#!/bin/bash
# bench.sh <label> <flyaway jar or "none"> [extra jvm args...]
S=/tmp/claude-0/-home-user-Fly-Away/a22993c7-9e20-5b76-9e0a-8a48956651d4/scratchpad
exec 9>$S/bench.lock; flock -w 3600 9 || { echo "bench lock busy"; exit 3; }
label=$1; jar=$2; shift 2
I=$S/inst
rm -f $I/mods/flyaway_perf*.jar
[ "$jar" != none ] && cp "$jar" $I/mods/flyaway_perf.jar
rm -rf $I/crash-reports; if [ "${SHADERS:-1}" = 1 ]; then sed -i "s/^enableShaders=.*/enableShaders=true/" $I/config/iris.properties; else sed -i "s/^enableShaders=.*/enableShaders=false/" $I/config/iris.properties; fi
pgrep -x Xvfb >/dev/null || (Xvfb :99 -screen 0 ${MCW:-640}x${MCH:-360}x24 >/dev/null 2>&1 &)
export GALLIUM_NOOP=${NOOP:-0} MCW=${MCW:-640} MCH=${MCH:-360} DISPLAY=:99 MESA_GL_VERSION_OVERRIDE=4.6 MESA_GLSL_VERSION_OVERRIDE=460
cd $I
mkdir -p $S/bench
timeout 2400 python3 -I $S/tools/launch.py $I -Xmx5500m -XX:MaxDirectMemorySize=1g -Dcustomskinloader.ignorePatchFailure=true -Dflyaway.harness=bench -Dflyaway.harness.label=$label "$@" > $S/bench/$label.log 2>&1
echo "exit $?"
cp $I/flyaway_bench/result-$label.txt $S/bench/ 2>/dev/null && cat $S/bench/result-$label.txt
ls -t $I/config/spark/ 2>/dev/null | head -3
ls $I/crash-reports 2>/dev/null
