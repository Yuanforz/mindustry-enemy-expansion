package bp;

import com.google.ortools.Loader;
import com.google.ortools.linearsolver.*;
import mindustry.*;

import java.util.*;

/**
 * The exact balance: how many of each machine, and how much of each it works, so that what is asked for comes out at the
 * rates asked for, every item, liquid, unit, power and heat taken in is made or supplied, and what is made but not used
 * goes somewhere (the core takes any item; a liquid a machine can't get rid of stops it, unless it makes another it can,
 * as the game's crafters with two liquid outputs go on; an item made beyond what is taken backs up and its maker works
 * less, which is no harm but for a maker of two things, which works only as fast as both are taken). A mixed-integer program (SCIP): machines are whole, their work a
 * share of what they can do at full speed (a machine fed less, or blocked, works less).
 *
 * The objective: the least ground the machines stand on (space is what the layouts are short of), then the fewest.
 */
public final class Balance{
    /** What is asked for: a resource and the rate (a second) it must come out at (into the core for items). */
    public final LinkedHashMap<String, Float> goals = new LinkedHashMap<>();
    /** What is supplied (the sandbox's sources, or what the map gives): resources by name; "power" for a power source. */
    public final Set<String> sources = new HashSet<>();
    /** The most of a supplied resource there is, a second (absent: no limit). */
    public final Map<String, Float> supplyCap = new HashMap<>();
    /** Hard caps: the ground the machines may stand on (tiles), the items they may cost (null: no cap). */
    public Float groundCap, itemCap;
    /** The recipes that may be used. */
    public final List<Recipe> recipes = new ArrayList<>();
    /** Blocks that must be at work, at least so much in all (machines at full speed), by block name, whatever their recipe. */
    public final Map<String, Float> runAtLeast = new LinkedHashMap<>();
    /** The power to have to spare at the least, a second. */
    public float powerSurplus = 0f;
    /**
     * For each place, the tiles it stands on and the tiles its beams pass over (by recipe name, as places): no two places
     * chosen stand on one tile, nor one on another's beam (Sites).
     */
    public final Map<String, List<int[][]>> placeTiles = new HashMap<>();
    /** How many machines a place may hold (by recipe name, as places; absent: one): a kind of ground to be made, as many as wanted. */
    public final Map<String, List<Integer>> placeMax = new HashMap<>();
    /** What using a place costs besides its machine (by recipe name, as places): a place that is not there yet, made. */
    public final Map<String, List<Float>> placeCost = new HashMap<>();
    /** The place chosen for each machine on ground, as the result has them (recipe name and place index). */
    final List<Object[]> placeVars = new ArrayList<>();
    final Map<MPVariable, Float> extraCost = new HashMap<>();
    /** Places for machines on ground, each with its yield, by recipe name (a recipe on ground with no place is not used). */
    public final Map<String, List<Float>> places = new HashMap<>();

    public static final class Result{
        public boolean solved;
        public String status;
        /** Machines: recipe, how many, how much they work in all (in machines at full speed), and its yield for ground ones. */
        public final List<Object[]> machines = new ArrayList<>();
        public final LinkedHashMap<String, Float> drawn = new LinkedHashMap<>(), delivered = new LinkedHashMap<>(), spilt = new LinkedHashMap<>();
        /** The places taken by machines on ground: recipe name and the place's index among that recipe's places. */
        public final List<Object[]> placesTaken = new ArrayList<>();
        /** Elastic: what fell short of what was asked, by goal (and "run block"). */
        public final LinkedHashMap<String, Float> unmet = new LinkedHashMap<>();
        public float powerMade, powerUsed, heatMade, heatNeeded, area;
        /** Seconds the game takes to build them all, at a build speed of one. */
        public float buildSeconds;
        /** What the machines cost to build, all together, by item. */
        public final LinkedHashMap<String, Float> cost = new LinkedHashMap<>();
        /** What more could be had a second: what the makers would make at full beyond what is taken, by thing, and power. */
        public final LinkedHashMap<String, Float> spare = new LinkedHashMap<>();

        @Override
        public String toString(){
            StringBuilder s = new StringBuilder("balance " + status + (solved ? String.format(": area %.0f tiles", area) : "") + "\n");
            for(Object[] m : machines) s.append(String.format("  %d x %s%s%s%n", (Integer)m[1], ((Recipe)m[0]).name, m[3] == null ? "" : String.format(" (yield %.1f)", (Float)m[3]),
                m[4] != null ? ", " + m[4] : String.format(", working %.3f", (Float)m[2])));
            if(!drawn.isEmpty()) s.append("  supplied: ").append(Recipe.fmt(drawn)).append('\n');
            if(!delivered.isEmpty()) s.append("  to the core: ").append(Recipe.fmt(delivered)).append('\n');
            if(!spilt.isEmpty()) s.append("  liquid let go (a second output its maker goes on past): ").append(Recipe.fmt(spilt)).append('\n');
            if(!spare.isEmpty()) s.append("  to spare, to be had besides (makers at full beyond what is taken): ").append(Recipe.fmt(spare)).append('\n');
            if(!unmet.isEmpty()) s.append("  FELL SHORT: ").append(Recipe.fmt(unmet)).append('\n');
            s.append(String.format("  power made %.1f used %.1f; heat made %.1f needed %.1f%n", powerMade, powerUsed, heatMade, heatNeeded));
            float all = 0f;
            for(float c : cost.values()) all += c;
            s.append(String.format("  cost to build %.0f items (%s); %.0f s to build at speed 1", all, Recipe.fmt(cost), buildSeconds));
            return s.toString();
        }
    }

    static boolean loaded;

    /**
     * When set, the goals are not rates to be met but what is to be had as much of as can be in this much ground (tiles
     * under machines): the most of the goals (each as a share of its rate) the ground allows. Swept over the ground, it
     * gives what each more tile buys (Balance.front): a refabricator fed by one fabricator works at 86% on 27 tiles, by two
     * at 100% on 36, and which is worth having is the layout's question, not the balance's.
     */
    public Float areaCap;
    /** What a tile of ground, an item of cost and a second of building weigh against one another in the objective. */
    public float perTile = 1f, perItem = 0f, perSecond = 0f;

    /** What of a thing the goals ask for at least. */
    float goalsMet(String k){
        return goals.getOrDefault(k, 0f);
    }

    /** A goal's rate, a thousandth under as written (0.0286 a second asked of a yard that makes one in 35 s, 0.02857, had two put down). */
    double goal(String k){
        return areaCap != null ? 0.0 : goals.getOrDefault(k, 0f) * (1.0 - 1e-3);
    }

    static boolean isItem(String name){
        return Vars.content.item(name) != null;
    }

    /** A block made as a payload (its name; a machine at work is "~" and its name, a turret's way has a colon). */
    static boolean isBlockPayload(String name){
        return !name.startsWith("~") && Vars.content.block(name) != null;
    }

    static boolean isLiquid(String name){
        return Vars.content.liquid(name) != null;
    }

    /** Every way of filling a face of this length with what can stand against it (counts of each kind), not empty. */
    static void pack(List<int[]> kinds, List<mindustry.world.Block> conds, int from, int left, int[] now, List<int[]> out){
        boolean any = false;
        for(int c : now) if(c > 0) any = true;
        if(any) out.add(now.clone());
        for(int k = from; k < kinds.size(); k++){
            int[] kind = kinds.get(k);
            int size = kind[0] == 0 ? kind[1] : conds.get(kind[1]).size;
            if(size > left) continue;
            now[k]++;
            pack(kinds, conds, k, left - size, now, out);
            now[k]--;
        }
    }

    /** The constraint that the redirectors of a kind at a level are those put against the faces of the level before. */
    final Map<String, MPConstraint> linkSeen = new HashMap<>();

    MPConstraint links(MPSolver solver, Recipe r, int level, int kind, MPVariable[][] placed){
        String key = r.name + "_" + level + "_" + kind;
        MPConstraint c = linkSeen.get(key);
        if(c == null){
            c = solver.makeConstraint(0, 0, "link_" + key);
            c.setCoefficient(placed[level][kind], -1);
            linkSeen.put(key, c);
        }
        return c;
    }

    /**
     * Elastic: what is asked for may fall short, at a great price a unit (the goals and the blocks to be at work): the most
     * that can be had, and what fell short is told (Result.unmet).
     */
    public boolean elastic;
    public float shortPrice = 1e5f;
    /** How long the solver may think, in seconds. */
    public float seconds = Float.parseFloat(System.getProperty("bp.balanceSeconds", "60"));
    MPObjective objective;
    final Map<String, MPVariable> shorts = new LinkedHashMap<>();

    /** The variable of what goes to a goal: at least the goal, or, elastic, anything, its shortfall dear. */
    MPVariable goalVar(MPSolver solver, String k){
        double g = goal(k);
        if(!elastic || g <= 0) return solver.makeNumVar(g, MPSolver.infinity(), "d_" + k);
        MPVariable d = solver.makeNumVar(0, MPSolver.infinity(), "d_" + k), sh = solver.makeNumVar(0, g, "short_" + k);
        MPConstraint c = solver.makeConstraint(g, MPSolver.infinity());
        c.setCoefficient(d, 1);
        c.setCoefficient(sh, 1);
        shorts.put(k, sh);
        return d;
    }

    public Result solve(){
        shorts.clear();
        placeVars.clear();
        extraCost.clear();
        linkSeen.clear();
        if(!loaded){
            Loader.loadNativeLibraries();
            loaded = true;
        }
        MPSolver solver = MPSolver.createSolver("SCIP");
        double inf = MPSolver.infinity();
        //the machines: for a block on no ground, a whole count and its work; for one on ground, one per place (taken or not)
        List<Object[]> vars = new ArrayList<>();
        for(Recipe r : recipes){
            if(r.kind == Recipe.Kind.source) continue;
            if(r.ground == Recipe.Ground.none){
                MPVariable n = solver.makeIntVar(0, 64, "n_" + r.name), a = solver.makeNumVar(0, inf, "a_" + r.name);
                MPConstraint c = solver.makeConstraint(-inf, 0);
                c.setCoefficient(a, 1);
                c.setCoefficient(n, -1);
                vars.add(new Object[]{r, n, a, null, r.at(1f), null});
            }else{
                List<Float> ps = places.getOrDefault(r.name, List.of());
                List<int[][]> tiles = placeTiles.get(r.name);
                for(int i = 0; i < ps.size(); i++){
                    float y = ps.get(i);
                    List<Integer> mx = placeMax.get(r.name);
                    MPVariable n = solver.makeIntVar(0, mx != null && i < mx.size() ? mx.get(i) : 1, "n_" + r.name + "_" + i), a = solver.makeNumVar(0, inf, "a_" + r.name + "_" + i);
                    MPConstraint c = solver.makeConstraint(-inf, 0);
                    c.setCoefficient(a, 1);
                    c.setCoefficient(n, -1);
                    vars.add(new Object[]{r, n, a, y, r.at(y), null});
                    if(tiles != null && i < tiles.size()) placeVars.add(new Object[]{n, tiles.get(i), r.name, i});
                    else placeVars.add(new Object[]{n, new int[][]{new int[0], new int[0]}, r.name, i});
                    List<Float> pc = placeCost.get(r.name);
                    if(pc != null && i < pc.size() && pc.get(i) > 0f) extraCost.put(n, pc.get(i));
                }
            }
        }
        //places on ground: one machine to a tile, and none on another's beam
        if(!placeVars.isEmpty()){
            Map<Integer, List<MPVariable>> onTile = new HashMap<>();
            Map<Integer, List<MPVariable>> beamOver = new HashMap<>();
            for(Object[] pv : placeVars){
                int[][] t = (int[][])pv[1];
                for(int i : t[0]) onTile.computeIfAbsent(i, k -> new ArrayList<>()).add((MPVariable)pv[0]);
                for(int i : t[1]) beamOver.computeIfAbsent(i, k -> new ArrayList<>()).add((MPVariable)pv[0]);
            }
            for(var e : onTile.entrySet()){
                List<MPVariable> on = e.getValue();
                if(on.size() > 1){
                    MPConstraint c = solver.makeConstraint(-inf, 1);
                    for(MPVariable v : on) c.setCoefficient(v, 1);
                }
                for(MPVariable beam : beamOver.getOrDefault(e.getKey(), List.of())){
                    MPConstraint c = solver.makeConstraint(-inf, 1);
                    c.setCoefficient(beam, 1);
                    for(MPVariable v : on) if(v != beam) c.setCoefficient(v, 1);
                }
            }
        }
        //heat: each machine that needs it has heaters of its own (heat goes from a heater only into the block its face
        //touches, Building.calculateHeat), of any kinds mixed, against its sides or against redirectors that gather it on
        //(HeatConductor, passing all on), whole faces only. Packed face by face: a machine has four faces of its size, a
        //redirector three of its own; what is put against a face (heaters, redirectors) is no wider together than the face
        //(a 5x5's side takes two 2x2 heaters, or a 3x3 redirector and a 2x2 heater). Redirectors by level, each against a
        //face of the level before (no ring of redirectors gathering for one another: a ring passes no heat on, the game
        //ignores its heat). Heaters burn as long as they stand; what they give at least what the machine's work needs.
        List<mindustry.world.Block> conductors = Recipes.conductors();
        List<Recipe> heaters = new ArrayList<>();
        for(Recipe h : recipes) if(h.heatOut > 0f && h.heatNeed <= 0f && h.ground == Recipe.Ground.none && (!h.name.equals("heat-source") || sources.contains("heat"))) heaters.add(h);
        int levels = 3;
        for(Object[] v : new ArrayList<>(vars)){
            Recipe r = (Recipe)v[0];
            if(r.heatNeed <= 0f) continue;
            MPVariable n = (MPVariable)v[1], a = (MPVariable)v[2];
            int s = r.size;
            List<mindustry.world.Block> conds = new ArrayList<>();
            for(mindustry.world.Block c : conductors) if(c.size <= s) conds.add(c);
            //(what can stand against a face: a heater of each size there is, a redirector of each kind)
            TreeSet<Integer> heaterSizes = new TreeSet<>();
            for(Recipe h : heaters) if(h.size <= s) heaterSizes.add(h.size);
            List<int[]> kinds = new ArrayList<>();
            for(int hs : heaterSizes) kinds.add(new int[]{0, hs});
            for(int i = 0; i < conds.size(); i++) kinds.add(new int[]{1, i});
            //heaters of each size, all levels together; redirectors of each kind at each level
            Map<Integer, MPConstraint> heaterRoom = new HashMap<>();
            for(int hs : heaterSizes) heaterRoom.put(hs, solver.makeConstraint(-inf, 0, "hroom_" + r.name + "_" + hs));
            MPVariable[][] placed = new MPVariable[levels + 1][conds.size()];
            for(int l = 1; l <= levels; l++) for(int i = 0; i < conds.size(); i++) placed[l][i] = solver.makeIntVar(0, 64, "y_" + r.name + "_" + l + "_" + conds.get(i).name);
            for(int l = 0; l < levels; l++){
                //the faces of this level: the machine's own, or those of the redirectors placed at this level
                List<Integer> lengths = new ArrayList<>();
                if(l == 0) lengths.add(s);
                else for(mindustry.world.Block c : conds) if(!lengths.contains(c.size)) lengths.add(c.size);
                for(int len : lengths){
                    MPConstraint faces = solver.makeConstraint(-inf, 0, "faces_" + r.name + "_" + l + "_" + len);
                    if(l == 0) faces.setCoefficient(n, -4.0);
                    else for(int i = 0; i < conds.size(); i++) if(conds.get(i).size == len) faces.setCoefficient(placed[l][i], -3.0);
                    //the ways of filling a face of this length
                    List<int[]> patterns = new ArrayList<>();
                    pack(kinds, conds, 0, len, new int[kinds.size()], patterns);
                    for(int[] p : patterns){
                        MPVariable z = solver.makeIntVar(0, 256, "z_" + r.name + "_" + l + "_" + len + "_" + Arrays.toString(p));
                        faces.setCoefficient(z, 1);
                        for(int k = 0; k < kinds.size(); k++){
                            if(p[k] == 0) continue;
                            int[] kind = kinds.get(k);
                            if(kind[0] == 0) heaterRoom.get(kind[1]).setCoefficient(z, -p[k]);
                            else if(l + 1 <= levels){
                                //(a redirector put against this face is one of the next level)
                                MPConstraint link = links(solver, r, l + 1, kind[1], placed);
                                link.setCoefficient(z, p[k]);
                            }
                        }
                    }
                }
            }
            //(the whole need of every machine, at full speed, not of its work: short of heat it runs slower, the waste the
            //user's rule leaves out)
            MPConstraint got = solver.makeConstraint(0, inf, "heat_" + r.name);
            got.setCoefficient(n, -r.heatNeed);
            for(Recipe h : heaters){
                if(h.size > s) continue;
                MPVariable x = solver.makeIntVar(0, 64, "x_" + r.name + "_" + h.name), ax = solver.makeNumVar(0, inf, "ax_" + r.name + "_" + h.name);
                MPConstraint full = solver.makeConstraint(0, 0);
                full.setCoefficient(ax, 1);
                full.setCoefficient(x, -1);
                vars.add(new Object[]{h, x, ax, null, h.at(1f), "heating " + r.name});
                got.setCoefficient(x, h.heatOut);
                heaterRoom.get(h.size).setCoefficient(x, 1);
            }
            for(int i = 0; i < conds.size(); i++){
                mindustry.world.Block c = conds.get(i);
                Recipe cr = new Recipe(c.name, c, Recipe.Kind.heater, Recipe.Ground.none, null);
                MPVariable y = solver.makeIntVar(0, 256, "yall_" + r.name + "_" + c.name), ay = solver.makeNumVar(0, 0, "ay_" + r.name + "_" + c.name);
                MPConstraint sum = solver.makeConstraint(0, 0);
                sum.setCoefficient(y, 1);
                for(int l = 1; l <= levels; l++) sum.setCoefficient(placed[l][i], -1);
                vars.add(new Object[]{cr, y, ay, null, cr.at(1f), "gathering heat for " + r.name});
            }
        }
        //every resource that comes up
        Set<String> res = new LinkedHashSet<>(goals.keySet());
        for(Object[] v : vars){
            Recipe.Rates rt = (Recipe.Rates)v[4];
            res.addAll(rt.in.keySet());
            res.addAll(rt.out.keySet());
            res.addAll(((Recipe)v[0]).fixedIn.keySet());
            res.addAll(((Recipe)v[0]).fixedOut.keySet());
        }
        Map<String, MPVariable> drawn = new HashMap<>(), delivered = new HashMap<>(), spilt = new HashMap<>();
        for(String k : res){
            //made + supplied = taken + delivered (+ let go)
            MPConstraint c = solver.makeConstraint(0, 0, "bal_" + k);
            for(Object[] v : vars){
                Recipe.Rates rt = (Recipe.Rates)v[4];
                float net = rt.out.getOrDefault(k, 0f) - rt.in.getOrDefault(k, 0f);
                if(net != 0f) c.setCoefficient((MPVariable)v[2], net);
                //(what it takes for standing there, per machine)
                float fixed = ((Recipe)v[0]).fixedOut.getOrDefault(k, 0f) - ((Recipe)v[0]).fixedIn.getOrDefault(k, 0f);
                if(fixed != 0f) c.setCoefficient((MPVariable)v[1], fixed);
            }
            if(sources.contains(k)){
                MPVariable s = solver.makeNumVar(0, supplyCap.containsKey(k) ? supplyCap.get(k) : inf, "s_" + k);
                drawn.put(k, s);
                c.setCoefficient(s, 1);
            }
            if(isItem(k)){
                //(what is asked for goes to the core at its rate at least; what is not asked for may go to the core too,
                //which takes every item (and burns what it has no room for, Rules.coreIncinerates), or nowhere: made
                //beyond what is taken it backs up in its line and its maker waits. A maker that makes two things works
                //only as fast as both are taken, or both go to the core)
                MPVariable d = goalVar(solver, k);
                delivered.put(k, d);
                c.setCoefficient(d, -1);
            }else if(isLiquid(k)){
                if(goals.containsKey(k)){
                    MPVariable d = goalVar(solver, k);
                    delivered.put(k, d);
                    c.setCoefficient(d, -1);
                }
                //(a liquid let go: only a second output of a maker that goes on past it, see below)
                MPVariable sp = solver.makeNumVar(0, inf, "spill_" + k);
                spilt.put(k, sp);
                c.setCoefficient(sp, -1);
            }else if(isBlockPayload(k)){
                //(a block a constructor makes: something must take it, an assembler or a deconstructor, or the
                //constructor holds it and stops; only a goal goes out of the plan)
                if(goals.containsKey(k)){
                    MPVariable d = goalVar(solver, k);
                    delivered.put(k, d);
                    c.setCoefficient(d, -1);
                }
            }else{
                //units, and a turret or a generator at work (what such a way "makes"): what is asked for comes out, and the
                //rest may too (units walk out of their yard; a turret firing needs nothing to take it)
                MPVariable d = goalVar(solver, k);
                delivered.put(k, d);
                c.setCoefficient(d, -1);
            }
        }
        //a liquid let go only as much as makers of it that go on when it is full make (a crafter with two liquid outputs);
        //what a maker stops for when full must all be taken
        for(var e : spilt.entrySet()){
            MPConstraint c = solver.makeConstraint(-inf, 0, "spillcap_" + e.getKey());
            c.setCoefficient(e.getValue(), 1);
            for(Object[] v : vars){
                Recipe r = (Recipe)v[0];
                Recipe.Rates rt = (Recipe.Rates)v[4];
                float made = rt.out.getOrDefault(e.getKey(), 0f);
                if(made > 0f && !r.stopsWhenFull.contains(e.getKey())) c.setCoefficient((MPVariable)v[2], -made);
                float fixed = r.fixedOut.getOrDefault(e.getKey(), 0f);
                if(fixed > 0f) c.setCoefficient((MPVariable)v[1], -fixed);
            }
        }
        //power: made (or supplied) at least what is used
        MPConstraint power = solver.makeConstraint(powerSurplus, inf, "power");
        if(elastic && powerSurplus > 0f){
            //(power to spare short, a unit of it priced as a unit of any other goal over its own size)
            MPVariable sh = solver.makeNumVar(0, powerSurplus, "short_power");
            power.setCoefficient(sh, 1);
            shorts.put("power to spare", sh);
        }
        //(blocks to be at work: the work of all their machines together at least so much)
        for(var e : runAtLeast.entrySet()){
            MPConstraint c = solver.makeConstraint(e.getValue(), inf, "run_" + e.getKey());
            for(Object[] v : vars) if(((Recipe)v[0]).block.name.equals(e.getKey())) c.setCoefficient((MPVariable)v[2], 1);
            if(elastic){
                MPVariable sh = solver.makeNumVar(0, e.getValue(), "short_run_" + e.getKey());
                c.setCoefficient(sh, 1);
                shorts.put("run " + e.getKey(), sh);
            }
        }
        MPVariable powerSource = sources.contains("power") ? solver.makeNumVar(0, inf, "s_power") : null;
        if(powerSource != null) power.setCoefficient(powerSource, 1);
        MPObjective obj = solver.objective();
        for(Object[] v : vars){
            Recipe r = (Recipe)v[0];
            Recipe.Rates rt = (Recipe.Rates)v[4];
            MPVariable a = (MPVariable)v[2], n = (MPVariable)v[1];
            power.setCoefficient(a, rt.powerOut - rt.powerUse);
            if(r.fixedPowerUse > 0f || r.fixedPowerOut > 0f) power.setCoefficient(n, r.fixedPowerOut - r.fixedPowerUse);
            //(the ground it stands on, what it costs, the time it takes to build, as weighed; one machine more costs a
            //little besides)
            obj.setCoefficient(n, perTile * r.area() + perItem * r.cost() + perSecond * r.buildSeconds() + 0.01);
            //(and working it a little, so that nothing works for nothing)
            obj.setCoefficient(a, 1e-4);
        }
        for(MPVariable s : drawn.values()) obj.setCoefficient(s, 1e-5);
        //(the hard caps on ground and on cost)
        if(groundCap != null || itemCap != null){
            MPConstraint g = groundCap == null ? null : solver.makeConstraint(-inf, groundCap, "groundcap");
            MPConstraint it = itemCap == null ? null : solver.makeConstraint(-inf, itemCap, "itemcap");
            for(Object[] v : vars){
                Recipe r = (Recipe)v[0];
                if(g != null) g.setCoefficient((MPVariable)v[1], r.area());
                if(it != null) it.setCoefficient((MPVariable)v[1], r.cost());
            }
        }
        if(areaCap != null){
            //(the most of the goals in so much ground: each goal's rate counted as a share of what is asked, the ground
            //and the rest as small costs that break ties)
            MPConstraint ground = solver.makeConstraint(-inf, areaCap, "area");
            for(Object[] v : vars){
                Recipe r = (Recipe)v[0];
                ground.setCoefficient((MPVariable)v[1], r.area());
                obj.setCoefficient((MPVariable)v[1], (r.area() + 0.01) * 1e-4);
            }
            for(var e : goals.entrySet()) if(delivered.containsKey(e.getKey())) obj.setCoefficient(delivered.get(e.getKey()), -1.0 / Math.max(1e-9, e.getValue()));
        }
        for(var e : shorts.entrySet()) obj.setCoefficient(e.getValue(), e.getKey().equals("power to spare") ? shortPrice / Math.max(1f, powerSurplus) : shortPrice);
        for(var e : extraCost.entrySet()) obj.setCoefficient(e.getKey(), obj.getCoefficient(e.getKey()) + e.getValue());
        obj.setMinimization();
        //(a time to think in: the best found by then, if the proof of the best takes longer)
        solver.setTimeLimit((long)(seconds * 1000));
        MPSolver.ResultStatus status = solver.solve();
        Result out = new Result();
        out.status = status.toString().toLowerCase();
        out.solved = status == MPSolver.ResultStatus.OPTIMAL || status == MPSolver.ResultStatus.FEASIBLE;
        if(!out.solved) return out;
        for(Object[] v : vars){
            MPVariable n = (MPVariable)v[1], a = (MPVariable)v[2];
            int count = (int)Math.round(n.solutionValue());
            if(count <= 0) continue;
            Recipe r = (Recipe)v[0];
            Recipe.Rates rt = (Recipe.Rates)v[4];
            out.machines.add(new Object[]{r, count, (float)a.solutionValue(), v[3], v[5]});
            for(var part : r.parts.entrySet()) for(var st : part.getKey().requirements) out.cost.merge(st.item.name, (float)st.amount * count * part.getValue(), Float::sum);
            out.buildSeconds += r.buildSeconds() * count;
            out.area += count * r.area();
            out.powerMade += a.solutionValue() * rt.powerOut + count * r.fixedPowerOut;
            out.powerUsed += a.solutionValue() * rt.powerUse + count * r.fixedPowerUse;
            out.heatMade += a.solutionValue() * rt.heatOut;
            out.heatNeeded += a.solutionValue() * rt.heatNeed;
        }
        for(var e : drawn.entrySet()) if(e.getValue().solutionValue() > 1e-6) out.drawn.put(e.getKey(), (float)e.getValue().solutionValue());
        for(var e : shorts.entrySet()) if(e.getValue().solutionValue() > 1e-4) out.unmet.put(e.getKey(), (float)e.getValue().solutionValue());
        for(Object[] pv : placeVars) if(((MPVariable)pv[0]).solutionValue() > 0.5) out.placesTaken.add(new Object[]{pv[2], pv[3], (int)Math.round(((MPVariable)pv[0]).solutionValue())});
        if(powerSource != null && powerSource.solutionValue() > 1e-6) out.drawn.put("power", (float)powerSource.solutionValue());
        for(var e : delivered.entrySet()) if(e.getValue().solutionValue() > 1e-6) out.delivered.put(e.getKey(), (float)e.getValue().solutionValue());
        for(var e : spilt.entrySet()) if(e.getValue().solutionValue() > 1e-6) out.spilt.put(e.getKey(), (float)e.getValue().solutionValue());
        //what more there is to be had: each thing made, what its makers would make at full less what is taken (asked for
        //and used); the power to spare; what is let go
        LinkedHashMap<String, Float> full = new LinkedHashMap<>(), taken = new LinkedHashMap<>();
        for(Object[] v : vars){
            int count = (int)Math.round(((MPVariable)v[1]).solutionValue());
            if(count <= 0) continue;
            Recipe r = (Recipe)v[0];
            Recipe.Rates rt = (Recipe.Rates)v[4];
            float work = (float)((MPVariable)v[2]).solutionValue();
            for(var e : rt.out.entrySet()) full.merge(e.getKey(), e.getValue() * count, Float::sum);
            for(var e : r.fixedOut.entrySet()) full.merge(e.getKey(), e.getValue() * count, Float::sum);
            for(var e : rt.in.entrySet()) taken.merge(e.getKey(), e.getValue() * work, Float::sum);
            for(var e : r.fixedIn.entrySet()) taken.merge(e.getKey(), e.getValue() * count, Float::sum);
        }
        for(var e : full.entrySet()){
            if(!isItem(e.getKey()) && !isLiquid(e.getKey())) continue;
            float spare = e.getValue() - taken.getOrDefault(e.getKey(), 0f) - goalsMet(e.getKey());
            if(spare > 1e-3f) out.spare.put(e.getKey(), spare);
        }
        out.spare.put("power", out.powerMade + out.drawn.getOrDefault("power", 0f) - out.powerUsed);
        return out;
    }

    /**
     * Each more tile of ground and what it buys: the most of the goals for every cap of ground from nothing up to this
     * much, each time the answer changes ({area, share of the goals' rates, the result}).
     */
    public List<Object[]> front(float most){
        List<Object[]> out = new ArrayList<>();
        float lastShare = -1f;
        for(float cap = 1f; cap <= most; cap += 1f){
            areaCap = cap;
            Result r = solve();
            if(!r.solved) continue;
            float share = Float.MAX_VALUE;
            for(var e : goals.entrySet()) share = Math.min(share, r.delivered.getOrDefault(e.getKey(), 0f) / e.getValue());
            if(share > lastShare + 1e-4f){
                out.add(new Object[]{r.area, share, r});
                lastShare = share;
            }
        }
        areaCap = null;
        return out;
    }

    /**
     * A goal with * in it is any of the ways it matches, as many of them in all: "scathe:*+coolant", a scathe with coolant
     * on whatever ammunition. Each recipe that matches makes the goal too.
     */
    public void expandWildcards(){
        for(String g : new ArrayList<>(goals.keySet())) if(g.contains("*")){
            String re = g.replace("+", "\\+").replace("*", ".*");
            for(Recipe r : recipes) for(var e : new ArrayList<>(r.out.entrySet())) if(e.getKey().matches(re)) r.out.put(g, e.getValue());
        }
    }

    /** Usage: bp.Balance data-dir goal:rate[,goal:rate] sources (comma separated; "power", "heat" too) [front:most-tiles] */
    public static void main(String[] args){
        Game.boot(args[0]);
        Balance b = new Balance();
        for(String g : args[1].split(",")){
            int at = g.lastIndexOf(':');
            b.goals.put(g.substring(0, at), Float.parseFloat(g.substring(at + 1)));
        }
        //(sources, each with its limit if it has one: thorium=3)
        if(args.length > 2) for(String src : args[2].split(",")){
            String[] p = src.split("=");
            b.sources.add(p[0]);
            if(p.length > 1) b.supplyCap.put(p[0], Float.parseFloat(p[1]));
        }
        for(int i = 3; i < args.length; i++){
            if(args[i].startsWith("ground:")) b.groundCap = Float.parseFloat(args[i].substring(7));
            if(args[i].startsWith("items:")) b.itemCap = Float.parseFloat(args[i].substring(6));
        }
        //(weights:tile=1,item=0.01,second=0)
        for(int i = 3; i < args.length; i++) if(args[i].startsWith("weights:")){
            for(String w : args[i].substring(8).split(",")){
                String[] p = w.split("=");
                float v = Float.parseFloat(p[1]);
                if(p[0].equals("tile")) b.perTile = v;
                else if(p[0].equals("item")) b.perItem = v;
                else if(p[0].equals("second")) b.perSecond = v;
            }
        }
        if(Arrays.asList(args).contains("ways")){
            //(the ways of heating each machine that needs heat, as the balance has them to choose from)
            for(Recipe r : Recipes.all()) if(r.heatNeed > 0f && b.goals.containsKey(r.name)) for(Recipe h : Recipes.all()) if(h.kind == Recipe.Kind.heater && !h.name.equals("heat-source"))
                for(Recipe c : Recipes.heated(r, h, Recipes.conductors())) System.out.println(String.format("    way: %s: %d tiles, %d items, %.0f s to build, works at %.0f%%, burns %s", c.name, c.area(), c.cost(), c.buildSeconds(), c.out.getOrDefault(r.name, 0f) * 100f, Recipe.fmt(c.fixedIn)));
        }
        //(places for machines on ground: at:recipe=yield/yield/...)
        for(int i = 3; i < args.length; i++) if(args[i].startsWith("at:")){
            String[] p = args[i].substring(3).split("=");
            List<Float> ys = new ArrayList<>();
            for(String y : p[1].split("/")) ys.add(Float.parseFloat(y));
            b.places.put(p[0], ys);
        }
        for(Recipe r : Recipes.all()) if(r.kind != Recipe.Kind.source && !r.name.equals("heat-source")) b.recipes.add(r);
        b.expandWildcards();
        long t0 = System.nanoTime();
        String frontArg = null;
        for(int i = 3; i < args.length; i++) if(args[i].startsWith("front:")) frontArg = args[i];
        if(frontArg != null){
            for(Object[] f : b.front(Float.parseFloat(frontArg.substring(6)))){
                Result fr = (Result)f[2];
                StringBuilder m = new StringBuilder();
                for(Object[] mm : fr.machines) m.append(String.format(" %dx%s", (Integer)mm[1], ((Recipe)mm[0]).name));
                System.out.println(String.format("  %3.0f tiles: %5.1f%% of the rate asked (%.3f per tile per 100%%),%s", (Float)f[0], (Float)f[1] * 100f, (Float)f[1] / (Float)f[0], m));
            }
            System.out.println(String.format("  front in %.0f ms", (System.nanoTime() - t0) / 1e6));
            System.exit(0);
        }
        Result r = b.solve();
        System.out.println(r);
        System.out.println(String.format("  solved in %.0f ms", (System.nanoTime() - t0) / 1e6));
        System.exit(0);
    }
}
