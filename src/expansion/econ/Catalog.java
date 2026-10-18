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
    /** The core unit: build speed, items a second it digs from a wall, how much it carries, how fast it flies (tiles a second). */
    public float buildSpeed = 1f, mineRate = 5f, flySpeed = 40f;
    public int carry = 60;
    /** How near the core it has to be for what it digs to go straight in, how near a wall to dig it, how near a site to build (tiles). */
    public float handOverRange = 27.5f, mineRange = 8.75f, buildRange = 27.5f;
    /** The rule that scales building speed for this side. */
    public float buildRule = 1f;

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

    public float[] zeros(){
        return new float[res.size()];
    }
}
