#!/bin/sh
# Builds build/EnemyExpansion.jar against the game jar.
# Usage: ./build.sh [path/to/Mindustry.jar]
set -e
cd "$(dirname "$0")"
GAME_JAR="${1:-../Mindustry.jar}"

rm -rf build/classes build/EnemyExpansion.jar
mkdir -p build/classes
javac --release 17 -proc:none -cp "$GAME_JAR" -d build/classes src/expansion/*.java
jar --create --file build/EnemyExpansion.jar -C build/classes . mod.hjson bundles blueprints
echo "Built build/EnemyExpansion.jar"
