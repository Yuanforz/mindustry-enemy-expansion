#!/bin/sh
# The scoreboard: every change is judged on all of these at once, so that what one gains is not paid for out of another.
# Usage: test/score.sh <name> <baseline-dir> [parts]
#   baseline-dir: a build to play against in the duels, as made by "test/score.sh freeze <dir>" (classes + blueprints)
#   parts: any of "alone raid duel" (all three if left out)
#   test/score.sh freeze <dir>   copies the current build there, as a baseline for later runs
#   CLASSES=<dir> test/score.sh ...   scores the build frozen in <dir> instead of the current one (with the current tests)
# Each part plays the 32 generated maps of test/seeds.txt, on the step clock with paths in step, nine at a time.
# Logs go to build/score/<name>/<part>/; test/score.py <name> [<name> ...] prints the table.
cd "$(dirname "$0")/.."
if [ "$1" = freeze ]; then
  ./test.sh build > /dev/null 2>&1
  mkdir -p "$2"
  cp -R build/classes "$2/" && cp -R blueprints "$2/"
  echo "baseline frozen in $2"
  exit 0
fi
name="$1"; base="$2"; parts="${3:-alone raid duel}"
[ -n "$name" ] && [ -d "$base/classes" ] || { echo "usage: test/score.sh <name> <baseline-dir> [parts]"; exit 1; }
./test.sh build > /dev/null 2>&1 || { echo "build failed"; exit 1; }
out="build/score/$name"; snap="$out/snap"
mkdir -p "$snap"
cp -R build/classes "$snap/" && cp -R build/test-classes "$snap/" && cp -R blueprints "$snap/"
# CLASSES=<dir>: the mod's classes (and blueprints beside them) from another build, played with the current tests
if [ -n "$CLASSES" ]; then cp -R "$CLASSES/classes/." "$snap/classes/"; [ -d "$CLASSES/blueprints" ] && cp -R "$CLASSES/blueprints/." "$snap/blueprints/"; fi
seeds=$(cat test/seeds.txt)
jobs=${P:-9}
CP="../Mindustry.jar:$snap/classes:$snap/test-classes"
OPTS="-XX:+UseSerialGC -XX:CICompilerCount=2 -Dxp.virtual=true -Dxp.syncpath=true -Dxp.syncfog=true -Dxtest.strongest=true"
run(){
  # run <part> <script body using $1 as the seed>
  part=$1; body=$2
  mkdir -p "$out/$part"
  printf '%s\n' "$body" > "$out/$part.sh"
  for seed in $seeds; do echo $seed; done | xargs -P $jobs -n 1 sh "$out/$part.sh"
}
for part in $parts; do
  case "$part" in
    alone)
      # the enemy alone for thirty minutes: its opening, its units of each tier
      run alone "java $OPTS -cp $CP xtest.Wild build/test-data $snap/blueprints 30 plain \$1 > $out/alone/\$1.log 2>/dev/null" ;;
    raid)
      # a person's pace of raiding from the sixth minute, twenty minutes in all
      run raid "java $OPTS -Dxtest.raid=6 -cp $CP xtest.Wild build/test-data $snap/blueprints 20 plain \$1 > $out/raid/\$1.log 2>/dev/null" ;;
    duel)
      # against the baseline, both seats of every map, both sides led by the game's squad AI
      mkdir -p "$out/duel"
      printf '%s\n' "seed=\${1%.*}; seat=\${1#*.}; swap=false; [ \"\$seat\" = B ] && swap=true
java $OPTS -Dxp.hashshuffle=\$((seed % 997)) -Dxtest.rtsself=true -Dxtest.rtsother=true -Dxtest.swap=\$swap -cp $CP xtest.BrainDuel build/test-data $snap/blueprints $base/classes $base/blueprints 30 \$seed > $out/duel/\$1.log 2>&1" > "$out/duel.sh"
      for seed in $seeds; do echo $seed.A; echo $seed.B; done | xargs -P $jobs -n 1 sh "$out/duel.sh" ;;
  esac
done
python3 -I test/score.py "$name"
