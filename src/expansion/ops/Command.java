package expansion.ops;

import arc.math.*;
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
import mindustry.world.blocks.units.*;

import java.io.*;
import java.util.*;

/**
 * The command layer: what to do next, chosen by looking ahead. Each candidate is a macro-action made of what the side
 * already knows how to do (a stage of the economy that makes a kind of unit with so many makers, with or without a
 * defence put first, paid out of the core's stock or by more economy), played forward in an abstract model to a
 * horizon: the economy model gives when its units come out, the defence planner what turrets it puts down and the cost
 * gives when; the forecast gives what of the other side's force can be at our core by each moment. Every candidate is
 * scored by one objective and the best is taken; the choice is made again at each stage, and sooner when the forecast
 * changes. (Search over scripted macro-actions in a forward model: Puppet Search, Barriga et al. 2015; MCPlan, Chung et
 * al. 2005. Fights by Lanchester's square law.)
 *
 * The objective, the same for any map and either side:
 * - at no moment may what can be at our core outweigh what holds it by more than the posture's margin allows (a plan that
 *   loses the core is worth nothing): the excess is a penalty that outweighs everything else;
 * - then: the earlier our army outweighs what the other side has, at home and in the field, the better (when a push can
 *   go); and the stronger our army at the horizon, the better.
 * The posture sets how much safety is bought and how much the early push is worth.
 */
public final class Command{
    /** How careful: the margin over the threat that holding home needs, and the weights of an early push and of strength. */
    public enum Posture{
        steady(2.0f, 0.5f, 3f),
        balanced(1.4f, 1f, 2f),
        aggressive(1.0f, 2f, 1f);

        /** What holds the core must outweigh what can come by this much (in Lanchester strength). */
        public final float margin;
        /** Score lost a minute the push waits; score a tenfold of army strength at the horizon is worth. */
        public final float perMinute, perTenfold;

        Posture(float margin, float perMinute, float perTenfold){
            this.margin = margin;
            this.perMinute = perMinute;
            this.perTenfold = perTenfold;
        }

        public static Posture of(String name){
            for(Posture p : values()) if(p.name().equals(name)) return p;
            return balanced;
        }
    }

    /** A defence the side could put first: the defence planner's plan for now, paid out of stock or by economy. */
    public interface Defences{
        /** The defence planner's plan, or null; fromStock: no more than the core holds. */
        expansion.defense.Planner.Plan plan(boolean fromStock);
    }

    /** One way to go, as it played out in the model. */
    public static final class Candidate{
        public final Muster.Aim aim;
        /** 0: no defence first; 1: from stock; 2: paid by economy. */
        public final int defence;
        public float score, worst, attackAt = Float.POSITIVE_INFINITY, firstUnit = Float.POSITIVE_INFINITY, defenceAt;
        public Force atHorizon = new Force();
        public int turrets;
        public String note = "";

        Candidate(Muster.Aim aim, int defence){
            this.aim = aim;
            this.defence = defence;
        }

        public String stage(){
            return aim.type.name + ":" + aim.makers;
        }

        @Override
        public String toString(){
            return String.format("%s%s: score %.1f, worst threat/hold %.2f, push at %s, first unit %s, army at horizon %s%s", stage(),
                defence == 0 ? "" : defence == 1 ? " after a defence from stock (" + turrets + " turrets by " + Plan.time(defenceAt) + ")" : " after a defence paid for (" + turrets + " turrets by " + Plan.time(defenceAt) + ")",
                score, worst, Float.isInfinite(attackAt) ? "never" : Plan.time(attackAt), Float.isInfinite(firstUnit) ? "never" : Plan.time(firstUnit), atHorizon, note);
        }
    }

    public final Team me, foe;
    public Posture posture = Posture.balanced;
    /** How far ahead it looks, and the step of the model, in seconds. */
    public float horizon = Float.parseFloat(System.getProperty("ops.horizon", "720")), step = 10f;
    /** How much longer the game takes than the economy model (see unitCurve; -Dops.lag). */
    public static float lag = Float.parseFloat(System.getProperty("ops.lag", "1.1"));
    /** How many kinds of unit it weighs at most. */
    public int kinds = Integer.getInteger("ops.kinds", 4);
    /** The last choice, all candidates weighed, and the forecast it was made against. */
    public final List<Candidate> weighed = new ArrayList<>();
    public Forecast forecast;
    public final StringBuilder log = new StringBuilder();
    /** How many of the other side's buildings stood when it was last looked at: a side whose count grows is building. */
    int foeBlocksBefore = -1;
    boolean foeBuilds;

    public Command(Team me, Team foe){
        this.me = me;
        this.foe = foe;
    }

    /**
     * The best way to go now, weighing up to {@link #kinds} kinds of unit, each with no defence first, with one from
     * stock, and with one paid for by economy. Null if nothing can be had.
     */
    public Candidate decide(List<String> blocks, Set<String> allowed, List<Design> library, Defences defences, Map<String, Float> income){
        long t0 = System.nanoTime();
        log.setLength(0);
        weighed.clear();
        forecast = new Forecast(me, foe, horizon, step);
        int foeBlocks = foe.data().buildings.size;
        if(foeBlocksBefore >= 0 && foeBlocks > foeBlocksBefore + 5) foeBuilds = true;
        foeBlocksBefore = foeBlocks;
        boolean foeHasYards = false;
        for(Building b : foe.data().buildings) if(b.block instanceof UnitFactory) foeHasYards = true;
        //a side that builds and has no yard yet: it will, as fast as we could (counted below from our fastest way)
        boolean mirror = !foeHasYards && (foeBuilds || foeBlocks <= foe.cores().size + 2) && foe.core() != null;

        //the kinds to weigh, each with its economy worked out by the model alone
        //every kind with one maker first (the first tier is what can be had soonest, the second what is strongest, and
        //only the model says which is worth it); then, of those that can be had, more makers (how many is a choice too)
        List<Muster.Aim> all = Muster.aims(me, foe, blocks);
        List<Muster.Aim> aims = new ArrayList<>();
        for(Muster.Aim aim : all) aims.add(aim.withMakers(1));
        List<Object[]> curves = new ArrayList<>();
        weigh(aims, curves, blocks, allowed, library, income);
        List<Muster.Aim> more = new ArrayList<>();
        for(Object[] c : curves){
            Muster.Aim one = (Muster.Aim)c[0];
            for(Muster.Aim aim : all) if(aim.type == one.type && aim.makers > 1) more.add(aim);
        }
        more.sort((a, b) -> Float.compare(b.strength, a.strength));
        if(more.size() > kinds) more = more.subList(0, kinds);
        weigh(more, curves, blocks, allowed, library, income);
        if(curves.isEmpty()){
            log.append("  command: nothing can be had\n");
            return null;
        }
        //the other side, as fast as our fastest
        Force[] mirrored = null;
        if(mirror){
            Force[] fastest = null;
            float best = Float.POSITIVE_INFINITY;
            for(Object[] c : curves){
                Force[] curve = (Force[])c[1];
                float first = firstOf(curve);
                if(first < best){
                    best = first;
                    fastest = curve;
                }
            }
            if(fastest != null){
                float walk = walkTime();
                mirrored = new Force[fastest.length];
                int shift = Math.round(walk / step);
                for(int i = 0; i < fastest.length; i++) mirrored[i] = i - shift >= 0 ? fastest[i - shift] : new Force();
                log.append(String.format("  command: the other side builds and has no yard: counted as making units as fast as we could, walked here in %.0f s\n", walk));
            }
        }

        //defences that could go first
        expansion.defense.Planner.Plan[] dp = new expansion.defense.Planner.Plan[3];
        if(defences != null){
            try{ dp[1] = defences.plan(true); }catch(Throwable t){ dp[1] = null; }
            try{ dp[2] = defences.plan(false); }catch(Throwable t){ dp[2] = null; }
        }

        Force home = holdNow();
        Force foeGuard = foeGuard();
        for(Object[] c : curves){
            Muster.Aim aim = (Muster.Aim)c[0];
            Force[] curve = (Force[])c[1];
            for(int d = 0; d <= 2; d++){
                if(d > 0 && (dp[d] == null || dp[d].placements.isEmpty())) continue;
                Candidate cand = new Candidate(aim, d);
                Force turrets = new Force();
                float ready = 0f;
                if(d > 0){
                    float[] cost = new float[1];
                    turrets = turretsOf(dp[d], cost);
                    cand.turrets = turrets.count;
                    ready = defenceTime(dp[d], cost[0], d == 1, income);
                    cand.defenceAt = ready;
                }
                //(the stage after the defence starts when it is up, and has the stock less what it cost: taken as later by that)
                int shift = Math.round(ready / step);
                float worst = 0f;
                for(int i = 0; i <= forecast.steps; i++){
                    float t = i * step;
                    Force ours = home.copy();
                    Force units = i - shift >= 0 && i - shift < curve.length ? curve[i - shift] : i - shift >= curve.length ? curve[curve.length - 1] : new Force();
                    ours.add(units);
                    if(t >= ready) ours.add(turrets);
                    Force threat = forecast.at[i].copy();
                    if(mirrored != null && i < mirrored.length) threat.add(mirrored[i]);
                    float ratio = threat.strength() / Math.max(1f, ours.strength());
                    worst = Math.max(worst, ratio);
                    if(Float.isInfinite(cand.firstUnit) && units.count > 0) cand.firstUnit = t;
                    //a push can go once the army outweighs all the other side has, and what guards its base, by the edge
                    Force mobile = mobileNow().add(units);
                    Force theirs = forecast.all[i].copy().add(foeGuard);
                    if(mirrored != null && i < mirrored.length) theirs.add(mirrored[i]);
                    if(Float.isInfinite(cand.attackAt) && mobile.count > 0 && mobile.strength() >= Allot.edge * theirs.strength()) cand.attackAt = t;
                    if(i == forecast.steps) cand.atHorizon = mobile;
                }
                cand.worst = worst;
                cand.score = score(cand);
                weighed.add(cand);
            }
        }
        weighed.sort((a, b) -> Float.compare(b.score, a.score));
        log.append(String.format("  command (%s, horizon %s): against %s at our core by the horizon; %s\n", posture, Plan.time(horizon), forecast.at[forecast.steps], forecast.notes));
        for(Candidate c : weighed) log.append("    ").append(c).append('\n');
        log.append(String.format("  command: weighed %d in %.1f s\n", weighed.size(), (System.nanoTime() - t0) / 1e9f));
        return weighed.isEmpty() ? null : weighed.get(0);
    }

    /** Works out by the model alone what each aim's economy has out over time; those that can be had go in the list. */
    void weigh(List<Muster.Aim> aims, List<Object[]> curves, List<String> blocks, Set<String> allowed, List<Design> library, Map<String, Float> income){
        for(Muster.Aim aim : aims){
            Plan p = new Plan(new PrintStream(OutputStream.nullOutputStream()));
            p.gate = true;
            p.widen = true;
            p.dry = true;
            Plan dry;
            try{
                //(with what the core really gets: the model counts what the map's own machines take on the way, as the drain)
                dry = p.make(me, blocks, allowed, library, aim.type.name, aim.makers, income == null || income.isEmpty() ? null : income);
            }catch(Throwable t){
                dry = null;
            }
            if(dry == null || dry.model == null || Float.isInfinite(dry.model.first[dry.unit])){
                log.append("    ").append(aim).append(": can't be had").append(p.lacking.isEmpty() ? "" : ", lacking " + p.lacking).append('\n');
                continue;
            }
            curves.add(new Object[]{aim, unitCurve(dry)});
        }
    }

    /** The one objective (see the class). */
    float score(Candidate c){
        //(how many times over the margin the worst moment is, in doublings: a plan with nothing at home against a wave is
        //worse than one a little short, and both worse than any that holds)
        float excess = c.worst * posture.margin <= 1f ? 0f : 1f + (float)(Math.log(c.worst * posture.margin) / Math.log(2));
        float push = Float.isInfinite(c.attackAt) ? horizon * 1.5f : c.attackAt;
        float army = c.atHorizon.strength() <= 0f ? 0f : (float)Math.log10(c.atHorizon.strength());
        return -100f * excess - posture.perMinute * push / 60f + posture.perTenfold * army;
    }

    /** What of a kind a stage's economy has out at each step from now (Lanchester force), from its own model. */
    Force[] unitCurve(Plan dry){
        Timeline run = new Timeline(dry.cat, dry.field, dry.model.order);
        int n = (int)Math.ceil(horizon / step) + 1;
        Force[] out = new Force[n];
        //(what the game takes against what the model says: the economy bench has the game at 93-119% of the model's time
        //to a unit, so a moment of the game is read as an earlier one of the model)
        int cap = mindustry.entities.Units.getCap(me);
        int have = 0;
        for(Unit u : me.data().units) if(!u.spawnedByCore && u.type.canAttack) have++;
        for(int i = 0; i < n; i++){
            run.runUntil(i * step / lag);
            Force f = new Force();
            int room = Math.max(0, cap - have);
            for(int k = 0; k < dry.cat.size(); k++){
                if(dry.cat.get(k).kind != Res.Kind.unit || run.made[k] <= 0f) continue;
                UnitType type = Vars.content.unit(dry.cat.get(k).name);
                if(type == null) continue;
                float n2 = Math.min(run.made[k], room);
                room -= (int)n2;
                f.add(type, n2);
            }
            out[i] = f;
        }
        return out;
    }

    static float firstOf(Force[] curve){
        for(int i = 0; i < curve.length; i++) if(curve[i].count > 0) return i;
        return Float.POSITIVE_INFINITY;
    }

    /** Seconds a tank takes from the other side's core to ours (for the mirror). */
    float walkTime(){
        Building a = me.core(), b = foe.core();
        if(a == null || b == null) return 60f;
        float tps = mindustry.content.UnitTypes.stell.speed * 60f / Vars.tilesize;
        return a.dst(b) / Vars.tilesize * 1.35f / tps;
    }

    /** Our fighting units, and our loaded turrets within 40 tiles of the core. */
    Force holdNow(){
        Force f = mobileNow();
        Building home = me.core();
        if(home != null) for(Building b : me.data().buildings){
            if(b instanceof Turret.TurretBuild t && t.hasAmmo() && b.within(home, 40f * Vars.tilesize)) f.addTurret((Turret)b.block, 1f);
        }
        return f;
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

    /** The turrets of a defence plan, as force, and what the whole plan costs (summed over items). */
    static Force turretsOf(expansion.defense.Planner.Plan plan, float[] cost){
        Force f = new Force();
        for(Placement p : plan.placements){
            Block b = Vars.content.block(p.block);
            if(b == null) continue;
            for(ItemStack s : b.requirements) cost[0] += s.amount * Vars.state.rules.buildCostMultiplier;
            if(p.kind == Placement.Kind.turret && b instanceof Turret t) f.addTurret(t, 1f);
        }
        return f;
    }

    /**
     * When a defence is up, seconds from now: the core unit's building time for all its blocks, and, paid for by economy,
     * no sooner than what it costs beyond the stock comes in at the income there is.
     */
    float defenceTime(expansion.defense.Planner.Plan plan, float cost, boolean fromStock, Map<String, Float> income){
        float build = 0f;
        float speed = Math.max(0.1f, (me.core() == null ? 1f : ((CoreBlock)me.core().block).unitType.buildSpeed) * Vars.state.rules.buildSpeed(me));
        for(Placement p : plan.placements){
            Block b = Vars.content.block(p.block);
            if(b != null) build += b.buildTime / 60f / speed;
        }
        float wait = 0f;
        if(!fromStock && me.core() != null){
            float stock = 0f, in = 0f;
            for(Item item : Vars.content.items()) stock += me.core().items.get(item);
            if(income != null) for(float v : income.values()) in += v;
            wait = cost > stock ? (cost - stock) / Math.max(0.5f, in) : 0f;
        }
        return Math.max(build, wait) + 15f;
    }
}
