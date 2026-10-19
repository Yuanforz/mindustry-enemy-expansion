package expansion.army;

import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.ai.types.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.world.blocks.defense.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.meta.*;

/**
 * A2, operations: a few of its quick units taken from the squad AI for jobs it does not do. Taking one is giving it an
 * order (the squad AI leaves alone any unit that has one); giving it back is letting the order run out, or ending it.
 *
 * - Harass: what the other side has that its turrets do not cover, or barely (drills, fabricators, generators, the
 *   lines between), gone for by two or three quick units at a time from where the turrets do not reach.
 * - Cut: the piece of a turret's supply that every way back to its sources passes through ({@link Threat.Cut}),
 *   destroyed so the turret fires only what it holds.
 *
 * A unit sent on a job that is down to a third of its health, or whose job is done, is sent home, and handed back to
 * the squad AI when it gets there.
 */
public class Ops{
    static final int harass = 0, cut = 1, home = 2;
    static final String[] names = {"harass", "cut", "home"};

    static class Job{
        int kind;
        Unit unit;
        @Nullable Building target;
        float began, expected, health;
    }

    final Army army;
    final IntMap<Job> jobs = new IntMap<>();
    final IntSet gone = new IntSet();
    /** What it has done, for the report: jobs begun of each kind, and targets destroyed. */
    public final int[] begun = new int[3];
    public int destroyed, lost, routes;
    /** At most how many units are out on jobs at once, and how many go together. */
    public static int most = Integer.getInteger("army.opsmost", 6), group = Integer.getInteger("army.opsgroup", 3);

    public Ops(Army army){
        this.army = army;
    }

    public void update(){
        Team team = army.team;
        Building core = team.core();
        if(core == null) return;
        float now = army.clock;
        //jobs that are over
        IntSeq over = new IntSeq();
        for(var entry : jobs){
            Job job = entry.value;
            Unit unit = job.unit;
            if(!unit.isValid() || unit.dead || !(unit.controller() instanceof CommandAI ai)){
                if(!unit.isValid() || unit.dead){
                    lost++;
                    if(job.kind != home) army.review.add(unit.type, job.expected, job.health / Math.max((now - job.began) / 60f, 1f));
                }
                over.add(entry.key);
                continue;
            }
            if(job.kind == home){
                //home, or the order run out: back to the squad AI
                if(!ai.hasCommand() || unit.within(core, Vars.tilesize * 14f) || now - job.began > 60f * 60f){
                    ai.clearCommands();
                    over.add(entry.key);
                }
                continue;
            }
            boolean done = job.target == null || !job.target.isValid();
            //(counted once a target, though several went for it)
            if(done && job.target != null && gone.add(job.target.id)) destroyed++;
            if(done || unit.health < unit.maxHealth / 3f || now - job.began > 60f * 90f || ai.targetPos == null && ai.attackTarget == null && ai.commandQueue.isEmpty() && !ai.isAttacking()){
                //its job done or given up, or itself worn down: home, and what it took set against what was expected
                army.review.add(unit.type, job.expected, (job.health - unit.health) / Math.max((now - job.began) / 60f, 1f));
                job.kind = home;
                job.began = now;
                job.target = null;
                ai.clearCommands();
                ai.commandPosition(Tmp.v1.set(core.x, core.y));
            }
        }
        for(int i = 0; i < over.size; i++) jobs.remove(over.get(i));

        if(jobs.size >= most) return;
        Seq<Unit> free = new Seq<>();
        for(int i = 0; i < team.data().units.size; i++){
            Unit unit = team.data().units.get(i);
            if(jobs.containsKey(unit.id) || unit.spawnedByCore || !unit.type.canAttack || !unit.type.targetGround || !quick(unit)) continue;
            if(!(unit.controller() instanceof CommandAI ai) || ai.hasCommand() || ai.isAttacking()) continue;
            if(unit.health < unit.maxHealth * 0.7f) continue;
            free.add(unit);
        }
        if(free.size < 2) return;

        if(army.flags.cut){
            for(Threat.Cut c : army.threat.cuts){
                if(jobs.size >= most || free.size < 2) break;
                if(taken(c.build)) continue;
                if(send(free, c.build, cut)) break;
            }
        }
        if(army.flags.harass && jobs.size < most && free.size >= 2){
            Building target = harassTarget(free.first());
            if(target != null) send(free, target, harass);
        }
    }

    /** Whether a unit is quick enough to be sent on a job of its own: what flies or hovers, and anything as fast. */
    static boolean quick(Unit unit){
        return unit.type.flying || unit.type.hovering && unit.type.speed >= 1.2f || unit.type.speed >= 1.2f;
    }

    boolean taken(Building target){
        for(var job : jobs.values()) if(job.target == target) return true;
        return false;
    }

    /**
     * Sends a group of free units against a target, if they can get at it from where the turrets do little to them:
     * where they would stand to fire, the damage a second they would take there is no more than they can stand for a while.
     */
    boolean send(Seq<Unit> free, Building target, int kind){
        Building core = army.team.core();
        Unit first = free.min(u -> u.dst2(target));
        if(first == null || core == null) return false;
        int n = Math.min(group, free.size);
        float danger = exposure(first, target, n);
        Vec2 stand = lastStand.cpy();
        //they come out of it with half their health between them
        if(danger > first.maxHealth * n * 0.5f) return false;
        //the way there round the turrets, which can only be worse than the straight way's estimate where that was wrong
        Route route = Route.find(army.threat, first, stand.x, stand.y, army.flags.micro && Dodger.agile(first));
        if(route == null) return false;
        float factor = army.flags.review ? army.review.factor(first.type) : 1f;
        if(lastRate * (target.health / Math.max(first.type.dpsEstimate * n, 1f) + 2f) + route.damage * 2f * factor > first.maxHealth * n * 0.5f) return false;
        routes++;
        free.sort(u -> u.dst2(target));
        for(int i = 0; i < n; i++){
            Unit unit = free.get(i);
            if(!(unit.controller() instanceof CommandAI ai)) continue;
            //by the way round the turrets to where they reach least, then at it
            ai.commandPosition(route.points.first());
            for(int p = 1; p < route.points.size; p++) ai.commandQueue(route.points.get(p));
            ai.commandQueue(target);
            Job job = new Job();
            job.kind = kind;
            job.unit = unit;
            job.target = target;
            job.began = army.clock;
            job.expected = lastRate / n;
            job.health = unit.health;
            jobs.put(unit.id, job);
            begun[kind]++;
        }
        free.removeAll(u -> jobs.containsKey(u.id));
        return true;
    }

    /** The damage a second the units would take where they stand, at the last {@link #exposure}. */
    float lastRate;
    final Vec2 lastStand = new Vec2();

    /**
     * The damage a group of units of one kind would take, all together, going for a target: the turrets' and the other
     * side's units' damage a second where they would stand to fire at it (the least of a ring of places round it at the
     * edge of their reach) over the time it takes them to destroy it, and what the turrets reaching the straight way
     * there from where they are would do to them on the way, there and back.
     */
    float exposure(Unit first, Building target, int n){
        boolean dodging = army.flags.micro && Dodger.agile(first);
        float range = first.type.range * 0.85f, least = Float.MAX_VALUE;
        for(int i = 0; i < 12; i++){
            float x = target.x + Angles.trnsx(i * 30f, range), y = target.y + Angles.trnsy(i * 30f, range);
            float d = army.threat.danger(x, y, first.type, dodging) + army.threat.unitDanger(x, y, first.type.flying);
            if(d < least){
                least = d;
                lastStand.set(x, y);
            }
        }
        float factor = army.flags.review ? army.review.factor(first.type) : 1f;
        least *= factor;
        lastRate = least;
        float seconds = target.health / Math.max(first.type.dpsEstimate * n, 1f) + 2f;
        //on the way: a step every three tiles, each taking what the unit's speed takes it
        float way = 0f, stepTime = Vars.tilesize * 3f / Math.max(first.type.speed, 0.1f) / 60f;
        float dst = first.dst(lastStand);
        for(float d = 0f; d < dst; d += Vars.tilesize * 3f){
            float x = Mathf.lerp(first.x, lastStand.x, d / dst), y = Mathf.lerp(first.y, lastStand.y, d / dst);
            way += army.threat.danger(x, y, first.type, dodging) * stepTime;
        }
        return least * seconds + way * 2f * factor;
    }

    /** The other side's building most worth hitting that its turrets cover least, for a few units of a kind. */
    @Nullable Building harassTarget(Unit unit){
        Building best = null;
        float bestScore = 0f;
        int n = group;
        for(BlockFlag flag : worth){
            for(Building build : Vars.indexer.getEnemy(army.team, flag)){
                if(taken(build)) continue;
                if(exposure(unit, build, n) > unit.maxHealth * n * 0.5f) continue;
                float value = flag == BlockFlag.factory ? 3f : flag == BlockFlag.generator ? 2.5f : flag == BlockFlag.drill ? 2f : 1f;
                float score = value / (1f + build.dst(unit) / (Vars.tilesize * 60f)) / (1f + build.health / 2000f);
                if(score > bestScore){
                    bestScore = score;
                    best = build;
                }
            }
        }
        return best;
    }

    static final BlockFlag[] worth = {BlockFlag.factory, BlockFlag.generator, BlockFlag.drill, BlockFlag.battery};

    public String report(){
        return "ops harass " + begun[harass] + " cut " + begun[cut] + " killed " + destroyed + " lost " + lost;
    }
}
