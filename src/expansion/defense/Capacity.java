package expansion.defense;

import java.util.*;

/**
 * What each module holds, as measured in the lab (xtest.TurretLab, "./test.sh turretlab suite:caps"): the largest single
 * wave of one kind of unit that one module, fed one way, kills with nothing lost but walls and nobody through the line.
 * The planner sizes defences from this table and nothing else, so its rules come from the turrets' roles as the game
 * plays them, not from guesses per map.
 *
 * It is measured twice: in a lane 15 tiles wide that the wall line closes from rock to rock, so that tanks have to stop
 * and break through under fire; and in open ground 41 wide where the walls stand only in front of the turrets and units
 * can walk round them. A place the planner cannot close is sized by the second.
 *
 * File lines: module ammo wet|dry wall[:width] unit holds seconds used (blueprints/defense/capacity.txt).
 */
public final class Capacity{
    /** Lanes wider than this were measured as open ground. */
    public static final int closedWidth = 21;

    /** One way of feeding a module: which module, on what ammunition, with water or not. */
    public static final class Config{
        public final String module, ammo;
        public final boolean wet;
        /** The largest wave of each unit it holds, in a closed lane and in open ground; a unit it cannot hit is missing. */
        public final Map<String, Integer> holds = new TreeMap<>(), open = new TreeMap<>();
        /** Seconds it took to kill that wave (closed lane), and what it used for it, by item or liquid. */
        public final Map<String, Float> seconds = new TreeMap<>();
        public final Map<String, Map<String, Integer>> used = new TreeMap<>();

        Config(String module, String ammo, boolean wet){
            this.module = module;
            this.ammo = ammo;
            this.wet = wet;
        }

        public String key(){
            return module + " " + ammo + " " + (wet ? "wet" : "dry");
        }

        /** The largest wave of a unit it holds, closed or open (in open ground, half the closed figure where it was not measured). */
        public int holds(String unit, boolean closed){
            Integer n = closed ? holds.get(unit) : open.get(unit);
            if(n == null && !closed && holds.containsKey(unit)) return holds.get(unit) / 2;
            return n == null ? 0 : n;
        }

        /** How much of the module a wave of units takes up: 1 is all it holds. Infinite if it cannot hold one of them. */
        public float load(Map<String, Integer> units, boolean closed){
            float load = 0f;
            for(var e : units.entrySet()){
                if(e.getValue() <= 0) continue;
                int n = holds(e.getKey(), closed);
                if(n <= 0) return Float.POSITIVE_INFINITY;
                load += e.getValue() / (float)n;
            }
            return load;
        }

        public float load(Map<String, Integer> units){
            return load(units, true);
        }
    }

    public final Map<String, Config> configs = new LinkedHashMap<>();

    public static Capacity parse(String text){
        Capacity c = new Capacity();
        for(String raw : text.split("\n")){
            String line = raw.trim();
            if(line.isEmpty() || line.startsWith("#")) continue;
            String[] a = line.split("\\s+");
            if(a.length < 6) continue;
            Config config = c.configs.computeIfAbsent(a[0] + " " + a[1] + " " + a[2], k -> new Config(a[0], a[1], a[2].equals("wet")));
            int width = a[3].contains(":") ? Integer.parseInt(a[3].substring(a[3].indexOf(':') + 1)) : 15;
            boolean closed = width <= closedWidth;
            (closed ? config.holds : config.open).put(a[4], Integer.parseInt(a[5]));
            if(!closed) continue;
            if(a.length > 6) config.seconds.put(a[4], Float.parseFloat(a[6]));
            if(a.length > 7 && !a[7].equals("-")){
                Map<String, Integer> used = new TreeMap<>();
                for(String u : a[7].split(",")) used.put(u.split(":")[0], Integer.parseInt(u.split(":")[1]));
                config.used.put(a[4], used);
            }
        }
        return c;
    }
}
