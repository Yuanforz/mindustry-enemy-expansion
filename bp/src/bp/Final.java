package bp;

import mindustry.*;
import mindustry.content.*;
import mindustry.world.*;
import mindustry.world.meta.*;

import java.util.*;

/**
 * The final form, as a plan: every block of Erekir a player may build, each that works at work at full (one machine's
 * worth), every way of every turret (each ammunition, with and without coolant) firing, and so much power to spare; what
 * it takes, from the raw things given. The plan only: which machines, how many, what flows; no layout.
 *
 * Usage: bp.Final data-dir surplus raws (comma separated) [places: recipe=yield/...]
 */
public final class Final{
    /**
     * The blocks of Erekir a player may build: those of its tech tree (Planet.techTree, as the game builds it), each
     * shown in play (not debug-only, editor-only, sandbox-only or hidden: BuildVisibility).
     */
    public static List<Block> erekir(){
        List<Block> out = new ArrayList<>();
        Set<mindustry.ctype.UnlockableContent> inTree = new HashSet<>();
        ArrayDeque<mindustry.content.TechTree.TechNode> todo = new ArrayDeque<>();
        if(Planets.erekir.techTree != null) todo.add(Planets.erekir.techTree);
        while(!todo.isEmpty()){
            var node = todo.poll();
            inTree.add(node.content);
            todo.addAll(node.children.list());
        }
        for(Block b : Vars.content.blocks()){
            if(!inTree.contains(b) || b.requirements.length == 0) continue;
            var v = b.buildVisibility;
            if(v == BuildVisibility.sandboxOnly || v == BuildVisibility.hidden || v == BuildVisibility.debugOnly || v == BuildVisibility.editorOnly) continue;
            out.add(b);
        }
        return out;
    }

    public static Balance plan(float surplus, Set<String> raws, Map<String, List<Float>> places){
        Balance bal = new Balance();
        bal.sources.addAll(raws);
        bal.powerSurplus = surplus;
        List<Block> blocks = erekir();
        Set<String> names = new HashSet<>();
        for(Block b : blocks) names.add(b.name);
        for(Recipe r : Recipes.all()){
            if(r.kind == Recipe.Kind.source || r.name.equals("heat-source")) continue;
            if(!names.contains(r.block.name)) continue;
            bal.recipes.add(r);
        }
        //every way of every turret firing; every other block that works, at work at full
        Set<String> working = new HashSet<>();
        for(Recipe r : bal.recipes){
            if(r.kind == Recipe.Kind.turret) bal.goals.put(r.name, 1f);
            else working.add(r.block.name);
        }
        for(String b : working) bal.runAtLeast.put(b, 1f);
        bal.places.putAll(places);
        bal.elastic = true;
        return bal;
    }

    /**
     * A place of full yield for each recipe on ground, as the best the game's floors and walls give: a beam drill facing
     * ore with every lane; a drill on ore every tile; a wall crafter against the best wall every tile of its face; a pump
     * on liquid every tile; a machine on vents every tile on the best vent floor.
     */
    public static Map<String, List<Float>> fullPlaces(List<Recipe> recipes){
        Map<String, List<Float>> out = new HashMap<>();
        for(Recipe r : recipes){
            if(r.ground == Recipe.Ground.none) continue;
            int s = r.size;
            float y = switch(r.ground){
                case wallOre, sandWall -> s * best(r);
                case floorOre -> s * s;
                case liquidFloor -> s * s;
                case vent -> s * s * best(r);
                default -> 0f;
            };
            if(y > 0f) out.put(r.name, List.of(y));
        }
        return out;
    }

    /** The most a tile of the game's floors or walls gives of what a block on ground draws on (its attribute), else 1. */
    static float best(Recipe r){
        mindustry.world.meta.Attribute attr = r.block instanceof mindustry.world.blocks.production.WallCrafter w ? w.attribute : r.block instanceof mindustry.world.blocks.production.AttributeCrafter a ? a.attribute : r.block instanceof mindustry.world.blocks.power.ThermalGenerator t ? t.attribute : null;
        if(attr == null) return 1f;
        float most = 0f;
        for(Block b : Vars.content.blocks()) if(b.isOnPlanet(Planets.erekir) || b instanceof mindustry.world.blocks.environment.Floor) most = Math.max(most, b.attributes.get(attr));
        return most;
    }

    /**
     * The final form on a saved map: no raw thing given, all of it dug, pumped, crushed or drawn from the map's places,
     * the nearest so many full places of each recipe on ground to the core weighed (Sites).
     */
    public static Balance onWorld(String dataDir, String world, float surplus, int keep){
        Problem prob = Problem.parse("world " + world + "\n");
        Gen gen = new Gen(prob, dataDir);
        Spec.Place core = prob.fixed.isEmpty() ? null : prob.fixed.get(0);
        int cx = core == null ? gen.board.w / 2 : core.x(), cy = core == null ? gen.board.h / 2 : core.y();
        Balance bal = plan(surplus, Set.of(), Map.of());
        long t0 = System.nanoTime();
        Map<Recipe, List<Sites.Site>> sites = new Sites(gen.board).find(bal.recipes, cx, cy, keep);
        int n = 0;
        for(var e : sites.entrySet()){
            List<Float> ys = new ArrayList<>();
            List<int[][]> ts = new ArrayList<>();
            for(Sites.Site st : e.getValue()){
                ys.add(st.yield());
                ts.add(new int[][]{st.body(), st.lane()});
            }
            bal.places.put(e.getKey().name, ys);
            bal.placeTiles.put(e.getKey().name, ts);
            n += ys.size();
        }
        lastSites = sites;
        System.out.println(String.format("  %s: %dx%d, core at %d,%d; %d full places of %d recipes on ground found in %.1f s", world, gen.board.w, gen.board.h, cx, cy, n, sites.size(), (System.nanoTime() - t0) / 1e9));
        for(var e : sites.entrySet()) if(e.getValue().isEmpty()) System.out.println("    none on this map at full: " + e.getKey().name);
        return bal;
    }

    static Map<Recipe, List<Sites.Site>> lastSites;

    public static void main(String[] args){
        Game.boot(args[0]);
        if(args[1].equals("world")){
            Balance bal = onWorld(args[0], args[2], Float.parseFloat(args[3]), args.length > 4 ? Integer.parseInt(args[4]) : 6);
            long t0 = System.nanoTime();
            Balance.Result r = bal.solve();
            System.out.println(r.toString().lines().filter(l -> !l.startsWith("  to the core")).reduce("", (a, c) -> a + c + "\n"));
            System.out.println(String.format("  solved in %.1f s", (System.nanoTime() - t0) / 1e9));
            System.exit(0);
        }
        float surplus = Float.parseFloat(args[1]);
        Set<String> raws = new HashSet<>(Arrays.asList(args[2].split(",")));
        Map<String, List<Float>> places = new HashMap<>();
        for(int i = 3; i < args.length; i++) if(args[i].startsWith("at:")){
            String[] p = args[i].substring(3).split("=");
            List<Float> ys = new ArrayList<>();
            for(String y : p[1].split("/")) ys.add(Float.parseFloat(y));
            places.put(p[0], ys);
        }
        long t0 = System.nanoTime();
        if(Arrays.asList(args).contains("anyplace")) places.putAll(fullPlaces(Recipes.all()));
        Balance bal = plan(surplus, raws, places);
        Balance.Result r = bal.solve();
        System.out.println(r);
        //(what of Erekir's blocks has no work to be at: put down once, its cost only)
        Set<String> withWork = new HashSet<>();
        for(Recipe x : bal.recipes) withWork.add(x.block.name);
        List<String> rest = new ArrayList<>();
        for(Block b : erekir()) if(!withWork.contains(b.name)) rest.add(b.name);
        System.out.println("  put down once, no work to be at: " + rest);
        System.out.println(String.format("  %d blocks of Erekir; %d recipes; %d turret ways; %d blocks at work; solved in %.1f s", erekir().size(), bal.recipes.size(), bal.goals.size(), bal.runAtLeast.size(), (System.nanoTime() - t0) / 1e9));
        System.exit(0);
    }
}
