package xtest;

import arc.files.*;
import expansion.*;
import mindustry.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.type.*;
import mindustry.world.*;

/**
 * Whether units the mod steers get from one place on a map to another: a few of a kind set down at a place and sent
 * against the other side's building nearest a second place, with where they are every few seconds.
 * Usage: PathLab data-dir blueprint-dir map.msav unit x y tx ty [seconds]
 */
public class PathLab{
    public static void main(String[] args){
        Sim.boot(args[0], args[1]);
        arc.Core.settings.put("expansion-enabled", false);
        Vars.logic.reset();
        SaveIO.load(new Fi(args[2]));
        Vars.state.rules.waves = false;
        Vars.logic.play();
        Brain.side = Vars.state.rules.defaultTeam;
        UnitType type = Vars.content.unit(args[3]);
        int x = Integer.parseInt(args[4]), y = Integer.parseInt(args[5]), tx = Integer.parseInt(args[6]), ty = Integer.parseInt(args[7]);
        int seconds = args.length > 8 ? Integer.parseInt(args[8]) : 60;
        Building target = Vars.indexer.findEnemyTile(Vars.state.rules.defaultTeam, tx * 8f, ty * 8f, 400f, b -> true);
        System.out.println("target: " + (target == null ? "none" : target.block.name + "@" + target.tileX() + "," + target.tileY()) + "; path cost " + type.pathCostId + ", field type " + type.flowfieldPathType);
        Unit[] units = new Unit[3];
        for(int i = 0; i < units.length; i++){
            Unit unit = type.create(Vars.state.rules.defaultTeam);
            unit.set((x + i) * 8f, y * 8f);
            unit.add();
            if(System.getProperty("lab.vanilla") == null){
                FighterAI ai = new FighterAI(ExpansionMod.brain);
                unit.controller(ai);
                if(target != null) ai.attack(target);
            }else if(target != null && System.getProperty("lab.vanilla").equals("command") && unit.controller() instanceof mindustry.ai.types.CommandAI c){
                c.commandPosition(new arc.math.geom.Vec2(target.x, target.y));
            }
            units[i] = unit;
        }
        for(int s = 0; s < seconds; s += 5){
            Boot.frames(300);
            StringBuilder out = new StringBuilder("  t=" + (s + 5));
            for(Unit unit : units) out.append(" | ").append(unit.isValid() ? unit.tileX() + "," + unit.tileY() + (unit.controller() instanceof FighterAI f ? " " + f.how : " " + unit.controller().getClass().getSimpleName()) : "dead");
            System.out.println(out);
        }
        System.exit(0);
    }
}
