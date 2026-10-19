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
        all.sort((a, b) -> Float.compare(score(b), score(a)));
        //no two overlap, nothing stands across a drill's beam, and no beam crosses something that stands
        IntSet taken = new IntSet(), lanes = new IntSet();
        Map<String, Integer> count = new HashMap<>();
        for(Field.Site site : all){
            String key = site.ground + "/" + site.item;
            if(count.getOrDefault(key, 0) >= keep) continue;
            boolean free = true;
            for(int t : site.tiles) if(taken.contains(t) || lanes.contains(t)){ free = false; break; }
            for(int t : site.lane) if(taken.contains(t)){ free = false; break; }
            if(!free) continue;
            for(int t : site.tiles) taken.add(t);
            for(int t : site.lane) lanes.add(t);
            field.sites.add(site);
            count.merge(key, 1, Integer::sum);
        }

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
                for(int lane = 0; lane < block.size; lane++){
                    block.nearbySide(x, y, rot, lane, p);
                    Tile other = Vars.world.tile(p.x, p.y);
                    if(other != null && other.solid()) factor += other.block().attributes.get(crusher.attribute);
                }
                if(factor <= 0f) return null;
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
        return site;
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
