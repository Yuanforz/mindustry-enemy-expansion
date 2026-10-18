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
    /** Machines built from nowhere in particular stand this far from the core (tiles). */
    public float baseDistance = 8f;
    /** How many machines beyond the plant may be tried. */
    public int extras = 12;
    public float limit = 3 * 3600f;
    /** For telling how it went. */
    public final StringBuilder notes = new StringBuilder();

    public Planner(Catalog cat, Field field){
        this.cat = cat;
        this.field = field;
        free = new ArrayList<>(field.sites);
    }

    // ---------------------------------------------------------------- the plant

    /** Machines to add to what stands so that the unit comes out at this rate (units a second) with every machine fed and powered. */
    public List<Machine> balance(int unit, float rate){
        List<Machine> plan = new ArrayList<>();
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

        //what everything takes, until there is enough of all of it
        for(int guard = 0; guard < 400; guard++){
            List<Machine> all = new ArrayList<>(field.existing);
            all.addAll(plan);
            float[] want = cat.zeros(), have = cat.zeros();
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
        return plan;
    }

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
                Field.Site site = take(Recipe.Ground.vent, -1);
                return site == null ? null : machine(best, site);
            }
            return machine(best, null);
        }
        //a crafter that makes it, if there is one
        for(Recipe r : cat.recipes){
            if(r.ground == Recipe.Ground.none && r.out[res] > 0f) return machine(r, null);
        }
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
        return new Machine(r, site == null ? -1 : site.item, site == null ? 1f : site.factor, x, y, d, cost, false, false);
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
     * The order to build the plant in. First by rollout: at each step, every kind of machine still to build (the best
     * place of each) and every extra drill or turbine is tried next, with the rest after it in a plain order, and the one
     * that gets the whole done soonest is taken. Then the rollout again, with the rest after each try in the best order
     * found so far instead (so it can only get better), until it stops getting better; then single moves: each machine
     * moved to each other place in the order, and each extra left out, while any of them helps.
     *
     * "Done" is the plant built and the first unit out; of two orders done at the same time, the one with the first unit
     * out earlier is better.
     */
    public Timeline order(List<Machine> plant, int unit){
        List<Machine> best = rollout(plant, unit, null);
        float[] bestScore = score(best, unit);
        for(int pass = 0; pass < 6; pass++){
            List<Machine> next = rollout(plant, unit, best);
            float[] s = score(next, unit);
            if(!better(s, bestScore)) break;
            best = next;
            bestScore = s;
        }
        int rollouts = evaluated;
        best = moves(best, plant, unit);
        notes.append(String.format("orders tried: %d in rollouts, %d in moves\n", rollouts, evaluated - rollouts));
        Timeline line = new Timeline(cat, field, best);
        line.runToGoal(unit, limit);
        return line;
    }

    /** How many orders have been played through. */
    public int evaluated;

    /** When the order is done (plant built and first unit out) and when the first unit is out; infinite if never. */
    public float[] score(List<Machine> seq, int unit){
        evaluated++;
        Timeline t = new Timeline(cat, field, seq);
        t.runToGoal(unit, limit);
        boolean done = t.done() && t.made[unit] >= 1f;
        return new float[]{done ? t.t : Float.POSITIVE_INFINITY, t.first[unit]};
    }

    static boolean better(float[] a, float[] b){
        if(a[0] < b[0] - 1e-3f) return true;
        return Math.abs(a[0] - b[0]) <= 1e-3f && a[1] < b[1] - 1e-3f;
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

    List<Machine> rollout(List<Machine> plant, int unit, List<Machine> guide){
        Timeline now = new Timeline(cat, field, new ArrayList<>());
        List<Machine> left = new ArrayList<>(plant);
        //the extras the guide took are kept, and more may be added
        if(guide != null) for(Machine m : guide) if(!plant.contains(m)) left.add(m);
        List<Field.Site> pool = new ArrayList<>(free);
        for(Machine m : left) pool.remove(extraSites.get(m));
        int extrasLeft = extras - (left.size() - plant.size());
        List<Machine> done = new ArrayList<>();
        while(!left.isEmpty()){
            List<Machine> tries = new ArrayList<>();
            Set<String> kinds = new HashSet<>();
            for(Machine m : left) if(kinds.add(m.recipe.name + "/" + m.item)) tries.add(m);
            //what the guide would do next is always tried, so that following it is one of the choices
            if(guide != null){
                List<Machine> g = following(left, guide);
                if(!tries.contains(g.get(0))) tries.add(g.get(0));
            }
            List<Machine> extraTries = extrasLeft > 0 ? extraCandidates(pool) : List.of();
            tries.addAll(extraTries);

            Machine best = null;
            float[] bestScore = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
            for(Machine m : tries){
                List<Machine> rest = new ArrayList<>(left);
                rest.remove(m);
                List<Machine> seq = new ArrayList<>(done);
                seq.add(m);
                seq.addAll(following(rest, guide));
                Timeline t = now.copy();
                t.reorder(seq.subList(done.size(), seq.size()));
                t.runToGoal(unit, limit);
                evaluated++;
                float[] sc = {t.done() && t.made[unit] >= 1f ? t.t : Float.POSITIVE_INFINITY, t.first[unit]};
                if(best == null || better(sc, bestScore)){
                    bestScore = sc;
                    best = m;
                }
            }
            done.add(best);
            if(extraTries.contains(best)){
                extrasLeft--;
                pool.remove(extraSites.get(best));
            }else{
                left.remove(best);
            }
            List<Machine> seq = new ArrayList<>();
            seq.add(best);
            seq.addAll(following(left, guide));
            now.reorder(seq);
            now.runToStart(now.next, limit);
        }
        return done;
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

    final Map<Machine, Field.Site> extraSites = new HashMap<>();

    /** One more of each kind of drill on its best place still free, and one more turbine. */
    List<Machine> extraCandidates(List<Field.Site> pool){
        List<Machine> list = new ArrayList<>();
        Set<String> kinds = new HashSet<>();
        for(Field.Site s : pool){
            if(!kinds.add(s.ground + "/" + s.item)) continue;
            for(Recipe r : cat.recipes){
                boolean fits = r.ground == s.ground && (s.ground == Recipe.Ground.vent ? r.powerOut > 0f : r.perFactor[s.item] > 0f && noInputs(r));
                if(!fits) continue;
                Machine m = machine(r, s);
                extraSites.put(m, s);
                list.add(m);
                break;
            }
        }
        return list;
    }

    /** Whether it needs nothing but power (so an extra one is only ever a gain). */
    boolean noInputs(Recipe r){
        for(float v : r.in) if(v > 0f) return false;
        return true;
    }
}
