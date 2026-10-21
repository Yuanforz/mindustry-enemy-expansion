package bp;

import com.google.ortools.Loader;
import com.google.ortools.sat.*;
import mindustry.world.*;

import java.util.*;

/**
 * How the heaters of a machine that needs heat stand against it: the least costly arrangement (ground, items, by the
 * weights) of heaters and heat redirectors that gives at least the machine's need, every one touching what it heats with
 * its whole face (Building.calculateHeat: heat over size times the tiles in contact; a redirector, HeatConductor, passes
 * on all its other three faces gather), leaving free at least as many tiles against the machine as it has lines.
 *
 * A small integer program (CP-SAT): every way a heater or a redirector can stand against a face of the machine, or of
 * a redirector that may stand (two levels of redirectors), whole face to it, is a choice; chosen ones do not overlap;
 * one stands only if what it faces does; a redirector gives at most what faces it gathers; the machine gets its need.
 */
public final class Heat{
    /** One block of an arrangement, in the machine's frame (machine at 0,0): lowest corner, rotation (facing what it heats). */
    public record Put(Block block, Recipe recipe, int x0, int y0, int rot){
    }

    /** A way a block may stand: what it is, where, what it faces (index, -1 the machine), and its level. */
    record Cand(Block block, Recipe recipe, int x0, int y0, int rot, int target, int level){
    }

    static boolean loaded;

    /** The low corner of a block of size w put against a side (outward dir) of a square at an offset along it. */
    static int[] at(int bx, int by, int bsize, int dir, int off, int w){
        return switch(dir){
            case 0 -> new int[]{bx + bsize, by + off};
            case 1 -> new int[]{bx + off, by + bsize};
            case 2 -> new int[]{bx - w, by + off};
            default -> new int[]{bx + off, by - w};
        };
    }

    static double cost(Block b, float perTile, float perItem){
        return Carriers.cost(b, perTile, perItem);
    }

    /** The best arrangement, or null if none gives the need. ports: tiles against the machine to be left free. */
    public static List<Put> arrange(Recipe machine, List<Recipe> heaters, List<Block> redirectors, float perTile, float perItem, int ports){
        if(!loaded){
            Loader.loadNativeLibraries();
            loaded = true;
        }
        int s = machine.size, depth = 2;
        //the ways to stand: against the machine's four sides, then against each redirector's three outer sides
        List<Cand> cands = new ArrayList<>();
        List<int[]> targets = new ArrayList<>();
        targets.add(new int[]{0, 0, s, -1, -1, 0});
        for(int ti = 0; ti < targets.size(); ti++){
            int[] t = targets.get(ti);
            int bx = t[0], by = t[1], bs = t[2], front = t[3], self = t[4], level = t[5];
            for(int d = 0; d < 4; d++){
                if(d == front) continue;
                for(Recipe h : heaters) for(int off = 0; off + h.size <= bs; off++){
                    int[] c = at(bx, by, bs, d, off, h.size);
                    if(overlapsMachine(c[0], c[1], h.size, s)) continue;
                    cands.add(new Cand(h.block, h, c[0], c[1], (d + 2) % 4, self, level + 1));
                }
                if(level < depth) for(Block r : redirectors) for(int off = 0; off + r.size <= bs; off++){
                    int[] c = at(bx, by, bs, d, off, r.size);
                    if(overlapsMachine(c[0], c[1], r.size, s)) continue;
                    cands.add(new Cand(r, null, c[0], c[1], (d + 2) % 4, self, level + 1));
                    //(its front faces what it heats: the side toward the target)
                    targets.add(new int[]{c[0], c[1], r.size, (d + 2) % 4, cands.size() - 1, level + 1});
                }
            }
        }
        CpModel m = new CpModel();
        int n = cands.size();
        BoolVar[] x = new BoolVar[n];
        IntVar[] heat = new IntVar[n];
        int scale = 100, big = 0;
        for(Recipe h : heaters) big += Math.round(h.heatOut * scale);
        big = Math.max(1, big) * 64;
        for(int i = 0; i < n; i++){
            x[i] = m.newBoolVar("x" + i);
            Cand c = cands.get(i);
            if(c.target >= 0) m.addImplication(x[i], x[c.target]);
        }
        //what each gives what it faces: a heater its heat, a redirector at most what faces it, nothing if not there
        List<List<Integer>> into = new ArrayList<>();
        for(int i = 0; i <= n; i++) into.add(new ArrayList<>());
        for(int i = 0; i < n; i++) into.get(cands.get(i).target + 1).add(i);
        for(int i = 0; i < n; i++){
            Cand c = cands.get(i);
            if(c.recipe != null){
                int hv = Math.round(c.recipe.heatOut * scale);
                heat[i] = m.newIntVar(0, hv, "h" + i);
                m.addEquality(heat[i], LinearExpr.term(x[i], hv));
            }else{
                heat[i] = m.newIntVar(0, big, "h" + i);
                m.addLessOrEqual(heat[i], LinearExpr.term(x[i], big));
            }
        }
        for(int i = 0; i < n; i++){
            if(cands.get(i).recipe != null) continue;
            List<Integer> kids = into.get(i + 1);
            LinearExprBuilder sum = LinearExpr.newBuilder();
            for(int k : kids) sum.add(heat[k]);
            m.addLessOrEqual(heat[i], sum);
        }
        LinearExprBuilder got = LinearExpr.newBuilder();
        for(int k : into.get(0)) got.add(heat[k]);
        m.addGreaterOrEqual(got, Math.round(machine.heatNeed * scale));
        //no two on a tile; the tiles against the machine left free for its lines
        Map<Long, List<Integer>> onTile = new HashMap<>();
        for(int i = 0; i < n; i++){
            Cand c = cands.get(i);
            for(int y = c.y0; y < c.y0 + c.block.size; y++) for(int xx = c.x0; xx < c.x0 + c.block.size; xx++) onTile.computeIfAbsent(key(xx, y), k -> new ArrayList<>()).add(i);
        }
        for(List<Integer> l : onTile.values()) if(l.size() > 1){
            LinearExprBuilder e = LinearExpr.newBuilder();
            for(int i : l) e.add(x[i]);
            m.addLessOrEqual(e, 1);
        }
        LinearExprBuilder covered = LinearExpr.newBuilder();
        int around = 0;
        for(int i = 0; i < s; i++) for(int[] t : new int[][]{{s, i}, {-1, i}, {i, s}, {i, -1}}){
            around++;
            for(int c : onTile.getOrDefault(key(t[0], t[1]), List.of())) covered.add(x[c]);
        }
        m.addLessOrEqual(covered, around - ports);
        //a heater that takes anything (power, fuel) keeps a tile against it free, for a line or a node to reach it by
        for(int i = 0; i < n; i++){
            Cand c = cands.get(i);
            if(c.recipe == null || c.recipe.in.isEmpty() && c.recipe.powerUse <= 0f && c.recipe.fixedIn.isEmpty()) continue;
            LinearExprBuilder free = LinearExpr.newBuilder();
            int w = c.block.size;
            for(int k = 0; k < w; k++) for(int[] t : new int[][]{{c.x0 + w, c.y0 + k}, {c.x0 - 1, c.y0 + k}, {c.x0 + k, c.y0 + w}, {c.x0 + k, c.y0 - 1}}){
                if(overlapsMachine(t[0], t[1], 1, s)) continue;
                BoolVar f = m.newBoolVar("free" + i);
                //(free: nothing chosen stands on it)
                for(int o : onTile.getOrDefault(key(t[0], t[1]), List.of())) if(o != i) m.addImplication(f, x[o].not());
                free.add(f);
            }
            m.addGreaterOrEqual(free, x[i]);
        }
        LinearExprBuilder obj = LinearExpr.newBuilder();
        for(int i = 0; i < n; i++) obj.addTerm(x[i], Math.round(cost(cands.get(i).block, perTile, perItem) * 1000));
        m.minimize(obj);
        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(20);
        solver.getParameters().setNumWorkers(4);
        CpSolverStatus st = solver.solve(m);
        if(st != CpSolverStatus.OPTIMAL && st != CpSolverStatus.FEASIBLE) return null;
        List<Put> out = new ArrayList<>();
        for(int i = 0; i < n; i++) if(solver.booleanValue(x[i])){
            Cand c = cands.get(i);
            out.add(new Put(c.block, c.recipe, c.x0, c.y0, c.rot));
        }
        return out;
    }

    static boolean overlapsMachine(int x0, int y0, int w, int s){
        return x0 < s && x0 + w > 0 && y0 < s && y0 + w > 0;
    }

    static long key(int x, int y){
        return ((long)x << 32) ^ (y & 0xffffffffL);
    }
}
