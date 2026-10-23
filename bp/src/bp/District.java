package bp;

import arc.files.*;
import com.google.ortools.Loader;
import com.google.ortools.linearsolver.*;
import mindustry.*;
import mindustry.world.*;

import java.io.*;
import java.util.*;

/**
 * The final form on its map in coarse: the map cut into squares (cells), and a linear program that says how many of
 * each machine of the plan stand in each cell and how much of each thing crosses from cell to cell. Each thing a cell
 * makes and takes is balanced by what comes in and goes out to its four neighbours (the core's cell sending the plan's
 * deliveries to the core); carrying a thing over a cell costs the tiles of carrier a line of it would take a unit a
 * second (its carrier's rate), so what is made and taken in bulk ends up together. A cell holds no more machines than
 * its free ground at the fill people's designs have (Survey: machines over half their box); a border between two cells
 * no more lines than the tiles on it where a line may cross. The plan's places stand in their cells. A heated machine
 * stands with its heaters (they must touch it); power goes by beam nodes and is not carried.
 *
 * Usage: bp.District data-dir world.msav in.plan out.png [cell size]
 */
public final class District{
    public static void main(String[] args) throws IOException{
        long t0 = System.nanoTime();
        Game.boot(args[0]);
        Loader.loadNativeLibraries();
        Map<String, Recipe> recipes = new HashMap<>();
        for(Recipe r : Recipes.all()) recipes.put(r.name, r);
        Problem prob = Problem.parse("world " + args[1] + "\n");
        Gen g = new Gen(prob, args[0]);
        Board b = g.board;
        int K = args.length > 4 ? Integer.parseInt(args[4]) : 10;
        float fill = Float.parseFloat(System.getProperty("bp.fill", "0.5"));
        int cw = (b.w + K - 1) / K, ch = (b.h + K - 1) / K, C = cw * ch;
        //(free ground of each cell; tiles on each border a line may cross)
        int[] free = new int[C];
        for(int y = 0; y < b.h; y++) for(int x = 0; x < b.w; x++) if(b.floor(x, y)) free[(y / K) * cw + x / K]++;
        Spec.Place core = prob.fixed.get(0);
        int coreCell = (core.y() / K) * cw + core.x() / K;
        //(what each machine of the plan takes and makes a second, its heaters' with it; its area, its heaters' with it)
        Map<String, Map<String, Float>> net = new LinkedHashMap<>();
        Map<String, Float> area = new HashMap<>(), count = new LinkedHashMap<>();
        Map<String, Integer> fixedCell = new HashMap<>();
        List<String[]> heaters = new ArrayList<>();
        Map<String, Float> delivered = new HashMap<>();
        int siteN = 0;
        for(String line : new Fi(args[2]).readString().split("\n")){
            String[] w = line.trim().split("\\s+");
            if(w[0].equals("machine")){
                Recipe r = recipes.get(w[1]);
                int n = Integer.parseInt(w[2]);
                float work = Float.parseFloat(w[3]);
                if(!w[4].equals("-")){
                    heaters.add(w);
                    continue;
                }
                Map<String, Float> m = new LinkedHashMap<>();
                Recipe.Rates rt = r.at(1f);
                for(var e : rt.out.entrySet()) m.merge(e.getKey(), e.getValue() * work / n, Float::sum);
                for(var e : rt.in.entrySet()) m.merge(e.getKey(), -e.getValue() * work / n, Float::sum);
                for(var e : r.fixedOut.entrySet()) m.merge(e.getKey(), e.getValue(), Float::sum);
                for(var e : r.fixedIn.entrySet()) m.merge(e.getKey(), -e.getValue(), Float::sum);
                net.put(w[1], m);
                area.put(w[1], (float)r.area());
                count.put(w[1], (float)n);
            }else if(w[0].equals("site")){
                Recipe r = recipes.get(w[1]);
                int x0 = Integer.parseInt(w[2]), y0 = Integer.parseInt(w[3]);
                float yield = Float.parseFloat(w[5]), work = Float.parseFloat(w[6]);
                String key = "site " + (siteN++) + " " + w[1];
                Map<String, Float> m = new LinkedHashMap<>();
                Recipe.Rates rt = r.at(yield);
                for(var e : rt.out.entrySet()) m.merge(e.getKey(), e.getValue() * work, Float::sum);
                for(var e : rt.in.entrySet()) m.merge(e.getKey(), -e.getValue() * work, Float::sum);
                net.put(key, m);
                area.put(key, (float)r.area());
                count.put(key, 1f);
                int cx = Math.min(cw - 1, (x0 + r.size / 2) / K), cy = Math.min(ch - 1, (y0 + r.size / 2) / K);
                fixedCell.put(key, cy * cw + cx);
            }else if(w[0].equals("delivered") && Vars.content.item(w[1]) != null) delivered.put(w[1], Float.parseFloat(w[2]));
        }
        //(heaters go with what they heat: their takings and area shared out over its machines)
        for(String[] w : heaters){
            String of = w[4].substring("heating_".length());
            Recipe r = recipes.get(w[1]);
            int n = Integer.parseInt(w[2]);
            float work = Float.parseFloat(w[3]);
            Map<String, Float> m = net.get(of);
            if(m == null) continue;
            float per = 1f / count.get(of);
            Recipe.Rates rt = r.at(1f);
            for(var e : rt.out.entrySet()) m.merge(e.getKey(), e.getValue() * work * per, Float::sum);
            for(var e : rt.in.entrySet()) m.merge(e.getKey(), -e.getValue() * work * per, Float::sum);
            area.merge(of, r.area() * n * per, Float::sum);
        }
        //(things carried: items, liquids, payloads; the tiles of carrier a unit a second takes)
        Set<String> things = new TreeSet<>();
        for(Map<String, Float> m : net.values()) for(String k : m.keySet()) if(Vars.content.item(k) != null || Vars.content.liquid(k) != null || Board.isPayload(k)) things.add(k);
        float duct = g.carriers.rate.get(g.carriers.cheapest(Carriers.Role.duct, g.weights));
        float conduit = g.carriers.rate.get(g.carriers.cheapest(Carriers.Role.conduit, g.weights));
        Block pb = g.carriers.cheapest(Carriers.Role.payload, g.weights);
        float payload = pb == null ? 1f : g.carriers.rate.getOrDefault(pb, 1f);
        Map<String, Float> perUnit = new HashMap<>();
        for(String k : things) perUnit.put(k, Vars.content.liquid(k) != null ? 1f / conduit : Vars.content.item(k) != null ? 1f / duct : 3f / payload);
        //(borders: the tiles a line may cross from one cell to the next)
        List<int[]> edges = new ArrayList<>();
        for(int cy = 0; cy < ch; cy++) for(int cx = 0; cx < cw; cx++){
            for(int d = 0; d < 2; d++){
                int nx = cx + (d == 0 ? 1 : 0), ny = cy + (d == 1 ? 1 : 0);
                if(nx >= cw || ny >= ch) continue;
                int cross = 0;
                for(int i = 0; i < K; i++){
                    int ax, ay, bx, by;
                    if(d == 0){
                        ax = nx * K - 1;
                        bx = nx * K;
                        ay = by = cy * K + i;
                    }else{
                        ay = ny * K - 1;
                        by = ny * K;
                        ax = bx = cx * K + i;
                    }
                    if(b.floor(ax, ay) && b.floor(bx, by)) cross++;
                }
                if(cross > 0) edges.add(new int[]{cy * cw + cx, ny * cw + nx, cross});
            }
        }
        float total = 0f, ground = 0f;
        for(String u : net.keySet()) total += area.get(u) * count.get(u);
        for(int c = 0; c < C; c++) ground += free[c];
        System.out.println(String.format(Locale.ROOT, "  machines and places, with heaters: %.0f tiles; free ground %.0f tiles (%.0f%%)", total, ground, 100 * total / ground));
        boolean mip = Boolean.getBoolean("bp.mip");
        MPSolver lp = MPSolver.createSolver(mip ? "SCIP" : System.getProperty("bp.lp", "GLOP"));
        if(mip) lp.setTimeLimit(Long.getLong("bp.seconds", 300L) * 1000);
        double inf = MPSolver.infinity();
        Map<String, MPVariable[]> x = new LinkedHashMap<>();
        for(String u : net.keySet()){
            MPVariable[] v = new MPVariable[C];
            Integer fc = fixedCell.get(u);
            MPConstraint all = lp.makeConstraint(count.get(u), count.get(u));
            for(int c = 0; c < C; c++){
                boolean may = fc == null ? free[c] > 0 : fc == c;
                v[c] = mip && fc == null ? lp.makeIntVar(0, may ? count.get(u) : 0, "") : lp.makeNumVar(0, may ? inf : 0, "");
                all.setCoefficient(v[c], 1);
            }
            x.put(u, v);
        }
        double[] capacity = new double[C];
        //(ground: machines' area within the cell's free ground at the fill)
        for(int c = 0; c < C; c++){
            MPConstraint cap = lp.makeConstraint(-inf, Math.max(0, free[c] * fill));
            for(String u : net.keySet()) if(!fixedCell.containsKey(u)) cap.setCoefficient(x.get(u)[c], area.get(u));
            float fixedArea = 0f;
            for(String u : fixedCell.keySet()) if(fixedCell.get(u) == c) fixedArea += area.get(u);
            cap.setBounds(-inf, Math.max(fixedArea, free[c] * fill) - fixedArea);
            capacity[c] = Math.max(fixedArea, free[c] * fill) - fixedArea;
        }
        //(flows over each border both ways; the lines across a border no more than its tiles)
        Map<String, MPVariable[][]> f = new HashMap<>();
        MPObjective obj = lp.objective();
        MPConstraint[] lines = new MPConstraint[edges.size()];
        for(int e = 0; e < edges.size(); e++) lines[e] = lp.makeConstraint(-inf, edges.get(e)[2]);
        Map<String, List<MPVariable>> shorts = new HashMap<>();
        for(String k : things){
            MPVariable[][] fe = new MPVariable[edges.size()][2];
            for(int e = 0; e < edges.size(); e++) for(int s = 0; s < 2; s++){
                fe[e][s] = lp.makeNumVar(0, inf, "");
                obj.setCoefficient(fe[e][s], perUnit.get(k) * K);
                lines[e].setCoefficient(fe[e][s], perUnit.get(k));
            }
            f.put(k, fe);
            //(in each cell: made + in >= taken + out (+ to the core); what is made beyond is not made)
            for(int c = 0; c < C; c++){
                float need = c == coreCell ? delivered.getOrDefault(k, 0f) : 0f;
                MPConstraint bal = lp.makeConstraint(need, inf);
                //(a shortfall, at a great price, so as to see it)
                MPVariable sh = lp.makeNumVar(0, inf, "short " + k + " " + c);
                bal.setCoefficient(sh, 1);
                obj.setCoefficient(sh, 1e3);
                shorts.computeIfAbsent(k, z -> new ArrayList<>()).add(sh);
                for(String u : net.keySet()){
                    Float r = net.get(u).get(k);
                    if(r != null) bal.setCoefficient(x.get(u)[c], r);
                }
                for(int e = 0; e < edges.size(); e++){
                    int[] ed = edges.get(e);
                    if(ed[0] == c){
                        bal.setCoefficient(fe[e][0], -1);
                        bal.setCoefficient(fe[e][1], 1);
                    }else if(ed[1] == c){
                        bal.setCoefficient(fe[e][0], 1);
                        bal.setCoefficient(fe[e][1], -1);
                    }
                }
            }
        }
        obj.setMinimization();
        if(System.getProperty("bp.lp", "GLOP").equals("GLOP")) lp.setSolverSpecificParametersAsString(System.getProperty("bp.glop", "solution_feasibility_tolerance: 1e-3 primal_feasibility_tolerance: 1e-6"));
        long ts = System.nanoTime();
        MPSolver.ResultStatus st = lp.solve();
        //(whole machines: the biggest first, each machine to the cell that has most of it in shares and room for it; then
        //the flows solved again between them)
        double lpValue = st == MPSolver.ResultStatus.OPTIMAL || st == MPSolver.ResultStatus.FEASIBLE ? obj.value() : -1;
        int rounds = 1;
        if(mip && st == MPSolver.ResultStatus.FEASIBLE) st = MPSolver.ResultStatus.OPTIMAL;
        if(st == MPSolver.ResultStatus.OPTIMAL && !mip){
            Map<String, double[]> sol = new HashMap<>();
            for(String u : net.keySet()){
                double[] a = new double[C];
                for(int c = 0; c < C; c++) a[c] = x.get(u)[c].solutionValue();
                sol.put(u, a);
            }
            double[] room = capacity.clone();
            List<String> order = new ArrayList<>();
            for(String u : net.keySet()) if(!fixedCell.containsKey(u)) order.add(u);
            order.sort((p, q) -> Float.compare(area.get(q), area.get(p)));
            Map<String, int[]> whole = new HashMap<>();
            for(String u : order){
                double[] share = sol.get(u).clone();
                int[] n = new int[C];
                for(int k = 0; k < Math.round(count.get(u)); k++){
                    int best = -1;
                    for(int c = 0; c < C; c++){
                        if(room[c] < area.get(u) - 1e-6 || free[c] == 0) continue;
                        if(best < 0 || share[c] > share[best]) best = c;
                    }
                    if(best < 0){
                        best = 0;
                        System.out.println("  no room for " + u);
                    }
                    n[best]++;
                    share[best] -= 1;
                    room[best] -= area.get(u);
                }
                whole.put(u, n);
            }
            for(String u : order) for(int c = 0; c < C; c++){
                x.get(u)[c].setLb(whole.get(u)[c]);
                x.get(u)[c].setUb(whole.get(u)[c]);
            }
            for(int c = 0; c < C; c++) if(room[c] < -1e-6) System.out.println("  cell " + c + " over by " + (-room[c]));
            st = lp.solve();
            if(st != MPSolver.ResultStatus.OPTIMAL){
                //(the same with SCIP, to tell numbers from a true conflict)
                System.out.println("  GLOP: " + st);
            }
        }
        System.out.println(String.format(Locale.ROOT, "  in shares: %.0f tiles of carrier; whole machines after %d rounds", lpValue, rounds));
        System.out.println(String.format(Locale.ROOT, "%dx%d cells of %d tiles, %d machines and places, %d things, %d borders; %s in %.1f s, carrying %.0f tiles of carrier",
        cw, ch, K, net.size(), things.size(), edges.size(), st, (System.nanoTime() - ts) / 1e9, st == MPSolver.ResultStatus.OPTIMAL ? obj.value() : -1));
        if(st != MPSolver.ResultStatus.OPTIMAL){
            System.exit(1);
        }
        for(var e : shorts.entrySet()){
            double t = 0;
            for(MPVariable v : e.getValue()) t += v.solutionValue();
            if(t > 1e-3) System.out.println(String.format(Locale.ROOT, "  SHORT %s %.3f/s", e.getKey(), t));
        }
        //(what is carried most, over how many cells)
        Map<String, Double> carried = new TreeMap<>();
        for(String k : things){
            double s = 0;
            for(MPVariable[] fe : f.get(k)) s += fe[0].solutionValue() + fe[1].solutionValue();
            if(s > 1e-3) carried.put(k, s * perUnit.get(k) * K);
        }
        List<Map.Entry<String, Double>> top = new ArrayList<>(carried.entrySet());
        top.sort((p, q) -> Double.compare(q.getValue(), p.getValue()));
        System.out.println("  tiles of carrier by thing: " + top.stream().limit(14).map(e -> e.getKey() + " " + Math.round(e.getValue())).toList());
        //(each cell's machines)
        StringBuilder cells = new StringBuilder();
        for(int c = 0; c < C; c++){
            List<String> here = new ArrayList<>();
            for(String u : net.keySet()) if(!fixedCell.containsKey(u) && x.get(u)[c].solutionValue() > 0.05) here.add(String.format(Locale.ROOT, "%s %.1f", u, x.get(u)[c].solutionValue()));
            if(!here.isEmpty()) cells.append(String.format("  cell %d,%d (free %d)%s: %s%n", c % cw, c / cw, free[c], c == coreCell ? " CORE" : "", String.join(", ", here)));
        }
        System.out.print(cells);
        System.out.println(String.format(Locale.ROOT, "  in %.1f s all told", (System.nanoTime() - t0) / 1e9));
        System.exit(0);
    }
}
