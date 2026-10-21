package expansion.defense;

import arc.struct.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;

import java.util.*;

/**
 * Where the defence planner meets the game: the map read into a {@link Terrain}, block sizes and costs read from the
 * real blocks, the enemy's waves read into counts, and a plan's placements put down on the map. Everything else in this
 * package is plain Java and does not need the game.
 */
public final class GameData{
    private GameData(){}

    /** Puts the real size and turning of every block into Battery.shapes. */
    public static void shapes(){
        for(Block b : Vars.content.blocks()) Battery.shapes.put(b.name, new int[]{b.size, b.rotate ? 1 : 0});
    }

    /** What every block costs, by item name. */
    public static Map<String, Map<String, Integer>> costs(){
        Map<String, Map<String, Integer>> out = new HashMap<>();
        for(Block b : Vars.content.blocks()){
            Map<String, Integer> c = new TreeMap<>();
            for(ItemStack s : b.requirements) c.put(s.item.name, s.amount);
            out.put(b.name, c);
        }
        return out;
    }

    /** The map as it is now, from the side of the given team. */
    public static Terrain terrain(Team us){
        int w = Vars.world.width(), h = Vars.world.height();
        Terrain t = new Terrain(w, h);
        float radius = Vars.state.rules.enemyCoreBuildRadius;
        Seq<Building> otherCores = new Seq<>();
        for(Teams.TeamData data : Vars.state.teams.active) if(data.team != us) otherCores.addAll(data.cores);
        for(int x = 0; x < w; x++) for(int y = 0; y < h; y++){
            Tile tile = Vars.world.tile(x, y);
            boolean rock = (tile.block().isStatic() && tile.block().solid) || tile.floor().solid;
            if(rock) t.set(x, y, Terrain.solid);
            if(tile.floor().isDeep()) t.set(x, y, Terrain.deep);
            if(tile.legSolid()) t.set(x, y, Terrain.legSolid);
            if(tile.build != null || (!rock && tile.block() != Blocks.air && !tile.block().alwaysReplace)) t.set(x, y, Terrain.built);
            if(tile.build != null && tile.build.team != us) t.set(x, y, Terrain.enemy);
            if(!Vars.world.isInMapArea(x, y)) t.set(x, y, Terrain.noBuild);
        }
        //(the game's own costs for a ground unit of the side that attacks: the way it walks, not the shortest)
        Team attacker = null;
        for(Teams.TeamData data : Vars.state.teams.active) if(data.team != us && data.team != Team.derelict && (attacker == null || data.cores.size > 0)) attacker = data.team;
        if(attacker == null) attacker = Vars.state.rules.waveTeam;
        //(by the way it finds its way: units led as a player leads them (the Brain's, a base's squads) by the control
        //pathfinder, that puts a million on each tile of another side's block and so goes round any wall it can; the map's
        //waves by the flow field, that walks into a wall at what its health costs)
        t.rts = attacker.rules().rtsAi || attacker != Vars.state.rules.waveTeam;
        int led = 0, flowed = 0;
        for(Unit u : attacker.data().units){
            if(u.controller() instanceof mindustry.ai.types.CommandAI) led++;
            else if(u.controller() instanceof mindustry.ai.types.GroundAI) flowed++;
        }
        if(led + flowed > 0) t.rts = led >= flowed;
        mindustry.ai.Pathfinder.PathCost flow = mindustry.ai.Pathfinder.costTypes.get(mindustry.ai.Pathfinder.costGround);
        t.groundCost = new float[w * h];
        for(int x = 0; x < w; x++) for(int y = 0; y < h; y++){
            int c = (t.rts ? mindustry.ai.ControlPathfinder.costGround : flow).getCost(attacker.id, Vars.pathfinder.get(x, y));
            t.groundCost[x + y * w] = c < 0 ? 0f : Math.max(0f, c - 1f);
        }
        for(Building core : otherCores){
            int r = (int)Math.ceil(radius / Vars.tilesize);
            for(int x = core.tileX() - r; x <= core.tileX() + r; x++) for(int y = core.tileY() - r; y <= core.tileY() + r; y++){
                if(core.within(x * Vars.tilesize, y * Vars.tilesize, radius)) t.set(x, y, Terrain.noBuild);
            }
        }
        //the map's scripts set off explosions and put blocks at fixed spots: nothing there either
        arc.struct.IntSet scripted = expansion.Scripts.reserved();
        for(var it = scripted.iterator(); it.hasNext; ){
            int pos = it.next();
            t.set(arc.math.geom.Point2.x(pos), arc.math.geom.Point2.y(pos), Terrain.noBuild);
        }
        //the game wipes out what stands near a ground spawn when a wave comes: nothing there
        if(Vars.state.rules.waves && Vars.state.hasSpawns()){
            int r = (int)Math.ceil(Vars.state.rules.dropZoneRadius / Vars.tilesize);
            for(Tile spawn : Vars.spawner.getSpawns()){
                for(int x = spawn.x - r; x <= spawn.x + r; x++) for(int y = spawn.y - r; y <= spawn.y + r; y++){
                    if((x - spawn.x) * (x - spawn.x) + (y - spawn.y) * (y - spawn.y) <= (r + 1) * (r + 1)) t.set(x, y, Terrain.noBuild);
                }
            }
        }
        return t;
    }

    /** What makes a port on the map: a sandbox source in the lab, the router a supply line ends in on a real base. */
    public interface PortMaker{
        Block block(Placement port);

        void configure(Building built, Placement port);
    }

    /** Sandbox sources, set to what the port takes: supply laid on without a line, for tests. */
    public static final PortMaker sources = new PortMaker(){
        @Override
        public Block block(Placement port){
            return port.liquid ? Blocks.liquidSource : Blocks.itemSource;
        }

        @Override
        public void configure(Building built, Placement port){
            Object what = port.liquid ? Vars.content.liquid(port.config) : Vars.content.item(port.config);
            if(what != null) built.configureAny(what);
        }
    };

    /** Puts the placements down at once (no building time, no cost). Returns what could not go up. */
    public static List<Placement> place(List<Placement> plan, Team team, PortMaker ports){
        List<Placement> failed = new ArrayList<>();
        for(Placement p : plan){
            Block block = p.kind == Placement.Kind.port ? ports.block(p) : Vars.content.block(p.block);
            Tile tile = Vars.world.tile(p.x, p.y);
            if(block == null || tile == null){
                failed.add(p);
                continue;
            }
            tile.setBlock(block, team, p.rotation);
            if(tile.build == null){
                failed.add(p);
                continue;
            }
            if(p.kind == Placement.Kind.port) ports.configure(tile.build, p);
        }
        return failed;
    }

    /** The units of every wave from first to last (1-based), by unit name, from the map's own spawn groups. */
    public static List<Map<String, Integer>> waves(int first, int last){
        List<Map<String, Integer>> out = new ArrayList<>();
        for(int wave = first; wave <= last; wave++){
            Map<String, Integer> units = new TreeMap<>();
            for(SpawnGroup g : Vars.state.rules.spawns){
                int n = g.getSpawned(wave - 1);
                if(n > 0) units.merge(g.type.name, n, Integer::sum);
            }
            out.add(units);
        }
        return out;
    }

    /** The blocks a team may build, by name: everything on the planet that is not sandbox-only and not banned. */
    public static Set<String> buildable(Set<String> unlocked){
        Set<String> out = new HashSet<>();
        for(Block b : Vars.content.blocks()){
            if(b.buildVisibility == mindustry.world.meta.BuildVisibility.sandboxOnly) continue;
            if(Vars.state.rules.isBanned(b)) continue;
            if(unlocked == null || unlocked.contains(b.name)) out.add(b.name);
        }
        return out;
    }
}
