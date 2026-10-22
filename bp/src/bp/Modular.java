package bp;

import arc.files.*;
import bp.Board.*;
import mindustry.*;
import mindustry.world.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.liquid.*;
import mindustry.world.blocks.sandbox.*;

import java.util.*;

/**
 * A layout made of modules: the plan first, alone (Balance: which machines, how many, what flows between them); then the
 * plan cut into modules (the machines of each product with their heaters; machines that hand payloads to one another in
 * one), each laid out on its own as a module with ports (Gen: its inputs and outputs on its edge, its recipes those the
 * plan chose), the smallest that lays out clean; then the modules put on the map as whole bodies, each port a carrier
 * on the module's edge (an output a duct or conduit pointing out, an input one pointing in to what took from it), and
 * only the lines between them laid, and power over all.
 *
 * Usage: bp.Modular data-dir problem-file [out-file]
 */
public final class Modular{
    final Problem prob;
    final String dataDir;
    final Gen whole;
    final List<String> notes = new ArrayList<>();

    /** A module laid out: its size, its blocks (in its own frame), its ports. */
    static final class Module{
        String name;
        int w, h;
        final List<Body.Part> parts = new ArrayList<>();
        final List<Piece> pieces = new ArrayList<>();
        /** Ports: in or out, what, where (tile, its rotation as a carrier), a second. */
        final List<Object[]> ports = new ArrayList<>();
        double cost;
        float seconds;
    }

    Modular(Problem prob, String dataDir){
        this.prob = prob;
        this.dataDir = dataDir;
        whole = new Gen(prob, dataDir);
    }

    // ------------------------------------------------------------------ the plan, cut

    /** What a machine is there to make: its first item, liquid, unit or block made; else its recipe's own name. */
    static String product(Recipe r){
        for(String k : r.out.keySet()) if(Vars.content.item(k) != null || Vars.content.liquid(k) != null || Board.isPayload(k)) return k;
        return r.name;
    }

    /** The machines of the plan in groups: by product, heaters with what they heat, payload makers with their takers. */
    List<List<Object[]>> cut(){
        List<Object[]> ms = whole.balance.machines;
        int n = ms.size();
        int[] parent = new int[n];
        for(int i = 0; i < n; i++) parent[i] = i;
        Map<String, Integer> byProduct = new HashMap<>();
        for(int i = 0; i < n; i++){
            Object[] m = ms.get(i);
            Recipe r = (Recipe)m[0];
            if(m[4] != null) continue;
            String p = product(r);
            if(byProduct.containsKey(p)) union(parent, i, byProduct.get(p));
            else byProduct.put(p, i);
        }
        for(int i = 0; i < n; i++){
            Object[] m = ms.get(i);
            if(m[4] == null) continue;
            String label = (String)m[4];
            String heated = label.substring(label.lastIndexOf(' ') + 1);
            for(int j = 0; j < n; j++) if(ms.get(j)[4] == null && ((Recipe)ms.get(j)[0]).name.equals(heated)) union(parent, i, j);
        }
        //(machines that hand payloads to one another stand in one module)
        for(int i = 0; i < n; i++) for(int j = 0; j < n; j++){
            if(i == j) continue;
            Recipe a = (Recipe)ms.get(i)[0], c = (Recipe)ms.get(j)[0];
            for(String k : a.out.keySet()) if(Board.isPayload(k) && c.in.containsKey(k)) union(parent, i, j);
        }
        Map<Integer, List<Object[]>> groups = new LinkedHashMap<>();
        for(int i = 0; i < n; i++) groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(ms.get(i));
        return new ArrayList<>(groups.values());
    }

    static int find(int[] p, int i){
        while(p[i] != i) i = p[i] = p[p[i]];
        return i;
    }

    static void union(int[] p, int a, int b){
        p[find(p, a)] = find(p, b);
    }

    /** What a group takes and makes a second, by the plan (work counted; heaters at full). */
    static Map<String, Float> net(List<Object[]> g){
        Map<String, Float> net = new LinkedHashMap<>();
        for(Object[] m : g){
            Recipe r = (Recipe)m[0];
            int count = (Integer)m[1];
            float work = (Float)m[2];
            for(var e : r.out.entrySet()) net.merge(e.getKey(), e.getValue() * work, Float::sum);
            for(var e : r.in.entrySet()) net.merge(e.getKey(), -e.getValue() * work, Float::sum);
            for(var e : r.fixedIn.entrySet()) net.merge(e.getKey(), -e.getValue() * count, Float::sum);
            for(var e : r.fixedOut.entrySet()) net.merge(e.getKey(), e.getValue() * count, Float::sum);
        }
        return net;
    }

    // ------------------------------------------------------------------ one module

    /** A group laid out as a module, the smallest of a few sizes that lays out clean (or the best tried). */
    Module module(List<Object[]> g, String name){
        Map<String, Float> net = net(g);
        Set<String> allowed = new HashSet<>();
        float area = 0f;
        boolean power = false;
        for(Object[] m : g){
            Recipe r = (Recipe)m[0];
            allowed.add(r.name);
            area += r.area() * (Integer)m[1];
            if(r.powerUse > 0f || r.fixedPowerUse > 0f) power = true;
        }
        //(its ports: what it takes from outside in, what it makes for outside or the goals out; goals that are no thing,
        //a turret firing or a unit, kept as goals with no port)
        StringBuilder text = new StringBuilder();
        for(var e : net.entrySet()){
            String k = e.getKey();
            float v = e.getValue();
            boolean thing = Vars.content.item(k) != null || Vars.content.liquid(k) != null;
            String kind = Vars.content.liquid(k) != null ? "liquid" : "item";
            if(v < -1e-5f && thing) text.append("in ").append(kind).append(' ').append(k).append('\n');
            else if(v > 1e-5f && thing) text.append("out ").append(kind).append(' ').append(k).append(' ').append(v).append('\n');
            //(what it makes that is no item or liquid and nothing in it takes: a turret firing, a generator running, a unit
            //made: its goal, with no port)
            else if(v > 1e-5f) text.append("goal ").append(k).append(' ').append(v * 0.999f).append('\n');
        }
        if(power) text.append("in power\n");
        text.append("weights tile=").append(prob.perTile).append(",item=").append(prob.perItem).append(",second=").append(prob.perSecond).append('\n');
        //(the smallest that lays out clean: from the square the machines fill with a ring for the ports, larger a step at a time)
        int side = (int)Math.ceil(Math.sqrt(area)) + 3;
        Module best = null;
        int bestBad = Integer.MAX_VALUE;
        for(int attempt = 0; attempt < 8; attempt++){
            int w = side, h = side;
            Problem mp = Problem.parse("module " + w + " " + h + "\n" + text);
            mp.name = name;
            Gen gen = new Gen(mp, dataDir);
            gen.allowed = allowed;
            Spec s = gen.run(attempt + 1);
            if(s == null){
                notes.add(name + ": no balance in its module");
                break;
            }
            Module m = from(gen, name, w, h);
            //(and by the game's rules, the module alone with its ports as sources and voids)
            int ruled = gen.verify(s, false);
            gen.problems += ruled;
            if(gen.problems < bestBad){
                bestBad = gen.problems;
                best = m;
            }
            System.out.println(String.format("module %s %dx%d: %d problems (%d by the game's rules), %d blocks", name, w, h, gen.problems, ruled, m.parts.size() + m.pieces.size()));
            if(gen.problems == 0) break;
            side = (int)Math.ceil(side * 1.15f);
        }
        if(bestBad > 0) notes.add(name + ": its module kept " + bestBad + " problems");
        return best;
    }

    /** A module out of a generator's layout: its blocks, and its ports as carriers on its edge. */
    Module from(Gen gen, String name, int w, int h){
        Module m = new Module();
        m.name = name;
        m.w = w;
        m.h = h;
        Set<Body.Part> portParts = new HashSet<>();
        for(Body b : gen.bodies) if(b.label.startsWith("in ") || b.label.startsWith("out ")) portParts.addAll(b.parts);
        for(Body b : gen.bodies) for(Body.Part p : b.parts) if(!portParts.contains(p)) m.parts.add(p);
        for(Router.Net net : gen.bestNets) for(Piece q : net.pieces) if(q.block != null) m.pieces.add(q);
        m.pieces.addAll(gen.bestPower);
        for(Body.Part p : portParts){
            boolean in = p.body.label.startsWith("in ");
            String res = p.config == null ? "power" : p.config;
            int rot = -1;
            if(in && !res.equals("power")){
                //(pointing in, to what took from the source: the first piece of its line, or the machine it fed)
                for(Router.Net net : gen.bestNets) if(net.from == p){
                    if(!net.pieces.isEmpty()){
                        Piece q = net.pieces.get(0);
                        rot = Geo.dir(p.ax(), p.ay(), q.x, q.y);
                    }else for(Body.Part t : net.to) for(int[] a : p.around()) if(t.covers(a[0], a[1])) rot = a[2];
                    if(rot >= 0) break;
                }
            }else if(!in){
                //(pointing out of the module)
                for(int d = 0; d < 4; d++){
                    int nx = p.ax() + Geo.dx(d), ny = p.ay() + Geo.dy(d);
                    if(nx < 0 || ny < 0 || nx >= w || ny >= h || !gen.board.floor(nx, ny)){
                        rot = d;
                        break;
                    }
                }
            }
            float rate = in ? 0f : gen.portRate.getOrDefault(p, 0f);
            //(what in the module hands into the port's tile, by the side it is on)
            boolean fromBehindOnly = true;
            if(!in && rot >= 0) for(int d = 0; d < 4; d++){
                int nx = p.ax() + Geo.dx(d), ny = p.ay() + Geo.dy(d);
                if(!gen.board.in(nx, ny)) continue;
                boolean pushes = false;
                Body.Part o = gen.board.partAt[gen.board.idx(nx, ny)];
                if(o != null && o != p && Board.out(o, (d + 2) % 4).contains(res)) pushes = true;
                for(Router.Net net : gen.bestNets) for(Piece q : net.pieces) if(q.block != null && q.x == nx && q.y == ny && q.x + Geo.dx(q.rot) == p.ax() && q.y + Geo.dy(q.rot) == p.ay()) pushes = true;
                if(pushes && d != (rot + 2) % 4) fromBehindOnly = false;
            }
            m.ports.add(new Object[]{in, res, p.ax(), p.ay(), rot, rate, in || fromBehindOnly});
        }
        for(Body.Part p : m.parts) m.cost += gen.weights.cost(p.block);
        for(Piece q : m.pieces) m.cost += gen.weights.cost(q.block);
        return m;
    }

    // ------------------------------------------------------------------ the whole

    Spec run(long seed){
        long t0 = System.nanoTime();
        whole.balance();
        if(!whole.balance.solved) return null;
        long t1 = System.nanoTime();
        List<List<Object[]>> groups = cut();
        System.out.println(String.format("plan in %.1f s: %d machines in %d modules", (t1 - t0) / 1e9, whole.balance.machines.size(), groups.size()));
        List<Module> modules = new ArrayList<>();
        int k = 0;
        for(List<Object[]> g : groups){
            String name = product((Recipe)g.get(0)[0]).replace(':', '-') + "-" + k++;
            Module m = module(g, name);
            if(m != null) modules.add(m);
        }
        long t2 = System.nanoTime();
        System.out.println(String.format("modules in %.1f s", (t2 - t1) / 1e9));
        //the modules as bodies on the map
        Gen top = new Gen(prob, dataDir);
        top.balance = whole.balance;
        int id = 0;
        for(Spec.Place p : prob.fixed){
            Block bl = Vars.content.block(p.block());
            Body body = new Body(id++, p.block(), true);
            body.add(bl, 0, 0, p.rotation(), null, 1f, p.config());
            body.put(Geo.lo(p.x(), bl.size), Geo.lo(p.y(), bl.size), 0);
            top.bodies.add(body);
            if(Board.isCore(body.main())) top.core = body.main();
        }
        Map<Body.Part, String> inPorts = new HashMap<>();
        Map<Body.Part, Float> inRate = new HashMap<>();
        for(Module m : modules){
            Body body = new Body(id++, m.name, false);
            for(Body.Part p : m.parts){
                Body.Part q = body.add(p.block, p.x0, p.y0, p.rot, p.recipe, p.work, p.config);
            }
            for(Piece q : m.pieces){
                Body.Part part = body.add(q.block, Geo.lo(q.x, q.block.size), Geo.lo(q.y, q.block.size), q.rot, null, 1f, null);
            }
            Map<String, Float> net = null;
            for(Object[] port : m.ports){
                boolean in = (Boolean)port[0];
                String res = (String)port[1];
                int x = (Integer)port[2], y = (Integer)port[3], rot = (Integer)port[4];
                if(res.equals("power")){
                    body.add(top.carriers.cheapest(Carriers.Role.node, top.weights), x, y, 0, null, 1f, null);
                    continue;
                }
                if(rot < 0) continue;
                boolean liquid = Vars.content.liquid(res) != null;
                //(an input port armoured, so that nothing about it in the module hands it anything: it takes from behind,
                //from outside; an output port too, where all that hands it its thing is behind it)
                boolean armour = (Boolean)port[6];
                Block carrier = top.carriers.cheapest(liquid ? Carriers.Role.conduit : armour ? Carriers.Role.armored : Carriers.Role.duct, top.weights);
                Body.Part part = body.add(carrier, x, y, rot, null, 1f, res);
                if(in){
                    inPorts.put(part, res);
                }else top.supplyRate.put(part, (Float)port[5]);
            }
            body.put(0, 0, 0);
            top.bodies.add(body);
        }
        //what each module's inputs take: its share of the plan's flows into its group
        for(int gi = 0; gi < groups.size() && gi < modules.size(); gi++){
            Map<String, Float> net = net(groups.get(gi));
            Body body = top.bodies.get(prob.fixed.size() + gi);
            for(Body.Part p : body.parts) if(inPorts.containsKey(p)){
                String res = inPorts.get(p);
                long same = body.parts.stream().filter(o -> res.equals(inPorts.get(o))).count();
                top.portRate.put(p, -net.getOrDefault(res, 0f) / same);
            }
        }
        //lines between the modules: who makes what for whom
        Set<String> res = new LinkedHashSet<>();
        for(Body b : top.bodies) for(Body.Part p : b.parts) if(p.config != null && Vars.content.block(p.config) == null) res.add(p.config);
        for(String r : res){
            boolean item = Vars.content.item(r) != null, liquid = Vars.content.liquid(r) != null;
            if(!item && !liquid) continue;
            Placer.Group g = new Placer.Group(r, liquid);
            for(Body b : top.bodies) for(Body.Part p : b.parts){
                boolean source = (p.block instanceof ItemSource || p.block instanceof LiquidSource) && r.equals(p.config);
                if(source || top.supplyRate.containsKey(p) && r.equals(p.config)) g.makers.add(p);
                if(inPorts.containsKey(p) && r.equals(inPorts.get(p)) || Board.isCore(p) && item && prob.goals.containsKey(r)) g.takers.add(p);
            }
            if(!g.makers.isEmpty() && !g.takers.isEmpty()) top.groups.add(g);
        }
        //(the core takes what the plan delivers of the goals; a module's output is what its port gives)
        System.out.println(String.format("%d modules on the map; %d things between them", modules.size(), top.groups.size()));
        Spec s = top.layOut(seed);
        top.notes.addAll(notes);
        for(String n : top.notes) System.out.println("  note: " + n);
        System.out.println(String.format("all in %.1f s", (System.nanoTime() - t0) / 1e9));
        last = top;
        return s;
    }

    Gen last;

    public static void main(String[] args){
        Game.boot(args[0]);
        Problem prob = Problem.read(new Fi(args[1]));
        Modular mod = new Modular(prob, args[0]);
        Spec s = mod.run(1L);
        if(s == null){
            System.out.println("no plan");
            System.exit(1);
        }
        s.name = prob.name;
        Fi out = new Fi(args.length > 2 ? args[2] : "build/bp-out/" + prob.name + "-mod.txt");
        out.writeString("# laid out in modules by bp.Modular from " + args[1] + "\n" + s.write() + "prime\n");
        System.out.println("written " + out.path());
        try{
            java.io.File png = new java.io.File(out.pathWithoutExtension() + ".png");
            Plot.draw(mod.last.board, mod.last.bodies, mod.last.bestNets, mod.last.bestPower, prob.name + " in modules" + (mod.last.problems > 0 ? "  (" + mod.last.problems + " problems left)" : ""), png);
            System.out.println("drawn " + png.getPath());
        }catch(Exception e){
            System.out.println("could not draw: " + e);
        }
        int bad = mod.last.verify(s, Boolean.getBoolean("bp.debug"));
        System.out.println("verified: " + (bad == 0 ? "clean by the game's rules" : bad + " things wrong by the game's rules"));
        System.exit(0);
    }
}
