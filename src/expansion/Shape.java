package expansion;

import arc.math.geom.*;
import arc.struct.*;
import expansion.Blueprint.*;

/** Where blocks are in relation to each other: the tiles a block stands on, the tiles along its sides, what touches what. */
public class Shape{

    /** The lowest tile a block covers, along either axis, for a block built from the given tile. */
    public static int low(Part part, boolean vertical){
        return (vertical ? part.y : part.x) - (part.block.size - 1) / 2;
    }

    public static boolean covers(Part part, int x, int y){
        int lx = low(part, false), ly = low(part, true);
        return x >= lx && y >= ly && x < lx + part.block.size && y < ly + part.block.size;
    }

    /**
     * Which side of a block a tile next to it is on: 0 to 3 for +x, +y, -x, -y, or -1 if the tile is not
     * right outside one of its sides.
     */
    public static int sideOf(Part part, int x, int y){
        int lx = low(part, false), ly = low(part, true), size = part.block.size;
        boolean inX = x >= lx && x < lx + size, inY = y >= ly && y < ly + size;
        if(inY && x == lx + size) return 0;
        if(inX && y == ly + size) return 1;
        if(inY && x == lx - 1) return 2;
        if(inX && y == ly - 1) return 3;
        return -1;
    }

    /** The tiles right outside one side of a block, as packed positions. */
    public static IntSeq along(Part part, int side, IntSeq out){
        out.clear();
        int lx = low(part, false), ly = low(part, true), size = part.block.size;
        for(int i = 0; i < size; i++){
            out.add(switch(side){
                case 0 -> Point2.pack(lx + size, ly + i);
                case 1 -> Point2.pack(lx + i, ly + size);
                case 2 -> Point2.pack(lx - 1, ly + i);
                default -> Point2.pack(lx + i, ly - 1);
            });
        }
        return out;
    }

    /** The side of the first block that the second one stands against, or -1 if they do not touch along a side. */
    public static int touching(Part first, Part second){
        int lx = low(second, false), ly = low(second, true), size = second.block.size;
        for(int dx = 0; dx < size; dx++){
            for(int dy = 0; dy < size; dy++){
                int side = sideOf(first, lx + dx, ly + dy);
                if(side != -1) return side;
            }
        }
        return -1;
    }

    /** Every tile a block stands on, as packed positions. */
    public static void tiles(Part part, IntSet into){
        int lx = low(part, false), ly = low(part, true), size = part.block.size;
        for(int dx = 0; dx < size; dx++){
            for(int dy = 0; dy < size; dy++) into.add(Point2.pack(lx + dx, ly + dy));
        }
    }
}
