package expansion.ops;

import mindustry.*;
import mindustry.entities.bullet.*;
import mindustry.game.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;

/**
 * How fighters hurt one another, by the game's own numbers: one model for every layer that weighs a fight (the command
 * layer's game, the forecast, the army's tactics), so that what a plan counts on and what carries it out agree.
 *
 * A hit does its damage less the target's armour, never less than the game's least share (Damage.applyArmor); a bullet's
 * direct hit lands only if it collides with what it is shot at (an artillery shell does not: only its splash reaches a
 * unit), its splash wherever it bursts; against a building both are times the bullet's building multiplier. A unit's
 * weapons each fire their shots every reload; a turret fires its pattern every reload, quickened by its ammunition; a
 * continuous beam hurts every damage interval. The team's rules (and so the campaign's difficulty) scale damage and health.
 *
 * Fights are Lanchester's square law over these (Stanescu et al. 2015): each side's fire spread over the other's health
 * as that is made up, the winner left with the root of the difference, and the time it takes from the law's solution.
 */
public final class Combat{
    /** A turret with the ammunition it fires: one kind of fighter. */
    public static final class Gun{
        public final Turret turret;
        public final BulletType ammo;

        public Gun(Turret turret, BulletType ammo){
            this.turret = turret;
            this.ammo = ammo;
        }

        @Override
        public boolean equals(Object o){
            return o instanceof Gun g && g.turret == turret && g.ammo == ammo;
        }

        @Override
        public int hashCode(){
            return turret.id * 31 + (ammo == null ? 0 : ammo.id);
        }

        @Override
        public String toString(){
            return turret.name;
        }
    }

    /** The ammunition a turret is taken to fire: what it holds now, else the strongest it can be given (one power runs on). */
    public static BulletType ammoOf(Turret type, Turret.TurretBuild build){
        BulletType held = build == null ? null : build.peekAmmo();
        if(held != null) return held;
        return bestAmmo(type);
    }

    /** The ammunition of a turret that hurts a ground unit of no armour most a second. */
    public static BulletType bestAmmo(Turret type){
        BulletType best = null;
        float bestDps = -1f;
        for(BulletType b : ammunition(type)){
            float d = gunDps(type, b, false, false, 0f);
            if(d > bestDps){
                bestDps = d;
                best = b;
            }
        }
        return best;
    }

    static Iterable<BulletType> ammunition(Turret type){
        arc.struct.Seq<BulletType> out = new arc.struct.Seq<>();
        if(type instanceof ItemTurret t) out.addAll(t.ammoTypes.values().toSeq());
        else if(type instanceof LiquidTurret t) out.addAll(t.ammoTypes.values().toSeq());
        else if(type instanceof ContinuousLiquidTurret t) out.addAll(t.ammoTypes.values().toSeq());
        else if(type instanceof ContinuousTurret t && t.shootType != null) out.add(t.shootType);
        else if(type instanceof PowerTurret t && t.shootType != null) out.add(t.shootType);
        return out;
    }

    /** The game's rule for a hit against armour. */
    static float afterArmor(float damage, float armor){
        return Math.max(damage - armor, damage * Vars.minArmorDamage);
    }

    /**
     * What one shot of a bullet does to one target: a unit (in the air or on the ground) or a building, of this armour. Its
     * direct hit if it collides with such a target, its splash, one of its fragments, and its lightning.
     */
    static float perShot(BulletType b, boolean air, boolean building, float armor){
        return perShot(b, air, building, armor, 0);
    }

    static float perShot(BulletType b, boolean air, boolean building, float armor, int depth){
        if(b == null || depth > 3) return 0f;
        float mult = building ? b.buildingDamageMultiplier : 1f;
        boolean reaches = building ? b.collidesGround || b.collidesTiles : air ? b.collidesAir : b.collidesGround;
        float d = 0f;
        if(b instanceof ContinuousBulletType || b instanceof PointLaserBulletType) return 0f;
        //(a missile: what the unit it spawns does, its weapons fired once, as it bursts)
        if(b.spawnUnit != null){
            for(Weapon w : b.spawnUnit.weapons) if(w.bullet != null) d += w.shoot.shots * perShot(w.bullet, air, building, armor, depth + 1);
            return d;
        }
        //(a line that hurts all it crosses at once, rail, laser, shrapnel, a point shot: its damage lands though it does not
        //fly into its target)
        boolean line = b instanceof RailBulletType || b instanceof LaserBulletType || b instanceof ShrapnelBulletType || b instanceof PointBulletType;
        if((b.collides || line) && reaches && b.damage > 0f) d += afterArmor(b.damage, armor) * mult;
        if(b.splashDamage > 0f && b.splashDamageRadius > 0f && (building || (air ? b.collidesAir : b.collidesGround))) d += afterArmor(b.splashDamage, armor) * mult;
        //(of its fragments, the one that comes the target's way)
        if(b.fragBullet != null && b.fragBullets > 0) d += perShot(b.fragBullet, air, building, armor, depth + 1);
        if(b.lightning > 0 && b.lightningDamage > 0f && !air) d += b.lightning * afterArmor(b.lightningDamage, armor) * mult;
        return d;
    }

    /** What a continuous beam does a second to one target. */
    static float beamDps(BulletType b, boolean air, boolean building, float armor){
        float interval = b instanceof ContinuousBulletType c ? c.damageInterval : b instanceof PointLaserBulletType p ? p.damageInterval : 0f;
        if(interval <= 0f) return 0f;
        boolean reaches = building ? b.collidesGround || b.collidesTiles : air ? b.collidesAir : b.collidesGround;
        if(!reaches) return 0f;
        return afterArmor(b.damage, armor) * (building ? b.buildingDamageMultiplier : 1f) * 60f / interval;
    }

    /** A turret's damage a second with this ammunition against one target of these, before the team's rules. */
    static float gunDps(Turret type, BulletType ammo, boolean air, boolean building, float armor){
        if(ammo == null || building) return 0f;
        if(air ? !type.targetAir : !type.targetGround) return 0f;
        float beam = beamDps(ammo, air, false, armor);
        if(beam > 0f) return beam;
        float reload = Math.max(1f, type.reload / Math.max(0.01f, ammo.reloadMultiplier));
        return type.shoot.shots * 60f / reload * perShot(ammo, air, false, armor);
    }

    /** A unit kind's damage a second against one target of these, before the team's rules. */
    static float unitDps(UnitType type, boolean air, boolean building, float armor){
        if(!type.canAttack) return 0f;
        if(building ? !type.targetGround : air ? !type.targetAir : !type.targetGround) return 0f;
        float sum = 0f;
        for(Weapon w : type.weapons){
            if(w.bullet == null || w.noAttack) continue;
            float beam = beamDps(w.bullet, air, building, armor);
            if(beam > 0f){
                sum += beam;
                continue;
            }
            sum += w.shoot.shots * 60f / Math.max(1f, w.reload) * perShot(w.bullet, air, building, armor);
        }
        return sum;
    }

    record Key(Object a, Team ta, Object b, Team tb){
    }

    /** (worked out once: the rules do not change in a game; -Dops.nocache for checking) */
    static final java.util.concurrent.ConcurrentHashMap<Key, Float> dpsSeen = new java.util.concurrent.ConcurrentHashMap<>();

    /** Damage a second of a fighter (a unit kind, a Gun) of a team against a target (a unit kind, a block) of another. */
    public static float dps(Object fighter, Team team, Object target, Team targetTeam){
        Key k = new Key(fighter, team, target, targetTeam);
        Float d = dpsSeen.get(k);
        if(d == null) dpsSeen.put(k, d = dpsNow(fighter, team, target, targetTeam));
        return d;
    }

    static float dpsNow(Object fighter, Team team, Object target, Team targetTeam){
        boolean air = target instanceof UnitType u && u.flying;
        boolean building = target instanceof Block || target instanceof Gun;
        float armor = target instanceof UnitType u ? u.armor : target instanceof Gun g ? g.turret.armor : target instanceof Block b ? b.armor : 0f;
        Rules rules = Vars.state.rules;
        if(fighter instanceof UnitType u) return unitDps(u, air, building, armor) * (team == null ? 1f : rules.unitDamage(team));
        if(fighter instanceof Gun g) return gunDps(g.turret, g.ammo, air, building, armor) * (team == null ? 1f : rules.blockDamage(team));
        return 0f;
    }

    /** The health of a fighter or target of a team. */
    public static float health(Object kind, Team team){
        Rules rules = Vars.state.rules;
        if(kind instanceof UnitType u) return u.health * (team == null ? 1f : rules.unitHealth(team));
        if(kind instanceof Gun g) return g.turret.health * (team == null ? 1f : rules.blockHealth(team));
        if(kind instanceof Block b) return b.health * (team == null ? 1f : rules.blockHealth(team));
        return 0f;
    }

    /** Whether a fighter can hurt a target at all (an anti-air turret a tank, a tank a flyer). */
    public static boolean hurts(Object fighter, Object target){
        return dps(fighter, null, target, null) > 0f;
    }

    /**
     * The end of a fight under the square law: a attacks with its damage a second da against b's health hb, b with db
     * against a's ha. {who won (1: a, -1: b, 0: neither can hurt the other), what is left of the winner (a share of it),
     * seconds it takes}.
     */
    public static float[] lanchester(float da, float ha, float db, float hb){
        if(da <= 0f && db <= 0f) return new float[]{0f, 1f, Float.POSITIVE_INFINITY};
        float sa = da * ha, sb = db * hb;
        if(sa >= sb){
            float left = sa <= 0f ? 1f : (float)Math.sqrt(Math.max(0f, 1f - sb / sa));
            return new float[]{1f, left, time(da / Math.max(1e-6f, hb), db / Math.max(1e-6f, ha))};
        }
        float left = (float)Math.sqrt(Math.max(0f, 1f - sa / sb));
        return new float[]{-1f, left, time(db / Math.max(1e-6f, ha), da / Math.max(1e-6f, hb))};
    }

    /**
     * Seconds till the loser is gone, the winner wearing it down at p of its health a second and worn at q of its own
     * (fractions at full strength; the square law with fire falling as a side does): atanh(sqrt(q / p)) / sqrt(pq).
     */
    static float time(float p, float q){
        if(p <= 0f) return Float.POSITIVE_INFINITY;
        if(q <= 0f) return 1f / p;
        double r = Math.sqrt(Math.min(0.999999, q / p));
        return (float)(0.5 * Math.log((1 + r) / (1 - r)) / Math.sqrt(p * q));
    }
}
