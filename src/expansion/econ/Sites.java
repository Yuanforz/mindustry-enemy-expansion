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
                        if(!Build.validPlace(block, team, x, y, rot) || occupied(block, x, y)) continue;
                        Field.Site site = site(cat, field, r.ground, block, x, y, rot);
                        if(site != null) all.add(site);
                    }
                }
            }
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
            field.existing.add(m);
        }
        return field;
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
