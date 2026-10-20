package xtest;

import arc.files.*;
import arc.struct.*;
import mindustry.*;
import mindustry.game.*;
import mindustry.io.*;
import mindustry.world.*;

/** Where on a map a core of ours could go (core-zone floor with nothing on it), how far from our core, and whether the other side's turrets reach it. */
public class CoreZones{
    public static void main(String[] args){
        Boot.boot(args[0]);
        for(int a = 1; a < args.length; a++){
            Vars.logic.reset();
            SaveIO.load(new Fi(args[a]));
            Vars.logic.play();
            Boot.frames(2);
            Team us = Vars.state.rules.defaultTeam;
            var home = us.core();
            boolean[] shot = expansion.econ.Sites.danger(us);
            int w = Vars.world.width(), h = Vars.world.height();
            boolean[] taken = new boolean[w * h];
            StringBuilder b = new StringBuilder(new Fi(args[a]).nameWithoutExtension() + ": our core " + home.tileX() + "," + home.tileY() + "; free core zones:");
            int n = 0;
            for(int y = 0; y < h - 3; y++) for(int x = 0; x < w - 3; x++){
                boolean ok = true, danger = false;
                for(int dx = 0; dx < 4 && ok; dx++) for(int dy = 0; dy < 4 && ok; dy++){
                    Tile t = Vars.world.tile(x + dx, y + dy);
                    if(t == null || !t.floor().allowCorePlacement || t.build != null || taken[x + dx + (y + dy) * w]) ok = false;
                    else if(shot[x + dx + (y + dy) * w]) danger = true;
                }
                if(!ok) continue;
                for(int dx = 0; dx < 4; dx++) for(int dy = 0; dy < 4; dy++) taken[x + dx + (y + dy) * w] = true;
                n++;
                b.append(String.format(" %d,%d (%d tiles%s)", x + 1, y + 1, (int)Math.hypot(x - home.tileX(), y - home.tileY()), danger ? ", under fire" : ""));
            }
            System.out.println(b + " [" + n + "]");
        }
        System.exit(0);
    }
}
