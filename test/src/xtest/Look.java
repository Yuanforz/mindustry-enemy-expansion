package xtest;

import arc.files.*;
import arc.struct.*;
import expansion.*;
import mindustry.*;
import mindustry.core.GameState.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.environment.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.storage.*;
import mindustry.world.blocks.units.*;

/**
 * Looks at a game somebody saved: what each side has, where, and what the enemy is thinking; then,
 * if asked, lets it run on with nobody playing and looks again.
 *
 * Usage: Look data-dir blueprint-dir save.msav [minutes [scale]]
 */
public class Look{
    public static void main(String[] args){
        Sim.boot(args[0], args[1]);
        Brain brain = Sim.brain;
        brain.unlocked = c -> true;
        int minutes = args.length > 3 ? Integer.parseInt(args[3]) : 0;
        int scale = args.length > 4 ? Integer.parseInt(args[4]) : 2;

        Vars.logic.reset();
        SaveIO.load(new Fi(args[2]));
        Vars.state.set(State.playing);
        arc.util.Time.setDeltaProvider(() -> 1f);
        Boot.frames(2);
        Team enemy = Vars.state.rules.waveTeam;
        Rules rules = Vars.state.rules;
        System.out.println("######## " + new Fi(args[2]).name() + ": " + Vars.world.width() + "x" + Vars.world.height() + ", minute " + (int)(Vars.state.tick / 3600f)
            + ", fog " + rules.fog + ", squads " + rules.teams.get(enemy).rtsAi + ", enemy build speed x" + rules.buildSpeed(enemy) + ", free items " + (rules.teams.get(enemy).infiniteResources || rules.teams.get(enemy).fillItems));
        say(enemy, "enemy");
        say(rules.defaultTeam, "player");
        System.out.println(map(scale));
        System.out.println("stock: " + Sim.stock());
        System.out.println(brain.status());

        if(minutes > 0){
            Sim.watch(minutes, enemy);
            say(enemy, "enemy");
            System.out.println(map(scale));
        }
        System.exit(0);
    }

    static void say(Team team, String name){
        ObjectIntMap<String> blocks = new ObjectIntMap<>(), units = new ObjectIntMap<>();
        for(Building b : team.data().buildings) blocks.increment(b.block.name);
        for(Unit u : team.data().units) units.increment(u.type.name + " (" + u.controller().getClass().getSimpleName() + ")");
        StringBuilder cores = new StringBuilder();
        for(var core : team.cores()) cores.append(core.block.name).append(" at ").append(core.tileX()).append(',').append(core.tileY()).append("  ");
        System.out.println(name + ": " + cores + "\n  buildings " + blocks + "\n  units " + units);
        for(Unit u : team.data().units) System.out.println("    " + u.type.name + " at " + u.tileX() + "," + u.tileY() + " health " + (int)u.health);
    }

    /** The whole map as text, one character for a square of tiles; the most telling thing in the square is what shows. */
    static String map(int scale){
        Team enemy = Vars.state.rules.waveTeam, player = Vars.state.rules.defaultTeam;
        int w = Vars.world.width(), h = Vars.world.height();
        char[][] grid = new char[(h + scale - 1) / scale][(w + scale - 1) / scale];
        int[][] rank = new int[grid.length][grid[0].length];
        for(Tile tile : Vars.world.tiles){
            char c;
            int r;
            if(tile.build != null && (tile.team() == enemy || tile.team() == player)){
                boolean own = tile.team() == enemy;
                Block b = tile.block();
                if(b instanceof CoreBlock){ c = own ? 'X' : 'O'; r = 9; }
                else if(b instanceof Turret){ c = own ? 'G' : 'T'; r = 8; }
                else if(b instanceof UnitFactory || b instanceof Reconstructor){ c = own ? 'Y' : 'U'; r = 7; }
                else if(b instanceof GenericCrafter){ c = own ? 'F' : 'M'; r = 6; }
                else if(b instanceof BeamDrill || b instanceof Drill || b instanceof WallCrafter){ c = own ? 'D' : 'B'; r = 6; }
                else if(b instanceof Wall){ c = own ? 'w' : 'H'; r = 5; }
                else if(b instanceof PowerNode || b instanceof PowerGenerator){ c = own ? 'n' : 'N'; r = 3; }
                else if(b instanceof Duct || b instanceof DuctRouter || b instanceof DuctBridge || b instanceof OverflowDuct){ c = own ? '+' : '='; r = 4; }
                else{ c = own ? 'e' : 'P'; r = 4; }
                //what is the player's shows through over the enemy's of the same kind, as that is what is being looked for
                if(!own) r += 1;
            }else if(tile.build != null){
                c = '?'; r = 2;
            }else if(tile.block().solid){
                c = tile.wallDrop() != null ? '%' : '#'; r = 1;
            }else if(tile.floor() instanceof SteamVent){
                c = ':'; r = 2;
            }else if(tile.floor().isDeep() || tile.floor().solid || !tile.floor().placeableOn){
                c = '~'; r = 1;
            }else{
                c = ' '; r = 0;
            }
            int gx = tile.x / scale, gy = tile.y / scale;
            if(grid[gy][gx] == 0 || r > rank[gy][gx]){ grid[gy][gx] = c; rank[gy][gx] = r; }
        }
        for(Unit u : Groups.unit){
            int gx = u.tileX() / scale, gy = u.tileY() / scale;
            if(gy < 0 || gx < 0 || gy >= grid.length || gx >= grid[0].length || rank[gy][gx] >= 8) continue;
            grid[gy][gx] = u.team == enemy ? (u.controller() instanceof ExpansionAI ? 'b' : 'u') : 'v';
            rank[gy][gx] = 8;
        }
        StringBuilder out = new StringBuilder("X G Y F D w n + e = enemy core, turret, yard, factory, drill, wall, power, duct, other; b its builder, u its units\nO T U M B H N = P = the player's; v the player's units; # wall, % ore wall, ~ no ground, : vent\n");
        for(int y = grid.length - 1; y >= 0; y--){
            out.append(String.format("%4d ", y * scale)).append(grid[y]).append('\n');
        }
        out.append("     ");
        for(int x = 0; x < grid[0].length; x++) out.append(x % 5 == 0 ? String.valueOf((x * scale / 10) % 10) : " ");
        return out.toString();
    }
}
