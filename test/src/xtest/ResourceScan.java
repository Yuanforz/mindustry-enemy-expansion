package xtest;

import arc.struct.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.meta.*;

/**
 * What each map the game's generator makes for Erekir has in reach of the player's core: the ores in walls and on the
 * floor, the liquids on the floor, vents and sand walls, counted by tiles. For finding maps with every kind of ground,
 * on which a side can climb the whole tech from nothing.
 *
 * Usage: ResourceScan data-dir first-seed last-seed [radius]
 */
public class ResourceScan{
    /** What a side needs the ground to give, to make everything Erekir has. */
    static final String[] wanted = {"wall:beryllium", "wall:graphite", "wall:tungsten", "floor:tungsten", "floor:thorium", "wall:thorium", "sand", "vent", "liquid:slag", "liquid:arkycite"};
    //(no water on Erekir's floors: the vent and turbine condensers make it)

    public static void main(String[] args){
        Boot.boot(args[0]);
        int first = Integer.parseInt(args[1]), last = Integer.parseInt(args[2]);
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : 110;
        for(int seed = first; seed <= last; seed++){
            Team enemy;
            try{
                enemy = Wild.make(seed, "plain");
            }catch(Throwable t){
                System.out.println("seed " + seed + ": failed " + t);
                continue;
            }
            if(enemy == null){
                System.out.println("seed " + seed + ": no map");
                continue;
            }
            var core = Vars.state.rules.defaultTeam.core();
            ObjectIntMap<String> found = census(core.tileX(), core.tileY(), radius);
            int have = 0;
            StringBuilder missing = new StringBuilder();
            for(String w : wanted){
                //tungsten and thorium count once, from a wall or the floor
                if(w.equals("wall:tungsten") || w.equals("wall:thorium")) continue;
                boolean ok = found.get(w, 0) > 0 || (w.equals("floor:tungsten") && found.get("wall:tungsten", 0) > 0) || (w.equals("floor:thorium") && found.get("wall:thorium", 0) > 0);
                if(ok) have++;
                else missing.append(' ').append(w);
            }
            System.out.println(String.format("seed %d: %dx%d, %d of 8 kinds%s; %s", seed, Vars.world.width(), Vars.world.height(), have, missing.length() == 0 ? " (all)" : ", missing" + missing, found));
        }
        System.exit(0);
    }

    /** Tiles of each kind of ground within the radius of a place. */
    static ObjectIntMap<String> census(int cx, int cy, int radius){
        ObjectIntMap<String> found = new ObjectIntMap<>();
        for(Tile t : Vars.world.tiles){
            if(Math.hypot(t.x - cx, t.y - cy) > radius) continue;
            if(t.solid()){
                Item drop = t.wallDrop();
                if(drop != null) found.increment("wall:" + drop.name);
                if(t.block().attributes.get(Attribute.sand) > 0) found.increment("sand");
                continue;
            }
            if(t.overlay() != null && t.overlay().itemDrop != null) found.increment("floor:" + t.overlay().itemDrop.name);
            else if(t.floor().itemDrop != null && t.floor().playerUnmineable == false && t.floor().itemDrop != Items.sand) found.increment("floor:" + t.floor().itemDrop.name);
            if(t.floor().liquidDrop != null) found.increment("liquid:" + t.floor().liquidDrop.name);
            if(t.floor().attributes.get(Attribute.steam) > 0) found.increment("vent");
        }
        return found;
    }
}
