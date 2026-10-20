#!/bin/sh
# Sets a fresh Linux machine up to build this repository and run its tests, with no root and nothing installed beforehand
# but a POSIX shell, tar and curl (or wget):
#   - a JDK 17 or later: the one on PATH if there is one, otherwise Temurin 21 downloaded into ../jdk
#   - the game: Mindustry v160.7's Mindustry.jar into ../Mindustry.jar, checked against the one the tests were made with
#   - the game's Erekir maps out of the jar into build/test-maps, the mod and its tests built
#   - a one-minute game as a check that it all runs (skipped with --no-check)
# test.sh, build.sh and test/matches.sh use ../jdk on their own when it is there; nothing has to be put on PATH.
# Usage: sh tools/server-setup.sh [--no-check]
set -e
cd "$(dirname "$0")/.."
root=$(pwd)
up=$(cd .. && pwd)
jar_sha=36d94941a1639478ad1d9be25a9d802c6f77552f400c6da0338b7b019f63a0d1
jar_url=https://github.com/Anuken/Mindustry/releases/download/v160.7/Mindustry.jar
GAME_JAR="${GAME_JAR:-$up/Mindustry.jar}"

fetch(){
  if command -v curl >/dev/null 2>&1; then curl -fsSL --retry 4 -o "$2" "$1"
  elif command -v wget >/dev/null 2>&1; then wget -q -O "$2" "$1"
  else echo "neither curl nor wget is installed"; exit 1
  fi
}
sha(){
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | cut -d' ' -f1
  else echo unknown
  fi
}

# 1. Java
jdk_ok(){
  command -v javac >/dev/null 2>&1 || return 1
  v=$(javac -version 2>&1 | awk '/javac/ {print $2}' | cut -d. -f1)
  [ "${v:-0}" -ge 17 ] 2>/dev/null
}
if [ -x "$up/jdk/bin/javac" ]; then PATH="$up/jdk/bin:$PATH"; fi
if ! jdk_ok; then
  case "$(uname -m)" in
    x86_64|amd64) arch=x64;;
    aarch64|arm64) arch=aarch64;;
    *) echo "no JDK download for a $(uname -m) machine: install a JDK 17+ and run this again"; exit 1;;
  esac
  echo "== no JDK 17+ found: downloading Temurin 21 into $up/jdk"
  tmp=$(mktemp -d)
  fetch "https://api.adoptium.net/v3/binary/latest/21/ga/linux/$arch/jdk/hotspot/normal/eclipse" "$tmp/jdk.tar.gz"
  rm -rf "$up/jdk"; mkdir -p "$up/jdk"
  tar -xzf "$tmp/jdk.tar.gz" -C "$up/jdk" --strip-components=1
  rm -rf "$tmp"
  PATH="$up/jdk/bin:$PATH"
  jdk_ok || { echo "the downloaded JDK does not run"; exit 1; }
fi
echo "== java: $(command -v java) ($(java -version 2>&1 | grep -v JAVA_TOOL | head -1))"

# 2. the game
if [ ! -f "$GAME_JAR" ] || [ "$(sha "$GAME_JAR")" != "$jar_sha" ]; then
  echo "== downloading Mindustry v160.7 to $GAME_JAR"
  fetch "$jar_url" "$GAME_JAR.part"
  mv "$GAME_JAR.part" "$GAME_JAR"
fi
got=$(sha "$GAME_JAR")
if [ "$got" != "$jar_sha" ] && [ "$got" != unknown ]; then
  echo "Mindustry.jar is not the build the tests were made with (sha256 $got)"; exit 1
fi
echo "== game: $GAME_JAR"

# 3. maps and build
if [ ! -f build/test-maps/maps/erekir/onset.msav ]; then
  mkdir -p build/test-maps
  (cd build/test-maps && jar xf "$GAME_JAR" maps/erekir)
fi
echo "== maps: $(ls build/test-maps/maps/erekir | wc -l) Erekir maps"
GAME_JAR="$GAME_JAR" ./test.sh build
echo "== built"

# 4. what the machine has, for choosing how many games to run at once
cores=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 1)
physical=$( (lscpu -p=Core,Socket 2>/dev/null | grep -v '^#' | sort -u | wc -l) || echo 0)
[ "$physical" -gt 0 ] 2>/dev/null || physical=$cores
mem=$(awk '/MemTotal/ {printf "%d", $2 / 1048576}' /proc/meminfo 2>/dev/null || echo "?")
echo "== machine: $cores logical CPUs, $physical physical cores, ${mem} GB memory"
echo "   games at once: at most the physical cores less two, and about 2 GB of memory each: JOBS=$(( physical > 3 ? physical - 2 : 1 ))"

# 5. a check that a game runs
if [ "$1" != "--no-check" ]; then
  echo "== a one-minute game on onset (even mode)"
  GAME_JAR="$GAME_JAR" ./test.sh match onset 1 even | grep -E "building:|^RESULT" || { echo "the check game did not finish: see build/match-err.log"; exit 1; }
fi
echo "== ready"
