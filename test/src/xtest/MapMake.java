package xtest;

import arc.files.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.world.*;

/**
 * A map the game's own generator makes for Erekir (Wild.make: a sector's terrain by the seed, the player's core where the
 * generator puts it), with the other side taken away (a sandbox), saved for the layout generator and the bench.
 *
 * Usage: MapMake data-dir seed out.msav
 */
public class MapMake{
    public static void main(String[] args){
        Boot.boot(args[0]);
        int seed = Integer.parseInt(args[1]);
        Team them = Wild.make(seed, "plain");
        if(them == null){
            System.out.println("MAP gen:" + seed + ": the generator gave no map");
            System.exit(1);
        }
        for(Tile tile : Vars.world.tiles) if(tile.build != null && tile.team() == them) tile.setBlock(Blocks.air);
        for(Unit u : Groups.unit.copy(new arc.struct.Seq<>())) if(u.team == them) u.remove();
        Vars.state.rules.attackMode = false;
        Vars.state.rules.waves = false;
        Vars.state.rules.defaultTeam.rules().blockHealthMultiplier = 1f;
        var core = Vars.state.rules.defaultTeam.core();
        SaveIO.save(new Fi(args[2]));
        System.out.println("MAP gen:" + seed + ": " + Vars.world.width() + "x" + Vars.world.height() + ", our core " + core.block.name + " at " + core.tileX() + "," + core.tileY() + "; saved to " + args[2]);
        System.exit(0);
    }
}
