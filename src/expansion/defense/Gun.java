package expansion.defense;

import java.util.*;

/**
 * What one kind of Erekir turret is, as plain numbers: how far it reaches, what it can hit, what it eats while it fires
 * and what it needs besides. The numbers are the game's (reference/erekir-late-spec.md §8, read from the source); what
 * each turret is for comes from how people play (reference/turrets-drills-human.md). Nothing here needs the game.
 *
 * Rates are per turret, per second, while it fires. A turret that has nothing to shoot at takes nothing (water and
 * nitrogen included); power and heat are drawn all the time.
 */
public final class Gun{
    /** What a turret is used for. A turret can have more than one. */
    public enum Role{
        /** Holds a choke against ground units at the wall line: the first line. */
        line,
        /** Close range, for leg mechs that step over walls and for swarms in a narrow place. */
        close,
        /** Long range against ground, from behind the line: for units that outrange the first line. */
        artillery,
        /** Against air. */
        antiAir,
        /** Kills one big unit at a time: for T3 and up, and for guardians. */
        heavy,
        /** Never part of a defence: a cruise missile launcher, for scouting and sniping. */
        sniper
    }

    /** One kind of ammunition: an item, or a liquid for a turret that fires liquid. */
    public static final class Ammo{
        public final String what;
        public final boolean liquid;
        /** Eaten while firing, a second: dry, and with water. */
        public final float rate, wetRate;
        /** How far it reaches with this ammunition, in tiles. */
        public final float range;
        /** Damage a second against one target in the open, dry, before armour: a rough figure for ordering. */
        public final float dps;

        Ammo(String what, boolean liquid, float rate, float wetRate, float range, float dps){
            this.what = what;
            this.liquid = liquid;
            this.rate = rate;
            this.wetRate = wetRate;
            this.range = range;
            this.dps = dps;
        }
    }

    public final String name;
    public final int size;
    public final boolean air, ground;
    public final EnumSet<Role> roles;
    /** The kinds of ammunition, best first for an ordinary defence. Empty for one that runs on power alone. */
    public final List<Ammo> ammo = new ArrayList<>();
    /** Power drawn, a second; heat it needs for full speed. */
    public float power, heat;
    /** A liquid it needs besides its ammunition (titan: hydrogen; lustre: nitrogen), and how much a second. */
    public String needs;
    public float needsRate;
    /** Whether that liquid is used only while it fires. */
    public boolean needsWhileFiring;
    /** Water that makes it fire faster, a second while it fires, and how much faster. 0 if none. */
    public float water, waterBoost = 1f;
    /** Range and damage a second when it fires on power alone. */
    public float range, dps;
    /** What it is built of. */
    public final Map<String, Integer> cost = new LinkedHashMap<>();
    /** Its target choice: "strongest" ones lag behind swarms, "grouped" ones aim at the thick of them. */
    public String aims = "closest";
    /** Whether it can only hit what is on the ground near it in a line (pierce) or splash. Used only for notes. */
    public String note = "";

    Gun(String name, int size, boolean air, boolean ground, Role... roles){
        this.name = name;
        this.size = size;
        this.air = air;
        this.ground = ground;
        this.roles = roles.length == 0 ? EnumSet.noneOf(Role.class) : EnumSet.copyOf(Arrays.asList(roles));
    }

    Gun ammo(String item, float rate, float wetRate, float range, float dps){
        ammo.add(new Ammo(item, false, rate, wetRate, range, dps));
        return this;
    }

    Gun liquidAmmo(String liquid, float rate, float range, float dps){
        ammo.add(new Ammo(liquid, true, rate, rate, range, dps));
        return this;
    }

    Gun cost(Object... pairs){
        for(int i = 0; i < pairs.length; i += 2) cost.put((String)pairs[i], (Integer)pairs[i + 1]);
        return this;
    }

    Gun water(float rate, float boost){
        water = rate;
        waterBoost = boost;
        return this;
    }

    /** Whether it can hit units that move this way. */
    public boolean hits(Foe.Move move){
        return move == Foe.Move.air ? air : ground;
    }

    /** The ammunition it would be given from what there is, best first; null if none of it is to be had. */
    public Ammo pick(Set<String> have){
        for(Ammo a : ammo) if(have.contains(a.what)) return a;
        return null;
    }

    /** How far it reaches with the given ammunition (or on power). */
    public float range(Ammo with){
        return with == null ? range : with.range;
    }

    public float dps(Ammo with, boolean wet){
        float base = with == null ? dps : with.dps;
        return wet && water > 0 ? base * waterBoost : base;
    }

    /** Every Erekir turret, by name. */
    public static final Map<String, Gun> all = new LinkedHashMap<>();

    static void add(Gun gun){
        all.put(gun.name, gun);
    }

    public static Gun get(String name){
        return all.get(name);
    }

    static{
        //breach: the first turret. Piercing bullets at air and ground; beryllium is 2 a shot, tungsten 1 and reaches 5 tiles further
        add(new Gun("breach", 3, true, true, Gun.Role.line, Gun.Role.antiAir)
            .ammo("tungsten", 1.5f, 3.75f, 28.75f, 107f)
            .ammo("beryllium", 3f, 7.5f, 23.75f, 96f)
            .ammo("carbide", 0.3f, 0.75f, 30.75f, 300f)
            .water(15f, 2.5f).cost("beryllium", 150, "silicon", 150, "graphite", 125));
        //diffuse: a shotgun with knockback; 15 tiles. Graphite 3 a salvo, oxide half that and twice the damage
        add(new Gun("diffuse", 3, true, true, Gun.Role.close)
            .ammo("oxide", 3f, 7.5f, 15.6f, 2025f)
            .ammo("graphite", 6f, 15f, 15.6f, 920f)
            .ammo("silicon", 6f, 15f, 15.6f, 790f)
            .water(15f, 2.5f).cost("beryllium", 150, "silicon", 200, "graphite", 200, "tungsten", 50));
        //sublimate: a flamethrower that ignores armour; ozone 16 tiles, cyanogen 25
        Gun sublimate = new Gun("sublimate", 3, true, true, Gun.Role.line, Gun.Role.close)
            .liquidAmmo("cyanogen", 18f, 25f, 1170f)
            .liquidAmmo("ozone", 15f, 16.25f, 540f)
            .cost("tungsten", 150, "silicon", 200, "oxide", 40, "beryllium", 400);
        sublimate.note = "ignores armour, pierces";
        add(sublimate);
        //titan: artillery, ground only, 48 tiles; always needs hydrogen
        Gun titan = new Gun("titan", 4, false, true, Gun.Role.artillery)
            .ammo("thorium", 1.74f, 3.0f, 48.75f, 265f)
            .ammo("oxide", 1.22f, 2.1f, 48.75f, 190f)
            .ammo("carbide", 1.39f, 2.4f, 58.75f, 520f)
            .water(30f, 1.75f).cost("tungsten", 250, "silicon", 300, "thorium", 400);
        titan.needs = "hydrogen";
        titan.needsRate = 5f;
        titan.aims = "grouped";
        add(titan);
        //disperse: flak, air only, 38 tiles; tungsten gives 3 salvos an item. Thorium cuts its range by 15 tiles: never on it
        add(new Gun("disperse", 4, true, false, Gun.Role.antiAir)
            .ammo("tungsten", 2.2f, 4.1f, 38.75f, 1300f)
            .ammo("surge-alloy", 1.67f, 3.1f, 41.75f, 1100f)
            .ammo("silicon", 1.67f, 3.1f, 38.75f, 740f)
            .water(20f, 1.83f).cost("thorium", 50, "oxide", 50, "silicon", 200, "beryllium", 350));
        //afflict: power and heat, air and ground, 46 tiles, kills missiles: the all-rounder once there is surge
        Gun afflict = new Gun("afflict", 4, true, true, Gun.Role.line, Gun.Role.artillery, Gun.Role.antiAir)
            .cost("surge-alloy", 100, "silicon", 200, "graphite", 250, "oxide", 40);
        afflict.power = 300f;
        afflict.heat = 20f;
        afflict.range = 46f;
        afflict.dps = 1056f;
        add(afflict);
        //lustre: a point laser on the strongest unit; power and nitrogen while it fires
        Gun lustre = new Gun("lustre", 4, true, true, Gun.Role.heavy)
            .cost("silicon", 250, "graphite", 200, "oxide", 50, "carbide", 90);
        lustre.power = 200f;
        lustre.needs = "nitrogen";
        lustre.needsRate = 6f;
        lustre.needsWhileFiring = true;
        lustre.range = 31.25f;
        lustre.dps = 1890f;
        lustre.aims = "strongest";
        add(lustre);
        //scathe: a cruise missile at the strongest ground unit, 168 tiles away, every 10 s. Not a defence
        Gun scathe = new Gun("scathe", 4, false, true, Gun.Role.sniper)
            .ammo("carbide", 1.5f, 3.75f, 168.75f, 170f)
            .ammo("phase-fabric", 0.375f, 0.94f, 168.75f, 26f)
            .water(15f, 2.5f).cost("silicon", 450, "graphite", 400, "tungsten", 500, "oxide", 100, "carbide", 200);
        scathe.aims = "strongest";
        add(scathe);
        //smite: ten piercing bolts a salvo, surge 2 a salvo
        add(new Gun("smite", 5, true, true, Gun.Role.line, Gun.Role.heavy)
            .ammo("surge-alloy", 1.2f, 3f, 37.5f, 1125f)
            .water(15f, 2.5f).cost("oxide", 200, "surge-alloy", 400, "silicon", 800, "carbide", 500, "phase-fabric", 300));
        //malign: power 2400 and heat 144
        Gun malign = new Gun("malign", 5, true, true, Gun.Role.heavy, Gun.Role.antiAir)
            .cost("carbide", 200, "beryllium", 1000, "silicon", 500, "graphite", 500, "phase-fabric", 200);
        malign.power = 2400f;
        malign.heat = 144f;
        malign.range = 51.25f;
        malign.dps = 1500f;
        malign.aims = "strongest";
        add(malign);
    }
}
