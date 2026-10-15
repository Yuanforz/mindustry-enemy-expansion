package xtest;

import arc.math.geom.*;
import arc.struct.*;
import expansion.*;
import expansion.Blueprint.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.world.*;
import mindustry.world.blocks.production.*;

/** Why a drill for ore in the floor finds no place on a generated map: every place with room for it, and what stops each way of laying it out there. Usage: PadWhy data blueprints seed minutes */
public class PadWhy{
    public static void main(String[] args){
        Sim.boot(args[0], args[1]);
        Sim.tech("full");
        Team enemy = Wild.make(Integer.parseInt(args[2]), "plain");
        Boot.frames(Integer.parseInt(args[3]) * 3600);
        Brain brain = Sim.brain;
        System.out.println(brain.tryOut("tungsten"));
        System.out.println("note: " + brain.planner.note + " | " + brain.fitter.padNote);
        Blueprint pad = brain.blueprints.find(b -> b.name.equals("impact-pad"));
        Drill drill = (Drill)pad.main();
        brain.syncGround();
        //as for a first source of something: any ground will do
        brain.ground.rule = (x, y) -> true;
        IntSeq tiles = brain.survey.floor(Items.tungsten);
        int shown = 0;
        for(int i = 0; i < tiles.size && shown < 4; i++){
            int x = Point2.x(tiles.get(i)), y = Point2.y(tiles.get(i));
            if((x + y) % 2 != 0 || !brain.ground.clear(drill, x, y)) continue;
            int ore = 0;
            for(int dx = -1; dx <= 2; dx++) for(int dy = -1; dy <= 2; dy++){
                Tile t = Vars.world.tile(x + dx, y + dy);
                if(t != null && drill.canMine(t) && drill.getDrop(t) == Items.tungsten) ore++;
            }
            if(ore < 4) continue;
            shown++;
            System.out.println("place " + x + "," + y + ": " + ore + " tiles of ore under it, " + brain.territory.fromHome(x, y) + " from home");
            for(int way = 0; way < 8; way++){
                Layout layout = pad.at(x, y, way % 4, way >= 4, 1);
                String why = null;
                for(Part part : layout.parts){
                    if(!brain.ground.clear(part.block, part.x, part.y)){ why = "no room for " + part.block.name + " at " + part.x + "," + part.y; break; }
                    if(!Build.validPlace(part.block, enemy, part.x, part.y, part.rotation)){ why = "the game refuses " + part.block.name + " at " + part.x + "," + part.y; break; }
                }
                if(why == null && !brain.fitter.valid(layout)) why = "stands against something it would feed or be fed by";
                System.out.println("   way " + way + ": " + (why == null ? "fits" : why));
            }
        }
        System.exit(0);
    }
}
