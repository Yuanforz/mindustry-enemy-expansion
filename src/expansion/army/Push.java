package expansion.army;

import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.ai.types.*;
import mindustry.game.*;
import mindustry.gen.*;
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
    /** Targets given up on (not reached, or not worn down, in their time), by position. */
    final IntSet given = new IntSet();
    /** For the report: how many targets it went for, and destroyed. */
    public int targets, destroyed;
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
        if(!go || fighters.size < 4){
            release(fighters);
            return;
        }
        //a target that has been gone for a long while and is not being worn down is given up
        if(target != null && army.clock - since > patience){
            if(target.health >= lastHealth - 1f){
                given.add(target.pos());
                target = null;
            }else{
                since = army.clock;
                lastHealth = target.health;
            }
        }
        if(target == null){
            target = pick(fighters);
            if(target == null){
                release(fighters);
                return;
            }
            targets++;
            since = army.clock;
            lastHealth = target.health;
        }
        //the reserve: the units nearest home, left to the squad AI
        Building home = team.core();
        int keep = Math.max(2, (int)(fighters.size * reserve));
        if(home != null) fighters.sort(u -> u.dst2(home));
        for(int i = 0; i < fighters.size; i++){
            Unit u = fighters.get(i);
            CommandAI ai = (CommandAI)u.controller();
            if(i < keep){
                if(sent.remove(u.id) && ai.attackTarget == target) ai.clearCommands();
                continue;
            }
            //(one given a job of its own by another part of the layer is left to it)
            if(!sent.contains(u.id) && ai.hasCommand()) continue;
            if(ai.attackTarget != target){
                ai.commandTarget(target, false);
                sent.add(u.id);
            }
        }
    }

    /** The other side's building nearest the units (the base taken from its edge in), skipping those given up on. */
    @Nullable Building pick(Seq<Unit> fighters){
        float x = 0f, y = 0f;
        for(Unit u : fighters){
            x += u.x;
            y += u.y;
        }
        x /= fighters.size;
        y /= fighters.size;
        Building best = nearest(first, x, y);
        return best != null ? best : nearest(then, x, y);
    }

    @Nullable Building nearest(BlockFlag[] flags, float x, float y){
        Building best = null;
        float bestDst = Float.MAX_VALUE;
        for(BlockFlag flag : flags){
            for(Building b : Vars.indexer.getEnemy(army.team, flag)){
                if(given.contains(b.pos()) || b.team == Team.derelict) continue;
                float d = b.dst2(x, y);
                if(d < bestDst){
                    bestDst = d;
                    best = b;
                }
            }
        }
        return best;
    }

    /** Every unit sent goes back to the squad AI. */
    void release(Seq<Unit> fighters){
        for(Unit u : fighters){
            if(!sent.remove(u.id)) continue;
            if(u.controller() instanceof CommandAI ai && (ai.attackTarget == target || target == null)) ai.clearCommands();
        }
        sent.clear();
    }

    public String report(){
        return "push targets " + targets + " destroyed " + destroyed;
    }
}
