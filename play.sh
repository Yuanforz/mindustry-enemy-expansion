#!/bin/sh
# Opens the game on a generated Erekir map to try the mod out. The enemy starts from one bare core
# with the same stock as you; the fog is off.
#
# Usage: ./play.sh [seed|random] [plain|poor|none] [cores]   a game to play (default seed 111, one enemy core). Its units attack in squads, as on the campaign's attack maps.
#        ./play.sh continue                          the game as it is, to load the save of an earlier trial
#        ./play.sh watch [seed] [plain|poor|none]    camera free over the enemy's core, its stock and plans on screen, nothing of yours can be destroyed
#        ./play.sh campaign [sector]                 a campaign sector (default lake) played live with the mod on YOUR side, as the campaign test plays it: hard, strongest fair mode, research from the record of the sectors before, fog off
#                                                    XP_SPEED=n runs it n times as fast
#
# Each kind runs in a data folder of its own under build/, made of copies of your settings and mods
# (taken on first use; the mod itself is taken from build/ every time). Your real game is not touched.
# The Nightmare mod is left out unless PLAY_NIGHTMARE=1 is set.
set -e
cd "$(dirname "$0")"
MOD="$(pwd)"
GAME_JAR="${GAME_JAR:-$MOD/../Mindustry-HiDPI.jar}"
REAL="$HOME/Library/Application Support/Mindustry"

mode=play
case "$1" in
  watch) mode=watch; shift ;;
  continue) mode=menu; shift ;;
  campaign) mode=campaign; shift; SECTOR="${1:-lake}"; shift || true ;;
esac
seed="${1:-111}"
[ "$seed" = random ] && seed=$(( ($(date +%s) % 9000) + 1000 ))
stock="${2:-plain}"
cores="${3:-1}"

[ -f build/EnemyExpansion.jar ] || ./build.sh "$MOD/../Mindustry.jar" > /dev/null
kind=play; [ "$mode" = watch ] && kind=watch; [ "$mode" = campaign ] && kind=campaign
DATA="$MOD/build/$kind-data"
mkdir -p "$DATA/mods" build/playtest-classes
[ -f "$DATA/settings.bin" ] || cp "$REAL/settings.bin" "$DATA/settings.bin"
for jar in "$REAL"/mods/*.jar; do
  # the Nightmare mod makes the enemy's units and buildings stronger wherever it is switched on for custom games:
  # left out unless asked for (PLAY_NIGHTMARE=1), so that what is tried here is this mod alone
  case "$jar" in *Nightmare.jar) [ -n "$PLAY_NIGHTMARE" ] || { rm -f "$DATA/mods/Nightmare.jar"; continue; } ;; esac
  [ -f "$jar" ] && cp "$jar" "$DATA/mods/"
done
cp build/EnemyExpansion.jar "$DATA/mods/EnemyExpansion.jar"
if [ "$mode" = campaign ]; then
  # the campaign test's own classes go along: research is done the way it does it
  ./build.sh "$MOD/../Mindustry.jar" > /dev/null
  cp build/EnemyExpansion.jar "$DATA/mods/EnemyExpansion.jar"
  mkdir -p build/test-classes
  javac --release 17 -proc:none -cp "$MOD/../Mindustry.jar:build/classes" -d build/test-classes test/src/xtest/*.java
  mkdir -p build/test-maps
  [ -f build/test-maps/maps/erekir/lake.msav ] || unzip -q -o "$MOD/../Mindustry.jar" 'maps/erekir/*' -d build/test-maps
  rm -rf build/playtest-classes; mkdir -p build/playtest-classes
  javac --release 17 -proc:none -cp "$MOD/../Mindustry.jar:build/classes:build/test-classes" -d build/playtest-classes tools/playtest/src/xpwatch/*.java tools/playtest/src/xtest/*.java
  cp -R build/test-classes/xtest build/playtest-classes/
else
  javac --release 17 -proc:none -cp "$MOD/../Mindustry.jar" -d build/playtest-classes tools/playtest/src/xpwatch/*.java
fi
jar cf "$DATA/mods/XpWatch.jar" -C tools/playtest mod.hjson -C build/playtest-classes .

[ "$mode" = menu ] || echo "seed $seed, start stock $stock"
set -- java -XstartOnFirstThread -Dxp.mode="$mode" -Dxp.sector="${SECTOR:-lake}" -Dxp.root="$MOD" -Dxp.seed="$seed" -Dxp.stock="$stock" -Dxp.cores="$cores" ${XP_PEEK:+-Dxp.peek="$XP_PEEK" -Dxp.shotframe=900} ${XP_STRONGEST:+-Dxp.strongest=1} ${XP_NOTES:+-Dxp.notes="$XP_NOTES"} ${XP_SETTINGS:+-Dxp.settings=1} ${XP_SHOT:+-Dxp.shot="$XP_SHOT" -Dxp.shotframe="${XP_SHOTFRAME:-900}" -Dxp.speed="${XP_SPEED:-1}"} -jar "$GAME_JAR"
if [ -n "$XP_DRY" ]; then echo "MINDUSTRY_DATA_DIR=$DATA $*"; exit 0; fi
# The game looks for its own files in the folder it is started from before it looks inside its jar. Started
# from this folder it would take the mod's bundles/ for its own and show every text of its own as ???name???.
mkdir -p build/run
cd build/run
MINDUSTRY_DATA_DIR="$DATA" exec "$@"
