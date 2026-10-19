package expansion.defense;

import java.util.*;

/**
 * One defence module of our own: a battery of turrets with its supply bus and buffers, written in the Blueprint text
 * format (see expansion.Blueprint): a [name] line, then "block x y [rotation] [setting]" for every part, positions
 * relative to the first part, the front along +x (toward the enemy). A part written "?block" goes up only where there is
 * room and what it needs is to be had (water for faster fire, a regen projector).
 *
 * Where the supply comes in is marked the way the economy's designs mark it (expansion.econ.Design): an item source or a
 * liquid source, set to what comes in there. On the map each becomes the router the supply line ends in; in the lab it
 * stays a source, giving as much as the line would.
 *
 * Lines starting "#!" are read here and skipped by the Blueprint reader as comments:
 * "#! role line close", "#! guns breach 2", "#! needs water optional", "#! about ...".
 *
 * This is plain data: it reads the text with no game loaded. How big a block is and whether it turns come from
 * {@link #shapes}, which the game side fills in from the real blocks (GameData) and which holds the blocks used here.
 */
public final class Battery{
    public static final class Part{
        public final String block;
        public final int x, y, rotation;
        public final String config;
        public final boolean optional;

        public Part(String block, int x, int y, int rotation, String config, boolean optional){
            this.block = block;
            this.x = x;
            this.y = y;
            this.rotation = rotation;
            this.config = config;
            this.optional = optional;
        }

        public int size(){
            return shape(block)[0];
        }

        /** Whether this marks where supply comes in. */
        public boolean port(){
            return block.equals("item-source") || block.equals("liquid-source");
        }

        public boolean liquidPort(){
            return block.equals("liquid-source");
        }

        @Override
        public String toString(){
            return (optional ? "?" : "") + block + " " + x + " " + y + " " + rotation + (config == null ? "" : " " + config);
        }
    }

    public final String name;
    public final List<Part> parts = new ArrayList<>();
    public final EnumSet<Gun.Role> roles = EnumSet.noneOf(Gun.Role.class);
    public String about = "";

    Battery(String name){
        this.name = name;
    }

    public Part main(){
        return parts.get(0);
    }

    /** The turret the module is built round. */
    public Gun gun(){
        return Gun.get(main().block);
    }

    /** How many of its turret it has. */
    public int guns(){
        int n = 0;
        for(Part p : parts) if(p.block.equals(main().block)) n++;
        return n;
    }

    public List<Part> ports(){
        List<Part> out = new ArrayList<>();
        for(Part p : parts) if(p.port()) out.add(p);
        return out;
    }

    /** Sizes of blocks and whether they turn: {size, 1 if it turns}. The game side puts in the real ones. */
    public static final Map<String, int[]> shapes = new HashMap<>();

    static{
        for(String n : new String[]{"breach", "diffuse", "sublimate", "reinforced-liquid-tank", "regen-projector", "build-tower", "heat-router"}) shapes.put(n, new int[]{3, 0});
        for(String n : new String[]{"titan", "disperse", "afflict", "lustre", "scathe"}) shapes.put(n, new int[]{4, 0});
        for(String n : new String[]{"smite", "malign"}) shapes.put(n, new int[]{5, 0});
        for(String n : new String[]{"reinforced-container", "reinforced-liquid-container", "beryllium-wall-large", "tungsten-wall-large", "carbide-wall-large", "reinforced-surge-wall-large", "blast-door", "shielded-wall"}) shapes.put(n, new int[]{2, 0});
        shapes.put("electric-heater", new int[]{2, 1});
        shapes.put("heat-redirector", new int[]{3, 1});
        for(String n : new String[]{"duct", "duct-router", "duct-unloader", "overflow-duct", "underflow-duct", "armored-duct", "reinforced-conduit"}) shapes.put(n, new int[]{1, 1});
        for(String n : new String[]{"item-source", "liquid-source", "reinforced-liquid-router", "beam-node", "beryllium-wall", "tungsten-wall", "carbide-wall", "reinforced-surge-wall", "reinforced-liquid-junction"}) shapes.put(n, new int[]{1, 0});
        shapes.put("reinforced-bridge-conduit", new int[]{1, 1});
        shapes.put("duct-bridge", new int[]{1, 1});
    }

    public static int[] shape(String block){
        int[] s = shapes.get(block);
        if(s == null) throw new IllegalArgumentException("no shape known for " + block);
        return s;
    }

    /** Reads every module in a text. Lines it cannot read are reported and the module left out. */
    public static List<Battery> parse(String text){
        List<Battery> out = new ArrayList<>();
        Battery current = null;
        boolean broken = false;
        for(String raw : text.split("\n")){
            String line = raw.trim();
            if(line.startsWith("#!") && current != null && !broken){
                String[] a = line.substring(2).trim().split("\\s+");
                if(a[0].equals("role")) for(int i = 1; i < a.length; i++) current.roles.add(Gun.Role.valueOf(a[i]));
                if(a[0].equals("about")) current.about = line.substring(line.indexOf("about") + 5).trim();
                continue;
            }
            int comment = line.indexOf('#');
            if(comment >= 0) line = line.substring(0, comment).trim();
            if(line.isEmpty()) continue;
            if(line.startsWith("[") && line.endsWith("]")){
                current = new Battery(line.substring(1, line.length() - 1).trim());
                broken = false;
                out.add(current);
                continue;
            }
            if(current == null || broken) continue;
            String[] a = line.split("\\s+");
            boolean optional = a[0].startsWith("?");
            String block = optional ? a[0].substring(1) : a[0];
            if(!shapes.containsKey(block) || a.length < 3){
                System.err.println("[defense] module " + current.name + " left out: cannot read \"" + line + "\"");
                out.remove(current);
                broken = true;
                continue;
            }
            int rotation = a.length > 3 && isInt(a[3]) ? Integer.parseInt(a[3]) : 0;
            String config = a.length > 4 ? a[4] : a.length > 3 && !isInt(a[3]) ? a[3] : null;
            current.parts.add(new Part(block, Integer.parseInt(a[1]), Integer.parseInt(a[2]), rotation, config, optional));
        }
        out.removeIf(m -> m.parts.isEmpty());
        return out;
    }

    static boolean isInt(String s){
        try{
            Integer.parseInt(s);
            return true;
        }catch(NumberFormatException e){
            return false;
        }
    }

    /**
     * The parts as they stand with the main block at (x, y), turned a number of quarter turns counter-clockwise from +x.
     * Turned about the middle of the main block the way the game turns schematics (and expansion.Blueprint does), so that
     * blocks of even size land on the right tiles.
     */
    public List<Part> at(int x, int y, int turns){
        turns = Math.floorMod(turns, 4);
        float pivot = offset(main().size());
        List<Part> out = new ArrayList<>();
        for(Part p : parts){
            float off = offset(p.size());
            float px = p.x * 8 + off - pivot, py = p.y * 8 + off - pivot;
            for(int i = 0; i < turns; i++){
                float prev = px;
                px = -py;
                py = prev;
            }
            int tx = Math.round((px + pivot - off) / 8f), ty = Math.round((py + pivot - off) / 8f);
            int rot = shape(p.block)[1] == 1 ? Math.floorMod(p.rotation + turns, 4) : 0;
            out.add(new Part(p.block, x + tx, y + ty, rot, p.config, p.optional));
        }
        return out;
    }

    static float offset(int size){
        return (size + 1) % 2 * 4f;
    }

    /** The lowest tile a block of this size covers, from the tile it stands on. */
    public static int low(int at, int size){
        return at - (size - 1) / 2;
    }

    /** Every tile the parts cover, packed as x + y * 65536 (x and y may be negative in module space). */
    public static Set<Long> cover(List<Part> parts, boolean withOptional){
        Set<Long> out = new HashSet<>();
        for(Part p : parts){
            if(p.optional && !withOptional) continue;
            int s = p.size(), lx = low(p.x, s), ly = low(p.y, s);
            for(int dx = 0; dx < s; dx++) for(int dy = 0; dy < s; dy++) out.add(key(lx + dx, ly + dy));
        }
        return out;
    }

    public static long key(int x, int y){
        return ((long)x << 32) ^ (y & 0xffffffffL);
    }

    public static int kx(long key){
        return (int)(key >> 32);
    }

    public static int ky(long key){
        return (int)key;
    }

    /** The box round the module as drawn: {minX, minY, maxX, maxY} in tiles relative to the main block. */
    public int[] box(){
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for(long k : cover(parts, true)){
            b[0] = Math.min(b[0], kx(k));
            b[1] = Math.min(b[1], ky(k));
            b[2] = Math.max(b[2], kx(k));
            b[3] = Math.max(b[3], ky(k));
        }
        return b;
    }

    /** What it costs, without its ports and with or without its optional parts, by item; block costs come from the table given. */
    public Map<String, Integer> cost(Map<String, Map<String, Integer>> blockCost, boolean withOptional){
        Map<String, Integer> out = new TreeMap<>();
        for(Part p : parts){
            if(p.port() || (p.optional && !withOptional)) continue;
            Map<String, Integer> c = blockCost.get(p.block);
            if(c != null) c.forEach((k, v) -> out.merge(k, v, Integer::sum));
        }
        return out;
    }
}
