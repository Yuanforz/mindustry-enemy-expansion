package xtest;

import arc.files.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.ctype.*;
import mindustry.game.*;
import mindustry.game.Schematic.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.power.ConsumeGenerator;
import mindustry.world.meta.*;

import java.util.*;

/**
 * Our own production designs, drawn from patterns with a few parameters (how many machines, which heaters) and written as
 * schematics for the blueprint lab (ModLab) to measure: the shipped module library is made of the ones that pass.
 *
 * A design marks each input with an item or liquid source and each output with a void, the way shared designs do, on its
 * outer edge with a free tile beyond: the source (and void) is drawn turned the way its line goes, an input's router
 * facing into the design and an output's first piece facing away (the lab honours that with -Dlab.portturn). Power: each
 * design is joined into one grid by beam nodes put down where they join most (the layout powers a design through one of
 * its blocks).
 *
 * Usage: data-dir out-dir [name-prefix...]   writes out-dir/id.msch, out-dir/id.txt (a picture) and out-dir/designs.tsv
 */
public class DesignGen{
    static final int E = 0, N = 1, W = 2, S = 3;

    // ---------------------------------------------------------------- a design being drawn

    /** A design being drawn: blocks put down by their lower-left tile, in any coordinates; moved to 0,0 when written. */
    static final class Plan{
        final String id, product;
        String pattern;
        final List<Stile> tiles = new ArrayList<>();
        /** Which block covers each tile (packed x,y). */
        final IntMap<Stile> cover = new IntMap<>();
        /** Tiles kept free: where the line into an input comes from, where the line from an output goes on to. */
        final IntSet reserved = new IntSet();

        Plan(String id, String product, String pattern){
            this.id = id;
            this.product = product;
            this.pattern = pattern;
        }

        Plan put(Block b, int x, int y){
            return put(b, x, y, 0, null);
        }

        Plan put(Block b, int x, int y, int rot){
            return put(b, x, y, rot, null);
        }

        /** A block with its lower-left tile at x,y. */
        Plan put(Block b, int x, int y, int rot, Object config){
            int off = (b.size - 1) / 2;
            Stile s = new Stile(b, x + off, y + off, config, (byte)rot);
            for(int dx = 0; dx < b.size; dx++){
                for(int dy = 0; dy < b.size; dy++){
                    int k = Point2.pack(x + dx, y + dy);
                    if(cover.containsKey(k)) throw new IllegalStateException(id + ": " + b.name + " at " + x + "," + y + " overlaps " + cover.get(k).block.name);
                    if(reserved.contains(k)) throw new IllegalStateException(id + ": " + b.name + " at " + x + "," + y + " is on a port's free tile");
                    cover.put(k, s);
                }
            }
            tiles.add(s);
            return this;
        }

        /** A 1x1 block put down for a moment, to see what it would do. */
        Stile place(Block b, int x, int y){
            Stile s = new Stile(b, x, y, null, (byte)0);
            cover.put(Point2.pack(x, y), s);
            tiles.add(s);
            return s;
        }

        void remove(Stile s){
            int off = (s.block.size - 1) / 2;
            for(int dx = 0; dx < s.block.size; dx++) for(int dy = 0; dy < s.block.size; dy++) cover.remove(Point2.pack(s.x - off + dx, s.y - off + dy));
            tiles.remove(s);
        }

        boolean free(int x, int y){
            int k = Point2.pack(x, y);
            return !cover.containsKey(k) && !reserved.contains(k);
        }

        Stile at(int x, int y){
            return cover.get(Point2.pack(x, y));
        }

        /** An input: its router stands at x,y facing dir (into the design); the line comes in from the tile behind. */
        Plan in(UnlockableContent what, int x, int y, int dir){
            reserve(x - Geometry.d4x(dir), y - Geometry.d4y(dir));
            return put(what instanceof Liquid ? Blocks.liquidSource : Blocks.itemSource, x, y, dir, what);
        }

        /** An output: the first piece of the line away stands at x,y facing dir; the design hands to it from behind. */
        Plan out(boolean liquid, int x, int y, int dir){
            reserve(x + Geometry.d4x(dir), y + Geometry.d4y(dir));
            return put(liquid ? Blocks.liquidVoid : Blocks.itemVoid, x, y, dir, null);
        }

        void reserve(int x, int y){
            int k = Point2.pack(x, y);
            if(cover.containsKey(k)) throw new IllegalStateException(id + ": the free tile of a port at " + x + "," + y + " is taken by " + cover.get(k).block.name);
            reserved.add(k);
        }

        int[] bounds(){
            int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
            for(var e : cover){
                int x = Point2.x(e.key), y = Point2.y(e.key);
                x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y);
            }
            return new int[]{x0, y0, x1, y1};
        }

        Schematic schematic(){
            int[] b = bounds();
            Seq<Stile> out = new Seq<>();
            for(Stile t : tiles) out.add(new Stile(t.block, t.x - b[0], t.y - b[1], t.config, t.rotation));
            StringMap tags = new StringMap();
            tags.put("name", id);
            tags.put("description", "generated: " + pattern);
            return new Schematic(out, tags, b[2] - b[0] + 1, b[3] - b[1] + 1);
        }

        /** The design as text, two characters a tile: a letter for the block and its facing (or '.' inside a big block). */
        String picture(){
            int[] b = bounds();
            StringBuilder sb = new StringBuilder();
            Map<Block, Character> letter = new LinkedHashMap<>();
            for(int y = b[3]; y >= b[1]; y--){
                sb.append(String.format("%3d ", y));
                for(int x = b[0]; x <= b[2]; x++){
                    Stile s = at(x, y);
                    if(s == null){
                        sb.append(reserved.contains(Point2.pack(x, y)) ? ", " : "  ");
                        continue;
                    }
                    char c = letter.computeIfAbsent(s.block, DesignGen::letterOf);
                    int off = (s.block.size - 1) / 2;
                    boolean origin = s.x - off == x && s.y - off == y;
                    sb.append(c).append(origin && (s.block.rotate || isPort(s.block)) ? ">^<v".charAt(s.rotation) : '.');
                }
                sb.append('\n');
            }
            sb.append("    ");
            for(var e : letter.entrySet()) sb.append(e.getValue()).append('=').append(e.getKey().name).append("  ");
            sb.append('\n');
            return sb.toString();
        }
    }

    static char letterOf(Block b){
        String[][] names = {{"silicon-arc-furnace", "F"}, {"oxidation-chamber", "O"}, {"electrolyzer", "Z"}, {"chemical-combustion-chamber", "C"},
            {"pyrolysis-generator", "P"}, {"atmospheric-concentrator", "A"}, {"carbide-crucible", "X"}, {"surge-crucible", "U"}, {"phase-synthesizer", "Y"},
            {"cyanogen-synthesizer", "K"}, {"electric-heater", "e"}, {"slag-heater", "g"}, {"phase-heater", "h"}, {"heat-redirector", "i"},
            {"duct", "d"}, {"duct-router", "R"}, {"duct-bridge", "B"}, {"beam-node", "n"}, {"reinforced-conduit", "~"}, {"reinforced-liquid-router", "r"},
            {"reinforced-bridge-conduit", "b"}, {"reinforced-liquid-junction", "j"}, {"item-source", "I"}, {"liquid-source", "L"}, {"item-void", "V"}, {"liquid-void", "Q"}};
        for(String[] n : names) if(n[0].equals(b.name)) return n[1].charAt(0);
        return '?';
    }

    /** Sources and voids, which mark a design's ports. */
    static boolean isPort(Block b){
        return b == Blocks.itemSource || b == Blocks.liquidSource || b == Blocks.itemVoid || b == Blocks.liquidVoid;
    }

    // ---------------------------------------------------------------- power: beam nodes where they join most

    static boolean powered(Block b){
        return b.hasPower && b.connectedPower && b.buildVisibility != BuildVisibility.sandboxOnly;
    }

    /** Whether two touching powered blocks are joined by the game (two plain consumers are not). */
    static boolean joins(Block a, Block b){
        return !(a.consumesPower && b.consumesPower && !a.outputsPower && !b.outputsPower && !a.conductivePower && !b.conductivePower);
    }

    static int find(int[] parent, int i){
        while(parent[i] != i) i = parent[i] = parent[parent[i]];
        return i;
    }

    /**
     * How many grids the design's powered blocks make, the game's way: touching blocks join (unless both only use
     * power), and a beam node joins, along each of its four axes, the first powered block within its range. A grid counts
     * if it has a block that uses or makes power other than a node.
     */
    static int grids(Plan p){
        Block node = Blocks.beamNode;
        int range = ((BeamNode)node).range;
        List<Stile> pw = new ArrayList<>();
        Map<Stile, Integer> index = new HashMap<>();
        for(Stile t : p.tiles) if(powered(t.block)){ index.put(t, pw.size()); pw.add(t); }
        int[] parent = new int[pw.size()];
        for(int i = 0; i < parent.length; i++) parent[i] = i;
        for(Stile t : pw){
            int off = (t.block.size - 1) / 2;
            for(int dx = -1; dx <= t.block.size; dx++){
                for(int dy = -1; dy <= t.block.size; dy++){
                    boolean edge = (dx == -1 || dx == t.block.size) != (dy == -1 || dy == t.block.size);
                    if(!edge) continue;
                    Stile o = p.at(t.x - off + dx, t.y - off + dy);
                    if(o != null && index.containsKey(o) && joins(t.block, o.block)) parent[find(parent, index.get(t))] = find(parent, index.get(o));
                }
            }
            if(t.block == node){
                for(int d = 0; d < 4; d++){
                    Stile o = beam(p, t.x, t.y, d, range);
                    if(o != null) parent[find(parent, index.get(t))] = find(parent, index.get(o));
                }
            }
        }
        IntSet real = new IntSet();
        for(Stile t : pw) if(t.block != node) real.add(find(parent, index.get(t)));
        return real.size;
    }

    /**
     * Joins the design's powered blocks into one grid with beam nodes: each step puts down the node (or, when no one node
     * helps, the two nodes in line with each other) that leaves the fewest grids, inside the design if that can be done,
     * else one tile out.
     */
    static void power(Plan p){
        Block node = Blocks.beamNode;
        int range = ((BeamNode)node).range;
        for(int step = 0; step < 100; step++){
            int now = grids(p);
            if(now <= 1) return;
            int[] b = p.bounds();
            int best = now;
            int[] pick = null;
            for(int margin = 0; margin <= 1 && pick == null; margin++){
                for(int x = b[0] - margin; x <= b[2] + margin; x++){
                    for(int y = b[1] - margin; y <= b[3] + margin; y++){
                        if(!p.free(x, y)) continue;
                        Stile a = p.place(node, x, y);
                        int g = grids(p);
                        p.remove(a);
                        if(g < best){
                            best = g;
                            pick = new int[]{x, y};
                        }
                    }
                }
            }
            for(int margin = 0; margin <= 1 && pick == null; margin++){
                for(int x = b[0] - margin; x <= b[2] + margin; x++){
                    for(int y = b[1] - margin; y <= b[3] + margin; y++){
                        if(!p.free(x, y)) continue;
                        for(int d = 0; d < 2; d++){
                            for(int j = 2; j <= range; j++){
                                int qx = x + Geometry.d4x(d) * j, qy = y + Geometry.d4y(d) * j;
                                Stile o = p.at(qx, qy);
                                if(o != null && powered(o.block)) break;
                                if(!p.free(qx, qy) || qx < b[0] - margin || qx > b[2] + margin || qy < b[1] - margin || qy > b[3] + margin) continue;
                                Stile a1 = p.place(node, x, y), a2 = p.place(node, qx, qy);
                                int g = grids(p);
                                p.remove(a1);
                                p.remove(a2);
                                if(g < best){
                                    best = g;
                                    pick = new int[]{x, y, qx, qy};
                                }
                            }
                        }
                    }
                }
            }
            if(pick == null) throw new IllegalStateException(p.id + ": can't join its power with beam nodes");
            p.put(node, pick[0], pick[1]);
            if(pick.length > 2) p.put(node, pick[2], pick[3]);
        }
    }

    /** The first powered block a beam from x,y going dir meets within range, or null. */
    static Stile beam(Plan p, int x, int y, int dir, int range){
        for(int j = 1; j <= range; j++){
            Stile o = p.at(x + Geometry.d4x(dir) * j, y + Geometry.d4y(dir) * j);
            if(o != null && powered(o.block)) return o;
        }
        return null;
    }

    // ---------------------------------------------------------------- the patterns

    static List<Plan> all(){
        List<Plan> out = new ArrayList<>();
        for(int n : new int[]{1, 2, 3, 4, 6}) out.add(silicon(n));
        for(int n : new int[]{1, 2, 4, 8}) out.add(oxideOzone(n));
        for(int m : new int[]{1, 2}) out.add(oxideWater(m));
        for(int n : new int[]{1, 2, 4}) out.add(electrolyzers(n));
        for(int n : new int[]{1, 2, 4}) out.add(combustionOzone(n));
        for(int m : new int[]{1, 2}) out.add(combustionWater(m));
        for(int n : new int[]{1, 2, 4}) out.add(pyrolysis(n));
        out.add(heated("carbide", Blocks.carbideCrucible, "slag", 2, 0, 0, 1));
        out.add(heated("carbide", Blocks.carbideCrucible, "phase", 1, 0, 0, 1));
        out.add(heated("carbide", Blocks.carbideCrucible, "electric", 4, 12, 12, 2));
        out.add(heated("surge-alloy", Blocks.surgeCrucible, "slag", 2, 0, 0, 1));
        out.add(heated("surge-alloy", Blocks.surgeCrucible, "phase", 1, 0, 0, 1));
        out.add(heated("surge-alloy", Blocks.surgeCrucible, "electric", 4, 12, 12, 2));
        out.add(heated("phase-fabric", Blocks.phaseSynthesizer, "slag", 2, 0, 0, 0));
        out.add(heated("phase-fabric", Blocks.phaseSynthesizer, "phase", 1, 0, 0, 1));
        out.add(heated("phase-fabric", Blocks.phaseSynthesizer, "electric", 3, 9, 9, 2));
        out.add(heated("nitrogen", Blocks.atmosphericConcentrator, "slag", 1, 0, 0, 1));
        out.add(heated("nitrogen", Blocks.atmosphericConcentrator, "phase", 1, 0, 0, 0));
        out.add(heated("nitrogen", Blocks.atmosphericConcentrator, "electric", 2, 8, 8, 0));
        out.add(heated("cyanogen", Blocks.cyanogenSynthesizer, "slag", 1, 0, 0, 1));
        out.add(heated("cyanogen", Blocks.cyanogenSynthesizer, "phase", 1, 0, 0, 0));
        out.add(heated("cyanogen", Blocks.cyanogenSynthesizer, "electric", 2, 6, 6, 1));
        out.add(phaseDirect("carbide", Blocks.carbideCrucible));
        out.add(phaseDirect("surge-alloy", Blocks.surgeCrucible));
        out.add(phaseDirect("phase-fabric", Blocks.phaseSynthesizer));
        out.add(slagDirect("nitrogen", Blocks.atmosphericConcentrator));
        out.add(slagDirect("cyanogen", Blocks.cyanogenSynthesizer));
        out.add(heatedStack("carbide", Blocks.carbideCrucible, 2, true));
        out.add(heatedStack("surge-alloy", Blocks.surgeCrucible, 2, true));
        out.add(heatedStack("phase-fabric", Blocks.phaseSynthesizer, 2, false));
        out.add(heatedStack("nitrogen", Blocks.atmosphericConcentrator, 1, true));
        out.add(heatedStack("cyanogen", Blocks.cyanogenSynthesizer, 1, true));
        return out;
    }

    /**
     * The row most of our designs are built on: machines side by side in pairs, a one-tile gap inside each pair (a lone
     * last machine gets a gap of its own). Returns the lower-left x of each machine and the x of each gap, from x = 1
     * (column 0 is for the ports at the west end); and the first column east of the row.
     */
    static int[][] row(int n){
        int[] machines = new int[n], gaps = new int[(n + 1) / 2];
        int x = 1;
        for(int i = 0; i < n; i += 2){
            machines[i] = x;
            gaps[i / 2] = x + 3;
            if(i + 1 < n){
                machines[i + 1] = x + 4;
                x += 7;
            }else{
                x += 4;
            }
        }
        return new int[][]{machines, gaps, {x}};
    }

    /**
     * An item line along y, from x0 to x1 in the way it goes (dir E or W): ducts, and a duct router at each x in drops,
     * which hands to the stub beside it (a duct pointing into a machine: a machine can't hand back into a duct's front,
     * nor into a router, which takes only from behind) as well as on along the line. It ends at its last router.
     */
    static void itemLine(Plan p, int y, int x0, int x1, int dir, IntSet drops){
        int step = x1 >= x0 ? 1 : -1, last = Integer.MIN_VALUE;
        for(int x = x0; x != x1 + step; x += step) if(drops.contains(x)) last = x;
        if(last == Integer.MIN_VALUE) return;
        for(int x = x0; x != last + step; x += step) p.put(drops.contains(x) ? Blocks.ductRouter : Blocks.duct, x, y, dir);
    }

    /**
     * Silicon: arc furnaces in a row. Sand comes along a line above them and drops into each through a stub duct; the
     * graphite comes along a line above that and down into each pair's gap by a bridge over the sand, where a router hands
     * it to both furnaces; the silicon goes into a duct under the row, which the furnaces touch. A duct carries about 15 a
     * second, so from four furnaces on the sand and the silicon are split in two halves, each with its own port.
     */
    static Plan silicon(int n){
        Plan p = new Plan("silicon-" + n, "silicon", "silicon row: " + n + " arc furnaces, sand line with stubs, graphite by bridges into the pair gaps");
        Block f = Blocks.siliconArcFurnace;
        if(n == 1){
            p.put(f, 1, 0);
            p.in(Items.sand, 0, 0, E);
            p.in(Items.graphite, 0, 2, E);
            p.out(false, 4, 1, E);
            return p;
        }
        int[][] r = row(n);
        int w = r[2][0];
        IntSet mids = middles(r[0]), gaps = new IntSet();
        for(int x : r[0]){
            p.put(f, x, 0);
            p.put(Blocks.duct, x + 1, 3, S);
        }
        for(int g : r[1]){
            gaps.add(g);
            p.put(Blocks.ductBridge, g, 5, S);
            p.put(Blocks.ductBridge, g, 2, S);
            p.put(Blocks.ductRouter, g, 1, S);
        }
        boolean split = n >= 4;
        //where the west half ends: after the middle furnace
        int mid = split ? r[0][(n + 1) / 2 - 1] + 2 : w - 1;
        p.in(Items.sand, 0, 4, E);
        itemLine(p, 4, 1, mid, E, mids);
        if(split){
            p.in(Items.sand, w, 4, W);
            itemLine(p, 4, w - 1, mid + 1, W, mids);
        }
        //graphite: straight into the gap's bridge when there is one gap, else along a line on top
        if(r[1].length == 1){
            p.in(Items.graphite, r[1][0], 6, S);
        }else{
            p.in(Items.graphite, 0, 6, E);
            itemLine(p, 6, 1, w - 1, E, gaps);
        }
        //silicon under the row
        if(split){
            for(int x = 1; x <= mid; x++) p.put(Blocks.duct, x, -1, W);
            p.out(false, 0, -1, W);
            for(int x = mid + 1; x < w; x++) p.put(Blocks.duct, x, -1, E);
            p.out(false, w, -1, E);
        }else{
            for(int x = 1; x < w; x++) p.put(Blocks.duct, x, -1, E);
            p.out(false, w, -1, E);
        }
        power(p);
        return p;
    }

    /**
     * Oxide from ozone brought in: oxidation chambers in a row, beryllium along a line under them with a stub into each,
     * ozone along a row of liquid routers over them, the oxide out of each pair's gap upwards, by a bridge over the
     * ozone, to a duct along the top.
     */
    static Plan oxideOzone(int n){
        Plan p = new Plan("oxide-ozone-" + n, "oxide", "oxide row: " + n + " oxidation chambers, beryllium line with stubs below, ozone routers above, oxide up the pair gaps");
        if(n == 1){
            p.put(Blocks.oxidationChamber, 1, 0, N);
            p.in(Items.beryllium, 0, 0, E);
            p.in(Liquids.ozone, 0, 2, E);
            p.out(false, 4, 1, E);
            return p;
        }
        oxideRow(p, n);
        p.in(Liquids.ozone, 0, 3, E);
        power(p);
        return p;
    }

    /** The chambers, the beryllium spine, the ozone row from x = 1 and the oxide line; returns the first column east of it. */
    static int oxideRow(Plan p, int n){
        int[][] r = row(n);
        int w = r[2][0];
        IntSet gap = new IntSet();
        for(int g : r[1]) gap.add(g);
        for(int x : r[0]){
            p.put(Blocks.oxidationChamber, x, 0, N);
            p.put(Blocks.duct, x + 1, -1, N);
        }
        p.in(Items.beryllium, 0, -2, E);
        itemLine(p, -2, 1, w - 1, E, middles(r[0]));
        //ozone: routers all along, so that it goes either way (electrolyzers may feed it from both ends)
        for(int x = 1; x < w; x++) p.put(Blocks.reinforcedLiquidRouter, x, 3);
        //the oxide: into a duct and a bridge in the gap (the chambers hand to both), over the ozone to a bridge that hands
        //it up into the oxide line (a bridge links to the first bridge ahead within its range, so the last one faces on)
        for(int g : r[1]){
            p.put(Blocks.duct, g, 1, N);
            p.put(Blocks.ductBridge, g, 2, N);
            p.put(Blocks.ductBridge, g, 4, N);
        }
        for(int x = r[1][0]; x < w; x++) p.put(Blocks.duct, x, 5, E);
        p.out(false, w, 5, E);
        return w;
    }

    /**
     * One or two electrolyzers at the ends of an ozone line along y 3 that ends at x w - 1: the west one facing south, so
     * that its left side (ozone) is east, into the line; the east one facing north. The hydrogen out of their right sides
     * has nowhere to go and caps. Water comes in under the west one, and on to the east one by conduits under the row.
     */
    static void endElectrolyzers(Plan p, int w, int m, int under){
        Block c = Blocks.reinforcedConduit;
        p.put(Blocks.electrolyzer, -3, 1, S);
        p.in(Liquids.water, -3, 0, E);
        if(m == 2){
            p.put(Blocks.electrolyzer, w, 1, N);
            for(int y = -1; y > under; y--) p.put(c, -3, y, S);
            for(int x = -3; x <= w; x++) p.put(c, x, under, E);
            for(int y = under; y <= 0; y++) p.put(c, w + 1, y, N);
        }
    }

    /**
     * Oxide from water: the same row, its ozone made by electrolyzers at the ends of the ozone row (an electrolyzer hands
     * ozone out of its left side only, hydrogen out of its right, which is left with nowhere to go and caps). One
     * electrolyzer makes ozone for two chambers.
     */
    static Plan oxideWater(int m){
        int n = 2 * m;
        Plan p = new Plan("oxide-water-" + n, "oxide", "oxide row: " + n + " oxidation chambers, ozone from " + m + " electrolyzer(s) at the row's ends");
        int w = oxideRow(p, n);
        p.put(Blocks.reinforcedLiquidRouter, 0, 3);
        endElectrolyzers(p, w, m, -3);
        power(p);
        return p;
    }

    /**
     * Electrolyzers: water in, ozone out of each one's left side (north here), hydrogen out of its right (south), each
     * collected by a liquid router against the middle of that side and a conduit along it. Pairs share a gap that water
     * comes down into through a liquid junction in the ozone line.
     */
    static Plan electrolyzers(int n){
        Plan p = new Plan("electrolyzer-" + n, "ozone", "electrolyzer row: " + n + " electrolyzers, water into the pair gaps by junctions, ozone line above, hydrogen line below");
        Block z = Blocks.electrolyzer;
        if(n == 1){
            p.put(z, 1, 1, E);
            p.in(Liquids.water, 0, 2, E);
            p.out(true, 2, 4, N);
            p.out(true, 2, 0, S);
            return p;
        }
        int[][] r = row(n);
        int w = r[2][0];
        IntSet gap = new IntSet();
        for(int g : r[1]) gap.add(g);
        IntSet middle = new IntSet();
        for(int x : r[0]){
            p.put(z, x, 1, E);
            middle.add(x + 1);
        }
        for(int x = r[0][0] + 1; x < w; x++){
            //ozone above (y 4), hydrogen below (y 0): a router against each electrolyzer's middle, conduits between
            if(gap.contains(x)){
                p.put(Blocks.reinforcedLiquidJunction, x, 4);
                p.put(Blocks.reinforcedConduit, x, 0, E);
            }else if(middle.contains(x)){
                p.put(Blocks.reinforcedLiquidRouter, x, 4);
                p.put(Blocks.reinforcedLiquidRouter, x, 0);
            }else{
                p.put(Blocks.reinforcedConduit, x, 4, E);
                p.put(Blocks.reinforcedConduit, x, 0, E);
            }
        }
        p.out(true, w, 4, E);
        p.out(true, w, 0, E);
        //water: into each gap's top through the junction, from a line of conduits along y 5 with a router over each gap
        for(int g : r[1]) p.put(Blocks.reinforcedLiquidRouter, g, 3);
        int first = r[1][0];
        p.in(Liquids.water, first, 5, S);
        for(int i = 1; i < r[1].length; i++){
            int g = r[1][i];
            for(int x = r[1][i - 1] + 1; x < g; x++) p.put(Blocks.reinforcedConduit, x, 5, E);
            p.put(Blocks.reinforcedLiquidRouter, g, 5);
        }
        power(p);
        return p;
    }

    /**
     * A liquid line along y going east from x0 to x1: a router at each x in routers (it hands to the machines beside it),
     * a bridge in and out around each x in over (to leave that tile free), conduits between; it ends at its last router,
     * a conduit with nothing in front leaking.
     */
    static void liquidLine(Plan p, int y, int x0, int x1, IntSet routers, IntSet over){
        Block[] at = new Block[x1 - x0 + 1];
        for(int x = x0; x <= x1; x++){
            if(over.contains(x)) continue;
            at[x - x0] = routers.contains(x) ? Blocks.reinforcedLiquidRouter : over.contains(x + 1) || over.contains(x - 1) ? Blocks.reinforcedBridgeConduit : Blocks.reinforcedConduit;
        }
        int last = at.length - 1;
        while(last >= 0 && at[last] != Blocks.reinforcedLiquidRouter) last--;
        for(int i = 0; i <= last; i++) if(at[i] != null) p.put(at[i], x0 + i, y, E);
    }

    static IntSet middles(int[] machines){
        IntSet out = new IntSet();
        for(int x : machines) out.add(x + 1);
        return out;
    }

    /**
     * Power from ozone and arkycite: chemical combustion chambers side by side (generators join by touching), ozone along
     * a line above, arkycite along one below, each with a router against the middle of every chamber.
     */
    static Plan combustionOzone(int n){
        Plan p = new Plan("combustion-ozone-" + n, "power", "combustion row: " + n + " chemical combustion chambers, ozone line above, arkycite line below");
        Block c = Blocks.chemicalCombustionChamber;
        if(n == 1){
            p.put(c, 1, 0);
            p.in(Liquids.ozone, 0, 2, E);
            p.in(Liquids.arkycite, 0, 0, E);
            return p;
        }
        int[] at = new int[n];
        for(int i = 0; i < n; i++) p.put(c, at[i] = 1 + 3 * i, 0);
        p.in(Liquids.ozone, 0, 3, E);
        p.in(Liquids.arkycite, 0, -1, E);
        liquidLine(p, 3, 1, 3 * n, middles(at), new IntSet());
        liquidLine(p, -1, 1, 3 * n, middles(at), new IntSet());
        power(p);
        return p;
    }

    /**
     * Power from water and arkycite: combustion chambers with their ozone made by electrolyzers at the ends of the ozone
     * line (one makes ozone for two chambers), which here is all routers so that it carries both ways.
     */
    static Plan combustionWater(int m){
        int n = 2 * m;
        Plan p = new Plan("combustion-water-" + n, "power", "combustion row: " + n + " chemical combustion chambers, ozone from " + m + " electrolyzer(s) at the row's ends");
        Block c = Blocks.chemicalCombustionChamber;
        int w = 1 + 3 * n;
        int[] at = new int[n];
        for(int i = 0; i < n; i++) p.put(c, at[i] = 1 + 3 * i, 0);
        p.in(Liquids.arkycite, 0, -1, E);
        liquidLine(p, -1, 1, 3 * n, middles(at), new IntSet());
        for(int x = 0; x < w; x++) p.put(Blocks.reinforcedLiquidRouter, x, 3);
        endElectrolyzers(p, w, m, -2);
        power(p);
        return p;
    }

    /**
     * Power from slag and arkycite: pyrolysis generators, fed by conduits pointing into them (a generator hands its water
     * to any liquid block beside it that takes water, which a liquid router does whenever it runs low), slag along a
     * line on top, arkycite along one under; the water out of each pair's gap, up through the slag line by a bridge.
     */
    static Plan pyrolysis(int n){
        Plan p = new Plan("pyrolysis-" + n, "power", "pyrolysis row: " + n + " pyrolysis generators fed by conduit stubs, water out of the pair gaps");
        Block g = Blocks.pyrolysisGenerator;
        if(n == 1){
            p.put(g, 1, 0);
            p.in(Liquids.slag, 2, 4, S);
            p.put(Blocks.reinforcedConduit, 2, 3, S);
            p.in(Liquids.arkycite, 2, -2, N);
            p.put(Blocks.reinforcedConduit, 2, -1, N);
            p.out(true, 4, 1, E);
            return p;
        }
        int[][] r = row(n);
        int w = r[2][0];
        IntSet gap = new IntSet();
        for(int x : r[1]) gap.add(x);
        for(int x : r[0]){
            p.put(g, x, 0);
            p.put(Blocks.reinforcedConduit, x + 1, 3, S);
            p.put(Blocks.reinforcedConduit, x + 1, -1, N);
        }
        for(int x : r[1]){
            p.put(Blocks.reinforcedLiquidRouter, x, 2);
            p.put(Blocks.reinforcedLiquidRouter, x, 1);
            p.put(Blocks.reinforcedConduit, x, 3, N);
            p.out(true, x, 4, N);
        }
        p.in(Liquids.slag, 0, 4, E);
        p.in(Liquids.arkycite, 0, -2, E);
        liquidLine(p, 4, 1, w - 1, middles(r[0]), gap);
        liquidLine(p, -2, 1, w - 1, middles(r[0]), new IntSet());
        power(p);
        return p;
    }


    // ---------------------------------------------------------------- heat

    /** A block's centre in tiles (a block of even size centres between tiles). */
    static float cx(Stile t){
        return t.x - (t.block.size - 1) / 2 + (t.block.size - 1) / 2f;
    }

    static float cy(Stile t){
        return t.y - (t.block.size - 1) / 2 + (t.block.size - 1) / 2f;
    }

    /** The blocks touching t along an edge. */
    static Set<Stile> around(Plan p, Stile t){
        Set<Stile> out = new LinkedHashSet<>();
        int off = (t.block.size - 1) / 2, s = t.block.size;
        for(int i = 0; i < s; i++){
            for(int[] d : new int[][]{{-1, i}, {s, i}, {i, -1}, {i, s}}){
                Stile o = p.at(t.x - off + d[0], t.y - off + d[1]);
                if(o != null && o != t) out.add(o);
            }
        }
        return out;
    }

    /**
     * The heat a block gets with every heater at full output, worked out as the game does (BuildingComp.calculateHeat):
     * from each heater or redirector beside it that faces it, its heat over its size times the tiles of contact.
     */
    static float heat(Plan p, Stile to, Set<Stile> seen){
        float sum = 0f;
        for(Stile b : around(p, to)){
            boolean producer = b.block instanceof mindustry.world.blocks.heat.HeatProducer, conductor = b.block instanceof mindustry.world.blocks.heat.HeatConductor;
            if(!producer && !conductor) continue;
            float dx = cx(b) - cx(to), dy = cy(b) - cy(to);
            int rel = Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? 0 : 2) : (dy > 0 ? 1 : 3);
            if((rel + 2) % 4 != b.rotation) continue;
            if(seen.contains(b)) continue;
            float h;
            if(producer){
                h = ((mindustry.world.blocks.heat.HeatProducer)b.block).heatOutput;
            }else{
                Set<Stile> next = new HashSet<>(seen);
                next.add(to);
                h = heat(p, b, next);
            }
            float diff = Math.min(Math.abs(dx), Math.abs(dy));
            int contact = Math.min((int)(to.block.size / 2f + b.block.size / 2f - diff), Math.min(b.block.size, to.block.size));
            sum += h / b.block.size * contact;
        }
        return sum;
    }

    /** Heat crafters drawn so far, with the heat they need, for the check once a design is finished. */
    static float need(Block crafter){
        return crafter instanceof mindustry.world.blocks.production.HeatCrafter hc ? hc.heatRequirement : 0f;
    }

    /** Fails a design whose heat crafters would not get the heat they need. */
    static void checkHeat(Plan p){
        for(Stile t : p.tiles){
            float want = need(t.block);
            if(want <= 0f) continue;
            float got = heat(p, t, new HashSet<>());
            if(got < want - 0.01f) throw new IllegalStateException(p.id + ": " + t.block.name + " gets " + got + " heat of " + want);
        }
    }

    /**
     * The heat bank: k heat redirectors in a row facing east into the crafter, which stands at x = 3k (y 0..2). Heaters go
     * on top of the row (y 3 up) and under it (y -1 down), facing it, and behind its first redirector (x -1 back); a
     * redirector adds up all that faces it and hands it on, so the crafter gets the lot through one side and keeps the
     * other three for its inputs and outputs.
     */
    static void redirectors(Plan p, int k){
        for(int i = 0; i < k; i++) p.put(Blocks.heatRedirector, 3 * i, 0, E);
    }

    /**
     * Slag heaters (8 heat for 40 slag a second, 3x3) on the bank: one on top of and one under each redirector, and with a
     * tail one behind the first. Slag comes in at the west end and goes up and down to a line along the top of the top
     * heaters and one along the bottom of the bottom ones, each with a router against the middle of every heater; with
     * toCrafter, the top line goes on and down to a router against the crafter's top corner (a surge crucible takes slag
     * as well).
     */
    static void slagBank(Plan p, int k, boolean tail, boolean toCrafter){
        redirectors(p, k);
        IntSet mids = new IntSet();
        for(int i = 0; i < k; i++){
            p.put(Blocks.slagHeater, 3 * i, 3, S);
            p.put(Blocks.slagHeater, 3 * i, -3, N);
            mids.add(3 * i + 1);
        }
        int px = tail ? -4 : -1;
        if(tail) p.put(Blocks.slagHeater, -3, 0, E);
        p.in(Liquids.slag, px, 1, E);
        for(int y = 2; y <= 5; y++) p.put(Blocks.reinforcedConduit, px, y, N);
        for(int y = 0; y >= -3; y--) p.put(Blocks.reinforcedConduit, px, y, S);
        int end = 3 * k - 2;
        p.put(Blocks.reinforcedConduit, px, 6, E);
        p.put(Blocks.reinforcedConduit, px, -4, E);
        liquidLine(p, 6, px + 1, end, mids, new IntSet());
        liquidLine(p, -4, px + 1, end, mids, new IntSet());
        if(toCrafter){
            int cx = 3 * k;
            p.put(Blocks.reinforcedConduit, cx - 1, 6, E);
            p.put(Blocks.reinforcedConduit, cx, 6, S);
            p.put(Blocks.reinforcedConduit, cx, 5, S);
            p.put(Blocks.reinforcedConduit, cx, 4, S);
            p.put(Blocks.reinforcedLiquidRouter, cx, 3);
        }
    }

    /**
     * Phase heaters (15 heat for one phase fabric in 8 s, 2x2) on a bank of one redirector: on top, under and, with a
     * tail, behind it, each against two of its tiles (full contact). Phase fabric comes down the west side through duct
     * routers that hand it to the heaters beside them, and round the back to the one underneath.
     */
    static void phaseBank(Plan p, boolean tail){
        redirectors(p, 1);
        Block h = Blocks.phaseHeater, r = Blocks.ductRouter, d = Blocks.duct;
        p.put(h, 0, 3, S);
        p.put(h, 0, -2, N);
        p.in(Items.phaseFabric, -1, 4, S);
        p.put(r, -1, 3, S);
        if(tail){
            p.put(h, -2, 0, E);
            //down the west: the router over the tail heater hands into it and on round the back to the bottom heater's corner
            p.put(r, -1, 2, S);
            p.put(d, -2, 2, W);
            p.put(d, -3, 2, S);
            p.put(d, -3, 1, S);
            p.put(d, -3, 0, S);
            p.put(d, -3, -1, E);
            p.put(d, -2, -1, E);
            p.put(r, -1, -1, E);
        }else{
            for(int y = 2; y >= 0; y--) p.put(d, -1, y, S);
            p.put(r, -1, -1, S);
        }
    }

    /**
     * Electric heaters (3 heat for 100 power a second, 2x2) on the bank: a row on top and one under, side by side from the
     * west end over top tiles (and bottom tiles) of the row, which may reach on over the crafter's own side; and tail ones
     * behind the first redirector (the second only half against it).
     */
    static void electricBank(Plan p, int k, int top, int bottom, int tail){
        redirectors(p, k);
        for(int x = 0; x < top; x += 2) p.put(Blocks.electricHeater, x, 3, S);
        for(int x = 0; x < bottom; x += 2) p.put(Blocks.electricHeater, x, -2, N);
        if(tail >= 1) p.put(Blocks.electricHeater, -2, 0, E);
        if(tail >= 2) p.put(Blocks.electricHeater, -2, 2, E);
    }

    /** One heat crafter on its bank, with its inputs and outputs on its three free sides (it stands at x = cx). */
    static Plan heated(String product, Block crafter, String heater, int k, int top, int bottom, int tail){
        String id = product + "-" + heater;
        Plan p = new Plan(id, product, "");
        int cx = 3 * k;
        p.put(crafter, cx, 0);
        switch(heater){
            case "slag" -> slagBank(p, k, tail > 0, crafter == Blocks.surgeCrucible);
            case "phase" -> phaseBank(p, tail > 0);
            default -> electricBank(p, k, top, bottom, tail);
        }
        if(crafter == Blocks.carbideCrucible){
            p.in(Items.tungsten, cx + 1, 3, S);
            p.in(Items.graphite, cx + 1, -1, N);
            p.out(false, cx + 3, 1, E);
        }else if(crafter == Blocks.surgeCrucible){
            p.in(Items.silicon, cx + 1, -1, N);
            if(!heater.equals("slag")) p.in(Liquids.slag, cx + 1, 3, S);
            p.out(false, cx + 3, 1, E);
        }else if(crafter == Blocks.phaseSynthesizer){
            p.in(Items.thorium, cx + 1, 3, S);
            p.in(Items.sand, cx + 1, -1, N);
            p.in(Liquids.ozone, cx + 3, 0, W);
            p.out(false, cx + 3, 2, E);
        }else if(crafter == Blocks.atmosphericConcentrator){
            p.out(true, cx + 3, 1, E);
        }else if(crafter == Blocks.cyanogenSynthesizer){
            p.in(Items.graphite, cx + 1, 3, S);
            //arkycite through a conduit pointing in: the synthesizer hands its cyanogen to any liquid block beside it that takes it
            p.in(Liquids.arkycite, cx + 1, -2, N);
            p.put(Blocks.reinforcedConduit, cx + 1, -1, N);
            p.out(true, cx + 3, 1, E);
        }
        float h = heat(p, p.at(cx, 0), new HashSet<>());
        String heaters = heater.equals("electric") ? (top + 1) / 2 + (bottom + 1) / 2 + tail + " electric heaters" : heater.equals("slag") ? 2 * k + tail + " slag heaters" : 2 + tail + " phase heaters";
        p.pattern = "heat bank: " + crafter.name + " fed by " + k + " heat redirector(s) with " + heaters + " (" + Strings.autoFixed(h, 1) + " heat of " + Strings.autoFixed(need(crafter), 0) + ")";
        checkHeat(p);
        power(p);
        return p;
    }

    /**
     * Slag banks stacked: the bank of slagBank drawn again 10 tiles up, so that one slag line runs between the two, against
     * the top heaters of the one below and the bottom heaters of the one above. The slag comes in at the west end and
     * goes up a column of conduits, with a router where it meets each line (and each tail heater).
     */
    static void slagStack(Plan p, int k, boolean tail, int layers){
        Block c = Blocks.reinforcedConduit, router = Blocks.reinforcedLiquidRouter;
        IntSet mids = new IntSet();
        for(int i = 0; i < k; i++) mids.add(3 * i + 1);
        int px = tail ? -4 : -1;
        for(int l = 0; l < layers; l++){
            int y0 = 10 * l;
            for(int i = 0; i < k; i++){
                p.put(Blocks.heatRedirector, 3 * i, y0, E);
                p.put(Blocks.slagHeater, 3 * i, y0 + 3, S);
                p.put(Blocks.slagHeater, 3 * i, y0 - 3, N);
            }
            if(tail) p.put(Blocks.slagHeater, -3, y0, E);
        }
        p.in(Liquids.slag, px, 1, E);
        for(int y = 0; y >= -3; y--) p.put(c, px, y, S);
        p.put(c, px, -4, E);
        int top = 10 * layers - 4;
        for(int y = 2; y < top; y++){
            boolean line = (y - 6) % 10 == 0, tailFeed = tail && (y - 1) % 10 == 0;
            p.put(line || tailFeed ? router : c, px, y, N);
        }
        p.put(c, px, top, E);
        for(int l = 0; l <= layers; l++) liquidLine(p, 10 * l - 4, px + 1, 3 * k - 2, mids, new IntSet());
    }

    /**
     * Two heat crafters on stacked slag banks (slagStack), the one below at y 0 and the one above at y 10, both at x = cx.
     * What they make goes out between them: each hands into a piece pointing away from it, the two meet and go east out
     * of the middle. Their inputs come in on their outer sides and their east sides; a surge crucible's slag comes off
     * the middle line, down and up to a router against each crucible's corner.
     */
    static Plan heatedStack(String product, Block crafter, int k, boolean tail){
        Plan p = new Plan(product + "-slag-2", product, "");
        int cx = 3 * k;
        p.put(crafter, cx, 0);
        p.put(crafter, cx, 10);
        slagStack(p, k, tail, 2);
        boolean liquid = crafter == Blocks.atmosphericConcentrator || crafter == Blocks.cyanogenSynthesizer;
        Block line = liquid ? Blocks.reinforcedConduit : Blocks.duct;
        for(int y = 3; y <= 5; y++) p.put(line, cx + 1, y, N);
        for(int y = 9; y >= 7; y--) p.put(line, cx + 1, y, S);
        p.put(line, cx + 1, 6, E);
        p.put(line, cx + 2, 6, E);
        p.out(liquid, cx + 3, 6, E);
        if(crafter == Blocks.surgeCrucible){
            Block c = Blocks.reinforcedConduit, router = Blocks.reinforcedLiquidRouter;
            p.put(c, cx - 1, 6, E);
            p.put(router, cx, 6);
            p.put(c, cx, 5, S);
            p.put(c, cx, 4, S);
            p.put(router, cx, 3);
            p.put(c, cx, 7, N);
            p.put(c, cx, 8, N);
            p.put(router, cx, 9);
            p.in(Items.silicon, cx + 1, -1, N);
            p.in(Items.silicon, cx + 1, 13, S);
        }else if(crafter == Blocks.carbideCrucible){
            p.in(Items.graphite, cx + 1, -1, N);
            p.in(Items.tungsten, cx + 3, 1, W);
            p.in(Items.graphite, cx + 1, 13, S);
            p.in(Items.tungsten, cx + 3, 11, W);
        }else if(crafter == Blocks.phaseSynthesizer){
            p.in(Items.sand, cx + 1, -1, N);
            p.in(Liquids.ozone, cx + 3, 0, W);
            p.in(Items.thorium, cx + 3, 2, W);
            p.in(Items.sand, cx + 1, 13, S);
            p.in(Liquids.ozone, cx + 3, 12, W);
            p.in(Items.thorium, cx + 3, 10, W);
        }else if(crafter == Blocks.cyanogenSynthesizer){
            p.in(Liquids.arkycite, cx + 1, -2, N);
            p.put(Blocks.reinforcedConduit, cx + 1, -1, N);
            p.in(Items.graphite, cx + 3, 1, W);
            p.in(Liquids.arkycite, cx + 1, 14, S);
            p.put(Blocks.reinforcedConduit, cx + 1, 13, S);
            p.in(Items.graphite, cx + 3, 11, W);
        }
        float h = heat(p, p.at(cx, 0), new HashSet<>());
        p.pattern = "stacked heat banks: 2 " + crafter.name + " each fed by " + k + " heat redirector(s) with " + (2 * k + (tail ? 1 : 0)) + " slag heaters (" + Strings.autoFixed(h, 1) + " heat of " + Strings.autoFixed(need(crafter), 0) + "), one slag line between them";
        checkHeat(p);
        power(p);
        return p;
    }

    /**
     * A heat crafter with three phase heaters straight against it (45 heat), west, south and east, each against two of
     * its three tiles on that side: no redirector, and the crafter's north side and the corner tiles left on its west and
     * east sides take its inputs and output. Phase fabric comes in at the south-west corner: a router hands it to the
     * west heater and on to one that hands it to the south heater, and a duct goes round under the south heater and up
     * into the east one.
     */
    static Plan phaseDirect(String product, Block crafter){
        Plan p = new Plan(product + "-phase-direct", product, "");
        Block h = Blocks.phaseHeater, r = Blocks.ductRouter, d = Blocks.duct;
        p.put(crafter, 0, 0);
        p.put(h, -2, 0, E);
        p.put(h, 3, 0, W);
        p.put(h, 1, -2, N);
        p.in(Items.phaseFabric, -2, -1, E);
        p.put(r, -1, -1, E);
        p.put(r, 0, -1, E);
        p.put(d, -1, -2, S);
        p.put(d, -1, -3, E);
        for(int x = 0; x <= 2; x++) p.put(d, x, -3, E);
        for(int y = -3; y <= -1; y++) p.put(d, 3, y, N);
        if(crafter == Blocks.carbideCrucible){
            p.in(Items.tungsten, 0, 3, S);
            p.in(Items.graphite, 2, 3, S);
        }else if(crafter == Blocks.surgeCrucible){
            p.in(Items.silicon, 1, 3, S);
            p.in(Liquids.slag, -1, 2, E);
        }else if(crafter == Blocks.phaseSynthesizer){
            p.in(Items.thorium, 0, 3, S);
            p.in(Items.sand, 2, 3, S);
            p.in(Liquids.ozone, -1, 2, E);
        }
        p.out(false, 3, 2, E);
        float heat = heat(p, p.at(0, 0), new HashSet<>());
        p.pattern = "phase heaters straight on: " + crafter.name + " with 3 phase heaters against it (" + Strings.autoFixed(heat, 1) + " heat of " + Strings.autoFixed(need(crafter), 0) + ")";
        checkHeat(p);
        power(p);
        return p;
    }

    /**
     * A heat crafter with three slag heaters straight against it (24 heat), west, south and east, its north side for its
     * inputs and output. The slag comes in at a router at the south-west corner, which touches the west and south heaters,
     * and goes round under the south heater to a router at the south-east corner for the east one. (Neither router touches
     * the crafter, which may hand out a liquid.)
     */
    static Plan slagDirect(String product, Block crafter){
        Plan p = new Plan(product + "-slag-direct", product, "");
        Block c = Blocks.reinforcedConduit;
        p.put(crafter, 0, 0);
        p.put(Blocks.slagHeater, -3, 0, E);
        p.put(Blocks.slagHeater, 3, 0, W);
        p.put(Blocks.slagHeater, 0, -3, N);
        p.in(Liquids.slag, -1, -1, E);
        p.put(c, -1, -2, S);
        p.put(c, -1, -3, S);
        for(int x = -1; x <= 2; x++) p.put(c, x, -4, E);
        for(int y = -4; y <= -2; y++) p.put(c, 3, y, N);
        p.put(Blocks.reinforcedLiquidRouter, 3, -1);
        if(crafter == Blocks.atmosphericConcentrator){
            p.out(true, 1, 3, N);
        }else if(crafter == Blocks.cyanogenSynthesizer){
            p.in(Items.graphite, 1, 3, S);
            //arkycite through conduits pointing in (the synthesizer hands its cyanogen to any liquid block beside it that
            //takes it), its router two tiles out, away from the cyanogen's line
            p.in(Liquids.arkycite, 0, 5, S);
            p.put(c, 0, 4, S);
            p.put(c, 0, 3, S);
            p.out(true, 2, 3, N);
        }
        float heat = heat(p, p.at(0, 0), new HashSet<>());
        p.pattern = "slag heaters straight on: " + crafter.name + " with 3 slag heaters against it (" + Strings.autoFixed(heat, 1) + " heat of " + Strings.autoFixed(need(crafter), 0) + ")";
        checkHeat(p);
        power(p);
        return p;
    }

    /**
     * Fails a design if something of it could hand into the lines that will run to and from its ports on a map: the two
     * tiles out from each port (the lab puts only a source or a void on the first). A crafter or generator hands what it
     * makes to anything beside it that takes it, a router to its sides, a duct or conduit to the tile in front; items only
     * into an item line, liquids only into a liquid one.
     */
    static void checkLines(Plan p){
        for(Stile port : p.tiles){
            if(!isPort(port.block)) continue;
            boolean in = port.block == Blocks.itemSource || port.block == Blocks.liquidSource;
            boolean liquid = port.block == Blocks.liquidSource || port.block == Blocks.liquidVoid;
            int dx = Geometry.d4x(port.rotation), dy = Geometry.d4y(port.rotation), s = in ? -1 : 1;
            for(int j = 1; j <= 2; j++){
                int tx = port.x + s * dx * j, ty = port.y + s * dy * j;
                for(int d = 0; d < 4; d++){
                    Stile n = p.at(tx + Geometry.d4x(d), ty + Geometry.d4y(d));
                    if(n == null || n == port) continue;
                    Block b = n.block;
                    boolean items, liquids;
                    if(b instanceof mindustry.world.blocks.production.GenericCrafter gc){
                        items = gc.outputItems != null;
                        liquids = gc.outputLiquids != null;
                    }else if(b instanceof ConsumeGenerator cg){
                        items = false;
                        liquids = cg.outputLiquid != null;
                    }else if(b instanceof mindustry.world.blocks.distribution.DuctRouter || b == Blocks.itemSource){
                        items = true;
                        liquids = false;
                    }else if(b instanceof mindustry.world.blocks.liquid.LiquidRouter || b == Blocks.liquidSource){
                        items = false;
                        liquids = true;
                    }else if(b.rotate && b.size == 1 && n.x + Geometry.d4x(n.rotation) == tx && n.y + Geometry.d4y(n.rotation) == ty){
                        //a line piece hands only to its front
                        items = b.hasItems;
                        liquids = b.hasLiquids;
                    }else{
                        continue;
                    }
                    if(liquid ? liquids : items) throw new IllegalStateException(p.id + ": " + b.name + " at " + n.x + "," + n.y + " could hand into the line of the port at " + port.x + "," + port.y);
                }
            }
        }
    }

    // ---------------------------------------------------------------- writing

    public static void main(String[] args) throws Exception{
        Boot.boot(args[0]);
        if(args[1].equals("--check")){
            check(new Fi(args[2]));
            System.exit(0);
        }
        Fi dir = new Fi(args[1]);
        dir.mkdirs();
        StringBuilder tsv = new StringBuilder("id\tproduct\tpattern\n"), pictures = new StringBuilder();
        for(Plan p : all()){
            boolean wanted = args.length <= 2;
            for(int i = 2; i < args.length; i++) if(p.id.startsWith(args[i])) wanted = true;
            if(!wanted) continue;
            try{
                checkLines(p);
            }catch(IllegalStateException e){
                System.out.println("NOT WRITTEN: " + e.getMessage());
                continue;
            }
            Schematics.write(p.schematic(), dir.child(p.id + ".msch"));
            String picture = p.id + "  " + p.pattern + "\n" + p.picture();
            dir.child(p.id + ".txt").writeString(picture);
            pictures.append(picture).append('\n');
            tsv.append(p.id).append('\t').append(p.product).append('\t').append(p.pattern).append('\n');
            System.out.println(p.id + ": " + p.pattern);
        }
        dir.child("designs.tsv").writeString(tsv.toString());
        dir.child("pictures.txt").writeString(pictures.toString());
        System.exit(0);
    }

    /** Reads a library back the way the planner does (Design.load) and says what it found: a row it can't use is left out there. */
    static void check(Fi dir){
        List<expansion.econ.Design> got = expansion.econ.Design.load(dir);
        String[] rows = dir.child("modules.tsv").readString().trim().split("\n");
        for(expansion.econ.Design d : got){
            StringBuilder b = new StringBuilder();
            for(expansion.econ.Design.Port port : d.ports) b.append(port.in ? " in " : " out ").append(port.what).append(' ').append(Strings.autoFixed(port.rate, 2)).append(port.turn >= 0 ? "" : "(no turn)");
            System.out.println("  " + d.id + ": " + d.width(0) + "x" + d.height(0) + ", power " + d.power + "," + b);
        }
        System.out.println(got.size() + " of " + (rows.length - 1) + " rows read as designs from " + dir);
    }
}
