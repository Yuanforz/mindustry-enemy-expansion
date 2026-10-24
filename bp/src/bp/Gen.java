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

    final Weights weights;
    /** The recipes the balance may use (null: all): a module keeps to those the whole plan chose for it. */
    Set<String> allowed;

    Gen(Problem prob, String dataDir){
        this.prob = prob;
        weights = new Weights(prob.perTile, prob.perItem, prob.perSecond);
        carriers = new Carriers(dataDir);
        board = new Board(prob.ground, carriers);
        board.goals.addAll(prob.goals.keySet());
        //(a saved map's own cores of the player, standing where they were, if the problem names none)
        boolean hasCore = false;
        for(Spec.Place p : prob.fixed) if(Vars.content.block(p.block()) instanceof mindustry.world.blocks.storage.CoreBlock) hasCore = true;
        if(!hasCore) prob.fixed.addAll(prob.ground.worldCores);
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
        //(a module's input ports: what they give, without limit)
        for(Problem.Port port : prob.ports) if(port.in()) bal.sources.add(port.kind().equals("power") ? "power" : port.res());
        for(Recipe r : Recipes.all()) if(r.kind != Recipe.Kind.source && !r.name.equals("heat-source") && (allowed == null || allowed.contains(r.name))) bal.recipes.add(r);
        bal.expandWildcards();
        balance = bal.solve();
        System.out.println(balance);
    }

    void bodies(){
        int id = 0;
        for(Spec.Place p : prob.fixed){
            Block bl = Vars.content.block(p.block());
            Body body = new Body(id++, p.block(), true);
            body.given = true;
            Recipe r = null;
            body.add(bl, 0, 0, p.rotation(), r, 1f, p.config());
            body.put(Geo.lo(p.x(), bl.size), Geo.lo(p.y(), bl.size), 0);
            bodies.add(body);
            if(Board.isCore(body.main())) core = body.main();
        }
        ports(id);
        id = bodies.size() + 1000;
        for(Object[] m : balance.machines){
            Recipe r = (Recipe)m[0];
            int count = (Integer)m[1];
            float work = (Float)m[2];
            if(m[4] != null) continue;
            if(r.ground != Recipe.Ground.none){
                notes.add("not laid out yet: " + r.name + " stands on ground (" + r.ground + ")");
                continue;
            }
            for(int k = 0; k < count; k++) bodies.add(machine(id++, r, work / count));
        }
    }

    /** One machine as a body: with the heaters that heat it, an assembler with its area and modules. */
    Body machine(int id, Recipe r, float work){
        return machine(id, r, work, null);
    }

    /** One machine as a body, heated (if it needs heat) by heaters of this one kind, or (null) the kinds the plan chose. */
    Body machine(int id, Recipe r, float work, Recipe heater){
        Body body = new Body(id, r.name, false);
        //(what a block that is set to make one thing of several is set to: a unit factory's unit, a constructor's
        //block, the part of the recipe's name after its block's)
        String config = (r.block instanceof mindustry.world.blocks.units.UnitFactory || r.block instanceof mindustry.world.blocks.payloads.Constructor) && r.name.contains(":") ? r.name.substring(r.name.indexOf(':') + 1) : null;
        body.add(r.block, 0, 0, 0, r, work, config);
        if(r.heatNeed > 0f) heat(body, r, heater);
        if(r.block instanceof mindustry.world.blocks.units.UnitAssembler a) assembler(body, r, a);
        body.put(0, 0, 0);
        return body;
    }

    /**
     * An assembler with its area and its modules: facing +x, its area the square of areaSize in front of it, its centre
     * (areaSize + size) / 2 ahead of the assembler's (UnitAssembler.getRect, getUnitSpawn); a module stands against the
     * area's far side facing the centre, its front tile on the area's edge (UnitAssembler.moduleFits).
     */
    void assembler(Body body, Recipe r, mindustry.world.blocks.units.UnitAssembler a){
        int s = a.size, area = a.areaSize;
        int cx = Geo.at(0, s), spawn = cx + (area + s) / 2;
        int half = area / 2;
        body.clearLocal.add(new int[]{spawn - half, cx - half, spawn + half, cx + half});
        body.exclusive = true;
        for(var e : r.parts.entrySet()){
            if(e.getKey() == r.block) continue;
            Block m = e.getKey();
            //(the first module straight across the area; more, at its sides)
            int[][] at = {{spawn + half + 1, Geo.lo(cx, m.size), 2}, {Geo.lo(spawn, m.size), cx + half + 1, 3}, {Geo.lo(spawn, m.size), cx - half - m.size, 1}};
            for(int k = 0; k < e.getValue() && k < at.length; k++) body.add(m, at[k][0], at[k][1], at[k][2], null, 1f, null);
        }
    }

    /** What each output port of a module takes a second. */
    final Map<Body.Part, Float> portRate = new HashMap<>();

    /**
     * A module's ports: each a block of the sandbox on the module's edge, free to stand anywhere along it (the placing
     * chooses): an input a source of what it gives, an output a void of what it takes (so that the module runs alone in
     * the engine), power a power source. With "out spare", each thing the balance has to spare gets an output port too.
     */
    void ports(int id){
        if(prob.moduleW <= 0) return;
        List<Problem.Port> all = new ArrayList<>(prob.ports);
        if(prob.spare) for(var e : balance.spare.entrySet()){
            String k = e.getKey();
            boolean taken = false;
            for(Problem.Port p : prob.ports) if(p.res().equals(k)) taken = true;
            if(taken || k.equals("power") || e.getValue() <= 1e-3f) continue;
            all.add(new Problem.Port(false, Vars.content.liquid(k) != null ? "liquid" : "item", k, e.getValue()));
        }
        int w = prob.moduleW, h = prob.moduleH;
        for(Problem.Port p : all){
            boolean liquid = p.kind().equals("liquid"), power = p.kind().equals("power");
            Block bl = power ? mindustry.content.Blocks.powerSource : p.in() ? (liquid ? mindustry.content.Blocks.liquidSource : mindustry.content.Blocks.itemSource) : (liquid ? mindustry.content.Blocks.liquidVoid : mindustry.content.Blocks.itemVoid);
            Body body = new Body(id++, (p.in() ? "in " : "out ") + p.res(), false);
            Body.Part part = body.add(bl, 0, 0, 0, null, 1f, power ? null : p.res());
            if(!p.in()) portRate.put(part, p.rate() > 0 ? balance.delivered.getOrDefault(p.res(), p.rate()) : p.rate());
            Placer.Places places = new Placer.Places();
            //(its edge: the tiles a block can stand on with a neighbour that is off the module or no ground to build on;
            //the game keeps the outermost tiles of a world unbuildable)
            //(turned as the carrier will be: an input fed from off the module behind it, pointing in; an output pointing
            //off the module)
            for(int x = 0; x < w; x++) for(int y = 0; y < h; y++){
                if(!board.floor(x, y)) continue;
                for(int d = 0; d < 4; d++){
                    if(board.floor(x + Geo.dx(d), y + Geo.dy(d))) continue;
                    places.at.add(new int[]{x, y, power ? 0 : p.in() ? (d + 2) % 4 : d});
                    places.yields.add(0f);
                    places.lanes.add(new int[0]);
                    if(power) break;
                }
            }
            part.port = !power;
            body.put(places.at.get(0)[0], places.at.get(0)[1], places.at.get(0)[2]);
            portPlaces.put(body, places);
            bodies.add(body);
        }
    }

    final Map<Body, Placer.Places> portPlaces = new HashMap<>();

    /** Heaters against a machine that needs heat, of the kinds the balance chose for it. */
    void heat(Body body, Recipe r, Recipe only){
        List<Recipe> heaters = new ArrayList<>();
        List<Block> redirectors = new ArrayList<>();
        if(only != null){
            heaters.add(only);
            redirectors.addAll(Recipes.conductors());
        }else for(Object[] m : balance.machines){
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
        List<Heat.Put> puts = Heat.arrange(r, heaters, redirectors, weights, ports);
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
                res.addAll(p.recipe.perYield.keySet());
                res.addAll(p.recipe.fixedIn.keySet());
                res.addAll(p.recipe.fixedOut.keySet());
            }
            if(p.config != null) res.add(p.config);
        }
        for(String k : res){
            boolean item = Vars.content.item(k) != null, liquid = Vars.content.liquid(k) != null;
            if(!item && !liquid && !Board.isPayload(k)) continue;
            Placer.Group g = new Placer.Group(k, liquid);
            for(Body body : bodies) for(Body.Part p : body.parts){
                //(a machine a strip serves takes and gives this through the strip's ports, not by lines of its own: Strips)
                if(served.getOrDefault(p, Set.of()).contains(k)) continue;
                if(k.equals(portMakes.get(p))) g.makers.add(p);
                if(p.makes(k) > 1e-6f || k.equals(p.config) && (p.block instanceof mindustry.world.blocks.sandbox.ItemSource || p.block instanceof mindustry.world.blocks.sandbox.LiquidSource)) g.makers.add(p);
                if(p.takes(k) > 1e-6f || Board.isCore(p) && item && prob.goals.containsKey(k)) g.takers.add(p);
                if(p.block instanceof mindustry.world.blocks.sandbox.LiquidVoid && liquid && drained(k) && p.config == null) g.takers.add(p);
                if(portRate.containsKey(p) && k.equals(p.config)) g.takers.add(p);
            }
            //(of what stands already, only a maker with some to spare, a taker still short)
            g.makers.removeIf(p -> spare.containsKey(p) && spare.get(p).getOrDefault(k, 0f) <= 1e-4f);
            g.takers.removeIf(p -> lacks.containsKey(p) && lacks.get(p).getOrDefault(k, 0f) <= 1e-4f);
            if(g.payload) for(Body.Part t : g.takers) g.buffer.put(t, Math.max(0, (int)batch(t, k) - 1));
            if(!g.makers.isEmpty() && !g.takers.isEmpty()) groups.add(g);
        }
    }

    /** Whether something must be rid of this liquid all the time. */
    boolean drained(String liquid){
        for(Body body : bodies) for(Body.Part p : body.parts) if(liquid.equals(Board.mustDrain(p))) return true;
        return false;
    }

    // ------------------------------------------------------------------ 4: who feeds whom

    /**
     * Of parts that stand already (a base grown), what each has to spare and what each still lacks, by thing; the most it
     * hands any one taker now; and how many lines it has to the core, by thing.
     */
    final Map<Body.Part, Map<String, Float>> spare = new HashMap<>(), lacks = new HashMap<>(), fedMost = new HashMap<>();
    final Map<Body.Part, Map<String, Integer>> coreLinks = new HashMap<>();

    /** What a maker gives a second; a source as much as lines from each of its sides carry. */
    float supply(Body.Part p, String res){
        if(spare.containsKey(p)) return spare.get(p).getOrDefault(res, 0f);
        if(supplyRate.containsKey(p)) return supplyRate.get(p);
        if(p.recipe == null){
            boolean liquid = Vars.content.liquid(res) != null;
            Block line = carriers.cheapest(liquid ? Carriers.Role.conduit : Carriers.Role.duct, weights);
            return p.around().size() * carriers.rate.get(line);
        }
        return p.makes(res);
    }

    float demand(Body.Part p, String res){
        if(lacks.containsKey(p)) return lacks.get(p).getOrDefault(res, 0f);
        if(portRate.containsKey(p)) return portRate.get(p);
        if(Board.isCore(p)) return balance.delivered.getOrDefault(res, prob.goals.getOrDefault(res, 0f));
        return p.takes(res);
    }

    List<Router.Net> nets(){
        List<Router.Net> nets = new ArrayList<>();
        int id = 0;
        float duct = carriers.rate.get(carriers.cheapest(Carriers.Role.duct, weights));
        Block surgeBlock = carriers.cheapest(Carriers.Role.surge, weights);
        float surge = surgeBlock == null ? 0f : carriers.rate.get(surgeBlock);
        double ductCost = weights.cost(carriers.cheapest(Carriers.Role.duct, weights));
        double surgeCost = surgeBlock == null ? Double.MAX_VALUE : weights.cost(surgeBlock);
        for(Placer.Group g : groups){
            if(g.payload){
                id = payloadNets(g, nets, id);
                continue;
            }
            List<Body.Part> ms = g.makers, ts = g.takers;
            //(the transportation problem: least rate times gap, each taker's need met, no maker past what it makes. A
            //maker hands round to all about it that take (Building.dump), and the core takes all it is handed: a maker
            //with a line to the core hands it as much as it hands any other line, and all it hands no other)
            boolean anyCore = false;
            for(Body.Part t : ts) if(Board.isCore(t)) anyCore = true;
            MPSolver lp = MPSolver.createSolver(anyCore ? "SCIP" : "GLOP");
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
            double inf = MPSolver.infinity();
            for(int i = 0; i < ms.size(); i++){
                Body.Part m = ms.get(i);
                float sup = supply(m, g.res);
                int links = coreLinks.getOrDefault(m, Map.of()).getOrDefault(g.res, 0);
                MPConstraint cap = lp.makeConstraint(0, sup);
                MPVariable core = null;
                for(int j = 0; j < ts.size(); j++){
                    if(m == ts.get(j)) continue;
                    boolean isCore = Board.isCore(ts.get(j));
                    //(one with a line to the core hands it all that is left already: no second)
                    if(isCore && links > 0) continue;
                    f[i][j] = lp.makeNumVar(0, inf, "f" + i + "_" + j);
                    cap.setCoefficient(f[i][j], 1);
                    need[j].setCoefficient(f[i][j], 1);
                    obj.setCoefficient(f[i][j], Placer.gap(m, ts.get(j)) + 1);
                    if(isCore) core = f[i][j];
                }
                if(links > 0){
                    //(its lines to the core take, each, as much as any other line of it, and between them all the rest;
                    //the takers it hands to now keep what they get, a shortfall of theirs as dear as any)
                    MPVariable level = lp.makeNumVar(0, inf, "level" + i);
                    cap.setCoefficient(level, links);
                    cap.setBounds(sup, sup);
                    for(int j = 0; j < ts.size(); j++){
                        if(f[i][j] == null) continue;
                        MPConstraint c = lp.makeConstraint(-inf, 0);
                        c.setCoefficient(f[i][j], 1);
                        c.setCoefficient(level, -1);
                    }
                    float most = fedMost.getOrDefault(m, Map.of()).getOrDefault(g.res, 0f);
                    if(most > 0f){
                        MPVariable lack = lp.makeNumVar(0, most, "kept" + i);
                        MPConstraint keep = lp.makeConstraint(most, inf);
                        keep.setCoefficient(level, 1);
                        keep.setCoefficient(lack, 1);
                        obj.setCoefficient(lack, 1e4);
                    }
                }else if(core != null){
                    //(given a line to the core now or not; given one, it takes as much as any other line of the maker, and
                    //all the rest)
                    MPVariable y = lp.makeIntVar(0, 1, "y" + i);
                    MPConstraint on = lp.makeConstraint(-inf, 0);
                    on.setCoefficient(core, 1);
                    on.setCoefficient(y, -sup);
                    MPConstraint all = lp.makeConstraint(0, inf);
                    for(int j = 0; j < ts.size(); j++) if(f[i][j] != null) all.setCoefficient(f[i][j], 1);
                    all.setCoefficient(y, -sup);
                    for(int j = 0; j < ts.size(); j++){
                        if(f[i][j] == null || f[i][j] == core) continue;
                        MPConstraint c = lp.makeConstraint(-inf, sup);
                        c.setCoefficient(f[i][j], 1);
                        c.setCoefficient(core, -1);
                        c.setCoefficient(y, sup);
                    }
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
            //(what stands already was drained when it was laid)
            if(liquid == null || body.standing) continue;
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

    /**
     * Lines of a payload: a maker hands its payload only out of its front, so each maker feeds one taker (an
     * assignment, SCIP: least gap, each taker's need met as far as it can be, a shortfall dear). A taker that takes a
     * whole batch at once (an assembler: the plan's payloads, all before it starts, none while it works) wants its lines to
     * hold what comes in while it works, its share of the batch, less the one its maker holds: the line's buffer.
     */
    int payloadNets(Placer.Group g, List<Router.Net> nets, int id){
        List<Body.Part> ms = g.makers, ts = g.takers;
        MPSolver ip = MPSolver.createSolver("SCIP");
        MPVariable[][] x = new MPVariable[ms.size()][ts.size()], f = new MPVariable[ms.size()][ts.size()];
        MPObjective obj = ip.objective();
        MPConstraint[] need = new MPConstraint[ts.size()];
        for(int j = 0; j < ts.size(); j++){
            float d = demand(ts.get(j), g.res);
            need[j] = ip.makeConstraint(d, MPSolver.infinity());
            MPVariable short_ = ip.makeNumVar(0, d, "short" + j);
            need[j].setCoefficient(short_, 1);
            obj.setCoefficient(short_, 1e4);
        }
        for(int i = 0; i < ms.size(); i++){
            MPConstraint one = ip.makeConstraint(0, 1);
            for(int j = 0; j < ts.size(); j++){
                if(ms.get(i) == ts.get(j)) continue;
                x[i][j] = ip.makeIntVar(0, 1, "x" + i + "_" + j);
                f[i][j] = ip.makeNumVar(0, MPSolver.infinity(), "f" + i + "_" + j);
                MPConstraint cap = ip.makeConstraint(-MPSolver.infinity(), 0);
                cap.setCoefficient(f[i][j], 1);
                cap.setCoefficient(x[i][j], -supply(ms.get(i), g.res));
                one.setCoefficient(x[i][j], 1);
                need[j].setCoefficient(f[i][j], 1);
                obj.setCoefficient(x[i][j], Placer.gap(ms.get(i), ts.get(j)) + 1);
            }
        }
        obj.setMinimization();
        ip.solve();
        for(int i = 0; i < ms.size(); i++) for(int j = 0; j < ts.size(); j++){
            if(x[i][j] == null || x[i][j].solutionValue() < 0.5) continue;
            Body.Part m = ms.get(i), t = ts.get(j);
            Router.Net n = new Router.Net(id++, g.res, 3, m);
            n.to.add(t);
            float flow = (float)f[i][j].solutionValue();
            n.flows.add(flow);
            n.buffer = Math.max(0, (int)Math.ceil(batch(t, g.res) * flow / Math.max(1e-9f, demand(t, g.res)) - 1e-4) - 1);
            nets.add(n);
        }
        for(int j = 0; j < ts.size(); j++){
            float got = 0f;
            for(int i = 0; i < ms.size(); i++) if(f[i][j] != null && x[i][j].solutionValue() > 0.5) got += f[i][j].solutionValue();
            if(got < demand(ts.get(j), g.res) * 0.999f) notes.add(String.format("%s gets %s at %.4f/s of %.4f/s: its makers each feed one taker", where(ts.get(j)), g.res, got, demand(ts.get(j), g.res)));
        }
        return id;
    }

    /** How many of a payload a taker takes at once: an assembler its plan's amount, else one. */
    static float batch(Body.Part t, String res){
        if(t.block instanceof mindustry.world.blocks.units.UnitAssembler a){
            for(var plan : a.plans) if(t.recipe != null && t.recipe.out.containsKey(plan.unit.name)) for(var st : plan.requirements) if(st.item.name.equals(res)) return st.amount;
        }
        return 1f;
    }

    // ------------------------------------------------------------------ the whole

    Spec spec(Router router, Power power){
        Spec s = new Spec();
        s.rows.addAll(prob.ground.rows);
        s.seconds = prob.seconds;
        s.places.addAll(prob.fixed);
        //(a piece of a line that stands turned to a router, as it is now)
        Map<Body.Part, Piece> retuned = router.retuned();
        for(Body body : bodies) if(!body.given) for(Body.Part p : body.parts){
            Piece f = retuned.get(p);
            if(f != null) s.places.add(new Spec.Place(f.block.name, f.x, f.y, f.rot, null));
            else s.places.add(new Spec.Place(p.block.name, p.ax(), p.ay(), p.rot, p.block.configurable && !(p.block instanceof mindustry.world.blocks.sandbox.ItemVoid || p.block instanceof mindustry.world.blocks.sandbox.LiquidVoid) ? p.config : null));
        }
        for(Piece q : router.all()) s.places.add(new Spec.Place(q.block.name, q.x, q.y, q.rot, null));
        if(power != null) for(Piece q : power.added) s.places.add(new Spec.Place(q.block.name, q.x, q.y, q.rot, null));
        return s;
    }

    Spec run(long seed){
        balance();
        if(!balance.solved) return null;
        bodies();
        groups();
        return layOut(seed);
    }

    /** What a maker that is no machine gives a second (a module's port, in a layout of modules). */
    final Map<Body.Part, Float> supplyRate = new HashMap<>();
    /** Machines a strip serves, with the things it takes to and from them (Strips): no line of those to or from them. */
    final Map<Body.Part, Set<String>> served = new HashMap<>();
    /** Ports that hand out a thing (a strip's outlet): makers of it. */
    final Map<Body.Part, String> portMakes = new HashMap<>();

    /** The topology: the bodies placed, the lines laid, power; the best of the tries kept (bodies put back where it had them). */
    /**
     * Of the layout kept, the bodies that stand where they may not, the blocks without power (block@x0,y0), and of its
     * lines the tiles two share and the leaks.
     */
    Set<Body> misplacedKept = Set.of();
    List<String> unpoweredKept = List.of();
    int overusedKept, leaksKept;
    /** Whether the bodies stand where they are to stand already (placed by other means): the first try lays lines only. */
    boolean keep;
    /** What a line pays to stand against another machine's side, in tiles of detour (bp.apron; 0 nothing). */
    static final float APRON = Float.parseFloat(System.getProperty("bp.apron", "6"));
    static final boolean ROUTED = System.getProperty("bp.place", "anneal").equals("routed");
    static final boolean GREEDY = System.getProperty("bp.place", "anneal").equals("greedy") || ROUTED;

    Spec layOut(long seed){
        long t0 = System.nanoTime();
        Placer placer = new Placer(board, bodies, groups, seed);
        placer.home = core;
        for(var e : portPlaces.entrySet()) placer.restrict(e.getKey(), e.getValue());
        Spec best = null;
        int bestBad = Integer.MAX_VALUE;
        Map<Body, int[]> bestAt = new HashMap<>();
        float[] history = null;
        List<String> bestNotes = new ArrayList<>();
        for(int attempt = 0; attempt < Integer.getInteger("bp.attempts", 8); attempt++){
            //(the first placing from anywhere; after it, mending: from where things are, cool, so that what was laid well
            //stays and the room asked for is found about the places fought over)
            int steps = keep && attempt == 0 ? 0 : (attempt == 0 ? 4000 : 1500) * Math.max(4, placer.movable.size());
            long tp = System.nanoTime();
            Arrays.fill(board.clear, false);
            //(greedy: the first placing each new body beside what feeds it, room about it, no annealing)
            if(GREEDY && attempt == 0 && !keep && !placer.movable.isEmpty()){
                Districts d = new Districts(this);
                d.room = Integer.getInteger("bp.room", 1);
                d.routed = ROUTED;
                d.run();
                placer.run(0, false, 0.0);
            }else placer.run(steps, attempt == 0 && !keep, attempt == 0 ? 1.0 : 0.05);
            board.putClear(bodies);
            long tr = System.nanoTime();
            System.out.println(String.format("placing %d: cost %.1f (%d bodies, %d steps)", attempt, placer.cost, bodies.size(), steps));
            if(placer.cost >= placer.wHard && Boolean.getBoolean("bp.debug")) System.out.println("  " + placer.explain());
            //(bodies that stand where they may not: on ground they cannot stand on, over another, against what they hand
            //into unmeant)
            Set<Body> misplaced = placer.cost >= placer.wHard ? placer.hardBodies() : Set.of();
            //(the machines' sides kept for their own lines: another's pays there, as a detour of so many tiles)
            board.markAprons(bodies, APRON * (float)weights.cost(carriers.cheapest(Carriers.Role.conduit, weights)));
            List<Router.Net> nets = nets();
            Router router = new Router(board, nets, weights);
            router.carry(history);
            boolean clean = router.run(80);
            long tw = System.nanoTime();
            Power power = new Power(board, bodies, router.all(), prob.freePower, weights).run();
            System.out.println(String.format("  time: placing %.1f s, lines %.1f s, power %.1f s", (tr - tp) / 1e9, (tw - tr) / 1e9, (System.nanoTime() - tw) / 1e9));
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
            if(!power.unpowered.isEmpty()) here.add("without power: " + power.unpowered + (powerMade() ? "" : " (nothing makes power yet)"));
            if(!misplaced.isEmpty()) here.add("standing where they may not: " + misplaced);
            int bad = unreached + router.overused + router.leaks + (powerMade() ? power.unpowered.size() : 0) + misplaced.size();
            System.out.println(String.format("lines: %d, %s after %d rounds (%d tiles shared, %d leaks, %d not reached); power: %d nodes, %d without", nets.size(), clean ? "clean" : "NOT clean", router.rounds, router.overused, router.leaks, unreached, power.added.size(), power.unpowered.size()));
            for(String h : here) System.out.println("    " + h);
            if(bad < bestBad){
                bestBad = bad;
                best = spec(router, power);
                bestNotes = here;
                bestNets = nets;
                bestPower = power.added;
                misplacedKept = misplaced;
                unpoweredKept = powerMade() ? new ArrayList<>(power.unpowered) : List.of();
                overusedKept = router.overused;
                leaksKept = router.leaks;
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
            Set<Body.Part> about = new LinkedHashSet<>();
            for(int i : router.fought){
                int x = i % board.w, y = i / board.w;
                for(int d = 0; d < 4; d++){
                    int nx = x + Geo.dx(d), ny = y + Geo.dy(d);
                    if(board.in(nx, ny) && board.partAt[board.idx(nx, ny)] != null) about.add(board.partAt[board.idx(nx, ny)]);
                }
            }
            for(Body.Part p : about) placer.margin.merge(p, 1, Integer::sum);
            //(the mending moves those most, and the next laying starts from what this one found fought over)
            placer.focus.clear();
            for(Body.Part p : about) placer.focus.add(p.body);
            for(Router.Net n : nets){
                for(Body.Part p : n.unreached){
                    placer.focus.add(p.body);
                    placer.focus.add(n.from.body);
                }
            }
            history = router.history();
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
        int bad = gen.verify(s, true);
        System.out.println("  verified: " + (bad == 0 ? "clean by the game's rules" : bad + " things wrong by the game's rules"));
        System.out.println(drawn(s));
        System.exit(0);
    }

    /**
     * The layout put down in the game's world and checked by its rules (Check): lines that stop, liquids mixed, blocks
     * without power or short of heat, and every line traced against what it was laid to reach. What is wrong, counted.
     */
    /** Whether anything laid out makes power (else nothing can be powered yet: a base still growing). */
    boolean powerMade(){
        for(Body b : bodies) for(Body.Part p : b.parts) if(Power.makes(p.block)) return true;
        return false;
    }

    int verify(Spec s, boolean print){
        return verify(s, print, true);
    }

    /** What is wrong by the game's rules: the check's own, and (lines) each line laid that does not reach as laid. */
    int verify(Spec s, boolean print, boolean lines){
        Check check = new Check(new Scratch(s)).run();
        if(print) System.out.print(check.report());
        int wrong = 0;
        if(lines) for(Router.Net n : bestNets){
            Set<String> got = check.reach.getOrDefault((n.cls == 3 ? "payload" : n.res) + " from " + where(n.from), Set.of());
            //(a line from a carrier, a module's port: traced on from it)
            if(n.from.recipe == null && n.from.config != null && n.cls != 3 && Board.isPortCarrier(n.from)){
                mindustry.gen.Building c = Vars.world.build(n.from.ax(), n.from.ay());
                if(c != null) got = Vars.content.item(n.res) != null ? check.traceItem(c, Vars.content.item(n.res)) : check.traceLiquid(c, Vars.content.liquid(n.res));
            }
            //(a taker no line was laid to is the router's to tell, not a line laid that goes wrong)
            for(Body.Part t : n.to) if(!n.unreached.contains(t) && !got.contains(where(t))){
                wrong++;
                if(print) System.out.println("  NOT AS LAID: " + n.res + " from " + where(n.from) + " does not reach " + where(t) + " (it reaches " + got + ")");
            }
        }
        if(print && wrong > 0) for(String d : differ(bestNets)) System.out.println("  DIFFERS: " + d);
        return wrong + check.jams + check.mixed + (powerMade() ? check.unpowered : 0) + check.cold + check.misplaced;
    }

    /**
     * Where the game's rules part from the router's for the lines laid (the world as last loaded, the layout's): each
     * piece asked what the game hands its thing to, against what the router meant it to hand to.
     */
    static List<String> differ(List<Router.Net> nets){
        List<String> out = new ArrayList<>();
        for(Router.Net n : nets){
            if(n.cls == 3) continue;
            boolean liquid = Vars.content.liquid(n.res) != null;
            for(int i = 0; i < n.pieces.size(); i++){
                Piece q = n.pieces.get(i);
                if(q.block == null || q.kind == Kind.bridgeIn || q.kind == Kind.lbridgeIn) continue;
                mindustry.gen.Building c = Vars.world.build(q.x, q.y);
                if(c == null || c.block != q.block){
                    out.add(n.res + ": " + q + " is not there (" + (c == null ? "nothing" : c.block.name) + ")");
                    continue;
                }
                List<mindustry.gen.Building> to = liquid ? Check.liquidTo(c, Vars.content.liquid(n.res)) : Check.handsTo(c, Vars.content.item(n.res));
                for(int d = 0; d < 4; d++){
                    if((q.outs & (1 << d)) == 0) continue;
                    mindustry.gen.Building meant = Vars.world.build(q.x + Geo.dx(d), q.y + Geo.dy(d));
                    if(meant != null && !to.contains(meant)){
                        List<String> got = new ArrayList<>();
                        for(var o : to) got.add(Scratch.where(o));
                        out.add(n.res + " (line " + n.id + "): " + q + " was to hand " + d + " to " + Scratch.where(meant) + ", the game hands to " + got);
                    }
                }
            }
        }
        return out;
    }

    static String where(Body.Part p){
        return p.block.name + "@" + p.ax() + "," + p.ay();
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
