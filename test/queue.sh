#!/bin/bash
# Keeps the machine busy with whole games (xtest.Match) from a queue that can be added to while it runs. A game is started
# whenever the machine's load is under the limit (the physical cores less a margin: other people's work counts too, so
# when they need the machine fewer of ours run), and never more than MAX of ours at once.
#
# The queue (build/queue/jobs.txt) has one game a line:   TAG MODE MINUTES MAP [java options...]
#   e.g.  l2c even 25 gen:3 -Dmatch.fog=true      (MODE brainrun: the Brain plays MAP, a sector, as xtest.Campaign has it)
# A tag's classes are copied once, when its first game starts (build/match/snap-$TAG), so that the source can be worked
# on meanwhile; a game's log is build/match/$TAG-$MAP-$MODE-<its line>.log and its result line is added to build/queue/results.txt.
#
# Usage: test/queue.sh [--follow]      (--follow: on running out of games, waits for more instead of ending)
#   LOAD=n the load to stay under (default the physical cores less 6); MAX=n at most this many games of ours (default 90)
#   test/tally.sh TAG MODE prints a tag's results and its tally.
cd "$(dirname "$0")/.."
[ -x ../jdk/bin/javac ] && PATH="$(cd .. && pwd)/jdk/bin:$PATH"
GAME_JAR="${GAME_JAR:-../Mindustry.jar}"
physical=$(lscpu -p=Core,Socket 2>/dev/null | grep -v '^#' | sort -u | wc -l)
[ "$physical" -gt 0 ] 2>/dev/null || physical=$(getconf _NPROCESSORS_ONLN)
LOAD="${LOAD:-$((physical - 6))}"; MAX="${MAX:-90}"
mkdir -p build/queue build/match build/test-maps
[ -f build/test-maps/maps/erekir/onset.msav ] || (cd build/test-maps && jar xf "../../$GAME_JAR" maps/erekir)
q=build/queue/jobs.txt; done_file=build/queue/started.txt
touch "$q" "$done_file" build/queue/results.txt

ours(){ jobs -rp | wc -l; }
load(){ cut -d' ' -f1 /proc/loadavg | cut -d. -f1; }

run(){
  local n=$1 tag=$2 mode=$3 minutes=$4 map=$5; shift 5
  local snap="build/match/snap-$tag"
  if [ ! -d "$snap/classes" ]; then
    mkdir -p "$snap"; cp -r build/classes "$snap/classes"; cp -r build/test-classes "$snap/test-classes"
  fi
  local name=${map//:/-}
  local log="build/match/$tag-$map-$mode-$n"
  local r
  if [ "$mode" = brainrun ]; then
    # the Brain on the player's side as its own branch tests it (xtest.Campaign, as test/one-run.sh runs it): one sector,
    # with everything the tech tree allows by then, a run directory of its own
    local dir="build/run/$tag-$map-$n"
    rm -rf "$dir"; mkdir -p "$dir/state"
    java -XX:+UseSerialGC -XX:CICompilerCount=2 -Dxp.virtual=true -Dxp.syncpath=true -Dxp.syncfog=true -Dxp.state="$dir/state" -Dxp.sectors=1 "$@" -cp "$GAME_JAR:$snap/classes:$snap/test-classes" xtest.Campaign "$dir" blueprints build/test-maps/maps/erekir "$minutes" hard "$map" > "$log.log" 2> "$log.err"
    local row=$(grep -h "^  $map: " "$log.log" | head -1)
    local w=$(case "$row" in *": WON"*) echo us;; *": LOST"*) echo enemy;; "") echo none;; *) echo -;; esac)
    local at=$(grep -h "METRICS sector=$map " "$log.log" | sed 's/.*minute=\([0-9]*\).*/\1/')
    r="RESULT $name $mode winner=$w at ${at:-?}:00; ${row#*: }"
  else
    local where=$(case "$map" in gen:*) echo "$map";; *) echo "build/test-maps/maps/erekir/$map.msav";; esac)
    java -XX:+UseSerialGC -XX:CICompilerCount=2 "$@" -cp "$GAME_JAR:$snap/classes:$snap/test-classes" xtest.Match build/test-data "$where" "$minutes" "$mode" > "$log.log" 2> "$log.err"
    r=$(grep -h "^RESULT" "$log.log")
  fi
  [ -n "$r" ] || r="RESULT $name $mode winner=none: $(grep -h -m1 "Exception\|Error" "$log.log" "$log.err" | head -1)"
  echo "$tag $r" >> build/queue/results.txt
}

while true; do
  started=$(wc -l < "$done_file")
  total=$(grep -c . "$q")
  if [ "$started" -lt "$total" ]; then
    # one more while the machine has room: the load counts everyone's running work, ours included
    if [ "$(ours)" -lt "$MAX" ] && [ "$(load)" -lt "$LOAD" ]; then
      line=$(grep . "$q" | sed -n "$((started + 1))p")
      echo "$line" >> "$done_file"
      run $((started + 1)) $line &
      # (the load average follows slowly: a short wait between starts, so that a burst does not overshoot)
      sleep 2
      continue
    fi
  elif [ "$(ours)" -eq 0 ] && [ "$1" != "--follow" ]; then
    break
  fi
  sleep 5
done
echo "queue done"
