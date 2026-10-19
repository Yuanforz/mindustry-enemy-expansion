package xtest;

import arc.files.*;
import arc.struct.*;
import mindustry.*;
import mindustry.io.*;
import mindustry.world.*;

/**
 * For each map: how many tiles of each ore (on the floor, in walls) and of each liquid floor it has, and how far from the
 * player's core the nearest is (tiles). For choosing maps to try the late tech on.
 *
 * Usage: OreCount data-dir map.msav|gen:seed...
 */
public class OreCount{
    public static void main(String[] args){
        Boot.boot(args[0]);
        for(int a = 1; a < args.length; a++){
            Vars.logic.reset();
            if(args[a].startsWith("gen:")){
                if(Wild.make(Integer.parseInt(args[a].substring(4)), "plain") == null) continue;
            }else SaveIO.load(new Fi(args[a]));
            var core = Vars.state.rules.defaultTeam.core();
            ObjectIntMap<String> count = new ObjectIntMap<>();
            ObjectFloatMap<String> near = new ObjectFloatMap<>();
            for(Tile t : Vars.world.tiles){
                String k = null;
                if(t.block().isStatic() && t.overlay() != null && t.overlay().itemDrop != null && t.overlay().wallOre) k = "wall:" + t.overlay().itemDrop.name;
                else if(t.block().isStatic() && t.block().itemDrop != null) k = "wall:" + t.block().itemDrop.name;
                else if(!t.solid() && t.overlay() != null && t.overlay().itemDrop != null) k = "floor:" + t.overlay().itemDrop.name;
                else if(!t.solid() && t.floor().liquidDrop != null) k = "liquid:" + t.floor().liquidDrop.name;
                if(k == null) continue;
                count.increment(k);
                if(core != null){
                    float d = core.tile.dst(t) / Vars.tilesize;
                    if(!near.containsKey(k) || d < near.get(k, 0f)) near.put(k, d);
                }
            }
            StringBuilder b = new StringBuilder(args[a].replaceAll(".*/", "") + ":");
            for(var e : count) b.append(String.format(" %s=%d(%.0f)", e.key, e.value, near.get(e.key, -1f)));
            System.out.println(b);
        }
        System.exit(0);
    }
}
