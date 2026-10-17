#!/bin/sh
# One run of the campaign with its own data, for ./test.sh batch: one-run.sh <name> <minutes> <difficulty> <sectors a run> <first sector> <n>
# The log is written in the run's own directory and copied to build/logs/<name>-<n>.log when the run ends.
name="$1"; minutes="$2"; difficulty="$3"; sectors="$4"; first="$5"; n="$6"
run="build/run/$name-$n"
rm -rf "$run"; mkdir -p "$run/state"
# planning is timed by the steps it takes, not the wall: with four runs at once on a machine that slows down when hot, the same
# code won nine of sixteen on the fourth sector one hour and none of eight the next. WALL=1 times it by the wall, as a game does.
# path-finding is done in step with the game, frame by frame, as it keeps up in a game played at its own pace: left to its own
# thread, it fell behind in a run going a hundred times as fast, the other side's units went by old paths, and a sector held in
# 14 of 16 runs that way was held in 1 of 8 with it in step (ASYNC_PATHS=1 for the old way)
java $RUN_OPTS $JAVA_OPTS $([ -z "$WALL" ] && echo -Dxp.virtual=true) $([ -z "$ASYNC_PATHS" ] && echo -Dxp.syncpath=true) ${SEEDED:+-Dxp.seed=$n -Dxp.virtual=true -Dxp.syncpath=true} -Dxp.state="$run/state" -Dxp.sectors="$sectors" -cp "$CP" xtest.Campaign "$run" blueprints build/test-maps/maps/erekir "$minutes" "$difficulty" $first \
  > "$run/out.log" 2> "$run/err.log" || echo "run $n stopped with an error" >> "$run/out.log"
mkdir -p build/logs
cp "$run/out.log" "build/logs/$name-$n.log"
