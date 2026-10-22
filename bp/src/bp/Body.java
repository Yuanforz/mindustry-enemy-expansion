package bp;

import mindustry.world.*;

import java.util.*;

/**
 * Blocks that are put down together and move together as the layout is searched: a machine, or a machine that needs
 * heat with the heaters and redirectors that heat it (heat goes only from a face into what it touches, so they stand
 * fixed against one another), or a block already standing (a core, a source). Its parts are given in its own frame
 * (lowest corner of each, rotation); the body is put at (ox, oy) turned by rot quarter turns anticlockwise.
 */
public final class Body{
    /** One block of a body. */
    public static final class Part{
        public final Body body;
        public final Block block;
        public final int size;
        /** In the body's frame: the lowest corner and the rotation. */
        public final int lx, ly, lrot;
        /** What it works by (null for a block that is only there, as a core or a redirector) and how much it works (1 full). */
        public final Recipe recipe;
        public float work;
        public final String config;
        /** For a machine on ground, its place's yield (ore tiles faced, vent points; 1 for one on no ground). */
        public float yield = 1f;
        /**
         * A module's port as the module is laid out (a source or a void standing for the carrier it will be on the
         * module's edge): an input hands its thing on only ahead, into the module, its back off the module; an output
         * hands on ahead, off the module.
         */
        public boolean port;
        /** A piece of a line that stands as a part (a module's own lines): the piece as laid in the body's frame. */
        public Board.Piece piece;
        /** Where it is now: its lowest corner and rotation on the map. */
        public int x0, y0, rot;

        Part(Body body, Block block, int lx, int ly, int lrot, Recipe recipe, float work, String config){
            this.body = body;
            this.block = block;
            this.size = block.size;
            this.lx = lx;
            this.ly = ly;
            this.lrot = lrot;
            this.recipe = recipe;
            this.work = work;
            this.config = config;
        }

        public int x1(){
            return x0 + size - 1;
        }

        public int y1(){
            return y0 + size - 1;
        }

        public boolean covers(int x, int y){
            return x >= x0 && x <= x1() && y >= y0 && y <= y1();
        }

        /** The tile the game puts it at. */
        public int ax(){
            return Geo.at(x0, size);
        }

        public int ay(){
            return Geo.at(y0, size);
        }

        /** What it makes a second of a resource as it stands (work counted; a heater burns, and so makes, all the time). */
        public float makes(String res){
            if(recipe == null) return 0f;
            return recipe.at(yield).out.getOrDefault(res, 0f) * work + recipe.fixedOut.getOrDefault(res, 0f);
        }

        public float takes(String res){
            if(recipe == null) return 0f;
            return recipe.at(yield).in.getOrDefault(res, 0f) * work + recipe.fixedIn.getOrDefault(res, 0f);
        }

        /** The tiles about it, each with the direction from it to that tile. */
        public List<int[]> around(){
            List<int[]> out = new ArrayList<>();
            for(int i = 0; i < size; i++){
                out.add(new int[]{x1() + 1, y0 + i, 0});
                out.add(new int[]{x0 + i, y1() + 1, 1});
                out.add(new int[]{x0 - 1, y0 + i, 2});
                out.add(new int[]{x0 + i, y0 - 1, 3});
            }
            return out;
        }

        @Override
        public String toString(){
            return block.name + "@" + ax() + "," + ay();
        }
    }

    public final int id;
    public final String label;
    public final List<Part> parts = new ArrayList<>();
    /**
     * Ground it keeps clear of solid blocks, in its own frame ({x0, y0, x1, y1}): an assembler's area, where its unit
     * comes together and out (UnitAssembler.getRect, checkSolid). Two bodies' areas may not overlap if exclusive (an
     * assembler may not stand where its area would overlap another's, UnitAssembler.canPlaceOn).
     */
    public final List<int[]> clearLocal = new ArrayList<>();
    public boolean exclusive;
    /** Where those areas are now, on the map. */
    public final List<int[]> clearWorld = new ArrayList<>();
    /** Single tiles it keeps clear of solid blocks, on the map (a fixed body: a drill's beams). */
    public final List<Integer> clearTiles = new ArrayList<>();
    /**
     * Ground it holds whole, in its own frame (a module's square): no block of another body stands on it and no line
     * goes through it, as what stood in a module's gaps would meet the module's own lines unmeant. And where it is now.
     */
    public final List<int[]> holdLocal = new ArrayList<>(), holdWorld = new ArrayList<>();
    public final boolean fixed;
    /** A block the problem gives standing (the map's core, a source): there already, so not written out again. */
    public boolean given;
    public int ox, oy, rot;

    public Body(int id, String label, boolean fixed){
        this.id = id;
        this.label = label;
        this.fixed = fixed;
    }

    /** A part standing for a piece of a line, as it stands now on the map (turned with the body; of no line there). */
    public static Board.Piece fixed(Part p){
        Board.Piece q = p.piece;
        int turn = p.body.rot;
        Board.Piece f = new Board.Piece(q.kind, p.block, p.ax(), p.ay(), p.rot, -1 - p.body.id, q.res);
        f.from = q.from < 0 ? -1 : (q.from + turn) % 4;
        int outs = 0;
        for(int d = 0; d < 4; d++) if((q.outs & (1 << d)) != 0) outs |= 1 << ((d + turn) % 4);
        f.outs = outs;
        f.role = q.role;
        f.fixed = true;
        return f;
    }

    public Part add(Block block, int lx, int ly, int lrot, Recipe recipe, float work, String config){
        Part p = new Part(this, block, lx, ly, lrot, recipe, work, config);
        parts.add(p);
        return p;
    }

    /** The main part: the first. */
    public Part main(){
        return parts.get(0);
    }

    /** Puts it at (ox, oy) turned rot quarter turns, working out where every part is. */
    public void put(int ox, int oy, int rot){
        this.ox = ox;
        this.oy = oy;
        this.rot = rot;
        for(Part p : parts){
            int x0 = p.lx, y0 = p.ly;
            for(int r = 0; r < rot; r++){
                //(a quarter turn anticlockwise: (x, y) to (-y, x); the lowest corner of the turned square)
                int nx = -(y0 + p.size - 1), ny = x0;
                x0 = nx;
                y0 = ny;
            }
            p.x0 = ox + x0;
            p.y0 = oy + y0;
            p.rot = (p.lrot + rot) % 4;
        }
        turned(clearLocal, clearWorld, ox, oy, rot);
        turned(holdLocal, holdWorld, ox, oy, rot);
    }

    static void turned(List<int[]> local, List<int[]> world, int ox, int oy, int rot){
        world.clear();
        for(int[] r : local){
            int x0 = r[0], y0 = r[1], x1 = r[2], y1 = r[3];
            for(int k = 0; k < rot; k++){
                int nx0 = -y1, nx1 = -y0, ny0 = x0, ny1 = x1;
                x0 = nx0;
                x1 = nx1;
                y0 = ny0;
                y1 = ny1;
            }
            world.add(new int[]{ox + x0, oy + y0, ox + x1, oy + y1});
        }
    }

    /** Its extent in its own frame turned by rot: {minx, miny, maxx, maxy}. */
    public int[] extent(int rot){
        int ox0 = ox, oy0 = oy, r0 = this.rot;
        put(0, 0, rot);
        int[] e = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for(Part p : parts){
            e[0] = Math.min(e[0], p.x0);
            e[1] = Math.min(e[1], p.y0);
            e[2] = Math.max(e[2], p.x1());
            e[3] = Math.max(e[3], p.y1());
        }
        List<int[]> areas = new ArrayList<>(clearWorld);
        areas.addAll(holdWorld);
        for(int[] r : areas){
            e[0] = Math.min(e[0], r[0]);
            e[1] = Math.min(e[1], r[1]);
            e[2] = Math.max(e[2], r[2]);
            e[3] = Math.max(e[3], r[3]);
        }
        put(ox0, oy0, r0);
        return e;
    }

    public int tiles(){
        int t = 0;
        for(Part p : parts) t += p.size * p.size;
        return t;
    }

    @Override
    public String toString(){
        return label + "#" + id;
    }
}
