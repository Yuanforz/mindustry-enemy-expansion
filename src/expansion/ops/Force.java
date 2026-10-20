package expansion.ops;

import mindustry.entities.bullet.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.blocks.defense.turrets.*;

/**
 * A body of fighters as Lanchester's square law sees it: their damage a second and their health, all together. Two such
 * bodies that fire at once wear each other down so that what decides it is damage times health ({@link #strength}); the
 * winner is left with the square root of the difference. Units and turrets alike: a turret is a fighter that does not
 * move. (Stanescu et al., "Using Lanchester Attrition Laws for Combat Prediction in StarCraft", AIIDE 2015.)
 */
public final class Force{
    public float dps, health;
    /** How many fighters, for the report. */
    public int count;

    public Force(){
    }

    public Force(float dps, float health, int count){
        this.dps = dps;
        this.health = health;
        this.count = count;
    }

    public float strength(){
        return dps * health;
    }

    public Force copy(){
        return new Force(dps, health, count);
    }

    public Force add(Force o){
        dps += o.dps;
        health += o.health;
        count += o.count;
        return this;
    }

    public Force add(UnitType type, float n){
        dps += type.dpsEstimate * n;
        health += (type.health + type.armor * 10f) * n;
        count += Math.round(n);
        return this;
    }

    public Force add(Unit u){
        dps += u.type.dpsEstimate;
        health += u.health + u.shield;
        count++;
        return this;
    }

    public Force addTurret(Turret type, float n){
        dps += dps(type) * n;
        health += type.health * n;
        count += Math.round(n);
        return this;
    }

    /** What a turret does a second with its first ammunition (or its shot, for one that runs on power), firing all the time. */
    public static float dps(Turret type){
        BulletType bullet = null;
        if(type instanceof ItemTurret item && item.ammoTypes.size > 0) bullet = item.ammoTypes.values().toSeq().first();
        if(bullet == null && type instanceof PowerTurret power) bullet = power.shootType;
        if(bullet == null && type instanceof LiquidTurret liquid && liquid.ammoTypes.size > 0) bullet = liquid.ammoTypes.values().toSeq().first();
        if(bullet == null) return 0f;
        return type.shoot.shots / Math.max(type.reload, 1f) * 60f * bullet.estimateDPS();
    }

    /** Whether a fight between this and another is won, with this much to spare (1: even). */
    public boolean beats(Force o, float margin){
        return strength() >= margin * o.strength();
    }

    @Override
    public String toString(){
        return String.format("%d (%.0f dps, %.0f hp, %.3g)", count, dps, health, strength());
    }
}
