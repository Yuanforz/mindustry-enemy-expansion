package bp;

import com.google.ortools.Loader;
import com.google.ortools.linearsolver.*;
import mindustry.*;

import java.util.*;

/**
 * The exact balance: how many of each machine, and how much of each it works, so that what is asked for comes out at the
 * rates asked for, every item, liquid, unit, power and heat taken in is made or supplied, and what is made but not used
 * goes somewhere (the core takes any item; a liquid a machine can't get rid of stops it, unless it makes another it can,
 * as the game's crafters with two liquid outputs go on). A mixed-integer program (SCIP): machines are whole, their work a
 * share of what they can do at full speed (a machine fed less, or blocked, works less).
 *
 * The objective: the least ground the machines stand on (space is what the layouts are short of), then the fewest.
 */
public final class Balance{
    /** What is asked for: a resource and the rate (a second) it must come out at (into the core for items). */
    public final LinkedHashMap<String, Float> goals = new LinkedHashMap<>();
    /** What is supplied without limit (the sandbox's sources): resources by name; "power" for a power source. */
    public final Set<String> sources = new HashSet<>();
    /** The recipes that may be used. */
    public final List<Recipe> recipes = new ArrayList<>();
    /** Places for machines on ground, each with its yield, by recipe name (a recipe on ground with no place is not used). */
    public final Map<String, List<Float>> places = new HashMap<>();

    public static final class Result{
        public boolean solved;
        public String status;
        /** Machines: recipe, how many, how much they work in all (in machines at full speed), and its yield for ground ones. */
        public final List<Object[]> machines = new ArrayList<>();
        public final LinkedHashMap<String, Float> drawn = new LinkedHashMap<>(), delivered = new LinkedHashMap<>(), spilt = new LinkedHashMap<>();
        public float powerMade, powerUsed, heatMade, heatNeeded, area;

        @Override
        public String toString(){
            StringBuilder s = new StringBuilder("balance " + status + (solved ? String.format(": area %.0f tiles", area) : "") + "\n");
            for(Object[] m : machines) s.append(String.format("  %d x %s%s, working %.3f%n", (Integer)m[1], ((Recipe)m[0]).name, m[3] == null ? "" : String.format(" (yield %.1f)", (Float)m[3]), (Float)m[2]));
            if(!drawn.isEmpty()) s.append("  supplied: ").append(Recipe.fmt(drawn)).append('\n');
            if(!delivered.isEmpty()) s.append("  to the core: ").append(Recipe.fmt(delivered)).append('\n');
            if(!spilt.isEmpty()) s.append("  liquid let go (a second output its maker goes on past): ").append(Recipe.fmt(spilt)).append('\n');
            s.append(String.format("  power made %.1f used %.1f; heat made %.1f needed %.1f", powerMade, powerUsed, heatMade, heatNeeded));
            return s.toString();
        }
    }

    static boolean loaded;

    /** A goal's rate, a thousandth under as written (0.0286 a second asked of a yard that makes one in 35 s, 0.02857, had two put down). */
    double goal(String k){
        return goals.getOrDefault(k, 0f) * (1.0 - 1e-3);
    }

    static boolean isItem(String name){
        return Vars.content.item(name) != null;
    }

    static boolean isLiquid(String name){
        return Vars.content.liquid(name) != null;
    }

    public Result solve(){
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
                vars.add(new Object[]{r, n, a, null, r.at(1f)});
            }else{
                List<Float> ps = places.getOrDefault(r.name, List.of());
                for(int i = 0; i < ps.size(); i++){
                    float y = ps.get(i);
                    MPVariable n = solver.makeIntVar(0, 1, "n_" + r.name + "_" + i), a = solver.makeNumVar(0, inf, "a_" + r.name + "_" + i);
                    MPConstraint c = solver.makeConstraint(-inf, 0);
                    c.setCoefficient(a, 1);
                    c.setCoefficient(n, -1);
                    vars.add(new Object[]{r, n, a, y, r.at(y)});
                }
            }
        }
        //every resource that comes up
        Set<String> res = new LinkedHashSet<>(goals.keySet());
        for(Object[] v : vars){
            Recipe.Rates rt = (Recipe.Rates)v[4];
            res.addAll(rt.in.keySet());
            res.addAll(rt.out.keySet());
        }
        Map<String, MPVariable> drawn = new HashMap<>(), delivered = new HashMap<>(), spilt = new HashMap<>();
        for(String k : res){
            //made + supplied = taken + delivered (+ let go)
            MPConstraint c = solver.makeConstraint(0, 0, "bal_" + k);
            for(Object[] v : vars){
                Recipe.Rates rt = (Recipe.Rates)v[4];
                float net = rt.out.getOrDefault(k, 0f) - rt.in.getOrDefault(k, 0f);
                if(net != 0f) c.setCoefficient((MPVariable)v[2], net);
            }
            if(sources.contains(k)){
                MPVariable s = solver.makeNumVar(0, inf, "s_" + k);
                drawn.put(k, s);
                c.setCoefficient(s, 1);
            }
            if(isItem(k)){
                //(any item may go to the core: what is asked for at its rate at least)
                MPVariable d = solver.makeNumVar(goal(k), inf, "d_" + k);
                delivered.put(k, d);
                c.setCoefficient(d, -1);
            }else if(isLiquid(k)){
                if(goals.containsKey(k)){
                    MPVariable d = solver.makeNumVar(goal(k), inf, "d_" + k);
                    delivered.put(k, d);
                    c.setCoefficient(d, -1);
                }
                //(a liquid let go: only a second output of a maker that goes on past it, see below)
                MPVariable sp = solver.makeNumVar(0, inf, "spill_" + k);
                spilt.put(k, sp);
                c.setCoefficient(sp, -1);
            }else{
                //units: what is asked for comes out, the rest goes on to what takes it
                MPVariable d = solver.makeNumVar(goal(k), inf, "d_" + k);
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
            }
        }
        //power: made (or supplied) at least what is used
        MPConstraint power = solver.makeConstraint(0, inf, "power");
        MPVariable powerSource = sources.contains("power") ? solver.makeNumVar(0, inf, "s_power") : null;
        if(powerSource != null) power.setCoefficient(powerSource, 1);
        //heat: given at least what is needed (where it goes is the layout's)
        MPConstraint heat = solver.makeConstraint(0, inf, "heat");
        MPVariable heatSource = sources.contains("heat") ? solver.makeNumVar(0, inf, "s_heat") : null;
        if(heatSource != null) heat.setCoefficient(heatSource, 1);
        MPObjective obj = solver.objective();
        for(Object[] v : vars){
            Recipe r = (Recipe)v[0];
            Recipe.Rates rt = (Recipe.Rates)v[4];
            MPVariable a = (MPVariable)v[2], n = (MPVariable)v[1];
            power.setCoefficient(a, rt.powerOut - rt.powerUse);
            heat.setCoefficient(a, rt.heatOut - rt.heatNeed);
            //(the ground it stands on first; one machine more costs a little besides)
            obj.setCoefficient(n, r.size * r.size + 0.01);
            //(and working it a little, so that nothing works for nothing)
            obj.setCoefficient(a, 1e-4);
        }
        for(MPVariable s : drawn.values()) obj.setCoefficient(s, 1e-5);
        obj.setMinimization();
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
            out.machines.add(new Object[]{r, count, (float)a.solutionValue(), v[3]});
            out.area += count * r.size * r.size;
            out.powerMade += a.solutionValue() * rt.powerOut;
            out.powerUsed += a.solutionValue() * rt.powerUse;
            out.heatMade += a.solutionValue() * rt.heatOut;
            out.heatNeeded += a.solutionValue() * rt.heatNeed;
        }
        for(var e : drawn.entrySet()) if(e.getValue().solutionValue() > 1e-6) out.drawn.put(e.getKey(), (float)e.getValue().solutionValue());
        if(powerSource != null && powerSource.solutionValue() > 1e-6) out.drawn.put("power", (float)powerSource.solutionValue());
        if(heatSource != null && heatSource.solutionValue() > 1e-6) out.drawn.put("heat", (float)heatSource.solutionValue());
        for(var e : delivered.entrySet()) if(e.getValue().solutionValue() > 1e-6) out.delivered.put(e.getKey(), (float)e.getValue().solutionValue());
        for(var e : spilt.entrySet()) if(e.getValue().solutionValue() > 1e-6) out.spilt.put(e.getKey(), (float)e.getValue().solutionValue());
        return out;
    }

    /** Usage: bp.Balance data-dir goal:rate[,goal:rate] sources (comma separated; "power", "heat" too) */
    public static void main(String[] args){
        Game.boot(args[0]);
        Balance b = new Balance();
        for(String g : args[1].split(",")){
            String[] p = g.split(":");
            b.goals.put(p[0], Float.parseFloat(p[1]));
        }
        if(args.length > 2) b.sources.addAll(Arrays.asList(args[2].split(",")));
        for(Recipe r : Recipes.all()) if(r.kind != Recipe.Kind.source && !r.name.equals("heat-source")) b.recipes.add(r);
        long t0 = System.nanoTime();
        Result r = b.solve();
        System.out.println(r);
        System.out.println(String.format("  solved in %.0f ms", (System.nanoTime() - t0) / 1e6));
        System.exit(0);
    }
}
