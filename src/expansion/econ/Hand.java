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
        offload();
        tell();
        watch.update(next);
        //a line to the core that is no longer wanted is cut
        if(job == null && !cuts.isEmpty()){
            cut();
            return;
        }
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
            if(where != null && unseen(where) != null){
                doing = String.format("a unit is sent to see %d,%d for #%d %s (ground not seen can't be built on); digging meanwhile", where.x, where.y, next + 1, m.recipe.name);
                Tile dark = unseen(where);
                scout(new Part(where.block, dark.x, dark.y, 0, null));
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
                doing = String.format("saving up for #%d %s: %s", next + 1, m.recipe.name, shortOf(need));
                dig(need);
                return;
            }
        }
        if(job != null){
            doing = repairing ? "putting back what went missing" : String.format("building #%d %s", next + 1, machine == null ? "" : machine.recipe.name);
            build();
            return;
        }
        doing = "all built; digging what the core has least of";
        //all done: what a crafter sent to the core now goes all to the factories it touches; and dig for whatever comes later
        if(!cut){
            cut = true;
            for(Part p : works.coreLine.values()) unit.addBuild(new BuildPlan(p.x, p.y));
        }
        dig(null);
    }

    /**
     * What has been dug goes to the core as it comes, as a player's unit has it (the game has a unit run by the AI hand it
     * over only once its stack is full: sixty items, eleven seconds of digging, that a build would wait for).
     */
    void offload(){
        var core = unit.team.core();
        if(core == null || unit.stack.amount < 6 || !unit.within(core, Vars.mineTransferRange)) return;
        int accepted = core.acceptStack(unit.item(), unit.stack.amount, unit);
        if(accepted > 0) Call.transferItemTo(unit, unit.item(), accepted, unit.x, unit.y, core);
    }

    /** What the layout meant, to which what the hand and the watch are doing now is added for showing; null for none. */
    public Intent intent;
    float toldAt;
    /** What it is doing, in words: set where it decides. */
    public String doing = "";

    /** Puts what it is doing now, and how each machine runs, into the intent being shown. */
    void tell(){
        if(intent == null || now() < toldAt) return;
        toldAt = now() + 1f;
        List<Intent.Mark> marks = new ArrayList<>();
        Map<Intent.Layer, List<String>> words = new EnumMap<>(Intent.Layer.class);
        List<String> hand = new ArrayList<>();
        hand.add(String.format("%s: %d of %d jobs done; %s", Watch.clock(now()), next, order.size(), doing));
        Seq<Part> showing = job != null ? job : pending;
        if(showing != null){
            for(Part p : showing){
                Tile t = Vars.world.tile(p.x, p.y);
                if(t == null || (t.block() == p.block && !(t.build instanceof ConstructBuild))) continue;
                int off = -(p.block.size - 1) / 2;
                marks.add(Intent.liveMark(Intent.Layer.hand, Intent.Shape.rect, p.x + off, p.y + off, p.x + off + p.block.size - 1, p.y + off + p.block.size - 1, job != null ? Intent.orange : Intent.grey, null));
            }
        }
        if(unit.mineTile != null) marks.add(Intent.liveMark(Intent.Layer.hand, Intent.Shape.arrow, unit.tileX(), unit.tileY(), unit.mineTile.x, unit.mineTile.y, Intent.yellow, "digging " + unit.mineTile.wallDrop().name));
        if(next < order.size() && works.at.get(order.get(next)) != null){
            Part p = works.at.get(order.get(next));
            marks.add(Intent.liveMark(Intent.Layer.hand, Intent.Shape.text, p.x, p.y + p.block.size, p.x, p.y, Intent.orange, "next: #" + (next + 1) + " " + order.get(next).recipe.name));
        }
        words.put(Intent.Layer.hand, hand);
        //the watch: a dot on each machine by how it runs
        List<String> watching = new ArrayList<>();
        int[] colors = {Intent.green, Intent.yellow, Intent.orange, Intent.red, Intent.grey};
        for(var e : watch.seconds.entrySet()){
            Part p = works.at.get(e.getKey());
            Integer st = watch.last.get(e.getKey());
            if(p == null || st == null) continue;
            marks.add(Intent.liveMark(Intent.Layer.watch, Intent.Shape.dot, p.x, p.y, p.x, p.y, colors[st], Watch.states[st]));
            if(st != 0) watching.add(String.format("%s at %d,%d: %s", e.getKey().recipe.name, p.x, p.y, Watch.states[st]));
        }
        if(watch.repaired > 0) watching.add(watch.repaired + " parts put back so far");
        words.put(Intent.Layer.watch, watching);
        intent.live(marks, words);
    }

    /** Pieces to take up. */
    final Seq<Part> cuts = new Seq<>();

    void cut(){
        Part p = cuts.first();
        Tile t = Vars.world.tile(p.x, p.y);
        doing = String.format("cutting the line to the core at %d,%d: what it carries goes all to the machines now", p.x, p.y);
        if(t == null || t.build == null || t.build.team != unit.team){
            cuts.remove(0);
            unit.plans.clear();
            return;
        }
        boolean planned = false;
        for(BuildPlan plan : unit.plans) if(plan.breaking && plan.x == p.x && plan.y == p.y) planned = true;
        if(!planned) unit.addBuild(new BuildPlan(p.x, p.y));
        moveTo(t, unit.type.buildRange * 0.6f);
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
                Tile dark = tile == null ? null : unseen(p);
                if(dark != null && tile.block() != p.block){
                    scout(new Part(p.block, dark.x, dark.y, 0, null));
                    continue;
                }
                if(tile == null || unit.dst(tile.worldx(), tile.worldy()) > Vars.tilesize * 4f) continue;
                //units standing where it goes keep it from going down: they are sent off, and it waits
                if(tile.block() != p.block && !(tile.build instanceof ConstructBuild) && !Build.validPlace(p.block, unit.team, p.x, p.y, p.rotation) && shoo(p)) continue;
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
            //the lines to the core that nothing still to be built needs any more are cut
            Seq<Part> due = works.cuts.get(next);
            if(due != null) for(Part p : due){
                cuts.add(p);
                watch.taken.add(p);
            }
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

    /** A tile of a part's ground the side has not seen yet (the game will not let anything go on one), or null. */
    Tile unseen(Part p){
        Tile[] out = {null};
        Tile t = Vars.world.tile(p.x, p.y);
        if(t == null) return null;
        t.getLinkedTilesAs(p.block, o -> { if(out[0] == null && !Vars.fogControl.isDiscovered(unit.team, o.x, o.y)) out[0] = o; });
        return out[0];
    }

    /** Sends the side's units off the ground a part is to go on; whether there were any. */
    boolean shoo(Part p){
        float t = Vars.tilesize, size = p.block.size * t;
        float x = p.x * t + p.block.offset - size / 2f, y = p.y * t + p.block.offset - size / 2f;
        boolean[] any = {false};
        mindustry.entities.Units.nearby(unit.team, x - t, y - t, size + 2 * t, size + 2 * t, u -> {
            if(u == unit || u.type.flying || !(u.controller() instanceof mindustry.ai.types.CommandAI ai)) return;
            any[0] = true;
            //out of the way: a few tiles past the part, away from the core
            var core = unit.team.core();
            float angle = core == null ? 0f : arc.math.Angles.angle(core.x, core.y, p.x * t, p.y * t);
            ai.commandPosition(new arc.math.geom.Vec2(p.x * t, p.y * t).add(arc.math.geom.Vec2.ZERO.cpy().trns(angle, size + 4 * t)));
        });
        if(any[0]) doing = String.format("waiting for units to get off %d,%d, where %s goes", p.x, p.y, p.block.name);
        return any[0];
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

    /** What of a cost the core does not have yet, in words. */
    String shortOf(int[] need){
        var core = unit.team.core();
        StringBuilder b = new StringBuilder();
        for(int k = 0; k < need.length; k++){
            if(need[k] <= 0 || core == null) continue;
            int have = core.items.get(Vars.content.item(works.cat.get(k).name));
            if(have < need[k]) b.append(works.cat.get(k).name).append(' ').append(have).append('/').append(need[k]).append(' ');
        }
        return b.length() == 0 ? "paid for" : b.toString().trim();
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
