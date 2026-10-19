#!/bin/sh
# Builds the mod and its tests, then runs what is asked for.
# Usage: ./test.sh bench [-v] [-only name] [blueprint files...]
set -e
cd "$(dirname "$0")"
GAME_JAR="${GAME_JAR:-../Mindustry.jar}"
# SNAP=<dir>: run on the classes copied there (by test/suite.sh) and build nothing, so that the source can be worked on meanwhile
if [ -n "$SNAP" ]; then
  mkdir -p build/test-data
  CP="$GAME_JAR:$SNAP/classes:$SNAP/test-classes"
else
  ./build.sh "$GAME_JAR" > /dev/null
  mkdir -p build/test-classes build/test-data
  javac --release 17 -proc:none -cp "$GAME_JAR:build/classes" -d build/test-classes test/src/xtest/*.java tools/src/tools/Ascii.java
  CP="$GAME_JAR:build/classes:build/test-classes"
fi
# what a run without a window is started with: see docs/PLAN.md, "how fast the tests go"
RUN_OPTS="${RUN_OPTS--XX:+UseSerialGC -XX:CICompilerCount=2}"
what="$1"; shift
case "$what" in
  build)
    # ./test.sh build   only builds the mod and its tests
    ;;
  bench)
    files=""; flags=""
    for a in "$@"; do case "$a" in *.txt) files="$files $a";; *) flags="$flags $a";; esac; done
    [ -n "$files" ] || files="blueprints/*.txt"
    java -cp "$CP" xtest.Bench build/test-data $flags $files 2>build/test-err.log
    ;;
  sim)
    # ./test.sh sim <minutes> <open|closed> [-tech early] <map names...>
    mkdir -p build/test-maps
    [ -f build/test-maps/maps/erekir/onset.msav ] || unzip -q -o "$GAME_JAR" 'maps/erekir/*' -d build/test-maps
    minutes="$1"; area="$2"; shift 2
    tech=""
    if [ "$1" = "-tech" ]; then tech="-tech $2"; shift 2; fi
    maps=""
    for m in "$@"; do maps="$maps build/test-maps/maps/erekir/$m.msav"; done
    java -cp "$CP" xtest.Sim build/test-data blueprints "$minutes" "$area" $tech $maps 2>build/test-err.log
    ;;
  probe)
    # ./test.sh probe <map> <minutes> [x y width height]
    mkdir -p build/test-maps
    [ -f build/test-maps/maps/erekir/onset.msav ] || unzip -q -o "$GAME_JAR" 'maps/erekir/*' -d build/test-maps
    map="$1"; shift
    java -cp "$CP" xtest.Probe build/test-data blueprints "build/test-maps/maps/erekir/$map.msav" "$@" 2>build/test-err.log
    ;;
  reload)
    # ./test.sh reload <map> [old save file]
    mkdir -p build/test-maps
    [ -f build/test-maps/maps/erekir/onset.msav ] || unzip -q -o "$GAME_JAR" 'maps/erekir/*' -d build/test-maps
    map="$1"; shift
    java -cp "$CP" xtest.Reload build/test-data blueprints "build/test-maps/maps/erekir/$map.msav" "$@" 2>build/test-err.log
    ;;
  knobs)
    mkdir -p build/test-maps
    [ -f build/test-maps/maps/erekir/onset.msav ] || unzip -q -o "$GAME_JAR" 'maps/erekir/*' -d build/test-maps
    java -cp "$CP" xtest.Knobs build/test-data blueprints build/test-maps/maps/erekir 2>build/test-err.log
    ;;
  look)
    # ./test.sh look <save.msav> [minutes [scale]]   a saved game as it stands: what each side has, a map of it, what the enemy is thinking; then run on with nobody playing
    java -cp "$CP" xtest.Look build/test-data blueprints "$@" 2>build/test-err.log
    ;;
  wild)
    # ./test.sh wild <minutes> <plain|poor|none> <seeds...>   maps the mod has never seen, made by the game's generator
    java -cp "$CP" xtest.Wild build/test-data blueprints "$@" 2>build/test-err.log
    ;;
  lab)
    # ./test.sh lab <file> [name|all] [seconds]
    java -cp "$CP" xtest.Lab build/test-data "$@" 2>build/test-err.log
    ;;
  campaign)
    # ./test.sh campaign <minutes a sector> [difficulty] [first sector]   the mod plays the campaign from the player's side
    mkdir -p build/test-maps
    [ -f build/test-maps/maps/erekir/onset.msav ] || unzip -q -o "$GAME_JAR" 'maps/erekir/*' -d build/test-maps
    rm -rf build/campaign-data; mkdir -p build/campaign-data
    java -cp "$CP" xtest.Campaign build/campaign-data blueprints build/test-maps/maps/erekir "$@" 2>build/test-err.log
    ;;
  batch)
    # ./test.sh batch <name> <runs> <minutes a sector> [difficulty] [first sector] [sectors a run]
    # The campaign played many times over, as many at once as the machine has cores for. Each run has its
    # own data and its own record of where it got to; logs go to build/logs/<name>-<n>.log.
    # JOBS=n runs n at a time (default 4, one for each fast core: measured, more gains nothing, and a machine with no fan slows down under it); JAVA_OPTS is passed to every run.
    # Planning is timed by its steps, not the wall (WALL=1 for the wall); SEEDED=1 also fixes each run's random seed.
    mkdir -p build/test-maps build/logs
    [ -f build/test-maps/maps/erekir/onset.msav ] || unzip -q -o "$GAME_JAR" 'maps/erekir/*' -d build/test-maps
    name="$1"; runs="$2"; minutes="$3"; difficulty="${4:-hard}"; first="$5"; sectors="${6:-99}"
    jobs="${JOBS:-4}"
    began=$(date +%s)
    export CP RUN_OPTS JAVA_OPTS
    seq 1 "$runs" | xargs -P "$jobs" -n 1 sh test/one-run.sh "$name" "$minutes" "$difficulty" "$sectors" "$first"
    took=$(( $(date +%s) - began ))
    for n in $(seq 1 "$runs"); do
      grep -E "^  [a-z-]+: (WON|LOST|NOT WON|could not)|stopped with an error" "build/logs/$name-$n.log" | sed "s/^ */  $n /; s/; its units.*//"
    done
    grep -h "^  speed:" build/logs/$name-[0-9]*.log | awk -v took="$took" -v jobs="$jobs" '{ game += $2; wall += $6 } END { printf "%d game minutes in %d s with %d at once: %.0f game minutes a second (one run alone: %.1f)\n", game / 60, took, jobs, game / 60 / (took > 0 ? took : 1), game / 60 / wall }'
    ;;
  compare)
    # ./test.sh compare <batch> [<batch>]   what each run of a batch measured, averaged with standard errors; two batches set against each other
    python3 -I test/compare.py "$@"
    ;;
  scan)
    # ./test.sh scan <map names...>   static facts about official maps: rules, waves, cores, resources by distance, routes and chokes per unit class, both bases
    # ./test.sh scan -tech | -ranges   the Erekir tech tree with every gate resolved | turret, bridge and node ranges
    mkdir -p build/test-maps build/maptools
    [ -f build/test-maps/maps/erekir/onset.msav ] || unzip -q -o "$GAME_JAR" 'maps/erekir/*' -d build/test-maps
    javac --release 17 -proc:none -cp "$CP" -d build/maptools tools/maptools/src/maptools/*.java
    case "$1" in
      -tech) java -cp "$CP:build/maptools" maptools.TechDump build/test-data 2>build/test-err.log ;;
      -ranges) java -cp "$CP:build/maptools" maptools.Ranges build/test-data 2>build/test-err.log ;;
      *)
        maps=""
        for m in "$@"; do maps="$maps build/test-maps/maps/erekir/$m.msav"; done
        java -cp "$CP:build/maptools" maptools.MapScan build/test-data $maps 2>build/test-err.log
        ;;
    esac
    ;;
  econ)
    # ./test.sh econ <map> <sector> <unit> <machines>   the economy model's fastest plan for a unit on a map: e.g. econ atlas atlas locus 1
    mkdir -p build/test-maps
    [ -f build/test-maps/maps/erekir/onset.msav ] || unzip -q -o "$GAME_JAR" 'maps/erekir/*' -d build/test-maps
    java -cp "$CP" xtest.EconLab build/test-data "build/test-maps/maps/erekir/$1.msav" "$2" "$3" "$4" 2>build/test-err.log
    ;;
  econrun)
    # ./test.sh econrun <map> <sector> <unit> <machines> <minutes>   the plan laid out and played in the game, set against the model
    mkdir -p build/test-maps
    [ -f build/test-maps/maps/erekir/onset.msav ] || unzip -q -o "$GAME_JAR" 'maps/erekir/*' -d build/test-maps
    java $RUN_OPTS -cp "$CP" xtest.EconRun build/test-data "build/test-maps/maps/erekir/$1.msav" "$2" "$3" "$4" "$5" 2>build/test-err.log
    ;;
  econbench)
    # ./test.sh econbench [jobs] [all|first7] [seeds]   the plan laid out and played on many maps (from several seeds each);
    # a table of the model against the game, and how often each map got its unit at 90% of the model's time or better
    mkdir -p build/test-maps build/bench
    [ -f build/test-maps/maps/erekir/onset.msav ] || unzip -q -o "$GAME_JAR" 'maps/erekir/*' -d build/test-maps
    jobs="${1:-4}"; which="${2:-all}"; seeds="${3:-1}"
    snap=build/bench/snap; rm -rf $snap; mkdir -p $snap; cp -r build/classes $snap/classes; cp -r build/test-classes $snap/test-classes
    CP="$GAME_JAR:$snap/classes:$snap/test-classes"
    # map sector unit machines minutes [radius]   (split, peaks and siege have no tungsten in reach: a T1 goal there, of a
    # kind the map does not ban; basin's tungsten is all under enemy turrets)
    cat > build/bench/all <<LIST
onset onset stell 2 8
aegis aegis stell 3 8
lake lake stell 2 8
intersect intersect stell 2 8
atlas atlas locus 1 12
split atlas merui 2 12
basin atlas stell 2 12
marsh atlas locus 1 12
peaks atlas elude 2 12
ravine atlas locus 1 12
caldera-erekir atlas locus 1 12
stronghold atlas locus 1 12 200
crevice atlas locus 1 12
siege atlas stell 2 12
crossroads atlas locus 1 12
karst atlas locus 1 12
origin atlas locus 1 12 200
LIST
    head -7 build/bench/all > build/bench/first7
    started=$(date +%s)
    : > build/bench/runs
    seed=0
    while [ $seed -lt $seeds ]; do
      while read map sector unit n minutes radius; do
        while [ $(ps ax | grep -c "[x]test.EconRun") -ge $jobs ]; do sleep 1; done
        echo "$map-$seed" >> build/bench/runs
        ( java $RUN_OPTS -Dxp.radius=${radius:-110} -Dxp.seed=$seed -cp "$CP" xtest.EconRun build/test-data "build/test-maps/maps/erekir/$map.msav" "$sector" "$unit" "$n" "$minutes" > build/bench/$map-$seed.log 2> build/bench/$map-$seed.err; \
          grep -q "^BENCH" build/bench/$map-$seed.log || echo "BENCH $map $sector $unit $n | failed: $(grep -m1 -E 'Exception|Error' build/bench/$map-$seed.err)" >> build/bench/$map-$seed.log ) &
        sleep 1
      done < build/bench/$which
      seed=$((seed + 1))
    done
    while [ $(ps ax | grep -c "[x]test.EconRun") -gt 0 ]; do sleep 1; done
    echo "$(( $(date +%s) - started )) s"
    for run in $(cat build/bench/runs); do sed "s/^BENCH /BENCH [$run] /" build/bench/$run.log | grep -h "^BENCH"; done | tee build/bench/table.txt
    # by map: runs that got the unit at 90% of the model's time or better, and the middle ratio
    awk '{ split($0, seg, "|"); split($2, a, " "); map = $3; n[map]++; r = seg[3]; gsub(/[ %]/, "", r);
           if(seg[2] !~ /never/ && r != "" && r != "-") { if(r + 0 >= 90) good[map]++; sum[map] += r; got[map]++ } }
         END { for(m in n) printf "  %-16s %d of %d runs at 90%% or better; got the unit in %d, on average at %.0f%% of the model time\n", m, good[m], n[m], got[m], got[m] ? sum[m] / got[m] : 0 }' build/bench/table.txt | sort | tee build/bench/summary.txt
    ;;
  duel)
    # ./test.sh duel <minutes> <controller A> <controller B> <maps...>   two sides with the same base and the same units, led by two
    # controllers ("rts" = the game's squad AI alone; "army:flag,..." = with the army module, see expansion.army.Army.Flags);
    # each map is played twice, the sides swapped. A map is a name of the campaign's (onset, aegis, ...) or gen:<seed>.
    # JOBS=n splits the maps over n games at once (default 1; at most 2 on this machine)
    mkdir -p build/test-maps build/duel
    [ -f build/test-maps/maps/erekir/onset.msav ] || unzip -q -o "$GAME_JAR" 'maps/erekir/*' -d build/test-maps
    minutes="$1"; ca="$2"; cb="$3"; shift 3
    maps=""
    for m in "$@"; do case "$m" in gen:*) maps="$maps $m";; *) maps="$maps build/test-maps/maps/erekir/$m.msav";; esac; done
    jobs="${JOBS:-1}"; n=0
    tag="${TAG:+$TAG-}$(echo "$ca-$cb" | tr ':,' '__')"
    for j in $(seq 1 "$jobs"); do
      part=""; i=0
      for m in $maps; do [ $((i % jobs + 1)) -eq "$j" ] && part="$part $m"; i=$((i + 1)); done
      [ -n "$part" ] && java $RUN_OPTS -Dxp.syncpath=true $JAVA_OPTS -cp "$CP" xtest.Duel build/test-data "$minutes" "$ca" "$cb" $part > "build/duel/$tag-$j.log" 2> "build/duel/$tag-$j.err" &
    done
    wait
    cat build/duel/$tag-[0-9]*.log | grep "^DUEL" | sort
    cat build/duel/$tag-[0-9]*.log | grep "^DUEL" | awk -v a="$ca" -v b="$cb" '/winner=A/{w++} /winner=B/{l++} /winner=-/{d++} {for(i=1;i<=NF;i++){split($i,kv,"="); if(kv[1]=="doneA") da+=kv[2]; if(kv[1]=="doneB") db+=kv[2]}} END { printf "TOTAL %s against %s: won %d, lost %d, drew %d; damage done %d against %d\n", a, b, w, l, d, da, db }'
    ;;
  modlab)
    # ./test.sh modlab <out.tsv> <file.msch>...   each schematic put down, fed from its own sources, measured at its voids
    out="$1"; shift
    java $RUN_OPTS -cp "$CP" xtest.ModLab build/test-data "$out" "$@" 2>build/test-err.log
    ;;
  econwhy)
    # ./test.sh econwhy <map> <sector> <unit> <machines> [recipe]   the balanced plant solved, and with one more of a recipe
    java -cp "$CP" xtest.EconWhy build/test-data "build/test-maps/maps/erekir/$1.msav" "$2" "$3" "$4" "$5" 2>build/test-err.log
    ;;
  econcheck)
    # ./test.sh econcheck   the economy model against sums done by hand
    java -cp "$CP" xtest.EconCheck build/test-data 2>build/test-err.log
    ;;
  mind)
    # ./test.sh mind   the posture machine, what it holds it can't do without, what it spares for walls
    java -cp "$CP" xtest.Mind build/test-data blueprints 2>build/test-err.log
    ;;
  pieces)
    java -cp "$CP" xtest.Pieces build/test-data "$@" blueprints/*.txt 2>build/test-err.log
    ;;
  *) echo "unknown: $what"; exit 2;;
esac
