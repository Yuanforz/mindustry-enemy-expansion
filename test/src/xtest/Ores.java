package xtest;

import arc.files.*;
import arc.math.geom.*;
import arc.struct.*;
import expansion.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.core.GameState.*;
import mindustry.io.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.production.*;

/** How much ore lies in the floor on each map, where, and how many places a drill could stand on enough of it. */
public class Ores{
    public static void main(String[] args){
        Sim.boot(args[0], args[1]);
        Brain brain = Sim.brain;
        for(int a = 2; a < args.length; a++){
            Vars.logic.reset();
            SaveIO.load(new Fi(args[a]));
            Vars.state.set(State.playing);
            arc.util.Time.setDeltaProvider(() -> 1f);
            Boot.frames(1);
            Sim.campaignRules(true);
            Boot.frames(30);
            var enemy = Vars.state.rules.waveTeam;
            if(enemy.cores().isEmpty()) continue;
            brain.territory.update(enemy.cores(), Vars.state.rules.defaultTeam.cores());
            brain.survey.scan();
            Drill drill = (Drill)Blocks.impactDrill;
            StringBuilder out = new StringBuilder(new Fi(args[a]).nameWithoutExtension() + ":");
            for(Item item : new Item[]{Items.beryllium, Items.tungsten, Items.thorium, Items.graphite}){
                IntSeq tiles = brain.survey.floor(item);
                int ours = 0, spots = 0, free = 0, nearest = 9999;
                for(int i = 0; i < tiles.size; i++){
                    int x = Point2.x(tiles.get(i)), y = Point2.y(tiles.get(i));
                    if(!brain.territory.ours(x, y)) continue;
                    ours++;
                    //a drill built from here: how much of what it stands on is this ore
                    int count = 0;
                    boolean clear = true;
                    for(int dx = -1; dx <= 2; dx++) for(int dy = -1; dy <= 2; dy++){
                        Tile t = Vars.world.tile(x + dx, y + dy);
                        if(t == null || t.solid() || t.build != null) clear = false;
                        if(t != null && drill.canMine(t) && drill.getDrop(t) == item) count++;
                    }
                    if(count >= 10){ spots++; if(clear){ free++; nearest = Math.min(nearest, brain.territory.fromHome(x, y)); } }
                }
                if(tiles.size > 0) out.append("  ").append(item.name).append(" in the floor ").append(tiles.size).append(", on its side ").append(ours).append(", places for a drill ").append(spots).append(" (free ").append(free).append(free > 0 ? ", the nearest " + nearest + " tiles from a core" : "").append(")");
            }
            //ore in the walls that only the big wall drill gets at: how many faces of it, and how many places that drill could stand with one, two or all three of its beams on it
            BeamDrill bore = (BeamDrill)Blocks.largePlasmaBore;
            brain.ground.team = enemy;
            brain.syncGround();
            for(Item item : new Item[]{Items.tungsten, Items.thorium}){
                IntSeq faces = brain.survey.ore(item);
                int ours = 0, nearest = 9999;
                int[] fit = new int[4];
                IntSet seen = new IntSet();
                for(int i = 0; i < faces.size; i++){
                    int face = faces.get(i), fx = brain.survey.x(face), fy = brain.survey.y(face), d = Survey.direction(face);
                    if(!brain.territory.ours(fx, fy) || brain.territory.fromHome(fx, fy) > 110) continue;
                    ours++;
                    for(int gap = 0; gap <= 2; gap++){
                        for(int lane = 0; lane < 3; lane++){
                            bore.nearbySide(0, 0, d, lane, arc.util.Tmp.p1);
                            int ox = fx + Geometry.d4x(d) * (1 - gap) - arc.util.Tmp.p1.x, oy = fy + Geometry.d4y(d) * (1 - gap) - arc.util.Tmp.p1.y;
                            if(!brain.ground.clear(bore, ox, oy) || !seen.add((ox + oy * 1000) * 4 + d)) continue;
                            int beams = brain.fitter.beams(bore, ox, oy, d, item);
                            fit[beams]++;
                            if(beams >= 2) nearest = Math.min(nearest, brain.territory.fromHome(fx, fy));
                        }
                    }
                }
                if(faces.size > 0) out.append("\n    ").append(item.name).append(" in walls: faces ").append(faces.size).append(", on its side within reach ").append(ours).append("; places for a big bore with 1/2/3 beams on it: ").append(fit[1]).append("/").append(fit[2]).append("/").append(fit[3]).append(nearest < 9999 ? ", nearest with 2+ " + nearest + " from a core" : "");
            }
            System.out.println(out);
        }
        System.exit(0);
    }
}
