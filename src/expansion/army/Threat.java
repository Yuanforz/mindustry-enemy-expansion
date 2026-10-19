package expansion.army;

import arc.math.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.entities.bullet.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.defense.turrets.ItemTurret.*;
import mindustry.world.blocks.defense.turrets.Turret.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.distribution.DirectionBridge.*;
import mindustry.world.blocks.distribution.ItemBridge.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.storage.*;
import mindustry.world.consumers.*;

/**
 * A0, the threat field: what the other side has that shoots, how hard it hits each kind of unit where, and what keeps
 * it shooting. Worked out afresh every half second from the game as it stands.
 *
 * How hard a turret hits a unit is its damage a second times the chance its shot lands. The shot takes distance over
 * bullet speed to get there; a unit that changes how it moves in that time is somewhere else when it arrives (the
 * turret aims where the unit would be if it kept going as it goes). How far off it can be is how hard it can speed up
 * times that time squared over two, and no further than its top speed takes it; the chance is the unit's width over
 * that. So a quick small ship under a breach's slow shot is hit by few of them, and a tank by nearly all.
 *
 * What keeps a turret shooting: the line that brings it ammunition (ducts, bridges, routers back to a drill, a crafter
 * or a store) or the power that runs it. Of those, the pieces every way from the turret back to a source passes through
 * are where cutting one thing leaves the turret with only what it holds: {@link Cut}.
 */
public class Threat{
    /** One of the other side's turrets as it stands. */
    public static class Gun{
        public Building build;
        public float x, y, range, dps, bulletSpeed, health;
        public boolean air, ground, loaded, items, power;
        /** How much ammunition it holds, as a part of what it can hold (1 for what runs on power). */
        public float ammo;
    }

    /** A piece of a turret's supply that every way back to its sources passes through, and what cutting it stops. */
    public static class Cut{
        public Building build;
        /** The turrets it feeds alone, and their damage a second together. */
        public Seq<Gun> guns = new Seq<>();
        public float dps;
        /** How hard the other side's turrets hit a unit standing off to fire at it (for a ground unit). */
        public float danger;
        /** Worth of cutting it: damage a second cut off for each second it takes to destroy. */
        public float worth;
    }

    public final Team team;
    public final Seq<Gun> guns = new Seq<>();
    public final Seq<Cut> cuts = new Seq<>();
    /** The other side's units that fight: their damage a second and health, all together. */
    public float enemyDps, enemyHealth;
    public int enemyUnits;
    /** Those units themselves, as they stood. */
    public final Seq<Unit> foes = new Seq<>();

    public Threat(Team team){
        this.team = team;
    }

    public void update(){
        guns.clear();
        foes.clear();
        enemyDps = enemyHealth = 0f;
        enemyUnits = 0;
        for(Teams.TeamData data : Vars.state.teams.active){
            if(data.team == team || data.team == Team.derelict) continue;
            for(Building build : data.buildings){
                if(build instanceof TurretBuild t && build.block instanceof Turret type) guns.add(gun(t, type));
            }
            for(int i = 0; i < data.units.size; i++){
                Unit unit = data.units.get(i);
                if(unit.spawnedByCore || !unit.type.canAttack) continue;
                enemyDps += unit.type.dpsEstimate;
                enemyHealth += unit.health;
                enemyUnits++;
                foes.add(unit);
            }
        }
    }

    static Gun gun(TurretBuild t, Turret type){
        Gun gun = new Gun();
        gun.build = t;
        gun.x = t.x;
        gun.y = t.y;
        gun.range = t.range();
        gun.air = type.targetAir;
        gun.ground = type.targetGround;
        gun.health = t.health;
        gun.loaded = t.hasAmmo();
        gun.items = type instanceof ItemTurret;
        gun.power = type.consPower != null;
        BulletType bullet = t.peekAmmo();
        if(bullet == null && type instanceof ItemTurret item && item.ammoTypes.size > 0) bullet = item.ammoTypes.values().toSeq().first();
        if(bullet == null && type instanceof PowerTurret power) bullet = power.shootType;
        gun.bulletSpeed = bullet == null ? 5f : Math.max(bullet.speed, 0.5f);
        //what it would do with ammunition, so that one gone dry for a moment still counts
        float shots = type.shoot.shots / Math.max(type.reload, 1f) * 60f;
        gun.dps = bullet == null ? 0f : shots * bullet.estimateDPS();
        gun.ammo = type instanceof ItemTurret ? Mathf.clamp(t.totalAmmo / (float)Math.max(type.maxAmmo, 1)) : 1f;
        //a continuous turret (sublimate, lustre) has no speed of shot worth the name: it hits whatever it points at
        if(bullet instanceof ContinuousBulletType || bullet instanceof LaserBulletType || bullet instanceof PointLaserBulletType) gun.bulletSpeed = 1000f;
        return gun;
    }

    /**
     * The chance a turret's shot lands on a unit of a kind at a distance, if the unit keeps changing how it moves
     * ({@code dodging}) or moves the way the game's own units do, steadily (then the turret's lead finds it).
     */
    public static float hitChance(UnitType type, float bulletSpeed, float distance, boolean dodging){
        if(!dodging) return 1f;
        float time = distance / Math.max(bulletSpeed, 0.5f);
        float accel = type.accel * type.speed;
        float off = Math.min(0.5f * accel * time * time, type.speed * time);
        float width = type.hitSize / 2f + 4f;
        return Mathf.clamp(width / Math.max(off, 0.001f));
    }

    /** How much damage a second a unit of a kind takes standing at a place, from the turrets that reach it. */
    public float danger(float x, float y, UnitType type, boolean dodging){
        boolean air = type.flying;
        float sum = 0f;
        for(int i = 0; i < guns.size; i++){
            Gun gun = guns.get(i);
            if(!gun.loaded || (air ? !gun.air : !gun.ground)) continue;
            float d = Mathf.dst(x, y, gun.x, gun.y);
            if(d > gun.range + type.hitSize / 2f) continue;
            sum += gun.dps * hitChance(type, gun.bulletSpeed, d, dodging);
        }
        return sum;
    }

    /**
     * How much damage a second the other side's units would do at a place: those that can fire at what is there (in the air
     * or on the ground) and stand within their reach of it, with a few tiles more for their coming closer.
     */
    public float unitDanger(float x, float y, boolean air){
        float sum = 0f;
        for(int i = 0; i < foes.size; i++){
            Unit foe = foes.get(i);
            if(!foe.isValid() || (air ? !foe.type.targetAir : !foe.type.targetGround)) continue;
            if(foe.within(x, y, foe.type.range + Vars.tilesize * 6f)) sum += foe.type.dpsEstimate;
        }
        return sum;
    }

    /** Whether any of the other side's turrets that can fire at a kind of unit reaches a place, with some tiles to spare. */
    public boolean covered(float x, float y, boolean air, float margin){
        for(int i = 0; i < guns.size; i++){
            Gun gun = guns.get(i);
            if(air ? !gun.air : !gun.ground) continue;
            if(Mathf.within(x, y, gun.x, gun.y, gun.range + margin)) return true;
        }
        return false;
    }

    //region supply

    /**
     * Works out the cuts: for each turret, what feeds it, back to its sources; then which of those pieces every way back
     * passes through. Slower than the rest, so done less often.
     */
    public void findCuts(float ourDps){
        cuts.clear();
        ObjectMap<Building, Cut> found = new ObjectMap<>();
        for(Gun gun : guns){
            if(gun.items){
                //what feeds it, a few dozen pieces back at most
                ObjectSet<Building> feeders = new ObjectSet<>();
                Seq<Building> order = new Seq<>();
                boolean anySource = upstream(gun.build, null, feeders, order);
                if(!anySource) continue;
                for(Building piece : order){
                    if(piece == gun.build) continue;
                    //cut if no way back to a source is left without it
                    if(upstream(gun.build, piece, new ObjectSet<>(), null)) continue;
                    Cut cut = found.get(piece);
                    if(cut == null){
                        cut = new Cut();
                        cut.build = piece;
                        found.put(piece, cut);
                        cuts.add(cut);
                    }
                    cut.guns.add(gun);
                    cut.dps += gun.dps;
                }
            }
        }
        for(Cut cut : cuts){
            float seconds = cut.build.health / Math.max(ourDps, 1f) + 1f;
            cut.worth = cut.dps / seconds;
        }
        cuts.sort(c -> -c.worth);
    }

    static final int maxPieces = 80;

    /**
     * Walks a turret's item supply back from it, leaving out one piece.
     * @return whether a source (a drill, a crafter, a store) is got to
     */
    static boolean upstream(Building turret, @Nullable Building without, ObjectSet<Building> seen, @Nullable Seq<Building> order){
        Seq<Building> queue = new Seq<>();
        queue.add(turret);
        seen.add(turret);
        boolean source = false;
        while(queue.size > 0 && seen.size < maxPieces){
            Building at = queue.remove(0);
            if(order != null) order.add(at);
            if(at != turret && isSource(at)){
                source = true;
                if(order == null) return true;
                continue;
            }
            for(Building from : feeding(at)){
                if(from == without || from.team != turret.team || !seen.add(from)) continue;
                queue.add(from);
            }
        }
        return source;
    }

    static final Seq<Building> near = new Seq<>();

    /** What can put items into a building: the buildings next to it that face it or pass items any way, and bridges that end on it. */
    static Seq<Building> feeding(Building to){
        Seq<Building> out = new Seq<>();
        for(Building from : to.proximity){
            if(from instanceof Duct.DuctBuild || from instanceof Conveyor.ConveyorBuild || from instanceof StackConveyor.StackConveyorBuild){
                if(from.front() == to) out.add(from);
            }else if(from instanceof DirectionBridgeBuild bridge){
                //a bridge passes items on out of the front of its last end only
                if(bridge.findLink() == null && from.front() == to) out.add(from);
            }else if(from.block instanceof DuctRouter || from.block instanceof Router || from.block instanceof Sorter || from.block instanceof OverflowGate || from.block instanceof OverflowDuct
                || from.block instanceof Junction || from.block instanceof DuctJunction || from.block instanceof DirectionalUnloader || from.block instanceof mindustry.world.blocks.storage.Unloader){
                out.add(from);
            }else if(from.block instanceof Drill || from.block instanceof BeamDrill || from.block instanceof mindustry.world.blocks.production.GenericCrafter || from.block instanceof mindustry.world.blocks.units.UnitCargoUnloadPoint){
                //what puts out what it makes by itself, into whatever is next to it
                out.add(from);
            }else if(from.block instanceof StorageBlock && (to.block instanceof DirectionalUnloader || to.block instanceof mindustry.world.blocks.storage.Unloader)){
                //a store gives only to what unloads it
                out.add(from);
            }
        }
        if(to instanceof DirectionBridgeBuild bridge){
            for(DirectionBridgeBuild other : bridge.occupied) if(other != null) out.add(other);
        }
        if(to instanceof ItemBridgeBuild bridge){
            for(int i = 0; i < bridge.incoming.size; i++){
                Building other = Vars.world.build(bridge.incoming.get(i));
                if(other != null) out.add(other);
            }
        }
        return out;
    }

    /** Whether a building makes or holds items that it puts out by itself. */
    static boolean isSource(Building build){
        return build.block instanceof Drill || build.block instanceof BeamDrill || build.block instanceof mindustry.world.blocks.production.GenericCrafter
            || build.block instanceof StorageBlock || build.block instanceof mindustry.world.blocks.units.UnitCargoUnloadPoint;
    }

    //endregion
}
