package bp;

import mindustry.world.*;

import java.util.*;

/**
 * One way a block works, read off the game's own objects: what it takes and gives a second at full speed (items,
 * liquids and units by name), the power it uses or makes, the heat it needs or gives, and what ground it must stand
 * on. For a block on ground (a drill, a vent machine, a pump, a crusher), what it gives depends on its place: the
 * rates are for one unit of the place's yield (a facing ore tile, an ore tile under it, a unit of the wall's sand, a
 * vent's attribute sum, a tile's liquid), see {@link #at(float)}.
 */
public final class Recipe{
    public enum Ground{
        /** Anywhere it fits. */
        none,
        /** All its tiles on vent floor (Attribute.steam). */
        vent,
        /** Facing ore in a wall (beam drills). */
        wallOre,
        /** On ore floor (drills). */
        floorOre,
        /** Facing walls that give sand (Attribute.sand). */
        sandWall,
        /** On a liquid floor (pumps). */
        liquidFloor
    }

    public enum Kind{crafter, heater, generator, drill, pump, units, upgrade, source, sink}

    public final String name;
    public final Block block;
    public final int size;
    public final Kind kind;
    public final Ground ground;
    /** For ground that gives one thing (an ore, a liquid): its name; else null. */
    public final String dug;
    /** At full speed, a second (for ground ones: what does not depend on the place). */
    public final LinkedHashMap<String, Float> in = new LinkedHashMap<>(), out = new LinkedHashMap<>();
    /** For ground ones: a second, for one unit of the place's yield. */
    public final LinkedHashMap<String, Float> perYield = new LinkedHashMap<>();
    /** Whether the inputs (items) and power scale with the place's yield too (a crafter on vents crafts faster). */
    public boolean inScales;
    public float powerUse, powerOut, heatNeed, heatOut;
    /** Power made for one unit of the place's yield (a turbine on vents). */
    public float powerPerYield;
    /** For crafters that need heat: the efficiency cap and how much of the heat over the need counts (HeatCrafter). */
    public float maxEfficiency = 1f, overheatScale = 1f;
    /** Whether this is the variant that takes its optional booster, and by how much that speeds it. */
    public boolean boosted;
    public float boost = 1f;
    /** For crafters on attribute ground: efficiency = base + min(maxBoost, scale * yield). */
    public float attrBase, attrScale, attrMax = Float.POSITIVE_INFINITY;
    /** Its outputs that it stops for when full (a liquid output it can't get rid of stops a crafter). */
    public final Set<String> stopsWhenFull = new HashSet<>();

    public Recipe(String name, Block block, Kind kind, Ground ground, String dug){
        this.name = name;
        this.block = block;
        this.size = block.size;
        this.kind = kind;
        this.ground = ground;
        this.dug = dug;
    }

    /** The rates at a place of this yield (1 for blocks that need no ground). */
    public Rates at(float yield){
        Rates r = new Rates();
        float mult = 1f;
        if(kind == Kind.crafter && ground == Ground.vent) mult = attrBase + Math.min(attrMax, attrScale * yield);
        for(var e : in.entrySet()) r.in.put(e.getKey(), e.getValue() * (inScales ? mult : 1f));
        for(var e : out.entrySet()) r.out.put(e.getKey(), e.getValue() * mult);
        for(var e : perYield.entrySet()) r.out.merge(e.getKey(), e.getValue() * yield, Float::sum);
        r.powerUse = powerUse;
        r.powerOut = powerOut + powerPerYield * yield;
        r.heatNeed = heatNeed;
        r.heatOut = heatOut;
        return r;
    }

    /** What a block takes and gives a second at one place. */
    public static final class Rates{
        public final LinkedHashMap<String, Float> in = new LinkedHashMap<>(), out = new LinkedHashMap<>();
        public float powerUse, powerOut, heatNeed, heatOut;

        @Override
        public String toString(){
            StringBuilder s = new StringBuilder();
            s.append("in ").append(fmt(in)).append(" | out ").append(fmt(out));
            if(powerUse > 0) s.append(String.format(" | power use %.1f", powerUse));
            if(powerOut > 0) s.append(String.format(" | power out %.1f", powerOut));
            if(heatNeed > 0) s.append(String.format(" | heat need %.1f", heatNeed));
            if(heatOut > 0) s.append(String.format(" | heat out %.1f", heatOut));
            return s.toString();
        }
    }

    static String fmt(Map<String, Float> m){
        if(m.isEmpty()) return "-";
        StringBuilder s = new StringBuilder();
        for(var e : m.entrySet()) s.append(s.length() > 0 ? ", " : "").append(e.getKey()).append(String.format(" %.3f", e.getValue()));
        return s.toString();
    }

    @Override
    public String toString(){
        StringBuilder s = new StringBuilder(name + " [" + kind + ", " + size + "x" + size + (ground == Ground.none ? "" : ", on " + ground + (dug == null ? "" : " " + dug)) + "]: ");
        s.append("in ").append(fmt(in)).append(" | out ").append(fmt(out));
        if(!perYield.isEmpty()) s.append(" | per unit of yield ").append(fmt(perYield));
        if(powerUse > 0) s.append(String.format(" | power use %.1f", powerUse));
        if(powerOut > 0) s.append(String.format(" | power out %.1f", powerOut));
        if(powerPerYield > 0) s.append(String.format(" | power per yield %.2f", powerPerYield));
        if(heatNeed > 0) s.append(String.format(" | heat need %.1f (max eff %.2f, overheat %.2f)", heatNeed, maxEfficiency, overheatScale));
        if(heatOut > 0) s.append(String.format(" | heat out %.1f", heatOut));
        if(ground == Ground.vent && kind == Kind.crafter) s.append(String.format(" | efficiency %.2f + min(%.2f, %.4f x yield)", attrBase, attrMax, attrScale));
        if(!stopsWhenFull.isEmpty()) s.append(" | stops when full of ").append(stopsWhenFull);
        return s.toString();
    }
}
