#!/bin/sh
# Builds the blueprint generator (package bp) against the game jar and OR-Tools, into build/bp-classes.
# Usage: bp/build.sh            OR-Tools jars are looked for in $ORTOOLS (default ~/lib/ortools)
set -e
cd "$(dirname "$0")/.."
[ -x ../jdk/bin/javac ] && PATH="$(cd .. && pwd)/jdk/bin:$PATH"
GAME_JAR="${GAME_JAR:-../Mindustry.jar}"
ORTOOLS="${ORTOOLS:-$HOME/lib/ortools}"
CP="$GAME_JAR:$(ls "$ORTOOLS"/*.jar | tr '\n' ':')"
rm -rf build/bp-classes
mkdir -p build/bp-classes
javac --release 17 -proc:none -nowarn -cp "$CP" -d build/bp-classes $(find bp/src -name "*.java")
echo "$CP:build/bp-classes" > build/bp-classpath
