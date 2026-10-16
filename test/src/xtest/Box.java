package xtest;

import arc.files.*;
import mindustry.*;
import mindustry.io.*;
import mindustry.world.*;

/**
 * What stands in a stretch of a saved game: every building in a box, with its team and block, and every tile of ore in
 * the walls there. Usage: Box data-dir blueprints save.msav x0 y0 w h
 */
public class Box{
    public static void main(String[] args){
        Sim.boot(args[0], args[1]);
        arc.Core.settings.put("expansion-enabled", false);
        Vars.logic.reset();
        SaveIO.load(new Fi(args[2]));
        int x0 = Integer.parseInt(args[3]), y0 = Integer.parseInt(args[4]), w = Integer.parseInt(args[5]), h = Integer.parseInt(args[6]);
        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        for(int x = x0; x < x0 + w; x++) for(int y = y0; y < y0 + h; y++){
            Tile t = Vars.world.tile(x, y);
            if(t == null || t.build == null || !seen.add(t.build.pos())) continue;
            String more = t.build instanceof mindustry.world.blocks.production.BeamDrill.BeamDrillBuild b ? " facing " + b.rotation + " mining " + b.lastItem + " eff " + b.efficiency : "";
            System.out.println(t.build.block.name + "@" + t.build.tileX() + "," + t.build.tileY() + " " + t.build.team + more);
        }
        for(int x = x0; x < x0 + w; x++) for(int y = y0; y < y0 + h; y++){
            Tile t = Vars.world.tile(x, y);
            if(t != null && t.solid() && t.wallDrop() != null) System.out.println("ore wall " + t.wallDrop().name + " at " + x + "," + y);
        }
        System.exit(0);
    }
}
