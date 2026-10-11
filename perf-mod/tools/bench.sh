#!/bin/bash
# bench.sh <label> <flyaway jar or "none"> [extra jvm args...]
S=/tmp/claude-0/-home-user-Fly-Away/a22993c7-9e20-5b76-9e0a-8a48956651d4/scratchpad
label=$1; jar=$2; shift 2
I=$S/inst
rm -f $I/mods/flyaway_perf*.jar
[ "$jar" != none ] && cp "$jar" $I/mods/flyaway_perf.jar
rm -rf $I/crash-reports
pgrep -x Xvfb >/dev/null || (Xvfb :99 -screen 0 1280x720x24 >/dev/null 2>&1 &)
export DISPLAY=:99
cd $I
mkdir -p $S/bench
timeout 2400 python3 -I $S/tools/launch.py $I -Xmx6G -Dcustomskinloader.ignorePatchFailure=true -Dflyaway.harness=bench -Dflyaway.harness.label=$label "$@" > $S/bench/$label.log 2>&1
echo "exit $?"
cp $I/flyaway_bench/result-$label.txt $S/bench/ 2>/dev/null && cat $S/bench/result-$label.txt
ls -t $I/config/spark/ 2>/dev/null | head -3
ls $I/crash-reports 2>/dev/null
