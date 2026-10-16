package xtest;

import arc.files.*;
import arc.struct.*;
import mindustry.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.world.blocks.defense.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.storage.*;
import mindustry.world.blocks.units.*;

/** What guards each of the other side's cores on a map: turrets, walls and yards within so many tiles. Usage: Guards data map.msav [tiles] */
public class Guards{
    public static void main(String[] args){
        Boot.boot(args[0]);
        Vars.logic.reset();
        SaveIO.load(new Fi(args[1]));
        float r = (args.length > 2 ? Float.parseFloat(args[2]) : 30f) * 8f;
        for(Building build : Groups.build){
            if(build.team == Vars.state.rules.defaultTeam && build instanceof CoreBlock.CoreBuild) System.out.println("player core " + build.block.name + " at " + build.tileX() + "," + build.tileY());
        }
        for(Building core : Groups.build){
            if(!(core instanceof CoreBlock.CoreBuild) || core.team == Vars.state.rules.defaultTeam) continue;
            ObjectIntMap<String> count = new ObjectIntMap<>();
            int wallHp = 0;
            StringBuilder yards = new StringBuilder();
            for(Building b : Groups.build){
                if(b.team != core.team || !b.within(core, r)) continue;
                if(b.block instanceof Turret) count.increment(b.block.name);
                if(b.block instanceof Wall){ count.increment("wall"); wallHp += b.health; }
                if(b.block instanceof UnitFactory || b.block instanceof Reconstructor) yards.append(' ').append(b.block.name).append('@').append(b.tileX()).append(',').append(b.tileY());
            }
            System.out.println(core.block.name + " " + core.team + " at " + core.tileX() + "," + core.tileY() + " in area " + Vars.world.isInMapArea(core.tileX(), core.tileY()) + ": " + count + " wall hp " + wallHp + " yards" + yards);
        }
    }
}
