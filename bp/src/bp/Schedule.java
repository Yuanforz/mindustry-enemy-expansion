package bp;

import arc.files.*;
import com.google.ortools.Loader;
import com.google.ortools.linearsolver.*;
import mindustry.*;

import java.util.*;

/**
 * When each part of the master goes up, from what stands now: a time-indexed program (SCIP). Time in spans of so many
 * seconds; in each span the core unit builds (at its build speed) and digs (what it can dig, at its rate), no more than the
 * span holds; a part built by the end of a span works from the next; a machine works as far as what it takes comes in on
 * lines that stand and what it makes goes out on them (or backs up), and power made by what works meets what is used;
 * what comes into the core over lines, and what is dug, pays for what is built; the core holds so much of each item and
 * burns the rest. Least: every part's time to stand, weighed by what it costs, so that what pays for the rest early goes
 * up early. Re-planned as it goes (a receding horizon): only the first spans are acted on.
 */
public final class Schedule{
    /** A part of the master: a body, or a line. */
    public static final class Part{
        public int id;
        public boolean line;
        public String kind, name, res;
        public float seconds, power, cap;
        public final Map<String, Float> cost = new TreeMap<>(), rate = new TreeMap<>();
        public int from = -1;
        public final List<Integer> to = new ArrayList<>();
        /** Its blocks: block name, x, y, rotation, config (or null). */
        public final List<Object[]> blocks = new ArrayList<>();
    }

    public final List<Part> parts = new ArrayList<>();
    public final Map<Integer, Part> bodies = new HashMap<>();

    public static Schedule read(String file){
        Schedule s = new Schedule();
        Map<Integer, Part> lines = new HashMap<>();
        for(String line : new Fi(file).readString().split("\n")){
            String[] w = line.trim().split("\\s+");
            switch(w[0]){
                case "comp" -> {
                    Part p = new Part();
                    p.id = Integer.parseInt(w[1]);
                    p.kind = w[2];
                    p.name = w[3];
                    p.seconds = Float.parseFloat(w[4]);
                    kv(w[5], p.cost);
                    p.power = Float.parseFloat(w[6]);
                    kv(w[7], p.rate);
                    s.parts.add(p);
                    s.bodies.put(p.id, p);
                }
                case "block" -> s.bodies.get(Integer.parseInt(w[1])).blocks.add(new Object[]{w[2], Integer.parseInt(w[3]), Integer.parseInt(w[4]), Integer.parseInt(w[5]), w[6].equals("-") ? null : w[6]});
                case "net" -> {
                    Part p = new Part();
                    p.line = true;
                    p.id = Integer.parseInt(w[1]);
                    p.kind = "line";
                    p.res = w[2];
                    p.name = "line " + w[2];
                    p.cap = Float.parseFloat(w[3]);
                    p.from = Integer.parseInt(w[4]);
                    if(!w[5].equals("-")) for(String t : w[5].split(",")) p.to.add(Integer.parseInt(t));
                    p.seconds = Float.parseFloat(w[6]);
                    kv(w[7], p.cost);
                    s.parts.add(p);
                    lines.put(p.id, p);
                }
                case "netblock" -> lines.get(Integer.parseInt(w[1])).blocks.add(new Object[]{w[2], Integer.parseInt(w[3]), Integer.parseInt(w[4]), Integer.parseInt(w[5]), null});
                case "node" -> {
                    Part p = s.bodies.get(Integer.parseInt(w[1]));
                    if(p == null) continue;
                    p.blocks.add(new Object[]{w[2], Integer.parseInt(w[3]), Integer.parseInt(w[4]), 0, null});
                    var bl = Vars.content.block(w[2]);
                    p.seconds += bl.buildTime / 60f;
                    for(var st : bl.requirements) p.cost.merge(st.item.name, (float)st.amount, Float::sum);
                }
                default -> {
                }
            }
        }
        return s;
    }

    static void kv(String s, Map<String, Float> into){
        if(s.equals("-")) return;
        for(String e : s.split(",")){
            int at = e.lastIndexOf('=');
            into.merge(e.substring(0, at), Float.parseFloat(e.substring(at + 1)), Float::sum);
        }
    }

    /** The state it is planned from. */
    public static final class State{
        public final Set<Part> built = new HashSet<>();
        public final Map<String, Float> stock = new HashMap<>();
        /** What the core unit digs a second of each item it can dig, digging only that. */
        public final Map<String, Float> dig = new HashMap<>();
        public float buildSpeed = 1.2f, coreCap = 4000f;
    }

    /** The plan: in which span each part stands (-1: not within the horizon); the first span's digging, by item, in seconds. */
    public static final class Result{
        public final Map<Part, Integer> span = new LinkedHashMap<>();
        public final Map<String, Float> digSeconds = new LinkedHashMap<>();
        public String status;
        public float objective;
    }

    static boolean loaded;

    public Result plan(State st, int spans, float spanSeconds, float thinkSeconds){
        if(!loaded){
            Loader.loadNativeLibraries();
            loaded = true;
        }
        MPSolver solver = MPSolver.createSolver("SCIP");
        double inf = MPSolver.infinity();
        int T = spans;
        float L = spanSeconds;
        Map<Part, MPVariable[]> x = new HashMap<>(), act = new HashMap<>();
        //(built by the end of span t, t = 0 now)
        for(Part p : parts){
            MPVariable[] v = new MPVariable[T + 1];
            for(int t = 0; t <= T; t++){
                boolean now = st.built.contains(p);
                v[t] = t == 0 || now ? solver.makeNumVar(now ? 1 : 0, now ? 1 : 0, "") : solver.makeBoolVar("x" + p.id + "_" + t);
                if(t > 0){
                    MPConstraint mono = solver.makeConstraint(0, inf);
                    mono.setCoefficient(v[t], 1);
                    mono.setCoefficient(v[t - 1], -1);
                }
            }
            x.put(p, v);
        }
        Set<String> items = new TreeSet<>();
        for(Part p : parts) items.addAll(p.cost.keySet());
        //digging: seconds a span on each item
        Map<String, MPVariable[]> dig = new HashMap<>();
        for(String i : st.dig.keySet()){
            MPVariable[] v = new MPVariable[T + 1];
            for(int t = 1; t <= T; t++) v[t] = solver.makeNumVar(0, L, "dig_" + i + "_" + t);
            dig.put(i, v);
        }
        //the core unit's time
        for(int t = 1; t <= T; t++){
            MPConstraint time = solver.makeConstraint(-inf, L);
            for(Part p : parts){
                if(st.built.contains(p)) continue;
                time.setCoefficient(x.get(p)[t], p.seconds / st.buildSpeed);
                time.setCoefficient(x.get(p)[t - 1], -p.seconds / st.buildSpeed);
            }
            for(MPVariable[] d : dig.values()) time.setCoefficient(d[t], 1);
        }
        //work: machines that stand by the span's start, at most their rate
        for(Part p : parts){
            if(p.line || p.rate.isEmpty() && p.power == 0f) continue;
            MPVariable[] a = new MPVariable[T + 1];
            for(int t = 1; t <= T; t++){
                a[t] = solver.makeNumVar(0, 1, "a" + p.id + "_" + t);
                MPConstraint c = solver.makeConstraint(-inf, 0);
                c.setCoefficient(a[t], 1);
                c.setCoefficient(x.get(p)[t - 1], -1);
            }
            act.put(p, a);
        }
        //flows on lines, by taker: at most what the line carries, once it stands
        Map<Part, Map<Integer, MPVariable[]>> flow = new HashMap<>();
        for(Part n : parts){
            if(!n.line) continue;
            Map<Integer, MPVariable[]> byTaker = new HashMap<>();
            for(int k : n.to){
                MPVariable[] f = new MPVariable[T + 1];
                for(int t = 1; t <= T; t++) f[t] = solver.makeNumVar(0, inf, "");
                byTaker.put(k, f);
            }
            flow.put(n, byTaker);
            for(int t = 1; t <= T; t++){
                MPConstraint cap = solver.makeConstraint(-inf, 0);
                for(MPVariable[] f : byTaker.values()) cap.setCoefficient(f[t], 1);
                cap.setCoefficient(x.get(n)[t - 1], -n.cap);
            }
        }
        //what a machine takes comes in on lines; what it makes goes out on them (or backs up)
        for(Part p : act.keySet()){
            MPVariable[] a = act.get(p);
            for(var e : p.rate.entrySet()){
                String r = e.getKey();
                float rate = e.getValue();
                if(Vars.content.item(r) == null && Vars.content.liquid(r) == null) continue;
                for(int t = 1; t <= T; t++){
                    MPConstraint c;
                    if(rate < 0){
                        //(in >= taken)
                        c = solver.makeConstraint(0, inf);
                        c.setCoefficient(a[t], rate);
                        for(var nf : flow.entrySet()) if(nf.getKey().res.equals(r) && nf.getValue().containsKey(p.id)) c.setCoefficient(nf.getValue().get(p.id)[t], 1);
                    }else{
                        //(out <= made)
                        c = solver.makeConstraint(-inf, 0);
                        c.setCoefficient(a[t], -rate);
                        for(var nf : flow.entrySet()) if(nf.getKey().res.equals(r) && nf.getKey().from == p.id) for(MPVariable[] f : nf.getValue().values()) c.setCoefficient(f[t], 1);
                    }
                }
            }
        }
        //a line from the core is no line (the core gives nothing out); a line into the core: what comes in
        //power: made by what works at least what is used
        for(int t = 1; t <= T; t++){
            MPConstraint power = solver.makeConstraint(0, inf);
            for(Part p : act.keySet()) power.setCoefficient(act.get(p)[t], p.power);
        }
        //the core's stock
        MPObjective obj = solver.objective();
        for(String i : items){
            MPVariable prev = null;
            for(int t = 1; t <= T; t++){
                MPVariable s = solver.makeNumVar(0, st.coreCap, "s_" + i + "_" + t), burnt = solver.makeNumVar(0, inf, "");
                MPConstraint bal = solver.makeConstraint(t == 1 ? st.stock.getOrDefault(i, 0f) : 0, t == 1 ? st.stock.getOrDefault(i, 0f) : 0);
                //(stock now = before + dug + come in - spent - burnt)
                bal.setCoefficient(s, 1);
                if(prev != null) bal.setCoefficient(prev, -1);
                bal.setCoefficient(burnt, 1);
                if(dig.containsKey(i)) bal.setCoefficient(dig.get(i)[t], -st.dig.get(i));
                for(var nf : flow.entrySet()) if(nf.getKey().res.equals(i) && nf.getValue().containsKey(0)) bal.setCoefficient(nf.getValue().get(0)[t], -L);
                for(Part p : parts){
                    float c = p.cost.getOrDefault(i, 0f);
                    if(c == 0f || st.built.contains(p)) continue;
                    bal.setCoefficient(x.get(p)[t], c);
                    bal.setCoefficient(x.get(p)[t - 1], -c);
                }
                prev = s;
            }
        }
        //least: each part's time to stand, weighed by its cost (and a little by its build time)
        for(Part p : parts){
            if(st.built.contains(p)) continue;
            float w = 1f + p.cost.values().stream().reduce(0f, Float::sum) / 100f + p.seconds / 10f;
            for(int t = 1; t <= T; t++) obj.setCoefficient(x.get(p)[t], -w);
        }
        obj.setMinimization();
        solver.setTimeLimit((long)(thinkSeconds * 1000));
        MPSolver.ResultStatus status = solver.solve();
        Result out = new Result();
        out.status = status.toString().toLowerCase();
        if(status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) return out;
        out.objective = (float)obj.value();
        for(Part p : parts){
            if(st.built.contains(p)) continue;
            int at = -1;
            for(int t = 1; t <= T; t++) if(x.get(p)[t].solutionValue() > 0.5){
                at = t;
                break;
            }
            if(at > 0) out.span.put(p, at);
        }
        for(var e : dig.entrySet()) if(e.getValue()[1].solutionValue() > 1e-3) out.digSeconds.put(e.getKey(), (float)e.getValue()[1].solutionValue());
        return out;
    }

    /** Usage: bp.Schedule data-dir parts.master spans span-seconds [stock item=n,...] */
    public static void main(String[] args){
        Game.boot(args[0]);
        Schedule s = read(args[1]);
        State st = new State();
        st.stock.put("beryllium", 300f);
        st.stock.put("graphite", 300f);
        st.dig.put("beryllium", 5f);
        st.dig.put("graphite", 5f);
        st.built.addAll(s.parts.stream().filter(p -> p.id == 0 && !p.line).toList());
        long t0 = System.nanoTime();
        Result r = s.plan(st, Integer.parseInt(args[2]), Float.parseFloat(args[3]), 120f);
        System.out.println(String.format("schedule %s in %.1f s: %d of %d parts within the horizon", r.status, (System.nanoTime() - t0) / 1e9, r.span.size(), s.parts.size()));
        Map<Integer, List<String>> bySpan = new TreeMap<>();
        for(var e : r.span.entrySet()) bySpan.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey().name);
        for(var e : bySpan.entrySet()) System.out.println("  span " + e.getKey() + ": " + e.getValue());
        System.out.println("  digging first: " + r.digSeconds);
        System.exit(0);
    }
}
