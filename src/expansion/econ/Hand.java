package expansion.econ;

import arc.math.geom.*;
import arc.struct.*;
import expansion.Blueprint.Part;
import mindustry.*;
import mindustry.entities.units.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.ConstructBlock.*;

import java.util.*;

/**
 * Flies the core unit through a planned order, the way {@link Timeline} has it do: it digs by hand what the next machine
 * still lacks, and once the core can pay for the machine and everything that goes with it, flies there and builds it all.
 */
public final class Hand extends mindustry.entities.units.AIController{
    public final Layout works;
    /** Keeps an eye on what is built: puts back what went missing, and counts how each machine runs. */
    public final Watch watch;
    public final List<Machine> order;
    public int next;
    /** What is being built now, for which machine of the order; null when it is digging. */
    public Seq<Part> job;
    public Machine machine;
    /** The parts worked out for the next machine, while what they cost comes in. */
    Seq<Part> pending;
    /** When each machine of the order was begun and finished, in seconds of the game. */
    public final float[] begun, finished;
    /** How many parts each machine's job had, and what they cost in all. */
    public final int[] partsOf, costOf;
    /** Parts that could not be put down where the plan had them. */
    public final Seq<String> trouble = new Seq<>();
    float checkAt;

    public Hand(Layout works, List<Machine> order){
        this.works = works;
        this.order = order;
        watch = new Watch(works, order);
        begun = new float[order.size()];
        finished = new float[order.size()];
        partsOf = new int[order.size()];
        costOf = new int[order.size()];
        Arrays.fill(begun, Float.NaN);
        Arrays.fill(finished, Float.NaN);
    }

    static float now(){
        return (float)(Vars.state.tick / 60.0);
    }

    @Override
    public void updateUnit(){
        rally();
        watch.update(next);
        //something built that went missing is put back before anything new
        if(job == null && !watch.repairs.isEmpty()){
            job = watch.repairs;
            watch.repairs = new Seq<>();
            repairing = true;
            unit.mineTile = null;
        }
        if(job == null && next < order.size()){
            Machine m = order.get(next);
            //its place not yet seen: a unit goes to have a look (the core unit sees nothing), and meanwhile it digs
            Part where = works.at.get(m);
            if(where != null && !Vars.fogControl.isDiscovered(unit.team, where.x, where.y)){
                scout(where);
                dig(m.cost);
                return;
            }
            //worked out against the map as it is once the plan's cost of it is in, and begun once all of it is paid for
            if(pending == null && affordable(m.cost)) pending = works.job(m);
            int[] need = pending == null ? m.cost : cost(pending);
            if(pending != null && affordable(need)){
                job = pending;
                pending = null;
                machine = m;
                begun[next] = now();
                partsOf[next] = job.size;
                for(int c : need) costOf[next] += c;
                unit.mineTile = null;
            }else{
                dig(need);
                return;
            }
        }
        if(job != null){
            build();
            return;
        }
        //all done: what a crafter sent to the core now goes all to the factories it touches; and dig for whatever comes later
        if(!cut){
            cut = true;
            for(Part p : works.coreLine.values()) unit.addBuild(new BuildPlan(p.x, p.y));
        }
        dig(null);
    }

    boolean cut, repairing;
    float rallyAt;

    /** Units standing about by the core are sent a little way off, out of the doors of the factories that made them. */
    void rally(){
        if(now() < rallyAt) return;
        rallyAt = now() + 3f;
        var core = unit.team.core();
        if(core == null) return;
        for(Unit u : unit.team.data().units){
            if(u == unit || u.type == unit.type || !(u.controller() instanceof mindustry.ai.types.CommandAI ai) || ai.targetPos != null) continue;
            float angle = arc.math.Angles.angle(works.field.coreX, works.field.coreY, works.at.values().stream().mapToInt(p -> p.x).sum() / (float)Math.max(1, works.at.size()), works.at.values().stream().mapToInt(p -> p.y).sum() / (float)Math.max(1, works.at.size())) + 180f;
            ai.commandPosition(new arc.math.geom.Vec2(core.x, core.y).add(arc.math.geom.Vec2.ZERO.cpy().trns(angle, 14f * Vars.tilesize)));
        }
    }

    void build(){
        boolean all = true;
        Part target = null;
        float best = Float.MAX_VALUE;
        for(Part p : job){
            Tile tile = Vars.world.tile(p.x, p.y);
            if(tile == null) continue;
            if(tile.block() == p.block && tile.build != null && !(tile.build instanceof ConstructBuild)) continue;
            all = false;
            float d = unit.dst(tile.worldx(), tile.worldy());
            if(d < best){
                best = d;
                target = p;
            }
            if(!(tile.build instanceof ConstructBuild site && site.current == p.block)){
                if(Build.validPlace(p.block, unit.team, p.x, p.y, p.rotation)){
                    Call.beginPlace(unit, p.block, unit.team, p.x, p.y, p.rotation, p.config);
                }
            }
            if(!hasPlan(p)) unit.addBuild(new BuildPlan(p.x, p.y, p.rotation, p.block, p.config));
        }
        //something the plan had that the game will not let go down there is left out, and said; but only once the core
        //unit is close enough to have seen the place (ground not yet seen can't be built on, and it is seen by coming near)
        if(now() > checkAt){
            checkAt = now() + 2f;
            for(Part p : job){
                Tile tile = Vars.world.tile(p.x, p.y);
                //ground not yet seen: a unit goes to look, and the part waits
                if(tile != null && !Vars.fogControl.isDiscovered(unit.team, p.x, p.y) && tile.block() != p.block){
                    scout(p);
                    continue;
                }
                if(tile == null || unit.dst(tile.worldx(), tile.worldy()) > Vars.tilesize * 4f) continue;
                if(tile.block() != p.block && !(tile.build instanceof ConstructBuild) && !Build.validPlace(p.block, unit.team, p.x, p.y, p.rotation)){
                    trouble.add(String.format("%s at %d,%d for %s (there: %s)", p.block.name, p.x, p.y, machine == null ? "a repair" : machine.recipe.name, tile.block().name));
                    job.remove(p);
                    unit.plans.remove(b -> b.x == p.x && b.y == p.y);
                    return;
                }
            }
        }
        if(all && repairing){
            repairing = false;
            job = null;
            unit.plans.clear();
            return;
        }
        if(all){
            finished[next] = now();
            next++;
            job = null;
            machine = null;
            unit.plans.clear();
            return;
        }
        if(target != null){
            //a part the game will not take yet may be on ground not seen: go right up to it
            Tile tt = Vars.world.tile(target.x, target.y);
            boolean placeable = tt.build instanceof ConstructBuild || Build.validPlace(target.block, unit.team, target.x, target.y, target.rotation);
            moveTo(tt, placeable ? unit.type.buildRange * 0.7f : Vars.tilesize * 2f);
        }
    }

    /** Sends the nearest unit of the side's (not the core unit) to a place, so that the side sees it. */
    void scout(Part p){
        Unit best = null;
        float bestD = Float.MAX_VALUE;
        for(Unit u : unit.team.data().units){
            if(u == unit || u.type == unit.type || u.dead() || !(u.controller() instanceof mindustry.ai.types.CommandAI)) continue;
            float d = u.dst(p.x * Vars.tilesize, p.y * Vars.tilesize);
            if(d < bestD){
                bestD = d;
                best = u;
            }
        }
        if(best != null) ((mindustry.ai.types.CommandAI)best.controller()).commandPosition(new arc.math.geom.Vec2(p.x * Vars.tilesize, p.y * Vars.tilesize));
    }

    boolean hasPlan(Part p){
        for(BuildPlan plan : unit.plans) if(plan.x == p.x && plan.y == p.y && plan.block == p.block) return true;
        return false;
    }

    int[] cost(Seq<Part> parts){
        int[] c = new int[works.cat.size()];
        for(Part p : parts){
            Tile tile = Vars.world.tile(p.x, p.y);
            if(tile != null && tile.block() == p.block) continue;
            for(ItemStack s : p.block.requirements) c[works.cat.id(s.item.name)] += Math.round(s.amount * Vars.state.rules.buildCostMultiplier);
        }
        return c;
    }

    boolean affordable(int[] cost){
        var core = unit.team.core();
        if(core == null) return false;
        for(int k = 0; k < cost.length; k++){
            if(cost[k] > 0 && core.items.get(Vars.content.item(works.cat.get(k).name)) < cost[k]) return false;
        }
        return true;
    }

    /** Digs whatever of a cost the core is furthest short of and can be dug; with no cost, or nothing of it to dig, what it has least of. */
    void dig(int[] cost){
        var core = unit.team.core();
        if(core == null) return;
        Item pick = null;
        float worst = -1f;
        for(Item item : Vars.content.items()){
            int id = works.cat.id(item.name);
            if(!unit.canMine(item) || !item.buildable || works.field.handRate(works.cat, id) <= 0f) continue;
            float short_ = cost == null ? core.storageCapacity - core.items.get(item) : cost[id] - core.items.get(item);
            if(short_ > worst && (cost == null || short_ > 0)){
                worst = short_;
                pick = item;
            }
        }
        if(pick == null){
            if(cost != null){
                dig(null);
            }
            return;
        }
        Tile wall = wall(pick, core);
        if(wall == null) return;
        if(unit.mineTile != wall) unit.mineTile = wall;
        moveTo(wall, unit.type.mineRange * 0.6f);
    }

    final ObjectMap<Item, Tile> walls = new ObjectMap<>();

    /** The wall of an item nearest the core, the one the core unit can hand over from if any is that near. */
    Tile wall(Item item, Building core){
        Tile known = walls.get(item);
        if(known != null && known.wallDrop() == item) return known;
        Tile best = null;
        float bestD = Float.MAX_VALUE;
        for(Tile t : Vars.world.tiles){
            if(t.wallDrop() != item) continue;
            float d = core.dst(t.worldx(), t.worldy());
            if(d < bestD){
                bestD = d;
                best = t;
            }
        }
        walls.put(item, best);
        return best;
    }
}
