package expansion.army;

import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.ai.types.*;
import mindustry.game.*;
import mindustry.gen.*;

/**
 * A2e, holding home as one army: the game's squad AI defends by squads, each the units that happen to stand together,
 * each going on its own to whatever of its side is being hit; units come out of several factories and go to the fight by
 * twos and threes, and are beaten by twos and threes (Lanchester's square law: a force split in two is worth half of
 * itself together). Here, with the other side's units come near home:
 * - stronger than they are (with the turrets of ours that reach where they are), every unit of ours that nothing else
 *   leads goes at them together;
 * - weaker, every one falls back to the core, where whatever stands round it fights with them, and waits for them there;
 * - with none near, those standing about are drawn together by the core, so the squad AI finds them as one squad.
 * The main body the push has sent out is left to it. Nothing here is about a map or a side.
 */
public class Hold{
    final Army army;
    /** Units it gave an order, by id. */
    final IntSet held = new IntSet();
    /** For the report. */
    public int engaged, fellBack;
    public String state = "quiet";
    /** How far from home (its core and turrets) the other side's units count as come near, in tiles. */
    public static float near = Float.parseFloat(System.getProperty("army.near", "35"));

    public Hold(Army army){
        this.army = army;
    }

    public void update(Push push){
        Team team = army.team;
        Building home = team.core();
        if(home == null) return;
        Seq<Unit> mine = new Seq<>();
        for(int i = 0; i < team.data().units.size; i++){
            Unit u = team.data().units.get(i);
            if(Bait.coreUnit(u) || !u.type.canAttack || u.isPlayer() || !(u.controller() instanceof CommandAI ai)) continue;
            if(push != null && push.sent.contains(u.id)) continue;
            //(one given a job by another part of the layer is left to it)
            if(!held.contains(u.id) && ai.hasCommand()) continue;
            mine.add(u);
        }
        //the other side's fighting units near anything of ours that fights or the core
        Seq<Unit> foes = new Seq<>();
        float r = near * Vars.tilesize;
        for(var data : Vars.state.teams.active){
            if(data.team == team || data.team == Team.derelict) continue;
            for(Unit e : data.units){
                if(!e.type.canAttack || Bait.coreUnit(e)) continue;
                if(e.within(home, r) || nearOurs(e.x, e.y, r)) foes.add(e);
            }
        }
        if(foes.isEmpty()){
            state = "quiet";
            gather(mine, home);
            return;
        }
        float fx = 0f, fy = 0f;
        for(Unit e : foes){
            fx += e.x;
            fy += e.y;
        }
        Vec2 at = new Vec2(fx / foes.size, fy / foes.size);
        float theirs = Push.strength(foes);
        float ours = Push.strength(mine) + ourGuard(at.x, at.y, foes.contains(Unit::isFlying));
        if(ours >= theirs){
            state = "engaging " + foes.size;
            engaged++;
            for(Unit u : mine){
                CommandAI ai = (CommandAI)u.controller();
                if(ai.targetPos == null || !ai.targetPos.within(at, 4f * Vars.tilesize)){
                    ai.commandPosition(at);
                    held.add(u.id);
                }
            }
        }else{
            state = "falling back from " + foes.size;
            fellBack++;
            for(Unit u : mine){
                CommandAI ai = (CommandAI)u.controller();
                if(u.within(home, 6f * Vars.tilesize)){
                    //at home: let it fight what comes (the squad AI defends from here)
                    if(held.remove(u.id)) ai.clearCommands();
                    continue;
                }
                if(ai.targetPos == null || !ai.targetPos.within(home, 2f * Vars.tilesize)){
                    ai.commandPosition(new Vec2(home.x, home.y));
                    held.add(u.id);
                }
            }
        }
    }

    /** With no one near: those standing apart are drawn to the core, and let go there, together. */
    void gather(Seq<Unit> mine, Building home){
        float pull = 10f * Vars.tilesize;
        for(Unit u : mine){
            CommandAI ai = (CommandAI)u.controller();
            if(u.within(home, pull)){
                if(held.remove(u.id)) ai.clearCommands();
                continue;
            }
            //(one the squad AI has going somewhere is let be; only those standing about are drawn in)
            if(!held.contains(u.id) && (ai.hasCommand() || ai.attackTarget != null || u.isShooting)) continue;
            if(ai.targetPos == null || !ai.targetPos.within(home, 5f * Vars.tilesize)){
                ai.commandPosition(new Vec2(home.x + Mathf.range(3f * Vars.tilesize), home.y + Mathf.range(3f * Vars.tilesize)));
                held.add(u.id);
            }
        }
    }

    /** Whether a turret of ours stands within r of a place. */
    boolean nearOurs(float x, float y, float r){
        for(Building b : Vars.indexer.getFlagged(army.team, mindustry.world.meta.BlockFlag.turret)) if(b.within(x, y, r)) return true;
        return false;
    }

    /** Our loaded turrets that reach a place, in Lanchester strength (damage a second times health). */
    float ourGuard(float x, float y, boolean air){
        float dps = 0f, health = 0f;
        for(Building b : Vars.indexer.getFlagged(army.team, mindustry.world.meta.BlockFlag.turret)){
            if(!(b instanceof mindustry.world.blocks.defense.turrets.Turret.TurretBuild t) || !t.hasAmmo()) continue;
            var turret = (mindustry.world.blocks.defense.turrets.Turret)b.block;
            if(air ? !turret.targetAir : !turret.targetGround) continue;
            if(!b.within(x, y, turret.range + 4f * Vars.tilesize)) continue;
            dps += Threat.gun(t, turret).dps;
            health += b.health;
        }
        return dps * health;
    }

    /** Everything it holds goes back to the squad AI. */
    public void release(){
        for(int i = 0; i < army.team.data().units.size; i++){
            Unit u = army.team.data().units.get(i);
            if(held.remove(u.id) && u.controller() instanceof CommandAI ai) ai.clearCommands();
        }
        held.clear();
    }

    public String report(){
        return "hold " + state + " (engaged " + engaged + ", fell back " + fellBack + ")";
    }
}
