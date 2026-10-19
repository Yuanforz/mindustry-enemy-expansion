#!/bin/sh
# Plays each controller given against the game's squad AI alone on the same maps, both sides of each, and prints a line
# for each: games won, lost and drawn, and damage done against damage taken.
# Usage: test/duels.sh <minutes> <controller...>
#   MAPS="..." to choose the maps; JOBS=2 by default; TAG=name keeps this batch's logs apart from another's.
#   The classes are copied first and the games run on the copy, so the source can be worked on meanwhile.
cd "$(dirname "$0")/.."
minutes="$1"; shift
MAPS="${MAPS:-onset aegis lake intersect atlas basin marsh caldera-erekir stronghold siege origin gen:1 gen:2 gen:3 gen:4 gen:5 gen:6 gen:7 gen:8 gen:9 gen:10}"
export JOBS="${JOBS:-2}"
export TAG="${TAG:-batch}"
mkdir -p build/duel
if [ -z "$SNAP" ]; then
  ./test.sh build > /dev/null 2>&1
  export SNAP="build/duel/snap-$TAG"
  rm -rf "$SNAP"; mkdir -p "$SNAP"; cp -r build/classes "$SNAP/classes"; cp -r build/test-classes "$SNAP/test-classes"
fi
for controller in "$@"; do
  out="build/duel/summary-$TAG-$(echo "$controller" | tr ':,' '__').txt"
  ./test.sh duel "$minutes" "$controller" rts $MAPS > "$out" 2>&1
  grep "^TOTAL" "$out"
done
