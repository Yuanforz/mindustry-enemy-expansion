#!/bin/sh
# Plays whole games of our side against the Brain on many maps (xtest.Match), a few at a time, on a copy of the classes
# (so the source can be worked on meanwhile), and prints each game's result line and a tally.
# Usage: test/matches.sh <minutes> <sector|even> [maps...]
#   JOBS=2 by default; TAG=name keeps this batch's logs apart; STAGES=... our stages (Match's -Dmatch.stages);
#   OPTS="-Dmatch.us=rts ..." more options for every game.
cd "$(dirname "$0")/.."
GAME_JAR="${GAME_JAR:-../Mindustry.jar}"
minutes="${1:-25}"; mode="${2:-even}"; shift 2
MAPS="${*:-onset aegis lake intersect atlas split basin marsh peaks ravine caldera-erekir stronghold crevice siege crossroads karst origin}"
JOBS="${JOBS:-2}"; TAG="${TAG:-m}"
STAGES="${STAGES:-stell:2|merui:2|elude:2|avert:1,defend,locus:1|stell:4|merui:4|elude:4|avert:2,defend,locus:2|stell:6|merui:6|elude:6|avert:3}"
snap="build/match/snap-$TAG"
rm -rf "$snap"; mkdir -p "$snap"; cp -r build/classes "$snap/classes"; cp -r build/test-classes "$snap/test-classes"
mkdir -p build/test-maps
[ -f build/test-maps/maps/erekir/onset.msav ] || unzip -q -o "$GAME_JAR" 'maps/erekir/*' -d build/test-maps
run(){
  java -XX:+UseSerialGC -XX:CICompilerCount=2 -Dmatch.stages="$STAGES" $OPTS -cp "$GAME_JAR:$snap/classes:$snap/test-classes" xtest.Match build/test-data "build/test-maps/maps/erekir/$1.msav" "$minutes" "$mode" > "build/match/$TAG-$1-$mode.log" 2> "build/match/$TAG-$1-$mode.err"
}
running=0
for m in $MAPS; do
  run "$m" &
  running=$((running + 1))
  if [ "$running" -ge "$JOBS" ]; then wait -n 2>/dev/null || wait; running=$((running - 1)); fi
done
wait
out="build/match/summary-$TAG-$mode.txt"
: > "$out"
for m in $MAPS; do
  r=$(grep -h "^RESULT" "build/match/$TAG-$m-$mode.log" 2>/dev/null)
  [ -n "$r" ] || r="RESULT $m $mode none: $(grep -h "Exception\|Error" "build/match/$TAG-$m-$mode.log" "build/match/$TAG-$m-$mode.err" 2>/dev/null | head -1)"
  echo "$r" >> "$out"
done
cat "$out"
awk '/winner=us/{w++} /winner=Brain/{l++} /winner=-/{d++} /winner=none/{n++} END{printf "TOTAL: won %d, lost %d, drew %d, not played %d of %d\n", w, l, d, n, NR}' "$out" | tee -a "$out"
