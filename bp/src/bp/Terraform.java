package bp;

import arc.files.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.io.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.environment.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.production.*;
import mindustry.world.meta.*;

import java.util.*;

/**
 * Ground a map lacks, made where the plan wants it: for each place the plan took that the map does not have (a place of
 * a recipe on ground at full yield), the nearest open ground to the core that holds the machine and the ground it draws
 * on, painted with that ground (an ore on the floor or in a wall, a wall that gives, a liquid, vents), each kind of ground
 * the game's own block for it (by what it drops, gives or yields); checked by the game's own placing that the machine
 * then stands there at full yield.
 */
public final class Terraform{
    final List<int[]> used = new ArrayList<>();
    public final List<String> done = new ArrayList<>();

    /** Paints, for each recipe, so many places of it on the map loaded now (the game's world, with its buildings). */
    public void paint(Map<Recipe, Integer> want, int cx, int cy){
        for(var e : want.entrySet()) for(int k = 0; k < e.getValue(); k++){
            Recipe r = e.getKey();
            if(!place(r, cx, cy)) done.add("no room for " + r.name);
        }
    }

    boolean place(Recipe r, int cx, int cy){
        Block bl = r.block;
        int s = bl.size;
        boolean wall = r.ground == Recipe.Ground.wallOre || r.ground == Recipe.Ground.sandWall;
        //(the machine's square, and for one facing a wall the wall's line ahead of it (turned +x), all within a margin)
        int w = wall ? s + 1 : s, h = s;
        for(int rad = 8; rad < 120; rad += 2){
            for(int a = 0; a < 360; a += 10){
                int x0 = cx + (int)Math.round(Math.cos(Math.toRadians(a)) * rad) - w / 2, y0 = cy + (int)Math.round(Math.sin(Math.toRadians(a)) * rad) - h / 2;
                if(!open(x0 - 1, y0 - 1, w + 2, h + 2)) continue;
                if(!paintAt(r, x0, y0)) continue;
                used.add(new int[]{x0 - 1, y0 - 1, w + 2, h + 2});
                done.add(String.format("%s at %d,%d", r.name, x0, y0));
                return true;
            }
        }
        return false;
    }

    /** Open ground: nothing built, nothing solid, no liquid, no ore, no vent, not used already. */
    boolean open(int x0, int y0, int w, int h){
        for(int[] u : used) if(x0 < u[0] + u[2] && u[0] < x0 + w && y0 < u[1] + u[3] && u[1] < y0 + h) return false;
        for(int y = y0; y < y0 + h; y++) for(int x = x0; x < x0 + w; x++){
            Tile t = Vars.world.tile(x, y);
            if(t == null || t.solid() || t.build != null || t.floor().isLiquid || t.floor().isDeep() || t.overlay() != Blocks.air || t.floor().itemDrop != null) return false;
            if(t.floor().attributes.get(Attribute.steam) > 0f) return false;
        }
        return true;
    }

    /** The ground a recipe's machine draws on, painted at (x0, y0) (the machine's lowest corner, turned +x); then checked. */
    boolean paintAt(Recipe r, int x0, int y0){
        Block bl = r.block;
        int s = bl.size;
        List<Object[]> undo = new ArrayList<>();
        switch(r.ground){
            case floorOre -> {
                Item item = Vars.content.item(r.dug);
                Block ore = floorOre(item);
                if(ore == null) return false;
                for(int y = y0; y < y0 + s; y++) for(int x = x0; x < x0 + s; x++) set(Vars.world.tile(x, y), ore, undo);
            }
            case liquidFloor -> {
                Liquid l = Vars.content.liquid(r.dug);
                for(Floor f : liquidFloors(l)){
                    for(int y = y0; y < y0 + s; y++) for(int x = x0; x < x0 + s; x++) set(Vars.world.tile(x, y), f, undo);
                    if(mindustry.world.Build.validPlace(bl, Vars.state.rules.defaultTeam, Geo.at(x0, s), Geo.at(y0, s), 0, false, false)) return true;
                    restore(undo);
                }
                return false;
            }
            case vent -> {
                Attribute attr = bl instanceof AttributeCrafter a ? a.attribute : bl instanceof ThermalGenerator t ? t.attribute : null;
                Floor f = bestFloor(attr);
                if(f == null) return false;
                for(int y = y0; y < y0 + s; y++) for(int x = x0; x < x0 + s; x++) set(Vars.world.tile(x, y), f, undo);
            }
            case wallOre, sandWall -> {
                //(a wall of it along the machine's front, +x)
                Block[] wallAndOre = r.ground == Recipe.Ground.wallOre ? wallOre(Vars.content.item(r.dug)) : new Block[]{bestWall(((WallCrafter)bl).attribute), null};
                if(wallAndOre == null || wallAndOre[0] == null) return false;
                for(int y = y0; y < y0 + s; y++){
                    Tile t = Vars.world.tile(x0 + s, y);
                    undo.add(new Object[]{t, t.floor(), t.overlay(), t.block()});
                    t.setBlock(wallAndOre[0]);
                    if(wallAndOre[1] != null) t.setOverlay(wallAndOre[1]);
                }
            }
            default -> {
                return false;
            }
        }
        if(mindustry.world.Build.validPlace(bl, Vars.state.rules.defaultTeam, Geo.at(x0, s), Geo.at(y0, s), 0, false, false)) return true;
        restore(undo);
        return false;
    }

    static void set(Tile t, Block ground, List<Object[]> undo){
        undo.add(new Object[]{t, t.floor(), t.overlay(), t.block()});
        if(ground instanceof OverlayFloor || ground instanceof OreBlock) t.setOverlay(ground);
        else t.setFloor((Floor)ground);
    }

    static void restore(List<Object[]> undo){
        for(int i = undo.size() - 1; i >= 0; i--){
            Object[] u = undo.get(i);
            Tile t = (Tile)u[0];
            t.setFloor((Floor)u[1]);
            t.setOverlay((Block)u[2]);
            t.setBlock((Block)u[3]);
        }
        undo.clear();
    }

    /** The ore put on a floor for an item (an ore overlay not in walls), else a floor that drops it. */
    static Block floorOre(Item item){
        for(Block b : Vars.content.blocks()) if(b instanceof OreBlock o && !o.wallOre && o.itemDrop == item) return b;
        for(Block b : Vars.content.blocks()) if(b instanceof Floor f && !(b instanceof OverlayFloor) && !f.isLiquid && f.itemDrop == item) return b;
        return null;
    }

    /** A wall with an item in it: a wall that drops it, or a plain wall with an ore of it for walls. */
    static Block[] wallOre(Item item){
        for(Block b : Vars.content.blocks()) if(b instanceof StaticWall && b.itemDrop == item && b.isOnPlanet(Planets.erekir)) return new Block[]{b, null};
        for(Block b : Vars.content.blocks()) if(b instanceof StaticWall && b.itemDrop == item) return new Block[]{b, null};
        for(Block b : Vars.content.blocks()) if(b instanceof OreBlock o && o.wallOre && o.itemDrop == item) return new Block[]{Blocks.rhyoliteWall, b};
        return null;
    }

    /** The floors that give a liquid, the shallow first. */
    static List<Floor> liquidFloors(Liquid l){
        List<Floor> out = new ArrayList<>();
        for(Block b : Vars.content.blocks()) if(b instanceof Floor f && !(b instanceof OverlayFloor) && f.liquidDrop == l) out.add(f);
        out.sort(Comparator.comparing(Floor::isDeep));
        return out;
    }

    static Floor bestFloor(Attribute attr){
        Floor best = null;
        for(Block b : Vars.content.blocks()) if(b instanceof Floor f && !(b instanceof OverlayFloor) && !f.isLiquid && (best == null || f.attributes.get(attr) > best.attributes.get(attr))) best = f;
        return best != null && best.attributes.get(attr) > 0f ? best : null;
    }

    static Block bestWall(Attribute attr){
        Block best = null;
        for(Block b : Vars.content.blocks()) if(b instanceof StaticWall && (best == null || b.attributes.get(attr) > best.attributes.get(attr))) best = b;
        return best != null && best.attributes.get(attr) > 0f ? best : null;
    }

    /**
     * A wall ore the map's generator never puts down (Erekir's puts beryllium in walls, tungsten and thorium only on the
     * floor), put down once as the generator puts its wall ores: on walls that touch open ground, where a noise field
     * (the generator's for beryllium in walls: four octaves, 0.8, scale 38, another offset) is high; of what is high, the
     * one largest vein kept. Where it lies is the noise's, not the plan's. The field's bar lowered only until the vein
     * gives one drill of that ore its whole face (else the ore were there for nothing).
     */
    static String vein(String dataDir, String in, String out, Item item){
        Block ore = null;
        for(Block b : Vars.content.blocks()) if(b instanceof OreBlock o && o.wallOre && o.itemDrop == item) ore = b;
        if(ore == null) return "no wall ore of " + item.name;
        List<Recipe> drills = new ArrayList<>();
        for(Recipe r : Recipes.all()) if(r.ground == Recipe.Ground.wallOre && item.name.equals(r.dug)) drills.add(r);
        if(drills.isEmpty()) return "no drill digs " + item.name + " in walls";
        SaveIO.load(new Fi(in));
        int w = Vars.world.width(), h = Vars.world.height();
        //the walls that touch open ground, by the field
        List<int[]> cand = new ArrayList<>();
        for(Tile t : Vars.world.tiles){
            if(!(t.block() instanceof StaticWall) || t.overlay() != Blocks.air || t.block().itemDrop != null) continue;
            boolean open = false;
            for(int d = 0; d < 4; d++){
                Tile o = Vars.world.tile(t.x + Geo.dx(d), t.y + Geo.dy(d));
                if(o != null && !o.solid() && !o.floor().isDeep()) open = true;
            }
            if(open) cand.add(new int[]{t.x, t.y, Math.round(arc.util.noise.Simplex.noise2d(0, 4, 0.8f, 1f / 38f, t.x + 1931, t.y) * 100000)});
        }
        cand.sort((a, c) -> Integer.compare(c[2], a[2]));
        for(int take = Math.max(1, cand.size() / 50); take <= cand.size(); take += Math.max(1, cand.size() / 50)){
            //(the walls above the bar, by veins: the largest kept)
            boolean[] high = new boolean[w * h];
            for(int i = 0; i < take; i++) high[cand.get(i)[0] + cand.get(i)[1] * w] = true;
            List<Integer> best = new ArrayList<>();
            boolean[] seen = new boolean[w * h];
            for(int i = 0; i < take; i++){
                int s0 = cand.get(i)[0] + cand.get(i)[1] * w;
                if(seen[s0]) continue;
                List<Integer> comp = new ArrayList<>();
                ArrayDeque<Integer> q = new ArrayDeque<>();
                q.add(s0);
                seen[s0] = true;
                while(!q.isEmpty()){
                    int c = q.poll();
                    comp.add(c);
                    for(int dy = -1; dy <= 1; dy++) for(int dx = -1; dx <= 1; dx++){
                        int x = c % w + dx, y = c / w + dy;
                        if(x < 0 || y < 0 || x >= w || y >= h) continue;
                        int k = x + y * w;
                        if(high[k] && !seen[k]){
                            seen[k] = true;
                            q.add(k);
                        }
                    }
                }
                if(comp.size() > best.size()) best = comp;
            }
            SaveIO.load(new Fi(in));
            for(int k : best) Vars.world.tile(k % w, k / w).setOverlay(ore);
            SaveIO.save(new Fi(out));
            //(a drill of it has its whole face there?)
            Gen g = new Gen(Problem.parse("world " + out + "\n"), dataDir);
            Spec.Place core = g.prob.fixed.isEmpty() ? null : g.prob.fixed.get(0);
            int cx = core == null ? w / 2 : core.x(), cy = core == null ? h / 2 : core.y();
            for(var e : new Sites(g.board).find(drills, cx, cy, 1).entrySet()) if(!e.getValue().isEmpty()){
                Sites.Site st = e.getValue().get(0);
                return String.format("a vein of %s, %d walls about %d,%d (the field's highest %d of %d walls on open ground); a %s stands whole at %d,%d", ore.name, best.size(), best.get(0) % w, best.get(0) / w, take, cand.size(), e.getKey().name, st.x0(), st.y0());
            }
        }
        return "no vein of " + item.name + " gives a drill its whole face";
    }

    /**
     * The final form on a map, with ground the map lacks made where the plan wants it: planned once with places that are
     * not there yet allowed at a price (the cost of making them), what of them the plan took painted on the map, saved.
     *
     * Usage: bp.Terraform data-dir in.msav out.msav surplus [places kept per recipe]
     */
    public static void main(String[] args){
        if(args[0].equals("vein")){
            //Usage: bp.Terraform vein data-dir in.msav out.msav item
            Game.boot(args[1]);
            System.out.println(vein(args[1], args[2], args[3], Vars.content.item(args[4])));
            System.exit(0);
        }
        Game.boot(args[0]);
        String in = args[1], out = args[2];
        float surplus = Float.parseFloat(args[3]);
        int keep = args.length > 4 ? Integer.parseInt(args[4]) : 6;
        Balance bal = Final.onWorld(args[0], in, surplus, keep);
        //(places that are not there yet: a few of each recipe on ground, full yield, at a price well above any machine's)
        Map<String, List<Float>> full = Final.fullPlaces(bal.recipes);
        Map<String, Integer> real = new HashMap<>();
        for(var e : full.entrySet()){
            List<Float> ys = new ArrayList<>(bal.places.getOrDefault(e.getKey(), List.of()));
            List<Float> cost = new ArrayList<>();
            real.put(e.getKey(), ys.size());
            for(int i = 0; i < ys.size(); i++) cost.add(0f);
            //(one place standing for as many of it as are wanted: ground made anew)
            ys.add(e.getValue().get(0));
            cost.add(1000f);
            List<Integer> max = new ArrayList<>();
            for(int i = 0; i < ys.size() - 1; i++) max.add(1);
            max.add(64);
            bal.places.put(e.getKey(), ys);
            bal.placeCost.put(e.getKey(), cost);
            bal.placeMax.put(e.getKey(), max);
        }
        Balance.Result r = bal.solve();
        System.out.println("planned with ground to be made: " + r.status + (r.unmet.isEmpty() ? "" : ", short: " + Recipe.fmt(r.unmet)));
        Map<Recipe, Integer> want = new LinkedHashMap<>();
        for(Object[] t : r.placesTaken){
            String name = (String)t[0];
            int i = (Integer)t[1];
            if(i < real.getOrDefault(name, 0)) continue;
            for(Recipe x : bal.recipes) if(x.name.equals(name)) want.merge(x, (Integer)t[2], Integer::sum);
        }
        System.out.println("ground to make: " + want.entrySet().stream().map(e -> e.getKey().name + " x" + e.getValue()).toList());
        //(painted on the map as saved, buildings and all)
        SaveIO.load(new Fi(in));
        int cx = Vars.world.width() / 2, cy = Vars.world.height() / 2;
        for(Tile tt : Vars.world.tiles) if(tt.build instanceof mindustry.world.blocks.storage.CoreBlock.CoreBuild c && c.team == Vars.state.rules.defaultTeam && tt.isCenter()){
            cx = tt.x;
            cy = tt.y;
        }
        Terraform t = new Terraform();
        t.paint(want, cx, cy);
        for(String d : t.done) System.out.println("  " + d);
        SaveIO.save(new Fi(out));
        System.out.println("saved " + out);
        System.exit(0);
    }
}
