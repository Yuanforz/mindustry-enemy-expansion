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
        /** The defence planner's plan for now (its own sizing against what is coming), or null. */
        expansion.defense.Planner.Plan plan();

        Collection<String> feedable();
    }

    /** One way to go, as it played out in the model. */
    public static final class Candidate{
        public final Muster.Aim aim;
        /** How many modules of the defence go first (0: none, Integer.MAX_VALUE: all it plans). */
        public final int modules;
        public float score, firstUnit = Float.POSITIVE_INFINITY, defenceDone;
        /** Whether it does what the map's tasks ask. */
        public boolean task;
        public int turrets;
        /** The scenario worst for it, as it played out. */
        public Outcome outcome;

        Candidate(Muster.Aim aim, int modules){
            this.aim = aim;
            this.modules = modules;
        }

        public String stage(){
            return aim.item != null ? aim.item.name + ":" + aim.rate : aim.type.name + ":" + aim.makers;
        }

        @Override
        public String toString(){
            String d = modules == 0 ? "" : String.format(" after a defence of %s (%d turrets, all up by %s)", modules == Integer.MAX_VALUE ? "all modules" : modules + " module(s)", turrets, Float.isInfinite(defenceDone) ? "never" : Plan.time(defenceDone));
            return String.format("%s%s%s: score %.1f, first unit %s; at worst: %s", stage(), d, task ? " (a task)" : "", score, Float.isInfinite(firstUnit) ? "never" : Plan.time(firstUnit), outcome);
        }
    }

    /** One scenario: how much slower the game goes than the model, whether the other side also builds yards as fast as we could, when it sends its army. */
    static final class Scenario{
        final float lag, sendAt;
        final boolean mirror;

        Scenario(float lag, boolean mirror, float sendAt){
            this.lag = lag;
            this.mirror = mirror;
            this.sendAt = sendAt;
        }
    }

    public final Team me, foe;
    public Posture posture = Posture.balanced;
    /** How far ahead it looks, and the step of the model, in seconds. */
    public float horizon = Float.parseFloat(System.getProperty("ops.horizon", "720")), step = 10f;
    /** The discount's time scale: what is foretold this many seconds off counts 1/e as much (-Dops.discount). */
    public static float discount = Float.parseFloat(System.getProperty("ops.discount", "600"));
    /** How much slower the game is than the economy model, in the two economy scenarios (the bench has it at 0.93-1.19). */
    public static float[] lags = {1f, 1.3f};
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
    /** How many makers a kind is weighed with, besides one. */
    public static int[] makerCounts = {2, 3, 4, 6};
    /** How many kinds of unit get more makers weighed, at most. */
    public int kinds = Integer.getInteger("ops.kinds", 4);
    public final List<Candidate> weighed = new ArrayList<>();
    public Forecast forecast;
    public final StringBuilder log = new StringBuilder();
    long dpAt;

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
        long t0 = System.nanoTime();
        log.setLength(0);
        weighed.clear();
        forecast = new Forecast(me, foe, horizon, step);

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

        //defences of so many modules, each with the economy that pays for and feeds it, worked out by the model
        long d0 = System.nanoTime();
        expansion.defense.Planner.Plan dp = null;
        if(defences != null){
            try{
                dp = defences.plan();
                dpAt = System.nanoTime();
            }catch(Throwable t){
                log.append("  command: the defence planner failed: ").append(t).append('\n');
            }
        }
        List<Object[]> guards = new ArrayList<>();
        guards.add(new Object[]{0, null});
        if(dp != null && !dp.placements.isEmpty()){
            int modules = 0;
            for(Placement p : dp.placements) modules = Math.max(modules, p.module + 1);
            for(int k : new int[]{1, 2, Integer.MAX_VALUE}){
                if(k != Integer.MAX_VALUE && k >= modules && k > 1) continue;
                Plan p = new Plan(new PrintStream(OutputStream.nullOutputStream()));
                p.gate = true;
                p.dry = true;
                p.maxModules = k;
                p.gateDepth = 2;
                p.income = income;
                Plan dry = null;
                try{
                    dry = p.defend(me, blocks, allowed, dp, defences.feedable());
                }catch(Throwable t){
                    log.append("  command: a defence of ").append(k).append(" could not be weighed: ").append(t).append('\n');
                }
                if(dry != null && dry.model != null) guards.add(new Object[]{k, dry});
            }
        }else if(dp != null) log.append("  command: the defence planner has nothing: ").append(String.join("; ", dp.notes)).append('\n');

        long d1 = System.nanoTime();
        List<Scenario> scenarios = new ArrayList<>();
        for(float lag : lags) for(boolean m : new boolean[]{false, true}) for(float s : sendAts) scenarios.add(new Scenario(lag, m, s));
        Force foeGuard = foeGuard();
        //(the mirror, the defences and each stage's units under each lag: worked out once)
        //(what our fastest stage adds over what our standing makers turn out anyway: what the other side could add, as fast;
        //its own standing yards are in the forecast already)
        //(the other side as fast as the model allows, whatever our own economy's lag: how slow ours turns out says nothing
        //of how fast theirs is)
        Map<Float, Force[]> mirrors = new HashMap<>();
        for(float lag : lags){
            if(fastest == null){
                mirrors.put(lag, null);
                continue;
            }
            Force[] with = unitCurve(fastest, 1f), without = unitCurve(fastest, 1f, List.of());
            Force[] added = new Force[with.length];
            for(int i = 0; i < with.length; i++){
                added[i] = minus(with[i], without[i]);
                if(added[i].count < 0 || added[i].strength() < 0f) added[i] = new Force();
            }
            mirrors.put(lag, shifted(added, walkTime()));
        }

        for(Object[] g : guards){
            int k = (Integer)g[0];
            Plan guard = (Plan)g[1];
            Map<Float, Object[]> schedules = new HashMap<>();
            if(guard != null) for(float lag : lags) schedules.put(lag, schedule(guard, dp, lag));
            for(Object[] c : stages){
                Muster.Aim aim = (Muster.Aim)c[0];
                Plan stage = (Plan)c[1];
                Candidate cand = new Candidate(aim, k);
                float[] scores = new float[scenarios.size()];
                float worst = Float.POSITIVE_INFINITY;
                Map<Float, Force[]> curves = new HashMap<>();
                for(float lag : lags) curves.put(lag, unitCurve(stage, lag));
                for(int i = 0; i < scenarios.size(); i++){
                    Scenario sc = scenarios.get(i);
                    Object[] sched = guard == null ? null : schedules.get(sc.lag);
                    float done = sched == null ? 0f : (Float)sched[2];
                    //(one core unit builds both: the stage after the defence is all up)
                    Force[] units = shifted(curves.get(sc.lag), done);
                    Outcome o = play(units, sched, sc.mirror ? mirrors.get(sc.lag) : null, foeGuard, sc.sendAt, aim.reaches);
                    scores[i] = score(o);
                    if(scores[i] < worst){
                        worst = scores[i];
                        cand.outcome = o;
                    }
                }
                cand.score = aggregate(scores);
                //(a way that does a task: worth what the task is, discounted by when it is done)
                if(aim.task){
                    float done = stage.model.first[stage.unit] * lags[0] + cand.defenceDone;
                    cand.score += taskWorth * d(done);
                    cand.task = true;
                }else if(k > 0 && taskDefence){
                    cand.score += taskWorth * d(cand.defenceDone);
                    cand.task = true;
                }
                if(sched(schedules) != null){
                    Object[] s = sched(schedules);
                    cand.turrets = ((Force)s[3]).count;
                    cand.defenceDone = (Float)s[2];
                }
                Force[] base = shifted(curves.get(lags[0]), cand.defenceDone);
                for(int i = 0; i < base.length; i++) if(base[i].count > 0){ cand.firstUnit = i * step; break; }
                weighed.add(cand);
            }
        }
        long p1 = System.nanoTime();
        weighed.sort((a, b) -> Float.compare(b.score, a.score));
        log.append(String.format("  command: times: stages %.1f + %.1f s, defences %.1f s (planner %.1f s), playing %.1f s\n", (w1 - w0) / 1e9f, (w2 - w1) / 1e9f, (d1 - d0) / 1e9f, (dpAt - d0) / 1e9f, (p1 - d1) / 1e9f));
        log.append(String.format("  command (%s, horizon %s, %d scenarios): theirs at our core by the horizon at most %s; %s\n", posture, Plan.time(horizon), scenarios.size(), forecast.at[forecast.steps], forecast.notes));
        for(int i = 0; i < Math.min(weighed.size(), 8); i++) log.append("    ").append(weighed.get(i)).append('\n');
        if(weighed.size() > 8) log.append("    (").append(weighed.size() - 8).append(" more)\n");
        log.append(String.format("  command: weighed %d ways in %d scenarios in %.1f s\n", weighed.size(), scenarios.size(), (System.nanoTime() - t0) / 1e9f));
        if(Boolean.getBoolean("ops.time")) log.append(Took.report());
        return weighed.isEmpty() ? null : weighed.get(0);
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
                Plan p = new Plan(new PrintStream(OutputStream.nullOutputStream()));
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
    Outcome play(Force[] curve, Object[] sched, Force[] mirrored, Force foeGuard, float sendAt, boolean reaches){
        Outcome o = new Outcome();
        Force units = mobileNow(), fixed = new Force();
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
        for(CoreBlock.CoreBuild c : foe.cores()) foeCoreHp += c.health;
        float scale = posture.foeScale;
        Force lastCurve = new Force(), lastArmy = new Force(), lastWaves = new Force(), lastMirror = new Force();
        for(int i = 0; i <= forecast.steps; i++){
            float t = i * step;
            Force now = curve[Math.min(i, curve.length - 1)];
            units.add(minus(now, lastCurve));
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
            if(theirs.count > 0 && theirs.strength() >= sendAt * units.copy().add(fixed).add(new Force(0f, coreHp, 0)).strength()){
                coming.add(theirs);
                theirs = new Force();
            }
            if(coming.count > 0){
                if(siege != null) siege.add(coming);
                else siege = fight(units, fixed, coreHp, coming, o);
            }
            if(siege != null){
                if(units.count > 0 || fixed.count > 0) siege = fight(units, fixed, coreHp, siege, o);
                if(siege != null){
                    coreHp -= siege.dps * siegeEfficiency * step;
                    if(coreHp <= 0f){
                        o.lost = t;
                        o.army = units;
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
                    if(foeCoreHp <= 0f){
                        o.won = t;
                        //(and what the economy goes on turning out to the horizon: of two ways that win alike, the stronger after)
                        o.army = away.add(units).add(minus(curve[curve.length - 1], now));
                        return o;
                    }
                }
            }
        }
        o.army = away == null ? units : away.copy().add(units);
        return o;
    }

    /**
     * A fight at our home between what holds it (units, turrets, and the core's own health, which takes hits and deals
     * none) and an attacker: null if the attacker is beaten (what is left of ours kept), else what is left of it (ours gone).
     */
    static Force fight(Force units, Force fixed, float coreHp, Force attacker, Outcome o){
        o.fights++;
        Force ours = units.copy().add(fixed).add(new Force(0f, coreHp, 0));
        float a = attacker.strength(), d = ours.strength();
        if(a < d){
            float keep = (float)Math.sqrt(1f - a / d);
            units.scl(keep);
            fixed.scl(keep);
            return null;
        }
        Force left = attacker.copy().scl(d <= 0f ? 1f : (float)Math.sqrt(1f - d / a));
        units.scl(0f);
        fixed.scl(0f);
        return left;
    }

    static Force minus(Force a, Force b){
        return new Force(a.dps - b.dps, a.health - b.health, a.count - b.count);
    }

    /** The objective for one game, discounted in time: losing the core worst, sooner worse; their cores taken best, sooner better; else army strength. */
    float score(Outcome o){
        if(!Float.isInfinite(o.lost)) return -1000f * d(o.lost);
        if(!Float.isInfinite(o.won)) return 300f * d(o.won) + posture.perTenfold * log(o.army);
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

    Force mobileNow(){
        Force f = new Force();
        for(Unit u : me.data().units) if(!u.spawnedByCore && u.type.canAttack && !u.dead()) f.add(u);
        return f;
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
