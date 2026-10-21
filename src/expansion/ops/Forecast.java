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
    /**
     * Of its units now, those already out on our side of the map (nearer our core than any of its own by the way there):
     * each with when it gets to our core. They are on their way, not at its base waiting to be sent (counted at its base,
     * an attack at our door had to walk the whole way again in the model first).
     */
    public final java.util.List<Object[]> coming = new java.util.ArrayList<>();
    /** Notes for the log: where the force comes from. */
    public final StringBuilder notes = new StringBuilder();

    final Team me, foe;
    final Building home;
    /** The share of their own pace the other side's yards are counted at (see the constructor). */
    public final float use;

    public Forecast(Team me, Team foe, float horizon, float step){
        this(me, foe, horizon, step, 1f);
    }

    /**
     * With the other side's yards counted at the share of their own pace they have been seen to work at (use, 0 to 1; see
     * Command#yardUse): what a yard does next is not what it does this moment (on lake the sector's ship fabricator had not
     * begun at the landing, counted idle nothing was foreseen, and its eludes took the core at four minutes; counted at its
     * own pace the Brain's starved yards made thirty-six stells in twelve minutes, and basin built walls all game).
     */
    public Forecast(Team me, Team foe, float horizon, float step, float use){
        this(me, foe, horizon, step, use, null);
    }

    /** Each yard's own share, by place (one not in it at use). */
    final arc.struct.IntFloatMap shares;

    float useOf(Building b){
        if(b.cheating()) return 1f;
        return shares == null ? use : shares.get(b.pos(), use);
    }

    public Forecast(Team me, Team foe, float horizon, float step, float use, arc.struct.IntFloatMap shares){
        this.use = use;
        this.shares = shares;
        this.me = me;
        this.foe = foe;
        this.step = step;
        steps = Math.max(1, (int)Math.ceil(horizon / step));
        at = new Force[steps + 1];
        all = new Force[steps + 1];
        army = new Force[steps + 1];
        waves = new Force[steps + 1];
        for(int i = 0; i <= steps; i++){
            at[i] = new Force(foe);
            all[i] = new Force(foe);
            army[i] = new Force(foe);
            waves[i] = new Force(foe);
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
        put(type, n, from, x, y, wave, 0f);
    }

    /** The same, with health beyond the kind's own (shields), in all. */
    void put(UnitType type, float n, float from, float x, float y, boolean wave, float more){
        int made = stepOf(from);
        if(made < 0) return;
        for(int i = made; i <= steps; i++) all[i].add(type, n).addHealth(more);
        boolean gets = reaches(type, x, y);
        if(!wave && gets) for(int i = made; i <= steps; i++) army[i].add(type, n).addHealth(more);
        if(!gets) return;
        int there = stepOf(from + walk(type, x, y));
        if(there < 0) return;
        for(int i = there; i <= steps; i++){
            at[i].add(type, n).addHealth(more);
            if(wave) waves[i].add(type, n).addHealth(more);
        }
    }

    /** Seconds a unit of a kind takes to walk from (x, y) to the core: the way's length (Reach.distance) at its speed. */
    float walk(UnitType type, float x, float y){
        float tilesPerSecond = Math.max(0.1f, type.speed * 60f / Vars.tilesize);
        return way(type, x, y) / tilesPerSecond;
    }

    /** Tiles along the way from (x, y) to our core for a kind (the least within three tiles of it); infinite if none. */
    float way(UnitType type, float x, float y){
        float[] d = ways.get(type);
        if(d == null){
            d = Reach.distance(type, foe, home.tileX(), home.tileY());
            ways.put(type, d == null ? new float[0] : d);
        }
        if(d == null || d.length == 0) return Float.POSITIVE_INFINITY;
        int tx = mindustry.core.World.toTile(x), ty = mindustry.core.World.toTile(y), w = Vars.world.width(), h = Vars.world.height();
        float best = Float.POSITIVE_INFINITY;
        for(int dx = -3; dx <= 3; dx++) for(int dy = -3; dy <= 3; dy++){
            int nx = tx + dx, ny = ty + dy;
            if(nx >= 0 && ny >= 0 && nx < w && ny < h) best = Math.min(best, d[nx + ny * w] + (float)Math.hypot(dx, dy));
        }
        return best;
    }

    final ObjectMap<UnitType, float[]> ways = new ObjectMap<>();

    /** Whether a unit of a kind standing at (x, y) can get to the core. */
    boolean reaches(UnitType type, float x, float y){
        return !Float.isInfinite(way(type, x, y));
    }

    void units(){
        int n = 0, out = 0;
        for(Unit u : foe.data().units){
            if(u.spawnedByCore || !u.type.canAttack || u.dead()) continue;
            float walk = reaches(u.type, u.x, u.y) ? walk(u.type, u.x, u.y) : Float.POSITIVE_INFINITY;
            int there = Float.isInfinite(walk) ? -1 : stepOf(walk);
            for(int i = 0; i <= steps; i++) all[i].add(u);
            if(there >= 0) for(int i = there; i <= steps; i++) at[i].add(u);
            n++;
            if(there < 0) continue;
            if(way(u.type, u.x, u.y) < wayHome(u)){
                coming.add(new Object[]{walk, new Force(foe).add(u)});
                out++;
            }else for(int i = 0; i <= steps; i++) army[i].add(u);
        }
        if(n > 0) notes.append(n).append(" units now").append(out > 0 ? " (" + out + " out on our side)" : "").append("; ");
    }

    /** Tiles along the way from a unit to the nearest of its side's cores (infinite if none it can get to). */
    float wayHome(Unit u){
        float best = Float.POSITIVE_INFINITY;
        int tx = u.tileX(), ty = u.tileY(), w = Vars.world.width();
        for(var c : foe.cores()){
            float[] d = homeWays.get(c.pos() * 31L + u.type.id);
            if(d == null){
                d = Reach.distance(u.type, foe, c.tileX(), c.tileY());
                homeWays.put(c.pos() * 31L + u.type.id, d == null ? new float[0] : d);
            }
            if(d != null && d.length > tx + ty * w && tx >= 0 && ty >= 0 && tx < w) best = Math.min(best, d[tx + ty * w]);
        }
        return best;
    }

    final LongMap<float[]> homeWays = new LongMap<>();

    /**
     * What its factories and refabricators make: a factory turns out its unit at its own pace (one that is not working
     * now is counted as idle); a refabricator turns a unit of the tier below into its own, so it adds the difference. All
     * up to the side's unit cap.
     */
    void factories(){
        //(the cap is of each kind, as the game has it, not of all together)
        int cap = capOfKind(foe);
        ObjectIntMap<UnitType> madeOf = new ObjectIntMap<>();
        ObjectIntMap<String> kinds = new ObjectIntMap<>();
        for(Building b : foe.data().buildings){
            if(b instanceof UnitFactory.UnitFactoryBuild yard && yard.currentPlan >= 0){
                UnitFactory block = (UnitFactory)b.block;
                var plan = block.plans.get(yard.currentPlan);
                //at the share of its pace the yard is seen to work at (one that cheats, at its pace)
                float eff = useOf(b);
                if(eff <= 1e-3f) continue;
                float time = plan.time / eff / 60f;
                float left = (1f - yard.fraction()) * time;
                for(float t = left; t <= steps * step && foe.data().countType(plan.unit) + madeOf.get(plan.unit, 0) < cap; t += time){
                    put(plan.unit, 1f, t, b.x, b.y);
                    madeOf.increment(plan.unit);
                }
                kinds.increment(plan.unit.name);
            }else if(b instanceof Reconstructor.ReconstructorBuild re && b.block instanceof Reconstructor block && block.upgrades.size > 0){
                float eff = useOf(b);
                if(eff <= 1e-3f) continue;
                UnitType[] up = block.upgrades.first();
                float time = block.constructTime / eff / 60f;
                for(float t = time; t <= steps * step && foe.data().countType(up[1]) + madeOf.get(up[1], 0) < cap; t += time){
                    //(the difference: one of the tier above, one fewer of the tier below)
                    put(up[1], 1f, t, b.x, b.y);
                    put(up[0], -1f, t, b.x, b.y);
                    madeOf.increment(up[1]);
                }
                kinds.increment(up[1].name);
            }
        }
        if(kinds.size > 0) notes.append("made by its yards: ").append(kinds).append("; ");
    }

    /**
     * How many of each kind of unit a side may have. The game caps each kind apart (Units.canCreate), and the side of the
     * waves not at all; where there are no waves (two bases, as against the Brain) that side is taken to keep to a
     * player's number of each, as the Brain keeps itself (Brain.unitCap): taken as uncapped, its yards were counted to
     * turn out stells for half an hour without end.
     */
    public static int capOfKind(Team team){
        int game = mindustry.entities.Units.getCap(team);
        Rules rules = Vars.state.rules;
        if(game != Integer.MAX_VALUE || rules.waves) return game;
        int player = Math.max(0, rules.unitCapVariable ? rules.unitCap + team.data().unitCap : rules.unitCap);
        return player > 0 ? player : game;
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
                //(in the campaign, so many times as many as the difficulty has it, as the game's spawner sends them: on hard
                //half as many again)
                if(Vars.state.isCampaign()){
                    float times = Vars.state.getPlanet().campaignRules.difficulty.enemySpawnMultiplier;
                    n = Math.max(1, g.effect == StatusEffects.boss ? (int)(n * times) : Math.round(n * times));
                }
                //(each with the shield its wave gives it, under the side's rules and a guardian's own multiplier)
                float shield = g.getShield(w - 1) * rules.unitHealth(foe) * (g.effect == StatusEffects.boss ? StatusEffects.boss.healthMultiplier : 1f);
                //a guardian has more health and hits harder (the boss effect: health and damage times its multipliers):
                //counted by what that makes of its strength, damage times health
                float worth = g.effect == StatusEffects.boss ? (float)Math.sqrt(StatusEffects.boss.healthMultiplier * StatusEffects.boss.damageMultiplier) : 1f;
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
                    put(g.type, n * worth, t, sx, sy, true, n * shield);
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
