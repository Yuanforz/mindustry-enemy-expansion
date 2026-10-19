package xtest;

import arc.files.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.io.*;

/** For looking into Reach: on a map as it starts, whether each kind of unit gets from our core to each of the other side's. */
public class ReachProbe{
    public static void main(String[] args){
        Boot.boot(args[0]);
        for(int a = 1; a < args.length; a++){
            Vars.logic.reset();
            SaveIO.load(new Fi(args[a]));
            Vars.logic.play();
            Boot.frames(3);
            Rules r = Vars.state.rules;
            if(r.limitMapArea){
                int x = r.limitX, y = r.limitY, w = r.limitWidth, h = r.limitHeight;
                r.limitMapArea = false;
                Vars.world.checkMapArea(x, y, w, h);
            }
            Boot.frames(3);
            Team us = r.defaultTeam, them = r.waveTeam;
            var home = us.core();
            StringBuilder b = new StringBuilder(new Fi(args[a]).nameWithoutExtension() + ": our core " + home.tileX() + "," + home.tileY() + " (limited " + r.limitX + "," + r.limitY + " " + r.limitWidth + "x" + r.limitHeight + ")");
            for(var c : them.cores()){
                b.append("\n  their core ").append(c.tileX()).append(",").append(c.tileY()).append(":");
                for(var kind : new mindustry.type.UnitType[]{UnitTypes.stell, UnitTypes.merui, UnitTypes.elude, UnitTypes.risso}) b.append(" ").append(kind.name).append("=").append(expansion.army.Reach.can(kind, us, home.tileX(), home.tileY(), c.tileX(), c.tileY()));
            }
            System.out.println(b);
        }
        System.exit(0);
    }
}
