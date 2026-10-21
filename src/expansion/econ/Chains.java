package expansion.econ;

import java.util.*;

/**
 * What it costs to make each thing, the cheapest way the map and the catalog allow: items spent on building, for one
 * unit a second of it, counting what the inputs cost to make in turn (and the power, at what power costs to make). Worked
 * out the way shortest paths are (Bellman-Ford over the recipes), since a recipe's cost depends on its inputs' costs.
 *
 * It chooses between ways of making the same thing: a crucible module heated by phase fabric or one heated by slag, a
 * small furnace module or a big one. It does not decide how many: the balance does that.
 */
public final class Chains{
    final Catalog cat;
    final Field field;
    /** Cost of one unit a second, by {@link Res#id}; infinite for what can't be made. */
    public final float[] cost;
    public float power = Float.POSITIVE_INFINITY;
    /** The recipe that gives each its cost. */
    public final Recipe[] by;
    /** What can be had without a maker: dug by hand, or made by the map's own machines. */
    final boolean[] given;
    /** Whether our standing machines make power (a powered maker can be had where no new one can be put down). */
    boolean givenPower;

    public Chains(Catalog cat, Field field){
        this(cat, field, false);
    }

    /**
     * With seeded on, what our standing machines already make (power among it) counts as had for nothing, and the rest is
     * worked out from there: for whether a thing can be had at all, as climbing the tech asks. Without it, a thing is had
     * only by new machines on free places (what more of it costs): late in a game every vent has its turbine, power came to
     * nothing new, and every powered maker with it, so a climb found silicon out of reach with a furnace standing.
     */
    public Chains(Catalog cat, Field field, boolean seeded){
        this.cat = cat;
        this.field = field;
        int n = cat.size();
        cost = new float[n];
        by = new Recipe[n];
        Arrays.fill(cost, Float.POSITIVE_INFINITY);
        if(seeded){
            //(the map's machines standing idle too: taken up, they cost only their lines)
            List<Machine> standing = new ArrayList<>(field.existing);
            standing.addAll(field.revivable);
            for(Machine m : standing){
                for(int k = 0; k < n; k++) if(m.out(k) > 1e-4f) cost[k] = 0f;
                if(m.powerOut() > 0f) power = 0f;
            }
        }
        //best yield of each thing on each kind of ground, from the places the map has
        float[][] best = new float[Recipe.Ground.values().length][n];
        boolean[] vents = new boolean[1];
        for(Field.Site s : field.sites){
            if(s.ground == Recipe.Ground.vent) vents[0] = true;
            else if(s.item >= 0) best[s.ground.ordinal()][s.item] = Math.max(best[s.ground.ordinal()][s.item], s.factor);
        }
        //what can be had without a maker: in the core now, dug by hand, or made by the map's own machines
        given = new boolean[n];
        for(int k = 0; k < n; k++) given[k] = field.handRate(cat, k) > 0f;
        for(Machine m : field.existing){
            for(int k = 0; k < n; k++) if(m.out(k) > 1e-4f) given[k] = true;
            if(m.powerOut() > 0f) givenPower = true;
        }
        for(int round = 0; round < 40; round++){
            boolean changed = false;
            for(Recipe r : cat.recipes){
                if(r.makesUnits()) continue;
                //(a machine that costs what can't be had at all makes nothing: a large cliff crusher where no surge alloy is)
                if(!buildable(r, given)) continue;
                float spent = r.totalCost();
                if(r.powerUse > 0f) spent += r.powerUse * power;
                for(int k = 0; k < n; k++) if(r.in[k] > 0f) spent += r.in[k] * cost[k];
                if(Float.isNaN(spent) || Float.isInfinite(spent)) continue;
                if(r.ground == Recipe.Ground.vent && !vents[0]) continue;
                //what it gives: on the ground, at the best place there is
                for(int k = 0; k < n; k++){
                    float rate = r.out[k];
                    if(r.perFactor[k] > 0f) rate += r.perFactor[k] * best[r.ground.ordinal()][k];
                    if(rate <= 1e-6f) continue;
                    float c = spent / rate;
                    if(c < cost[k] * 0.999f){
                        cost[k] = c;
                        by[k] = r;
                        changed = true;
                    }
                }
                if(r.powerOut > 0f){
                    float c = spent / r.powerOut;
                    if(c < power * 0.999f){
                        power = c;
                        changed = true;
                    }
                }
            }
            if(!changed) break;
        }
    }

    /** Whether what building this costs can be had: each item of it in the core already, or to be had some way. */
    boolean buildable(Recipe r, boolean[] given){
        for(int k = 0; k < r.cost.length; k++) if(r.cost[k] > 0f && r.cost[k] > field.stock[k] && !given[k] && Float.isInfinite(cost[k])) return false;
        return true;
    }

    /** Whether a recipe can be built and fed at all: what it costs to build to be had, and what it takes in to be made. */
    public boolean feasible(Recipe r){
        if(!buildable(r, given)) return false;
        for(int k = 0; k < cost.length; k++) if(r.in[k] > 0f && !given[k] && Float.isInfinite(cost[k])) return false;
        return !(r.powerUse > 0f && Float.isInfinite(power) && !givenPower);
    }

    /** What making this a second with this recipe costs, inputs and power included; infinite if some input can't be made. */
    public float costOf(Recipe r, int res){
        float rate = r.out[res];
        if(rate <= 0f || !buildable(r, given)) return Float.POSITIVE_INFINITY;
        float spent = r.totalCost();
        if(r.powerUse > 0f) spent += r.powerUse * power;
        for(int k = 0; k < cost.length; k++) if(r.in[k] > 0f) spent += r.in[k] * cost[k];
        return spent / rate;
    }

    /** The crafter or module (no ground) that makes this the cheapest way; null if none can, or a machine on the ground is cheaper. */
    public Recipe bestCrafter(int res){
        Recipe best = null;
        float bestCost = Float.POSITIVE_INFINITY;
        for(Recipe r : cat.recipes){
            if(r.ground != Recipe.Ground.none || r.makesUnits() || r.out[res] <= 0f) continue;
            float c = costOf(r, res);
            if(c < bestCost){
                bestCost = c;
                best = r;
            }
        }
        if(best == null) return null;
        //(a drill on the ground that gives it for less)
        if(by[res] != null && by[res].ground != Recipe.Ground.none && cost[res] < bestCost * 0.999f) return null;
        return best;
    }

    /** One line a thing: what it costs a second and how. */
    public String describe(){
        StringBuilder b = new StringBuilder(String.format("power: %.1f a unit/s\n", power));
        for(int k = 0; k < cost.length; k++){
            if(cat.get(k).kind == Res.Kind.unit) continue;
            b.append(String.format("%s: %s%s\n", cat.get(k).name, Float.isInfinite(cost[k]) ? "can't be made" : String.format("%.0f a unit/s", cost[k]), by[k] == null ? "" : " by " + by[k].name));
        }
        return b.toString();
    }
}
