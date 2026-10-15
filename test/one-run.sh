#!/bin/sh
# One run of the campaign with its own data, for ./test.sh batch: one-run.sh <name> <minutes> <difficulty> <sectors a run> <first sector> <n>
# The log is written in the run's own directory and copied to build/logs/<name>-<n>.log when the run ends.
name="$1"; minutes="$2"; difficulty="$3"; sectors="$4"; first="$5"; n="$6"
run="build/run/$name-$n"
rm -rf "$run"; mkdir -p "$run/state"
java $RUN_OPTS $JAVA_OPTS ${SEEDED:+-Dxp.seed=$n -Dxp.virtual=true -Dxp.syncpath=true} -Dxp.state="$run/state" -Dxp.sectors="$sectors" -cp "$CP" xtest.Campaign "$run" blueprints build/test-maps/maps/erekir "$minutes" "$difficulty" $first \
  > "$run/out.log" 2> "$run/err.log" || echo "run $n stopped with an error" >> "$run/out.log"
mkdir -p build/logs
cp "$run/out.log" "build/logs/$name-$n.log"
