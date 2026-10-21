package expansion.ops;

import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import expansion.army.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.units.*;

/**
 * What of the other side's fighting force can be at one's core, a step at a time from now to a horizon: what it has
 * now, once each unit has walked there; what its unit factories turn out, at their own pace, once walked there; and,
 * on a map of waves, each wave the map's spawn groups send, once walked from where it comes. The force is counted as
 * gathering and never thinning: the worst it could do is come all at once with everything it has by then. Units that
 * cannot get to the core (tanks across deep water) do not count. Nothing here is about a map or a side.
 */
public final class Forecast{
    /** Seconds a step, and how many steps. */
    public final float step;
    public final int steps;
    /** For each step: what can be at the core by then, and what the other side has in all by then (wherever it is). */
    public final Force[] at, all;
    /**
     * The same apart: what the other side's own army has by then (its units and what its yards make), counted where it
     * is; and what of the map's waves has come to our core by then. The first it sends when it chooses, the second come
     * when the map sends them.
     */
    public final Force[] army, waves;
    /** Notes for the log: where the force comes from. */
    public final StringBuilder notes = new StringBuilder();

    final Team me, foe;
    final Building home;
    final ObjectMap<UnitType, boolean[]> reach = new ObjectMap<>();
    final IntMap<float[]> yardsSeen;

    /**
     * Whether a yard is to be counted as idle: not working now, and seen so for a minute (its progress not moved since).
     * Not working at one moment is not idle: on lake the sector's ship fabricator had not begun at the landing, the
     * forecast counted nothing coming, and its eludes took the core at four minutes.
     */
    boolean idle(Building b, float fraction, float efficiency){
        float now = (float)(Vars.state.tick / 60.0);
        float[] seen = yardsSeen.get(b.pos());
        if(seen == null || Math.abs(seen[1] - fraction) > 1e-4f){
            yardsSeen.put(b.pos(), new float[]{now, fraction});
            return false;
        }
        return efficiency <= 0.01f && now - seen[0] >= 60f;
    }

    public Forecast(Team me, Team foe, float horizon, float step){
        this(me, foe, horizon, step, new IntMap<>());
    }

    /**
     * With what was seen of the other side's yards at earlier weighings (by place: {when its progress last moved, its
     * progress then}), kept by the caller: a yard is idle only once it has been seen not to move for a minute.
     */
    public Forecast(Team me, Team foe, float horizon, float step, IntMap<float[]> yardsSeen){
        this.yardsSeen = yardsSeen;
        this.me = me;
        this.foe = foe;
        this.step = step;
        steps = Math.max(1, (int)Math.ceil(horizon / step));
        at = new Force[steps + 1];
        all = new Force[steps + 1];
        army = new Force[steps + 1];
        waves = new Force[steps + 1];
        for(int i = 0; i <= steps; i++){
            at[i] = new Force();
            all[i] = new Force();
            army[i] = new Force();
            waves[i] = new Force();
        }
        home = me.core();
        if(home == null) return;
        units();
        factories();
        waves();
    }

    /** The step a time (seconds from now) falls in, or -1 past the horizon. */
    int stepOf(float t){
        int i = (int)Math.ceil(Math.max(0f, t) / step);
        return i > steps ? -1 : i;
    }

    /** Puts n of a kind in from a time on, arriving at the core after walking from (x, y), in world units. */
    void put(UnitType type, float n, float from, float x, float y){
        put(type, n, from, x, y, false);
    }

    void put(UnitType type, float n, float from, float x, float y, boolean wave){
        int made = stepOf(from);
        if(made < 0) return;
        for(int i = made; i <= steps; i++) all[i].add(type, n);
        boolean gets = reaches(type, x, y);
        if(!wave && gets) for(int i = made; i <= steps; i++) army[i].add(type, n);
        if(!gets) return;
        int there = stepOf(from + walk(type, x, y));
        if(there < 0) return;
        for(int i = there; i <= steps; i++){
            at[i].add(type, n);
            if(wave) waves[i].add(type, n);
        }
    }

    /** Seconds a unit of a kind takes to walk from (x, y) to the core: the way as the crow flies, a third again for the bends. */
    float walk(UnitType type, float x, float y){
        float tilesPerSecond = Math.max(0.1f, type.speed * 60f / Vars.tilesize);
        float bend = type.flying ? 1f : 1.35f;
        return Mathf.dst(x, y, home.x, home.y) / Vars.tilesize * bend / tilesPerSecond;
    }

    /** Whether a unit of a kind standing at (x, y) can get to the core. */
    boolean reaches(UnitType type, float x, float y){
        if(type.flying) return true;
        boolean[] r = reach.get(type);
        if(r == null){
            r = Reach.region(type, foe, home.tileX(), home.tileY());
            reach.put(type, r == null ? new boolean[0] : r);
        }
        if(r == null || r.length == 0) return false;
        return Reach.near(r, mindustry.core.World.toTile(x), mindustry.core.World.toTile(y), 3);
    }

    void units(){
        int n = 0;
        for(Unit u : foe.data().units){
            if(u.spawnedByCore || !u.type.canAttack || u.dead()) continue;
            int there = reaches(u.type, u.x, u.y) ? stepOf(walk(u.type, u.x, u.y)) : -1;
            for(int i = 0; i <= steps; i++) all[i].add(u);
            if(there >= 0) for(int i = 0; i <= steps; i++) army[i].add(u);
            if(there >= 0) for(int i = there; i <= steps; i++) at[i].add(u);
            n++;
        }
        if(n > 0) notes.append(n).append(" units now; ");
    }

    /**
     * What its factories and refabricators make: a factory turns out its unit at its own pace (one that is not working
     * now is counted as idle); a refabricator turns a unit of the tier below into its own, so it adds the difference. All
     * up to the side's unit cap.
     */
    void factories(){
        int cap = mindustry.entities.Units.getCap(foe);
        int have = 0;
        for(Unit u : foe.data().units) if(!u.spawnedByCore && u.type.canAttack) have++;
        float room = Math.max(0, cap - have);
        float made = 0f;
        ObjectIntMap<String> kinds = new ObjectIntMap<>();
        for(Building b : foe.data().buildings){
            if(b instanceof UnitFactory.UnitFactoryBuild yard && yard.currentPlan >= 0){
                UnitFactory block = (UnitFactory)b.block;
                var plan = block.plans.get(yard.currentPlan);
                //a factory seen making nothing for a minute (starved, unpowered) makes nothing; one that is, at its rate (one not
                //working at this moment but not seen idle for long, at its full rate)
                if(!b.cheating() && idle(b, yard.fraction(), yard.efficiency)) continue;
                float time = plan.time / Math.max(0.01f, b.cheating() || yard.efficiency <= 0.01f ? 1f : Math.min(1f, yard.efficiency)) / 60f;
                float left = (1f - yard.fraction()) * time;
                for(float t = left; t <= steps * step && made < room; t += time){
                    put(plan.unit, 1f, t, b.x, b.y);
                    made++;
                }
                kinds.increment(plan.unit.name);
            }else if(b instanceof Reconstructor.ReconstructorBuild re && b.block instanceof Reconstructor block && block.upgrades.size > 0){
                if(!b.cheating() && idle(b, re.fraction(), re.efficiency)) continue;
                UnitType[] up = block.upgrades.first();
                float time = block.constructTime / 60f;
                for(float t = time; t <= steps * step; t += time){
                    //(the difference: one of the tier above, one fewer of the tier below)
                    put(up[1], 1f, t, b.x, b.y);
                    put(up[0], -1f, t, b.x, b.y);
                }
                kinds.increment(up[1].name);
            }
        }
        if(kinds.size > 0) notes.append("made by its yards: ").append(kinds).append("; ");
    }

    /** The map's waves, for the side that sends them: each wave's units, from its spawns (air from the map edge where the map has it so). */
    void waves(){
        Rules rules = Vars.state.rules;
        if(!rules.waves || foe != rules.waveTeam || rules.spawns.isEmpty()) return;
        float mult = Vars.state.isCampaign() ? Vars.state.getPlanet().campaignRules.difficulty.waveTimeMultiplier : 1f;
        float spacing = Math.max(1f, rules.waveSpacing * mult / 60f);
        float next = rules.waveTimer ? Vars.state.wavetime / 60f : Float.POSITIVE_INFINITY;
        Seq<Tile> spawns = Vars.spawner.getSpawns();
        int sent = 0;
        for(int w = Vars.state.wave; ; w++){
            float t = next + (w - Vars.state.wave) * spacing;
            if(t > steps * step || (rules.winWave > 0 && w > rules.winWave)) break;
            for(SpawnGroup g : rules.spawns){
                if(g.type == null) continue;
                int n = g.getSpawned(w - 1);
                if(n <= 0) continue;
                //a guardian has more health and hits harder: counted as two
                float worth = g.effect == StatusEffects.boss ? 2f : 1f;
                for(Tile s : spawns){
                    if(g.spawn != -1 && g.spawn != s.pos()) continue;
                    float sx = s.worldx(), sy = s.worldy();
                    if(g.type.flying && !rules.airUseSpawns){
                        //from the map edge beyond the spawn, as the game's spawner sends flyers
                        float angle = Angles.angle(Vars.world.unitWidth() / 2f, Vars.world.unitHeight() / 2f, sx, sy);
                        float far = Math.max(Vars.world.unitWidth(), Vars.world.unitHeight()) * Mathf.sqrt2;
                        sx = Mathf.clamp(Vars.world.unitWidth() / 2f + Angles.trnsx(angle, far), 0, Vars.world.unitWidth());
                        sy = Mathf.clamp(Vars.world.unitHeight() / 2f + Angles.trnsy(angle, far), 0, Vars.world.unitHeight());
                    }
                    put(g.type, n * worth, t, sx, sy, true);
                    sent += n;
                }
            }
        }
        if(sent > 0) notes.append(String.format("waves from %d (next in %.0f s, every %.0f s): %d units; ", Vars.state.wave, next, spacing, sent));
    }

    /** What can be at the core this many seconds from now. */
    public Force atCore(float t){
        int i = stepOf(t);
        return at[i < 0 ? steps : i];
    }

    /** What the other side has in all this many seconds from now. */
    public Force total(float t){
        int i = stepOf(t);
        return all[i < 0 ? steps : i];
    }
}
