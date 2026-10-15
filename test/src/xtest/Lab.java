package xtest;

import arc.files.*;
import arc.struct.*;
import expansion.*;
import expansion.Blueprint.*;
import mindustry.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.liquid.*;
import mindustry.world.blocks.power.*;

/**
 * A place to try things out by hand: a layout written down block by block is put on an empty map
 * with the ground it needs, the game is run, and everything in it says how it is doing. For finding
 * out how blocks have to stand to work together before making a blueprint of them.
 *
 * Usage: Lab data-dir file.txt [name] [seconds]
 * The file is in the blueprint format; sandbox sources (item-source, liquid-source, power-source) may
 * be used in it, with what they give as their setting.
 */
public class Lab{
    public static void main(String[] args){
        Boot.boot(args[0]);
        Bench.install();
        String only = args.length > 2 ? args[2] : null;
        int seconds = args.length > 3 ? Integer.parseInt(args[3]) : 120;

        for(Blueprint blueprint : Blueprint.parse(new Fi(args[1]).readString())){
            if(only != null && !only.equals("all") && !blueprint.name.equals(only)) continue;
            Bench.world();
            Layout layout = blueprint.at(24, 24, 0, false, 1);
            Bench.terrain(layout, mindustry.content.Items.beryllium);
            Bench.build(layout.parts);

            Boot.frames(60 * 60);
            Bench.arrived.clear();
            Bench.turnedOut.clear();
            ObjectFloatMap<Building> before = new ObjectFloatMap<>();
            Boot.frames(60 * seconds);

            System.out.println("#### " + blueprint.name + " after " + (60 + seconds) + " s");
            System.out.println(Bench.picture());
            for(Part part : layout.parts){
                Building build = Vars.world.build(part.x, part.y);
                if(build == null){
                    System.out.println("  " + part.block.name + " at " + part.x + "," + part.y + ": NOT STANDING");
                    continue;
                }
                //the ducts and conduits themselves only say something when they hold something odd
                boolean transport = build.block instanceof Duct || build.block instanceof Conduit || build.block instanceof BeamNode;
                StringBuilder line = new StringBuilder("  " + part.block.name + " at " + (part.x - 24) + "," + (part.y - 24) + ":");
                if(build.block.hasPower && build.block.consPower != null && !build.block.consPower.buffered) line.append(String.format(" power %.0f%%", build.power.status * 100f));
                if(build instanceof PowerGenerator.GeneratorBuild generator) line.append(String.format(" makes %.2f power/tick", generator.getPowerProduction()));
                if(build.block.hasConsumers || build.block instanceof mindustry.world.blocks.production.GenericCrafter) line.append(String.format(" efficiency %.0f%%", build.efficiency * 100f));
                if(build.items != null && build.items.any()) line.append(" items ").append(build.items);
                if(build.liquids != null){
                    for(Liquid liquid : Vars.content.liquids()){
                        if(build.liquids.get(liquid) > 0.01f) line.append(String.format(" %s %.1f/%.0f", liquid.name, build.liquids.get(liquid), build.block.liquidCapacity));
                    }
                }
                if(build instanceof mindustry.world.blocks.heat.HeatBlock heat && heat.heat() > 0f) line.append(String.format(" gives heat %.1f", heat.heat()));
                if(build instanceof mindustry.world.blocks.heat.HeatConsumer consumer){
                    float total = 0f;
                    for(float side : consumer.sideHeat()) total += side;
                    line.append(String.format(" gets heat %.1f of %.1f", total, consumer.heatRequirement()));
                }
                if(!transport || line.toString().contains("items") || verbose(build)) System.out.println(line);
            }
            StringBuilder got = new StringBuilder("  core got:");
            for(var entry : Bench.arrived) got.append(String.format(" %s %.2f/s", entry.key.name, entry.value / (float)seconds));
            for(var entry : Bench.turnedOut) got.append(" ").append(entry.key.name).append(" ×").append(entry.value);
            System.out.println(got);
        }
        System.exit(0);
    }

    private static boolean verbose(Building build){
        return build.block instanceof Conduit && build.liquids.currentAmount() > 0.01f && build.front() == null;
    }
}
