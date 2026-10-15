package xtest;

import arc.files.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.core.GameState.*;
import mindustry.io.*;
import mindustry.world.*;

/** Why the game does or does not let the enemy place a block on a map. Usage: Place data-dir blueprint-dir map.msav block */
public class Place{
    public static void main(String[] args){
        Sim.boot(args[0], args[1]);
        Vars.logic.reset();
        SaveIO.load(new Fi(args[2]));
        Vars.state.set(State.playing);
        Vars.state.tick = 0;
        arc.util.Time.setDeltaProvider(() -> 1f);
        Boot.frames(1);
        Sim.campaignRules(true);
        Boot.frames(60);
        Block block = Vars.content.block(args[3]);
        var team = Vars.state.rules.waveTeam;
        var r = Vars.state.rules;
        System.out.println("banned=" + r.isBanned(block) + " placeable=" + block.isPlaceable() + " visible=" + block.isVisible() + " envBuildable=" + block.environmentBuildable()
            + " placeRangeCheck=" + r.placeRangeCheck + " polygonCoreProtection=" + r.polygonCoreProtection + " enemyCoreBuildRadius=" + r.enemyCoreBuildRadius + " fog=" + r.fog + " isAI=" + team.isAI()
            + " bannedBlocks=" + r.bannedBlocks + " whitelist=" + r.blockWhitelist);
        int ok = 0, open = 0;
        for(Tile tile : Vars.world.tiles){
            if(tile.build != null || tile.solid() || !Vars.world.isInMapArea(tile.x, tile.y)) continue;
            open++;
            if(Build.validPlace(block, team, tile.x, tile.y, 0)) ok++;
        }
        System.out.println(block.name + ": allowed on " + ok + " of " + open + " open tiles");
        System.exit(0);
    }
}
