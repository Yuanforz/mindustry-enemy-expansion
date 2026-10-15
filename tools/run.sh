#!/bin/sh
# Small tools for looking at the game's data. They only read.
# Usage: tools/run.sh content                      what every block of the Erekir tech tree costs, needs and makes
#        tools/run.sh schematic file.msch...       draws schematics as text
#        tools/run.sh region map.msav x y w h [team]   draws part of a map as text
set -e
cd "$(dirname "$0")/.."
GAME_JAR="${GAME_JAR:-../Mindustry.jar}"
mkdir -p build/tools build/tools-data
javac -proc:none -cp "$GAME_JAR" -d build/tools tools/src/tools/*.java
what="$1"; shift
case "$what" in
  content) java -cp "$GAME_JAR:build/tools" tools.ContentDump build/tools-data 2>/dev/null ;;
  schematic) java -cp "$GAME_JAR:build/tools" tools.SchemDump build/tools-data "$@" 2>/dev/null ;;
  region) java -cp "$GAME_JAR:build/tools" tools.RegionDump build/tools-data "$@" 2>/dev/null ;;
  *) echo "unknown: $what"; exit 2 ;;
esac
