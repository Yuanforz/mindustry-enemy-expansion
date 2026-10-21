package expansion.defense;

/**
 * The map as the defence planner sees it: a flag per tile. Plain data, filled from the game by GameData (or by hand in a
 * test). Tiles count from 0 at the bottom left.
 */
public final class Terrain{
    /** Rock: nothing walks or builds here (legs only near its edge). */
    public static final int solid = 1;
    /** Deep liquid: tanks drown, nothing is built. */
    public static final int deep = 2;
    /** So deep in rock that even mechs cannot step on it. */
    public static final int legSolid = 4;
    /** Something stands here already. */
    public static final int built = 8;
    /** No building allowed (outside the open map, near an enemy core, a scripted tile). */
    public static final int noBuild = 16;
    /** A building of the other side: walkable for the other side's units only by breaking it. */
    public static final int enemy = 32;

    public final int w, h;
    public final byte[] flags;
    /**
     * What the game's own path finder charges the side that attacks for a step on each tile, over the step itself, on the
     * ground (near rock, near liquid, on damaging floor, through what stands): the way it really walks. Null where not read.
     */
    public float[] groundCost;
    /** Whether the side that attacks finds its way as a player's units do (the control pathfinder), else by the flow field. */
    public boolean rts = true;

    public Terrain(int w, int h){
        this.w = w;
        this.h = h;
        flags = new byte[w * h];
    }

    public boolean in(int x, int y){
        return x >= 0 && y >= 0 && x < w && y < h;
    }

    public int at(int x, int y){
        return in(x, y) ? flags[x + y * w] : solid | legSolid | noBuild;
    }

    public boolean has(int x, int y, int flag){
        return (at(x, y) & flag) != 0;
    }

    public void set(int x, int y, int flag){
        if(in(x, y)) flags[x + y * w] |= flag;
    }

    public void clear(int x, int y, int flag){
        if(in(x, y)) flags[x + y * w] &= ~flag;
    }

    /** Whether something can be built here. */
    public boolean free(int x, int y){
        return in(x, y) && (at(x, y) & (solid | deep | built | noBuild)) == 0;
    }

    /** Whether a unit that moves this way can cross the tile (our own buildings count as crossable: they are broken through). */
    public boolean passable(Foe.Move move, int x, int y){
        if(!in(x, y)) return false;
        int f = at(x, y);
        return switch(move){
            case ground -> (f & solid) == 0 && (f & deep) == 0;
            case hover -> (f & solid) == 0;
            case legs -> (f & legSolid) == 0;
            case air -> true;
        };
    }
}
