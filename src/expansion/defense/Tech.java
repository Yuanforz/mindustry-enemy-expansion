package expansion.defense;

import java.util.*;

/**
 * What the defence can use by each Erekir sector of the campaign: the blocks it needs that are unlocked by landing there
 * (reference/erekir-late-spec.md §1.2-1.3), and the items and liquids the economy can make by then to feed turrets.
 * Each sector has everything of the ones before it. Plain data, for the planner and its tests; in a real game the
 * economy says what it can feed.
 */
public final class Tech{
    private Tech(){}

    public static final String[] order = {"onset", "aegis", "lake", "intersect", "atlas", "split", "basin", "marsh", "peaks", "ravine",
        "caldera-erekir", "stronghold", "crevice", "siege", "crossroads", "karst", "origin"};

    static final Map<String, String[]> blocks = new HashMap<>(), supply = new HashMap<>();

    static{
        blocks.put("onset", new String[]{"duct", "duct-router", "duct-bridge", "beam-node", "breach", "beryllium-wall", "beryllium-wall-large"});
        blocks.put("aegis", new String[]{"overflow-duct", "underflow-duct", "reinforced-conduit", "reinforced-liquid-router", "reinforced-liquid-junction",
            "reinforced-bridge-conduit", "reinforced-liquid-container", "reinforced-container", "duct-unloader", "tungsten-wall", "tungsten-wall-large"});
        blocks.put("lake", new String[]{"diffuse"});
        blocks.put("intersect", new String[]{"reinforced-liquid-tank"});
        blocks.put("atlas", new String[]{"blast-door"});
        blocks.put("marsh", new String[]{"sublimate"});
        blocks.put("peaks", new String[]{"regen-projector"});
        blocks.put("ravine", new String[]{"afflict", "heat-redirector", "electric-heater", "reinforced-surge-wall", "reinforced-surge-wall-large"});
        blocks.put("stronghold", new String[]{"titan", "disperse", "build-tower"});
        blocks.put("crevice", new String[]{"lustre", "carbide-wall", "carbide-wall-large"});
        blocks.put("siege", new String[]{"scathe", "shockwave-tower"});
        blocks.put("karst", new String[]{"smite", "shielded-wall"});
        blocks.put("origin", new String[]{"malign"});

        supply.put("onset", new String[]{"beryllium", "graphite", "silicon", "water"});
        supply.put("aegis", new String[]{"tungsten"});
        supply.put("atlas", new String[]{"ozone", "hydrogen"});
        supply.put("marsh", new String[]{"oxide"});
        supply.put("ravine", new String[]{"surge-alloy"});
        supply.put("caldera-erekir", new String[]{"nitrogen"});
        supply.put("stronghold", new String[]{"thorium"});
        supply.put("crevice", new String[]{"carbide"});
        supply.put("siege", new String[]{"cyanogen"});
        supply.put("karst", new String[]{"phase-fabric"});
    }

    static Set<String> upTo(Map<String, String[]> table, String sector){
        Set<String> out = new HashSet<>();
        for(String s : order){
            String[] add = table.get(s);
            if(add != null) out.addAll(Arrays.asList(add));
            if(s.equals(sector)) return out;
        }
        throw new IllegalArgumentException("no sector " + sector);
    }

    /** The defence blocks there by this sector. */
    public static Set<String> blocks(String sector){
        Set<String> out = upTo(blocks, sector);
        //the sources standing for supply lines in a module are never built: they are always "there"
        out.add("item-source");
        out.add("liquid-source");
        return out;
    }

    /** What the economy can feed turrets by this sector. */
    public static Set<String> supply(String sector){
        return upTo(supply, sector);
    }

    public static boolean known(String sector){
        return Arrays.asList(order).contains(sector);
    }
}
