#!/bin/sh
# Runs a class of the blueprint generator.   Usage: bp/run.sh bp.Class args...
cd "$(dirname "$0")/.."
[ -x ../jdk/bin/java ] && PATH="$(cd .. && pwd)/jdk/bin:$PATH"
ORTOOLS="${ORTOOLS:-$HOME/lib/ortools}"
mkdir -p "$ORTOOLS/tmp" build/bp-data
exec java -XX:+UseSerialGC -Djava.io.tmpdir="$ORTOOLS/tmp" -cp "$(cat build/bp-classpath)" "$@"
