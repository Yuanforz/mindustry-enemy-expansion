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
        watch.notBuilt = i -> skipped.contains(i) || i == retrying;
        begun = new float[order.size()];
        finished = new float[order.size()];
        partsOf = new int[order.size()];
        costOf = new int[order.size()];
        Arrays.fill(begun, Float.NaN);
        Arrays.fill(finished, Float.NaN);
    }

    /** Whether its own jobs come before putting back what went missing (a defence's). */
    public boolean urgent;

    /**
     * Whether everything of the order has been begun and finished, whatever it is putting back meanwhile (a stage is over when
     * its own order is up: under raids the mending never ends, and the next stage waited on it to its time).
     */
    public boolean orderDone(){
        return next >= order.size() && skipped.isEmpty() && pending == null && (job == null || repairing);
    }

    /** Whether everything of the order is up (nothing begun and nothing put off is left). */
    public boolean done(){
        return next >= order.size() && job == null && skipped.isEmpty() && pending == null;
    }

    float mendAt;
    /** Machines seen cut off from every generator at the last look, by position: twice running, and they are mended. */
    final arc.struct.IntSet cutOff = new arc.struct.IntSet();
    public int mended;

    /**
     * Every so often: a machine of the plan's, built, on a grid with no generator (its node's beam taken by something put up
     * later in the way, or a node lost) gets nodes that join it to the main grid again, put up like anything that went
     * missing. Seen so on basin: the furnace and a bore beside it on a grid of their own, and no silicon all game.
     */
    void mendPower(){
        if(now() < mendAt) return;
        mendAt = now() + 10f;
        arc.struct.IntSet now = new arc.struct.IntSet();
        for(int i = 0; i < Math.min(next, order.size()); i++){
            Part self = works.at.get(order.get(i));
            if(self == null) continue;
            Building b = Vars.world.build(self.x, self.y);
            if(b == null || b.block != self.block || b.power == null || b.block.consPower == null || !b.power.graph.producers.isEmpty()) continue;
            if(b.power.graph.batteries.size > 0) continue;
            int pos = b.pos();
            now.add(pos);
            if(!cutOff.contains(pos)) continue;
            Seq<Part> nodes = Wiring.mend(works.cat, unit.team, b);
            if(nodes == null || nodes.isEmpty()) continue;
            for(Part p : nodes) if(!watch.repairs.contains(p, true)) watch.repairs.add(p);
            mended++;
            trouble.add(String.format("%s at %d,%d had no power: %d nodes to join it to the grid", b.block.name, b.tileX(), b.tileY(), nodes.size));
            //(one at a time: the next look sees whether that did it for the others on the same island)
            break;
        }
        cutOff.clear();
        cutOff.addAll(now);
    }

    /** Whether it has nothing at all to do: all built, nothing to put back or to cut. */
    public boolean idle(){
        return done() && watch.repairs.isEmpty() && deferred.isEmpty() && cuts.isEmpty();
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
        mendPower();
        //parts left for want of anyone to see their ground: once seen, they go up; until then a unit goes to look
        if(job == null && !deferred.isEmpty()){
            Seq<Part> now = new Seq<>();
            for(Part p : deferred) if(unseen(p) == null) now.add(p);
            if(now.size > 0){
                deferred.removeAll(now, true);
                for(Part p : now) watch.taken.remove(p);
                job = now;
                repairing = true;
                unit.mineTile = null;
            }else if(canScout()){
                Tile dark = unseen(deferred.first());
                if(dark != null) scout(new Part(deferred.first().block, dark.x, dark.y, 0, null));
            }
        }
        //a line to the core that is no longer wanted is cut
        if(job == null && !cuts.isEmpty()){
            cut();
            return;
        }
        //something built that went missing is put back before anything new (unless what is new is urgent: a defence going
        //up while raiders break lines faster than they can be put back; what they broke waits till it is up)
        //(and only while it can be begun: saving up for it, what was broken is put back meanwhile)
        boolean first = urgent && job == null && next < order.size() && affordable(order.get(next).cost);
        //(what is under fire, or lost again and again lately, waits: put back into the fire it is lost again at once)
        Seq<Part> mend = new Seq<>(), held = new Seq<>();
        if(job == null && !first) for(Part p : watch.repairs) (watch.hot(p) ? held : mend).add(p);
        if(held.size > 0 && mend.isEmpty() && job == null && !first) watch.heldBack++;
        if(job == null && !mend.isEmpty() && !first){
            //paid for first, like any job (building waits on the core for what it lacks, and the hand would wait with it)
            int[] need = cost(mend);
            if(affordable(need)){
                job = mend;
                watch.repairs = held;
                repairing = true;
                unit.mineTile = null;
            }else{
                doing = "saving up to put back what went missing: " + shortOf(need);
                dig(need);
                return;
            }
        }
        if(job == null && next < order.size()){
            //a step put off before, that can be paid for now, goes first
            if(retryPutOff()) return;
            Machine m = order.get(next);
            //its place not yet seen: a unit goes to have a look (the core unit sees nothing), and meanwhile it digs
            Part where = works.at.get(m);
            //(once: the game works its fog out on a thread of its own)
            Tile dark = where == null ? null : unseen(where);
            //waited too long for a look (no unit can get there): this one is put off to the end, and the next is begun
            if(dark != null && fogSince[0] == next && now() - fogSince[1] > 45f){
                skipped.add(next);
                trouble.add(String.format("%s at %d,%d put off: nobody could get to see its ground", m.recipe.name, where.x, where.y));
                next++;
                return;
            }
            if(dark != null && fogSince[0] != next){
                fogSince[0] = next;
                fogSince[1] = now();
            }
            if(dark != null){
                doing = String.format("a unit is sent to see %d,%d for #%d %s (ground not seen can't be built on); digging meanwhile", where.x, where.y, next + 1, m.recipe.name);
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
                //nothing coming in for it: put off, and the next step begun (an item none of our machines makes yet and the
                //core unit can't dig: on caldera a ship fabricator ordered before the first furnace waited for silicon 200 of
                //250 for eight minutes, while 3800 of everything else piled up and no turret went down)
                int shortNow = shortfall(need);
                if(saveSince[0] != next || shortNow < saveSince[2]){
                    saveSince[0] = next;
                    saveSince[1] = now();
                    saveSince[2] = shortNow;
                }else if(putOff && now() - saveSince[1] > stallTime && next + 1 < order.size()){
                    skipped.add(next);
                    trouble.add(String.format("#%d %s put off: %s, none of it come in for %.0f s", next + 1, m.recipe.name, shortOf(need), stallTime));
                    pending = null;
                    saveSince[0] = -1f;
                    next++;
                    return;
                }
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
        //what was put off for want of a look is tried again once its ground is seen
        if(!skipped.isEmpty()){
            for(int i = 0; i < skipped.size; i++){
                int idx = skipped.get(i);
                Machine m = order.get(idx);
                Part where = works.at.get(m);
                if(where != null && unseen(where) != null){
                    if(canScout()){
                        Tile dark = unseen(where);
                        if(dark != null) scout(new Part(where.block, dark.x, dark.y, 0, null));
                    }
                    continue;
                }
                Seq<Part> parts = works.job(m);
                if(!affordable(cost(parts))){
                    doing = String.format("saving up for #%d %s (put off before): %s", idx + 1, m.recipe.name, shortOf(cost(parts)));
                    dig(cost(parts));
                    return;
                }
                skipped.removeIndex(i);
                begun[idx] = now();
                partsOf[idx] = parts.size;
                retrying = idx;
                job = parts;
                machine = m;
                unit.mineTile = null;
                return;
            }
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
        if(core == null || unit.stack.amount <= 0 || !unit.within(core, Vars.mineTransferRange)) return;
        //(what is carried from a machine goes over at once; what is dug, a few at a time)
        if(unit.stack.amount < 6 && !carrying) return;
        carrying = false;
        int accepted = core.acceptStack(unit.item(), unit.stack.amount, unit);
        if(accepted > 0){
            if(!carrying) delivered[unit.item().id] += accepted;
            Call.transferItemTo(unit, unit.item(), accepted, unit.x, unit.y, core);
        }
    }

    /** For calibration: items dug and handed to the core, and seconds spent digging, by item id. */
    public final float[] delivered = new float[256], digSeconds = new float[256];

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

    /** Jobs put off because nobody could get to see their ground, by place in the order; the one being built again. */
    public final IntSeq skipped = new IntSeq();
    int retrying = -1;
    /** For seeing a job get no further: the job, how many of its parts were left, since when. */
    Seq<Part> stallJob;
    int stallLeft;
    float stallSince;
    /** How long a job may get no further before it is put off, in seconds. */
    public static float stallTime = Float.parseFloat(System.getProperty("xp.stall", "60"));
    final float[] fogSince = {-1f, 0f};
    /** The step being saved up for, since when, and the least the core has been short of it by since then. */
    final float[] saveSince = {-1f, 0f, 0f};
    /** Whether a step nothing comes in for is put off for the next (-Dxp.putoff=false: waited for however long). */
    public static boolean putOff = !"false".equals(System.getProperty("xp.putoff"));

    /** Parts put off because their ground has not been seen and there was no unit to send. */
    public final Seq<Part> deferred = new Seq<>();

    /** Whether there is a unit (not the core unit) to send to have a look. */
    boolean canScout(){
        for(Unit u : unit.team.data().units) if(u != unit && u.type != unit.type && !u.dead() && u.controller() instanceof mindustry.ai.types.CommandAI) return true;
        return false;
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
    /** Whether units made are sent to wait by the core, out of the way (off where something else leads them: a unit told where to go is left alone by the game's squad AI). */
    public boolean rallyUnits = true;

    void rally(){
        if(!rallyUnits || now() < rallyAt) return;
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
        //ground no one has seen, with no unit to send to look: those parts wait, the rest goes up
        if(!canScout()){
            for(int i = job.size - 1; i >= 0; i--){
                Part p = job.get(i);
                Tile tile = Vars.world.tile(p.x, p.y);
                if(tile != null && tile.block() != p.block && unseen(p) != null){
                    deferred.add(p);
                    watch.taken.add(p);
                    job.remove(i);
                }
            }
        }
        //putting back what went missing: what has come under fire since goes back to wait (not begun again and again
        //under the raider)
        if(repairing){
            for(int i = job.size - 1; i >= 0; i--){
                Part p = job.get(i);
                Tile tile = Vars.world.tile(p.x, p.y);
                if(tile == null || tile.block() == p.block || !watch.hot(p)) continue;
                job.remove(i);
                if(!watch.repairs.contains(p, true)) watch.repairs.add(p);
                unit.plans.remove(b -> b.x == p.x && b.y == p.y);
                watch.heldBack++;
            }
            if(job.isEmpty()){
                job = null;
                repairing = false;
                return;
            }
        }
        boolean all = true;
        Part target = null;
        float best = Float.MAX_VALUE;
        int left = 0;
        for(Part p : job){
            Tile tile = Vars.world.tile(p.x, p.y);
            if(tile == null) continue;
            if(tile.block() == p.block && tile.build != null && !(tile.build instanceof ConstructBuild)) continue;
            all = false;
            left++;
            float d = unit.dst(tile.worldx(), tile.worldy());
            if(d < best){
                best = d;
                target = p;
            }
            //(a site is begun only within the core unit's building reach, as a player's: further off it stays a plan, and the game
            //begins it once the unit has flown near)
            if(!(tile.build instanceof ConstructBuild site && site.current == p.block) && unit.within(tile.worldx(), tile.worldy(), unit.type.buildRange)){
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
                //(near enough to have seen it: within building reach, the ground seen; a unit that builds from afar never comes
                //within a few tiles of a part it can't put down, and waited on it for good)
                if(tile == null || unit.dst(tile.worldx(), tile.worldy()) > Math.max(Vars.tilesize * 4f, unit.type.buildRange * 0.8f)) continue;
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
        if(all && retrying >= 0){
            finished[retrying] = now();
            retrying = -1;
            job = null;
            machine = null;
            unit.plans.clear();
            return;
        }
        if(all && repairing){
            repairing = false;
            job = null;
            unit.plans.clear();
            return;
        }
        //a job that gets no further for a long while (a part that will not go down where the other side's units stand and
        //fire, say) is put off to the end, and the next begun: one wall line that never finished kept the breaches behind it
        //from going up on aegis for four minutes, while the core held two thousand items
        if(job != stallJob || left < stallLeft){
            stallJob = job;
            stallLeft = left;
            stallSince = now();
        }else if(!all && now() - stallSince > stallTime){
            stallJob = null;
            if(repairing){
                trouble.add(String.format("putting back %d parts given up after %.0f s with no progress", left, stallTime));
                repairing = false;
            }else if(retrying >= 0){
                trouble.add(String.format("#%d %s given up again after %.0f s with no progress", retrying + 1, machine == null ? "" : machine.recipe.name, stallTime));
                retrying = -1;
            }else{
                trouble.add(String.format("#%d %s put off after %.0f s with no progress (%d parts left)", next + 1, machine == null ? "" : machine.recipe.name, stallTime, left));
                skipped.add(next);
                next++;
            }
            job = null;
            machine = null;
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

    /** How much of a cost the core is short of, every item together. */
    int shortfall(int[] need){
        var core = unit.team.core();
        if(core == null) return Integer.MAX_VALUE;
        int sum = 0;
        for(int k = 0; k < need.length; k++){
            if(need[k] > 0) sum += Math.max(0, need[k] - core.items.get(Vars.content.item(works.cat.get(k).name)));
        }
        return sum;
    }

    /** Begins the first step put off before whose ground is seen and that can be paid for now; whether it did. */
    boolean retryPutOff(){
        for(int i = 0; i < skipped.size; i++){
            int idx = skipped.get(i);
            Machine m = order.get(idx);
            if(!affordable(m.cost)) continue;
            Part where = works.at.get(m);
            if(where != null && unseen(where) != null) continue;
            Seq<Part> parts = works.job(m);
            if(!affordable(cost(parts))) continue;
            skipped.removeIndex(i);
            begun[idx] = now();
            partsOf[idx] = parts.size;
            retrying = idx;
            job = parts;
            machine = m;
            unit.mineTile = null;
            return true;
        }
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

    /** What it dug last. */
    Item lastPick;

    /** Whether what it holds was taken from a machine, to be handed to the core however little it is. */
    boolean carrying;

    /**
     * Carries what the core is short of from a machine of the plan's that has no line to the core (see
     * {@link Machine#hauled}): flies there, takes what it holds, and brings it back. False if there is nothing to carry.
     */
    boolean haul(int[] cost){
        var core = unit.team.core();
        if(core == null || cost == null) return false;
        for(Machine m : order){
            if(!m.hauled) continue;
            Part p = works.at.get(m);
            Building b = p == null ? null : Vars.world.build(p.x, p.y);
            if(b == null || b.team != unit.team || b.items == null) continue;
            for(int k = 0; k < cost.length; k++){
                Item item = Vars.content.item(works.cat.get(k).name);
                if(item == null || cost[k] <= core.items.get(item)) continue;
                boolean has = unit.stack.amount > 0 && unit.item() == item;
                if(!has && b.items.get(item) <= 0) continue;
                unit.mineTile = null;
                //something else in hand goes to the core first
                if(unit.stack.amount > 0 && unit.item() != item){
                    carrying = true;
                    moveTo(core.tile, Vars.mineTransferRange * 0.6f);
                    return true;
                }
                int want = Math.min(unit.type.itemCapacity, cost[k] - core.items.get(item));
                if(has && (unit.stack.amount >= want || b.items.get(item) <= 0)){
                    carrying = true;
                    doing += String.format(" (carrying %d %s from %s)", unit.stack.amount, item.name, m.recipe.name);
                    moveTo(core.tile, Vars.mineTransferRange * 0.6f);
                    return true;
                }
                if(unit.within(b, Vars.itemTransferRange * 0.8f)){
                    Call.takeItems(b, item, want - (has ? unit.stack.amount : 0), unit);
                }else{
                    doing += String.format(" (fetching %s from %s at %d,%d)", item.name, m.recipe.name, p.x, p.y);
                    moveTo(b, Vars.itemTransferRange * 0.5f);
                }
                return true;
            }
        }
        return false;
    }

    /** Digs whatever of a cost the core is furthest short of and can be dug; with no cost, or nothing of it to dig, what it has least of. */
    void dig(int[] cost){
        var core = unit.team.core();
        if(core == null) return;
        if(haul(cost)) return;
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
        //what it was digging, it goes on with while the core is still short of it (as the model has it): changing walls
        //costs the way between them
        if(cost != null && lastPick != null && lastPick != pick){
            int id = works.cat.id(lastPick.name);
            int carried = unit.stack.amount > 0 && unit.item() == lastPick ? unit.stack.amount : 0;
            if(unit.canMine(lastPick) && cost[id] - core.items.get(lastPick) - carried > 0) pick = lastPick;
        }
        lastPick = pick;
        Tile wall = wall(pick, core);
        if(wall == null) return;
        //a full stack, or one of something else, goes to the core first: the game hands over only near it
        boolean full = unit.stack.amount >= unit.type.itemCapacity || (unit.stack.amount > 0 && unit.item() != pick);
        if(full && !unit.within(core, Vars.mineTransferRange * 0.9f)){
            unit.mineTile = null;
            moveTo(core.tile, Vars.mineTransferRange * 0.6f);
            return;
        }
        if(full){
            //in range but of another item: hand it all over now
            int accepted = core.acceptStack(unit.item(), unit.stack.amount, unit);
            if(accepted > 0) Call.transferItemTo(unit, unit.item(), accepted, unit.x, unit.y, core);
            else unit.clearItem();
        }
        if(unit.mineTile != wall) unit.mineTile = wall;
        digSeconds[pick.id] += arc.util.Time.delta / 60f;
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
