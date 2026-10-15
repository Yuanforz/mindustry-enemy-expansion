package tools;

import arc.files.*;
import mindustry.*;
import mindustry.io.*;
import mindustry.type.*;
import mindustry.world.blocks.storage.*;

public class CoreItems{
    public static void main(String[] args){
        Boot.boot(args[0]);
        for(int a = 1; a < args.length; a++){
            Vars.logic.reset();
            SaveIO.load(new Fi(args[a]));
            StringBuilder b = new StringBuilder(new Fi(args[a]).name() + ":");
            for(var team : new mindustry.game.Team[]{Vars.state.rules.waveTeam, Vars.state.rules.defaultTeam}){
                b.append("\n   ").append(team).append(" unitCap=").append(Vars.state.rules.unitCap).append(" ");
                for(var t : Vars.world.tiles){
                    if(t.build instanceof CoreBlock.CoreBuild core && t.build.tile == t && t.team() == team){
                        b.append(core.block.name).append("{");
                        for(Item i : Vars.content.items()) if(core.items.get(i) > 0) b.append(i.name).append('=').append(core.items.get(i)).append(' ');
                        b.append("} ");
                        break;
                    }
                }
            }
            System.out.println(b);
        }
        System.exit(0);
    }
}
