package expansion.army;

import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.ai.types.*;
import mindustry.content.*;
import mindustry.entities.units.*;
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.world.blocks.ConstructBlock.*;
import mindustry.world.blocks.defense.turrets.ItemTurret.*;

import java.lang.reflect.*;

/**
 * A2b, the harasser with the core unit: cheap walls put down just inside the other side's turrets' reach, for the turrets
 * to spend their ammunition on.
 *
 * A turret fires at units first and at buildings when there are none, so a wall at the edge of its reach draws its fire
 * whenever no unit is near. A breach does three tenths of its damage to buildings (its beryllium shot does 19 to a wall,
 * less the wall's armour of 2), so a beryllium wall (six beryllium, 520 health) takes about thirty shots, each a
 * beryllium of the turret's: five times what the wall cost, and a turret fed by a drill's line gets a beryllium a
 * second or so while it fires one and a half. A turret that has spent its store on walls fires at the next units only
 * as fast as its line brings it more.
 *
 * The core unit puts the walls down from outside the turret's reach: it builds as far as 27 tiles off and a breach
 * reaches 24. It is not shot at in any case (it can't be targeted unless it carries something), so this costs it
 * nothing but its time and the beryllium. Nothing may be built within 50 tiles of the other side's cores, so only
 * turrets out beyond that are open to it: outposts and the turrets guarding drills.
 *
 * Its own squads would come running to defend a wall of its own under fire (the squad AI goes to whatever of its own
 * is damaged), so the walls are kept off the squad AI's list of what is being damaged.
 */
public class Bait{
    /** A place a wall is kept: by which turret, the wall's tile, and where the core unit stands to build it. */
    public static class Spot{
        public Building turret;
        public int x, y;
        public final Vec2 stand = new Vec2();
        boolean up;
    }

    final Army army;
    public final Seq<Spot> spots = new Seq<>();
    /** How many turrets it keeps a wall by at once. */
    public static int most = Integer.getInteger("army.baitspots", 3);
    /** How much beryllium it keeps in the core for other things. */
    public static int reserve = Integer.getInteger("army.baitreserve", 300);
    /** What it did: walls begun, and the other side's ammunition spent on its walls. */
    public int walls, drained;
    /** The other side's ammunition spent on the core unit flying by with something in its hold, and how often it was lost. */
    public int decoyed, decoysLost;
    final IntIntMap lastAmmo = new IntIntMap();
    float lastPlan = -1000f;

    public Bait(Army army){
        this.army = army;
    }

    /** Whether a unit is a core unit (the one a core gives the side that builds). */
    public static boolean coreUnit(Unit unit){
        return unit.spawnedByCore || unit.type == UnitTypes.evoke || unit.type == UnitTypes.incite || unit.type == UnitTypes.emanate;
    }

    /** How long the mod's economy lends it its builder at a time, and how long it lets it be after. */
    static final float lendTime = 60f * 45f, restTime = 60f * 30f;
    float restUntil;

    /**
     * The unit that does it: the team's core unit, if it has one and it is not busy building something else. One that the
     * mod's economy leads is only borrowed while it has nothing to do, and for at most {@link #lendTime} at a time: its
     * building comes first.
     */
    public @Nullable Unit worker(){
        for(int i = 0; i < army.team.data().units.size; i++){
            Unit unit = army.team.data().units.get(i);
            if(!coreUnit(unit)) continue;
            if(unit.controller() instanceof BaitAI bait){
                if(bait.previous instanceof expansion.ExpansionAI && army.clock - bait.since > lendTime){
                    unit.controller(bait.previous);
                    restUntil = army.clock + restTime;
                    continue;
                }
                return unit;
            }
            if(unit.isPlayer()) continue;
            //busy with something of its own side's: let be
            if(unit.controller() instanceof expansion.ExpansionAI ai ? !ai.idle() || army.clock < restUntil : unit.plans().size > 0) continue;
            BaitAI bait = new BaitAI(this);
            bait.previous = unit.controller();
            bait.since = army.clock;
            unit.controller(bait);
            return unit;
        }
        return null;
    }

    /** Gives the core unit back to what led it before, when baiting is over. */
    public void release(){
        for(int i = 0; i < army.team.data().units.size; i++){
            Unit unit = army.team.data().units.get(i);
            if(unit.controller() instanceof BaitAI bait){
                if(bait.previous != null) unit.controller(bait.previous);
                else unit.resetController();
            }
        }
    }

    /** Every frame: the other side's ammunition spent on its walls counted, and its walls kept off the squad AI's list. */
    public void track(){
        for(Threat.Gun gun : army.threat.guns){
            if(!(gun.build instanceof ItemTurretBuild t) || !t.isValid()) continue;
            int before = lastAmmo.get(t.id, t.totalAmmo);
            if(t.totalAmmo < before && t.target instanceof Building target && target.team == army.team && isBait(target)) drained += before - t.totalAmmo;
            if(t.totalAmmo < before && t.target instanceof Unit target && target.team == army.team && target.controller() instanceof BaitAI) decoyed += before - t.totalAmmo;
            lastAmmo.put(t.id, t.totalAmmo);
        }
        //a wall counted once, when it goes up (its plan may be put in again and again before it does)
        for(Spot spot : spots){
            boolean now = standing(spot);
            if(now && !spot.up) walls++;
            spot.up = now;
        }
        unlist();
        //the core unit lost with a wall in its hold
        Unit worker = null;
        for(int i = 0; i < army.team.data().units.size; i++) if(army.team.data().units.get(i).controller() instanceof BaitAI) worker = army.team.data().units.get(i);
        if(lastWorker != null && lastWorker != worker && lastWorker.dead && lastLoaded) decoysLost++;
        lastWorker = worker;
        lastLoaded = worker instanceof Payloadc pay && pay.hasPayload();
    }

    @Nullable Unit lastWorker;
    boolean lastLoaded;

    static boolean isBait(Building build){
        return build.block == Blocks.berylliumWall || build instanceof ConstructBuild c && c.current == Blocks.berylliumWall;
    }

    static Field damagedSet;

    /** Takes its walls off the squad AI's list of what is being damaged, so that no squad goes to them. */
    @SuppressWarnings("unchecked")
    void unlist(){
        var rts = army.team.data().rtsAi;
        if(rts == null) return;
        try{
            if(damagedSet == null){
                damagedSet = mindustry.ai.RtsAI.class.getDeclaredField("damagedSet");
                damagedSet.setAccessible(true);
            }
            ObjectSet<Building> set = (ObjectSet<Building>)damagedSet.get(rts);
            if(set.size > 0) set.removeAll(Seq.with(set).select(Bait::isBait));
        }catch(Exception e){
            throw new RuntimeException(e);
        }
    }

    /** Twice a second: spots by turrets gone are dropped, and a new one found now and then. */
    public void plan(){
        Unit unit = worker();
        if(unit == null) return;
        Building core = army.team.core();
        if(core == null) return;
        spots.removeAll(s -> !s.turret.isValid());
        if(army.flags.bait && spots.size < most && army.clock - lastPlan > 120f){
            lastPlan = army.clock;
            choose(core);
        }
    }

    boolean standing(Spot spot){
        Tile tile = Vars.world.tile(spot.x, spot.y);
        return tile != null && tile.build != null && tile.build.team == army.team && isBait(tile.build);
    }

    /** The nearest of the other side's turrets that a wall can be put down by: fed by items, and far enough from its cores. */
    void choose(Building core){
        float bestDst = Float.MAX_VALUE;
        Spot best = null;
        for(Threat.Gun gun : army.threat.guns){
            if(!gun.items || !gun.ground || !(gun.build instanceof ItemTurretBuild) || spots.contains(s -> s.turret == gun.build)) continue;
            float d = gun.build.dst(core);
            if(d >= bestDst) continue;
            float away = Angles.angle(gun.x, gun.y, core.x, core.y);
            for(int i = 0; i < 7; i++){
                float angle = away + (i % 2 == 0 ? 1 : -1) * ((i + 1) / 2) * 20f;
                float far = gun.range - Vars.tilesize * 1.5f;
                int wx = mindustry.core.World.toTile(gun.x + Angles.trnsx(angle, far)), wy = mindustry.core.World.toTile(gun.y + Angles.trnsy(angle, far));
                if(!Build.validPlace(Blocks.berylliumWall, army.team, wx, wy, 0)) continue;
                //it stands out of reach of all of them, near enough to build
                float sx = wx * Vars.tilesize + Angles.trnsx(angle, Vars.tilesize * 12f), sy = wy * Vars.tilesize + Angles.trnsy(angle, Vars.tilesize * 12f);
                if(army.threat.covered(sx, sy, true, Vars.tilesize * 2f)) continue;
                bestDst = d;
                best = new Spot();
                best.turret = gun.build;
                best.x = wx;
                best.y = wy;
                best.stand.set(sx, sy);
                break;
            }
        }
        if(best != null) spots.add(best);
    }

    /** The spot it should go to: the nearest whose wall is down; the first if all are standing. */
    @Nullable Spot next(Unit unit){
        Spot open = null;
        float bestDst = Float.MAX_VALUE;
        for(Spot spot : spots){
            if(standing(spot)) continue;
            float d = spot.stand.dst2(unit);
            if(d < bestDst){
                bestDst = d;
                open = spot;
            }
        }
        return open != null ? open : spots.isEmpty() ? null : spots.first();
    }

    /** Puts a wall's plan in if it is not standing, there is beryllium to spare and it is near enough. */
    void build(Unit unit, Spot spot){
        Building core = army.team.core();
        if(core == null || core.items.get(Items.beryllium) < reserve) return;
        if(standing(spot) || unit.plans().size > 0) return;
        Tile tile = Vars.world.tile(spot.x, spot.y);
        if(tile == null || !Build.validPlace(Blocks.berylliumWall, army.team, spot.x, spot.y, 0)) return;
        if(!unit.within(tile.worldx(), tile.worldy(), unit.type.buildRange - Vars.tilesize)) return;
        unit.addBuild(new BuildPlan(spot.x, spot.y, 0, Blocks.berylliumWall));
    }

    public String report(){
        return "bait walls " + walls + " drained " + drained + " spots " + spots.size + (army.flags.decoy ? " decoyed " + decoyed + " lost " + decoysLost : "");
    }

    /** The core unit's controller while it baits: to the spot whose wall is down, and the wall put down. */
    public static class BaitAI extends AIController{
        final Bait bait;
        /** What led it before, and since when it baits. */
        @Nullable mindustry.entities.units.UnitController previous;
        float since;

        BaitAI(Bait bait){
            this.bait = bait;
        }

        @Override
        public void updateMovement(){
            Spot spot = bait.next(unit);
            boolean wallDown = spot != null && !bait.standing(spot);
            if(bait.army.flags.decoy && !wallDown && decoy()) return;
            if(spot == null){
                Building core = unit.closestCore();
                if(core != null) moveTo(core, Vars.tilesize * 4f);
                return;
            }
            moveTo(spot.stand, Vars.tilesize * 1f);
            bait.build(unit, spot);
        }

        /** Where it keeps the wall it picks up, by its core. */
        int stockX = -1, stockY = -1;
        int side = 1;
        float turnAt;

        /**
         * The fly-by: with a wall in its hold (a core unit carrying something can be shot at, and is not otherwise), round
         * the edge of a turret's reach at full speed, stepping out of the way of each shot as the army's quick units do.
         * Every shot that misses is ammunition gone, and this works on turrets by the other side's cores too, where no wall
         * may be put down. It goes home when hurt.
         * @return whether it is doing this
         */
        boolean decoy(){
            Building core = unit.closestCore();
            if(core == null || !(unit instanceof Payloadc pay)) return false;
            if(unit.health < unit.maxHealth * 0.5f){
                moveTo(core, Vars.tilesize * 3f);
                return true;
            }
            if(!pay.hasPayload()){
                //a wall by the core, put down and picked up
                if(stockX < 0){
                    Building enemyCore = unit.closestEnemyCore();
                    float away = enemyCore == null ? 0f : Angles.angle(enemyCore.x, enemyCore.y, core.x, core.y);
                    stockX = mindustry.core.World.toTile(core.x + Angles.trnsx(away, Vars.tilesize * 5f));
                    stockY = mindustry.core.World.toTile(core.y + Angles.trnsy(away, Vars.tilesize * 5f));
                }
                Tile tile = Vars.world.tile(stockX, stockY);
                if(tile == null) return false;
                if(tile.build != null && tile.build.block == Blocks.berylliumWall && tile.build.team == unit.team){
                    moveTo(tile.build, 2f);
                    if(unit.within(tile.build, Vars.tilesize * 1.5f)) Call.pickedBuildPayload(unit, tile.build, true);
                }else if(tile.build == null && core.items.get(Items.beryllium) >= reserve){
                    moveTo(tile, Vars.tilesize * 3f);
                    if(unit.plans().size == 0 && Build.validPlace(Blocks.berylliumWall, unit.team, stockX, stockY, 0)) unit.addBuild(new BuildPlan(stockX, stockY, 0, Blocks.berylliumWall));
                }else{
                    stockX = -1;
                    return false;
                }
                return true;
            }
            //the nearest of the other side's turrets with ammunition that fires at what flies
            Threat.Gun aim = null;
            float bestDst = Float.MAX_VALUE;
            for(Threat.Gun gun : bait.army.threat.guns){
                if(!gun.items || !gun.air || !gun.loaded) continue;
                float d = gun.build.dst(core);
                if(d < bestDst){
                    bestDst = d;
                    aim = gun;
                }
            }
            if(aim == null) return false;
            float radius = aim.range - Vars.tilesize * 1.5f;
            float now = Angles.angle(aim.x, aim.y, unit.x, unit.y), dist = unit.dst(aim.x, aim.y);
            if(dist > aim.range + Vars.tilesize * 6f){
                //on the way there: to the edge of its reach on the near side, round the reach of the others
                Tmp.v1.trns(Angles.angle(aim.x, aim.y, core.x, core.y), aim.range + Vars.tilesize * 2f).add(aim.x, aim.y);
                moveTo(Tmp.v1, Vars.tilesize * 2f);
                return true;
            }
            //round it, turning back now and then: what aims ahead of where it goes then misses
            if(bait.army.clock > turnAt){
                side = -side;
                turnAt = bait.army.clock + 60f * (1.5f + (unit.id % 5) * 0.4f);
            }
            Vec2 intent = Tmp.v2.trns(now + 90f * side, unit.speed()).add(Tmp.v3.trns(now, Mathf.clamp((radius - dist) / (Vars.tilesize * 2f), -1f, 1f) * unit.speed()));
            intent.limit(unit.speed());
            float vx = unit.vel.x, vy = unit.vel.y;
            Vec2 out = Tmp.v3;
            if(Dodger.steer(unit, intent, vx, vy, out)) unit.movePref(out);
            else unit.movePref(intent);
            unit.lookAt(intent.angle());
            return true;
        }

        @Override
        public void updateTargeting(){
        }
    }
}
