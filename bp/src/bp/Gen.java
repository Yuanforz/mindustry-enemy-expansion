package bp;

import arc.files.*;
import bp.Board.*;
import com.google.ortools.linearsolver.*;
import mindustry.*;
import mindustry.world.*;

import java.util.*;

/**
 * The layout generator: a problem (a map, what stands on it, what is asked for) to a layout on it.
 * <ol>
 * <li>the balance (Balance): which machines, how many, how much each works;
 * <li>the bodies: each machine, a machine that needs heat with its heaters arranged against it (Heat), what stands fixed;
 * <li>where they go (Placer, annealing);
 * <li>who feeds whom: for each thing, its makers' output shared among its takers by the least carrying (a transportation
 * problem), each maker's share a line or more, of the carrier that takes the least for its rate;
 * <li>the lines laid (Router, negotiated congestion);
 * <li>power (Power, beam nodes);
 * <li>the layout written as a spec and checked by the game's rules (Check); where lines could not be laid, the
 * bodies about them are given more room and placed again.
 * </ol>
 * Usage: bp.Gen data-dir problem-file [out-file]
 */
public final class Gen{
    final Problem prob;
    final Carriers carriers;
    final Board board;
    final List<Body> bodies = new ArrayList<>();
    final List<String> notes = new ArrayList<>();
    Balance.Result balance;
    Body.Part core;
    final List<Placer.Group> groups = new ArrayList<>();
    List<Router.Net> bestNets = new ArrayList<>();
    List<Piece> bestPower = new ArrayList<>();
    int problems;

    Gen(Problem prob, String dataDir){
        this.prob = prob;
        carriers = new Carriers(dataDir);
        board = new Board(prob.ground, carriers);
        board.goals.addAll(prob.goals.keySet());
    }

    // ------------------------------------------------------------------ 1-2: the balance and the bodies

    void balance(){
        Balance bal = new Balance();
        bal.goals.putAll(prob.goals);
        bal.perTile = prob.perTile;
        bal.perItem = prob.perItem;
        bal.perSecond = prob.perSecond;
        for(Spec.Place p : prob.fixed){
            if(p.block().equals("item-source") || p.block().equals("liquid-source")) bal.sources.add(p.config());
            if(Vars.content.block(p.block()) instanceof mindustry.world.blocks.sandbox.PowerSource) bal.sources.add("power");
        }
        if(prob.freePower) bal.sources.add("power");
        for(Recipe r : Recipes.all()) if(r.kind != Recipe.Kind.source && !r.name.equals("heat-source")) bal.recipes.add(r);
        bal.expandWildcards();
        balance = bal.solve();
        System.out.println(balance);
    }

    void bodies(){
        int id = 0;
        for(Spec.Place p : prob.fixed){
            Block bl = Vars.content.block(p.block());
            Body body = new Body(id++, p.block(), true);
            Recipe r = null;
            body.add(bl, 0, 0, p.rotation(), r, 1f, p.config());
            body.put(Geo.lo(p.x(), bl.size), Geo.lo(p.y(), bl.size), 0);
            bodies.add(body);
            if(Board.isCore(body.main())) core = body.main();
        }
        for(Object[] m : balance.machines){
            Recipe r = (Recipe)m[0];
            int count = (Integer)m[1];
            float work = (Float)m[2];
            if(m[4] != null) continue;
            if(r.ground != Recipe.Ground.none){
                notes.add("not laid out yet: " + r.name + " stands on ground (" + r.ground + ")");
                continue;
            }
            for(int k = 0; k < count; k++){
                Body body = new Body(id++, r.name, false);
                body.add(r.block, 0, 0, 0, r, work / count, null);
                if(r.heatNeed > 0f) heat(body, r);
                body.put(0, 0, 0);
                bodies.add(body);
            }
        }
    }

    /** Heaters against a machine that needs heat, of the kinds the balance chose for it. */
    void heat(Body body, Recipe r){
        List<Recipe> heaters = new ArrayList<>();
        List<Block> redirectors = new ArrayList<>();
        for(Object[] m : balance.machines){
            if(m[4] == null) continue;
            String label = (String)m[4];
            Recipe h = (Recipe)m[0];
            if(label.equals("heating " + r.name)) heaters.add(h);
            if(label.equals("gathering heat for " + r.name)) redirectors.add(h.block);
        }
        int ports = 0;
        for(String k : r.in.keySet()) if(Vars.content.item(k) != null || Vars.content.liquid(k) != null) ports++;
        for(String k : r.out.keySet()) if(Vars.content.item(k) != null || Vars.content.liquid(k) != null) ports++;
        if(r.powerUse > 0f || r.powerOut > 0f) ports++;
        List<Heat.Put> puts = Heat.arrange(r, heaters, redirectors, prob.perTile, prob.perItem, ports);
        if(puts == null){
            notes.add("no arrangement of heaters for " + r.name + " with " + heaters.size() + " kinds of heater");
            return;
        }
        for(Heat.Put p : puts) body.add(p.block(), p.x0(), p.y0(), p.rot(), p.recipe(), 1f, null);
    }

    void groups(){
        Set<String> res = new LinkedHashSet<>();
        for(Body body : bodies) for(Body.Part p : body.parts){
            if(p.recipe != null){
                res.addAll(p.recipe.in.keySet());
                res.addAll(p.recipe.out.keySet());
                res.addAll(p.recipe.fixedIn.keySet());
                res.addAll(p.recipe.fixedOut.keySet());
            }
            if(p.config != null) res.add(p.config);
        }
        for(String k : res){
            boolean item = Vars.content.item(k) != null, liquid = Vars.content.liquid(k) != null;
            if(!item && !liquid) continue;
            Placer.Group g = new Placer.Group(k, liquid);
            for(Body body : bodies) for(Body.Part p : body.parts){
                if(p.makes(k) > 1e-6f || k.equals(p.config) && (p.block instanceof mindustry.world.blocks.sandbox.ItemSource || p.block instanceof mindustry.world.blocks.sandbox.LiquidSource)) g.makers.add(p);
                if(p.takes(k) > 1e-6f || Board.isCore(p) && item && prob.goals.containsKey(k)) g.takers.add(p);
                if(p.block instanceof mindustry.world.blocks.sandbox.LiquidVoid && liquid && drained(k)) g.takers.add(p);
            }
            if(!g.makers.isEmpty() && !g.takers.isEmpty()) groups.add(g);
        }
    }

    /** Whether something must be rid of this liquid all the time. */
    boolean drained(String liquid){
        for(Body body : bodies) for(Body.Part p : body.parts) if(liquid.equals(Board.mustDrain(p))) return true;
        return false;
    }

    // ------------------------------------------------------------------ 4: who feeds whom

    /** What a maker gives a second; a source as much as lines from each of its sides carry. */
    float supply(Body.Part p, String res){
        if(p.recipe == null){
            boolean liquid = Vars.content.liquid(res) != null;
            Block line = carriers.cheapest(liquid ? Carriers.Role.conduit : Carriers.Role.duct, prob.perTile, prob.perItem);
            return p.around().size() * carriers.rate.get(line);
        }
        return p.makes(res);
    }

    float demand(Body.Part p, String res){
        if(Board.isCore(p)) return balance.delivered.getOrDefault(res, prob.goals.getOrDefault(res, 0f));
        return p.takes(res);
    }

    List<Router.Net> nets(){
        List<Router.Net> nets = new ArrayList<>();
        int id = 0;
        float duct = carriers.rate.get(carriers.cheapest(Carriers.Role.duct, prob.perTile, prob.perItem));
        Block surgeBlock = carriers.cheapest(Carriers.Role.surge, prob.perTile, prob.perItem);
        float surge = surgeBlock == null ? 0f : carriers.rate.get(surgeBlock);
        double ductCost = Carriers.cost(carriers.cheapest(Carriers.Role.duct, prob.perTile, prob.perItem), prob.perTile, prob.perItem);
        double surgeCost = surgeBlock == null ? Double.MAX_VALUE : Carriers.cost(surgeBlock, prob.perTile, prob.perItem);
        for(Placer.Group g : groups){
            List<Body.Part> ms = g.makers, ts = g.takers;
            //(the transportation problem: least rate times gap, each taker's need met, no maker past what it makes)
            MPSolver lp = MPSolver.createSolver("GLOP");
            MPVariable[][] f = new MPVariable[ms.size()][ts.size()];
            MPObjective obj = lp.objective();
            MPConstraint[] need = new MPConstraint[ts.size()];
            for(int j = 0; j < ts.size(); j++){
                float d = demand(ts.get(j), g.res);
                need[j] = lp.makeConstraint(d, MPSolver.infinity());
                //(a shortfall, at a great price: the balance's rates are met to a thousandth)
                MPVariable short_ = lp.makeNumVar(0, d, "short" + j);
                need[j].setCoefficient(short_, 1);
                obj.setCoefficient(short_, 1e4);
            }
            for(int i = 0; i < ms.size(); i++){
                MPConstraint cap = lp.makeConstraint(0, supply(ms.get(i), g.res));
                for(int j = 0; j < ts.size(); j++){
                    if(ms.get(i) == ts.get(j)) continue;
                    f[i][j] = lp.makeNumVar(0, MPSolver.infinity(), "f" + i + "_" + j);
                    cap.setCoefficient(f[i][j], 1);
                    need[j].setCoefficient(f[i][j], 1);
                    obj.setCoefficient(f[i][j], Placer.gap(ms.get(i), ts.get(j)) + 1);
                }
            }
            obj.setMinimization();
            lp.solve();
            for(int i = 0; i < ms.size(); i++){
                Body.Part m = ms.get(i);
                List<Body.Part> to = new ArrayList<>();
                List<Float> fl = new ArrayList<>();
                for(int j = 0; j < ts.size(); j++) if(f[i][j] != null && f[i][j].solutionValue() > 1e-5){
                    to.add(ts.get(j));
                    fl.add((float)f[i][j].solutionValue());
                }
                if(to.isEmpty()) continue;
                if(g.liquid){
                    Router.Net n = new Router.Net(id++, g.res, 2, m);
                    n.to.addAll(to);
                    n.flows.addAll(fl);
                    nets.add(n);
                    continue;
                }
                //(lines of ducts, as many takers on one as its rate allows, first fit by need; a taker that needs more than a
                //duct carries on its own lines, of ducts or surge conveyors, whichever takes less)
                List<Integer> order = new ArrayList<>();
                for(int j = 0; j < to.size(); j++) order.add(j);
                order.sort((a, c) -> Float.compare(fl.get(c), fl.get(a)));
                List<Router.Net> bins = new ArrayList<>();
                for(int j : order){
                    float x = fl.get(j);
                    Body.Part t = to.get(j);
                    if(x > duct){
                        int kd = (int)Math.ceil(x / duct), ks = surge > 0 ? (int)Math.ceil(x / surge) : Integer.MAX_VALUE;
                        boolean useSurge = ks != Integer.MAX_VALUE && ks * surgeCost < kd * ductCost;
                        int k = useSurge ? ks : kd;
                        for(int c = 0; c < k; c++){
                            Router.Net n = new Router.Net(id++, g.res, useSurge ? 1 : 0, m);
                            n.to.add(t);
                            n.flows.add(x / k);
                            nets.add(n);
                        }
                        continue;
                    }
                    Router.Net into = null;
                    for(Router.Net n : bins) if(n.flow() + x <= duct + 1e-4f){
                        into = n;
                        break;
                    }
                    if(into == null){
                        into = new Router.Net(id++, g.res, 0, m);
                        bins.add(into);
                        nets.add(into);
                    }
                    into.to.add(t);
                    into.flows.add(x);
                }
            }
        }
        //drains for what must be got rid of all the time: into the nearest void standing there (the sandbox's), else let
        //out onto the ground, where it harms least (Router.harmAt: a liquid floor it cannot lie on takes it, away from what
        //holds the liquid its puddles spread into)
        for(Body body : bodies) for(Body.Part p : body.parts){
            String liquid = Board.mustDrain(p);
            if(liquid == null) continue;
            Body.Part sink = null;
            for(Body o : bodies) for(Body.Part q : o.parts) if(q.block instanceof mindustry.world.blocks.sandbox.LiquidVoid && (sink == null || Placer.gap(p, q) < Placer.gap(p, sink))) sink = q;
            Router.Net n = new Router.Net(id++, liquid, 2, p);
            if(sink != null){
                n.to.add(sink);
                n.flows.add(p.makes(liquid));
            }else n.drain = true;
            nets.add(n);
        }
        return nets;
    }

    // ------------------------------------------------------------------ the whole

    Spec spec(Router router, Power power){
        Spec s = new Spec();
        s.rows.addAll(prob.ground.rows);
        s.seconds = prob.seconds;
        s.places.addAll(prob.fixed);
        for(Body body : bodies) if(!body.fixed) for(Body.Part p : body.parts) s.places.add(new Spec.Place(p.block.name, p.ax(), p.ay(), p.rot, p.config));
        for(Piece q : router.all()) s.places.add(new Spec.Place(q.block.name, q.x, q.y, q.rot, null));
        if(power != null) for(Piece q : power.added) s.places.add(new Spec.Place(q.block.name, q.x, q.y, q.rot, null));
        return s;
    }

    Spec run(long seed){
        long t0 = System.nanoTime();
        balance();
        if(!balance.solved) return null;
        bodies();
        groups();
        Placer placer = new Placer(board, bodies, groups, seed);
        Spec best = null;
        int bestBad = Integer.MAX_VALUE;
        Map<Body, int[]> bestAt = new HashMap<>();
        List<String> bestNotes = new ArrayList<>();
        for(int attempt = 0; attempt < 8; attempt++){
            //(the first placing from anywhere; after it, mending: from where things are, cool, so that what was laid well
            //stays and the room asked for is found about the places fought over)
            int steps = (attempt == 0 ? 4000 : 1500) * Math.max(4, placer.movable.size());
            placer.run(steps, attempt == 0, attempt == 0 ? 1.0 : 0.05);
            System.out.println(String.format("placing %d: cost %.1f (%d bodies, %d steps)", attempt, placer.cost, bodies.size(), steps));
            List<Router.Net> nets = nets();
            Router router = new Router(board, nets, prob.perTile, prob.perItem);
            boolean clean = router.run(80);
            Power power = new Power(board, bodies, router.all(), prob.freePower, prob.perTile, prob.perItem).run();
            List<String> here = new ArrayList<>();
            int unreached = 0;
            for(Router.Net n : nets) if(!n.unreached.isEmpty()){
                unreached += n.unreached.size();
                here.add("not reached: " + n + " to " + n.unreached);
            }
            for(Router.Net n : nets) if(n.drain && !n.drained){
                unreached++;
                here.add("not drained: " + n);
            }
            if(!power.unpowered.isEmpty()) here.add("without power: " + power.unpowered);
            int bad = unreached + router.overused + router.leaks + power.unpowered.size();
            System.out.println(String.format("lines: %d, %s after %d rounds (%d tiles shared, %d leaks, %d not reached); power: %d nodes, %d without", nets.size(), clean ? "clean" : "NOT clean", router.rounds, router.overused, router.leaks, unreached, power.added.size(), power.unpowered.size()));
            for(String h : here) System.out.println("    " + h);
            if(bad < bestBad){
                bestBad = bad;
                best = spec(router, power);
                bestNotes = here;
                bestNets = nets;
                bestPower = power.added;
                bestAt.clear();
                for(Body body : bodies) bestAt.put(body, new int[]{body.ox, body.oy, body.rot});
            }
            if(bad == 0) break;
            //(more room about the parts whose lines could not be laid, and about the ends of lines still fought over)
            for(Router.Net n : nets) for(Body.Part p : n.unreached){
                placer.margin.merge(p, 1, Integer::sum);
                placer.margin.merge(n.from, 1, Integer::sum);
            }
            //(and about each tile still fought over, the parts against it)
            Set<Body.Part> about = new HashSet<>();
            for(int i : router.fought){
                int x = i % board.w, y = i / board.w;
                for(int d = 0; d < 4; d++){
                    int nx = x + Geo.dx(d), ny = y + Geo.dy(d);
                    if(board.in(nx, ny) && board.partAt[board.idx(nx, ny)] != null) about.add(board.partAt[board.idx(nx, ny)]);
                }
            }
            for(Body.Part p : about) placer.margin.merge(p, 1, Integer::sum);
            placer.wCrowd *= 1.3f;
        }
        notes.addAll(bestNotes);
        for(var e : bestAt.entrySet()) e.getKey().put(e.getValue()[0], e.getValue()[1], e.getValue()[2]);
        board.putParts(bodies);
        problems = bestBad;
        System.out.println(String.format("generated in %.1f s; kept the layout with %d problems", (System.nanoTime() - t0) / 1e9, bestBad));
        for(String n : notes) System.out.println("  note: " + n);
        return best;
    }

    public static void main(String[] args){
        Game.boot(args[0]);
        Problem prob = Problem.read(new Fi(args[1]));
        Gen gen = new Gen(prob, args[0]);
        Spec s = gen.run(args.length > 3 ? Long.parseLong(args[3]) : 1L);
        if(s == null){
            System.out.println("no balance: " + gen.balance.status);
            System.exit(1);
        }
        s.name = prob.name;
        Fi out = new Fi(args.length > 2 ? args[2] : "build/bp-out/" + prob.name + ".txt");
        //(the bench starts it primed: how a layout gets going is left out for now)
        out.writeString("# laid out by bp.Gen from " + args[1] + "\n" + s.write() + "prime\n");
        System.out.println("written " + out.path());
        try{
            java.io.File png = new java.io.File(out.pathWithoutExtension() + ".png");
            Plot.draw(gen.board, gen.bodies, gen.bestNets, gen.bestPower, prob.name + ": " + String.join(", ", prob.goals.keySet()) + (gen.problems > 0 ? "  (" + gen.problems + " problems left)" : ""), png);
            System.out.println("drawn " + png.getPath());
        }catch(Exception e){
            System.out.println("could not draw: " + e);
        }
        System.out.print(new Check(new Scratch(s)).run().report());
        System.out.println(drawn(s));
        System.exit(0);
    }

    /** The layout drawn in characters, top row first (a letter for each block, by its name's first letter). */
    static String drawn(Spec s){
        int w = s.width(), h = s.height();
        char[][] g = new char[h][w];
        for(int y = 0; y < h; y++) for(int x = 0; x < w; x++){
            char c = s.at(x, y);
            g[y][x] = c == '.' ? '.' : c;
        }
        for(Spec.Place p : s.places){
            Block b = Vars.content.block(p.block());
            if(b == null) continue;
            int x0 = Geo.lo(p.x(), b.size), y0 = Geo.lo(p.y(), b.size);
            char c = symbol(b, p.rotation());
            for(int y = y0; y < y0 + b.size; y++) for(int x = x0; x < x0 + b.size; x++) if(x >= 0 && y >= 0 && x < w && y < h) g[y][x] = c;
        }
        StringBuilder out = new StringBuilder();
        for(int y = h - 1; y >= 0; y--) out.append(new String(g[y])).append('\n');
        return out.toString();
    }

    static char symbol(Block b, int rot){
        Carriers.Role r = Carriers.role(b);
        if(r == Carriers.Role.duct || r == Carriers.Role.armored || r == Carriers.Role.conduit) return ">^<v".charAt(rot);
        if(r == Carriers.Role.surge) return "enws".charAt(rot);
        if(r == Carriers.Role.router) return '+';
        if(r == Carriers.Role.bridge || r == Carriers.Role.liquidBridge) return '=';
        if(r == Carriers.Role.node) return '*';
        if(r == Carriers.Role.powerSource) return 'P';
        return Character.toUpperCase(b.name.charAt(0));
    }
}
