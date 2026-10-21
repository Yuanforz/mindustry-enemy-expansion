package bp;

import arc.math.geom.*;

/**
 * Where a block stands, as the game counts it: a block of size s put at (x, y) covers x - (s-1)/2 to x + s/2 (and the same
 * for y); rotation 0 faces +x, 1 +y, 2 -x, 3 -y.
 */
public final class Geo{
    private Geo(){
    }

    public static int lo(int at, int size){
        return at - (size - 1) / 2;
    }

    public static int hi(int at, int size){
        return at + size / 2;
    }

    /** The tile to put a block of this size at so that its lowest corner is at (x0, y0). */
    public static int at(int low, int size){
        return low + (size - 1) / 2;
    }

    public static int dx(int rot){
        return Geometry.d4x(rot);
    }

    public static int dy(int rot){
        return Geometry.d4y(rot);
    }

    /** The rotation from one tile to a neighbour (-1 if not a neighbour). */
    public static int dir(int x0, int y0, int x1, int y1){
        for(int r = 0; r < 4; r++) if(x0 + dx(r) == x1 && y0 + dy(r) == y1) return r;
        return -1;
    }
}
