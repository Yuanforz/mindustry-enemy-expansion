package expansion.econ;

import java.util.*;

/**
 * Works out a plant that makes a unit at a wanted rate, and the order to build it in that gets there soonest.
 *
 * The plant is worked back from the unit: so many factories for it, what they take, what makes that, down to drills on
 * the best places the map has, and then the power for all of it. The order is found by rollout: at each step every machine
 * that could come next is tried, the rest is built in a plain order after it, and the one after which the whole is done
 * soonest is taken. Extra drills and turbines beyond the plant are tried the same way, so that building more early on is
 * chosen where it pays.
 */
public final class Planner{
    public final Catalog cat;
    public final Field field;
    /** The places not yet given to a machine. */
    final List<Field.Site> free;
    /** The map's idle machines not yet taken up by this plan (the field's list stays whole for a plan made again). */
    final List<Machine> revivable;
    /** Machines built from nowhere in particular stand this far from the core (tiles). */
    public float baseDistance = 8f;
    /** How many machines beyond the plant may be tried. */
    public int extras = 16;
    public float limit = 3 * 3600f;
    /** For telling how it went. */
    public final StringBuilder notes = new StringBuilder();

    public Planner(Catalog cat, Field field){
        this.cat = cat;
        this.field = field;
        free = new ArrayList<>(field.sites);
        revivable = new ArrayList<>(field.revivable);
    }

    // ---------------------------------------------------------------- the plant

    /** Machines to add to what stands so that the unit comes out at this rate (units a second) with every machine fed and powered. */
    public List<Machine> balance(int unit, float rate){
        wantRate = rate;
        chains = new Chains(cat, field);
        List<Machine> plan = new ArrayList<>();
        //an item aimed at: that much a second for the core, besides everything the machines take
        demand = cat.zeros();
        if(cat.get(unit).kind != Res.Kind.unit){
            demand[unit] = rate;
            return balanceRest(plan);
        }
        //the unit itself, then the units it is made from
        Map<Integer, Float> unitsWanted = new HashMap<>();
        unitsWanted.put(unit, rate);
        Deque<Integer> todo = new ArrayDeque<>(List.of(unit));
        while(!todo.isEmpty()){
            int u = todo.pop();
            float want = unitsWanted.get(u);
            Recipe r = maker(u);
            if(r == null){
                notes.append("nothing that may be built makes ").append(cat.get(u).name).append('\n');
                return plan;
            }
            float have = 0f;
            for(Machine m : field.existing) if(m.recipe == r) have += 1f / r.unitSeconds;
            int count = (int)Math.ceil(Math.max(0f, want - have) * r.unitSeconds - 1e-4f);
            for(int i = 0; i < count; i++) plan.add(machine(r, null));
            if(r.unitIn >= 0){
                unitsWanted.merge(r.unitIn, (count + have * r.unitSeconds) / r.unitSeconds, Float::sum);
                todo.push(r.unitIn);
            }
        }

        return balanceRest(plan);
    }

    /** What is wanted for the core besides what machines take, a second, by {@link Res#id}. */
    float[] demand;
    /** What each thing costs to make, the cheapest way: for choosing between recipes and modules. */
    public Chains chains;

    List<Machine> balanceRest(List<Machine> plan){
        //what everything takes, until there is enough of all of it
        for(int guard = 0; guard < 400; guard++){
            List<Machine> all = new ArrayList<>(field.existing);
            all.addAll(plan);
            float[] want = demand.clone(), have = cat.zeros();
            float powerWant = 0f, powerHave = 0f;
            for(Machine m : all){
                for(int k = 0; k < cat.size(); k++){
                    want[k] += m.recipe.in[k];
                    have[k] += m.out(k);
                }
                powerWant += m.recipe.powerUse;
                powerHave += m.powerOut();
            }
            //the thing most short of, as a share of what is wanted
            int worst = -1;
            float worstShare = 0f;
            for(int k = 0; k < cat.size(); k++){
                if(cat.get(k).kind == Res.Kind.unit || want[k] <= have[k] + 1e-4f) continue;
                float share = (want[k] - have[k]) / want[k];
                if(share > worstShare){
                    worstShare = share;
                    worst = k;
                }
            }
            Machine add = null;
            if(worst >= 0){
                sofar = plan;
                add = maker(worst, want[worst] - have[worst], powerWant > powerHave);
                if(add == null){
                    notes.append("no way to make enough ").append(cat.get(worst).name).append(" (").append(String.format("%.2f", want[worst] - have[worst])).append("/s short)\n");
                    return plan;
                }
            }else if(powerWant > powerHave + 1e-3f){
                add = powerMaker();
                if(add == null){
                    notes.append(String.format("not enough vents for the power: %.0f/s wanted, %.0f/s made\n", powerWant, powerHave));
                    return plan;
                }
            }else{
                break;
            }
            plan.add(add);
        }
        //what building it all costs must come from somewhere: the core's stock, digging by hand, or a maker whose surplus goes
        //to the core. A thing it can get none of is given a maker (one of the map's idle ones first)
        for(int k = 0; k < cat.size(); k++){
            Res r = cat.get(k);
            if(r.kind != Res.Kind.item || !r.storable) continue;
            float cost = 0f;
            for(Machine m : plan) cost += m.recipe.cost[k];
            if(cost <= field.stock[k] || field.handRate(cat, k) > 0f) continue;
            boolean made = false;
            for(Machine m : field.existing) if(m.out(k) > 1e-4f) made = true;
            for(Machine m : plan) if(m.out(k) > 1e-4f) made = true;
            if(made) continue;
            sofar = plan;
            Machine add = maker(k, 0.1f, false);
            if(add == null){
                notes.append(String.format("building it all costs %.0f %s, the core has %.0f, and nothing could make it\n", cost, r.name, field.stock[k]));
                continue;
            }
            plan.add(add);
            notes.append(String.format("%s for building: %s\n", r.name, add.recipe.name));
        }
        //a liquid is led only so far: a machine on the ground that takes one with nothing making it near gets a maker of
        //its own on the nearest free vent (a turbine gives power too)
        for(int k = 0; k < cat.size(); k++){
            if(cat.get(k).kind != Res.Kind.liquid) continue;
            List<Machine> all = new ArrayList<>(field.existing);
            all.addAll(plan);
            for(Machine c : new ArrayList<>(plan)){
                float need = c.recipe.in[k] + (c.boosted && c.recipe.boostRes == k ? c.recipe.boostUse : 0f);
                if(c.recipe.ground == Recipe.Ground.none || need <= 0f) continue;
                //what is made near enough (counting makers already given to others near it as shared alike)
                for(int guard = 0; guard < 4; guard++){
                    float near = 0f;
                    for(Machine m : all) if(m != c && m.out(k) > 0f && m.recipe.ground != Recipe.Ground.none && Math.hypot(m.x - c.x, m.y - c.y) <= localLiquid) near += m.out(k);
                    if(near >= need - 1e-3f) break;
                    sofar = List.of(c);
                    Machine add = maker(k, need - near, false);
                    sofar = plan;
                    if(add == null) break;
                    if(Math.hypot(add.x - c.x, add.y - c.y) > localLiquid * 1.5f){
                        free.add(0, new Field.Site(add.recipe.ground, add.x, add.y, add.rotation, add.item, add.factor, add.distance, new int[0]));
                        break;
                    }
                    plan.add(add);
                    all.add(add);
                    notes.append(String.format("%s at %d,%d gets %s from its own %s at %d,%d\n", c.recipe.name, c.x, c.y, cat.get(k).name, add.recipe.name, add.x, add.y));
                }
            }
        }
        return plan;
    }

    /**
     * A maker of a thing on the free place nearest a machine that takes it: for when the layout found no way to feed that
     * machine from what the plan (or the map) makes. Null if no place gives it.
     */
    public Machine makerNear(int res, Machine taker){
        return makerNear(res, taker, taker.x, taker.y, true);
    }

    /** The same, near a place; taken only if asked (else it is only looked at, for what it would cost). */
    public Machine makerNear(int res, Machine taker, int tx, int ty, boolean take){
        Field.Site best = null;
        Recipe bestRecipe = null;
        double bestD = Double.MAX_VALUE;
        for(Recipe r : cat.recipes){
            if(r.ground == Recipe.Ground.none) continue;
            boolean gives = r.out[res] > 0f || r.perFactor[res] > 0f;
            if(!gives) continue;
            for(Field.Site s : free){
                if(s.ground != r.ground || (r.perFactor[res] > 0f && s.item != res)) continue;
                double d = Math.hypot(s.x - tx, s.y - ty) - (r.perFactor[res] > 0f ? r.perFactor[res] * s.factor : r.out[res]) * 2;
                if(d < bestD){
                    bestD = d;
                    best = s;
                    bestRecipe = r;
                }
            }
        }
        if(best == null) return null;
        if(!take) return machine(bestRecipe, best);
        free.remove(best);
        notes.append(String.format("%s at %d,%d could not be fed %s: a %s of its own at %d,%d\n", taker.recipe.name, tx, ty, cat.get(res).name, bestRecipe.name, best.x, best.y));
        return machine(bestRecipe, best);
    }

    /** How far a liquid is worth leading (tiles); beyond it a machine is given a maker of its own. */
    public float localLiquid = 25f;

    /** The recipe that makes this unit. */
    Recipe maker(int unit){
        for(Recipe r : cat.recipes) if(r.unitOut == unit) return r;
        return null;
    }

    /** A machine that makes more of a thing: a crafter, or a machine on the best free place that gives it. */
    Machine maker(int res, float short_, boolean powerShort){
        //water comes from vents, as power does: where power is short too a turbine gives both, else a condenser gives far more water
        if(cat.get(res).kind == Res.Kind.liquid){
            Recipe best = null;
            for(Recipe r : cat.recipes){
                if(r.out[res] <= 0f) continue;
                if(r.ground == Recipe.Ground.vent){
                    boolean turbine = r.powerOut > 0f;
                    if(best == null || (powerShort || short_ <= r.out[res] ? turbine : !turbine)) best = r;
                }else if(best == null){
                    best = r;
                }
            }
            if(best == null) return null;
            if(best.ground == Recipe.Ground.vent){
                //a liquid is led only so far: the vent nearest a machine that takes it, not the one nearest the core
                Field.Site site = nearestVent(res);
                if(site == null) return null;
                free.remove(site);
                return machine(best, site);
            }
            return machine(best, null);
        }
        //a machine of the map's that stands idle: taken up, it costs only its lines
        for(Machine old : revivable){
            if(old.out(res) <= 1e-4f) continue;
            revivable.remove(old);
            Machine m = new Machine(old.recipe, old.item, old.factor, old.x, old.y, old.distance, new int[cat.size()], false, false);
            m.rotation = old.rotation;
            m.revived = true;
            notes.append(String.format("the map's idle %s at %d,%d taken up\n", old.recipe.name, old.x, old.y));
            return m;
        }
        //a crafter (or module) that makes it, the one whose whole chain is cheapest; none at all if a drill is cheaper
        Recipe crafter = chains == null ? null : chains.bestCrafter(res);
        if(chains == null) for(Recipe r : cat.recipes) if(r.ground == Recipe.Ground.none && r.out[res] > 0f){ crafter = r; break; }
        if(crafter != null) return machine(crafter, null);
        //else a machine on the ground: the best place for any recipe that can dig it
        Field.Site bestSite = null;
        Recipe bestRecipe = null;
        for(Recipe r : cat.recipes){
            if(r.ground == Recipe.Ground.none || r.perFactor[res] <= 0f) continue;
            Field.Site site = peek(r.ground, res);
            if(site != null && (bestSite == null || r.perFactor[res] * site.factor / cost(r) > bestRecipe.perFactor[res] * bestSite.factor / cost(bestRecipe))){
                bestSite = site;
                bestRecipe = r;
            }
        }
        if(bestSite == null) return null;
        free.remove(bestSite);
        return machine(bestRecipe, bestSite);
    }

    /** The machines of the plan so far, for {@link #nearestVent}. */
    List<Machine> sofar = new ArrayList<>();

    /** The free vent nearest any machine of the plan (or of the map's) that takes this liquid. */
    Field.Site nearestVent(int res){
        List<Machine> takers = new ArrayList<>();
        for(Machine m : sofar) if(m.recipe.in[res] > 0f || (m.boosted && m.recipe.boostRes == res)) takers.add(m);
        for(Machine m : field.existing) if(m.recipe.in[res] > 0f) takers.add(m);
        Field.Site best = null;
        double bestD = Double.MAX_VALUE;
        for(Field.Site s : free){
            if(s.ground != Recipe.Ground.vent) continue;
            double d = takers.isEmpty() ? s.distance : Double.MAX_VALUE;
            for(Machine m : takers) d = Math.min(d, Math.hypot(s.x - m.x, s.y - m.y));
            if(d < bestD){
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    Machine powerMaker(){
        for(Recipe r : cat.recipes){
            if(r.powerOut > 0f && r.ground == Recipe.Ground.vent){
                Field.Site site = take(Recipe.Ground.vent, -1);
                return site == null ? null : machine(r, site);
            }
        }
        return null;
    }

    static float cost(Recipe r){
        return Math.max(1, r.totalCost());
    }

    Field.Site peek(Recipe.Ground ground, int item){
        for(Field.Site s : free) if(s.ground == ground && (item < 0 || s.item == item)) return s;
        return null;
    }

    Field.Site take(Recipe.Ground ground, int item){
        Field.Site s = peek(ground, item);
        if(s != null) free.remove(s);
        return s;
    }

    /**
     * A machine at a place, with the line that joins it to the rest in its cost: a duct to carry what it makes, beam nodes
     * for power, a conduit for liquids, over the distance to the core (all beryllium).
     */
    public Machine machine(Recipe r, Field.Site site){
        float d = site == null ? baseDistance : site.distance;
        int[] cost = r.cost.clone();
        int be = cat.id("beryllium");
        boolean items = false, liquids = false;
        for(int k = 0; k < cat.size(); k++){
            Res res = cat.get(k);
            if((r.in[k] > 0f || r.out[k] > 0f || r.perFactor[k] > 0f && site != null && site.item == k) && res.kind == Res.Kind.item) items = true;
            if((r.in[k] > 0f || r.out[k] > 0f) && res.kind == Res.Kind.liquid) liquids = true;
        }
        if(items) cost[be] += Math.round(d);
        if(liquids) cost[be] += Math.round(d);
        if(r.powerUse > 0f || r.powerOut > 0f) cost[be] += (int)Math.ceil(d / 10f) * 8;
        int x = site == null ? field.coreX + (int)baseDistance : site.x, y = site == null ? field.coreY : site.y;
        Machine m = new Machine(r, site == null ? -1 : site.item, site == null ? 1f : site.factor, x, y, d, cost, false, false);
        if(site != null){
            m.rotation = site.rotation;
            m.unseen = !site.seen;
        }
        return m;
    }

    // ---------------------------------------------------------------- the order

    /**
     * A plain order: what digs and makes power first, then what uses it, each step cheapest first; but never a machine
     * whose cost has an item nothing yet makes (the core has too little of it, it can't be dug by hand, and nothing
     * standing or earlier in the order makes it), as that would wait for ever.
     */
    public List<Machine> plainOrder(List<Machine> machines){
        Map<Recipe, Integer> depth = new HashMap<>();
        for(Machine m : machines) depth(m.recipe, depth, new HashSet<>());
        List<Machine> left = new ArrayList<>(machines);
        left.sort(Comparator.<Machine>comparingInt(m -> depth.get(m.recipe)).thenComparingInt(m -> sum(m.cost)));
        boolean[] made = new boolean[cat.size()];
        for(Machine m : field.existing) for(int k = 0; k < made.length; k++) if(m.out(k) > 0f) made[k] = true;
        List<Machine> list = new ArrayList<>();
        while(!left.isEmpty()){
            Machine pick = null;
            for(Machine m : left){
                if(payable(m, made)){
                    pick = m;
                    break;
                }
            }
            if(pick == null) pick = left.get(0);
            left.remove(pick);
            list.add(pick);
            for(int k = 0; k < made.length; k++) if(pick.out(k) > 0f) made[k] = true;
        }
        return list;
    }

    boolean payable(Machine m, boolean[] made){
        for(int k = 0; k < m.cost.length; k++){
            if(m.cost[k] <= 0 || field.stock[k] >= m.cost[k] || made[k] || field.handRate(cat, k) > 0f) continue;
            return false;
        }
        return true;
    }

    int depth(Recipe r, Map<Recipe, Integer> memo, Set<Recipe> seen){
        Integer d = memo.get(r);
        if(d != null) return d;
        if(!seen.add(r)) return 0;
        int best = 0;
        for(int k = 0; k < cat.size(); k++){
            boolean takes = r.in[k] > 0f || r.unitIn == k;
            if(!takes) continue;
            for(Recipe other : cat.recipes){
                if(other != r && other.makes(k)) best = Math.max(best, 1 + depth(other, memo, seen));
            }
        }
        memo.put(r, best);
        return best;
    }

    static int sum(int[] a){
        int s = 0;
        for(int x : a) s += x;
        return s;
    }

    /**
     * The order to build the plant in, found three ways and the best kept:
     * <ol>
     * <li>rollout: at each step every option is tried next (one more of each kind of machine still to build, at its best
     * place, and the extras below), the rest is built after it in a plain order, and the option after which the whole is
     * done soonest is taken; then the same again with the best order so far standing in for the plain order, until it
     * stops getting better (with that, it can only get better);</li>
     * <li>a beam search: the best few partial orders are kept at each step instead of one;</li>
     * <li>single moves on the best: each machine moved to each other place, each extra left out, while any helps.</li>
     * </ol>
     * Extras are machines beyond the plant that may be worth it: another drill or turbine, a drill boosted with hydrogen
     * where hydrogen is made, a furnace with the crushers and bores that feed it.
     *
     * "Done" is the plant built and the first unit out; of two orders done at the same time, the one with the first unit
     * out earlier is better.
     */
    public Timeline order(List<Machine> plant, int unit){
        stages.clear();
        List<Machine> best = rollout(plant, unit, null);
        float[] bestScore = score(best, unit);
        stages.put("rollout", bestScore);
        for(int pass = 0; pass < 6; pass++){
            List<Machine> next = rollout(plant, unit, best);
            float[] s = score(next, unit);
            if(!better(s, bestScore)) break;
            best = next;
            bestScore = s;
        }
        stages.put("rollout, repeated", bestScore);
        //the beam and the moves, each starting from what the other found, while either finds better
        for(int round = 1; round <= 4; round++){
            boolean gained = false;
            if(beamWidth > 1){
                List<Machine> b = beam(plant, unit, beamWidth, best);
                float[] s = score(b, unit);
                if(better(s, bestScore)){
                    best = b;
                    bestScore = s;
                    gained = true;
                }
                stages.put("beam " + beamWidth + " #" + round, bestScore);
            }
            List<Machine> m = moves(best, plant, unit);
            float[] s = score(m, unit);
            if(better(s, bestScore)){
                best = m;
                bestScore = s;
                gained = true;
            }
            stages.put("moves #" + round, bestScore);
            if(!gained) break;
        }
        Timeline line = new Timeline(cat, field, best);
        line.runToGoal(unit, limit);
        return line;
    }

    /** The rate the plant was balanced for: once built, it has to run at that, every machine fed and powered. */
    public float wantRate;

    /**
     * Whether a finished timeline counts as done: the plant built, the first unit out, and what stands then making the
     * unit at the rate wanted with power fully met (extras may not take what the plant needs).
     */
    boolean reached(Timeline t, int unit){
        if(!t.done() || t.made[unit] < t.goalAmount(unit) - 1e-3f) return false;
        Plant p = t.steady();
        if(cat.get(unit).kind != Res.Kind.unit) return p.powerMet >= 0.999f && p.toCore[unit] >= wantRate * 0.98f;
        return p.powerMet >= 0.999f && p.unitRate(t.built, unit) >= wantRate * 0.98f;
    }

    /** How wide the beam search is; 1 or less leaves it out. */
    public int beamWidth = 16;
    /** The score after each stage of {@link #order}, for telling how much each adds. */
    public final Map<String, float[]> stages = new LinkedHashMap<>();
    /** How many orders have been played through. */
    public int evaluated;

    /** When the order is done (plant built and first unit out) and when the first unit is out; infinite if never. */
    public float[] score(List<Machine> seq, int unit){
        evaluated++;
        Timeline t = new Timeline(cat, field, seq);
        t.runToGoal(unit, limit);
        return new float[]{reached(t, unit) ? t.t : Float.POSITIVE_INFINITY, t.first[unit], spent(seq)};
    }

    /** Items the order spends, all counted alike: of two orders as fast, the one that leaves more in the core is better. */
    static float spent(List<Machine> seq){
        float sum = 0f;
        for(Machine m : seq) for(int c : m.cost) sum += c;
        return sum;
    }

    /** Sooner done, then sooner first unit, then less spent; times in whole seconds, as the model is no finer. */
    static boolean better(float[] a, float[] b){
        return compare(a, b) < 0;
    }

    static int compare(float[] a, float[] b){
        int c = Double.compare(seconds(a[0]), seconds(b[0]));
        if(c != 0) return c;
        c = Double.compare(seconds(a[1]), seconds(b[1]));
        if(c != 0) return c;
        return Float.compare(a.length > 2 ? a[2] : 0f, b.length > 2 ? b[2] : 0f);
    }

    static double seconds(float t){
        return Float.isInfinite(t) || Float.isNaN(t) ? Double.POSITIVE_INFINITY : Math.round(t);
    }

    /** The rest in the order a guide has them, what the guide does not have after in the plain order. */
    List<Machine> following(List<Machine> rest, List<Machine> guide){
        if(guide == null) return plainOrder(rest);
        List<Machine> list = new ArrayList<>();
        for(Machine m : guide) if(rest.contains(m)) list.add(m);
        List<Machine> others = new ArrayList<>(rest);
        others.removeAll(list);
        list.addAll(plainOrder(others));
        return list;
    }

    // ---------------------------------------------------------------- one point of a search

    /** Where a search has got to: what has been begun, in order; what is still to be built; places still free for extras. */
    final class Node{
        Timeline now;
        List<Machine> done, left;
        List<Field.Site> pool;
        int extrasLeft;
        float[] score;

        Node copy(){
            Node n = new Node();
            n.now = now.copy();
            n.done = new ArrayList<>(done);
            n.left = new ArrayList<>(left);
            n.pool = new ArrayList<>(pool);
            n.extrasLeft = extrasLeft;
            return n;
        }
    }

    Node start(List<Machine> plant, List<Machine> guide){
        Node n = new Node();
        n.now = new Timeline(cat, field, new ArrayList<>());
        n.done = new ArrayList<>();
        n.left = new ArrayList<>(plant);
        //the extras the guide took are kept (moves may still leave them out), and more may be added
        if(guide != null) for(Machine m : guide) if(!plant.contains(m)) n.left.add(m);
        n.pool = new ArrayList<>(free);
        for(Machine m : n.left) n.pool.remove(extraSites.get(m));
        n.extrasLeft = extras - (n.left.size() - plant.size());
        return n;
    }

    /** What could be begun next: lists of machines, the first begun now and the rest joining what is still to be built. */
    List<List<Machine>> options(Node n, List<Machine> guide){
        List<List<Machine>> list = new ArrayList<>();
        Set<String> kinds = new HashSet<>();
        for(Machine m : n.left) if(kinds.add(m.recipe.name + "/" + m.item + "/" + m.boosted)) list.add(List.of(m));
        //what the guide would do next is always among them, so that following it is one of the choices
        if(guide != null && !n.left.isEmpty()){
            Machine g = following(n.left, guide).get(0);
            if(!kinds.contains(g.recipe.name + "/" + g.item + "/" + g.boosted) || !containsFirst(list, g)) list.add(List.of(g));
        }
        if(n.extrasLeft > 0) list.addAll(extraOptions(n));
        return list;
    }

    static boolean containsFirst(List<List<Machine>> list, Machine m){
        for(List<Machine> o : list) if(o.get(0) == m) return true;
        return false;
    }

    /** The node after beginning an option. */
    Node apply(Node n, List<Machine> option, List<Machine> guide){
        Node c = n.copy();
        Machine first = option.get(0);
        if(!c.left.remove(first)){
            c.extrasLeft -= option.size();
            for(Machine m : option) c.pool.remove(extraSites.get(m));
        }
        for(int i = 1; i < option.size(); i++) c.left.add(option.get(i));
        c.done.add(first);
        List<Machine> seq = new ArrayList<>();
        seq.add(first);
        seq.addAll(following(c.left, guide));
        c.now.reorder(seq);
        c.now.runToStart(c.now.next, limit);
        return c;
    }

    /** How the node ends if the rest is built in the guide's order (or the plain order). */
    float[] finish(Node n, int unit, List<Machine> guide){
        evaluated++;
        Timeline t = n.now.copy();
        List<Machine> rest = following(n.left, guide);
        t.reorder(rest);
        t.runToGoal(unit, limit);
        return new float[]{reached(t, unit) ? t.t : Float.POSITIVE_INFINITY, t.first[unit], spent(n.done) + spent(rest)};
    }

    List<Machine> rollout(List<Machine> plant, int unit, List<Machine> guide){
        Node n = start(plant, guide);
        while(!n.left.isEmpty()){
            Node best = null;
            for(List<Machine> o : options(n, guide)){
                Node c = apply(n, o, guide);
                c.score = finish(c, unit, guide);
                if(best == null || better(c.score, best.score)) best = c;
            }
            n = best;
        }
        return n.done;
    }

    /** The best few partial orders kept at each step, each scored by building the rest in the guide's order. */
    List<Machine> beam(List<Machine> plant, int unit, int width, List<Machine> guide){
        List<Node> beam = new ArrayList<>(List.of(start(plant, guide)));
        while(true){
            List<Node> next = new ArrayList<>();
            boolean grew = false;
            for(Node n : beam){
                if(n.left.isEmpty()){
                    next.add(n);
                    continue;
                }
                grew = true;
                for(List<Machine> o : options(n, guide)){
                    Node c = apply(n, o, guide);
                    c.score = finish(c, unit, guide);
                    next.add(c);
                }
            }
            if(!grew) break;
            next.sort((a, b) -> compare(a.score, b.score));
            //two partial orders with the same machines begun in the same order are the same
            List<Node> kept = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for(Node c : next){
                if(kept.size() >= width) break;
                StringBuilder key = new StringBuilder();
                for(Machine m : c.done) key.append(m.recipe.name).append(m.item).append(m.x).append(',').append(m.y).append(';');
                if(seen.add(key.toString())) kept.add(c);
            }
            beam = kept;
        }
        Node best = beam.get(0);
        for(Node n : beam) if(better(n.score, best.score)) best = n;
        return best.done;
    }

    /** Single moves on a whole order, kept while they help. */
    List<Machine> moves(List<Machine> seq, List<Machine> plant, int unit){
        float[] best = score(seq, unit);
        boolean better = true;
        for(int round = 0; round < 20 && better; round++){
            better = false;
            for(int i = 0; i < seq.size() && !better; i++){
                //an extra left out
                if(!plant.contains(seq.get(i))){
                    List<Machine> without = new ArrayList<>(seq);
                    without.remove(i);
                    float[] s = score(without, unit);
                    if(better(s, best)){
                        seq = without;
                        best = s;
                        better = true;
                        break;
                    }
                }
                for(int j = 0; j < seq.size(); j++){
                    if(j == i) continue;
                    List<Machine> moved = new ArrayList<>(seq);
                    Machine m = moved.remove(i);
                    moved.add(j, m);
                    float[] s = score(moved, unit);
                    if(better(s, best)){
                        seq = moved;
                        best = s;
                        better = true;
                        break;
                    }
                }
            }
        }
        return seq;
    }

    // ---------------------------------------------------------------- extras

    final Map<Machine, Field.Site> extraSites = new HashMap<>();

    /**
     * Extras that could be begun: one more of each kind of drill on its best place still free, one more turbine; a bore
     * boosted with hydrogen where something makes hydrogen; another of each crafter that is fed only from the ground,
     * with the drills and crushers that feed it.
     */
    List<List<Machine>> extraOptions(Node n){
        List<List<Machine>> list = new ArrayList<>();
        Set<String> kinds = new HashSet<>();
        boolean[] made = madeSomewhere(n);
        for(Field.Site s : n.pool){
            if(!kinds.add(s.ground + "/" + s.item)) continue;
            for(Recipe r : cat.recipes){
                boolean fits = r.ground == s.ground && (s.ground == Recipe.Ground.vent ? r.powerOut > 0f : r.perFactor[s.item] > 0f && noInputs(r));
                if(!fits) continue;
                list.add(List.of(extra(r, s, false)));
                if(r.boostRes >= 0 && made[r.boostRes]) list.add(List.of(extra(r, s, true)));
                break;
            }
        }
        //a whole furnace (or any crafter fed from the ground), with what feeds it
        for(Recipe r : cat.recipes){
            if(r.ground != Recipe.Ground.none || r.makesUnits() || !fedFromGround(r)) continue;
            List<Machine> bundle = bundle(r, n.pool, n.extrasLeft);
            if(bundle != null) list.add(bundle);
        }
        return list;
    }

    Machine extra(Recipe r, Field.Site s, boolean boosted){
        Machine m = boosted ? boosted(machine(r, s)) : machine(r, s);
        extraSites.put(m, s);
        return m;
    }

    /** The same machine with hydrogen (its booster) led to it: a conduit of the same length on top. */
    Machine boosted(Machine m){
        int[] cost = m.cost.clone();
        cost[cat.id("beryllium")] += Math.round(m.distance);
        Machine b = new Machine(m.recipe, m.item, m.factor, m.x, m.y, m.distance, cost, true, false);
        b.rotation = m.rotation;
        b.unseen = m.unseen;
        return b;
    }

    boolean[] madeSomewhere(Node n){
        boolean[] made = new boolean[cat.size()];
        List<Machine> all = new ArrayList<>(n.now.built);
        all.addAll(n.done);
        all.addAll(n.left);
        for(Machine m : all) for(int k = 0; k < made.length; k++) if(m.out(k) > 0f) made[k] = true;
        return made;
    }

    /** Whether all a crafter takes (but power) can be dug by machines that need nothing but power. */
    boolean fedFromGround(Recipe r){
        boolean any = false;
        for(int k = 0; k < cat.size(); k++){
            if(r.in[k] <= 0f) continue;
            any = true;
            boolean dug = false;
            for(Recipe d : cat.recipes) if(d.ground != Recipe.Ground.none && d.perFactor[k] > 0f && noInputs(d)) dug = true;
            if(!dug) return false;
        }
        return any;
    }

    /** A crafter and, on the best free places, enough of what digs its inputs for it to run at full speed. Diggers first. */
    List<Machine> bundle(Recipe r, List<Field.Site> pool, int room){
        List<Machine> feeders = new ArrayList<>();
        Set<Field.Site> used = new HashSet<>();
        for(int k = 0; k < cat.size(); k++){
            float need = r.in[k];
            while(need > 1e-4f){
                Field.Site best = null;
                Recipe bestRecipe = null;
                for(Field.Site s : pool){
                    if(used.contains(s) || s.item != k) continue;
                    for(Recipe d : cat.recipes){
                        if(d.ground == s.ground && d.perFactor[k] > 0f && noInputs(d) && (best == null || d.perFactor[k] * s.factor > bestRecipe.perFactor[k] * best.factor)){
                            best = s;
                            bestRecipe = d;
                        }
                    }
                }
                if(best == null) return null;
                used.add(best);
                feeders.add(extra(bestRecipe, best, false));
                need -= bestRecipe.perFactor[k] * best.factor;
            }
        }
        if(feeders.size() + 1 > room) return null;
        feeders.add(machine(r, null));
        return feeders;
    }

    /** Whether it needs nothing but power (so an extra one is only ever a gain). */
    boolean noInputs(Recipe r){
        for(float v : r.in) if(v > 0f) return false;
        return true;
    }
}
