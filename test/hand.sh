#!/bin/sh
# Plays a sector by hand: test/hand.sh <sector> [minutes] [dir]. The game waits for commands written to dir/in.txt
# (see test/src/xtest/Hand.java); dir/h "cmd" ... sends some and prints the answer.
cd "$(dirname "$0")/.."
sector="$1"; minutes="${2:-60}"; dir="${3:-build/hand}"
mkdir -p "$dir" "build/run/hand-$sector/state"
: > "$dir/in.txt"; : > "$dir/out.txt"
exec java -XX:+UseSerialGC -Dxp.hand="$dir" -Dxp.state="build/run/hand-$sector/state" -Dxp.sectors=1 -cp ../Mindustry.jar:build/classes:build/test-classes xtest.Campaign "build/run/hand-$sector" blueprints build/test-maps/maps/erekir "$minutes" hard "$sector" > "$dir/log.txt" 2>&1
