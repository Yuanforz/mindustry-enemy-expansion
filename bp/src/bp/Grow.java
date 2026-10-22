package bp;

import arc.files.*;
import bp.Board.*;
import com.google.ortools.Loader;
import com.google.ortools.linearsolver.*;
import mindustry.*;
import mindustry.type.*;
import mindustry.world.*;

import java.util.*;

/**
 * Line three grown: the final form's plan is where it goes, and the base grows toward it a step at a time. Each step:
 * what to add next, chosen by a rolling schedule over what is left of the plan (time in spans; the core unit's building
 * and digging; the stock; what stands, making and taking, every thing made at least as fast as it is taken, power too);
 * that laid out on the map among what stands (what stands fixed, its lines among the router's pieces; a new line fed
 * from a maker's free side), checked by the game's rules, and written for the core unit to build. A ledger keeps what is
 * committed: each body's blocks, each line's pieces and how much of what it carries from which part to which, so what a
 * maker has to spare and what a taker still lacks are known at the next step.
 *
 * Usage: bp.Grow data-dir world.msav plan-file ledger-file state-file orders-file [spans span-seconds think-seconds]
 */
public final class Grow{
    // ------------------------------------------------------------------ what the plan wants

    /** A kind of thing the plan wants so many of: a machine with its heaters, a machine on its place, a block that only stands. */
    static final class Unit{
        String key;
        Recipe recipe;
        Block block;
        int count;
        float work = 1f, yield = 1f;
        /** A machine on its place: lowest corner and turn; the tiles its beams keep clear. */
        int[] site;
        int[] lane = new int[0];
        /** Tiles it stands on, for a machine on its place (two that share one cannot both stand). */
        int[] tiles = new int[0];
        /** Of the final form (wanted), or there only if it helps the rest come sooner (a way up, kept). */
        boolean required = true;
        /**
         * What of the final form it counts as: a machine's recipe (however heated), a place, a block that only stands;
         * the final form wants so many of each class (need).
         */
        String cls;
        /** A machine heated by heaters of one kind (null: the kinds the plan chose, or no heat). */
        Recipe heater;
        /** Whether it may be taken down again (a place the final form has is kept). */
        boolean removable = true;
        final Map<String, Float> cost = new TreeMap<>(), rate = new TreeMap<>();
        float seconds, power;

        @Override
        public String toString(){
            return key;
        }
    }

    final List<Unit> units = new ArrayList<>();
    /** How many of each class the final form wants. */
    final Map<String, Integer> need = new LinkedHashMap<>();
    /** The plan's work of a machine of each recipe. */
    final Map<String, Float> planWork = new HashMap<>();
    final Balance.Result plan = new Balance.Result();
    boolean fin = true;

    void readPlan(String file, Map<String, Recipe> recipes){
        plan.solved = true;
        int sites = 0;
        for(String line : new Fi(file).readString().split("\n")){
            String[] w = line.trim().split("\\s+");
            switch(w[0]){
                case "form" -> fin = w[1].equals("final");
                case "machine" -> plan.machines.add(new Object[]{recipes.get(w[1]), Integer.parseInt(w[2]), Float.parseFloat(w[3]), null, w[4].equals("-") ? null : w[4].replace('_', ' ')});
                case "site" -> {
                    Unit u = new Unit();
                    u.recipe = recipes.get(w[1]);
                    u.block = u.recipe.block;
                    u.key = "site " + w[1] + " " + sites++;
                    u.count = 1;
                    u.site = new int[]{Integer.parseInt(w[2]), Integer.parseInt(w[3]), Integer.parseInt(w[4])};
                    u.yield = Float.parseFloat(w[5]);
                    u.work = Float.parseFloat(w[6]);
                    u.tiles = Master.split(w[7]);
                    u.lane = Master.split(w[8]);
                    u.cls = u.key;
                    u.removable = false;
                    need.put(u.cls, 1);
                    units.add(u);
                }
                case "delivered" -> plan.delivered.put(w[1], Float.parseFloat(w[2]));
                default -> {
                }
            }
        }
        //(machines that are no heater: as many as the plan has of each recipe, however heated)
        for(Object[] m : plan.machines){
            Recipe r = (Recipe)m[0];
            if(m[4] != null || r == null || r.ground != Recipe.Ground.none) continue;
            need.merge(r.name, (Integer)m[1], Integer::sum);
            planWork.put(r.name, (Float)m[2] / (Integer)m[1]);
        }
        //(the final form's blocks that only stand: one each)
        if(fin){
            Set<String> working = new HashSet<>();
            for(Recipe r : recipes.values()) if(r.kind != Recipe.Kind.source) working.add(r.block.name);
            for(Block b : Final.erekir()){
                if(working.contains(b.name) || b instanceof mindustry.world.blocks.storage.CoreBlock) continue;
                Unit u = new Unit();
                u.block = b;
                u.key = "single " + b.name;
                u.cls = u.key;
                u.count = 1;
                u.removable = false;
                need.put(u.cls, 1);
                units.add(u);
            }
        }
    }

    /**
     * Ways up besides the final form's: every machine of Erekir that makes something (an item, a liquid, power), as many
     * as may help; every machine on ground at its nearest full places not the plan's. Built only where they bring the
     * whole sooner (the schedule's choice): the plan is a steady state, and the way to it may need what it does not (a
     * drill that needs no tungsten, to dig the first tungsten).
     */
    void ways(Gen g){
        Set<String> blocks = new HashSet<>();
        for(Block b : Final.erekir()) blocks.add(b.name);
        //(the kinds of heater there are: each may heat a machine all by itself)
        List<Recipe> heaterKinds = new ArrayList<>();
        for(Recipe r : recipes.values()) if(r.kind == Recipe.Kind.heater && r.heatOut > 0f && !r.name.equals("heat-source") && r.ground == Recipe.Ground.none && blocks.contains(r.block.name)) heaterKinds.add(r);
        heaterKinds.sort(Comparator.comparing(r -> r.name));
        List<Recipe> ground = new ArrayList<>();
        List<Recipe> all = new ArrayList<>(recipes.values());
        all.sort(Comparator.comparing(r -> r.name));
        for(Recipe r : all){
            if(r.kind == Recipe.Kind.source || r.name.equals("heat-source") || !blocks.contains(r.block.name)) continue;
            boolean makes = r.powerOut > 0f || r.powerPerYield > 0f;
            for(String k : r.out.keySet()) if(Vars.content.item(k) != null || Vars.content.liquid(k) != null) makes = true;
            for(String k : r.perYield.keySet()) if(Vars.content.item(k) != null || Vars.content.liquid(k) != null) makes = true;
            int wanted = need.getOrDefault(r.name, 0);
            if(r.ground != Recipe.Ground.none){
                if(makes) ground.add(r);
                continue;
            }
            if(!makes && wanted == 0) continue;
            //(a turret or a maker of units only as many as wanted; a maker of things as many as help)
            int count = makes ? Math.max(64, wanted) : wanted;
            List<Recipe> heatings = new ArrayList<>();
            if(r.heatNeed > 0f){
                if(wanted > 0) heatings.add(null);
                heatings.addAll(heaterKinds);
            }else heatings.add(null);
            for(Recipe h : heatings){
                Unit u = new Unit();
                u.recipe = r;
                u.block = r.block;
                u.heater = h;
                u.key = r.name + (h == null ? "" : " heated by " + h.name);
                u.cls = r.name;
                u.count = count;
                u.required = wanted > 0;
                u.work = planWork.getOrDefault(r.name, 1f);
                units.add(u);
            }
        }
        Spec.Place core = g.prob.fixed.isEmpty() ? null : g.prob.fixed.get(0);
        int cx = core == null ? g.board.w / 2 : core.x(), cy = core == null ? g.board.h / 2 : core.y();
        int k = 0;
        for(var e : new Sites(g.board).find(ground, cx, cy, 6).entrySet()) for(Sites.Site site : e.getValue()){
            Unit u = new Unit();
            u.recipe = e.getKey();
            u.block = u.recipe.block;
            u.key = "way site " + u.recipe.name + " " + k++;
            u.cls = u.key;
            u.count = 1;
            u.required = false;
            u.site = new int[]{site.x0(), site.y0(), site.rot()};
            u.yield = site.yield();
            u.work = 1f;
            u.tiles = site.body();
            u.lane = site.lane();
            units.add(u);
        }
    }

    /** The tiles what is committed stands on (map index), and of them the solid ones. */
    final Set<Integer> occupied = new HashSet<>(), solidOn = new HashSet<>();

    void occupied(int w){
        for(Inst i : ledger.insts){
            if(i.removed) continue;
            for(Object[] p : i.parts){
                Block bl = Vars.content.block((String)p[0]);
                for(int y = (Integer)p[2]; y < (Integer)p[2] + bl.size; y++) for(int x = (Integer)p[1]; x < (Integer)p[1] + bl.size; x++){
                    occupied.add(x + y * w);
                    if(bl.solid) solidOn.add(x + y * w);
                }
            }
            for(Object[] q : i.pieces){
                Block bl = Vars.content.block((String)q[1]);
                int x0 = Geo.lo((Integer)q[2], bl.size), y0 = Geo.lo((Integer)q[3], bl.size);
                for(int y = y0; y < y0 + bl.size; y++) for(int x = x0; x < x0 + bl.size; x++){
                    occupied.add(x + y * w);
                    if(bl.solid) solidOn.add(x + y * w);
                }
            }
        }
    }

    /** Whether a machine on ground can no longer stand at its place: what is committed stands on it, or solid on its beams' way. */
    boolean blocked(Unit u){
        if(u.site == null || ledger.committed(u.key) > 0) return false;
        if(ledger.avoid.contains(u.key)) return true;
        for(int t : u.tiles) if(occupied.contains(t)) return true;
        for(int t : u.lane) if(solidOn.contains(t)) return true;
        return false;
    }

    /** What a unit costs, takes and makes, from one of it as a body (its heaters arranged as they will stand). */
    void measure(Gen g){
        float line = ledger.lineTiles();
        Map<String, Float> perLine = ledger.lineCost(), perNode = ledger.nodeCost();
        Block duct = g.carriers.cheapest(Carriers.Role.duct, g.weights), conduit = g.carriers.cheapest(Carriers.Role.conduit, g.weights);
        for(Unit u : units){
            Body b = body(g, u, 0);
            for(Body.Part p : b.parts){
                for(ItemStack st : p.block.requirements) u.cost.merge(st.item.name, (float)st.amount, Float::sum);
                u.seconds += p.block.buildTime / 60f;
                if(p.recipe == null) continue;
                Recipe.Rates rt = p.recipe.at(p.yield);
                Set<String> res = new TreeSet<>(rt.out.keySet());
                res.addAll(rt.in.keySet());
                res.addAll(p.recipe.fixedIn.keySet());
                res.addAll(p.recipe.fixedOut.keySet());
                for(String k : res) u.rate.merge(k, p.makes(k) - p.takes(k), Float::sum);
                u.power += (rt.powerOut - rt.powerUse) * p.work + p.recipe.fixedPowerOut - p.recipe.fixedPowerUse;
                //(and a line for each thing it takes or makes, what lines have cost so far (else so many tiles of the
                //cheapest carrier); power nodes for one that uses power, what they have cost so far)
                for(String k : res){
                    Block c = Vars.content.liquid(k) != null ? conduit : Vars.content.item(k) != null ? duct : null;
                    if(c == null) continue;
                    if(perLine != null) for(var e : perLine.entrySet()) u.cost.merge(e.getKey(), e.getValue(), Float::sum);
                    else for(ItemStack st : c.requirements) u.cost.merge(st.item.name, st.amount * line, Float::sum);
                    u.seconds += c.buildTime / 60f * line;
                }
                if(perNode != null && Power.needs(p.block)) for(var e : perNode.entrySet()) u.cost.merge(e.getKey(), e.getValue(), Float::sum);
            }
        }
    }

    /**
     * Machines as bodies, worked out once (a heated one's heaters arranged by CP-SAT, an assembler with its area and its
     * modules) and kept in a file by the ledger: by unit, each part (block, lowest corner, turn, recipe, work, yield,
     * config) and the areas kept clear.
     */
    final Map<String, List<String>> shapes = new LinkedHashMap<>();
    Fi shapeFile;

    void readShapes(Fi file){
        shapeFile = file;
        if(!file.exists()) return;
        String cur = null;
        for(String line : file.readString().split("\n")){
            if(line.startsWith("unit ")){
                cur = line.substring(5);
                shapes.put(cur, new ArrayList<>());
            }else if(cur != null && !line.isBlank()) shapes.get(cur).add(line);
        }
    }

    void writeShapes(){
        if(shapeFile == null) return;
        StringBuilder s = new StringBuilder();
        for(var e : shapes.entrySet()){
            s.append("unit ").append(e.getKey()).append('\n');
            for(String l : e.getValue()) s.append(l).append('\n');
        }
        shapeFile.writeString(s.toString());
    }

    Body machineBody(Gen g, Unit u, int id){
        List<String> shape = shapes.get(u.key);
        if(shape == null){
            Body b = g.machine(id, u.recipe, u.work, u.heater);
            shape = new ArrayList<>();
            for(Body.Part p : b.parts) shape.add(String.format(Locale.ROOT, "part %s %d %d %d %s %.6f %.4f %s", p.block.name, p.lx, p.ly, p.lrot, p.recipe == null ? "-" : p.recipe.name, p.work, p.yield, p.config == null ? "-" : p.config));
            for(int[] c : b.clearLocal) shape.add(String.format(Locale.ROOT, "clear %d %d %d %d", c[0], c[1], c[2], c[3]));
            if(b.exclusive) shape.add("exclusive");
            shapes.put(u.key, shape);
            return b;
        }
        Body b = new Body(id, u.recipe.name, false);
        for(String l : shape){
            String[] w = l.split(" ");
            switch(w[0]){
                case "part" -> b.add(Vars.content.block(w[1]), Integer.parseInt(w[2]), Integer.parseInt(w[3]), Integer.parseInt(w[4]), w[5].equals("-") ? null : recipes.get(w[5]), Float.parseFloat(w[6]), w[8].equals("-") ? null : w[8]).yield = Float.parseFloat(w[7]);
                case "clear" -> b.clearLocal.add(new int[]{Integer.parseInt(w[1]), Integer.parseInt(w[2]), Integer.parseInt(w[3]), Integer.parseInt(w[4])});
                case "exclusive" -> b.exclusive = true;
                default -> {
                }
            }
        }
        b.put(0, 0, 0);
        return b;
    }

    /** A unit as a body: a machine with its heaters, one on its place, a block that only stands. */
    Body body(Gen g, Unit u, int id){
        if(u.recipe == null){
            Body b = new Body(id, u.block.name, false);
            b.add(u.block, 0, 0, 0, null, 1f, null);
            b.put(0, 0, 0);
            return b;
        }
        if(u.site != null){
            Body b = new Body(id, u.recipe.name, true);
            b.add(u.block, 0, 0, u.site[2], u.recipe, u.work, null).yield = u.yield;
            b.put(u.site[0], u.site[1], 0);
            for(int t : u.lane) b.clearTiles.add(t);
            return b;
        }
        return machineBody(g, u, id);
    }

    // ------------------------------------------------------------------ what is committed

    /** One thing committed: a body (machine, site, single, nodes) or a line, with its blocks; for a line what it carries. */
    static final class Inst{
        int id;
        String kind, unit;
        boolean built, removed;
        /** Its blocks: block, lowest corner x, y, turn, recipe (or null), work, yield, config (or null). */
        final List<Object[]> parts = new ArrayList<>();
        /** A line's pieces: kind, block, x, y, turn, fed from, hands to (bits), role, thing. */
        final List<Object[]> pieces = new ArrayList<>();
        /** A line's flows: thing, from (inst, part), to (inst, part), how much a second. */
        final List<Object[]> flows = new ArrayList<>();
        /** The lines a line runs on into, joining them (it stands only as long as they do). */
        final Set<Integer> via = new TreeSet<>();
    }

    static final class Ledger{
        final List<Inst> insts = new ArrayList<>();
        /** Places found not to be had among what stands (a step's layout could not put them there). */
        final Set<String> avoid = new TreeSet<>();
        int step;

        int nextId(){
            int m = 0;
            for(Inst i : insts) m = Math.max(m, i.id);
            return m + 1;
        }

        Inst get(int id){
            for(Inst i : insts) if(i.id == id) return i;
            return null;
        }

        /** How many of a unit are committed (and not taken down). */
        int committed(String unit){
            int n = 0;
            for(Inst i : insts) if(unit.equals(i.unit) && !i.removed) n++;
            return n;
        }

        /**
         * What a line has cost, a thing joined (the items of its blocks over its flows), and power nodes, a machine joined:
         * as they have been; null until there are lines (nodes).
         */
        Map<String, Float> lineCost(){
            Map<String, Float> c = new TreeMap<>();
            int joins = 0;
            for(Inst i : insts) if(i.kind.equals("line")){
                for(Object[] q : i.pieces) for(ItemStack st : Vars.content.block((String)q[1]).requirements) c.merge(st.item.name, (float)st.amount, Float::sum);
                joins += Math.max(1, i.flows.size());
            }
            if(joins == 0) return null;
            for(var e : c.entrySet()) e.setValue(e.getValue() / joins);
            return c;
        }

        Map<String, Float> nodeCost(){
            Map<String, Float> c = new TreeMap<>();
            int machines = 0;
            for(Inst i : insts){
                if(i.kind.equals("nodes")) for(Object[] p : i.parts) for(ItemStack st : Vars.content.block((String)p[0]).requirements) c.merge(st.item.name, (float)st.amount, Float::sum);
                else if(!i.kind.equals("line")) machines++;
            }
            if(machines == 0 || c.isEmpty()) return null;
            for(var e : c.entrySet()) e.setValue(e.getValue() / machines);
            return c;
        }

        /** How long a line has been, a thing joined (tiles of carrier over flows): ten until there are lines. */
        float lineTiles(){
            int tiles = 0, joins = 0;
            for(Inst i : insts) if(i.kind.equals("line")){
                tiles += i.pieces.size();
                joins += Math.max(1, i.flows.size());
            }
            return joins == 0 ? 10f : (float)tiles / joins;
        }

        static Ledger read(String file){
            Ledger l = new Ledger();
            Fi f = new Fi(file);
            if(!f.exists()) return l;
            Inst cur = null;
            for(String line : f.readString().split("\n")){
                String[] w = line.trim().split("\\s+");
                switch(w[0]){
                    case "step" -> l.step = Integer.parseInt(w[1]);
                    case "avoid" -> l.avoid.add(w[1].replace('|', ' '));
                    case "inst" -> {
                        cur = new Inst();
                        cur.id = Integer.parseInt(w[1]);
                        cur.kind = w[2];
                        cur.unit = w[3].equals("-") ? null : w[3].replace('|', ' ');
                        cur.built = w[4].equals("built");
                        cur.removed = w[4].equals("removed");
                        l.insts.add(cur);
                    }
                    case "part" -> cur.parts.add(new Object[]{w[1], Integer.parseInt(w[2]), Integer.parseInt(w[3]), Integer.parseInt(w[4]), w[5].equals("-") ? null : w[5], Float.parseFloat(w[6]), Float.parseFloat(w[7]), w[8].equals("-") ? null : w[8]});
                    case "piece" -> cur.pieces.add(new Object[]{w[1], w[2], Integer.parseInt(w[3]), Integer.parseInt(w[4]), Integer.parseInt(w[5]), Integer.parseInt(w[6]), Integer.parseInt(w[7]), Integer.parseInt(w[8]), w[9]});
                    case "flow" -> cur.flows.add(new Object[]{w[1], Integer.parseInt(w[2]), Integer.parseInt(w[3]), Integer.parseInt(w[4]), Integer.parseInt(w[5]), Float.parseFloat(w[6])});
                    case "via" -> cur.via.add(Integer.parseInt(w[1]));
                    default -> {
                    }
                }
            }
            return l;
        }

        void write(String file){
            StringBuilder s = new StringBuilder("# what the grown base has committed, step by step\nstep " + step + "\n");
            for(String a : avoid) s.append("avoid ").append(a.replace(' ', '|')).append('\n');
            for(Inst i : insts){
                s.append(String.format(Locale.ROOT, "inst %d %s %s %s%n", i.id, i.kind, i.unit == null ? "-" : i.unit.replace(' ', '|'), i.removed ? "removed" : i.built ? "built" : "committed"));
                for(Object[] p : i.parts) s.append(String.format(Locale.ROOT, "part %s %d %d %d %s %.6f %.4f %s%n", p[0], p[1], p[2], p[3], p[4] == null ? "-" : p[4], p[5], p[6], p[7] == null ? "-" : p[7]));
                for(Object[] q : i.pieces) s.append(String.format(Locale.ROOT, "piece %s %s %d %d %d %d %d %d %s%n", q));
                for(Object[] f : i.flows) s.append(String.format(Locale.ROOT, "flow %s %d %d %d %d %.6f%n", f));
                for(int v : i.via) s.append("via ").append(v).append('\n');
            }
            new Fi(file).writeString(s.toString());
        }
    }

    Ledger ledger;

    // ------------------------------------------------------------------ what stands now (from the game)

    static final class State{
        final Map<String, Float> stock = new HashMap<>(), dig = new HashMap<>();
        final Set<Integer> built = new HashSet<>(), begun = new HashSet<>();
        float buildSpeed = 1.2f, coreCap = 4000f, time;

        static State read(String file){
            State s = new State();
            Fi f = new Fi(file);
            if(!f.exists()) return s;
            for(String line : f.readString().split("\n")){
                String[] w = line.trim().split("\\s+");
                switch(w[0]){
                    case "stock" -> s.stock.put(w[1], Float.parseFloat(w[2]));
                    case "dig" -> s.dig.put(w[1], Float.parseFloat(w[2]));
                    case "built" -> s.built.add(Integer.parseInt(w[1]));
                    case "begun" -> s.begun.add(Integer.parseInt(w[1]));
                    case "buildSpeed" -> s.buildSpeed = Float.parseFloat(w[1]);
                    case "coreCap" -> s.coreCap = Float.parseFloat(w[1]);
                    case "time" -> s.time = Float.parseFloat(w[1]);
                    default -> {
                    }
                }
            }
            return s;
        }
    }

    // ------------------------------------------------------------------ what next: the rolling schedule

    static boolean loaded;

    /**
     * How many of each unit stand by the end of each span (time-indexed, SCIP), the spans growing (two of each length,
     * each length twice the one before) so that the whole of the plan falls within them: what stands works as far as what
     * it takes is made at least as fast by what works (every thing, power too), and what it makes beyond goes to the core
     * (items); what comes into the core and is dug pays for what is built; the core unit's building and digging fill no
     * more than a span; the core holds so much of an item and burns the rest. Least: how long until the whole stands, and
     * the building left undone, span by span (so that what makes the rest come sooner goes up first). The first span's is
     * the next step; the rest is planned again then.
     */
    Map<Unit, Integer> next(State st, int spans, float spanSeconds, float think){
        if(!loaded){
            Loader.loadNativeLibraries();
            loaded = true;
        }
        MPSolver solver = MPSolver.createSolver("SCIP");
        double inf = MPSolver.infinity();
        int T = spans;
        float[] L = new float[T + 1];
        for(int t = 1; t <= T; t++) L[t] = spanSeconds * (1 << ((t - 1) / 2));
        //(each span: how many of a unit are built, and taken down (a way up only: it gives back what the game gives
        //back, Rules.deconstructRefundMultiplier, and takes the core unit as long); how many stand at its end; how much of
        //them works, no more than stood from its start and stays)
        Map<Unit, MPVariable[]> n = new LinkedHashMap<>(), a = new LinkedHashMap<>(), b = new LinkedHashMap<>(), d = new LinkedHashMap<>();
        float refund = Vars.state.rules.deconstructRefundMultiplier;
        //(the classes the final form wants, each with its units, and what one of it takes to build, as planned or least)
        Map<String, List<Unit>> classes = new LinkedHashMap<>();
        for(Unit u : units) if(need.getOrDefault(u.cls, 0) > 0) classes.computeIfAbsent(u.cls, k -> new ArrayList<>()).add(u);
        Map<String, Float> clsSeconds = new HashMap<>();
        for(var e : classes.entrySet()){
            float sec = Float.MAX_VALUE;
            for(Unit u : e.getValue()) sec = u.heater == null && u.recipe != null && u.recipe.heatNeed > 0f ? u.seconds : Math.min(sec, u.seconds);
            clsSeconds.put(e.getKey(), sec);
        }
        float total = 0f;
        for(var e : classes.entrySet()) total += clsSeconds.get(e.getKey()) * need.get(e.getKey());
        for(Unit u : units){
            int have = ledger.committed(u.key);
            MPVariable[] v = new MPVariable[T + 1], w = new MPVariable[T + 1], up = new MPVariable[T + 1], down = new MPVariable[T + 1];
            v[0] = solver.makeIntVar(have, have, "");
            int most = blocked(u) ? have : u.count;
            for(int t = 1; t <= T; t++){
                //(whole numbers in the first two spans, the ones acted on soon; beyond, a share: the far plan an estimate)
                boolean whole = t <= Integer.getInteger("bp.wholeSpans", 2);
                int lo = u.removable ? 0 : have;
                v[t] = whole ? solver.makeIntVar(lo, most, "n_" + u.key + "_" + t) : solver.makeNumVar(lo, most, "n_" + u.key + "_" + t);
                up[t] = whole ? solver.makeIntVar(0, u.count, "") : solver.makeNumVar(0, u.count, "");
                down[t] = whole ? solver.makeIntVar(0, u.removable ? u.count : 0, "") : solver.makeNumVar(0, u.removable ? u.count : 0, "");
                MPConstraint flow = solver.makeConstraint(0, 0);
                flow.setCoefficient(v[t], 1);
                flow.setCoefficient(v[t - 1], -1);
                flow.setCoefficient(up[t], -1);
                flow.setCoefficient(down[t], 1);
                w[t] = solver.makeNumVar(0, inf, "");
                MPConstraint c = solver.makeConstraint(-inf, 0);
                c.setCoefficient(w[t], 1);
                c.setCoefficient(v[t - 1], -1);
                c.setCoefficient(down[t], 1);
            }
            n.put(u, v);
            a.put(u, w);
            b.put(u, up);
            d.put(u, down);
        }
        //(digging: seconds a span on each item the core unit can dig)
        Map<String, MPVariable[]> dig = new HashMap<>();
        for(String i : st.dig.keySet()){
            MPVariable[] v = new MPVariable[T + 1];
            for(int t = 1; t <= T; t++) v[t] = solver.makeNumVar(0, L[t], "dig_" + i + "_" + t);
            dig.put(i, v);
        }
        Set<String> res = new TreeSet<>();
        for(Unit u : units){
            res.addAll(u.rate.keySet());
            res.addAll(u.cost.keySet());
        }
        MPObjective obj = solver.objective();
        Map<String, MPVariable[]> part = new HashMap<>();
        for(int t = 1; t <= T; t++){
            //the core unit's time
            MPConstraint time = solver.makeConstraint(-inf, L[t]);
            for(Unit u : units){
                time.setCoefficient(b.get(u)[t], u.seconds / st.buildSpeed);
                time.setCoefficient(d.get(u)[t], u.seconds / st.buildSpeed);
            }
            for(MPVariable[] dg : dig.values()) time.setCoefficient(dg[t], 1);
            //power
            MPConstraint power = solver.makeConstraint(0, inf);
            for(Unit u : units) power.setCoefficient(a.get(u)[t], u.power);
            //(the whole standing by the end of the span: no more than the least of every class's share standing)
            MPVariable done = solver.makeNumVar(0, 1, "done_" + t);
            for(var e : classes.entrySet()){
                MPConstraint c = solver.makeConstraint(-inf, 0);
                c.setCoefficient(done, need.get(e.getKey()));
                for(Unit u : e.getValue()) c.setCoefficient(n.get(u)[t], -1);
                //(and how much of the class stands, at most what is wanted of it)
                MPVariable p = solver.makeNumVar(0, need.get(e.getKey()), "");
                MPConstraint pc = solver.makeConstraint(-inf, 0);
                pc.setCoefficient(p, 1);
                for(Unit u : e.getValue()) pc.setCoefficient(n.get(u)[t], -1);
                obj.setCoefficient(p, -L[t] * clsSeconds.get(e.getKey()) / Math.max(1f, total));
                part.computeIfAbsent(e.getKey(), k -> new MPVariable[T + 1])[t] = p;
            }
            //least: the span's length while the whole does not stand, and while building is left, by how much is left;
            //what is taken down, its building lost
            obj.setCoefficient(done, -L[t]);
            for(Unit u : units) if(u.removable) obj.setCoefficient(d.get(u)[t], L[t] * u.seconds / Math.max(1f, total));
        }
        //(two machines on ground in one another's way: not both at once)
        for(int i = 0; i < units.size(); i++) for(int j = i + 1; j < units.size(); j++){
            Unit x = units.get(i), y = units.get(j);
            //(a body on the other's body, or on the tiles its beams pass over)
            if(x.tiles.length == 0 || y.tiles.length == 0 || !share(x.tiles, y.tiles) && !share(x.tiles, y.lane) && !share(x.lane, y.tiles)) continue;
            for(int t = 1; t <= T; t++){
                MPConstraint c = solver.makeConstraint(-inf, 1);
                c.setCoefficient(n.get(x)[t], 1);
                c.setCoefficient(n.get(y)[t], 1);
            }
        }
        for(String r : res){
            boolean item = Vars.content.item(r) != null;
            MPVariable prev = null;
            for(int t = 1; t <= T; t++){
                //(made at least as fast as taken; an item's rest into the core)
                MPVariable in = item ? solver.makeNumVar(0, inf, "") : null;
                MPConstraint made = solver.makeConstraint(0, item ? 0 : inf);
                for(Unit u : units){
                    float rate = u.rate.getOrDefault(r, 0f);
                    if(rate != 0f) made.setCoefficient(a.get(u)[t], rate);
                }
                if(!item) continue;
                made.setCoefficient(in, -1);
                //(the stock: before + come in + dug - spent - burnt, at most what the core holds)
                MPVariable s = solver.makeNumVar(0, st.coreCap, ""), burnt = solver.makeNumVar(0, inf, "");
                float before = t == 1 ? st.stock.getOrDefault(r, 0f) : 0f;
                MPConstraint bal = solver.makeConstraint(before, before);
                bal.setCoefficient(s, 1);
                if(prev != null) bal.setCoefficient(prev, -1);
                bal.setCoefficient(burnt, 1);
                bal.setCoefficient(in, -L[t]);
                if(dig.containsKey(r)) bal.setCoefficient(dig.get(r)[t], -st.dig.get(r));
                for(Unit u : units){
                    float c = u.cost.getOrDefault(r, 0f);
                    if(c == 0f) continue;
                    bal.setCoefficient(b.get(u)[t], c);
                    bal.setCoefficient(d.get(u)[t], -c * refund);
                }
                prev = s;
            }
        }
        obj.setMinimization();
        solver.setTimeLimit((long)(think * 1000));
        MPSolver.ResultStatus status = solver.solve();
        Map<Unit, Integer> out = new LinkedHashMap<>();
        float horizon = 0f;
        for(int t = 1; t <= T; t++) horizon += L[t];
        System.out.println(String.format("schedule: %s, %d units, %d spans (%.0f s to %.0f s, %.0f s in all)", status.toString().toLowerCase(), units.size(), T, L[1], L[T], horizon));
        if(status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) return out;
        for(Unit u : units){
            int add = (int)Math.round(b.get(u)[1].solutionValue()), drop = (int)Math.round(d.get(u)[1].solutionValue());
            //(to build some and take some down of one unit in a span is the same as the difference alone, for less)
            if(add > 0 && drop > 0){
                System.out.println("  (" + u.key + ": built " + add + " and taken down " + drop + " in one span: only the difference)");
                int both = Math.min(add, drop);
                add -= both;
                drop -= both;
            }
            if(add > 0) out.put(u, add);
            if(drop > 0) takeDown.put(u, drop);
            if(Boolean.getBoolean("bp.debug") && (add > 0 || drop > 0)) System.out.println(String.format("    %s: had %d, built %d, taken down %d, then %d; works %.2f in span 1, %.2f in span 2", u.key, ledger.committed(u.key), add, drop, Math.round(n.get(u)[1].solutionValue()), a.get(u)[1].solutionValue(), T > 1 ? a.get(u)[2].solutionValue() : 0));
        }
        float end = 0f;
        StringBuilder later = new StringBuilder();
        for(int t = 1; t <= T; t++){
            float left = 0f;
            for(var e : classes.entrySet()) left += clsSeconds.get(e.getKey()) * (need.get(e.getKey()) - (float)part.get(e.getKey())[t].solutionValue());
            end += L[t];
            later.append(String.format(" %.0fs:%.0f%%", end, 100f * (1f - left / Math.max(1f, total))));
        }
        System.out.println("  built by then (of the building):" + later);
        if(Boolean.getBoolean("bp.debug")){
            Map<String, Float> want = new TreeMap<>();
            for(var c : classes.entrySet()){
                float got = (float)part.get(c.getKey())[T].solutionValue();
                if(got >= need.get(c.getKey()) - 0.5f) continue;
                Unit u = c.getValue().get(0);
                System.out.println("    not by the end: " + c.getKey() + " " + Math.round(got) + "/" + need.get(c.getKey()) + " cost " + u.cost);
                for(var e : u.cost.entrySet()) want.merge(e.getKey(), e.getValue() * (need.get(c.getKey()) - got), Float::sum);
            }
            System.out.println("    their cost: " + want);
            for(int t : new int[]{3, 6, 9}){
                Map<String, Float> made = new TreeMap<>();
                for(Unit u : units) for(var e : u.rate.entrySet()) if(Vars.content.item(e.getKey()) != null && e.getValue() > 0f && a.get(u)[t].solutionValue() > 1e-6) made.merge(e.getKey(), e.getValue() * (float)a.get(u)[t].solutionValue(), Float::sum);
                System.out.println("    made in span " + t + ", a second: " + made);
            }
            List<String> ways = new ArrayList<>();
            for(Unit u : units) if(!u.required){
                int most = 0;
                for(int t = 1; t <= T; t++) most = Math.max(most, (int)Math.round(n.get(u)[t].solutionValue()));
                if(most > 0) ways.add(most + " " + u.key);
            }
            System.out.println("    ways up taken: " + ways);
        }
        for(var e : dig.entrySet()) if(e.getValue()[1].solutionValue() > 1e-3) digFirst.put(e.getKey(), (float)e.getValue()[1].solutionValue());
        //(what the plan expects into the core in the first span, a second: made beyond taken, and dug)
        for(Unit u : units) for(var e : u.rate.entrySet()) if(Vars.content.item(e.getKey()) != null) expected.merge(e.getKey(), e.getValue() * (float)a.get(u)[1].solutionValue(), Float::sum);
        for(var e : dig.entrySet()) expected.merge(e.getKey(), st.dig.get(e.getKey()) * (float)e.getValue()[1].solutionValue() / L[1], Float::sum);
        expected.values().removeIf(v -> Math.abs(v) < 1e-3f);
        System.out.println("  expected into the core, a second: " + Recipe.fmt(new LinkedHashMap<>(expected)));
        return out;
    }

    /** (A check: what can ever be had, from the stock and digging, unit by unit as what it costs and takes is had.) */
    void reach(State st){
        Set<String> had = new TreeSet<>(st.dig.keySet());
        for(var e : st.stock.entrySet()) if(e.getValue() > 0f) had.add(e.getKey());
        Set<Unit> can = new LinkedHashSet<>();
        boolean grew = true;
        while(grew){
            grew = false;
            for(Unit u : units){
                if(can.contains(u)) continue;
                boolean ok = had.containsAll(u.cost.keySet());
                for(var e : u.rate.entrySet()) if(e.getValue() < 0f && !had.contains(e.getKey())) ok = false;
                if(u.power < 0f && !had.contains("power")) ok = false;
                if(!ok) continue;
                can.add(u);
                grew = true;
                for(var e : u.rate.entrySet()) if(e.getValue() > 0f) had.add(e.getKey());
                if(u.power > 0f) had.add("power");
            }
        }
        System.out.println("    can be had: " + had);
        Map<String, List<String>> why = new TreeMap<>();
        Set<String> canCls = new HashSet<>();
        for(Unit u : can) canCls.add(u.cls);
        for(Unit u : units) if(need.getOrDefault(u.cls, 0) > 0 && !canCls.contains(u.cls) && u == firstOf(u.cls)){
            List<String> lack = new ArrayList<>();
            for(String k : u.cost.keySet()) if(!had.contains(k)) lack.add("cost " + k);
            for(var e : u.rate.entrySet()) if(e.getValue() < 0f && !had.contains(e.getKey())) lack.add("takes " + e.getKey());
            why.computeIfAbsent(String.join(",", lack), k -> new ArrayList<>()).add(u.key);
        }
        for(var e : why.entrySet()) System.out.println("    never, for want of " + e.getKey() + ": " + e.getValue().size() + " " + e.getValue().subList(0, Math.min(4, e.getValue().size())));
        //(and what makes what is wanting, and what each of those wants)
        for(String k : List.of("oxide", "thorium", "carbide", "surge-alloy", "phase-fabric", "ozone", "slag", "hydrogen", "nitrogen", "cyanogen", "arkycite", "heat")) if(!had.contains(k)){
            for(Unit u : units) if(u.rate.getOrDefault(k, 0f) > 0f){
                List<String> lack = new ArrayList<>();
                for(String c : u.cost.keySet()) if(!had.contains(c)) lack.add("cost " + c);
                for(var e : u.rate.entrySet()) if(e.getValue() < 0f && !had.contains(e.getKey())) lack.add("takes " + e.getKey());
                if(u.power < 0f && !had.contains("power")) lack.add("power");
                System.out.println("      " + k + " from " + u.key + ": wants " + lack);
                break;
            }
        }
    }

    Unit firstOf(String cls){
        for(Unit u : units) if(cls.equals(u.cls)) return u;
        return null;
    }

    final Map<String, Float> digFirst = new LinkedHashMap<>();
    /** What the first span is expected to bring into the core, a second, by item. */
    final Map<String, Float> expected = new TreeMap<>();
    /** Ways up the first span takes down: how many of each. */
    final Map<Unit, Integer> takeDown = new LinkedHashMap<>();

    static boolean share(int[] a, int[] b){
        for(int x : a) for(int y : b) if(x == y) return true;
        return false;
    }

    // ------------------------------------------------------------------ laid out among what stands

    /** A part of a body, by the thing committed it is of and its place among its blocks. */
    final Map<Body.Part, int[]> whose = new IdentityHashMap<>();

    /**
     * The step laid out among what stands: what is committed fixed (its lines' pieces among the router's), the step's
     * machines placed, its machines on their places put there, lines laid from makers with some to spare to takers short,
     * and to the core what is made beyond; power to every new block that uses it. Committed to the ledger.
     */
    boolean layOut(String dataDir, String world, Map<Unit, Integer> step, long seed){
        Problem prob = Problem.parse("world " + world + "\n");
        prob.name = "grow-" + ledger.step;
        //(what is made beyond what is taken goes to the core: the items the step's machines make, and what stands has
        //to spare)
        Gen g = new Gen(prob, dataDir);
        g.balance = plan;
        //(what can be had now: in the core, dug, or made by what is committed)
        Set<String> had = new HashSet<>(state.dig.keySet());
        for(var e : state.stock.entrySet()) if(e.getValue() >= 1f) had.add(e.getKey());
        for(Inst i : ledger.insts) if(!i.removed) for(Object[] p : i.parts){
            Recipe r = p[4] == null ? null : recipes.get((String)p[4]);
            if(r == null) continue;
            had.addAll(r.out.keySet());
            had.addAll(r.perYield.keySet());
            had.addAll(r.fixedOut.keySet());
        }
        g.board.lacking = new HashSet<>();
        for(Item item : Vars.content.items()) if(!had.contains(item.name)) g.board.lacking.add(item.name);
        int id = 0;
        for(Spec.Place p : prob.fixed){
            Block bl = Vars.content.block(p.block());
            Body body = new Body(id++, p.block(), true);
            body.given = true;
            body.standing = true;
            body.add(bl, 0, 0, p.rotation(), null, 1f, p.config());
            body.put(Geo.lo(p.x(), bl.size), Geo.lo(p.y(), bl.size), 0);
            g.bodies.add(body);
            if(Board.isCore(body.main())){
                g.core = body.main();
                whose.put(body.main(), new int[]{0, 0});
            }
        }
        //what is committed, fixed
        Map<Integer, Map<String, Float>> outOf = new HashMap<>(), into = new HashMap<>();
        //(what goes to the core is what is left: a maker hands it to whatever line will take it, so what it sends the core
        //is still to be had by a taker that lacks it, a new line from it taking its share)
        for(Inst i : ledger.insts) if(!i.removed) for(Object[] f : i.flows){
            if((Integer)f[3] != 0) outOf.computeIfAbsent((Integer)f[1] * 1000 + (Integer)f[2], k -> new HashMap<>()).merge((String)f[0], (Float)f[5], Float::sum);
            into.computeIfAbsent((Integer)f[3] * 1000 + (Integer)f[4], k -> new HashMap<>()).merge((String)f[0], (Float)f[5], Float::sum);
        }
        for(Inst i : ledger.insts){
            if(i.removed) continue;
            Body body = new Body(100000 + i.id, i.unit == null ? i.kind : i.unit, true);
            for(int k = 0; k < i.parts.size(); k++){
                Object[] p = i.parts.get(k);
                Block bl = Vars.content.block((String)p[0]);
                Recipe r = p[4] == null ? null : recipes.get((String)p[4]);
                Body.Part part = body.add(bl, (Integer)p[1], (Integer)p[2], (Integer)p[3], r, (Float)p[5], (String)p[7]);
                part.yield = (Float)p[6];
                whose.put(part, new int[]{i.id, k});
            }
            for(Object[] q : i.pieces){
                Block bl = Vars.content.block((String)q[1]);
                int x = (Integer)q[2], y = (Integer)q[3];
                Body.Part part = body.add(bl, Geo.lo(x, bl.size), Geo.lo(y, bl.size), (Integer)q[4], null, 1f, null);
                Piece pc = new Piece(Kind.valueOf((String)q[0]), bl, x, y, (Integer)q[4], -1 - i.id, (String)q[8]);
                pc.from = (Integer)q[5];
                pc.outs = (Integer)q[6];
                pc.role = (Integer)q[7];
                part.piece = pc;
            }
            if(body.parts.isEmpty()) continue;
            body.standing = true;
            body.put(0, 0, 0);
            g.bodies.add(body);
            //(what each of its parts has to spare, still lacks)
            for(int k = 0; k < body.parts.size(); k++){
                Body.Part p = body.parts.get(k);
                if(p.recipe == null) continue;
                Map<String, Float> sp = new HashMap<>(), lk = new HashMap<>();
                Map<String, Float> o = outOf.getOrDefault(i.id * 1000 + k, Map.of()), in = into.getOrDefault(i.id * 1000 + k, Map.of());
                Set<String> res = new HashSet<>(p.recipe.at(p.yield).out.keySet());
                res.addAll(p.recipe.at(p.yield).in.keySet());
                res.addAll(p.recipe.fixedIn.keySet());
                res.addAll(p.recipe.fixedOut.keySet());
                for(String r : res){
                    sp.put(r, Math.max(0f, p.makes(r) - o.getOrDefault(r, 0f)));
                    lk.put(r, Math.max(0f, p.takes(r) - in.getOrDefault(r, 0f)));
                }
                g.spare.put(p, sp);
                g.lacks.put(p, lk);
            }
        }
        //(the final form's places not yet standing nor in this step: held, no line or block of the step on them)
        Body hold = new Body(id++, "places held", true);
        hold.standing = true;
        //(a machine on ground the step puts there itself may stand on one: the schedule has them one at a time)
        Set<Integer> stepSites = new HashSet<>();
        for(Unit u : step.keySet()) for(int t : u.tiles) stepSites.add(t);
        for(Unit u : units){
            if(u.site == null || !u.required || ledger.committed(u.key) > 0 || step.containsKey(u)) continue;
            for(int t : u.tiles) if(!stepSites.contains(t)) hold.holdLocal.add(new int[]{t % g.board.w, t / g.board.w, t % g.board.w, t / g.board.w});
        }
        hold.put(0, 0, 0);
        if(!hold.holdLocal.isEmpty()) g.bodies.add(hold);
        //(what is wrong already, by the game's rules, before the step: the step is to make nothing worse)
        int before = 0;
        if(!ledger.insts.isEmpty()){
            Spec base = new Spec();
            base.name = prob.name + "-before";
            base.world = world;
            for(Body b : g.bodies) if(!b.given) for(Body.Part p : b.parts) base.places.add(new Spec.Place(p.block.name, p.ax(), p.ay(), p.rot, p.block.configurable ? p.config : null));
            base.places.addAll(0, prob.fixed);
            before = g.verify(base, false, false);
        }
        //the step's own
        Map<Body, Unit> fresh = new LinkedHashMap<>();
        int nid = ledger.nextId();
        for(var e : step.entrySet()) for(int k = 0; k < e.getValue(); k++){
            Body b = body(g, e.getKey(), 200000 + nid);
            fresh.put(b, e.getKey());
            g.bodies.add(b);
            nid++;
        }
        //(to the core: every item made beyond what is taken)
        Map<String, Float> made = new HashMap<>();
        for(Body b : g.bodies) for(Body.Part p : b.parts){
            if(p.recipe == null) continue;
            for(String r : p.recipe.at(p.yield).out.keySet()) if(Vars.content.item(r) != null) made.merge(r, g.supply(p, r), Float::sum);
            for(String r : p.recipe.fixedOut.keySet()) if(Vars.content.item(r) != null) made.merge(r, g.supply(p, r), Float::sum);
            for(String r : p.recipe.at(p.yield).in.keySet()) if(Vars.content.item(r) != null) made.merge(r, -g.demand(p, r), Float::sum);
        }
        prob.goals.clear();
        for(var e : made.entrySet()) if(e.getValue() > 1e-3f){
            prob.goals.put(e.getKey(), e.getValue());
            g.board.goals.add(e.getKey());
        }
        Balance.Result toCore = new Balance.Result();
        toCore.solved = true;
        toCore.machines.addAll(plan.machines);
        toCore.delivered.putAll(prob.goals);
        g.balance = toCore;
        g.groups();
        System.out.println(String.format("step %d: %d new bodies among %d committed; %d things between them; to the core %s", ledger.step, fresh.size(), ledger.insts.size(), g.groups.size(), Recipe.fmt(new LinkedHashMap<>(prob.goals))));
        Spec s = g.layOut(seed);
        s.name = prob.name;
        s.world = world;
        //(a line between what stands only, that could not be laid: not laid now, tried again at a later step; what is
        //new is not held up by it)
        List<Router.Net> notNow = new ArrayList<>();
        for(Router.Net n : g.bestNets){
            if(n.unreached.isEmpty()) continue;
            boolean standingOnly = n.from.body.standing;
            for(Body.Part t : n.to) if(!t.body.standing) standingOnly = false;
            if(standingOnly) notNow.add(n);
        }
        if(!notNow.isEmpty()){
            Set<String> gone = new HashSet<>();
            for(Router.Net n : notNow) for(Piece q : n.pieces) if(q.block != null) gone.add(q.block.name + "@" + q.x + "," + q.y);
            s.places.removeIf(p -> gone.contains(p.block() + "@" + p.x() + "," + p.y()));
            g.bestNets.removeAll(notNow);
            g.problems -= notNow.stream().mapToInt(n -> n.unreached.size()).sum();
            System.out.println("  not laid now (between what stands): " + notNow);
        }
        int bad = g.verify(s, false);
        System.out.println(String.format("  laid out; %s", bad == 0 ? "clean by the game's rules" : bad + " things wrong by the game's rules" + (before > 0 ? " (" + before + " before it)" : "")));
        if(g.problems > 0 || bad > before){
            //(what of the step stands where it may not, or a line to or from it could not be laid: left out, the rest
            //laid out again)
            Set<Body> trouble = new HashSet<>(g.misplacedKept);
            for(Router.Net n : g.bestNets) if(!n.unreached.isEmpty()){
                trouble.add(n.from.body);
                for(Body.Part t : n.unreached) trouble.add(t.body);
            }
            for(Body b : fresh.keySet()) if(trouble.contains(b)) drop.merge(fresh.get(b), 1, Integer::sum);
            if(drop.isEmpty()) g.verify(s, true);
            return false;
        }
        //committed: the step's bodies, its lines with what they carry, its nodes
        for(Body b : fresh.keySet()){
            Inst i = new Inst();
            i.id = b.id - 200000;
            i.kind = b.fixed ? "site" : b.main().recipe == null ? "single" : "machine";
            i.unit = fresh.get(b).key;
            for(int k = 0; k < b.parts.size(); k++){
                Body.Part p = b.parts.get(k);
                i.parts.add(new Object[]{p.block.name, p.x0, p.y0, p.rot, p.recipe == null ? null : p.recipe.name, p.work, p.yield, p.block.configurable ? p.config : null});
                whose.put(p, new int[]{i.id, k});
            }
            ledger.insts.add(i);
        }
        int lid = ledger.nextId();
        Map<Integer, Inst> ofNet = new HashMap<>();
        for(Router.Net net : g.bestNets){
            Inst i = new Inst();
            i.id = lid++;
            i.kind = "line";
            ofNet.put(net.id, i);
            for(Piece q : net.pieces) if(q.block != null) i.pieces.add(new Object[]{q.kind.name(), q.block.name, q.x, q.y, q.rot, q.from, q.outs, q.role, net.res});
            int[] from = whose.get(net.from);
            for(int k = 0; k < net.to.size(); k++){
                int[] to = whose.get(net.to.get(k));
                if(from == null || to == null) continue;
                i.flows.add(new Object[]{net.res, from[0], from[1], to[0], to[1], k < net.flows.size() ? net.flows.get(k) : 0f});
            }
            ledger.insts.add(i);
        }
        //(a line that joins another runs on into it)
        for(Router.Net net : g.bestNets) for(Piece q : net.joins.values()){
            Inst joined = ofNet.get(q.net);
            if(joined != null) ofNet.get(net.id).via.add(joined.id);
        }
        if(!g.bestPower.isEmpty()){
            Inst i = new Inst();
            i.id = lid;
            i.kind = "nodes";
            for(Piece q : g.bestPower) i.parts.add(new Object[]{q.block.name, Geo.lo(q.x, q.block.size), Geo.lo(q.y, q.block.size), q.rot, null, 1f, 1f, null});
            ledger.insts.add(i);
        }
        return true;
    }

    final Map<String, Recipe> recipes = new HashMap<>();
    State state;

    /** Committed things not begun (no block of them stands): given back to the plan, planned again with the rest. */
    void release(State st){
        Set<Inst> back = new LinkedHashSet<>();
        for(Inst i : ledger.insts) if(!i.removed && !i.built && !st.begun.contains(i.id)) back.add(i);
        //(not a line that a line kept runs on into, nor what it joins in turn)
        boolean more = true;
        while(more){
            more = false;
            for(Inst i : ledger.insts){
                if(back.contains(i) || i.removed) continue;
                for(int v : i.via){
                    Inst into = ledger.get(v);
                    if(into != null && back.remove(into)) more = true;
                }
            }
        }
        if(back.isEmpty()) return;
        ledger.insts.removeAll(back);
        System.out.println("  planned again: " + back.size() + " things not begun");
    }

    /** Of a step not laid out clean, what to leave out: so many of each unit. */
    final Map<Unit, Integer> drop = new LinkedHashMap<>();

    /** What the step takes down: so many of each unit (the last committed first), and the lines to and from them. */
    final List<Inst> removed = new ArrayList<>();

    void takeDown(){
        Set<Integer> gone = new HashSet<>();
        for(var e : takeDown.entrySet()){
            int k = e.getValue();
            for(int i = ledger.insts.size() - 1; i >= 0 && k > 0; i--){
                Inst in = ledger.insts.get(i);
                if(in.removed || !e.getKey().key.equals(in.unit)) continue;
                in.removed = true;
                removed.add(in);
                gone.add(in.id);
                k--;
            }
        }
        //(and the lines to and from them, and the lines that run on into a line taken down)
        boolean more = true;
        while(more){
            more = false;
            for(Inst in : ledger.insts){
                if(in.removed || !in.kind.equals("line")) continue;
                boolean touches = false;
                for(Object[] f : in.flows) if(gone.contains((Integer)f[1]) || gone.contains((Integer)f[3])) touches = true;
                for(int v : in.via) if(gone.contains(v)) touches = true;
                if(!touches) continue;
                in.removed = true;
                removed.add(in);
                gone.add(in.id);
                more = true;
            }
        }
    }

    /** What the core unit is to build, thing by thing, block by block (centre and turn, as the game puts it). */
    void orders(String file, int from){
        StringBuilder s = new StringBuilder("# what to take down, then what to build next, in order\n");
        for(Inst i : removed){
            s.append("remove ").append(i.id).append(' ').append(i.kind).append(' ').append(i.unit == null ? "-" : i.unit.replace(' ', '|')).append('\n');
            for(Object[] p : i.parts){
                Block bl = Vars.content.block((String)p[0]);
                s.append(String.format(Locale.ROOT, "block %s %d %d %d -%n", p[0], Geo.at((Integer)p[1], bl.size), Geo.at((Integer)p[2], bl.size), p[3]));
            }
            for(Object[] q : i.pieces) s.append(String.format(Locale.ROOT, "block %s %d %d %d -%n", q[1], q[2], q[3], q[4]));
        }
        for(Inst i : ledger.insts){
            if(i.id < from || i.removed) continue;
            s.append("inst ").append(i.id).append(' ').append(i.kind).append(' ').append(i.unit == null ? "-" : i.unit.replace(' ', '|')).append('\n');
            for(Object[] p : i.parts){
                Block bl = Vars.content.block((String)p[0]);
                s.append(String.format(Locale.ROOT, "block %s %d %d %d %s%n", p[0], Geo.at((Integer)p[1], bl.size), Geo.at((Integer)p[2], bl.size), p[3], p[7] == null ? "-" : p[7]));
            }
            for(Object[] q : i.pieces) s.append(String.format(Locale.ROOT, "block %s %d %d %d -%n", q[1], q[2], q[3], q[4]));
        }
        for(var e : digFirst.entrySet()) s.append(String.format(Locale.ROOT, "dig %s %.1f%n", e.getKey(), e.getValue()));
        new Fi(file).writeString(s.toString());
    }

    public static void main(String[] args){
        Game.boot(args[0]);
        String dataDir = args[0], world = args[1], planFile = args[2], ledgerFile = args[3], stateFile = args[4], ordersFile = args[5];
        int spans = args.length > 6 ? Integer.parseInt(args[6]) : 8;
        float spanSeconds = args.length > 7 ? Float.parseFloat(args[7]) : 90f;
        float think = args.length > 8 ? Float.parseFloat(args[8]) : 30f;
        long t0 = System.nanoTime();
        Grow grow = new Grow();
        for(Recipe r : Recipes.all()) grow.recipes.put(r.name, r);
        grow.ledger = Ledger.read(ledgerFile);
        grow.readShapes(new Fi(ledgerFile).sibling("shapes.txt"));
        grow.readPlan(planFile, grow.recipes);
        State st = State.read(stateFile);
        grow.state = st;
        for(Inst i : grow.ledger.insts) if(st.built.contains(i.id)) i.built = true;
        grow.release(st);
        //(units measured as bodies, on the map)
        Gen probe = new Gen(Problem.parse("world " + world + "\n"), dataDir);
        probe.balance = grow.plan;
        grow.ways(probe);
        grow.measure(probe);
        grow.writeShapes();
        grow.ledger.step++;
        grow.occupied(probe.board.w);
        if(Boolean.getBoolean("bp.debug")) grow.reach(st);
        Map<Unit, Integer> step = grow.next(st, spans, spanSeconds, think);
        System.out.println(String.format("step %d: %s; digging first %s (%.1f s)", grow.ledger.step, step, grow.digFirst, (System.nanoTime() - t0) / 1e9));
        int from = grow.ledger.nextId();
        grow.takeDown();
        if(!grow.removed.isEmpty()) System.out.println("  taken down: " + grow.removed.stream().map(i -> i.unit == null ? i.kind + " " + i.id : i.unit).toList());
        for(int round = 0; !step.isEmpty(); round++){
            grow.drop.clear();
            grow.whose.clear();
            if(grow.layOut(dataDir, world, step, grow.ledger.step)) break;
            if(grow.drop.isEmpty() || round >= 3){
                System.out.println("  NOT laid out clean: committed nothing");
                break;
            }
            System.out.println("  left out: " + grow.drop.entrySet().stream().map(e -> e.getValue() + " " + e.getKey().key).toList());
            for(Unit u : grow.drop.keySet()) if(u.site != null) grow.ledger.avoid.add(u.key);
            for(var e : grow.drop.entrySet()) step.merge(e.getKey(), -e.getValue(), Integer::sum);
            step.values().removeIf(v -> v <= 0);
        }
        grow.ledger.write(ledgerFile);
        grow.orders(ordersFile, from);
        System.out.println(String.format("grow step %d done in %.1f s", grow.ledger.step, (System.nanoTime() - t0) / 1e9));
        System.exit(0);
    }
}
