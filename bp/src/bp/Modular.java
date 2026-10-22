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
        int problems;
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
            if(m[4] != null || r.ground != Recipe.Ground.none) continue;
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
        //(machines on ground are not cut into modules: each stands on its place)
        for(int i = 0; i < n; i++) if(((Recipe)ms.get(i)[0]).ground == Recipe.Ground.none) groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(ms.get(i));
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
        for(int attempt = 0; attempt < 4; attempt++){
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
        if(best != null) best.problems = bestBad;
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
            //(turned as it was laid out: an input pointing in, an output pointing off the module)
            int rot = res.equals("power") ? -1 : p.rot;
            float rate = in ? 0f : gen.portRate.getOrDefault(p, 0f);
            //(the port's carrier armoured only where a machine in the module would hand it, from a side, what is not
            //its thing: a plain one takes from behind and from its sides, an armoured one from a side only what a duct
            //pointing into it hands (Duct.acceptItem); else plain, the cheaper)
            boolean armour = false;
            if(rot >= 0 && Vars.content.item(res) != null) for(int d = 0; d < 4; d++){
                if(d == rot) continue;
                int nx = p.ax() + Geo.dx(d), ny = p.ay() + Geo.dy(d);
                if(!gen.board.in(nx, ny)) continue;
                Body.Part o = gen.board.partAt[gen.board.idx(nx, ny)];
                //(another port is a carrier then, handing on only ahead, into the module or out of it)
                if(o != null && o != p && !portParts.contains(o)) for(String y : Board.itemsOut(o)) if(!y.equals(res)) armour = true;
            }
            m.ports.add(new Object[]{in, res, p.ax(), p.ay(), rot, rate, armour});
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
        List<Object[]> pairs = build(groups);
        System.out.println(String.format("modules in %.1f s", (System.nanoTime() - t1) / 1e9));
        Spec s = composeFitting(pairs, new ArrayList<>(), seed);
        System.out.println(String.format("all in %.1f s", (System.nanoTime() - t0) / 1e9));
        return s;
    }

    /**
     * The modules and the bodies besides put together; a module that will not stand where it can be put (on ground it
     * cannot stand on, over another...) cut in two and the halves laid out, and put together again; a few times.
     */
    Spec composeFitting(List<Object[]> pairs, List<Body> extra, long seed){
        for(int round = 0; ; round++){
            Spec s = compose(pairs, extra, seed);
            Set<Integer> misfits = new HashSet<>();
            for(Body b : last.misplacedKept) if(b.id >= 1000 && b.id - 1000 < pairs.size()) misfits.add(b.id - 1000);
            if(misfits.isEmpty() || round >= 4) return s;
            List<Object[]> next = new ArrayList<>();
            boolean cut = false;
            for(int i = 0; i < pairs.size(); i++){
                Object[] pr = pairs.get(i);
                @SuppressWarnings("unchecked")
                List<Object[]> g = (List<Object[]>)pr[0];
                List<Object[]>[] halves = misfits.contains(i) ? halve(g) : null;
                if(halves == null){
                    next.add(pr);
                    continue;
                }
                String name = ((Module)pr[1]).name;
                System.out.println("module " + name + " does not fit: cut in two");
                buildOne(halves[0], name + "a", next);
                buildOne(halves[1], name + "b", next);
                cut = true;
            }
            if(!cut) return s;
            pairs = next;
        }
    }

    /**
     * Each group laid out as a module; a group whose module will not lay out clean cut in two, each half laid out in turn
     * (and so on): the modules as small as they must be to lay out clean. Pairs of {group, module}.
     */
    List<Object[]> build(List<List<Object[]>> groups){
        List<Object[]> out = new ArrayList<>();
        int k = 0;
        for(List<Object[]> g : groups) buildOne(g, product((Recipe)g.get(0)[0]).replace(':', '-').replace('~', 'x') + "-" + k++, out);
        return out;
    }

    void buildOne(List<Object[]> g, String name, List<Object[]> out){
        Module m = module(g, name);
        if(m != null && m.problems > 0){
            List<Object[]>[] halves = halve(g);
            if(halves != null){
                System.out.println("module " + name + " cut in two");
                buildOne(halves[0], name + "a", out);
                buildOne(halves[1], name + "b", out);
                return;
            }
            notes.add(name + ": its module kept " + m.problems + " problems");
        }
        out.add(new Object[]{g, m});
    }

    /** A group in two halves, by its machines (each kind's count split, its work with it); null if it is one machine. */
    @SuppressWarnings("unchecked")
    static List<Object[]>[] halve(List<Object[]> g){
        int machines = 0;
        for(Object[] m : g) if(m[4] == null) machines += (Integer)m[1];
        if(machines <= 1) return null;
        List<Object[]> a = new ArrayList<>(), b = new ArrayList<>();
        boolean toA = true;
        for(Object[] m : g){
            int count = (Integer)m[1];
            float work = (Float)m[2];
            int ca = count / 2, cb = count / 2;
            if(count % 2 == 1){
                if(toA) ca++;
                else cb++;
                toA = !toA;
            }
            if(ca > 0) a.add(new Object[]{m[0], ca, work * ca / count, m[3], m[4]});
            if(cb > 0) b.add(new Object[]{m[0], cb, work * cb / count, m[3], m[4]});
        }
        if(a.stream().noneMatch(m -> m[4] == null) || b.stream().noneMatch(m -> m[4] == null)) return null;
        return new List[]{a, b};
    }

    /**
     * The modules put on the map as whole bodies, with bodies besides (machines fixed on their places, blocks put down
     * once), and the lines between them laid: what each module's ports, each body besides, the map's sources and the core
     * make and take, shared out by the least carrying.
     */
    Spec compose(List<Object[]> pairs, List<Body> extra, long seed){
        Gen top = new Gen(prob, dataDir);
        top.balance = whole.balance;
        int id = 0;
        for(Spec.Place p : prob.fixed){
            Block bl = Vars.content.block(p.block());
            Body body = new Body(id++, p.block(), true);
            body.given = true;
            body.add(bl, 0, 0, p.rotation(), null, 1f, p.config());
            body.put(Geo.lo(p.x(), bl.size), Geo.lo(p.y(), bl.size), 0);
            top.bodies.add(body);
            if(Board.isCore(body.main())) top.core = body.main();
        }
        Set<Body.Part> outside = new HashSet<>();
        for(Body b : extra){
            top.bodies.add(b);
            outside.addAll(b.parts);
        }
        Map<Body.Part, String> inPorts = new HashMap<>();
        for(int gi = 0; gi < pairs.size(); gi++){
            Module m = (Module)pairs.get(gi)[1];
            @SuppressWarnings("unchecked")
            List<Object[]> group = (List<Object[]>)pairs.get(gi)[0];
            if(m == null) continue;
            Body body = new Body(1000 + gi, m.name, false);
            for(Body.Part p : m.parts) body.add(p.block, p.x0, p.y0, p.rot, p.recipe, p.work, p.config).yield = p.yield;
            for(Piece q : m.pieces) body.add(q.block, Geo.lo(q.x, q.block.size), Geo.lo(q.y, q.block.size), q.rot, null, 1f, null).piece = q;
            Map<String, Float> net = net(group);
            Map<String, Integer> portsOf = new HashMap<>();
            for(Object[] port : m.ports) if((Boolean)port[0]) portsOf.merge((String)port[1], 1, Integer::sum);
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
                //(a port armoured where something about it in the module would hand it what is not its thing)
                boolean armour = (Boolean)port[6];
                Block carrier = top.carriers.cheapest(liquid ? Carriers.Role.conduit : armour ? Carriers.Role.armored : Carriers.Role.duct, top.weights);
                Body.Part part = body.add(carrier, x, y, rot, null, 1f, res);
                outside.add(part);
                if(in){
                    inPorts.put(part, res);
                    top.portRate.put(part, -net.getOrDefault(res, 0f) / portsOf.get(res));
                }else top.supplyRate.put(part, (Float)port[5]);
            }
            body.put(0, 0, 0);
            top.bodies.add(body);
        }
        //what is made and taken between the bodies: by ports, by the bodies besides, by the map's sources, the core
        Set<String> res = new LinkedHashSet<>();
        for(Body b : top.bodies) for(Body.Part p : b.parts){
            if(p.config != null && Vars.content.block(p.config) == null) res.add(p.config);
            if(outside.contains(p) && p.recipe != null){
                res.addAll(p.recipe.at(p.yield).out.keySet());
                res.addAll(p.recipe.at(p.yield).in.keySet());
            }
        }
        res.addAll(prob.goals.keySet());
        for(String r : res){
            boolean item = Vars.content.item(r) != null, liquid = Vars.content.liquid(r) != null;
            if(!item && !liquid) continue;
            Placer.Group g = new Placer.Group(r, liquid);
            for(Body b : top.bodies) for(Body.Part p : b.parts){
                boolean source = (p.block instanceof ItemSource || p.block instanceof LiquidSource) && r.equals(p.config);
                boolean port = top.supplyRate.containsKey(p) && r.equals(p.config);
                boolean machine = outside.contains(p) && p.recipe != null && p.makes(r) > 1e-6f;
                if(source || port || machine) g.makers.add(p);
                if(inPorts.containsKey(p) && r.equals(inPorts.get(p)) || outside.contains(p) && p.recipe != null && p.takes(r) > 1e-6f || Board.isCore(p) && item && prob.goals.containsKey(r)) g.takers.add(p);
            }
            if(!g.makers.isEmpty() && !g.takers.isEmpty()) top.groups.add(g);
        }
        System.out.println(String.format("%d modules and %d bodies besides on the map; %d things between them", pairs.size(), extra.size(), top.groups.size()));
        Spec s = top.layOut(seed);
        String dump = System.getProperty("bp.dumpAt");
        if(dump != null){
            int[] r = Arrays.stream(dump.split(",")).mapToInt(Integer::parseInt).toArray();
            for(Body b : top.bodies) for(Body.Part p : b.parts) if(p.ax() >= r[0] && p.ax() <= r[2] && p.ay() >= r[1] && p.ay() <= r[3]) System.out.println("  dump part " + p.block.name + "@" + p.ax() + "," + p.ay() + " rot " + p.rot + " body " + b.label + (b.fixed ? " fixed" : "") + (p.config != null ? " config " + p.config : ""));
            for(Router.Net n : top.bestNets) for(Piece q : n.pieces) if(q.x >= r[0] && q.x <= r[2] && q.y >= r[1] && q.y <= r[3]) System.out.println("  dump piece " + q + " net " + n.id + " " + n.res + " from " + n.from + " to " + n.to);
        }
        top.notes.addAll(notes);
        for(String n : top.notes) System.out.println("  note: " + n);
        last = top;
        return s;
    }

    Gen last;

    static long key(int x, int y){
        return ((long)x << 32) | (y & 0xffffffffL);
    }

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
