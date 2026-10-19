package expansion.defense;

import java.util.*;

/**
 * What one kind of Erekir unit is, as plain numbers, for working out what a defence has to stop
 * (reference/erekir-late-spec.md §7.3, from the source). Nothing here needs the game.
 */
public final class Foe{
    /** How a unit gets about, which says which way it comes and what can hit it. */
    public enum Move{
        /** Tanks: only over open ground; walls stop them until broken. */
        ground,
        /** Mechs: step over walls and buildings. */
        legs,
        /** Hover ships: over liquids too, but hit as ground units. */
        hover,
        /** Flying: straight over everything. */
        air
    }

    public final String name;
    public final int tier;
    public final Move move;
    public final float health, armor, speed, range, dps;
    /** Whether it fires missiles that an anti-air turret can shoot down. */
    public boolean missiles;
    /** Whether its guns hit only ground targets. */
    public boolean groundOnly;

    Foe(String name, int tier, Move move, float health, float armor, float speed, float range, float dps){
        this.name = name;
        this.tier = tier;
        this.move = move;
        this.health = health;
        this.armor = armor;
        this.speed = speed;
        this.range = range;
        this.dps = dps;
    }

    Foe missiles(){
        missiles = true;
        return this;
    }

    Foe groundOnly(){
        groundOnly = true;
        return this;
    }

    public static final Map<String, Foe> all = new LinkedHashMap<>();

    static void add(Foe foe){
        all.put(foe.name, foe);
    }

    public static Foe get(String name){
        return all.get(name);
    }

    static{
        //speed in tiles a second, range in tiles, rough damage a second against one target
        add(new Foe("stell", 1, Move.ground, 850, 6, 5.6f, 20f, 36));
        add(new Foe("locus", 2, Move.ground, 2100, 8, 5.25f, 20f, 120));
        add(new Foe("precept", 3, Move.ground, 5000, 11, 4.8f, 24.5f, 105));
        add(new Foe("vanquish", 4, Move.ground, 11000, 20, 4.7f, 23.75f, 490));
        add(new Foe("conquer", 5, Move.ground, 22000, 26, 3.6f, 35f, 900));
        add(new Foe("merui", 1, Move.legs, 680, 4, 5.4f, 17.25f, 29).groundOnly());
        add(new Foe("cleroi", 2, Move.legs, 1100, 5, 4.5f, 17.5f, 238));
        add(new Foe("anthicus", 3, Move.legs, 2700, 7, 4.9f, 41.75f, 258).missiles());
        add(new Foe("tecta", 4, Move.legs, 6500, 5, 4.5f, 28.9f, 880).missiles());
        add(new Foe("collaris", 5, Move.legs, 18000, 9, 8.25f, 48f, 400).groundOnly());
        add(new Foe("elude", 1, Move.hover, 600, 1, 13.5f, 18.75f, 72));
        add(new Foe("avert", 2, Move.air, 1100, 3, 15f, 11.25f, 75));
        add(new Foe("obviate", 3, Move.air, 2300, 6, 13.5f, 22.5f, 248));
        add(new Foe("quell", 4, Move.air, 6000, 4, 8.25f, 45f, 240).missiles().groundOnly());
        add(new Foe("disrupt", 5, Move.air, 12000, 9, 7.5f, 58.6f, 720).missiles().groundOnly());
    }
}
