#!/bin/sh
# The regression suite: the campaign sectors it can be judged on, 24 runs each, under the same settings every time.
# Usage: test/suite.sh <name> [runs]     (JAVA_OPTS passes switches to every run; logs go to build/logs/<name>-<sector>-<n>.log)
# Prints one line a sector: how the runs ended, how long they lasted, income at 10 minutes, units made, buildings of the other side destroyed.
cd "$(dirname "$0")/.."
name="$1"; runs="${2:-24}"
# built once, and run on a copy of the classes: the source can be changed and built while the suite runs
# (SNAP=<dir> set already: the copy made for another suite, so that several are run on the same code)
if [ -z "$SNAP" ]; then
  ./test.sh build > /dev/null 2>&1
  SNAP="build/snap/$name"
  mkdir -p "$SNAP"
  cp -R build/classes "$SNAP/" && cp -R build/test-classes "$SNAP/"
fi
export SNAP
# SECTORS="aegis:60 atlas:45": only those (each sector with the minutes a run of it may take)
for entry in ${SECTORS:-aegis:60 lake:60 intersect:45 atlas:45 basin:45}; do
  sector="${entry%%:*}"; minutes="${entry##*:}"
  ./test.sh batch "$name-$sector" "$runs" "$minutes" hard "$sector" 1 > /dev/null 2>&1
  python3 -I test/compare.py "$name-$sector" > "build/logs/$name-$sector.summary" 2>&1
  won=$(grep -h 'METRICS' build/logs/$name-$sector-*.log | grep -c 'result=won')
  lost=$(grep -h 'METRICS' build/logs/$name-$sector-*.log | grep -c 'result=lost')
  out=$(grep -h 'METRICS' build/logs/$name-$sector-*.log | grep -c 'result=timeout')
  survive=$(grep '   minute' "build/logs/$name-$sector.summary" | awk '{print $2 "±" $4}')
  income=$(grep 'income10' "build/logs/$name-$sector.summary" | awk '{print $2}')
  made=$(grep '   made' "build/logs/$name-$sector.summary" | awk '{print $2}')
  destroyed=$(grep 'destroyed' "build/logs/$name-$sector.summary" | head -1 | awk '{print $2}')
  printf "%-10s won %2d lost %2d timeout %2d | lasted %s min | income10 %s | made %s | destroyed %s\n" "$sector" "$won" "$lost" "$out" "$survive" "$income" "$made" "$destroyed"
done
