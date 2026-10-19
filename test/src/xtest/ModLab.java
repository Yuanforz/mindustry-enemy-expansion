package xtest;

import arc.*;
import arc.files.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.core.GameState.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.game.Schematic.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.sandbox.*;

import java.util.*;

/**
 * The blueprint lab: puts a schematic down on open ground, gives it power, feeds it from its own item and liquid
 * sources (the way the designs that people share mark their inputs) and takes away what reaches its item and liquid
 * voids (the way they mark their outputs), and measures both, port by port. What a design really makes, and what it
 * really takes, is known from the game and not from its name.
 *
 * Usage: data-dir out.tsv file.msch... (the files are data: they are read and placed, nothing in them is run)
 */
public class ModLab{
    static final int size = 120;
    static final Team team = Team.sharded;
    static Block srcItem, srcLiquid, sinkItem, sinkLiquid;
    /** What each counting block has passed so far: items (or liquid units) by content id. */
    static final ObjectMap<Building, float[]> passed = new ObjectMap<>();
    static final ObjectIntMap<String> units = new ObjectIntMap<>();

    static float[] counter(Building b){
        float[] c = passed.get(b);
        if(c == null) passed.put(b, c = new float[256]);
        return c;
    }

    /** Counting stand-ins for the sandbox sources and voids, made where a mod makes its blocks. */
    static void content(){
        srcItem = new ItemSource("lab-item-source"){{
            buildType = () -> new ItemSourceBuild(){
                @Override
                public boolean dump(Item todump){
                    boolean ok = super.dump(todump);
                    if(ok && todump != null) counter(this)[todump.id]++;
                    return ok;
                }
            };
        }};
        srcLiquid = new LiquidSource("lab-liquid-source"){{
            buildType = () -> new LiquidSourceBuild(){
                @Override
                public void updateTile(){
                    if(source == null){
                        liquids.clear();
                        return;
                    }
                    liquids.set(source, block.liquidCapacity);
                    dumpLiquid(source);
                    counter(this)[source.id] += block.liquidCapacity - liquids.get(source);
                }
            };
        }};
        sinkItem = new ItemVoid("lab-item-void"){{
            buildType = () -> new ItemVoidBuild(){
                @Override
                public void handleItem(Building source, Item item){
                    counter(this)[item.id]++;
                }
            };
        }};
        sinkLiquid = new LiquidVoid("lab-liquid-void"){{
            buildType = () -> new LiquidVoidBuild(){
                @Override
                public void handleLiquid(Building source, Liquid liquid, float amount){
                    counter(this)[liquid.id] += amount;
                }
            };
        }};
    }

    /** One input or output of a design: where it is in the design, what passes it, how fast. */
    static final class Port{
        boolean in, liquid;
        int x, y;
        String what;
        float rate;
        Building at;
        /** Deployed: the way the router at an input faces (the line comes from behind), or the first piece of an output. */
        int turn = -1;
        /** The rotation the source or void was drawn with: with -Dlab.portturn the way it is tried first. */
        int drawn;
    }

    static final class Result{
        String file, name, error;
        int w, h, blocks;
        final List<Port> ports = new ArrayList<>();
        final ObjectFloatMap<String> made = new ObjectFloatMap<>();
        final ObjectIntMap<String> cost = new ObjectIntMap<>();
        float powerUse, powerMade, seconds;
        final List<String> machines = new ArrayList<>();
        boolean stable = true;
        /**
         * Power grids the design's own blocks make (its beam nodes and what touches them): with more than one, the layout,
         * which powers a design through one of its blocks, leaves the others dark.
         */
        int grids;
        final ObjectFloatMap<String> liquidUse = new ObjectFloatMap<>();
        final List<String> spills = new ArrayList<>();
    }

    public static void main(String[] args){
        Boot.boot(args[0], null, ModLab::content);
        Events.on(UnitCreateEvent.class, e -> units.increment(e.unit.type.name));
        float warm = Float.parseFloat(System.getProperty("lab.warm", "60")), measure = Float.parseFloat(System.getProperty("lab.measure", "120"));
        StringBuilder tsv = new StringBuilder("file\tname\tsize\tblocks\tcost\tpower\tinputs\toutputs\tunits_per_min\tmachines\tstable\tpower_made\tgrids\n");
        for(int a = 2; a < args.length; a++){
            Result r = run(new Fi(args[a]), warm, measure);
            print(r);
            tsv.append(row(r)).append('\n');
        }
        new Fi(args[1]).writeString(tsv.toString());
        System.exit(0);
    }

    static Result run(Fi file, float warm, float measure){
        Result r = new Result();
        r.file = file.name();
        Schematic s;
        try{
            s = Schematics.read(file);
        }catch(Throwable t){
            r.error = "unreadable: " + t;
            return r;
        }
        //what an older game version's numbering got wrong (a constructor told to build a crucible) put right
        int repaired = expansion.econ.Design.repair(s);
        if(repaired > 0) System.out.println("  (" + repaired + " constructors set to the wall their assembler needs)");
        //turned, if asked (-Dlab.turn=1..3): a design must do the same whichever way it faces
        for(int o = 0; o < Integer.getInteger("lab.turn", 0); o++) s = expansion.econ.Design.turn(s);
        r.name = s.name();
        r.w = s.width;
        r.h = s.height;
        r.blocks = s.tiles.size;
        if(s.width > size - 20 || s.height > size - 20){
            r.error = "too big";
            return r;
        }
        world();
        passed.clear();
        units.clear();
        int ox = size / 2 - s.width / 2, oy = size / 2 - s.height / 2;
        for(Stile st : s.tiles){
            Block b = st.block;
            boolean port = false, in = false, liquid = false;
            if(b == Blocks.itemSource){ b = srcItem; port = in = true; }
            else if(b == Blocks.liquidSource){ b = srcLiquid; port = in = liquid = true; }
            else if(b == Blocks.itemVoid){ b = sinkItem; port = true; }
            else if(b == Blocks.liquidVoid){ b = sinkLiquid; port = liquid = true; }
            else if(b.buildVisibility != mindustry.world.meta.BuildVisibility.sandboxOnly) for(ItemStack stack : b.requirements) r.cost.increment(stack.item.name, stack.amount);
            Tile tile = Vars.world.tile(st.x + ox, st.y + oy);
            if(tile == null) continue;
            try{
                tile.setBlock(b, team, st.rotation);
                if(tile.build != null && st.config != null) tile.build.configureAny(st.config);
            }catch(Throwable t){
                r.error = "could not place " + b.name + ": " + t;
            }
            if(port){
                Port p = new Port();
                p.in = in;
                p.liquid = liquid;
                p.x = st.x;
                p.y = st.y;
                p.what = st.config instanceof mindustry.ctype.UnlockableContent c ? c.name : "any";
                p.drawn = st.rotation;
                p.at = tile.build;
                r.ports.add(p);
            }
        }
        //as it would stand on the map (-Dlab.deploy): each source a router the line comes into from behind (a duct router
        //takes only from behind), with the source outside; each void the first piece of the line away, the void beyond it
        if(Boolean.getBoolean("lab.deploy")){
            IntSet foot = new IntSet();
            for(Stile st : s.tiles){
                if(st.block.buildVisibility == mindustry.world.meta.BuildVisibility.sandboxOnly) continue;
                int off = (st.block.size - 1) / 2;
                for(int dx = 0; dx < st.block.size; dx++) for(int dy = 0; dy < st.block.size; dy++) foot.add(Point2.pack(st.x - off + dx + ox, st.y - off + dy + oy));
            }
            for(Port p : r.ports){
                int px = p.x + ox, py = p.y + oy;
                boolean done = false;
                //an item input that fed one neighbour only: a plain duct into it, which a line can come into from behind
                //or either side (its turn is written as 4 + the way the duct faces)
                if(p.in && !p.liquid && Boolean.parseBoolean(System.getProperty("lab.ducts", "true"))){
                    Item item = Vars.content.item(p.what);
                    int only = -1, count = 0;
                    for(int d = 0; d < 4; d++){
                        Tile nb = Vars.world.tile(px + Geometry.d4x(d), py + Geometry.d4y(d));
                        if(nb == null || nb.build == null || !foot.contains(Point2.pack(nb.x, nb.y)) || item == null) continue;
                        if(nb.build.acceptItem(p.at, item)){
                            only = d;
                            count++;
                        }
                    }
                    if(count == 1){
                                    if(d == only) continue;
                            int qx = px + Geometry.d4x(d), qy = py + Geometry.d4y(d);
                            Tile q = Vars.world.tile(qx, qy);
                            if(q == null || foot.contains(Point2.pack(qx, qy)) || q.build != null) continue;
                            Object what = p.at == null ? null : p.at.config();
                            Vars.world.tile(px, py).setBlock(Blocks.duct, team, only);
                            q.setBlock(srcItem, team, 0);
                            if(what != null) q.build.configureAny(what);
                            p.at = q.build;
                            p.turn = 4 + only;
                            done = true;
                        }
                    }
                }
                //a design that says which way each port faces (its source or void drawn turned that way), with -Dlab.portturn: tried first
                for(int k = 0; k < 4 && !done; k++){
                    int d = Boolean.getBoolean("lab.portturn") ? (p.drawn + k) % 4 : k;
                    int dx = Geometry.d4x(d), dy = Geometry.d4y(d);
                    if(p.in){
                        //the line comes from behind: the tile behind must be outside the design and free
                        int qx = px - dx, qy = py - dy;
                        Tile q = Vars.world.tile(qx, qy);
                        if(q == null || foot.contains(Point2.pack(qx, qy)) || q.build != null) continue;
                        Object what = p.at == null ? null : p.at.config();
                        Vars.world.tile(px, py).setBlock(p.liquid ? Blocks.reinforcedLiquidRouter : Blocks.ductRouter, team, d);
                        q.setBlock(p.liquid ? srcLiquid : srcItem, team, 0);
                        if(what != null) q.build.configureAny(what);
                        p.at = q.build;
                        p.turn = d;
                        done = true;
                    }else{
                        //the line goes away from what feeds the void: in front free, behind part of the design
                        int fx = px + dx, fy = py + dy, bx = px - dx, by = py - dy;
                        Tile f = Vars.world.tile(fx, fy);
                        if(f == null || foot.contains(Point2.pack(fx, fy)) || f.build != null || !foot.contains(Point2.pack(bx, by))) continue;
                        Vars.world.tile(px, py).setBlock(p.liquid ? Blocks.reinforcedConduit : Blocks.duct, team, d);
                        f.setBlock(p.liquid ? sinkLiquid : sinkItem, team, 0);
                        p.at = f.build;
                        p.turn = d;
                        done = true;
                    }
                }
                if(!done) r.error = "port at " + p.x + "," + p.y + " has no way in or out";
            }
        }
        //power for all of it: one source, every grid joined to it
        Tile far = Vars.world.tile(2, 2);
        far.setBlock(Blocks.powerSource, team, 0);
        PowerGraph graph = far.build.power.graph;
        for(Building b : team.data().buildings){
            if(b.power != null && b != far.build && b.power.graph != graph) graph.addGraph(b.power.graph);
            if(b.block.consPower != null && !(b.block instanceof PowerSource)) r.powerUse += b.block.consPower.usage * 60f;
            if(b.block instanceof PowerGenerator g && !(b.block instanceof PowerSource)) r.powerMade += g.powerProduction * 60f;
        }
        //warm up, then measure; the second half against the first says whether it has settled
        for(int f = 0; f < warm * 60; f++){
            Boot.frames(1);
            if(f % 60 == 0) Groups.unit.each(u -> u.team == team && u.type != UnitTypes.assemblyDrone, Unit::kill);
        }
        float[][] before = snapshot(r);
        ObjectIntMap<String> unitsBefore = new ObjectIntMap<>(units);
        Seq<Building> machines = new Seq<>();
        for(Building b : team.data().buildings) if(b.block.consumers.length > 0 && !(b.block instanceof PowerSource) && b.block != srcItem && b.block != srcLiquid) machines.add(b);
        float[] eff = new float[machines.size];
        int samples = 0;
        float[][] mid = null;
        int frames = (int)(measure * 60);
        for(int f = 0; f < frames; f++){
            Boot.frames(1);
            if(f % 60 == 0) Groups.unit.each(u -> u.team == team && u.type != UnitTypes.assemblyDrone, Unit::kill);
            if(f % 30 == 0){
                for(int i = 0; i < machines.size; i++) eff[i] += machines.get(i).efficiency;
                samples++;
            }
            if(f == frames / 2) mid = snapshot(r);
        }
        float[][] after = snapshot(r);
        r.seconds = measure;
        for(int i = 0; i < r.ports.size(); i++){
            Port p = r.ports.get(i);
            float first = 0f, second = 0f;
            for(int id = 0; id < 256; id++){
                first += mid[i][id] - before[i][id];
                second += after[i][id] - mid[i][id];
            }
            p.rate = (first + second) / measure;
            if(Math.abs(first - second) > 0.15f * Math.max(first, second) + 2f) r.stable = false;
        }
        for(var e : units){
            float n = e.value - unitsBefore.get(e.key, 0);
            if(n > 0) r.made.put(e.key, n / measure * 60f);
        }
        ObjectFloatMap<String> byKind = new ObjectFloatMap<>();
        ObjectIntMap<String> count = new ObjectIntMap<>();
        for(int i = 0; i < machines.size; i++){
            String k = machines.get(i).block.name;
            byKind.increment(k, 0f, eff[i] / Math.max(1, samples));
            count.increment(k);
        }
        //what its machines take of each liquid, at the rate they ran: a source giving much more is spilling somewhere
        for(int i = 0; i < machines.size; i++){
            Building b = machines.get(i);
            float e = eff[i] / Math.max(1, samples);
            for(var c : b.block.consumers){
                if(c instanceof mindustry.world.consumers.ConsumeLiquid cl && !cl.booster) r.liquidUse.increment(cl.liquid.name, 0f, cl.amount * 60f * e);
                if(c instanceof mindustry.world.consumers.ConsumeLiquids cls) for(LiquidStack ls : cls.liquids) r.liquidUse.increment(ls.liquid.name, 0f, ls.amount * 60f * e);
            }
        }
        r.grids = grids();
        for(Port p : r.ports){
            if(!p.in || !p.liquid) continue;
            float use = r.liquidUse.get(p.what, 0f);
            if(p.rate > use * 1.25f + 1f) r.spills.add(String.format("%s %.0f/s in, machines take %.0f/s", p.what, p.rate, use));
        }
        for(var e : count) r.machines.add(String.format("%s x%d %.0f%%", e.key, e.value, byKind.get(e.key, 0f) / e.value * 100f));
        Collections.sort(r.machines);
        return r;
    }

    /**
     * How many grids the design's powered blocks make by themselves: joined the way the game joins them (touching, as
     * BuildingComp.getPowerConnections has it, and the links its beam nodes made), not by the lab's own joining to its
     * source. A grid counts if something in it uses or makes power other than nodes and powered lines.
     */
    static int grids(){
        ObjectSet<Building> seen = new ObjectSet<>();
        Seq<Building> near = new Seq<>(), queue = new Seq<>();
        int n = 0;
        for(Building b : team.data().buildings){
            if(b.power == null || b.block instanceof PowerSource || seen.contains(b)) continue;
            boolean counts = false;
            queue.clear();
            queue.add(b);
            seen.add(b);
            while(!queue.isEmpty()){
                Building c = queue.pop();
                if((c.block.consPower != null && !(c.block instanceof BeamNode) && !c.block.conductivePower) || c.block instanceof PowerGenerator) counts = true;
                for(Building o : c.getPowerConnections(near)){
                    if(o.block instanceof PowerSource || !seen.add(o)) continue;
                    queue.add(o);
                }
            }
            if(counts) n++;
        }
        return n;
    }

    static int id(Port p){
        if(p.what.equals("any")) return -1;
        if(p.liquid){
            Liquid l = Vars.content.liquid(p.what);
            return l == null ? -1 : l.id;
        }
        Item i = Vars.content.item(p.what);
        return i == null ? -1 : i.id;
    }

    static float[][] snapshot(Result r){
        float[][] out = new float[r.ports.size()][];
        for(int i = 0; i < out.length; i++){
            Building b = r.ports.get(i).at;
            out[i] = b == null ? new float[256] : counter(b).clone();
        }
        return out;
    }

    static void world(){
        Vars.logic.reset();
        Vars.world.loadGenerator(size, size, tiles -> {
            for(int x = 0; x < size; x++) for(int y = 0; y < size; y++) tiles.set(x, y, new Tile(x, y, Blocks.rhyolite, Blocks.air, Blocks.air));
        });
        Rules rules = new Rules();
        Planets.erekir.applyRules(rules, true);
        rules.fog = false;
        rules.waves = false;
        rules.unitCap = 1000;
        Vars.state.rules = rules;
        Vars.state.set(State.playing);
        Time.setDeltaProvider(() -> 1f);
    }

    /** Items and liquids by name, outputs (voids) named for what reached them. */
    static void print(Result r){
        System.out.println("#### " + r.file + "  \"" + r.name + "\"  " + r.w + "x" + r.h + "  " + r.blocks + " blocks" + (r.error != null ? "  ERROR " + r.error : ""));
        if(r.error != null && r.ports.isEmpty()) return;
        System.out.println("  cost " + costText(r) + "; power " + Strings.autoFixed(r.powerUse, 1) + "/s used, " + Strings.autoFixed(r.powerMade, 1) + "/s made; " + (r.stable ? "settled" : "NOT SETTLED"));
        for(Port p : r.ports) System.out.println(String.format("  %s %-12s at %d,%d: %.2f/s", p.in ? "in " : "out", p.in ? p.what : outWhat(p), p.x, p.y, p.in ? p.rate : outRate(p)));
        for(var e : r.made) System.out.println(String.format("  makes %s: %.2f a minute", e.key, e.value));
        System.out.println("  machines: " + String.join(", ", r.machines) + (r.grids > 1 ? "; " + r.grids + " POWER GRIDS" : ""));
        if(!r.spills.isEmpty()) System.out.println("  SPILLS: " + String.join("; ", r.spills));
    }

    /** A void takes anything: what reached it, by name (the largest first). */
    static String outWhat(Port p){
        float[] c = p.at == null ? new float[256] : counter(p.at);
        StringBuilder b = new StringBuilder();
        for(int id = 0; id < 256; id++){
            if(c[id] <= 0) continue;
            String name = p.liquid ? (id < Vars.content.liquids().size ? Vars.content.liquid(id).name : "?") : (id < Vars.content.items().size ? Vars.content.item(id).name : "?");
            if(b.length() > 0) b.append('+');
            b.append(name);
        }
        return b.length() == 0 ? "nothing" : b.toString();
    }

    static float outRate(Port p){
        return p.rate;
    }

    static String costText(Result r){
        StringBuilder b = new StringBuilder();
        for(var e : r.cost) b.append(e.key).append(' ').append(e.value).append(' ');
        return b.toString().trim();
    }

    static String row(Result r){
        StringBuilder in = new StringBuilder(), out = new StringBuilder();
        for(Port p : r.ports){
            StringBuilder t = p.in ? in : out;
            if(t.length() > 0) t.append(';');
            t.append(p.in ? p.what : outWhat(p)).append('@').append(p.x).append(',').append(p.y).append(p.turn >= 0 ? ":" + p.turn : "").append('=').append(Strings.autoFixed(p.rate, 3));
        }
        StringBuilder made = new StringBuilder();
        for(var e : r.made) made.append(e.key).append('=').append(Strings.autoFixed(e.value, 3)).append(';');
        return String.join("\t", r.file, r.name == null ? "" : r.name.replace('\t', ' '), r.w + "x" + r.h, String.valueOf(r.blocks), costText(r),
            Strings.autoFixed(r.powerUse, 1), in.toString(), out.toString(), made.toString(), String.join(";", r.machines), (r.error != null ? "error: " + r.error : r.stable ? "settled" : "not settled") + (r.spills.isEmpty() ? "" : "; spills " + String.join(",", r.spills)),
            Strings.autoFixed(r.powerMade, 1), String.valueOf(r.grids));
    }
}
