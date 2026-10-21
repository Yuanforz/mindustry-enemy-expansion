package expansion.ops;

import arc.struct.*;
import expansion.army.*;
import expansion.defense.Placement;
import expansion.econ.*;
import mindustry.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.storage.*;

import java.io.*;
import java.util.*;

/**
 * The command layer: what to do next, chosen by looking ahead (see docs/COMMAND.md).
 *
 * Candidates are macro-actions made of what the side already knows how to do: a stage of the economy that makes a kind
 * of unit with so many makers, with or without a defence of so many modules put first. Each is worked out by the
 * economy model alone (no layout): its build order played forward with what comes in over time, so a defence of what is
 * not held yet is bought when the income has paid for it, and one of what nothing makes comes with the makers of that.
 * Then each is played forward to a horizon in an abstract game against the other side, in several scenarios: the economy
 * as the model has it or a third slower; the other side as it is seen (its army, its yards, the map's waves) or also
 * building yards as fast as we could; and sending its army once it outweighs what holds our home by half, as much, or
 * twice. Fights by Lanchester's square law; a side beaten at home is besieged, its core taken down at the winner's damage
 * a second while what it turns out meanwhile fights on.
 *
 * Each game is scored by one objective, discounted in time (what is foretold further off counts less, being less sure):
 * our core falling is the worst there is, the sooner the worse; taking the other side's cores is the best, the sooner the
 * better; otherwise the strength of the army at the horizon. The scenarios' scores are put together by the posture's
 * attitude to risk: the worst of them (steady), the mean of the worse half (balanced: the conditional value at risk), or
 * the mean (aggressive). The best is taken, and only its first step is acted on: the choice is made again at the next
 * stage, and sooner when a stage falls behind its model (receding horizon, as model predictive control does).
 * (Search over scripted macro-actions in a forward model: Puppet Search, Barriga et al. 2015; MCPlan, Chung et al. 2005.)
 */
public final class Command{
    /** How careful: how the scenarios are put together, how much the other side is feared, when our army goes out, what strength is worth. */
    public enum Posture{
        steady(Risk.worst, 1.2f, 2.5f, 3f),
        balanced(Risk.worseHalf, 1f, 2f, 2f),
        aggressive(Risk.mean, 1f, 1.4f, 1f);

        public final Risk risk;
        /** How much stronger the other side is taken to be than the forecast says. */
        public final float foeScale;
        /** How much our army must outweigh the other side's army and its base's turrets to go out. */
        public final float pushEdge;
        /** Score a tenfold of army strength at the horizon is worth (before the discount). */
        public final float perTenfold;

        Posture(Risk risk, float foeScale, float pushEdge, float perTenfold){
            this.risk = risk;
            this.foeScale = foeScale;
            this.pushEdge = pushEdge;
            this.perTenfold = perTenfold;
        }

        /** By name; anything else (as "setting"): what the mod's setting says. */
        public static Posture of(String name){
            for(Posture p : values()) if(p.name().equals(name)) return p;
            return values()[arc.math.Mathf.clamp(expansion.Options.posture.get(), 0, 2)];
        }
    }

    /** How the scenarios' scores make one. */
    public enum Risk{worst, worseHalf, mean}

    /** The defence planner, as the game stands now, and what our turrets can be fed with. */
    public interface Defences{
        /** The defence planner's plan for now, of so many modules at each place it defends (the kind strongest for its cost), or null. */
        default expansion.defense.Planner.Plan plan(int modules){
            return plan(modules, 0);
        }

        /** The same of the kind ranked so (0: the strongest for its cost, 1: the next kind). */
        expansion.defense.Planner.Plan plan(int modules, int rank);

        Collection<String> feedable();
    }

    /** One way to go, as it played out in the model. */
    public static final class Candidate{
        public final Muster.Aim aim;
        /** How many modules of the defence go first (0: none, Integer.MAX_VALUE: all it plans). */
        public final int modules;
        /** Which kind of module (by its rank for strength for its cost), and its turret, for the report. */
        public int rank;
        public String kind = "";
        /** Whether its stage was planned on top of the defence (else from the game as it stands, begun once that is up). */
        public boolean atop;
        /** Whether it is going on with the stage being built. */
        public boolean keep;
        public float score, firstUnit = Float.POSITIVE_INFINITY, defenceDone;
        /** Whether it does what the map's tasks ask. */
        public boolean task;
        public int turrets;
        /** The scenario worst for it, as it played out. */
        public Outcome outcome;
        /** Its score in each scenario, and how each played out (for -Dops.all). */
        float[] scores;
        Outcome[] outcomes;

        Candidate(Muster.Aim aim, int modules){
            this.aim = aim;
            this.modules = modules;
        }

        public String stage(){
            return aim.item != null ? aim.item.name + ":" + aim.rate : aim.type.name + ":" + aim.makers;
        }

        @Override
        public String toString(){
            String d = modules == 0 ? "" : String.format(" after a defence of %d module(s) %sa place (%d turrets, all up by %s%s)", modules, kind.isEmpty() ? "" : "of " + kind + " ", turrets, Float.isInfinite(defenceDone) ? "never" : Plan.time(defenceDone), atop ? "; the stage planned on it" : "");
            return String.format("%s%s%s%s: score %.1f, first unit %s; at worst: %s", keep ? "going on with " : "", stage(), d, task ? " (a task)" : "", score, Float.isInfinite(firstUnit) ? "never" : Plan.time(firstUnit), outcome);
        }
    }

    /** One scenario: how much slower the game goes than the model, whether the other side also builds yards as fast as we could, when it sends its army. */
    static final class Scenario{
        final float lag, sendAt;
        final boolean mirror;
        /** What the other side has, as its yards work now or at their own pace. */
        final Forecast forecast;

        Scenario(float lag, boolean mirror, float sendAt, Forecast forecast){
            this.lag = lag;
            this.mirror = mirror;
            this.sendAt = sendAt;
            this.forecast = forecast;
        }
    }

    public final Team me, foe;
    public Posture posture = Posture.balanced;
    /** How far ahead it looks, and the step of the model, in seconds. */
    public float horizon = Float.parseFloat(System.getProperty("ops.horizon", "720")), step = 10f;
    /** The discount's time scale: what is foretold this many seconds off counts 1/e as much (-Dops.discount). */
    public static float discount = Float.parseFloat(System.getProperty("ops.discount", "600"));
    /**
     * How much slower the game is than the economy model, in the two economy scenarios: as the model, and as slow as the
     * bench has seen it (93-119% of the model's time on the campaign maps, 97-103% on generated maps with no enemy).
     */
    public static float[] lags = {1f, 1.15f};
    /**
     * How much of a unit's estimated damage a second lands on a core under siege with nothing of ours about it (measured on
     * a generated map: four stells, 144 a second by the game's estimate, took 2100 health off a core in 27 s, 78 a second;
     * with our units about it, a quarter; -Dops.siege).
     */
    public static float siegeEfficiency = Float.parseFloat(System.getProperty("ops.siege", "0.55"));
    /** When the other side sends its army: once it outweighs what holds our home by this much. */
    static final float[] sendAts = {0.5f, 1f, 2f};
    /** What doing one of the map's tasks is worth, now (discounted like the rest by when it is done; -Dops.task). */
    public static float taskWorth = Float.parseFloat(System.getProperty("ops.task", "150"));
    /** How many modules at each defended place a defence is weighed with, and of which kinds (by rank for strength for cost). */
    public static int[] defenceCounts = {1, 2, 4};
    public static int defenceKinds = Integer.getInteger("ops.defkinds", 2);
    /** How many of the best stages alone are planned again on top of each defence (-Dops.atop). */
    public static int afterTop = Integer.getInteger("ops.atop", 3);
    long afterNanos;
    /** How many makers a kind is weighed with, besides one. */
    public static int[] makerCounts = {2, 3, 4, 6};
    /** How many kinds of unit get more makers weighed, at most. */
    public int kinds = Integer.getInteger("ops.kinds", 4);
    public final List<Candidate> weighed = new ArrayList<>();
    public Forecast forecast;
    public final StringBuilder log = new StringBuilder();
    long dpAt;
    /** When the other side was first weighed against, and what its buildings cost in all then. */
    float foeFirstSeen = -1f, foeFirstSpend;

    /** What a side's buildings cost in all (items of every kind). */
    static float spent(Team team){
        float sum = 0f;
        for(Building b : team.data().buildings) for(var st : b.block.requirements) sum += st.amount;
        return sum;
    }

    public Command(Team me, Team foe){
        this.me = me;
        this.foe = foe;
    }

    /** The best way to go now, or null if nothing can be had. */
    public Candidate decide(List<String> blocks, Set<String> allowed, List<Design> library, Defences defences, Map<String, Float> income){
        return decide(blocks, allowed, library, defences, income, List.of());
    }

    /**
     * The same, with what the map's tasks ask for as ways of their own ("stell:1", "oxide:0.2", "defend"): each weighed
     * like the rest, and worth besides what doing the task is worth (a map opens up as its tasks are done, and the enemy
     * cores to be taken with it), the sooner the more.
     */
    public Candidate decide(List<String> blocks, Set<String> allowed, List<Design> library, Defences defences, Map<String, Float> income, List<String> tasks){
        return decide(blocks, allowed, library, defences, income, tasks, null, null);
    }

    /**
     * The same, with going on with the stage being built as a way of its own: what is left of it (going, its model played
     * from the game as it stands; spec, what it makes, "stell:2"), weighed like the rest. The receding horizon: weighed again
     * at each decision, a stage that has stuck (waiting on what nothing brings in) loses to a way planned from what stands,
     * and one going as it should is gone on with.
     */
    public Candidate decide(List<String> blocks, Set<String> allowed, List<Design> library, Defences defences, Map<String, Float> income, List<String> tasks, Plan going, String spec){
        long t0 = System.nanoTime();
        log.setLength(0);
        weighed.clear();
        forecast = new Forecast(me, foe, horizon, step);
        //(and with its yards at their own pace, when one is not at it now: a scenario of its own)
        Forecast full = forecast.slack ? new Forecast(me, foe, horizon, step, true) : null;
        reachCache.clear();
        nowReaching = mobileNow(true);
        nowStaying = mobileNow(false);

        //every kind with one maker (only the model says whether the first tier soon or the second later is worth more);
        //then, of those that can be had, more makers (how many is a choice too). Every kind: one that cannot get to the other
        //side's base still holds ours; only one that can goes out.
        List<Muster.Aim> all = Muster.aims(me, foe, blocks, true);
        List<Muster.Aim> ones = new ArrayList<>();
        for(Muster.Aim aim : all) ones.add(aim.withMakers(1));
        List<Object[]> stages = new ArrayList<>();
        long w0 = System.nanoTime();
        weigh(ones, stages, blocks, allowed, library, income);
        long w1 = System.nanoTime();
        //(how many makers: a choice point of its own, as many as the model finds the economy can feed, up to six)
        List<Muster.Aim> more = new ArrayList<>();
        List<Object[]> had = new ArrayList<>(stages);
        had.sort((a, b) -> Float.compare(((Muster.Aim)b[0]).strength, ((Muster.Aim)a[0]).strength));
        for(int i = 0; i < Math.min(kinds, had.size()); i++){
            Muster.Aim one = (Muster.Aim)had.get(i)[0];
            for(int n : makerCounts) more.add(one.withMakers(n));
        }
        weigh(more, stages, blocks, allowed, library, income);
        long w2 = System.nanoTime();
        //the tasks' stages: a unit or an item to have
        boolean taskDefence = false;
        List<Muster.Aim> taskAims = new ArrayList<>();
        for(String t : tasks){
            if(t.equals("defend")){
                taskDefence = true;
                continue;
            }
            String[] st = t.split(":");
            UnitType ut = Vars.content.unit(st[0]);
            Item it = Vars.content.item(st[0]);
            if(ut != null) taskAims.add(new Muster.Aim(ut, Math.max(1, (int)Float.parseFloat(st[1])), ut.dpsEstimate * ut.health, Reach.toFoe(ut, me, foe), 1f, 1).task(true));
            else if(it != null) taskAims.add(Muster.Aim.item(it, Float.parseFloat(st[1])));
        }
        if(!taskAims.isEmpty()) weigh(taskAims, stages, blocks, allowed, library, income);
        if(stages.isEmpty()){
            log.append("  command: nothing can be had\n");
            return null;
        }

        //the other side building as fast as we could: our fastest way to units, walked here (for the mirror scenarios)
        Plan fastest = null;
        float soonest = Float.POSITIVE_INFINITY;
        for(Object[] c : stages){
            Plan p = (Plan)c[1];
            if(p.model.first[p.unit] < soonest){
                soonest = p.model.first[p.unit];
                fastest = p;
            }
        }

        long d0 = System.nanoTime();
        //defences of so many modules (a choice point: one, two, four), each with the economy that pays for and feeds it,
        //worked out by the model with what comes in over time
        List<Object[]> guards = new ArrayList<>();
        guards.add(new Object[]{0, null, null, 0, ""});
        Set<String> guardsSeen = new HashSet<>();
        List<java.util.concurrent.Future<Object[]>> defenceJobs = new ArrayList<>();
        if(defences != null) for(int rank = 0; rank < defenceKinds; rank++) for(int k : defenceCounts){
            expansion.defense.Planner.Plan dp;
            try{
                dp = defences.plan(k, rank);
            }catch(Throwable t){
                log.append("  command: the defence planner failed: ").append(t).append('\n');
                continue;
            }
            if(dp == null || dp.placements.isEmpty()){
                if(dp != null && k == defenceCounts[0]) log.append("  command: the defence planner has nothing").append(rank > 0 ? " of a kind ranked " + (rank + 1) : "").append(": ").append(String.join("; ", dp.notes)).append('\n');
                continue;
            }
            //(the same turrets again, as four modules where only two fit: weighed once)
            Map<String, Integer> turretsOf = new TreeMap<>();
            for(Placement pl : dp.placements) if(pl.kind == Placement.Kind.turret) turretsOf.merge(pl.block, 1, Integer::sum);
            if(!guardsSeen.add(turretsOf.toString())) continue;
            String kind = turretsOf.isEmpty() ? "" : turretsOf.keySet().iterator().next();
            final int kk = k, rk = rank;
            final expansion.defense.Planner.Plan dpf = dp;
            Collection<String> feed = defences.feedable();
            //(each defence's economy worked out by the model on the pool, as the stages are)
            defenceJobs.add(pool().submit(() -> {
                Plan p = new Plan(new PrintStream(OutputStream.nullOutputStream()));
                p.gate = true;
                p.dry = true;
                p.gateDepth = 2;
                p.income = income;
                try{
                    Plan dry = p.defend(me, blocks, allowed, dpf, feed);
                    return dry != null && dry.model != null ? new Object[]{kk, dry, dpf, rk, kind} : null;
                }catch(Throwable t){
                    return new Object[]{kk, null, t};
                }
            }));
        }
        for(var job : defenceJobs){
            try{
                Object[] g = job.get();
                if(g == null) continue;
                if(g[1] == null) log.append("  command: a defence of ").append(g[0]).append(" could not be weighed: ").append(g[2]).append('\n');
                else guards.add(g);
            }catch(Exception e){
                log.append("  command: a defence could not be weighed: ").append(e).append('\n');
            }
        }
        long d1 = System.nanoTime();
        dpAt = d1;

        //how fast the other side builds, from what is seen of it: what its buildings cost in all, at each weighing, gained
        //since it was first weighed against, a second, against what our fastest stage spends a second; the mirror scenarios
        //have it build as fast as we could, slowed by that (before there is anything to go by, as fast; the game's own enemy
        //on a sector, that plays the base it was given, comes to build at no pace at all)
        float seen = (float)(Vars.state.tick / 60.0);
        float spent = spent(foe);
        if(foeFirstSeen < 0f){
            foeFirstSeen = seen;
            foeFirstSpend = spent;
        }
        float ours = 0f;
        if(fastest != null){
            for(Machine m : fastest.model.order) for(int c : m.cost) ours += c;
            ours /= Math.max(1f, Float.isInfinite(fastest.model.t) ? horizon : Math.min(fastest.model.t, horizon));
        }
        float pace = seen - foeFirstSeen <= 0f || ours <= 0f ? 1f : Math.min(1f, Math.max(0f, spent - foeFirstSpend) / (seen - foeFirstSeen) / ours);
        boolean builds = fastest != null && pace > 0f;
        List<Scenario> scenarios = new ArrayList<>();
        for(Forecast fc : full == null ? List.of(forecast) : List.of(forecast, full)) for(float lag : lags) for(boolean m : builds ? new boolean[]{false, true} : new boolean[]{false}) for(float s : sendAts) scenarios.add(new Scenario(lag, m, s, fc));
        log.append(String.format("  command: the other side builds at %.0f%% of our fastest pace (%.1f items a second since %s, ours %.1f)%s\n", pace * 100f, seen - foeFirstSeen <= 0f ? 0f : Math.max(0f, spent - foeFirstSpend) / (seen - foeFirstSeen), Plan.time(foeFirstSeen), ours, builds ? "" : ": no mirror"));
        Force foeGuard = foeGuard();
        //(the mirror, the defences and each stage's units under each lag: worked out once)
        //(what our fastest stage adds over what our standing makers turn out anyway: what the other side could add, as fast;
        //its own standing yards are in the forecast already)
        //(the other side as slow as ours in the same scenario: the lag is how far the game falls behind the model, for any
        //side's economy alike; theirs at the model's pace while ours lags is a race lost by construction, and every rush
        //lost in it had the side wall itself in and send its first unit at ten minutes on onset, where a rush of two
        //makers had won at six)
        Map<Float, Force[]> mirrors = new HashMap<>();
        for(float lag : lags){
            if(fastest == null){
                mirrors.put(lag, null);
                continue;
            }
            //(at the pace it is seen to build at: a slower economy is a later curve)
            float slow = lag / Math.max(1e-3f, pace);
            Force[] with = unitCurve(fastest, slow), without = unitCurve(fastest, slow, List.of());
            Force[] added = new Force[with.length];
            for(int i = 0; i < with.length; i++){
                added[i] = minus(with[i], without[i]);
                if(added[i].count < 0 || added[i].strength() < 0f) added[i] = new Force();
            }
            mirrors.put(lag, shifted(added, walkTime()));
        }

        //the stages alone first; then, for each defence, the best few of them planned again on top of it (its machines
        //standing, what it leaves in the core to begin with), as the next stage would be once it is up; the rest are
        //counted as begun only once it is up, from nothing (too slow by what the defence's economy gives them)
        Map<Float, Object[]> none = new HashMap<>();
        for(Object[] c : stages){
            Muster.Aim aim = (Muster.Aim)c[0];
            Plan stage = (Plan)c[1];
            weighed.add(weighOne(aim, stage, null, 0, 0, "", none, scenarios, mirrors, foeGuard, taskDefence));
        }
        //(going on with the stage being built)
        if(going != null && going.model != null && spec != null){
            String[] sp = spec.split(":");
            UnitType ut = Vars.content.unit(sp[0]);
            if(ut != null && sp.length > 1){
                Muster.Aim aim = new Muster.Aim(ut, Math.max(1, (int)Float.parseFloat(sp[1])), ut.dpsEstimate * ut.health, reachesFoe(ut), 1f, 1);
                Candidate c = weighOne(aim, going, null, 0, 0, "", none, scenarios, mirrors, foeGuard, taskDefence);
                c.keep = true;
                weighed.add(c);
            }
        }
        List<Candidate> plain = new ArrayList<>(weighed);
        plain.sort((a, b) -> Float.compare(b.score, a.score));
        List<Object[]> tops = new ArrayList<>();
        for(Candidate c : plain){
            if(tops.size() >= afterTop) break;
            for(Object[] st : stages) if(st[0] == c.aim) tops.add(st);
        }
        long a0 = System.nanoTime();
        Map<String, java.util.concurrent.Future<Plan>> atop = new HashMap<>();
        for(int gi = 1; gi < guards.size(); gi++){
            Object[] g = guards.get(gi);
            Plan guard = (Plan)g[1];
            float done = (Float)schedule(guard, (expansion.defense.Planner.Plan)g[2], lags[0])[2];
            if(Float.isInfinite(done) || done >= horizon) continue;
            Timeline before = new Timeline(guard.cat, guard.field, guard.model.order);
            before.runUntil(done);
            Set<Machine> skip = guard.feeding();
            for(Object[] st : tops){
                Muster.Aim aim = (Muster.Aim)st[0];
                atop.put(gi + "|" + System.identityHashCode(aim), pool().submit(() -> {
                    Plan p = new Plan(new PrintStream(OutputStream.nullOutputStream()));
                    p.gate = true;
                    p.widen = true;
                    p.dry = true;
                    p.after = before;
                    p.afterSkip = skip;
                    try{
                        Plan dry = aim.item != null ? p.make(me, blocks, allowed, library, aim.item.name, aim.rate, income == null || income.isEmpty() ? null : income)
                            : p.make(me, blocks, allowed, library, aim.type.name, aim.makers, income == null || income.isEmpty() ? null : income);
                        return dry == null || dry.model == null || Float.isInfinite(dry.model.first[dry.unit]) ? null : dry;
                    }catch(Throwable t){
                        return null;
                    }
                }));
            }
        }
        for(int gi = 1; gi < guards.size(); gi++){
            Object[] g = guards.get(gi);
            int k = (Integer)g[0];
            Plan guard = (Plan)g[1];
            expansion.defense.Planner.Plan dp = (expansion.defense.Planner.Plan)g[2];
            int rank = (Integer)g[3];
            String kind = (String)g[4];
            Map<Float, Object[]> schedules = new HashMap<>();
            for(float lag : lags) schedules.put(lag, schedule(guard, dp, lag));
            if(Boolean.getBoolean("ops.all")){
                Object[] s0 = schedules.get(lags[0]);
                log.append(String.format("    defence of %d %s: all up by %s, its modules at %s: %s\n", k, kind, Plan.time((Float)s0[2]), s0[0], guard.model.order));
            }
            for(Object[] c : stages){
                Muster.Aim aim = (Muster.Aim)c[0];
                Plan stage = (Plan)c[1], on = null;
                var job = atop.get(gi + "|" + System.identityHashCode(aim));
                if(job != null){
                    try{
                        on = job.get();
                    }catch(Exception e){
                        on = null;
                    }
                }
                Candidate cand = weighOne(aim, on != null ? on : stage, null, k, rank, kind, schedules, scenarios, mirrors, foeGuard, taskDefence);
                cand.atop = on != null;
                weighed.add(cand);
            }
        }
        afterNanos = System.nanoTime() - a0;
        long p1 = System.nanoTime();
        weighed.sort((a, b) -> Float.compare(b.score, a.score));
        log.append(String.format("  command: times: stages %.1f + %.1f s, defences %.1f s, stages after them %.1f s, playing %.1f s\n", (w1 - w0) / 1e9f, (w2 - w1) / 1e9f, (d1 - d0) / 1e9f, afterNanos / 1e9f, (p1 - d1 - afterNanos) / 1e9f));
        log.append(String.format("  command (%s, horizon %s, %d scenarios): theirs at our core by the horizon at most %s%s; %s\n", posture, Plan.time(horizon), scenarios.size(), forecast.at[forecast.steps], full == null ? "" : " (" + full.at[full.steps] + " with its yards at their own pace)", forecast.notes));
        for(int i = 0; i < Math.min(weighed.size(), 8); i++) log.append("    ").append(weighed.get(i)).append('\n');
        if(weighed.size() > 8) log.append("    (").append(weighed.size() - 8).append(" more)\n");
        if(Boolean.getBoolean("ops.all")){
            StringBuilder head = new StringBuilder("    scenarios (lag/mirror/sendAt):");
            for(Scenario sc : scenarios) head.append(String.format(" %.2f/%s/%.1f%s", sc.lag, sc.mirror ? "m" : "-", sc.sendAt, sc.forecast.full ? "/full" : ""));
            log.append(head).append('\n');
            for(Candidate c : weighed){
                log.append("    ").append(c.stage()).append(c.modules == 0 ? "" : "+d" + c.modules + c.kind).append(String.format(" %.1f:", c.score));
                for(int i = 0; i < c.scores.length; i++) log.append(String.format(" %.0f[%s]", c.scores[i], c.outcomes[i]));
                log.append('\n');
            }
        }
        log.append(String.format("  command: weighed %d ways in %d scenarios in %.1f s\n", weighed.size(), scenarios.size(), (System.nanoTime() - t0) / 1e9f));
        if(Boolean.getBoolean("ops.time")) log.append(Took.report());
        if(weighed.isEmpty()) return null;
        //(going on with the stage being built is left only for a way no worse in any scenario: of many ways weighed, the
        //best looks better than it is by the model's errors alone (the optimizer's curse), and a stage left for one that
        //only looked better was begun again every minute and none finished; one that has stuck is beaten in every one)
        Candidate keep = null;
        for(Candidate c : weighed) if(c.keep) keep = c;
        Candidate best = weighed.get(0);
        if(keep != null && best != keep){
            boolean dominates = true, better = false;
            for(int i = 0; i < best.scores.length; i++){
                if(best.scores[i] < keep.scores[i]) dominates = false;
                if(best.scores[i] > keep.scores[i]) better = true;
            }
            if(!dominates || !better){
                log.append("  command: going on with the stage: ").append(best.stage()).append(!better ? " is no better in any scenario\n" : " is better on the whole but worse in some scenario\n");
                return keep;
            }
        }
        return best;
    }

    /** One way to go weighed: its stage's units (after the defence, if any, is all up), played in every scenario. */
    Candidate weighOne(Muster.Aim aim, Plan stage, Force[] unused, int k, int rank, String kind, Map<Float, Object[]> schedules, List<Scenario> scenarios, Map<Float, Force[]> mirrors, Force foeGuard, boolean taskDefence){
        Candidate cand = new Candidate(aim, k);
        cand.rank = rank;
        cand.kind = kind;
        float[] scores = new float[scenarios.size()];
        float worst = Float.POSITIVE_INFINITY;
        Map<Float, Force[]> curves = new HashMap<>();
        for(float lag : lags) curves.put(lag, unitCurve(stage, lag));
        cand.outcomes = new Outcome[scenarios.size()];
        if(sched(schedules) != null){
            Object[] s = sched(schedules);
            cand.turrets = ((Force)s[3]).count;
            cand.defenceDone = (Float)s[2];
        }
        for(int i = 0; i < scenarios.size(); i++){
            Scenario sc = scenarios.get(i);
            Object[] sched = schedules.get(sc.lag);
            float done = sched == null ? 0f : (Float)sched[2];
            //(one core unit builds both: the stage after the defence is all up)
            Force[] units = shifted(curves.get(sc.lag), done);
            Outcome o = play(units, sched, sc.mirror ? mirrors.get(sc.lag) : null, foeGuard, sc.sendAt, aim.reaches, sc.forecast);
            scores[i] = score(o);
            cand.outcomes[i] = o;
            if(scores[i] < worst){
                worst = scores[i];
                cand.outcome = o;
            }
        }
        cand.score = aggregate(scores);
        cand.scores = scores;
        //(a way that does a task: worth what the task is, discounted by when it is done)
        if(aim.task){
            float done = stage.model.first[stage.unit] * lags[0] + cand.defenceDone;
            cand.score += taskWorth * d(done);
            cand.task = true;
        }else if(k > 0 && taskDefence){
            cand.score += taskWorth * d(cand.defenceDone);
            cand.task = true;
        }
        Force[] base = shifted(curves.get(lags[0]), cand.defenceDone);
        for(int i = 0; i < base.length; i++) if(base[i].count > 0){ cand.firstUnit = i * step; break; }
        return cand;
    }

    static Object[] sched(Map<Float, Object[]> schedules){
        return schedules.isEmpty() ? null : schedules.get(lags[0]);
    }

    /** The scenarios' scores as one, by the posture's attitude to risk. */
    float aggregate(float[] scores){
        float[] s = scores.clone();
        Arrays.sort(s);
        return switch(posture.risk){
            case worst -> s[0];
            case worseHalf -> {
                int n = Math.max(1, s.length / 2);
                float sum = 0f;
                for(int i = 0; i < n; i++) sum += s[i];
                yield sum / n;
            }
            case mean -> {
                float sum = 0f;
                for(float v : s) sum += v;
                yield sum / s.length;
            }
        };
    }

    /** Threads the stages are weighed on at once (-Dops.threads; each plan is worked out by itself, over its own copy of the reading). */
    public static int threads = Integer.getInteger("ops.threads", 8);
    static java.util.concurrent.ExecutorService pool;

    static synchronized java.util.concurrent.ExecutorService pool(){
        if(pool == null) pool = java.util.concurrent.Executors.newFixedThreadPool(Math.max(1, threads), r -> {
            Thread t = new Thread(r, "command-weigh");
            t.setDaemon(true);
            return t;
        });
        return pool;
    }

    /** Works out by the model alone each aim's stage, at once on the pool; those that can be had go in the list, with their plans. */
    void weigh(List<Muster.Aim> aims, List<Object[]> stages, List<String> blocks, Set<String> allowed, List<Design> library, Map<String, Float> income){
        List<java.util.concurrent.Future<Object[]>> jobs = new ArrayList<>();
        for(Muster.Aim aim : aims){
            jobs.add(pool().submit(() -> {
                //(-Dops.planlog=stell:1: that stage's dry plan written out, to see why it can't be had)
                boolean show = (aim.item != null ? aim.item.name + ":" + aim.rate : aim.type.name + ":" + aim.makers).equals(System.getProperty("ops.planlog"));
                Plan p = new Plan(show ? System.out : new PrintStream(OutputStream.nullOutputStream()));
                p.gate = true;
                p.widen = true;
                p.dry = true;
                Plan dry;
                try{
                    //(with what the core really gets: the model counts what the map's own machines take on the way, as the drain)
                    dry = aim.item != null ? p.make(me, blocks, allowed, library, aim.item.name, aim.rate, income == null || income.isEmpty() ? null : income)
                        : p.make(me, blocks, allowed, library, aim.type.name, aim.makers, income == null || income.isEmpty() ? null : income);
                }catch(Throwable t){
                    StringWriter w = new StringWriter();
                    t.printStackTrace(new PrintWriter(w));
                    return new Object[]{aim, null, ": " + w.toString().lines().limit(6).reduce("", (a, b) -> a + " | " + b.trim())};
                }
                if(dry == null || dry.model == null || Float.isInfinite(dry.model.first[dry.unit])) return new Object[]{aim, null, p.lacking.isEmpty() ? "" : ", lacking " + p.lacking};
                return new Object[]{aim, dry, ""};
            }));
        }
        for(var job : jobs){
            Object[] r;
            try{
                r = job.get();
            }catch(Exception e){
                continue;
            }
            if(r[1] == null) log.append("    ").append(r[0]).append(": can't be had").append(r[2]).append('\n');
            else stages.add(new Object[]{r[0], r[1]});
        }
    }

    /**
     * What a plan's economy has out at each step from now (units as Lanchester force), played forward by its own model,
     * a moment of the game read as an earlier one of the model by the lag.
     */
    Force[] unitCurve(Plan plan, float lag){
        return unitCurve(plan, lag, plan.model.order);
    }

    /** The same with another order (an empty one: what stands alone turns out). */
    Force[] unitCurve(Plan plan, float lag, List<Machine> order){
        Timeline run = new Timeline(plan.cat, plan.field, order);
        int n = (int)Math.ceil(horizon / step) + 1;
        Force[] out = new Force[n];
        int cap = mindustry.entities.Units.getCap(me);
        int have = 0;
        for(Unit u : me.data().units) if(!u.spawnedByCore && u.type.canAttack) have++;
        for(int i = 0; i < n; i++){
            run.runUntil(i * step / lag);
            Force f = new Force();
            int room = Math.max(0, cap - have);
            for(int k = 0; k < plan.cat.size(); k++){
                if(plan.cat.get(k).kind != Res.Kind.unit || run.made[k] <= 0f) continue;
                UnitType type = Vars.content.unit(plan.cat.get(k).name);
                if(type == null) continue;
                float m = Math.min(run.made[k], room);
                room -= (int)m;
                f.add(type, m);
            }
            out[i] = f;
        }
        return out;
    }

    /** A curve begun later by so many seconds (nothing before). */
    Force[] shifted(Force[] curve, float seconds){
        Force[] out = new Force[curve.length];
        int s = Float.isInfinite(seconds) ? curve.length : Math.round(seconds / step);
        for(int i = 0; i < curve.length; i++) out[i] = i - s >= 0 ? curve[i - s] : new Force();
        return out;
    }

    /**
     * When a defence's turrets stand, by its own model: {times of each module's turrets (seconds), their force, when all
     * of it is up, the whole of its turrets}. A module's turrets are the defence planner's placements of that module.
     */
    Object[] schedule(Plan guard, expansion.defense.Planner.Plan dp, float lag){
        Timeline run = new Timeline(guard.cat, guard.field, guard.model.order);
        run.runUntil(horizon / lag);
        List<Float> times = new ArrayList<>();
        List<Force> forces = new ArrayList<>();
        Force total = new Force();
        float done = 0f;
        for(int i = 0; i < guard.model.order.size(); i++){
            float fin = run.finished[i];
            float at = Float.isNaN(fin) ? Float.POSITIVE_INFINITY : fin * lag;
            done = Math.max(done, at);
            Machine m = guard.model.order.get(i);
            if(!Defence.is(m)) continue;
            String name = m.recipe.name;
            int hash = name.lastIndexOf('#');
            if(hash < 0 || name.startsWith("defence:fed-")) continue;
            int module;
            try{
                module = Integer.parseInt(name.substring(hash + 1));
            }catch(NumberFormatException e){
                continue;
            }
            Force f = new Force();
            for(Placement p : dp.placements){
                if(p.module != module) continue;
                Block b = Vars.content.block(p.block);
                if(p.kind == Placement.Kind.turret && b instanceof Turret t) f.addTurret(t, 1f);
                //(walls take hits in front of them)
                else if(p.kind == Placement.Kind.wall && b != null) f.health += b.health;
            }
            times.add(at);
            forces.add(f);
            if(!Float.isInfinite(at)) total.add(f);
        }
        return new Object[]{times, forces, done, total};
    }

    /** How one way to go played out in one scenario. */
    public static final class Outcome{
        /** When our core fell, and when our push took the other side's cores; infinite for never. */
        public float lost = Float.POSITIVE_INFINITY, won = Float.POSITIVE_INFINITY;
        /** What we have in the field at the end. */
        public Force army = new Force();
        /** Whether what was taken was all the other side's cores (else those our units could get to). */
        public boolean whole = true;
        public int fights;

        @Override
        public String toString(){
            return (Float.isInfinite(lost) ? "" : "core falls at " + Plan.time(lost) + ", ") + (Float.isInfinite(won) ? "" : "their cores fall at " + Plan.time(won) + ", ") + "army " + army.count + ", " + fights + " fights";
        }
    }

    /**
     * One game in the model, a step at a time. Ours: units come home as the curve has them, turrets stand from when the
     * defence's model has them up. Theirs: its army grows at its base as the forecast has it (and as the mirror, in the
     * scenarios that have it), scaled by the posture's fear; the map's waves come at their times. It sends its army once
     * that outweighs what holds our home by sendAt; ours goes out once it outweighs their army and their base's turrets
     * by the posture's edge, walks there, fights, and takes their cores down. A side beaten at home is besieged: the
     * winner's damage a second takes the core down while what the loser turns out fights on.
     */
    Outcome play(Force[] curve, Object[] sched, Force[] mirrored, Force foeGuard, float sendAt, boolean reaches, Forecast forecast){
        Outcome o = new Outcome();
        //(units that can get to the other side's base go out; those that can't, as tanks across deep water, only hold ours)
        Force units = nowReaching.copy(), stay = nowStaying.copy(), fixed = new Force();
        Building home = me.core();
        float coreHp = home == null ? 0f : home.health;
        if(home != null) for(Building b : me.data().buildings) if(b instanceof Turret.TurretBuild t && t.hasAmmo() && b.within(home, 40f * Vars.tilesize)) fixed.addTurret((Turret)b.block, 1f);
        @SuppressWarnings("unchecked")
        List<Float> times = sched == null ? List.of() : (List<Float>)sched[0];
        @SuppressWarnings("unchecked")
        List<Force> forces = sched == null ? List.of() : (List<Force>)sched[1];
        boolean[] up = new boolean[times.size()];
        Force theirs = new Force(), siege = null, away = null, guard = foeGuard.copy();
        float awayAt = 0f, walk = walkTime(), foeCoreHp = 0f;
        boolean engaged = false;
        //(only the cores our units can get to: on a sector whose map opens as its tasks are done, those beyond the edge are
        //not taken, and taking the rest is a part won, not the whole)
        int cores = 0, reachable = 0;
        for(CoreBlock.CoreBuild c : foe.cores()){
            cores++;
            if(!Vars.world.isInMapArea(c.tileX(), c.tileY())) continue;
            reachable++;
            foeCoreHp += c.health;
        }
        boolean whole = reachable == cores;
        float scale = posture.foeScale;
        Force lastCurve = new Force(), lastArmy = new Force(), lastWaves = new Force(), lastMirror = new Force();
        for(int i = 0; i <= forecast.steps; i++){
            float t = i * step;
            Force now = curve[Math.min(i, curve.length - 1)];
            (reaches ? units : stay).add(minus(now, lastCurve));
            lastCurve = now;
            for(int j = 0; j < up.length; j++) if(!up[j] && t >= times.get(j)){
                up[j] = true;
                fixed.add(forces.get(j));
            }
            theirs.add(minus(forecast.army[i], lastArmy).scl(scale));
            lastArmy = forecast.army[i];
            if(mirrored != null && i < mirrored.length){
                theirs.add(minus(mirrored[i], lastMirror).scl(scale));
                lastMirror = mirrored[i];
            }
            //what comes to our home: a wave, and their army once it outweighs what holds our home by sendAt
            Force coming = minus(forecast.waves[i], lastWaves).scl(scale);
            lastWaves = forecast.waves[i];
            if(theirs.count > 0 && theirs.strength() >= sendAt * units.copy().add(stay).add(fixed).add(new Force(0f, coreHp, 0)).strength()){
                coming.add(theirs);
                theirs = new Force();
            }
            if(coming.count > 0){
                if(siege != null) siege.add(coming);
                else siege = fight(units, stay, fixed, coreHp, coming, o);
            }
            if(siege != null){
                if(units.count > 0 || stay.count > 0 || fixed.count > 0) siege = fight(units, stay, fixed, coreHp, siege, o);
                if(siege != null){
                    coreHp -= siege.dps * siegeEfficiency * step;
                    if(coreHp <= 0f){
                        o.lost = t;
                        o.army = units.copy().add(stay);
                        return o;
                    }
                }
            }
            //ours out, with nothing at our door
            if(away == null && siege == null && reaches && units.count > 0 && units.strength() >= posture.pushEdge * Math.max(1f, theirs.copy().add(guard).strength())){
                away = units;
                units = new Force();
                awayAt = t + walk;
                engaged = false;
            }
            if(away != null && t >= awayAt){
                Force against = engaged ? theirs : theirs.copy().add(guard);
                if(against.count > 0 || !engaged){
                    o.fights++;
                    if(against.strength() >= away.strength()){
                        theirs.scl(against.strength() <= 0f ? 1f : (float)Math.sqrt(1f - away.strength() / against.strength()));
                        away = null;
                    }else{
                        away.scl((float)Math.sqrt(1f - against.strength() / Math.max(1f, away.strength())));
                        theirs = new Force();
                        if(!engaged) guard = new Force();
                    }
                    engaged = true;
                }
                if(away != null){
                    foeCoreHp -= away.dps * siegeEfficiency * step;
                    if(foeCoreHp <= 0f && reachable > 0){
                        o.won = t;
                        o.whole = whole;
                        //(and what the economy goes on turning out to the horizon: of two ways that win alike, the stronger after)
                        o.army = away.add(units).add(stay).add(minus(curve[curve.length - 1], now));
                        return o;
                    }
                }
            }
        }
        o.army = (away == null ? units.copy() : away.copy().add(units)).add(stay);
        //past the horizon, valued at its leaf: what the other side has gathered and not yet sent, against what holds our
        //home. One that outweighs it takes the core once it comes, after the siege it needs (a defence that only keeps
        //them waiting is not one that holds them: a part of one up held the core in the model only as long as they waited)
        Force hold = units.copy().add(stay).add(fixed).add(new Force(0f, coreHp, 0));
        Force coming = theirs.copy();
        if(siege != null) coming.add(siege);
        if(coming.count > 0 && coming.strength() >= hold.strength()){
            Force left = coming.copy().scl(hold.strength() <= 0f ? 1f : (float)Math.sqrt(1f - hold.strength() / coming.strength()));
            o.lost = forecast.steps * step + walk + coreHp / Math.max(1f, left.dps * siegeEfficiency);
        }
        return o;
    }

    /**
     * A fight at our home between what holds it (units, turrets, and the core's own health, which takes hits and deals
     * none) and an attacker: null if the attacker is beaten (what is left of ours kept), else what is left of it (ours gone).
     */
    static Force fight(Force units, Force stay, Force fixed, float coreHp, Force attacker, Outcome o){
        o.fights++;
        Force ours = units.copy().add(stay).add(fixed).add(new Force(0f, coreHp, 0));
        float a = attacker.strength(), d = ours.strength();
        if(a < d){
            float keep = (float)Math.sqrt(1f - a / d);
            units.scl(keep);
            stay.scl(keep);
            fixed.scl(keep);
            return null;
        }
        Force left = attacker.copy().scl(d <= 0f ? 1f : (float)Math.sqrt(1f - d / a));
        units.scl(0f);
        stay.scl(0f);
        fixed.scl(0f);
        return left;
    }

    static Force minus(Force a, Force b){
        return new Force(a.dps - b.dps, a.health - b.health, a.count - b.count);
    }

    /** The objective for one game, discounted in time: losing the core worst, sooner worse; their cores taken best, sooner better; else army strength. */
    float score(Outcome o){
        if(!Float.isInfinite(o.lost)) return -1000f * d(o.lost);
        if(!Float.isInfinite(o.won)) return (o.whole ? 300f : 100f) * d(o.won) + posture.perTenfold * log(o.army);
        return posture.perTenfold * log(o.army) * d(horizon);
    }

    static float d(float t){
        return (float)Math.exp(-t / discount);
    }

    static float log(Force f){
        return f.strength() <= 1f ? 0f : (float)Math.log10(f.strength());
    }

    /** Seconds a tank takes from the other side's core to ours. */
    float walkTime(){
        Building a = me.core(), b = foe.core();
        if(a == null || b == null) return 60f;
        float tps = mindustry.content.UnitTypes.stell.speed * 60f / Vars.tilesize;
        return a.dst(b) / Vars.tilesize * 1.35f / tps;
    }

    /** Our fighting units now: those that can get to the other side's base, or those that can't. */
    Force mobileNow(boolean reaching){
        Force f = new Force();
        for(Unit u : me.data().units) if(!u.spawnedByCore && u.type.canAttack && !u.dead() && reachesFoe(u.type) == reaching) f.add(u);
        return f;
    }

    final ObjectMap<UnitType, Boolean> reachCache = new ObjectMap<>();
    /** Our fighting units at the weighing: those that can go out, and those that only hold our home. */
    Force nowReaching = new Force(), nowStaying = new Force();

    synchronized boolean reachesFoe(UnitType type){
        Boolean r = reachCache.get(type);
        if(r == null) reachCache.put(type, r = Reach.toFoe(type, me, foe));
        return r;
    }

    /** The other side's loaded turrets within 40 tiles of its cores: what a push into its base meets besides its units. */
    Force foeGuard(){
        Force f = new Force();
        for(Building b : foe.data().buildings){
            if(!(b instanceof Turret.TurretBuild t) || !t.hasAmmo()) continue;
            boolean near = false;
            for(CoreBlock.CoreBuild c : foe.cores()) if(b.within(c, 40f * Vars.tilesize)) near = true;
            if(near) f.addTurret((Turret)b.block, 1f);
        }
        return f;
    }
}
