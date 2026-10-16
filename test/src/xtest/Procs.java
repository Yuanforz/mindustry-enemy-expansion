package xtest;

import arc.files.*;
import mindustry.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.world.blocks.logic.*;

/** The world processors of a map and what they run. Usage: Procs data map.msav */
public class Procs{
    public static void main(String[] args){
        Boot.boot(args[0]);
        Vars.logic.reset();
        SaveIO.load(new Fi(args[1]));
        for(Building build : Groups.build){
            if(!(build instanceof LogicBlock.LogicBuild logic)) continue;
            System.out.println("==== " + build.block.name + " at " + build.tileX() + "," + build.tileY() + " team " + build.team);
            System.out.println(logic.code);
        }
    }
}
