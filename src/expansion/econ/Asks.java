package expansion.econ;

import arc.math.*;
import arc.struct.*;
import arc.util.*;
import expansion.Blueprint.Part;
import mindustry.*;
import mindustry.game.*;
import mindustry.game.MapObjectives.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.storage.*;
import mindustry.world.blocks.units.*;

/**
 * What the map's own tasks ask of the side they are set for, as what the side already knows how to do: a stage to plan
 * (a source of an item, a maker of a unit, a defence), a few blocks to put down (the map counts only that they were put
 * down: walls, a block of some kind, a core on a core zone), and things to destroy (for the army). A campaign map opens
 * up as its tasks are done, and a part of it stays shut, with the enemy cores to be taken in it, until they are: on onset
 * nothing past the first enemy core could be reached until six walls stood.
 *
 * Tasks that need nothing done (a timer, a flag the map's script sets, research, which the side gets as the tree allows)
 * ask nothing.
 */
public final class Asks{
    private Asks(){}

    public static final class Ask{
        /** A stage to plan, as Match's stages are written ("stell:1", "oxide:0.2", "defend"), or null. */
        public @Nullable String stage;
        /** Blocks to put down. */
        public final Seq<Part> parts = new Seq<>();
        /** What to destroy. */
        public final Seq<Building> targets = new Seq<>();
        /** Why, in words, for the log. */
        public final Seq<String> why = new Seq<>();
        /** The task that asked for the stage, so that one task asks for one at a time. */
        public @Nullable MapObjective by;
    }

    /** Where a block may go: free of what is planned (the caller's layout), and placeable by the game's rules. */
    public interface Free{
        boolean at(Block block, int x, int y, int rotation);
    }

    /**
     * What the open tasks ask now. {@code allowed} is what the side may build; {@code free} says where a block may go;
     * {@code given} are the blocks already put down for a task (not asked for again while they stand or are going up).
     */
    public static Ask now(Team me, java.util.Set<String> allowed, Free free, Seq<Part> given){
        Ask ask = new Ask();
        var all = Vars.state.rules.objectives.all;
        for(MapObjective o : all){
            if(o.isCompleted() || !o.dependencyFinished()) continue;
            if(o instanceof BuildCountObjective b){
                int more = b.count - Vars.state.stats.placedBlockCount.get(b.block, 0);
                for(Part p : given) if(p.block == b.block) more--;
                if(more <= 0 || !allowed.contains(b.block.name)) continue;
                if(b.block instanceof UnitFactory uf && !uf.plans.isEmpty()){
                    want(ask, o, uf.plans.first().unit.name + ":1", "the map asks for a " + b.block.name);
                }else if(b.block instanceof Turret){
                    //a turret the map asks for is one that is fed (its next task is often that it is): the defence
                    want(ask, o, "defend", "the map asks for a " + b.block.name);
                }else{
                    Seq<Part> put = place(me, b.block, more, free, given);
                    ask.parts.addAll(put);
                    ask.why.add(String.format("the map asks for %d %s: %d placed", more, b.block.name, put.size));
                }
            }else if(o instanceof CoreItemObjective c){
                source(ask, o, me, c.item, Vars.state.stats.coreItemCount.get(c.item, 0) < c.amount);
            }else if(o instanceof ItemObjective c){
                source(ask, o, me, c.item, me.core() != null && me.core().items.get(c.item) < c.amount);
            }else if(o instanceof ProduceObjective p && p.content instanceof Item item){
                source(ask, o, me, item, true);
            }else if(o instanceof UnitCountObjective u){
                if(me.data().countType(u.unit) >= u.count || makes(me, u.unit)) continue;
                want(ask, o, u.unit.name + ":1", "the map asks for " + u.count + " " + u.unit.name);
            }else if(o instanceof DestroyBlockObjective d){
                Building b = Vars.world.build(d.pos.x, d.pos.y);
                if(b != null && b.team != me){
                    ask.targets.add(b);
                    ask.why.add("the map asks to destroy " + b.block.name + " at " + d.pos.x + "," + d.pos.y);
                }
            }else if(o instanceof DestroyBlocksObjective d){
                for(var pos : d.positions){
                    Building b = Vars.world.build(pos.x, pos.y);
                    if(b != null && b.team != me) ask.targets.add(b);
                }
                ask.why.add("the map asks to destroy " + d.positions.length + " " + (d.block == null ? "blocks" : d.block.name));
            }else if(o instanceof DestroyCoreObjective){
                for(Team t : Team.all) if(t != me && t.active()) for(CoreBlock.CoreBuild c : t.cores()) ask.targets.add(c);
                ask.why.add("the map asks to destroy the enemy cores");
            }
        }
        return ask;
    }

    static void want(Ask ask, MapObjective by, String stage, String why){
        if(ask.stage != null) return;
        ask.stage = stage;
        ask.by = by;
        ask.why.add(why);
    }

    /** An item the core is to have more of: a stage that makes it, unless something of the side makes it already. */
    static void source(Ask ask, MapObjective by, Team me, Item item, boolean short_){
        if(!short_ || makes(me, item)) return;
        want(ask, by, item.name + ":0.2", "the map asks for " + item.name + ", which nothing of ours makes");
    }

    /** Whether a building of the side makes this item (a drill on its ore, a crafter of it). */
    static boolean makes(Team me, Item item){
        for(Building b : me.data().buildings){
            if(b instanceof Drill.DrillBuild d && d.dominantItem == item) return true;
            if(b instanceof BeamDrill.BeamDrillBuild d && d.lastItem == item) return true;
            if(b.block instanceof GenericCrafter g && g.outputItems != null) for(ItemStack s : g.outputItems) if(s.item == item) return true;
        }
        return false;
    }

    /** Whether a building of the side makes this unit. */
    static boolean makes(Team me, UnitType unit){
        for(Building b : me.data().buildings){
            if(b.block instanceof UnitFactory uf && uf.plans.contains(p -> p.unit == unit)) return true;
            if(b.block instanceof Reconstructor r && r.upgrades.contains(u -> u[1] == unit)) return true;
        }
        return false;
    }

    /**
     * Places for so many of a block: a core where the game lets one go (a core zone, the place of a core destroyed), the
     * nearest to ours; walls about our turrets, on the side the enemy comes from; anything else near the core.
     */
    static Seq<Part> place(Team me, Block block, int n, Free free, Seq<Part> given){
        Seq<Part> out = new Seq<>();
        CoreBlock.CoreBuild home = me.core();
        if(home == null) return out;
        if(block instanceof CoreBlock){
            Tile best = null;
            float bestD = Float.MAX_VALUE;
            for(Tile t : Vars.world.tiles){
                if(!Vars.world.isInMapArea(t.x, t.y) || !mindustry.world.Build.validPlace(block, me, t.x, t.y, 0)) continue;
                float d = Mathf.dst2(t.x, t.y, home.tileX(), home.tileY());
                if(d < bestD){
                    bestD = d;
                    best = t;
                }
            }
            if(best != null) out.add(new Part(block, best.x, best.y, 0, null));
            return out;
        }
        //about what it is to stand by: walls by our turrets (the nearest to the enemy first), anything else by the core
        Seq<Building> anchors = new Seq<>();
        if(block.group == mindustry.world.meta.BlockGroup.walls){
            for(Building b : me.data().buildings) if(b.block instanceof Turret) anchors.add(b);
            Building foe = null;
            for(Team t : Team.all) if(t != me && t.active() && t.core() != null) foe = t.core();
            if(foe != null){
                Building f = foe;
                anchors.sort(b -> b.dst2(f));
            }
        }
        anchors.add(home);
        IntSet taken = new IntSet();
        for(Part p : given) taken.add(arc.math.geom.Point2.pack(p.x, p.y));
        for(Building anchor : anchors){
            int r0 = anchor.block.size / 2 + 1 + (block.group == mindustry.world.meta.BlockGroup.walls ? 0 : 2);
            for(int r = r0; r <= r0 + 12 && out.size < n; r++){
                for(int dx = -r; dx <= r && out.size < n; dx++){
                    for(int dy = -r; dy <= r && out.size < n; dy++){
                        if(Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
                        int x = anchor.tileX() + dx, y = anchor.tileY() + dy;
                        if(taken.contains(arc.math.geom.Point2.pack(x, y))) continue;
                        if(!mindustry.world.Build.validPlace(block, me, x, y, 0) || !free.at(block, x, y, 0)) continue;
                        out.add(new Part(block, x, y, 0, null));
                        //(a block bigger than a tile keeps a gap to the next of its kind; walls go side by side)
                        int s = block.size == 1 ? 0 : block.size;
                        for(int ox = -s; ox <= s; ox++) for(int oy = -s; oy <= s; oy++) taken.add(arc.math.geom.Point2.pack(x + ox, y + oy));
                    }
                }
            }
            if(out.size >= n) break;
        }
        return out;
    }
}
