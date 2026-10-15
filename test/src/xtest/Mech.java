package xtest;

import mindustry.*;
import mindustry.content.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.ConstructBlock.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.storage.*;

/**
 * Asks the game how it treats one block put over another, and what taking a block down gives back.
 * The answers are what replacing old things with better ones is built on.
 *
 * Usage: Mech data-dir
 */
public class Mech{
    public static void main(String[] args){
        Boot.boot(args[0]);
        Bench.install();
        Bench.world();
        var team = Bench.team;
        CoreBlock.CoreBuild core = team.core();
        for(Item item : Vars.content.items()) core.items.set(item, 1500);

        //one block over another: does the game allow it?
        Object[][] pairs = {
            {Blocks.breach, Blocks.diffuse}, {Blocks.breach, Blocks.titan}, {Blocks.breach, Blocks.disperse}, {Blocks.titan, Blocks.breach},
            {Blocks.berylliumWallLarge, Blocks.tungstenWallLarge}, {Blocks.berylliumWall, Blocks.tungstenWall}, {Blocks.berylliumWall, Blocks.tungstenWallLarge},
            {Blocks.duct, Blocks.armoredDuct}, {Blocks.duct, Blocks.ductRouter}, {Blocks.plasmaBore, Blocks.largePlasmaBore},
            {Blocks.tankFabricator, Blocks.mechFabricator}, {Blocks.turbineCondenser, Blocks.ventCondenser}, {Blocks.beamNode, Blocks.beamTower},
            {Blocks.reinforcedConduit, Blocks.reinforcedLiquidRouter}, {Blocks.reinforcedContainer, Blocks.reinforcedVault},
        };
        for(Object[] pair : pairs){
            Block old = (Block)pair[0], next = (Block)pair[1];
            Bench.put(old, 20, 20, 0, null);
            boolean ok = Build.validPlace(next, team, 20, 20, 0);
            System.out.println(String.format("  %-26s over %-24s %s (groups %s / %s)", next.name, old.name, ok ? "allowed" : "NOT allowed", next.group, old.group));
            Vars.world.tile(20, 20).setAir();
            for(int dx = -3; dx <= 3; dx++) for(int dy = -3; dy <= 3; dy++) if(Vars.world.build(20 + dx, 20 + dy) != null) Vars.world.tile(20 + dx, 20 + dy).setAir();
        }

        //what it costs and gives back: a breach built, replaced by a diffuse, then taken down
        Unit unit = UnitTypes.evoke.create(team);
        unit.set(22 * 8, 22 * 8);
        unit.add();
        report("start", core);
        Call.beginPlace(unit, Blocks.breach, team, 20, 20, 0, null);
        finish(20, 20, unit, core);
        report("breach built (costs " + java.util.Arrays.toString(Blocks.breach.requirements) + ")", core);
        Call.beginPlace(unit, Blocks.diffuse, team, 20, 20, 0, null);
        finish(20, 20, unit, core);
        report("diffuse put over it (costs " + java.util.Arrays.toString(Blocks.diffuse.requirements) + "); now stands " + Vars.world.tile(20, 20).block().name, core);
        Call.beginBreak(unit, team, 20, 20);
        for(int i = 0; i < 4000 && Vars.world.tile(20, 20).build instanceof ConstructBuild site; i++) site.deconstruct(unit, core, 0.01f);
        report("diffuse taken down; now stands " + Vars.world.tile(20, 20).block().name + "; refund rule " + Vars.state.rules.deconstructRefundMultiplier, core);

        //a bigger core over a smaller
        System.out.println("  citadel over the bastion at the core: " + (Build.validPlace(Blocks.coreCitadel, team, core.tileX(), core.tileY(), 0) ? "allowed" : "NOT allowed")
            + "; core holds up to " + core.storageCapacity + " of each");
        Bench.put(Blocks.reinforcedContainer, core.tileX() + 3, core.tileY(), 0, null);
        Boot.frames(2);
        System.out.println("  with a container against it the core holds up to " + core.storageCapacity);

        //what water does for a turret
        for(Block block : new Block[]{Blocks.breach, Blocks.diffuse, Blocks.titan, Blocks.disperse}){
            Turret turret = (Turret)block;
            float extra = turret.coolant == null ? 0f : turret.coolant.amount * Liquids.water.heatCapacity * turret.coolantMultiplier;
            System.out.println(String.format("  %s: reload %.0f ticks; with water %.1f%% of its rate, taking %.1f water/s while it reloads", block.name, turret.reload, (1f + extra) * 100f, turret.coolant == null ? 0f : turret.coolant.amount * 60f));
        }
        System.exit(0);
    }

    static void finish(int x, int y, Unit unit, CoreBlock.CoreBuild core){
        for(int i = 0; i < 4000 && Vars.world.tile(x, y).build instanceof ConstructBuild site; i++) site.construct(unit, core, 0.01f, null);
    }

    static void report(String what, CoreBlock.CoreBuild core){
        StringBuilder out = new StringBuilder("  " + what + ": ");
        for(Item item : Vars.content.items()) if(core.items.get(item) != 1500) out.append(item.name).append(' ').append(core.items.get(item) - 1500).append("  ");
        System.out.println(out);
    }
}
