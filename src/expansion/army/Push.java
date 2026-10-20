package expansion.army;

import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.ai.types.*;
import mindustry.entities.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.meta.*;

/**
 * A2d, the push: once the allotment says go, the main body is sent at the other side's base, one target after the next,
 * and a reserve stays home for the squad AI to defend with.
 *
 * The squad AI sends every squad big enough to defend whatever of its side is being hit, before anything else ("will
 * always rush to defense", as its own comment says). Against a side that raids lines with a few quick units all game,
 * its whole army chases the raiders round its own base and never goes out (seen against the Brain on lake: fifteen tanks
 * at the unit cap at home for twenty minutes). Here the units sent are given their target themselves, which the squad
 * AI leaves alone; the reserve, the units nearest home, it goes on leading.
 */
public class Push{
    final Army army;
    /** What the main body is sent at, and since when. */
    public @Nullable Building target;
    float since, lastHealth;
    /** Units sent, by id. */
    final IntSet sent = new IntSet();
    /** Targets given up on (not reached, or not worn down, in their time), by position, and until when. */
    final IntFloatMap given = new IntFloatMap();
    /** How long one given up on is left alone: it may be got at another way, or after the turrets round it are gone. */
    static final float forget = 60f * 180f;
    /** For the report: how many targets it went for, and destroyed, and how many waves it sent in. */
    public int targets, destroyed, waves;
    /**
     * Gathering: the main body waits at the staging point, out of reach of the target's turrets, till most of it is there,
     * and then goes in together. (Sent in as each unit came free, it went in by fives and sixes and was shot down by fives
     * and sixes: on atlas 67 lost to the Brain's breaches for nothing.)
     */
    boolean gathering = true;
    @Nullable boolean[] reachable;
    float lastGuard, lastNear;
    @Nullable Vec2 staging;
    float gatherSince;
    final IntSet wave = new IntSet();
    int waveSize;
    static final int minWave = Integer.getInteger("army.minwave", 6);
    static final float gatherRadius = 10f * Vars.tilesize, gatherPatience = 60f * 90f;
    /**
     * What it goes for: first what fights back or makes what fights back, and the cores (a base ground down drill by drill
     * holds out for a whole game: on atlas, 626 of the Brain's blocks in 25 minutes and its core untouched); the rest only
     * once none of those are left.
     */
    static final BlockFlag[] first = {BlockFlag.turret, BlockFlag.factory, BlockFlag.core}, then = {BlockFlag.generator, BlockFlag.battery, BlockFlag.drill};
    /** Of the fighting units, the share kept home as the reserve (at least two). */
    public static float reserve = Float.parseFloat(System.getProperty("army.reserve", "0.2"));
    /** How long a target may take before it is given up, in ticks, unless it is being worn down. */
    static final float patience = 60f * 60f;

    public Push(Army army){
        this.army = army;
    }

    public void update(boolean go){
        Team team = army.team;
        Seq<Unit> fighters = new Seq<>();
        for(int i = 0; i < team.data().units.size; i++){
            Unit u = team.data().units.get(i);
            if(Bait.coreUnit(u) || !u.type.canAttack || u.isPlayer() || !(u.controller() instanceof CommandAI)) continue;
            fighters.add(u);
        }
        if(target != null && (!target.isValid() || target.team == team || target.dead())){
            if(!target.isValid() || target.dead()) destroyed++;
            target = null;
        }
        //held back by the allotment (the other side over all is the stronger), it may still go to open up an ore it wants:
        //that is a fight with the few turrets over it, which the local guard weighs, not one with the whole base
        boolean opening = !go && !want.isEmpty();
        if(!go && opening && target != null && !openingTarget) target = null;
        if((!go && !opening) || fighters.size < 4){
            release(fighters);
            return;
        }
        //a target that has been gone for a long while and is not being worn down is given up for a while (the time to walk
        //there counted in: a core across the map takes the main body more than a minute to get to)
        if(target != null && !gathering && army.clock - since > patience + walk(fighters, target)){
            if(target.health >= lastHealth - 1f){
                given.put(target.pos(), army.clock + forget);
                target = null;
            }else{
                since = army.clock;
                lastHealth = target.health;
            }
        }
        if(target == null){
            if(opening){
                reachable = region(fighters);
                target = opener(strength(fighters) / Allot.edge, fighters.allMatch(Unit::isFlying));
            }else target = pick(fighters);
            openingTarget = opening;
            if(target == null){
                release(fighters);
                return;
            }
            targets++;
            since = army.clock;
            lastHealth = target.health;
            //a wave still strong goes straight on to the next target; one worn down gathers again first
            if(waveAlive(team) < Math.max(minWave, waveSize * 0.4f)) gather();
            else staging = stagingFor(target, team);
        }
        //the reserve: the units nearest home, left to the squad AI
        Building home = team.core();
        int keep = Math.max(2, (int)(fighters.size * reserve));
        if(home != null) fighters.sort(u -> u.dst2(home));
        Seq<Unit> body = new Seq<>();
        for(int i = 0; i < fighters.size; i++){
            Unit u = fighters.get(i);
            CommandAI ai = (CommandAI)u.controller();
            if(i < keep){
                if(sent.remove(u.id)) ai.clearCommands();
                wave.remove(u.id);
                continue;
            }
            //(one given a job of its own by another part of the layer is left to it)
            if(!sent.contains(u.id) && ai.hasCommand()) continue;
            body.add(u);
        }
        if(staging == null) staging = stagingFor(target, team);
        if(gathering){
            //all to the staging point, out of the turrets' reach; in together once most are there
            int there = 0;
            for(Unit u : body){
                CommandAI ai = (CommandAI)u.controller();
                if(u.within(staging, gatherRadius)) there++;
                if(ai.targetPos == null || !ai.targetPos.epsilonEquals(staging, 1f)){
                    ai.commandPosition(staging);
                    sent.add(u.id);
                }
            }
            Seq<Unit> near = body.select(u -> u.within(staging, gatherRadius));
            lastGuard = guard(target, near.contains(Unit::isFlying));
            lastNear = strength(near);
            boolean strong = lastNear >= Allot.edge * lastGuard;
            boolean ready = there >= minWave && there >= body.size * 0.75f && strong;
            //(at the unit cap no more are coming: those there go)
            boolean full = fighters.size >= Units.getCap(team) - 2 && there >= minWave && strong;
            //(waited long, at the cap, and still not strong enough for it: another target, one it can take)
            if(!strong && fighters.size >= Units.getCap(team) - 2 && army.clock - gatherSince > gatherPatience){
                given.put(target.pos(), army.clock + forget);
                target = null;
                gather();
                return;
            }
            if(ready || full || army.clock - gatherSince > gatherPatience && there >= minWave && strong){
                gathering = false;
                wave.clear();
                for(Unit u : body) if(u.within(staging, gatherRadius * 1.5f)) wave.add(u.id);
                waveSize = wave.size;
                waves++;
                since = army.clock;
                lastHealth = target.health;
            }
        }
        if(!gathering){
            //the wave at the target; what is made meanwhile waits at the staging point for the next
            for(Unit u : body){
                CommandAI ai = (CommandAI)u.controller();
                if(wave.contains(u.id)){
                    if(ai.attackTarget != target){
                        ai.commandTarget(target, false);
                        sent.add(u.id);
                    }
                }else if(ai.targetPos == null || !ai.targetPos.epsilonEquals(staging, 1f)){
                    ai.commandPosition(staging);
                    sent.add(u.id);
                }
            }
            //worn down: what is left falls back to gather with the rest
            if(waveAlive(team) < Math.max(2, waveSize * 0.4f)) gather();
        }
    }

    /**
     * What stands guard over a target, in Lanchester strength (damage a second times health): the other side's loaded
     * turrets that reach it, and a few tiles round it. (The allotment's estimate is for the whole map; a breach pair over a
     * furnace kills a wave of six that is "four times stronger" than the other side all over.)
     */
    float guard(Building target, boolean air){
        float dps = 0f, health = 0f;
        for(Threat.Gun g : army.threat.guns){
            if(!g.loaded || (air ? !g.air : !g.ground)) continue;
            if(Mathf.dst(g.x, g.y, target.x, target.y) > g.range + 4f * Vars.tilesize) continue;
            dps += g.dps;
            health += g.health;
        }
        return dps * health;
    }

    static float strength(Iterable<Unit> units){
        float dps = 0f, health = 0f;
        for(Unit u : units){
            dps += u.type.dpsEstimate;
            health += u.health;
        }
        return dps * health;
    }

    /** Back to gathering, at the staging point for the target now. */
    void gather(){
        gathering = true;
        gatherSince = army.clock;
        wave.clear();
        staging = target == null ? null : stagingFor(target, army.team);
    }

    int waveAlive(Team team){
        int n = 0;
        for(int i = 0; i < team.data().units.size; i++) if(wave.contains(team.data().units.get(i).id)) n++;
        return n;
    }

    /**
     * Where the main body gathers before going at a target: on the way from home to it, far enough out that the turrets
     * round it don't reach (the furthest reach of any of the other side's turrets near it, and a few tiles), on open ground.
     */
    @Nullable Vec2 stagingFor(Building target, Team team){
        Building home = team.core();
        if(home == null) return new Vec2(target.x, target.y);
        float reach = 0f;
        for(Building b : Vars.indexer.getEnemy(team, BlockFlag.turret)){
            if(b instanceof mindustry.world.blocks.defense.turrets.BaseTurret.BaseTurretBuild t && b.within(target, t.range() + 30f * Vars.tilesize)) reach = Math.max(reach, t.range() + b.dst(target));
        }
        float back = Math.max(reach, 12f * Vars.tilesize) + 8f * Vars.tilesize;
        float d = home.dst(target);
        if(back >= d) return new Vec2(home.x, home.y);
        Vec2 at = new Vec2();
        //along the line from the target back home: the first open tile from that far out
        for(float t = back; t < d; t += Vars.tilesize){
            at.set(target.x, target.y).lerp(home.x, home.y, t / d);
            var tile = Vars.world.tileWorld(at.x, at.y);
            if(tile != null && !tile.solid() && !tile.floor().isDeep()) return at;
        }
        return new Vec2(home.x, home.y);
    }

    /** Where the main body can get to safely, from home, for the kind most of it is; worked out every half minute. */
    @Nullable boolean[] region;
    float regionAt = -1e9f;
    @Nullable UnitType regionKind;

    @Nullable boolean[] region(Seq<Unit> fighters){
        ObjectIntMap<UnitType> kinds = new ObjectIntMap<>();
        for(Unit u : fighters) kinds.increment(u.type);
        UnitType most = null;
        for(var e : kinds) if(most == null || e.value > kinds.get(most)) most = e.key;
        Building home = army.team.core();
        if(most == null || home == null || most.flying) return null;
        if(region == null || most != regionKind || army.clock - regionAt > 60f * 30f){
            region = Reach.region(most, army.team, home.tileX(), home.tileY());
            regionKind = most;
            regionAt = army.clock;
        }
        return region;
    }

    /** The other side's building nearest the units (the base taken from its edge in), skipping those given up on. */
    @Nullable Building pick(Seq<Unit> fighters){
        reachable = region(fighters);
        float x = 0f, y = 0f;
        for(Unit u : fighters){
            x += u.x;
            y += u.y;
        }
        x /= fighters.size;
        y /= fighters.size;
        //of what it goes for first, the nearest it is strong enough to take with all it has; else the nearest of the rest
        //that it can take; else nothing (the squad AI keeps them, to defend)
        float all = strength(fighters) / Allot.edge;
        boolean air = fighters.allMatch(Unit::isFlying);
        //first what keeps the economy from what it lacks: the turrets over an ore it wants (the tungsten of a map is often
        //all under the other side's guns, and with it every unit of the second tier)
        Building opener = opener(all, air);
        if(opener != null) return opener;
        Building best = nearest(first, x, y, all, air);
        return best != null ? best : nearest(then, x, y, all, air);
    }

    /** Items the economy lacks, whose ore under the other side's turrets is worth opening up. */
    public final ObjectSet<Item> want = new ObjectSet<>();
    /** Whether the target now was taken to open up an ore (and may be gone for while the allotment holds the rest back). */
    boolean openingTarget;
    public int opened;

    /**
     * Of the other side's turrets that reach a tile of an ore it wants, the nearest home that it is strong enough to take
     * and can walk to; null if none.
     */
    @Nullable Building opener(float can, boolean air){
        if(want.isEmpty()) return null;
        Building home = army.team.core();
        if(home == null) return null;
        IntSeq ore = new IntSeq();
        for(var t : Vars.world.tiles){
            Item drop = t.block() == mindustry.content.Blocks.air ? t.drop() : t.solid() ? t.wallDrop() : null;
            if(drop != null && want.contains(drop)) ore.add(t.pos());
        }
        if(ore.isEmpty()) return null;
        Building best = null;
        float bestDst = Float.MAX_VALUE;
        for(Building b : Vars.indexer.getEnemy(army.team, BlockFlag.turret)){
            if(!(b instanceof mindustry.world.blocks.defense.turrets.BaseTurret.BaseTurretBuild t) || given.get(b.pos(), -1f) > army.clock) continue;
            float r2 = (t.range() + 2f * Vars.tilesize) * (t.range() + 2f * Vars.tilesize);
            boolean covers = false;
            for(int i = 0; i < ore.size && !covers; i++){
                int pos = ore.get(i);
                float ox = Point2.x(pos) * Vars.tilesize, oy = Point2.y(pos) * Vars.tilesize;
                if(Mathf.dst2(ox, oy, b.x, b.y) <= r2) covers = true;
            }
            if(!covers || guard(b, air) > can) continue;
            if(reachable != null && !Reach.near(reachable, b.tileX(), b.tileY(), b.block.size / 2 + 2)) continue;
            float d = b.dst2(home);
            if(d < bestDst){
                bestDst = d;
                best = b;
            }
        }
        if(best != null) opened++;
        return best;
    }

    @Nullable Building nearest(BlockFlag[] flags, float x, float y, float can, boolean air){
        Building best = null;
        float bestDst = Float.MAX_VALUE;
        for(BlockFlag flag : flags){
            for(Building b : Vars.indexer.getEnemy(army.team, flag)){
                if(given.get(b.pos(), -1f) > army.clock || b.team == Team.derelict) continue;
                if(guard(b, air) > can) continue;
                //(only what the main body can walk to without going under in slag or deep water)
                if(reachable != null && !Reach.near(reachable, b.tileX(), b.tileY(), b.block.size / 2 + 2)) continue;
                float d = b.dst2(x, y);
                if(d < bestDst){
                    bestDst = d;
                    best = b;
                }
            }
        }
        return best;
    }

    /** Ticks for the slowest of the units to go the straight way to the target, twice over (the way round is longer). */
    static float walk(Seq<Unit> fighters, Building target){
        float slowest = Float.MAX_VALUE, far = 0f;
        for(Unit u : fighters){
            slowest = Math.min(slowest, u.speed());
            far = Math.max(far, u.dst(target));
        }
        return slowest <= 0.01f ? 0f : 2f * far / slowest;
    }

    /** Every unit sent goes back to the squad AI. */
    void release(Seq<Unit> fighters){
        for(Unit u : fighters){
            if(!sent.remove(u.id)) continue;
            if(u.controller() instanceof CommandAI ai) ai.clearCommands();
        }
        sent.clear();
        wave.clear();
        gathering = true;
        gatherSince = army.clock;
    }

    public String report(){
        return (want.isEmpty() ? "" : "wants " + want.toSeq().toString(",", i -> i.name) + " (opening " + opened + "); ") + "push targets " + targets + " destroyed " + destroyed + " given up " + given.size + " waves " + waves + (gathering ? " (gathering)" : " (wave of " + waveSize + " in)") + String.format(" [at the staging point %.3g against the guard %.3g; %d guns known, %d loaded]", lastNear, lastGuard, army.threat.guns.size, army.threat.guns.count(g -> g.loaded));
    }
}
