#!/bin/sh
# What a batch of duels says, controller by controller: games won, lost and drawn by A (the controller tried), how many of
# them by a core destroyed, damage done against taken, and on average per game the seconds the other side's turrets stood
# dry, the other side's ammunition spent on A's bait and decoy, and units A lost on its own jobs.
# Usage: test/duelstats.sh <summary files...>   (build/duel/summary-*.txt)
for f in "$@"; do
  grep -h "^DUEL" "$f" | awk -v name="$(basename "$f" .txt | sed 's/^summary-//')" '
    { n++ }
    /winner=A/ { w++ } /winner=B/ { l++ } /winner=-/ { d++ }
    /winner=A how=core/ { wc++ } /winner=B how=core/ { lc++ }
    {
      for(i = 1; i <= NF; i++){
        split($i, kv, "=")
        if(kv[1] == "doneA") da += kv[2]
        if(kv[1] == "doneB") db += kv[2]
        if(kv[1] == "dryB") dry += kv[2]
        if(kv[1] == "dryA") dryown += kv[2]
        if($i == "drained") drained += $(i + 1)
        if($i == "decoyed") decoyed += $(i + 1)
        if($i == "lost" && $(i - 2) == "killed") opslost += $(i + 1)
      }
    }
    END {
      if(n == 0) exit
      printf "%-34s %2d games: won %2d (%2d by core), lost %2d (%2d by core), drew %2d; damage %7d against %7d (%+.0f%%); per game: other side dry %4.0f s (own %4.0f s), drained %4.0f, decoyed %4.0f, lost on jobs %.1f\n",
        name, n, w, wc, l, lc, d, da, db, (da - db) * 100 / db, dry / n, dryown / n, drained / n, decoyed / n, opslost / n
    }'
done
