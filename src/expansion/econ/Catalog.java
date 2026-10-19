package expansion.econ;

import java.util.*;

/**
 * Everything the model knows about the game that does not depend on the map: the things that flow, the machines that
 * may be built, and the core and its unit. {@link Recipes} fills one in from the game; tests can fill one in by hand.
 */
public final class Catalog{
    public final List<Res> res = new ArrayList<>();
    public final Map<String, Res> byName = new HashMap<>();
    public final List<Recipe> recipes = new ArrayList<>();

    /** How much of each item the core holds at most; more is burned. */
    public int coreCap = 2000;
    /**
     * For each thing, the share of what flows past a machine that takes it that goes on to the core regardless: without
     * underflow ducts a dock is a router, which hands out by turns to the machine and the line on to the core, so a
     * machine gets at most half of what passes it. Null when docks give the machines all they want first.
     */
    public float[] coreShare;
    /**
     * For each thing, how much less a second the core gets of what the map's own machines make than the model would say:
     * measured, by watching the map before planning (lines that back up, makers that also feed what the map has). Taken
     * off what goes to the core, nothing else. Null when not measured.
     */
    public float[] drain;
    /** The core unit: build speed, items a second it digs from a wall, how much it carries, how fast it flies (tiles a second). */
    public float buildSpeed = 1f, mineRate = 5f, flySpeed = 40f;
    public int carry = 60;
    /** How near the core it has to be for what it digs to go straight in, how near a wall to dig it, how near a site to build (tiles). */
    public float handOverRange = 27.5f, mineRange = 8.75f, buildRange = 27.5f;
    /** The rule that scales building speed for this side. */
    public float buildRule = 1f;
    /** How fast each kind of unit goes over the ground, tiles a second, by {@link Res#id} (0 for what is not a unit). */
    public final Map<Integer, Float> unitSpeed = new HashMap<>();

    public Res add(String name, Res.Kind kind, boolean storable, boolean handMined){
        Res r = new Res(res.size(), name, kind, storable, handMined);
        res.add(r);
        byName.put(name, r);
        return r;
    }

    public int id(String name){
        Res r = byName.get(name);
        if(r == null) throw new IllegalArgumentException("nothing called " + name);
        return r.id;
    }

    public Res get(int id){
        return res.get(id);
    }

    public int size(){
        return res.size();
    }

    public Recipe recipe(String name){
        for(Recipe r : recipes) if(r.name.equals(name)) return r;
        return null;
    }

    /** Seconds the core unit takes to build it. */
    public float buildSeconds(Recipe recipe){
        return recipe.buildTicks / (buildSpeed * buildRule) / 60f;
    }

    /** Sets {@link #coreShare} by what may be built: with no underflow duct, half of what building costs goes on to the core. */
    public void logistics(java.util.Set<String> allowed){
        if(allowed.contains("underflow-duct")){
            coreShare = null;
            return;
        }
        coreShare = zeros();
        for(int k = 0; k < size(); k++){
            Res r = get(k);
            if(r.kind != Res.Kind.item || !r.storable) continue;
            boolean builds = false;
            for(Recipe rec : recipes) if(rec.cost[k] > 0) builds = true;
            if(builds) coreShare[k] = 0.5f;
        }
    }

    public float[] zeros(){
        return new float[res.size()];
    }
}
