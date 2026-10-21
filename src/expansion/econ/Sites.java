package expansion.econ;

import arc.math.geom.*;
import arc.struct.*;
import mindustry.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.storage.*;
import mindustry.world.blocks.units.*;
import mindustry.world.meta.*;

import java.util.*;

/**
 * Reads a map into a {@link Field}: every place within reach of the core where a machine that works the ground could
 * stand, with what it gives there worked out the way the game's blocks work it out, and the machines already standing.
 */
public final class Sites{
    private Sites(){}

    /** At most this many places are kept for each kind of ground and item, the best first. */
    public static int keep = 40;

    static final boolean DEBUG = Boolean.getBoolean("xp.sitelog");
    static final int[] dbg = new int[4];

    public static Field scan(Catalog cat, Team team, int radius){
        Field field = new Field(cat);
        CoreBlock.CoreBuild core = team.core();
        field.coreX = core.tileX();
        field.coreY = core.tileY();
        for(Item item : Vars.content.items()) field.stock[cat.id(item.name)] = core.items.get(item);

        //walls the core unit can dig, nearest first
        UnitType unit = ((CoreBlock)core.block).unitType;
        for(Tile tile : Vars.world.tiles){
            Item drop = tile.wallDrop();
            if(drop == null || drop.hardness > unit.mineTier) continue;
            int id = cat.id(drop.name);
            float d = dst(field, tile.x, tile.y);
            if(d < field.handDistance[id]) field.handDistance[id] = d;
        }

        //every place for every kind of ground machine, then the best that do not overlap, across all kinds.
        //ground not yet seen counts: the core unit flies fast enough to have a look before it builds
        boolean fog = Vars.state.rules.fog;
        Vars.state.rules.fog = false;
        List<Field.Site> all = new ArrayList<>();
        Set<String> done = new HashSet<>();
        for(Recipe r : cat.recipes){
            if(r.ground == Recipe.Ground.none || !done.add(r.block)) continue;
            Block block = Vars.content.block(r.block);
            for(int x = field.coreX - radius; x <= field.coreX + radius; x++){
                for(int y = field.coreY - radius; y <= field.coreY + radius; y++){
                    Tile tile = Vars.world.tile(x, y);
                    if(tile == null || dst(field, x, y) > radius) continue;
                    for(int rot = 0; rot < (block.rotate ? 4 : 1); rot++){
                        boolean liq = DEBUG && r.ground == Recipe.Ground.liquidFloor && tile.floor().liquidDrop != null;
                        if(liq) dbg[0]++;
                        if(!Build.validPlace(block, team, x, y, rot) || occupied(block, x, y)) continue;
                        if(liq) dbg[1]++;
                        Field.Site site = site(cat, field, r.ground, block, x, y, rot);
                        if(site != null) site.block = r.block;
                        if(site != null) all.add(site);
                        if(liq && site != null) dbg[2]++;
                    }
                }
            }
        }
        if(DEBUG){
            Map<String, Integer> before = new TreeMap<>();
            for(Field.Site st : all) before.merge(st.ground + "/" + (st.item >= 0 ? cat.get(st.item).name : "-"), 1, Integer::sum);
            System.out.println("  sites before reach/danger: " + before);
        }
        //only places a line can get to from the core: over ground that can be built on, or by bridge over up to three
        //tiles of anything else
        boolean[] reach = reach(field, team), shot = danger(team);
        int w = Vars.world.width();
        String around = System.getProperty("xp.sitearound");
        if(around != null){
            int ax = Integer.parseInt(around.split(",")[0]), ay = Integer.parseInt(around.split(",")[1]);
            System.out.println("  around " + around + " (. reached, o open but not reached, # rock, letter: ore in rock, ~ deep, x shot at, - not to build on):");
            for(int y = ay + 15; y >= ay - 15; y--){
                StringBuilder row = new StringBuilder("    ");
                for(int x = ax - 25; x <= ax + 25; x++){
                    Tile t = Vars.world.tile(x, y);
                    if(t == null){ row.append(' '); continue; }
                    int q = x + y * w;
                    char c;
                    if(shot[q]) c = 'x';
                    else if(t.block().isStatic()) c = t.wallDrop() != null ? t.wallDrop().name.charAt(0) : '#';
                    else if(t.floor().isDeep()) c = '~';
                    else if(reach[q]) c = '.';
                    else if(t.floor().placeableOn && (t.block() == mindustry.content.Blocks.air || t.block().alwaysReplace || t.build != null)) c = 'o';
                    else c = '-';
                    if(x == ax && y == ay) c = '@';
                    row.append(c);
                }
                System.out.println(row);
            }
        }
        if(DEBUG){
            //for each kind, one place left out and why
            Set<String> told = new HashSet<>();
            for(Field.Site site : all){
                String kind = site.ground + "/" + (site.item >= 0 ? cat.get(site.item).name : "-");
                boolean hit = false, near = false;
                for(int t : site.tiles) if(shot[t]) hit = true;
                for(int t : site.tiles){
                    int x = t % w, y = t / w;
                    for(int d = 0; d < 4; d++){
                        int nx = x + arc.math.geom.Geometry.d4x(d), ny = y + arc.math.geom.Geometry.d4y(d);
                        if(Vars.world.tile(nx, ny) != null && reach[nx + ny * w]) near = true;
                    }
                }
                if((hit || !near) && told.add(kind)) System.out.println("  left out: " + kind + " at " + site.x + "," + site.y + (hit ? " (shot at)" : " (no way there for a line)"));
            }
        }
        all.removeIf(site -> {
            for(int t : site.tiles) if(shot[t]) return true;
            for(int t : site.tiles){
                int x = t % w, y = t / w;
                for(int d = 0; d < 4; d++){
                    int nx = x + arc.math.geom.Geometry.d4x(d), ny = y + arc.math.geom.Geometry.d4y(d);
                    if(Vars.world.tile(nx, ny) != null && reach[nx + ny * w]) return false;
                }
            }
            return true;
        });
        if(DEBUG){
            Map<String, Integer> before = new TreeMap<>();
            for(Field.Site st : all) before.merge(st.ground + "/" + (st.item >= 0 ? cat.get(st.item).name : "-"), 1, Integer::sum);
            System.out.println("  sites after reach/danger: " + before);
            int left = 0;
            for(Field.Site st : all) if(st.ground == Recipe.Ground.liquidFloor) left++;
            System.out.println("  sites: liquid floor tiles " + dbg[0] + ", placeable " + dbg[1] + ", sites " + dbg[2] + ", left after reach/danger " + left);
        }
        Vars.state.rules.fog = fog;
        //what the side has seen so far: the game works its fog out on a thread of its own, which this makes it do now
        if(fog && Vars.state.rules.staticFog){
            try{
                Vars.fogControl.update();
            }catch(Throwable ignored){
            }
            for(Field.Site s : all){
                for(int t : s.tiles) if(!Vars.fogControl.isDiscovered(team, t % Vars.world.width(), t / Vars.world.width())){ s.seen = false; break; }
            }
        }
        //what the map's own drills reach across stays clear
        IntSet lit = new IntSet();
        for(Building build : team.data().buildings){
            if(build instanceof BeamDrill.BeamDrillBuild && build.tile == Vars.world.tile(build.tileX(), build.tileY())){
                Field.Site s = site(cat, field, Recipe.Ground.wallOre, build.block, build.tileX(), build.tileY(), build.rotation);
                if(s != null) for(int t : s.lane) lit.add(t);
            }
        }
        all.removeIf(s -> { for(int t : s.tiles) if(lit.contains(t)) return true; return false; });
        //the best set of places that are in no one's way: no two overlap, nothing stands across a beam or on the row
        //behind a bore or crusher (where its duct goes) or right against a floor drill
        float[] worth = new float[all.size()];
        for(int i = 0; i < all.size(); i++) worth[i] = Packing.rate(cat, all.get(i)) / (1f + all.get(i).distance / 40f);
        List<Field.Site> packed = Packing.select(all, worth, Vars.world.width());
        packed.sort((a, b) -> Float.compare(Packing.rate(cat, b) / (1f + b.distance / 40f), Packing.rate(cat, a) / (1f + a.distance / 40f)));
        Map<String, Integer> count = new HashMap<>();
        for(Field.Site site : packed){
            String key = site.ground + "/" + site.item;
            if(count.getOrDefault(key, 0) >= keep) continue;
            field.sites.add(site);
            count.merge(key, 1, Integer::sum);
        }
        field.candidates = all.size();

        //what already stands: the map's own base for this side
        for(Building build : team.data().buildings){
            if(build.tile != Vars.world.tile(build.tileX(), build.tileY())) continue;
            Recipe r = existing(cat, build);
            if(r == null) continue;
            int item = -1;
            float factor = 1f;
            if(r.ground != Recipe.Ground.none){
                Field.Site site = site(cat, field, r.ground, build.block, build.tileX(), build.tileY(), build.rotation);
                if(site == null) continue;
                item = site.item;
                factor = site.factor;
            }
            Machine m = new Machine(r, item, factor, build.tileX(), build.tileY(), dst(field, build.tileX(), build.tileY()), new int[cat.size()], false, true);
            m.rotation = build.rotation;
            //(one of the map's that is not working now, or whose output has nowhere to go, gives nothing as it stands: seen
            //at once, before any income is measured; it may be taken up with lines of the plan's own. On crevice nine
            //crushers stood with their sand backed up, the plan counted on them, and its furnace never got sand)
            if(!r.makesUnits() && !producing(build)){
                if(!unfeedable.contains(arc.math.geom.Point2.pack(build.tileX(), build.tileY()))) field.revivable.add(m);
                continue;
            }
            field.existing.add(m);
        }
        return field;
    }

    /**
     * Leaves out of the map's own machines those whose item does not get to the core as the model would count it: for
     * each item, if what the core was seen to get (a second, watched over a while) is under a tenth of what those machines
     * would make, they stand idle or feed something else, and the plan may not count on them. What was left out, in words.
     */
    /**
     * The places of the map's idle machines a layout found no way to feed (what they take nothing within reach makes, or
     * no line can be laid to them): not counted as to be taken up again (onset's idle furnace, with no sand to be had near
     * it, was counted on by every plan for twenty minutes, and no unit came). Learned as the game goes, by place.
     */
    public static final Set<Integer> unfeedable = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static String prune(Catalog cat, Field field, Map<String, Float> income, Team team){
        StringBuilder out = new StringBuilder();
        for(int k = 0; k < cat.size(); k++){
            Res r = cat.get(k);
            if(r.kind != Res.Kind.item || !r.storable) continue;
            float made = 0f;
            List<Machine> makers = new ArrayList<>();
            for(Machine m : field.existing){
                if(m.out(k) <= 1e-4f) continue;
                made += m.out(k);
                makers.add(m);
            }
            if(made <= 0.05f) continue;
            //what feeds the map's own crafters is not meant for the core: those makers are kept
            boolean used = false;
            for(Machine m : field.existing) if(m.recipe.in[k] > 0f) used = true;
            if(used) continue;
            float got = income.getOrDefault(r.name, 0f);
            if(got >= made * 0.1f) continue;
            field.existing.removeAll(makers);
            //(one within reach of another side's turrets is not: a line to it would be shot down, which may be why it is idle)
            boolean[] shot = danger(team);
            for(Machine m : makers) if(m.recipe.ground != Recipe.Ground.none && !field.revivable.contains(m) && !shot[m.x + m.y * Vars.world.width()] && !unfeedable.contains(arc.math.geom.Point2.pack(m.x, m.y))) field.revivable.add(m);
            out.append(String.format("%d makers of %s left out (they would make %.2f/s, the core got %.2f/s), but may be taken up; ", makers.size(), r.name, made, got));
        }
        //what is left of the map's own: what the model says they give the core against what it got, the difference a drain
        Plant own = new Plant(cat);
        own.solve(field.existing, null, false);
        cat.drain = cat.zeros();
        for(int k = 0; k < cat.size(); k++){
            Res r = cat.get(k);
            if(r.kind != Res.Kind.item || !r.storable) continue;
            float expected = own.toCore[k], got = income.getOrDefault(r.name, 0f);
            if(expected > 0.05f && got < expected * 0.9f){
                cat.drain[k] = expected - Math.max(0f, got);
                out.append(String.format("%s: the map's own would give the core %.2f/s, it got %.2f/s; ", r.name, expected, got));
            }
        }
        return out.toString().trim();
    }

    /** Tiles another side's turrets reach (with two tiles to spare): nothing of the plan's goes there, it would be shot down. */
    public static boolean[] danger(Team team){
        int w = Vars.world.width(), h = Vars.world.height();
        boolean[] out = new boolean[w * h];
        for(var data : Vars.state.teams.active){
            if(data.team == team || data.team == Team.derelict) continue;
            for(Building b : data.buildings){
                if(!(b.block instanceof mindustry.world.blocks.defense.turrets.BaseTurret turret)) continue;
                int r = (int)Math.ceil(turret.range / Vars.tilesize) + 2, cx = b.tileX(), cy = b.tileY();
                for(int dx = -r; dx <= r; dx++){
                    for(int dy = -r; dy <= r; dy++){
                        int x = cx + dx, y = cy + dy;
                        if(x < 0 || y < 0 || x >= w || y >= h || dx * dx + dy * dy > r * r) continue;
                        out[x + y * w] = true;
                    }
                }
            }
        }
        return out;
    }

    /** The tiles a line can get to from the core: ground that can be built on, crossing up to three tiles of anything else by bridge. */
    static boolean[] reach(Field field, Team team){
        int w = Vars.world.width(), h = Vars.world.height();
        boolean[] open = new boolean[w * h], seen = new boolean[w * h], shot = danger(team);
        for(int y = 0; y < h; y++){
            for(int x = 0; x < w; x++){
                Tile t = Vars.world.tile(x, y);
                open[x + y * w] = !shot[x + y * w] && !t.block().isStatic() && (t.block() == mindustry.content.Blocks.air || t.block().alwaysReplace || t.build != null) && !t.floor().isDeep() && t.floor().placeableOn && Vars.world.isInMapArea(x, y) && Vars.world.getDarkness(x, y) < 3;
            }
        }
        IntQueue queue = new IntQueue();
        var core = team.core();
        int r = core.block.size / 2 + 1;
        for(int dx = -r; dx <= r; dx++){
            for(int dy = -r; dy <= r; dy++){
                int x = field.coreX + dx, y = field.coreY + dy;
                if(x < 0 || y < 0 || x >= w || y >= h || !open[x + y * w] || seen[x + y * w]) continue;
                seen[x + y * w] = true;
                queue.addLast(x + y * w);
            }
        }
        while(!queue.isEmpty()){
            int p = queue.removeFirst(), x = p % w, y = p / w;
            for(int d = 0; d < 4; d++){
                for(int j = 1; j <= 4; j++){
                    int nx = x + arc.math.geom.Geometry.d4x(d) * j, ny = y + arc.math.geom.Geometry.d4y(d) * j;
                    if(nx < 0 || ny < 0 || nx >= w || ny >= h) break;
                    int q = nx + ny * w;
                    if(!open[q]) continue;
                    if(!seen[q]){
                        seen[q] = true;
                        queue.addLast(q);
                    }
                    //open ground is walked one step at a time; only over what can't be built on does it jump
                    break;
                }
            }
        }
        return seen;
    }

    /** Whether anything is built under where the block would go (the game would let some blocks be put over others; a plan may not count on that). */
    static boolean occupied(Block block, int x, int y){
        boolean[] any = {false};
        Vars.world.tile(x, y).getLinkedTilesAs(block, t -> { if(t.build != null) any[0] = true; });
        return any[0];
    }

    static float score(Field.Site s){
        return s.factor / (1f + s.distance / 40f);
    }

    static float dst(Field field, int x, int y){
        return (float)Math.hypot(x - field.coreX, y - field.coreY);
    }

    /** What a machine of this kind gives standing here, or null if nothing. */
    static Field.Site site(Catalog cat, Field field, Recipe.Ground ground, Block block, int x, int y, int rot){
        int item = -1;
        float factor = 0f;
        IntSeq beam = new IntSeq();
        switch(ground){
            case vent -> {
                //on Erekir the vent machines need all nine tiles
                if(block.sumAttribute(Attribute.steam, x, y) < block.size * block.size - 0.001f) return null;
                factor = 1f;
            }
            case wallOre -> {
                BeamDrill drill = (BeamDrill)block;
                Point2 p = new Point2();
                Item found = null;
                int dx = Geometry.d4x(rot), dy = Geometry.d4y(rot);
                for(int lane = 0; lane < block.size; lane++){
                    block.nearbySide(x, y, rot, lane, p);
                    for(int i = 0; i < drill.range; i++){
                        Tile other = Vars.world.tile(p.x + dx * i, p.y + dy * i);
                        if(other == null) break;
                        if(!other.solid()){
                            beam.add(other.x + other.y * Vars.world.width());
                            continue;
                        }
                        Item drop = other.wallDrop();
                        if(drop != null && drop.hardness <= drill.tier && (drill.blockedItems == null || !drill.blockedItems.contains(drop))){
                            //two items in one bore makes it slow on both; such places are left out
                            if(found != null && found != drop) return null;
                            found = drop;
                            factor++;
                        }
                        break;
                    }
                }
                if(found == null) return null;
                item = cat.id(found.name);
            }
            case sandWall -> {
                WallCrafter crusher = (WallCrafter)block;
                Point2 p = new Point2();
                //every tile of its face on sand: one with half its face on nothing gives half, and is not worth the room
                for(int lane = 0; lane < block.size; lane++){
                    block.nearbySide(x, y, rot, lane, p);
                    Tile other = Vars.world.tile(p.x, p.y);
                    float a = other != null && other.solid() ? other.block().attributes.get(crusher.attribute) : 0f;
                    if(a <= 0f) return null;
                    factor += a;
                }
                item = cat.id(crusher.output.name);
            }
            case floorOre -> {
                Drill drill = (Drill)block;
                ObjectIntMap<Item> ores = new ObjectIntMap<>();
                Vars.world.tile(x, y).getLinkedTilesAs(block, other -> {
                    if(drill.canMine(other)) ores.increment(other.drop(), 0, 1);
                });
                Item best = null;
                for(var e : ores) if(best == null || e.value > ores.get(best, 0)) best = e.key;
                if(best == null) return null;
                item = cat.id(best.name);
                factor = ores.get(best, 0);
            }
            case liquidFloor -> {
                //one liquid under all of it (the game's rule), so much a tile
                Liquid[] drop = {null};
                float[] amount = {0f};
                boolean[] mixed = {false};
                Vars.world.tile(x, y).getLinkedTilesAs(block, other -> {
                    Liquid l = other.floor().liquidDrop;
                    if(l == null) return;
                    if(drop[0] != null && drop[0] != l) mixed[0] = true;
                    drop[0] = l;
                    amount[0] += other.floor().liquidMultiplier;
                });
                if(drop[0] == null || mixed[0]) return null;
                item = cat.id(drop[0].name);
                factor = amount[0];
            }
            default -> {
                return null;
            }
        }
        IntSeq tiles = new IntSeq();
        Vars.world.tile(x, y).getLinkedTilesAs(block, t -> tiles.add(t.x + t.y * Vars.world.width()));
        Field.Site site = new Field.Site(ground, x, y, rot, item, factor, dst(field, x, y), tiles.toArray());
        site.lane = beam.toArray();
        site.apron = apron(ground, block, x, y, rot);
        return site;
    }

    /**
     * The tiles a machine on the ground needs kept free of other such machines: the row behind a bore or crusher, where
     * the duct that takes what the row gives runs, and the ring round a floor drill, so every drill touches a corridor.
     */
    static int[] apron(Recipe.Ground ground, Block block, int x, int y, int rot){
        int w = Vars.world.width(), s = block.size, off = -(s - 1) / 2;
        IntSeq out = new IntSeq();
        int x0 = x + off, y0 = y + off, x1 = x0 + s - 1, y1 = y0 + s - 1;
        if(ground == Recipe.Ground.wallOre || ground == Recipe.Ground.sandWall){
            int back = (rot + 2) % 4;
            for(int i = 0; i < s; i++){
                int tx = back == 0 ? x1 + 1 : back == 2 ? x0 - 1 : x0 + i;
                int ty = back == 1 ? y1 + 1 : back == 3 ? y0 - 1 : y0 + i;
                if(Vars.world.tile(tx, ty) != null) out.add(tx + ty * w);
            }
        }else if(ground == Recipe.Ground.floorOre){
            for(int i = 0; i < s; i++){
                int[][] ring = {{x + off + i, y + off - 1}, {x + off + i, y + off + s}, {x + off - 1, y + off + i}, {x + off + s, y + off + i}};
                for(int[] t : ring) if(Vars.world.tile(t[0], t[1]) != null) out.add(t[0] + t[1] * w);
            }
        }
        return out.toArray();
    }

    /** The recipe a standing building works by, if it is one the catalog has. */
    /** Whether a building is making something that goes somewhere: working, and not full of what it makes. */
    static boolean producing(Building build){
        if(build.efficiency <= 0.01f && build.block.consPower != null) return false;
        if(build.items != null && build.block.hasItems && build.block.itemCapacity > 0 && build.items.total() >= build.block.itemCapacity - 1) return false;
        return true;
    }

    static Recipe existing(Catalog cat, Building build){
        String name = build.block.name;
        if(build instanceof UnitFactory.UnitFactoryBuild f){
            UnitFactory block = (UnitFactory)build.block;
            if(f.currentPlan < 0 || f.currentPlan >= block.plans.size) return null;
            name = name + ":" + block.plans.get(f.currentPlan).unit.name;
        }
        for(Recipe r : cat.recipes){
            if(r.name.equals(name)) return r;
            //a refabricator is taken to make whatever it is given
            if(r.block.equals(name) && r.unitIn >= 0) return r;
        }
        return null;
    }
}
