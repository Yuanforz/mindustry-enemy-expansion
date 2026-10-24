package bp;

import bp.Board.*;
import mindustry.*;
import mindustry.world.*;

import java.util.*;

/**
 * Rows of pumps: machines on a liquid side by side along its edge, each with its one face on land toward a strip of
 * three lines of carriers, woven as people build them (a reinforced pump takes hydrogen and gives what it pumps, both
 * through that face). Columns of the strip run from the row's entry end:
 * <pre>
 *   line 3   outlet <- <- <- <- <- <- <-        the collector, toward the entry end
 *   line 2   intake -> J  R  J  R  J  R        the distributor: a junction (J) where a machine's output crosses it, a
 *   line 1      .     ^  v  ^  v  ^  v           liquid router (R) above a machine's intake
 *               .   [pump][pump][pump]           line 1: each machine's two stubs, out (^) and in (v)
 * </pre>
 * What comes into the intake goes on through the junctions and routers, each router handing it down its stub into the
 * machine below (LiquidRouter: to all about it that take; the collector's reinforced conduits take nothing from its
 * side, ArmoredConduit; a junction hands it on straight, LiquidJunction). What a machine pumps goes up its out stub,
 * straight through the junction above (to the collector, a conduit that takes from a junction) and along the collector
 * to the outlet. So a row of k pumps needs two lines from outside, not 2k, and none of them on the pumps' faces.
 *
 * The strip is built with the row's first pump (lines 2 and 3, both ports); each pump with its own two stubs. The column
 * past the far end, and line 1 of the entry column, are kept empty (a router at the end would hand to what stood
 * there). Only the nearest end to the core is an entry. Rows do not overlap, nor their strips.
 */
public final class Strips{
    public static final boolean ON = Boolean.parseBoolean(System.getProperty("bp.strips", "true"));
    /** The most machines in a row. */
    public static final int MOST = Integer.getInteger("bp.rowMost", 5);

    /** A row: its recipe, places, the way from the machines to the strip, the strip's frame. */
    public static final class Row{
        public final Recipe recipe;
        /** Lowest corners of its places, from the entry end. */
        public final List<int[]> at = new ArrayList<>();
        /** The way from the machines to the strip (0 +x, 1 +y, 2 -x, 3 -y), and the way along the strip from the entry. */
        public int dir, along;
        /** The tile of column 0, line 1 (the first face tile of the first machine, one out). */
        public int fx, fy;
        /** What the machines take through the intake (null: nothing) and give out of the outlet. */
        public String in, out;
        public int id;

        Row(Recipe recipe){
            this.recipe = recipe;
        }

        public int k(){
            return at.size();
        }

        int s(){
            return recipe.size;
        }

        /** The tile at a column (from the entry end; -1 the entry column) and a line (1..3). */
        public int[] tile(int c, int line){
            return new int[]{fx + Geo.dx(along) * c + Geo.dx(dir) * (line - 1), fy + Geo.dy(along) * c + Geo.dy(dir) * (line - 1)};
        }

        /** The columns of the strip: the entry column, the machines' columns, the empty one past the end. */
        public int columns(){
            return k() * s() + 2;
        }

        /** Every tile of the strip, by map index. */
        public List<Integer> tiles(Board b){
            List<Integer> out = new ArrayList<>();
            for(int c = -1; c <= k() * s(); c++) for(int l = 1; l <= 3; l++){
                int[] t = tile(c, l);
                out.add(b.idx(t[0], t[1]));
            }
            return out;
        }

        /** A place's share of the strip: its columns, the entry column with the first, the end one with the last. */
        public List<Integer> share(Board b, int i){
            List<Integer> out = new ArrayList<>();
            int c0 = i * s() - (i == 0 ? 1 : 0), c1 = (i + 1) * s() - 1 + (i == k() - 1 ? 1 : 0);
            for(int c = c0; c <= c1; c++) for(int l = 1; l <= 3; l++){
                int[] t = tile(c, l);
                out.add(b.idx(t[0], t[1]));
            }
            return out;
        }

        /** The tiles of the strip itself (lines 2 and 3, the ports), not the stubs nor the empty ones kept. */
        public List<Integer> infraTiles(Board b){
            List<Integer> out = new ArrayList<>();
            for(int c = -1; c < k() * s(); c++) for(int l = 2; l <= 3; l++){
                int[] t = tile(c, l);
                out.add(b.idx(t[0], t[1]));
            }
            return out;
        }

        /** Which of its places is at a lowest corner (-1 none). */
        public int index(int x0, int y0){
            for(int i = 0; i < at.size(); i++) if(at.get(i)[0] == x0 && at.get(i)[1] == y0) return i;
            return -1;
        }

        /** The column of a machine's out stub and of its in stub (the first two of its face). */
        int outCol(int i){
            return i * s();
        }

        int inCol(int i){
            return i * s() + 1;
        }

        /**
         * The strip without stubs, as pieces: {kind, x, y, turn, from, outs, thing}; the intake (line 2 of the entry
         * column) and the outlet (line 3 of it) first, as ports.
         */
        public List<Object[]> infra(){
            List<Object[]> ps = new ArrayList<>();
            int back = (along + 2) % 4, up = dir, down = (dir + 2) % 4;
            //({kind, x, y, turn, way it is fed (the way things travel into it), ways it hands to (bits), thing})
            int[] t = tile(-1, 2);
            if(in != null) ps.add(new Object[]{Kind.conduit, t[0], t[1], along, along, 1 << along, in});
            t = tile(-1, 3);
            ps.add(new Object[]{Kind.conduit, t[0], t[1], back, back, 1 << back, out});
            for(int c = 0; c < k() * s(); c++){
                int j = c % s();
                t = tile(c, 2);
                if(j == 0) ps.add(new Object[]{Kind.ljunction, t[0], t[1], 0, -1, (1 << along) | (1 << up), in == null ? out : in});
                else if(j == 1 && in != null) ps.add(new Object[]{Kind.lrouter, t[0], t[1], along, along, (1 << along) | (1 << down), in});
                else if(in != null) ps.add(new Object[]{Kind.conduit, t[0], t[1], along, along, 1 << along, in});
                t = tile(c, 3);
                ps.add(new Object[]{Kind.conduit, t[0], t[1], back, back, 1 << back, out});
            }
            return ps;
        }

        /** A machine's two stubs: out (away from it, into the junction) and in (from the router, into it). */
        public List<Object[]> stubs(int i){
            List<Object[]> ps = new ArrayList<>();
            int up = dir, down = (dir + 2) % 4;
            int[] t = tile(outCol(i), 1);
            ps.add(new Object[]{Kind.conduit, t[0], t[1], up, up, 1 << up, out});
            if(in != null){
                t = tile(inCol(i), 1);
                ps.add(new Object[]{Kind.conduit, t[0], t[1], down, down, 1 << down, in});
            }
            return ps;
        }

        @Override
        public String toString(){
            return String.format("row %d of %d %s from %d,%d way %d", id, k(), recipe.name, at.get(0)[0], at.get(0)[1], dir);
        }
    }

    /** Whether a recipe stands in rows: a machine on a liquid giving one liquid, taking at most one through the same face. */
    public static boolean rowed(Recipe r){
        if(!ON || r.ground != Recipe.Ground.liquidFloor || r.size < 2) return false;
        return given(r) != null && taken(r).size() <= 1;
    }

    static String given(Recipe r){
        for(String k : r.perYield.keySet()) if(Vars.content.liquid(k) != null) return k;
        for(String k : r.out.keySet()) if(Vars.content.liquid(k) != null) return k;
        return null;
    }

    static List<String> taken(Recipe r){
        List<String> out = new ArrayList<>();
        for(String k : r.in.keySet()) if(Vars.content.liquid(k) != null) out.add(k);
        for(String k : r.fixedIn.keySet()) if(Vars.content.liquid(k) != null && !out.contains(k)) out.add(k);
        for(String k : r.in.keySet()) if(Vars.content.item(k) != null) out.add(k);
        return out;
    }

    /** Land a strip may lie on: a carrier stands there, no liquid under it (the liquid is for the machines). */
    static boolean land(Board b, int x, int y){
        return b.in(x, y) && b.floor(x, y) && b.floorAt[b.idx(x, y)].liquidDrop == null && !b.solidGround[b.idx(x, y)];
    }

    static final Map<Board, Map<Recipe, List<Row>>> cache = new IdentityHashMap<>();

    /**
     * The rows of a recipe on a board: of its full places with a face all on land and three lines of land beyond it,
     * those side by side along one edge, cut into rows of at most MOST; kept nearest the core first, none over another's
     * places or strip.
     */
    public static synchronized List<Row> rows(Board b, Recipe r, int cx, int cy){
        Map<Recipe, List<Row>> byRecipe = cache.computeIfAbsent(b, k -> new IdentityHashMap<>());
        List<Row> got = byRecipe.get(r);
        if(got != null) return got;
        List<Row> out = new ArrayList<>();
        byRecipe.put(r, out);
        if(!rowed(r)) return out;
        int s = r.size;
        Sites sites = new Sites(b);
        //(each full place, by lowest corner)
        boolean[] full = new boolean[b.w * b.h];
        for(int y0 = 0; y0 + s <= b.h; y0++) for(int x0 = 0; x0 + s <= b.w; x0++){
            if(!b.canStand(r.block, x0, y0, 0)) continue;
            if(sites.at(r, x0, y0, 0) != null) full[b.idx(x0, y0)] = true;
        }
        List<Row> cand = new ArrayList<>();
        for(int dir = 0; dir < 4; dir++){
            //(the way along the face, the axis a quarter turn from the way out, positive)
            int along = (dir & 1) == 0 ? 1 : 0;
            int ax = Geo.dx(along), ay = Geo.dy(along);
            //(places whose face that way is open: lines 1..3 beyond it on land, the columns of the face)
            boolean[] open = new boolean[b.w * b.h];
            for(int y0 = 0; y0 + s <= b.h; y0++) for(int x0 = 0; x0 + s <= b.w; x0++){
                if(!full[b.idx(x0, y0)]) continue;
                boolean ok = true;
                for(int c = 0; c < s && ok; c++) for(int l = 1; l <= 3 && ok; l++){
                    int[] t = faceTile(x0, y0, s, dir, ax, ay, c, l);
                    if(!land(b, t[0], t[1])) ok = false;
                }
                open[b.idx(x0, y0)] = ok;
            }
            //(runs of them side by side along the face, s apart; each cut into rows of at most MOST)
            boolean[] used = new boolean[b.w * b.h];
            for(int y0 = 0; y0 + s <= b.h; y0++) for(int x0 = 0; x0 + s <= b.w; x0++){
                int i0 = b.idx(x0, y0);
                if(!open[i0] || used[i0]) continue;
                //(a run starts where the place before it, s back along, is not open)
                int px = x0 - ax * s, py = y0 - ay * s;
                if(b.in(px, py) && open[b.idx(px, py)]) continue;
                List<int[]> run = new ArrayList<>();
                int x = x0, y = y0;
                while(b.in(x, y) && x + s <= b.w && y + s <= b.h && open[b.idx(x, y)]){
                    run.add(new int[]{x, y});
                    used[b.idx(x, y)] = true;
                    x += ax * s;
                    y += ay * s;
                }
                for(int from = 0; from < run.size(); from += MOST){
                    List<int[]> part = run.subList(from, Math.min(run.size(), from + MOST));
                    for(boolean flip : new boolean[]{false, true}){
                        Row row = new Row(r);
                        row.dir = dir;
                        row.in = taken(r).isEmpty() ? null : taken(r).get(0);
                        row.out = given(r);
                        List<int[]> ps = new ArrayList<>(part);
                        //(the entry at the low end, or turned about at the high end)
                        if(flip) Collections.reverse(ps);
                        row.at.addAll(ps);
                        row.along = flip ? (along + 2) % 4 : along;
                        int[] f0 = ps.get(0);
                        int c0 = flip ? s - 1 : 0;
                        int[] t = faceTile(f0[0], f0[1], s, dir, ax, ay, c0, 1);
                        row.fx = t[0];
                        row.fy = t[1];
                        cand.add(row);
                    }
                }
            }
        }
        //(the strip on land, its end columns too, and the two tiles the lines from outside meet its ports on; kept
        //nearest the core first, entry nearest; none over another, nor over another's meeting tiles)
        cand.removeIf(row -> {
            for(int c = -1; c <= row.k() * row.s(); c++) for(int l = 1; l <= 3; l++){
                int[] t = row.tile(c, l);
                if(!land(b, t[0], t[1])) return true;
            }
            for(int l = 2; l <= 3; l++){
                int[] t = row.tile(-2, l);
                if(!land(b, t[0], t[1])) return true;
            }
            return false;
        });
        cand.sort(Comparator.comparingDouble(row -> {
            int[] e = row.tile(-1, 2);
            return Math.abs(e[0] - cx) + Math.abs(e[1] - cy) - 3.0 * row.k();
        }));
        Set<Integer> taken = new HashSet<>();
        for(Row row : cand){
            //(trimmed from its far end until it lies clear of the rows kept)
            while(row.k() > 0 && clash(b, row, taken)) row.at.remove(row.k() - 1);
            if(row.k() == 0) continue;
            row.id = out.size();
            out.add(row);
            taken.addAll(row.tiles(b));
            for(int l = 2; l <= 3; l++){
                int[] t = row.tile(-2, l);
                taken.add(b.idx(t[0], t[1]));
            }
            for(int[] p : row.at) for(int y = p[1]; y < p[1] + s; y++) for(int x = p[0]; x < p[0] + s; x++) taken.add(b.idx(x, y));
        }
        return out;
    }

    static boolean clash(Board b, Row row, Set<Integer> taken){
        for(int t : row.tiles(b)) if(taken.contains(t)) return true;
        for(int l = 2; l <= 3; l++){
            int[] t = row.tile(-2, l);
            if(taken.contains(b.idx(t[0], t[1]))) return true;
        }
        int s = row.s();
        for(int[] p : row.at) for(int y = p[1]; y < p[1] + s; y++) for(int x = p[0]; x < p[0] + s; x++) if(taken.contains(b.idx(x, y))) return true;
        return false;
    }

    /** The tile c along the face (from the low corner) and l out from it, of a place of size s facing dir. */
    static int[] faceTile(int x0, int y0, int s, int dir, int ax, int ay, int c, int l){
        return switch(dir){
            case 0 -> new int[]{x0 + s - 1 + l, y0 + c};
            case 1 -> new int[]{x0 + c, y0 + s - 1 + l};
            case 2 -> new int[]{x0 - l, y0 + c};
            default -> new int[]{x0 + c, y0 - l};
        };
    }

    /** The row a place of a recipe is in, and which of it: {row, index}, or null. */
    public static Object[] of(Board b, Recipe r, int x0, int y0, int cx, int cy){
        for(Row row : rows(b, r, cx, cy)){
            int i = row.index(x0, y0);
            if(i >= 0) return new Object[]{row, i};
        }
        return null;
    }
}
