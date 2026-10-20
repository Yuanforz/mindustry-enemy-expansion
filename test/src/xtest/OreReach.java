package xtest;

import arc.files.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.io.*;
import mindustry.world.*;

/** Where an ore is on a map, from our core: floor ore and wall ore tiles by distance band, and how many of them the other side's turrets reach. */
public class OreReach{
    public static void main(String[] args){
        Boot.boot(args[0]);
        mindustry.type.Item item = Vars.content.item(args[1]);
        for(int a = 2; a < args.length; a++){
            Vars.logic.reset();
            SaveIO.load(new Fi(args[a]));
            Vars.logic.play();
            Boot.frames(2);
            Team us = Vars.state.rules.defaultTeam;
            var home = us.core();
            boolean[] shot = expansion.econ.Sites.danger(us);
            int w = Vars.world.width();
            int[] floor = new int[6], floorShot = new int[6], wall = new int[6], wallShot = new int[6];
            for(Tile t : Vars.world.tiles){
                int band = Math.min(5, (int)(Math.hypot(t.x - home.tileX(), t.y - home.tileY()) / 50));
                boolean s = shot[t.x + t.y * w];
                if(t.block() == Blocks.air && t.drop() == item){ floor[band]++; if(s) floorShot[band]++; }
                if(t.solid() && t.wallDrop() == item){ wall[band]++; if(s) wallShot[band]++; }
            }
            StringBuilder b = new StringBuilder(String.format("%-15s", new Fi(args[a]).nameWithoutExtension()));
            for(int i = 0; i < 6; i++) b.append(String.format(" | <%d: floor %d (%d shot) wall %d (%d shot)", (i + 1) * 50, floor[i], floorShot[i], wall[i], wallShot[i]));
            System.out.println(b);
        }
        System.exit(0);
    }
}
