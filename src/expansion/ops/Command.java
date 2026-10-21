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
    /**
     * (The game is played forward till what lies further off counts a twentieth as much by the discount, three of its time
     * scales: whatever has not come to a fight by twelve minutes, as a defence then a second tier, was worth almost nothing
     * at a twelve-minute horizon, valued by the army it had standing.)
     */
    public float horizon = Float.parseFloat(System.getProperty("ops.horizon", String.valueOf(3f * discount))), step = 10f;
    /** The discount's time scale: what is foretold this many seconds off counts 1/e as much (-Dops.discount). */
    public static float discount = Float.parseFloat(System.getProperty("ops.discount", "600"));
    /**
     * How much slower the game is than the economy model, in the two economy scenarios: the median and the upper part
     * (85th percentile) of what has been seen this game, the first unit of each stage of units chosen against the time the
     * weighing's model had it at (a stage left before it came: at least the time it had run). With two seen before any, as
     * the bench has measured it (93-119% of the model's time on the campaign maps): 1 and 1.15.
     */
    public float[] lags = {1f, 1.15f};
    final List<Float> lagSeen = new ArrayList<>(List.of(1f, 1.15f));
    /** The kind of unit the stage chosen last is to turn out, from when, and when the model had its first. */
    UnitType expecting;
    float expectFrom, expectIn;

    /** What the chosen way is to show (for the lags): when its first unit comes against when the model had it. */
    public void expect(Candidate c){
        expecting = null;
        if(c == null || c.modules > 0 || c.aim.item != null || Float.isInfinite(c.firstUnit)) return;
        expecting = c.aim.type;
        expectFrom = (float)(Vars.state.tick / 60.0);
        expectIn = Math.max(step, c.firstUnit);
    }

    void lagsNow(){
        if(expecting != null){
            //(no unit yet: it has taken at least this long)
            float r = ((float)(Vars.state.tick / 60.0) - expectFrom) / expectIn;
            if(r > 1f) lagSeen.add(r);
            expecting = null;
        }
        List<Float> sorted = new ArrayList<>(lagSeen);
        Collections.sort(sorted);
        lags = new float[]{quantile(sorted, 0.5f), quantile(sorted, 0.85f)};
        if(lags[1] <= lags[0] + 1e-3f) lags = new float[]{lags[0]};
    }

    static float quantile(List<Float> sorted, float q){
        float at = q * (sorted.size() - 1);
        int lo = (int)Math.floor(at), hi = Math.min(sorted.size() - 1, lo + 1);
        return sorted.get(lo) + (sorted.get(hi) - sorted.get(lo)) * (at - lo);
    }
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
    public static int afterTop = Integer.getInteger("ops.atop", 3), strongTop = Integer.getInteger("ops.strong", 2);
    long afterNanos;
    /** How many makers a kind is weighed with, besides one. */
    public static int[] makerCounts = {2, 3, 4, 6};
    /** How many kinds of unit get more makers weighed, at most. */
    public int kinds = Integer.getInteger("ops.kinds", 4);
    public final List<Candidate> weighed = new ArrayList<>();
    public Forecast forecast;
    public final StringBuilder log = new StringBuilder();
    long dpAt;
    /** The other side's fighting units seen at the weighings so far, how many new ones came since the first, and how many its yards would have made at their pace meanwhile. */
    float foeMade, foeExpected = 0f, lastWeighAt = -1f;
    /** Fighting units the other side's yards made since the last weighing (counted as each is made). */
    int foeCreated;

    /**
     * The share of their own pace the other side's yards have been seen to work at: the units that came since the first
     * weighing over those its yards would have made at their pace meanwhile, with one unit's worth of prior that they do
     * (as fast as they could before anything is seen; Laplace's rule of succession for a rate).
     */
    float yardUse(){
        float now = (float)(Vars.state.tick / 60.0);
        float pace = 0f;
        for(Building b : foe.data().buildings){
            if(b.cheating()) continue;
            if(b instanceof mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild yard && yard.currentPlan >= 0) pace += 60f / Math.max(1f, ((mindustry.world.blocks.units.UnitFactory)b.block).plans.get(yard.currentPlan).time);
            else if(b.block instanceof mindustry.world.blocks.units.Reconstructor re) pace += 60f / Math.max(1f, re.constructTime);
        }
        //(the units its yards turned out, counted as they come: those our turrets kill before the next weighing count too)
        if(lastWeighAt >= 0f){
            foeMade += foeCreated;
            foeExpected += Math.max(0f, now - lastWeighAt) * pace;
        }
        foeCreated = 0;
        lastWeighAt = now;
        return Math.min(1f, (foeMade + 1f) / (foeExpected + 1f));
    }

    /** When the other side was first weighed against, and what its buildings cost in all then. */
    float foeFirstSeen = -1f, foeFirstSpend;
    /** Our fastest stage's first unit at the first weighing, and when the other side's first fighting unit came (-1: not yet). */
    float fastestAtStart = -1f, foeFirstUnitAt = -1f;
    /** How fast the other side comes to fight before anything is seen of it, as a share of our fastest (-Dops.mirrorprior). */
    public static float mirrorPrior = Float.parseFloat(System.getProperty("ops.mirrorprior", "0.8"));
    /** Their base's guard when first weighed against, and since when; how fast it grows (a second). */
    Force foeGuardFirst, guardGrowth = new Force();
    float foeGuardSince;

    /** What a side's buildings cost in all (items of every kind). */
    static float spent(Team team){
        float sum = 0f;
        for(Building b : team.data().buildings) for(var st : b.block.requirements) sum += st.amount;
        return sum;
    }

    public Command(Team me, Team foe){
        this.me = me;
        this.foe = foe;
        arc.Events.on(mindustry.game.EventType.UnitCreateEvent.class, e -> {
            if(e.unit == null || !e.unit.type.canAttack || e.unit.spawnedByCore) return;
            if(e.unit.team == foe){
                foeCreated++;
                if(foeFirstUnitAt < 0f) foeFirstUnitAt = (float)(Vars.state.tick / 60.0);
            }
            //(the first of the kind the stage chosen is to turn out: how long it took against the model)
            if(e.unit.team == me && e.unit.type == expecting){
                lagSeen.add(Math.max(0.5f, ((float)(Vars.state.tick / 60.0) - expectFrom) / expectIn));
                expecting = null;
            }
        });
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
        forecast = new Forecast(me, foe, horizon, step, yardUse());
        lagsNow();
        reachCache.clear();
        reachUsCache.clear();
        standing();
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
            //(fighting units it had from the start: it fights from the start)
            for(Unit u : foe.data().units) if(!u.spawnedByCore && u.type.canAttack) foeFirstUnitAt = seen;
        }
        float ours = 0f;
        if(fastest != null){
            for(Machine m : fastest.model.order) for(int c : m.cost) ours += c;
            ours /= Math.max(1f, Float.isInfinite(fastest.model.t) ? horizon : Math.min(fastest.model.t, horizon));
        }
        float spendPace = seen - foeFirstSeen <= 0f || ours <= 0f ? 1f : Math.min(1f, Math.max(0f, spent - foeFirstSpend) / (seen - foeFirstSeen) / ours);
        //(how fast it comes to fight, which is what the mirror is about: its first fighting unit's time against our fastest
        //stage's first unit at the first weighing; none yet, at most as fast as it would have had to be by now; before
        //anything is seen, the faster quarter of what the Brain has been measured at, its first unit at 1.25 times our
        //fastest plan's (53 games: median 1.55))
        if(fastestAtStart < 0f && fastest != null) fastestAtStart = soonest;
        float militaryPace = mirrorPrior;
        if(fastestAtStart > 0f){
            if(foeFirstUnitAt >= 0f) militaryPace = Math.min(1f, fastestAtStart / Math.max(1f, foeFirstUnitAt - foeFirstSeen));
            else if(seen - foeFirstSeen > fastestAtStart) militaryPace = Math.min(militaryPace, fastestAtStart / (seen - foeFirstSeen));
        }
        float pace = Math.min(spendPace, militaryPace);
        boolean builds = fastest != null && pace > 0f;
        List<Scenario> scenarios = new ArrayList<>();
        //(what is chance: how slow our economy runs, whether the other side builds; when it sends its army is its own choice,
        //and each of these is played at every sendAt and counted at the worst of them)
        for(Forecast fc : List.of(forecast)) for(float lag : lags) for(boolean m : builds ? new boolean[]{false, true} : new boolean[]{false}) scenarios.add(new Scenario(lag, m, 0f, fc));
        log.append(String.format("  command: the other side builds at %.0f%% of our fastest pace (spending %.1f items a second since %s against ours %.1f: %.0f%%; its first fighting unit %s against our fastest's %s: %.0f%%)%s\n", pace * 100f, seen - foeFirstSeen <= 0f ? 0f : Math.max(0f, spent - foeFirstSpend) / (seen - foeFirstSeen), Plan.time(foeFirstSeen), ours, spendPace * 100f, foeFirstUnitAt < 0f ? "not yet" : Plan.time(foeFirstUnitAt), Plan.time(fastestAtStart), militaryPace * 100f, builds ? "" : ": no mirror"));
        Force foeGuard = foeGuard();
        //(and how fast it has grown: what its loaded turrets about its cores gained a second since it was first weighed
        //against; a base seen with no turrets at the landing grew three breaches by the time the push the model counted on
        //got there, and the army that was to take it stood at home all game)
        if(foeGuardFirst == null){
            foeGuardFirst = foeGuard.copy();
            foeGuardSince = seen;
        }
        float span = seen - foeGuardSince;
        guardGrowth = span <= 0f ? new Force(foe) : Force.minus(foeGuard, foeGuardFirst).scl(1f / span);
        if(guardGrowth.dps > 0f) log.append(String.format("  command: their base's guard grows %.1f dps and %.0f health a minute\n", guardGrowth.dps * 60f, guardGrowth.health * 60f));
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
                //(theirs, under their rules, and only of the kinds that can get from their base to ours)
                added[i] = Force.minus(with[i], without[i]).as(foe);
                for(var e : added[i].kinds.entries()) if(e.key instanceof UnitType type && !reachesUs(type)) added[i].kinds.put(e.key, 0f);
                added[i].refresh();
            }
            //(at their base as they are made: once sent, they walk, as every army does in the game)
            mirrors.put(lag, added);
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
        //(which stages to plan again on top of each defence, a budget of search: the best alone, and the strongest kinds that
        //can be had, one and two makers, as a defence first then a higher tier is the way that wants it and alone is
        //weighed worst, its first unit coming after any rush)
        List<Object[]> tops = new ArrayList<>();
        for(Candidate c : plain){
            if(tops.size() >= afterTop) break;
            for(Object[] st : stages) if(st[0] == c.aim && !tops.contains(st)) tops.add(st);
        }
        //(the strongest kinds that can be had, every number of makers weighed: a higher tier in numbers is what a defence
        //buys the time for)
        List<Object[]> strong = new ArrayList<>();
        for(Object[] st : stages) if(((Muster.Aim)st[0]).item == null) strong.add(st);
        strong.sort((a, b) -> Float.compare(((Muster.Aim)b[0]).strength, ((Muster.Aim)a[0]).strength));
        Set<UnitType> strongKinds = new HashSet<>();
        for(Object[] st : strong){
            UnitType kind = ((Muster.Aim)st[0]).type;
            if(!strongKinds.contains(kind) && strongKinds.size() >= strongTop) continue;
            strongKinds.add(kind);
            if(!tops.contains(st)) tops.add(st);
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
                if(job != null && on == null && Boolean.getBoolean("ops.all")) log.append("    (").append(aim).append(" could not be planned on top of the defence of ").append(k).append(' ').append(kind).append(")\n");
                weighed.add(cand);
            }
        }
        afterNanos = System.nanoTime() - a0;
        long p1 = System.nanoTime();
        weighed.sort((a, b) -> Float.compare(b.score, a.score));
        log.append(String.format("  command: times: stages %.1f + %.1f s, defences %.1f s, stages after them %.1f s, playing %.1f s\n", (w1 - w0) / 1e9f, (w2 - w1) / 1e9f, (d1 - d0) / 1e9f, afterNanos / 1e9f, (p1 - d1 - afterNanos) / 1e9f));
        log.append(String.format("  command (%s, horizon %s, %d scenarios, our economy %s of the model's pace as seen): theirs at our core by the horizon at most %s (its yards at %.0f%% of their pace, as seen); %s\n", posture, Plan.time(horizon), scenarios.size(), Arrays.toString(lags), forecast.at[forecast.steps], forecast.use * 100f, forecast.notes));
        for(int i = 0; i < Math.min(weighed.size(), 8); i++) log.append("    ").append(weighed.get(i)).append('\n');
        if(weighed.size() > 8) log.append("    (").append(weighed.size() - 8).append(" more)\n");
        if(Boolean.getBoolean("ops.all")){
            StringBuilder head = new StringBuilder("    scenarios (lag/mirror; each at the other side's worst timing):");
            for(Scenario sc : scenarios) head.append(String.format(" %.2f/%s", sc.lag, sc.mirror ? "m" : "-"));
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
        //(going on with the stage being built is left for another way only when what is gained by leaving it, scenario by
        //scenario, is worth it by the posture's own attitude to risk (the worst gain, the mean of the worse half, the mean):
        //of many ways weighed, the best looks better than it is by the model's errors alone (the optimizer's curse), and a
        //stage left for one only a little better somewhere was begun again every minute and none finished; one that has
        //stuck, or that the game has turned against, loses by much in most)
        Candidate keep = null;
        for(Candidate c : weighed) if(c.keep) keep = c;
        Candidate best = weighed.get(0);
        if(keep != null && best != keep){
            float[] gain = new float[best.scores.length];
            for(int i = 0; i < gain.length; i++) gain[i] = best.scores[i] - keep.scores[i];
            float worth = aggregate(gain);
            if(worth <= 0f){
                log.append(String.format("  command: going on with the stage: %s gains %.1f by the posture's measure of risk\n", best.stage(), worth));
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
            //(the other side's best timing against this way: the worst of its sendAts)
            scores[i] = Float.POSITIVE_INFINITY;
            for(float sendAt : sendAts){
                Outcome o = play(units, sched, sc.mirror ? mirrors.get(sc.lag) : null, foeGuard, sendAt, aim.reaches, sc.forecast);
                float v = score(o);
                if(v < scores[i]){
                    scores[i] = v;
                    cand.outcomes[i] = o;
                }
            }
            Outcome o = cand.outcomes[i];
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

    Object[] sched(Map<Float, Object[]> schedules){
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
            Force f = new Force(me);
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
        for(int i = 0; i < curve.length; i++) out[i] = i - s >= 0 ? curve[i - s] : new Force(me);
        return out;
    }

    /**
     * When a defence's turrets stand, by its own model: {times of each module's turrets (seconds), their force, when all
     * of it is up, the whole of its turrets}. A module's turrets are the defence planner's placements of that module, its
     * walls with them (they take hits).
     */
    Object[] schedule(Plan guard, expansion.defense.Planner.Plan dp, float lag){
        Timeline run = new Timeline(guard.cat, guard.field, guard.model.order);
        run.runUntil(horizon / lag);
        List<Float> times = new ArrayList<>();
        List<Force> forces = new ArrayList<>();
        Force total = new Force(me);
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
            Force f = new Force(me);
            for(Placement p : dp.placements){
                if(p.module != module) continue;
                Block b = Vars.content.block(p.block);
                if(p.kind == Placement.Kind.turret && b instanceof Turret t) f.addTurret(t, 1f);
                else if(p.kind == Placement.Kind.wall && b != null) f.add(b, 1f);
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

    /** Sub-steps of a step over which fights are worn down (the square law's equations, stepped). */
    static final int fightSteps = 5;

    /**
     * One game in the model, a step at a time, every fight by the same law (Force, Combat). Ours: units come home as the
     * curve has them, turrets stand from when the defence's model has them up; our home is our units there, our turrets and
     * our core, which takes its share of the hits. Theirs: its army grows at its base as the forecast has it (and as the
     * mirror, in the scenarios that have it), scaled by the posture's fear; its base's guard grows as it has been seen to;
     * the map's waves come at their times. It sends its army once that outweighs what holds our home by sendAt (its
     * choice: the scenarios take the worst for us); the army walks here, by the way's length at its slowest kind's speed.
     * Ours goes out once it outweighs their army and guard by the posture's edge, walks there, and fights what is there,
     * their cores among it. A fight goes on step by step, each side worn down by the other's fire (what comes meanwhile
     * joins in), till one side is gone: our core gone is the game lost, theirs taken the game won.
     */
    Outcome play(Force[] curve, Object[] sched, Force[] mirrored, Force foeGuard, float sendAt, boolean reaches, Forecast forecast){
        Outcome o = new Outcome();
        //(units that can get to the other side's base go out; those that can't, as tanks across deep water, only hold ours)
        Force units = nowReaching.copy(), stay = nowStaying.copy(), fixed = ourTurrets.copy(), core = ourCore.copy();
        @SuppressWarnings("unchecked")
        List<Float> times = sched == null ? List.of() : (List<Float>)sched[0];
        @SuppressWarnings("unchecked")
        List<Force> forces = sched == null ? List.of() : (List<Force>)sched[1];
        boolean[] up = new boolean[times.size()];
        Force theirs = new Force(foe), attackers = new Force(foe), away = null, guard = foeGuard.copy(), cores = foeCores.copy();
        float awayAt = 0f;
        boolean fighting = false, besieging = false;
        float scale = posture.foeScale;
        Force lastCurve = new Force(me), lastArmy = new Force(foe), lastWaves = new Force(foe), lastMirror = new Force(foe);
        //(their army once sent, on its way)
        List<Object[]> incoming = new ArrayList<>();
        for(int i = 0; i <= forecast.steps; i++){
            float t = i * step;
            Force now = curve[Math.min(i, curve.length - 1)];
            (reaches ? units : stay).add(Force.minus(now, lastCurve));
            lastCurve = now;
            for(int j = 0; j < up.length; j++) if(!up[j] && t >= times.get(j)){
                up[j] = true;
                fixed.add(forces.get(j));
            }
            theirs.add(Force.minus(forecast.army[i], lastArmy).scl(scale));
            lastArmy = forecast.army[i];
            if(mirrored != null && i < mirrored.length){
                theirs.add(Force.minus(mirrored[i], lastMirror).scl(scale));
                lastMirror = mirrored[i];
            }
            //(their guard grows as it is seen to, a step's worth)
            if(!guardGrowth.empty()) guard.add(guardGrowth.copy().scl(step));
            //the map's waves, come to our core
            attackers.add(Force.minus(forecast.waves[i], lastWaves).scl(scale));
            lastWaves = forecast.waves[i];
            //their army sent, once it outweighs what holds our home by sendAt
            Force home = units.copy().add(stay).add(fixed).add(core);
            if(!theirs.empty() && theirs.dps > 0f && theirs.strengthAgainst(home) >= sendAt * home.strengthAgainst(theirs)){
                float walk = walkTheirs(theirs);
                if(!Float.isInfinite(walk)) incoming.add(new Object[]{t + walk, theirs});
                theirs = new Force(foe);
            }
            for(int j = incoming.size() - 1; j >= 0; j--) if((Float)incoming.get(j)[0] <= t){
                attackers.add((Force)incoming.get(j)[1]);
                incoming.remove(j);
            }
            //a fight at our home: what has come against all that holds it
            if(!attackers.empty() && attackers.dps > 0f){
                if(!fighting) o.fights++;
                fighting = true;
                wear(attackers, List.of(units, stay, fixed, core), step);
                if(core.health <= 1f){
                    o.lost = t;
                    o.army = units.copy().add(stay);
                    return o;
                }
            }else fighting = false;
            //ours out, with nothing at our door
            if(away == null && attackers.empty() && reaches && !units.empty() && units.dps > 0f){
                Force there = theirs.copy().add(guard);
                if(there.empty() || units.strengthAgainst(there) >= posture.pushEdge * there.strengthAgainst(units)){
                    float walk = walkOurs(units);
                    if(!Float.isInfinite(walk)){
                        away = units;
                        units = new Force(me);
                        awayAt = t + walk;
                    }
                }
            }
            //ours at their base: against their army there, their guard and their cores
            if(away != null && t >= awayAt){
                if(!besieging) o.fights++;
                besieging = true;
                wear(away, List.of(theirs, guard, cores), step);
                if(cores.health <= 1f && !foeCores.empty()){
                    o.won = t;
                    o.whole = foeCoresWhole;
                    //(and what the economy goes on turning out to the horizon: of two ways that win alike, the stronger after)
                    o.army = away.add(units).add(stay).add(Force.minus(curve[curve.length - 1], now));
                    return o;
                }
                if(away.empty() || away.dps <= 0f){
                    away = null;
                    besieging = false;
                }
            }
        }
        o.army = (away == null ? units.copy() : away.copy().add(units)).add(stay);
        return o;
    }

    /**
     * A fight's step: one body against all the parts of another (each part worn down alike, as the fire falls on them by
     * their share of the health), over dt seconds in sub-steps, both firing at once. The bodies are changed.
     */
    static void wear(Force a, List<Force> parts, float dt){
        float h = dt / fightSteps;
        for(int k = 0; k < fightSteps; k++){
            Force b = new Force(parts.get(0).team);
            for(Force p : parts) b.add(p);
            if(a.empty() || b.empty()) return;
            float toB = a.dpsAgainst(b) * h, toA = b.dpsAgainst(a) * h;
            float keepA = Math.max(0f, 1f - toA / Math.max(1e-6f, a.health)), keepB = Math.max(0f, 1f - toB / Math.max(1e-6f, b.health));
            a.scl(keepA);
            for(Force p : parts) p.scl(keepB);
        }
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

    /** Seconds a body takes from the place to another, at its slowest kind's speed along the way (infinite: one cannot). */
    float walk(Force f, Team team, Building from, Building to){
        if(from == null || to == null) return Float.POSITIVE_INFINITY;
        float worst = 0f;
        boolean any = false;
        for(var e : f.kinds){
            if(e.value <= 1e-6f || !(e.key instanceof UnitType type)) continue;
            any = true;
            float[] d = Reach.distance(type, team, from.tileX(), from.tileY());
            float tiles = Float.POSITIVE_INFINITY;
            if(d != null && d.length > 0){
                int w = Vars.world.width(), h = Vars.world.height(), r = to.block.size / 2 + 3;
                for(int dx = -r; dx <= r; dx++) for(int dy = -r; dy <= r; dy++){
                    int x = to.tileX() + dx, y = to.tileY() + dy;
                    if(x >= 0 && y >= 0 && x < w && y < h) tiles = Math.min(tiles, d[x + y * w]);
                }
            }
            worst = Math.max(worst, tiles / Math.max(0.1f, type.speed * 60f / Vars.tilesize));
        }
        return any ? worst : Float.POSITIVE_INFINITY;
    }

    /** Our body's walk to the nearest of their cores; theirs to our core. */
    float walkOurs(Force f){
        Building home = me.core();
        Building target = home == null ? null : nearestFoeCore(home);
        return walk(f, me, home, target);
    }

    float walkTheirs(Force f){
        Building home = me.core();
        Building from = home == null ? null : nearestFoeCore(home);
        return walk(f, foe, from, home);
    }

    Building nearestFoeCore(Building home){
        Building best = null;
        for(CoreBlock.CoreBuild c : foe.cores()){
            if(!Vars.world.isInMapArea(c.tileX(), c.tileY())) continue;
            if(best == null || c.dst2(home) < best.dst2(home)) best = c;
        }
        return best;
    }

    /** Seconds a tank takes from the other side's core to ours (for the mirror's walk; the forecast walks its own). */
    float walkTime(){
        Force one = new Force(foe).add(mindustry.content.UnitTypes.stell, 1f);
        float w = walkTheirs(one);
        return Float.isInfinite(w) ? 60f : w;
    }

    /** Our fighting units now: those that can get to the other side's base, or those that can't. */
    Force mobileNow(boolean reaching){
        Force f = new Force(me);
        for(Unit u : me.data().units) if(!u.spawnedByCore && u.type.canAttack && !u.dead() && reachesFoe(u.type) == reaching) f.add(u);
        return f;
    }

    final ObjectMap<UnitType, Boolean> reachCache = new ObjectMap<>(), reachUsCache = new ObjectMap<>();
    /** Our fighting units at the weighing: those that can go out, and those that only hold our home; our loaded turrets about our core, and the core. */
    Force nowReaching = new Force(), nowStaying = new Force(), ourTurrets = new Force(), ourCore = new Force();
    /** The other side's cores our units can get to (the open part of the map), and whether that is all of them. */
    Force foeCores = new Force();
    boolean foeCoresWhole = true;

    synchronized boolean reachesFoe(UnitType type){
        Boolean r = reachCache.get(type);
        if(r == null) reachCache.put(type, r = Reach.toFoe(type, me, foe));
        return r;
    }

    /** Whether units of a kind of theirs can get from their base to our core. */
    synchronized boolean reachesUs(UnitType type){
        Boolean r = reachUsCache.get(type);
        if(r == null) reachUsCache.put(type, r = Reach.toFoe(type, foe, me));
        return r;
    }

    /** What stands now: our turrets about our core with ammunition, our core; their cores in the open part of the map. */
    void standing(){
        Building home = me.core();
        ourTurrets = new Force(me);
        ourCore = new Force(me);
        if(home != null){
            ourCore.addBuilding(home);
            for(Building b : me.data().buildings) if(b instanceof Turret.TurretBuild t && t.hasAmmo() && b.within(home, 40f * Vars.tilesize)) ourTurrets.addTurret(t);
        }
        foeCores = new Force(foe);
        foeCoresWhole = true;
        for(CoreBlock.CoreBuild c : foe.cores()){
            if(Vars.world.isInMapArea(c.tileX(), c.tileY())) foeCores.addBuilding(c);
            else foeCoresWhole = false;
        }
    }

    /** The other side's loaded turrets within 40 tiles of its cores: what a push into its base meets besides its units. */
    Force foeGuard(){
        Force f = new Force(foe);
        for(Building b : foe.data().buildings){
            if(!(b instanceof Turret.TurretBuild t) || !t.hasAmmo()) continue;
            boolean near = false;
            for(CoreBlock.CoreBuild c : foe.cores()) if(b.within(c, 40f * Vars.tilesize)) near = true;
            if(near) f.addTurret(t);
        }
        return f;
    }
}
