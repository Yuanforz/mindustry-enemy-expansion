#!/bin/sh
# A tag's results from the queue (test/queue.sh), and its tally.   Usage: test/tally.sh TAG [MODE]
cd "$(dirname "$0")/.."
r=$(grep "^$1 RESULT [^ ]* ${2:-[a-z]*} " build/queue/results.txt | cut -d' ' -f2- | sort -k2,2)
[ -n "$r" ] && echo "$r"
echo "$r" | awk 'NF==0{next} /winner=us/{w++} /winner=(Brain|enemy)/{l++} /winner=-/{d++} /winner=none/{n++} {t++} END{printf "TOTAL: won %d, lost %d, drew %d, not played %d of %d\n", w, l, d, n, t}'
