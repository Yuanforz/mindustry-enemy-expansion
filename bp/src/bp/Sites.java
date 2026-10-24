package bp;

import arc.math.geom.*;
import mindustry.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.production.*;
import mindustry.world.meta.*;

import java.util.*;

/**
 * Where machines that stand on ground can stand on a map at full yield, by the game's own rules on the bare map (the
 * Board's world): a beam drill with every lane meeting the ore it digs first (BeamDrill.updateFacing: the first solid
 * tile of each lane; another ore on any lane, and it digs nothing), the lane's tiles kept free of solid blocks; a drill
 * with every tile under it the ore it digs (Drill.countOre); a wall crafter facing walls that give on every tile of its
 * face (WallCrafter.getEfficiency); a pump on the liquid on every tile; a machine on vents with every tile on one
 * (Block.sumAttribute). Only full ones (the user's rule): a place that gives less wastes the machine.
 */
public final class Sites{
    /** A place: the recipe, its lowest corner and turn, its yield, the tiles it stands on and those its beams pass over. */
    public record Site(Recipe recipe, int x0, int y0, int rot, float yield, int[] body, int[] lane){
    }

    final Board b;

    public Sites(Board b){
        this.b = b;
    }

    /** Every full place of each recipe on ground, the nearest so many to a point kept for each (the rest are no better). */
    public Map<Recipe, List<Site>> find(List<Recipe> recipes, int cx, int cy, int keep){
        Map<Recipe, List<Site>> out = new LinkedHashMap<>();
        for(Recipe r : recipes){
            if(r.ground == Recipe.Ground.none) continue;
            List<Site> all = new ArrayList<>();
            Block bl = r.block;
            int s = bl.size;
            //(a pump only in a row along its liquid's edge, its share of the row's strip kept as its lane: Strips)
            if(Strips.rowed(r)){
                for(Strips.Row row : Strips.rows(b, r, cx, cy)) for(int i = 0; i < row.k(); i++){
                    int[] p = row.at.get(i);
                    Site site = at(r, p[0], p[1], 0);
                    if(site == null) continue;
                    all.add(new Site(r, p[0], p[1], 0, site.yield(), site.body(), row.share(b, i).stream().mapToInt(Integer::intValue).toArray()));
                }
                all.sort(Comparator.comparingDouble(st -> Math.abs(st.x0 + s / 2f - cx) + Math.abs(st.y0 + s / 2f - cy)));
                out.put(r, new ArrayList<>(all.subList(0, Math.min(keep, all.size()))));
                continue;
            }
            boolean turns = bl instanceof BeamDrill || bl instanceof WallCrafter;
            for(int rot = 0; rot < (turns ? 4 : 1); rot++){
                for(int y0 = 0; y0 + s <= b.h; y0++) for(int x0 = 0; x0 + s <= b.w; x0++){
                    if(!b.canStand(bl, x0, y0, rot)) continue;
                    Site site = at(r, x0, y0, rot);
                    if(site != null) all.add(site);
                }
            }
            all.sort(Comparator.comparingDouble(st -> Math.abs(st.x0 + s / 2f - cx) + Math.abs(st.y0 + s / 2f - cy)));
            out.put(r, new ArrayList<>(all.subList(0, Math.min(keep, all.size()))));
        }
        return out;
    }

    /**
     * Of each recipe, the nearest places at full yield that can all stand at once, so many: none on another's body or on
     * the tiles its beams pass over (the nearest are often one place turned or shifted a tile, of which one stands).
     */
    public Map<Recipe, List<Site>> findApart(List<Recipe> recipes, int cx, int cy, int keep){
        return findApart(recipes, cx, cy, keep, Map.of());
    }

    /** The same, of each recipe none on the tiles given for it (kept for others: the plan's places of other recipes). */
    public Map<Recipe, List<Site>> findApart(List<Recipe> recipes, int cx, int cy, int keep, Map<Recipe, Set<Integer>> pre){
        Map<Recipe, List<Site>> all = find(recipes, cx, cy, Integer.MAX_VALUE), out = new LinkedHashMap<>();
        for(var e : all.entrySet()){
            List<Site> kept = new ArrayList<>();
            Set<Integer> taken = new HashSet<>(pre.getOrDefault(e.getKey(), Set.of()));
            boolean rowed = Strips.rowed(e.getKey());
            for(Site st : e.getValue()){
                if(kept.size() >= keep) break;
                boolean clash = false;
                for(int t : st.body()) if(taken.contains(t)) clash = true;
                for(int t : st.lane()) if(taken.contains(t)) clash = true;
                if(clash) continue;
                //(a whole face of it kept free for its lines, none of the others on it: else places side by side and back
                //to back wall one another in, a drill among them with no tile to hand out of; a pump's is its strip's)
                int[] face = rowed || !FACE ? new int[0] : face(st, taken, cx, cy);
                if(face == null) continue;
                if(face.length > 0){
                    int[] lane = Arrays.copyOf(st.lane(), st.lane().length + face.length);
                    System.arraycopy(face, 0, lane, st.lane().length, face.length);
                    st = new Site(st.recipe(), st.x0(), st.y0(), st.rot(), st.yield(), st.body(), lane);
                }
                kept.add(st);
                for(int t : st.body()) taken.add(t);
                for(int t : st.lane()) taken.add(t);
            }
            out.put(e.getKey(), kept);
        }
        return out;
    }

    /** Whether a place keeps a free face (bp.siteFace). */
    static final boolean FACE = Boolean.parseBoolean(System.getProperty("bp.siteFace", "true"));

    /**
     * A face of a place all on ground a carrier stands on and none of it taken, the one nearest the core; not the face a
     * drill or a crusher works toward. Null if it has none.
     */
    int[] face(Site st, Set<Integer> taken, int cx, int cy){
        int s = st.recipe().size, x0 = st.x0(), y0 = st.y0();
        boolean turned = st.recipe().block instanceof BeamDrill || st.recipe().block instanceof WallCrafter;
        int[] best = null;
        int bestD = Integer.MAX_VALUE;
        for(int d = 0; d < 4; d++){
            if(turned && d == st.rot()) continue;
            int[] f = new int[s];
            boolean ok = true;
            for(int i = 0; i < s && ok; i++){
                int x = d == 0 ? x0 + s : d == 2 ? x0 - 1 : x0 + i, y = d == 1 ? y0 + s : d == 3 ? y0 - 1 : y0 + i;
                if(!b.in(x, y) || !b.floor(x, y) || taken.contains(b.idx(x, y))){
                    ok = false;
                    break;
                }
                f[i] = b.idx(x, y);
            }
            if(!ok) continue;
            int mx = d == 0 ? x0 + s : d == 2 ? x0 - 1 : x0 + s / 2, my = d == 1 ? y0 + s : d == 3 ? y0 - 1 : y0 + s / 2;
            int dist = Math.abs(mx - cx) + Math.abs(my - cy);
            if(dist < bestD){
                bestD = dist;
                best = f;
            }
        }
        return best;
    }

    /** The place of a recipe here, if it is at full yield. */
    Site at(Recipe r, int x0, int y0, int rot){
        Block bl = r.block;
        int s = bl.size, ax = Geo.at(x0, s), ay = Geo.at(y0, s);
        int[] body = new int[s * s];
        int k = 0;
        for(int y = y0; y < y0 + s; y++) for(int x = x0; x < x0 + s; x++) body[k++] = b.idx(x, y);
        switch(r.ground){
            case wallOre -> {
                if(!(bl instanceof BeamDrill bd)) return null;
                Item item = Vars.content.item(r.dug);
                List<Integer> lane = new ArrayList<>();
                Point2 p = new Point2();
                int dx = Geo.dx(rot), dy = Geo.dy(rot);
                for(int i = 0; i < s; i++){
                    bl.nearbySide(ax, ay, rot, i, p);
                    boolean hit = false;
                    for(int j = 0; j < bd.range; j++){
                        Tile t = Vars.world.tile(p.x + dx * j, p.y + dy * j);
                        if(t == null) break;
                        if(t.solid()){
                            hit = t.wallDrop() == item;
                            break;
                        }
                        lane.add(b.idx(t.x, t.y));
                    }
                    if(!hit) return null;
                }
                return new Site(r, x0, y0, rot, s, body, lane.stream().mapToInt(Integer::intValue).toArray());
            }
            case floorOre -> {
                if(!(bl instanceof Drill d)) return null;
                Item item = Vars.content.item(r.dug);
                for(int y = y0; y < y0 + s; y++) for(int x = x0; x < x0 + s; x++){
                    Tile t = Vars.world.tile(x, y);
                    if(t == null || d.getDrop(t) != item || !d.canMine(t)) return null;
                }
                return new Site(r, x0, y0, rot, s * s, body, new int[0]);
            }
            case sandWall -> {
                if(!(bl instanceof WallCrafter w)) return null;
                float eff = 0f;
                int[][] face = new int[s][];
                for(int i = 0; i < s; i++){
                    int fx, fy;
                    switch(rot){
                        case 0 -> {
                            fx = x0 + s;
                            fy = y0 + i;
                        }
                        case 1 -> {
                            fx = x0 + i;
                            fy = y0 + s;
                        }
                        case 2 -> {
                            fx = x0 - 1;
                            fy = y0 + i;
                        }
                        default -> {
                            fx = x0 + i;
                            fy = y0 - 1;
                        }
                    }
                    Tile t = Vars.world.tile(fx, fy);
                    if(t == null || !t.solid()) return null;
                    float at = t.block().attributes.get(w.attribute);
                    if(at <= 0f) return null;
                    eff += at;
                }
                return new Site(r, x0, y0, rot, eff, body, new int[0]);
            }
            case liquidFloor -> {
                Liquid l = Vars.content.liquid(r.dug);
                float y = 0f;
                for(int yy = y0; yy < y0 + s; yy++) for(int x = x0; x < x0 + s; x++){
                    Tile t = Vars.world.tile(x, yy);
                    if(t == null || t.floor().liquidDrop != l) return null;
                    y += t.floor().liquidMultiplier;
                }
                return new Site(r, x0, y0, rot, y, body, new int[0]);
            }
            case vent -> {
                Attribute attr = bl instanceof AttributeCrafter a ? a.attribute : bl instanceof ThermalGenerator t ? t.attribute : null;
                if(attr == null) return null;
                for(int yy = y0; yy < y0 + s; yy++) for(int x = x0; x < x0 + s; x++){
                    Tile t = Vars.world.tile(x, yy);
                    if(t == null || t.floor().attributes.get(attr) <= 0f) return null;
                }
                return new Site(r, x0, y0, rot, bl.sumAttribute(attr, ax, ay), body, new int[0]);
            }
            default -> {
                return null;
            }
        }
    }
}
