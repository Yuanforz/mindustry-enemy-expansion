package xtest;

import expansion.econ.*;
import mindustry.content.*;

import java.util.*;

/**
 * Checks the economy model against sums done by hand: a T1 tank cell (one arc furnace, three tank fabricators and what
 * feeds them) with enough power and with too little, and a plan for it played forward in time.
 *
 * Usage: EconCheck data-dir
 */
public class EconCheck{
    static int failures;

    public static void main(String[] args){
        Boot.boot(args[0]);
        Bench.install();
        Bench.world();
        Catalog cat = Recipes.read(List.of("plasma-bore", "cliff-crusher", "turbine-condenser", "silicon-arc-furnace", "tank-fabricator"),
            (mindustry.world.blocks.storage.CoreBlock)Blocks.coreBastion, 1f);
        for(Recipe r : cat.recipes){
            StringBuilder in = new StringBuilder(), out = new StringBuilder();
            for(int k = 0; k < cat.size(); k++){
                if(r.in[k] > 0f) in.append(String.format(" %s %.3f", cat.get(k).name, r.in[k]));
                if(r.out[k] > 0f) out.append(String.format(" %s %.3f", cat.get(k).name, r.out[k]));
                if(r.perFactor[k] > 0f && cat.get(k).kind == Res.Kind.item && (k == cat.id("beryllium") || k == cat.id("graphite") || k == cat.id("sand") || k == cat.id("tungsten"))) out.append(String.format(" %s %.4f/yield", cat.get(k).name, r.perFactor[k]));
            }
            System.out.println(String.format("  %-34s build %.1fs cost %d power -%.0f +%.0f | in%s | out%s%s", r.name, cat.buildSeconds(r), r.totalCost(), r.powerUse, r.powerOut, in, out,
                r.makesUnits() ? String.format(" | a %s every %.0fs", cat.get(r.unitOut).name, r.unitSeconds) : ""));
        }
        System.out.println(String.format("  core unit: digs %.2f/s, carries %d, flies %.1f tiles/s, hands over within %.1f tiles, builds at %.2f", cat.mineRate, cat.carry, cat.flySpeed, cat.handOverRange, cat.buildSpeed));

        int be = cat.id("beryllium"), gr = cat.id("graphite"), sand = cat.id("sand"), si = cat.id("silicon"), stell = cat.id("stell");
        check("hand digging, by the source", cat.mineRate, 60f / 11f, 0.01f);

        List<Machine> cell = cell(cat, 4);
        Plant plant = new Plant(cat);
        plant.solve(cell, null, true);
        System.out.println(String.format("  cell, 4 turbines: power met %.3f; furnace %.3f; stell %.4f/s; to the core: beryllium %.3f, graphite %.3f, silicon %.3f; sand made %.3f used %.3f",
            plant.powerMet, plant.eff[indexOf(cell, "silicon-arc-furnace")], plant.unitRate(cell, stell), plant.toCore[be], plant.toCore[gr], plant.toCore[si], plant.supply[sand], plant.used[sand]));
        //by hand: 3 fabricators take silicon 4.286 and beryllium 3.429; one furnace makes 4.8 silicon from 4.8 sand and 1.2 graphite
        //power wanted: furnace 300 x (4.286/4.8) + 3 x 90 + crushers 4 x 11 x (sand used/made) + bores ...: under 720, so all is met
        check("power met with four turbines", plant.powerMet, 1f, 0.001f);
        check("stell a second from three fabricators", plant.unitRate(cell, stell), 3f / 35f, 0.002f);
        check("beryllium left for the core", plant.toCore[be], 5 * 0.75f - 3f * 40f / 35f, 0.02f);
        //four crushers on walls of 2 points make 4 x 2 x 60/110 = 4.364 sand, so the furnace runs at 4.364/4.8
        check("furnace held back by sand", plant.eff[indexOf(cell, "silicon-arc-furnace")], 4f * 2f * 60f / 110f / 4.8f, 0.002f);
        check("silicon left for the core", plant.toCore[si], 4f * 2f * 60f / 110f - 3f * 50f / 35f, 0.01f);

        List<Machine> weak = cell(cat, 2);
        plant.solve(weak, null, true);
        System.out.println(String.format("  cell, 2 turbines: power met %.3f of %.0f wanted; stell %.4f/s", plant.powerMet, plant.powerWanted, plant.unitRate(weak, stell)));
        check("power with two turbines: what 360 meets of what is wanted", plant.powerMet, 360f / plant.powerWanted, 0.01f);
        check("stell held back by power as much as power is short", plant.unitRate(weak, stell), 3f / 35f * plant.powerMet, 0.004f);
        
        //the same cell built from nothing, in a plain order, with the core unit digging beryllium and graphite beside the core
        Field field = new Field(cat);
        field.coreX = 50;
        field.coreY = 50;
        field.handDistance[be] = 10f;
        field.handDistance[gr] = 10f;
        Timeline line = new Timeline(cat, field, cell);
        line.runToGoal(stell, 3600f);
        System.out.println(String.format("  built from nothing: all done at %.0fs, first stell at %.0fs", line.finished[cell.size() - 1], line.first[stell]));
        for(int i = 0; i < cell.size(); i++) System.out.println(String.format("    %-26s begun %6.1fs finished %6.1fs", cell.get(i).recipe.name, line.begun[i], line.finished[i]));
        check("the cell gets built", line.done() ? 1f : 0f, 1f, 0f);

        System.out.println(failures == 0 ? "RESULT: all checks pass" : "RESULT: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** Turbines first, then bores, crushers, the furnace and three fabricators. */
    static List<Machine> cell(Catalog cat, int turbines){
        List<Machine> list = new ArrayList<>();
        int be = cat.id("beryllium"), gr = cat.id("graphite"), sand = cat.id("sand");
        for(int i = 0; i < turbines; i++) list.add(machine(cat, "turbine-condenser", -1, 1f));
        for(int i = 0; i < 5; i++) list.add(machine(cat, "plasma-bore", be, 2f));
        for(int i = 0; i < 2; i++) list.add(machine(cat, "plasma-bore", gr, 2f));
        for(int i = 0; i < 4; i++) list.add(machine(cat, "cliff-crusher", sand, 2f));
        list.add(machine(cat, "silicon-arc-furnace", -1, 1f));
        for(int i = 0; i < 3; i++) list.add(machine(cat, "tank-fabricator:stell", -1, 1f));
        return list;
    }

    static Machine machine(Catalog cat, String recipe, int item, float factor){
        Recipe r = cat.recipe(recipe);
        return new Machine(r, item, factor, 55, 50, 5f, r.cost.clone(), false, false);
    }

    static int indexOf(List<Machine> list, String recipe){
        for(int i = 0; i < list.size(); i++) if(list.get(i).recipe.name.equals(recipe)) return i;
        return -1;
    }

    static void check(String what, float got, float want, float within){
        boolean ok = Math.abs(got - want) <= within;
        if(!ok) failures++;
        System.out.println(String.format("  %s %s: %.4f (want %.4f)", ok ? "pass" : "FAIL", what, got, want));
    }
}
