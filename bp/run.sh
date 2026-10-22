#!/bin/sh
# Runs a class of the blueprint generator.   Usage: bp/run.sh bp.Class args...
cd "$(dirname "$0")/.."
[ -x ../jdk/bin/java ] && PATH="$(cd .. && pwd)/jdk/bin:$PATH"
ORTOOLS="${ORTOOLS:-$HOME/lib/ortools}"
mkdir -p "$ORTOOLS/tmp" build/bp-data
# (BP_CLASSPATH: run from a snapshot of the classes instead, so that a long test is not broken by a rebuild)
exec java -Djava.awt.headless=true -XX:+UseSerialGC -Djava.io.tmpdir="$ORTOOLS/tmp" -cp "${BP_CLASSPATH:-$(cat build/bp-classpath)}" "$@"
