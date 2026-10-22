#!/bin/sh
# Every test problem laid out again, whole and in modules, each checked by the game's rules: run after every change.
# Usage: bp/regress.sh            (prints one line a problem; any line not "clean" is a regression to look at)
cd "$(dirname "$0")/.."
mkdir -p build/bp-out/regress
for p in test/bp/modules/silicon.prob test/bp/gen-silicon.prob test/bp/gen-silicon3.prob test/bp/gen-carbide.prob test/bp/gen-conquer.prob; do
  n=$(basename $p .prob)
  r=$(timeout 900 sh bp/run.sh bp.Gen build/bp-data $p build/bp-out/regress/$n.txt 2>&1 | grep -E "^generated|verified:" | tr '\n' ' ')
  echo "whole   $n: $r"
done
for p in test/bp/gen-silicon3.prob test/bp/gen-mixed.prob; do
  n=$(basename $p .prob)
  r=$(timeout 2400 sh bp/run.sh bp.Modular build/bp-data $p build/bp-out/regress/$n-mod.txt 2>&1 | grep -E "^all in|^verified:|kept [0-9]+ problems" | tr '\n' ' ')
  echo "modules $n: $r"
done
