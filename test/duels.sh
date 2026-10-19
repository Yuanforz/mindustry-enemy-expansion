#!/bin/sh
# Plays each controller given against the game's squad AI alone on the same maps, both sides of each, and prints a line
# for each: games won, lost and drawn, and damage done against damage taken.
# Usage: test/duels.sh <minutes> <controller...>      MAPS="..." to choose the maps; JOBS=2 by default
cd "$(dirname "$0")/.."
minutes="$1"; shift
MAPS="${MAPS:-onset aegis lake intersect atlas basin marsh caldera-erekir stronghold siege origin gen:1 gen:2 gen:3 gen:4 gen:5 gen:6 gen:7 gen:8 gen:9 gen:10}"
export JOBS="${JOBS:-2}"
mkdir -p build/duel
for controller in "$@"; do
  ./test.sh duel "$minutes" "$controller" rts $MAPS > "build/duel/summary-$(echo "$controller" | tr ':,' '__').txt" 2>&1
  grep "^TOTAL" "build/duel/summary-$(echo "$controller" | tr ':,' '__').txt"
done
