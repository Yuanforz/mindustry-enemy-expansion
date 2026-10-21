package expansion.ops;

import arc.struct.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;

/**
 * A body of fighters, kind by kind (unit kinds, turrets with their ammunition, and buildings that only take hits: a core,
 * walls), of one team. What it does to another body is worked out against what that one is made of (Combat): its fire
 * spread over the other's health as that is made up, each of its kinds hurting each of the other's as the game has it
 * (an anti-air turret no tank, a tank no flyer; armour against each hit). Two bodies that fire at once wear each other down
 * so that what decides it is damage times health against each other (Lanchester's square law, see Combat.lanchester).
 */
public final class Force{
    /** Whose rules scale its damage and health (null: none). */
    public Team team;
    /** How many of each kind (a UnitType, a Combat.Gun, a Block that takes hits): fractions allowed (a share of a body). */
    final ObjectFloatMap<Object> kinds = new ObjectFloatMap<>();
    /** Health lost (or more, as shields) beyond what the kinds have at full: units counted as they stand. */
    float damage;
    /** How many fighters, health, and damage a second against a plain target, kept up for the report and sorting. */
    public int count;
    public float health, dps;

    /** Of the time units take to a building, the share they are firing at it: measured, four stells taking a bastion core
     * down at 78 a second where they do 120 by their weapons (getting there, aiming, the shots' flight). */
    public static float buildingUptime = Float.parseFloat(System.getProperty("ops.uptime", "0.65"));

    public Force(){
    }

    public Force(Team team){
        this.team = team;
    }

    public Force copy(){
        Force f = new Force(team);
        f.kinds.putAll(kinds);
        f.damage = damage;
        f.refresh();
        return f;
    }

    void refresh(){
        float n = 0f, hp = -damage, d = 0f;
        for(var e : kinds){
            if(e.value <= 1e-6f) continue;
            hp += e.value * Combat.health(e.key, team);
            if(!(e.key instanceof Block) || e.key instanceof Turret){
                n += e.value;
                d += e.value * Combat.dps(e.key, team, UnitTypes.stell, null);
            }
        }
        count = Math.round(n);
        health = Math.max(0f, hp);
        dps = d;
    }

    /** Damage a second against a plain target times health: for the report and for sorting, not for fights. */
    public float strength(){
        return dps * health;
    }

    public Force add(Object kind, float n){
        if(kind == null || n == 0f) return this;
        kinds.increment(kind, 0f, n);
        refresh();
        return this;
    }

    public Force add(UnitType type, float n){
        return add((Object)type, n);
    }

    /** A unit as it stands: its kind, and what of its health it has lost (or has over, in shields). */
    public Force add(Unit u){
        kinds.increment(u.type, 0f, 1f);
        damage += Combat.health(u.type, u.team) - (u.health + u.shield);
        refresh();
        return this;
    }

    /** A turret with the ammunition it is taken to fire (what it holds, else its best). */
    public Force addTurret(Turret type, float n){
        return add(new Combat.Gun(type, Combat.bestAmmo(type)), n);
    }

    public Force addTurret(Turret.TurretBuild build){
        Turret type = (Turret)build.block;
        kinds.increment(new Combat.Gun(type, Combat.ammoOf(type, build)), 0f, 1f);
        damage += Combat.health(type, build.team) - build.health;
        refresh();
        return this;
    }

    /** A building that takes hits and gives none (a core, a wall), with the health it has. */
    public Force addBuilding(Building b){
        kinds.increment(b.block, 0f, 1f);
        damage += Combat.health(b.block, b.team) - b.health;
        refresh();
        return this;
    }

    public Force add(Force o){
        if(team == null) team = o.team;
        for(var e : o.kinds) kinds.increment(e.key, 0f, e.value);
        damage += o.damage;
        refresh();
        return this;
    }

    /** So much of it (every kind alike, as a body that has lost that part of its members). */
    public Force scl(float f){
        for(var e : kinds.entries()) kinds.put(e.key, e.value * f);
        damage *= f;
        refresh();
        return this;
    }

    /** What a has that b has not (kinds below none are none). */
    public static Force minus(Force a, Force b){
        Force f = new Force(a.team != null ? a.team : b.team);
        for(var e : a.kinds) f.kinds.put(e.key, e.value);
        for(var e : b.kinds) f.kinds.increment(e.key, 0f, -e.value);
        for(var e : f.kinds.entries()) if(e.value < 0f) f.kinds.put(e.key, 0f);
        f.damage = Math.max(0f, a.damage - b.damage);
        f.refresh();
        return f;
    }

    /** The same body under another team's rules (the other side doing as we could). */
    public Force as(Team t){
        Force f = copy();
        f.team = t;
        f.refresh();
        return f;
    }

    /** The unit kinds in it, by name. */
    public java.util.List<String> kindNames(){
        java.util.List<String> out = new java.util.ArrayList<>();
        for(var e : kinds) if(e.value > 1e-3f && e.key instanceof UnitType u) out.add(u.name);
        return out;
    }

    /** How many of a unit kind, by name. */
    public float countOf(String name){
        for(var e : kinds) if(e.key instanceof UnitType u && u.name.equals(name)) return e.value;
        return 0f;
    }

    /** Whether it has nothing that fights or takes hits. */
    public boolean empty(){
        return health <= 0f;
    }

    /**
     * Its damage a second against another body: each of its kinds' fire spread over the other's kinds by the share of
     * the other's health each makes up, of those it can hurt (an anti-air turret's fire all on the flyers there are).
     */
    public float dpsAgainst(Force o){
        if(o == null || o.health <= 0f) return 0f;
        float sum = 0f;
        for(var a : kinds){
            if(a.value <= 1e-6f || (a.key instanceof Block && !(a.key instanceof Turret))) continue;
            float hurtable = 0f, dealt = 0f;
            for(var b : o.kinds){
                if(b.value <= 1e-6f) continue;
                float d = Combat.dps(a.key, team, b.key, o.team);
                if(d <= 0f) continue;
                float hp = b.value * Combat.health(b.key, o.team);
                //(a unit on a building fires part of the time it is there; a turret's fire is all on what it aims at)
                if(a.key instanceof UnitType && (b.key instanceof Block || b.key instanceof Combat.Gun)) d *= buildingUptime;
                hurtable += hp;
                dealt += hp * d;
            }
            if(hurtable > 0f) sum += a.value * dealt / hurtable;
        }
        return sum;
    }

    /** Damage a second against one target kind of a team (a core being taken down). */
    public float dpsAgainst(Object target, Team targetTeam){
        float sum = 0f;
        for(var a : kinds){
            if(a.value <= 1e-6f || (a.key instanceof Block && !(a.key instanceof Turret))) continue;
            float d = Combat.dps(a.key, team, target, targetTeam);
            if(a.key instanceof UnitType && (target instanceof Block || target instanceof Combat.Gun)) d *= buildingUptime;
            sum += a.value * d;
        }
        return sum;
    }

    /** Its strength against another: what it does to that one a second times what it can take. */
    public float strengthAgainst(Force o){
        return dpsAgainst(o) * health;
    }

    /**
     * A fight between this and another, both worn down as they fall: {1 this won, -1 the other did, 0 neither can hurt the
     * other; what is left of the winner (a share); seconds}. Neither is changed.
     */
    public float[] fight(Force o){
        return Combat.lanchester(dpsAgainst(o), health, o.dpsAgainst(this), o.health);
    }

    /** Whether a fight between this and another is won, with this much to spare (1: even). */
    public boolean beats(Force o, float margin){
        return strengthAgainst(o) >= margin * o.strengthAgainst(this);
    }

    @Override
    public String toString(){
        return String.format("%d (%.0f dps, %.0f hp, %.3g)", count, dps, health, strength());
    }
}
