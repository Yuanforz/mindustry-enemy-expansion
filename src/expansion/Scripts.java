package expansion;

import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.world.blocks.logic.*;
import mindustry.world.blocks.logic.LogicBlock.*;

import static mindustry.Vars.*;

/**
 * Finds the places a map's own scripting works with, so nothing gets built in its way.
 *
 * Campaign maps stage themselves with world processors: they open up more of the map, switch linked
 * factories on, spawn units and set off explosions at fixed spots.
 */
public class Scripts{

    /** Tiles that belong to the map's scripting. */
    public static IntSet reserved(){
        IntSet out = new IntSet();
        for(Building build : Groups.build){
            if(!(build instanceof LogicBuild logic) || !((LogicBlock)build.block).privileged) continue;

            //buildings the script switches on and off: if one is destroyed, its place stays free for it
            for(LogicLink link : logic.links){
                Building linked = world.build(link.x, link.y);
                if(linked != null){
                    linked.tile.getLinkedTiles(tile -> out.add(tile.pos()));
                }else{
                    area(out, link.x, link.y, 2);
                }
            }

            for(String line : logic.code.split("\n")){
                String[] args = line.trim().split("\\s+");
                switch(args[0]){
                    //spawn type x y rotation team result
                    case "spawn" -> spot(out, args, 2, 3);
                    //spawnwave x y natural
                    case "spawnwave" -> spot(out, args, 1, 4);
                    //explosion team x y radius ...
                    case "explosion" -> spot(out, args, 2, 2 + (int)number(args, 4, 0));
                    //setblock layer block x y team rotation
                    case "setblock" -> spot(out, args, 3, 1);
                    default -> {}
                }
            }
        }
        return out;
    }

    /** Only fixed coordinates can be known ahead of time; ones the script computes are left out. */
    private static void spot(IntSet out, String[] args, int index, int radius){
        float x = number(args, index, Float.NaN), y = number(args, index + 1, Float.NaN);
        if(!Float.isNaN(x) && !Float.isNaN(y)) area(out, (int)x, (int)y, radius);
    }

    private static float number(String[] args, int index, float fallback){
        return index < args.length && Strings.canParseFloat(args[index]) ? Strings.parseFloat(args[index]) : fallback;
    }

    private static void area(IntSet out, int x, int y, int radius){
        for(int dx = -radius; dx <= radius; dx++){
            for(int dy = -radius; dy <= radius; dy++){
                if(dx * dx + dy * dy <= radius * radius && world.tiles.in(x + dx, y + dy)) out.add(Point2.pack(x + dx, y + dy));
            }
        }
    }
}
