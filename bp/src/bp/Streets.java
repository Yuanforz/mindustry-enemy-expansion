package bp;

import java.util.*;

/**
 * Streets: lanes of ground kept for lines, a grid about the core (bp.streetPitch apart, bp.streetWidth wide; width 0 none).
 * No body that is laid now stands on a street (Placer, a hard term); a line pays less a tile there (Router, bp.streetCost
 * of what it pays elsewhere), so that lines between things far apart run along the streets, and the blocks between them
 * keep their ground for machines and the short lines between neighbours. Before, every line took the shortest way,
 * through whatever stood about, and lines laid side by side became a wall later lines could not get over (a bridge
 * reaches over three tiles).
 *
 * Not a street: ground no carrier stands on, and what is kept for machines on ground and their own lines (the places of
 * the plan and the schedule, the tiles their beams pass over, the strips of rows of pumps). The core sits in the middle
 * of a block, with a ring of street about it (bp.plaza wide): every line of items ends at the core, from every side.
 */
public final class Streets{
    public static final int PITCH = Integer.getInteger("bp.streetPitch", 22), WIDTH = Integer.getInteger("bp.streetWidth", 3);
    public static final float COST = Float.parseFloat(System.getProperty("bp.streetCost", "0.6"));
    /** A ring of street about the core so wide (bp.plaza): every line ends there, the core takes from each side. */
    public static final int PLAZA = Integer.getInteger("bp.plaza", 4);

    /** Whether a tile is on the grid's lines about a core at cx, cy (the core in the middle of a block). */
    public static boolean onGrid(int x, int y, int cx, int cy){
        if(WIDTH <= 0 || PITCH <= WIDTH) return false;
        int o = PITCH / 2;
        return Math.floorMod(x - cx - o, PITCH) < WIDTH || Math.floorMod(y - cy - o, PITCH) < WIDTH;
    }

    /** The streets of a board: grid tiles a carrier can stand on, none of those kept. */
    public static boolean[] mark(Board b, int cx, int cy, Set<Integer> kept){
        return mark(b, cx, cy, 1, kept);
    }

    /** The same, the core coreSize square about cx, cy: the ring about it a street too. */
    public static boolean[] mark(Board b, int cx, int cy, int coreSize, Set<Integer> kept){
        boolean[] s = new boolean[b.w * b.h];
        if(WIDTH <= 0) return s;
        int n = 0;
        int x0 = Geo.lo(cx, coreSize), y0 = Geo.lo(cy, coreSize), x1 = x0 + coreSize - 1, y1 = y0 + coreSize - 1;
        for(int y = 0; y < b.h; y++) for(int x = 0; x < b.w; x++){
            int i = b.idx(x, y);
            int ring = Math.max(Math.max(x0 - x, x - x1), Math.max(y0 - y, y - y1));
            boolean plaza = ring >= 1 && ring <= PLAZA;
            if(!onGrid(x, y, cx, cy) && !plaza || kept.contains(i) || !b.floor(x, y)) continue;
            s[i] = true;
            n++;
        }
        b.street = s;
        b.streetCount = n;
        return s;
    }
}
