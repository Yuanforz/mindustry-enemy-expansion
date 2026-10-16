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
            if(t.build.power != null) more += " power " + t.build.power.status + (t.build.power.graph == null ? "" : " grid " + t.build.power.graph.getPowerBalance() * 60f + "/s of " + t.build.power.graph.all.size);
            if(t.build.items != null && t.build.items.any()) more += " holds " + t.build.items;
            System.out.println(t.build.block.name + "@" + t.build.tileX() + "," + t.build.tileY() + " " + t.build.team + more);
        }
        for(int x = x0; x < x0 + w; x++) for(int y = y0; y < y0 + h; y++){
            Tile t = Vars.world.tile(x, y);
            if(t != null && t.solid() && t.wallDrop() != null && System.getProperty("box.ore") != null) System.out.println("ore wall " + t.wallDrop().name + " at " + x + "," + y);
        }
        //vents: the middle tile of each three by three of steam, what stands on it, and how far from the side played's core
        var core = Vars.state.rules.defaultTeam.core();
        for(int x = x0; x < x0 + w; x++) for(int y = y0; y < y0 + h; y++){
            Tile t = Vars.world.tile(x, y);
            if(t == null || t.floor().attributes.get(mindustry.world.meta.Attribute.steam) <= 0f) continue;
            boolean middle = true;
            for(int dx = -1; dx <= 1 && middle; dx++) for(int dy = -1; dy <= 1 && middle; dy++){
                Tile o = Vars.world.tile(x + dx, y + dy);
                middle = o != null && o.floor().attributes.get(mindustry.world.meta.Attribute.steam) > 0f;
            }
            if(!middle) continue;
            System.out.println("vent at " + x + "," + y + (t.build == null ? " free" : " under " + t.build.block.name + " of " + t.build.team) + (core == null ? "" : " " + (int)(core.dst(t) / 8f) + " from core") + (Vars.world.isInMapArea(x, y) ? "" : " (map not open there)"));
        }
        System.exit(0);
    }
}
