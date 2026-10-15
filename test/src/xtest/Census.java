package xtest;

import arc.math.geom.*;
import arc.struct.*;
import expansion.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.type.*;
import mindustry.world.*;

/**
 * What generated maps have to offer beyond the first tier: vents, liquid in the floor, and the ores
 * only the bigger drills get at. Says what an enemy that builds further could ever have to build with.
 *
 * Usage: Census data-dir blueprint-dir seed...
 */
public class Census{
    public static void main(String[] args){
        Sim.boot(args[0], args[1]);
        Brain brain = Sim.brain;
        for(int a = 2; a < args.length; a++){
            int seed = Integer.parseInt(args[a]);
            Team enemy = Wild.make(seed, "plain");
            if(enemy == null) continue;
            Boot.frames(1);
            brain.territory.update(enemy.cores(), Vars.state.rules.defaultTeam.cores());
            brain.survey.scan();
            StringBuilder out = new StringBuilder(String.format("%5d %dx%d:", seed, Vars.world.width(), Vars.world.height()));
            IntSeq vents = brain.terrain.vents(Blocks.turbineCondenser, enemy);
            int ventsOurs = 0;
            for(int i = 0; i < vents.size; i++) if(brain.territory.ours(Point2.x(vents.get(i)), Point2.y(vents.get(i)))) ventsOurs++;
            out.append(" vents ").append(ventsOurs).append("/").append(vents.size);

            //liquid in the floor, by what a pump would get out of it
            ObjectIntMap<Liquid> all = new ObjectIntMap<>(), ours = new ObjectIntMap<>(), nearest = new ObjectIntMap<>();
            for(Tile tile : Vars.world.tiles){
                Liquid liquid = tile.floor().liquidDrop;
                if(liquid == null || tile.block().isStatic()) continue;
                all.increment(liquid);
                //a pump stands on it, and has to be got to over land: how far the shore is from home
                int far = Integer.MAX_VALUE;
                for(int d = 0; d < 4; d++){
                    int x = tile.x + Geometry.d4x(d), y = tile.y + Geometry.d4y(d);
                    Tile other = Vars.world.tile(x, y);
                    if(other != null && Survey.ground(other)) far = Math.min(far, brain.territory.fromHome(x, y));
                }
                if(brain.territory.ours(tile.x, tile.y) || (far < 5000 && brain.territory.ours(tile.x, tile.y))) ours.increment(liquid);
                if(far < nearest.get(liquid, Integer.MAX_VALUE)) nearest.put(liquid, far);
            }
            for(var entry : all){
                out.append("  ").append(entry.key.name).append(" floor ").append(ours.get(entry.key, 0)).append("/").append(entry.value)
                    .append(" (shore nearest home ").append(nearest.get(entry.key, -1) == Integer.MAX_VALUE ? "-" : nearest.get(entry.key, -1) + "").append(")");
            }
            for(Item item : new Item[]{Items.tungsten, Items.thorium}){
                IntSeq faces = brain.survey.ore(item), floor = brain.survey.floor(item);
                int f = 0, g = 0;
                for(int i = 0; i < faces.size; i++) if(brain.territory.ours(brain.survey.x(faces.get(i)), brain.survey.y(faces.get(i)))) f++;
                for(int i = 0; i < floor.size; i++) if(brain.territory.ours(Point2.x(floor.get(i)), Point2.y(floor.get(i)))) g++;
                out.append("  ").append(item.name).append(" wall ").append(f).append("/").append(faces.size).append(" floor ").append(g).append("/").append(floor.size);
            }
            System.out.println(out);
        }
        System.exit(0);
    }
}
