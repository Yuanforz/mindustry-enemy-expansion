package xtest;

import arc.files.*;
import mindustry.*;
import mindustry.io.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.*;
import mindustry.world.blocks.storage.*;

/** How the enemy's cores are walled in on each map: of the tiles around each, how many are free, walls, or something else. */
public class Cores{
    public static void main(String[] args){
        Boot.boot(args[0]);
        for(int a = 1; a < args.length; a++){
            Vars.logic.reset();
            SaveIO.load(new Fi(args[a]));
            StringBuilder out = new StringBuilder(new Fi(args[a]).nameWithoutExtension() + ":");
            for(Tile t : Vars.world.tiles){
                if(!(t.build instanceof CoreBlock.CoreBuild core) || t.build.tile != t || t.team() != Vars.state.rules.waveTeam) continue;
                int free = 0, walls = 0, other = 0, size = core.block.size, off = -(size - 1) / 2;
                for(int i = 0; i < size; i++){
                    for(int side = 0; side < 4; side++){
                        int x = t.x + off + (side == 0 ? size : side == 2 ? -1 : i), y = t.y + off + (side == 1 ? size : side == 3 ? -1 : i);
                        Tile o = Vars.world.tile(x, y);
                        if(o == null) continue;
                        if(o.build == null && !o.solid()) free++;
                        else if(o.build != null && o.block() instanceof Wall) walls++;
                        else other++;
                    }
                }
                out.append("  ").append(core.block.name.replace("core-", "")).append("@").append(t.x).append(",").append(t.y).append(" free ").append(free).append(" walls ").append(walls).append(" other ").append(other);
            }
            System.out.println(out);
        }
        System.exit(0);
    }
}
