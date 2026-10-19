package expansion.econ;

import arc.files.*;
import arc.struct.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.game.Schematic.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.meta.*;

import java.util.*;

/**
 * A design that makes something, built as one piece: a schematic people use, measured in the blueprint lab
 * (xtest.ModLab). Its item and liquid sources mark where what it takes comes in, its voids where what it makes goes out;
 * the lab measured how much passes each of them, what power it uses and how its machines ran. The planner takes a module
 * for a machine that makes and takes those amounts; the layout puts all of it down as it is, turned four ways, and leads
 * lines to and from its ports.
 *
 * On the map a source becomes a router (a duct router, or a liquid router) the line ends in, which hands on to the same
 * neighbours the source did; a void becomes the tile the line away from the module starts on.
 */
public final class Design{
    public final String id, name;
    /** The design as drawn, and turned 0..3 times a quarter (the same turn as a block's rotation). */
    public final Schematic[] turned = new Schematic[4];
    /** Measured, per second: what passes each port, by item or liquid name; and units made. */
    public final List<Port> ports = new ArrayList<>();
    public final ObjectFloatMap<String> units = new ObjectFloatMap<>();
    public final float power;
    /** Built of, by item name (without its sources and voids). */
    public final ObjectIntMap<String> cost = new ObjectIntMap<>();
    public float buildTicks;

    /** An input (a source in the design) or an output (a void). Positions are in the design as drawn. */
    public static final class Port{
        public final boolean in, liquid;
        public final String what;
        public final int x, y;
        public final float rate;
        /** Which tile of the design marks it (the same in every turn). */
        public int index;
        /**
         * As the lab put it down, in the design as drawn: for an input, the way the router on it faces (the line comes in
         * from behind); for an output, the way the first piece of the line away from it faces. -1 if not known.
         */
        public int turn = -1;

        Port(boolean in, boolean liquid, String what, int x, int y, float rate){
            this.in = in;
            this.liquid = liquid;
            this.what = what;
            this.x = x;
            this.y = y;
            this.rate = rate;
        }
    }

    Design(String id, String name, Schematic s, float power){
        this.id = id;
        this.name = name;
        this.power = power;
        turned[0] = s;
        for(int o = 1; o < 4; o++) turned[o] = turn(turned[o - 1]);
        for(Stile t : s.tiles){
            if(sandbox(t.block)) continue;
            for(ItemStack stack : t.block.requirements) cost.increment(stack.item.name, stack.amount);
            buildTicks += t.block.buildTime;
        }
    }

    /**
     * The design turned a quarter anticlockwise (as a block's rotation goes), tile for tile in the same order, moved so its
     * corner is at 0,0. Each block's tiles are turned and it stands on the turned square; a bridge's link turns with it.
     */
    public static Schematic turn(Schematic s){
        Seq<Stile> tiles = new Seq<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        for(Stile t : s.tiles){
            int size = t.block.size, off = (size - 1) / 2;
            //the block's corner tile turned is its new top-left... take the smallest corner of the turned square
            int x0 = t.x - off, y0 = t.y - off, x1 = x0 + size - 1, y1 = y0 + size - 1;
            int nx = Math.min(-y0, -y1), ny = Math.min(x0, x1);
            Stile n = new Stile(t.block, nx + off, ny + off, mindustry.entities.units.BuildPlan.pointConfig(t.block, t.config, p -> p.set(-p.y, p.x)), (byte)((t.rotation + 1) % 4));
            tiles.add(n);
            minX = Math.min(minX, nx);
            minY = Math.min(minY, ny);
        }
        for(Stile t : tiles){
            t.x = (short)(t.x - minX);
            t.y = (short)(t.y - minY);
        }
        return new Schematic(tiles, new arc.struct.StringMap(), s.height, s.width);
    }

    static boolean sandbox(Block b){
        return b.buildVisibility == BuildVisibility.sandboxOnly;
    }

    /** Width and height when turned o times. */
    public int width(int o){
        return turned[o].width;
    }

    public int height(int o){
        return turned[o].height;
    }

    private final int[][][] foot = new int[4][][];

    /**
     * The tiles it covers turned o times, from its corner: every tile of every block, and the tiles of its ports (the
     * router an input becomes, the first piece of an output's line), {dx, dy} each.
     */
    public int[][] footprint(int o){
        if(foot[o] != null) return foot[o];
        arc.struct.IntSet seen = new arc.struct.IntSet();
        List<int[]> out = new ArrayList<>();
        for(Stile t : turned[o].tiles){
            boolean port = sandbox(t.block);
            int size = port ? 1 : t.block.size, off = (size - 1) / 2;
            for(int dx = 0; dx < size; dx++){
                for(int dy = 0; dy < size; dy++){
                    int x = t.x - off + dx, y = t.y - off + dy;
                    if(seen.add(x * 4096 + y)) out.add(new int[]{x, y});
                }
            }
        }
        //and a lane straight out from each port, LANE tiles long: the way a line comes into an input (behind its router)
        //and the way an output's line goes off; kept clear, or ports side by side by a cliff can be shut in
        for(int[] t : lanes(o)) if(seen.add(t[0] * 4096 + t[1])) out.add(t);
        return foot[o] = out.toArray(new int[0][]);
    }

    /** How long the lane kept clear before each port is. */
    public static final int LANE = 3;

    /** The lane tiles before every port, turned o times, from the design's corner. */
    public List<int[]> lanes(int o){
        List<int[]> out = new ArrayList<>();
        for(int[] port : portsAt(o)){
            if(port[4] < 0) continue;
            int dx = arc.math.geom.Geometry.d4x(port[4] % 4), dy = arc.math.geom.Geometry.d4y(port[4] % 4);
            for(int i = 1; i <= LANE; i++){
                int x = port[0] == 1 ? port[2] - dx * i : port[2] + dx * i, y = port[0] == 1 ? port[3] - dy * i : port[3] + dy * i;
                out.add(new int[]{x, y});
            }
        }
        return out;
    }

    /** The block that stands for the whole design where only one can (the biggest that uses power, else the biggest), turned o times. */
    public Stile main(int o){
        Stile best = null;
        for(Stile t : parts(o)){
            boolean powered = t.block.consPower != null, bp = best != null && best.block.consPower != null;
            if(best == null || (powered && !bp) || (powered == bp && t.block.size > best.block.size)) best = t;
        }
        return best;
    }

    /** The design's blocks turned o times, without its sources and voids: what is built. */
    public Seq<Stile> parts(int o){
        return turned[o].tiles.select(t -> !sandbox(t.block));
    }

    /**
     * The ports turned o times: {in (1/0), liquid (1/0), x, y} in the turned design, in the order of {@link #ports}. Found
     * again in the turned design by the source or void that marks each (the game turns them with the rest).
     */
    private final int[][][] portCache = new int[4][][];

    public int[][] portsAt(int o){
        if(portCache[o] != null) return portCache[o];
        int[][] out = portCache[o] = new int[ports.size()][];
        for(int i = 0; i < ports.size(); i++){
            Port p = ports.get(i);
            Stile t = turned[o].tiles.get(p.index);
            //(a turn of 4 and up is a plain duct into the design facing turn - 4: it turns the same way, and stays 4 and up)
            out[i] = new int[]{p.in ? 1 : 0, p.liquid ? 1 : 0, t.x, t.y, p.turn < 0 ? -1 : p.turn >= 4 ? 4 + (p.turn - 4 + o) % 4 : (p.turn + o) % 4};
        }
        return out;
    }

    /** The modules of a library: the lab's table and the schematic files beside it. Those that can't be read are left out. */
    public static List<Design> load(Fi dir){
        List<Design> out = new ArrayList<>();
        Fi table = dir.child("modules.tsv");
        if(!table.exists()) return out;
        String[] lines = table.readString().split("\n");
        String[] head = lines[0].split("\t");
        Map<String, Integer> col = new HashMap<>();
        for(int i = 0; i < head.length; i++) col.put(head[i].trim(), i);
        for(int l = 1; l < lines.length; l++){
            String[] f = lines[l].split("\t", -1);
            if(f.length < head.length) continue;
            String id = f[col.get("id")];
            Schematic s;
            try{
                s = Schematics.read(dir.child(id + ".msch"));
            }catch(Throwable t){
                continue;
            }
            Design m = new Design(id, f[col.get("name")], s, Float.parseFloat(f[col.get("power")]));
            ports(m, f[col.get("inputs")], true);
            ports(m, f[col.get("outputs")], false);
            boolean found = true;
            for(Port p : m.ports){
                p.index = -1;
                for(int i = 0; i < s.tiles.size; i++){
                    Stile t = s.tiles.get(i);
                    if(t.x == p.x && t.y == p.y && sandbox(t.block)) p.index = i;
                }
                if(p.index < 0) found = false;
            }
            if(!found) continue;
            for(String u : f[col.get("units_per_min")].split(";")){
                if(!u.contains("=")) continue;
                m.units.put(u.split("=")[0], Float.parseFloat(u.split("=")[1]) / 60f);
            }
            out.add(m);
        }
        return out;
    }

    static void ports(Design m, String text, boolean in){
        for(String p : text.split(";")){
            if(p.isEmpty()) continue;
            int at = p.lastIndexOf('@'), eq = p.lastIndexOf('=');
            String what = p.substring(0, at);
            String place = p.substring(at + 1, eq);
            int turn = -1;
            if(place.contains(":")){
                turn = Integer.parseInt(place.substring(place.indexOf(':') + 1));
                //(0..3: a router facing that way; 4..7: a plain duct facing turn - 4)
                place = place.substring(0, place.indexOf(':'));
            }
            String[] xy = place.split(",");
            float rate = Float.parseFloat(p.substring(eq + 1));
            //a void that got nothing, or several things (what it was fed, passing through), is no product
            if(!in && (rate <= 1e-3f || what.contains("+") || what.equals("nothing"))) continue;
            boolean liquid = Vars.content.liquid(what) != null;
            Port port = new Port(in, liquid, what, Integer.parseInt(xy[0]), Integer.parseInt(xy[1]), rate);
            port.turn = turn;
            m.ports.add(port);
        }
    }
}
