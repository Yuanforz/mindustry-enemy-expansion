package expansion.econ;

import arc.math.geom.*;
import arc.struct.*;
import expansion.defense.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.game.Schematic.*;
import mindustry.world.*;

import java.util.*;

/**
 * The defence planner's plan as machines of the economy's: each module it laid becomes a design pinned where it was put,
 * taking at its ports what its turrets fire (on average, over a share of the time spent firing), so the balance gives it
 * makers and the layout leads their lines into it, the way it does for any machine. Each site's wall line becomes a design
 * of its own with no ports, built after the modules it covers.
 */
public final class Defence{
    private Defence(){}

    /** A port is fed by a line of at least this much a second, so that its store fills between waves. */
    public static float leastRate = 0.25f;

    /**
     * Makes a design and a recipe for each module and wall line of the plan, adds the recipes to the catalog, and returns
     * a machine for each, pinned where the plan put it: modules in the plan's order, then the walls. A module whose
     * ammunition the catalog has no way to make is left out (it would stand dry), and so are optional ports.
     */
    public static List<Machine> machines(Catalog cat, expansion.defense.Planner.Plan plan, StringBuilder notes){
        Map<Integer, List<Placement>> modules = new LinkedHashMap<>(), walls = new LinkedHashMap<>();
        Map<Integer, expansion.defense.Planner.Supply> supplies = new HashMap<>();
        for(expansion.defense.Planner.Supply s : plan.supplies) supplies.put(s.module, s);
        for(Placement p : plan.placements){
            if(p.kind == Placement.Kind.port && p.optional) continue;
            (p.kind == Placement.Kind.wall && !supplies.containsKey(p.module) ? walls : modules).computeIfAbsent(p.module, k -> new ArrayList<>()).add(p);
        }
        List<Machine> out = new ArrayList<>(), wallLines = new ArrayList<>();
        for(var e : walls.entrySet()){
            Machine m = machine(cat, "defence:walls#" + e.getKey(), e.getValue(), null, notes);
            if(m != null) wallLines.add(m);
        }
        for(var e : modules.entrySet()){
            expansion.defense.Planner.Supply s = supplies.get(e.getKey());
            String name = "defence:" + (s == null ? "module" : s.name) + "#" + e.getKey();
            Machine m = machine(cat, name, e.getValue(), s, notes);
            if(m != null) out.add(m);
        }
        //the wall lines first: cheap (beryllium only), up long before the first module is paid for, and they hold the
        //enemy where the modules will fire
        out.addAll(0, wallLines);
        return out;
    }

    /**
     * Our item turrets that stand with nothing leading ammunition into them (the map's own, say, or ours whose line was
     * lost): each a design of the turret and one port beside it, on its free side nearest the core, fed with the first of
     * what it fires that can be had. The turret stands, so only the port's line is built.
     */
    public static List<Machine> existing(Catalog cat, Team team, Collection<String> feedable, StringBuilder notes){
        List<Machine> out = new ArrayList<>();
        var core = team.core();
        if(core == null) return out;
        int id = 1000;
        for(mindustry.gen.Building b : team.data().buildings){
            if(!(b.block instanceof mindustry.world.blocks.defense.turrets.ItemTurret it)) continue;
            String ammo = null;
            for(String want : new String[]{"beryllium", "graphite", "silicon", "tungsten", "oxide", "carbide", "thorium"}){
                if(!feedable.contains(want)) continue;
                for(var t : it.ammoTypes.keys()) if(t.name.equals(want)) ammo = want;
                if(ammo != null) break;
            }
            if(ammo == null || fed(b)){
                notes.append(String.format("%s at %d,%d %s\n", b.block.name, b.tileX(), b.tileY(), ammo == null ? "fires nothing that can be had" : "has a line into it"));
                continue;
            }
            //a free tile beside it, the nearest to the core
            int[] best = null;
            float bestD = Float.MAX_VALUE;
            for(int[] t : Grid.around(b.block.size, b.tileX(), b.tileY())){
                Tile tile = Vars.world.tile(t[0], t[1]);
                if(tile == null || tile.build != null || !mindustry.world.Build.validPlace(Blocks.duct, team, t[0], t[1], 0)) continue;
                float d = core.dst(tile.worldx(), tile.worldy());
                if(d < bestD){
                    bestD = d;
                    best = t;
                }
            }
            if(best == null){
                notes.append(String.format("%s at %d,%d has no free side for a line\n", b.block.name, b.tileX(), b.tileY()));
                continue;
            }
            List<Placement> parts = new ArrayList<>();
            parts.add(new Placement(b.block.name, b.tileX(), b.tileY(), 0, null, Placement.Kind.turret, id));
            parts.add(new Placement("item-source", best[0], best[1], 0, ammo, Placement.Kind.port, id));
            Machine m = machine(cat, "defence:fed-" + b.block.name + "@" + b.tileX() + "," + b.tileY(), parts, null, notes, 1f);
            if(m != null) out.add(m);
            id++;
        }
        return out;
    }

    /** Whether something of the turret's side leads into it: a duct facing it, or a router, bridge or unloader beside it. */
    static boolean fed(mindustry.gen.Building turret){
        for(mindustry.gen.Building n : turret.proximity){
            if(n.team != turret.team) continue;
            if(n.block instanceof mindustry.world.blocks.distribution.Duct && n.front() == turret) return true;
            if(n.block instanceof mindustry.world.blocks.distribution.DuctRouter || n.block instanceof mindustry.world.blocks.distribution.DuctBridge
                || n.block instanceof mindustry.world.blocks.distribution.DirectionalUnloader) return true;
        }
        return false;
    }

    static Machine machine(Catalog cat, String name, List<Placement> parts, expansion.defense.Planner.Supply supply, StringBuilder notes){
        return machine(cat, name, parts, supply, notes, leastRate);
    }

    static Machine machine(Catalog cat, String name, List<Placement> parts, expansion.defense.Planner.Supply supply, StringBuilder notes, float least){
        //the corner of all its blocks, and their extent
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        Set<Long> body = new HashSet<>();
        for(Placement p : parts){
            Block b = block(p);
            if(b == null){
                notes.append(name).append(": no block ").append(p.block).append('\n');
                return null;
            }
            int size = p.kind == Placement.Kind.port ? 1 : b.size, off = (size - 1) / 2;
            minX = Math.min(minX, p.x - off);
            minY = Math.min(minY, p.y - off);
            maxX = Math.max(maxX, p.x - off + size - 1);
            maxY = Math.max(maxY, p.y - off + size - 1);
            if(p.kind != Placement.Kind.port) for(int dx = 0; dx < size; dx++) for(int dy = 0; dy < size; dy++) body.add(key(p.x - off + dx, p.y - off + dy));
        }
        Seq<Stile> tiles = new Seq<>();
        List<Placement> ports = new ArrayList<>();
        for(Placement p : parts){
            Block b = block(p);
            Object config = null;
            if(p.kind == Placement.Kind.port){
                config = p.liquid ? Vars.content.liquid(p.config) : Vars.content.item(p.config);
                ports.add(p);
            }else if(p.config != null){
                config = Vars.content.item(p.config) != null ? Vars.content.item(p.config) : Vars.content.liquid(p.config);
            }
            tiles.add(new Stile(b, p.x - minX, p.y - minY, config, (byte)p.rotation));
        }
        Schematic s = new Schematic(tiles, new StringMap(), maxX - minX + 1, maxY - minY + 1);
        float power = supply == null ? 0f : supply.power;
        Design d = new Design(name, name, s, power);
        int n = cat.size();
        float[] in = new float[n];
        for(int i = 0; i < ports.size(); i++){
            Placement p = ports.get(i);
            Res r = cat.byName.get(p.config);
            if(r == null){
                notes.append(name).append(": ").append(p.config).append(" is not made here; left out\n");
                return null;
            }
            float rate = least;
            if(supply != null) for(String[] need : supply.needs) if(need[0].equals(p.config)) rate = Math.max(rate, Float.parseFloat(need[2]));
            //the way into the module: towards the one block beside the port (a plain duct faces it; a liquid router any way)
            int dir = -1;
            for(int k = 0; k < 4; k++){
                if(body.contains(key(p.x + Geometry.d4x(k), p.y + Geometry.d4y(k)))){
                    dir = k;
                    break;
                }
            }
            Design.Port port = new Design.Port(true, p.liquid, p.config, p.x - minX, p.y - minY, rate);
            port.index = parts.indexOf(p);
            port.turn = dir < 0 ? -1 : p.liquid ? dir : 4 + dir;
            d.ports.add(port);
            in[r.id] += rate;
        }
        int[] cost = new int[n];
        for(var e : d.cost) cost[cat.id(e.key)] += Math.round(e.value * Vars.state.rules.buildCostMultiplier);
        Recipe r = new Recipe(name, name, Math.max(d.width(0), d.height(0)), Recipe.Ground.none, cost, d.buildTicks, power, 0f,
            in, new float[n], new float[n], -1, 0f, 1f, -1, -1, 0f);
        r.module = d;
        cat.recipes.add(r);
        Machine m = new Machine(r, -1, 1f, minX, minY, 0f, cost.clone(), false, false);
        m.pinned = new int[]{minX, minY, 0};
        return m;
    }

    static long key(int x, int y){
        return ((long)x << 32) ^ (y & 0xffffffffL);
    }

    /** The block a placement puts down: a port is the sandbox source that marks it in a design. */
    static Block block(Placement p){
        if(p.kind == Placement.Kind.port) return p.liquid ? Blocks.liquidSource : Blocks.itemSource;
        return Vars.content.block(p.block);
    }

    /** Whether a machine is one of the defence's. */
    public static boolean is(Machine m){
        return m.recipe.name.startsWith("defence:");
    }
}
