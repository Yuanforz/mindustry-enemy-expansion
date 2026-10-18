package xtest;

import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.storage.*;

/**
 * Measures, in the game itself, the numbers the economy model is built on: how fast the core unit builds and
 * whether a block in progress stalls when the core runs short, how fast it digs ore out of a wall, and whether
 * machines draw power while they have nothing to work on.
 *
 * Usage: EconProbe data-dir
 */
public class EconProbe{
    static final Team team = Bench.team;

    public static void main(String[] args){
        Boot.boot(args[0]);
        Bench.install();
        Bench.world();
        CoreBlock.CoreBuild core = team.core();
        UnitType type = ((CoreBlock)core.block).unitType;
        System.out.println("core " + core.block.name + " holds " + core.storageCapacity + " of each item; its unit " + type.name);
        System.out.println(String.format("  unit: buildSpeed %.2f, mineSpeed %.2f, mineTier %d, mineWalls %b, mineFloor %b, speed %.2f/tick (%.2f tiles/s), itemCapacity %d, mineRange %.0f (%.1f tiles), buildRange %.0f (%.1f tiles)",
            type.buildSpeed, type.mineSpeed, type.mineTier, type.mineWalls, type.mineFloor, type.speed, type.speed * 60f / 8f, type.itemCapacity, type.mineRange, type.mineRange / 8f, Vars.buildingRange, Vars.buildingRange / 8f));
        System.out.println(String.format("  rules: buildSpeed(team) %.2f, buildCostMultiplier %.2f, unitBuildSpeed(team) %.2f, deconstructRefund %.2f",
            Vars.state.rules.buildSpeed(team), Vars.state.rules.buildCostMultiplier, Vars.state.rules.unitBuildSpeed(team), Vars.state.rules.deconstructRefundMultiplier));

        //building with plenty in the core: measured against buildTime / (buildSpeed x rule) ticks
        for(Item item : Vars.content.items()) core.items.set(item, 1000);
        Block[] blocks = {Blocks.plasmaBore, Blocks.turbineCondenser, Blocks.cliffCrusher, Blocks.siliconArcFurnace, Blocks.tankFabricator, Blocks.beamNode, Blocks.duct};
        int x = 6;
        for(Block block : blocks){
            int bx = x + block.sizeOffset * -1, by = 40;
            if(block == Blocks.turbineCondenser) Vars.world.tile(bx, by).getLinkedTilesAs(block, t -> t.setFloor(Blocks.rhyoliteVent.asFloor()));
            float predicted = block.buildTime / (type.buildSpeed * Vars.state.rules.buildSpeed(team));
            float took = build(type, block, bx, by, core, -1);
            System.out.println(String.format("  build %-20s buildTime %6.0f  predicted %6.0f ticks  took %6.0f ticks (%.1f s)", block.name, block.buildTime, predicted, took, took / 60f));
            x += block.size + 1;
        }

        //building when the core cannot pay for all of it: does it stall part way?
        for(Item item : Vars.content.items()) core.items.set(item, 0);
        core.items.set(Items.beryllium, 10);
        core.items.set(Items.graphite, 20);
        float stalled = build(type, Blocks.siliconArcFurnace, 30, 20, core, 600);
        Tile at = Vars.world.tile(30, 20);
        String state = at.build instanceof mindustry.world.blocks.ConstructBlock.ConstructBuild c ? String.format("still in construction at %.0f%%", c.progress * 100f) : at.block().name;
        System.out.println("  furnace (70 beryllium, 80 graphite) with 10 beryllium and 20 graphite in the core, after " + stalled + " ticks: " + state + "; core now holds " + core.items.get(Items.beryllium) + " beryllium, " + core.items.get(Items.graphite) + " graphite");

        //digging: the unit beside a beryllium wall, its load counted until full
        for(Item ore : new Item[]{Items.beryllium, Items.graphite}){
            int wx = Bench.coreX - 12, wy = Bench.coreY + (ore == Items.graphite ? 6 : 0);
            Bench.wall(wx, wy, ore);
            Unit unit = type.spawn(team, (wx + 2) * 8f, wy * 8f);
            unit.controller(new mindustry.ai.types.CommandAI(){ @Override public void updateUnit(){} });
            Tile wall = Vars.world.tile(wx, wy);
            unit.mineTile = wall;
            int before = core.items.get(ore), ticks = 60 * 60;
            for(int i = 0; i < ticks; i++){
                unit.mineTile = wall;
                Boot.frames(1);
            }
            int got = core.items.get(ore) - before + (unit.stack.item == ore ? unit.stack.amount : 0);
            System.out.println(String.format("  digs %s from a wall %.0f tiles from the core: %d items in %d ticks = %.2f/s, of them %d still carried (valid mine: %b)", ore.name, unit.dst(core) / 8f, got, ticks, got * 60f / ticks, unit.stack.amount, unit.validMine(wall)));
            unit.remove();
        }

        //digging far from the core, and digging while a block is being built
        {
            for(Item item : Vars.content.items()) core.items.set(item, 1000);
            core.items.set(Items.beryllium, 0);
            int wx = 4, wy = 60;
            Bench.wall(wx, wy, Items.beryllium);
            Unit unit = type.spawn(team, (wx + 2) * 8f, wy * 8f);
            unit.controller(new mindustry.ai.types.CommandAI(){ @Override public void updateUnit(){} });
            Tile wall = Vars.world.tile(wx, wy);
            for(int i = 0; i < 1200; i++){ unit.mineTile = wall; Boot.frames(1); }
            System.out.println(String.format("  digs beryllium %.0f tiles from the core (beyond the hand-over range %.1f): after 20 s it carries %d and the core got %d",
                unit.dst(core) / 8f, type.mineRange / 8f, unit.stack.amount, core.items.get(Items.beryllium)));
            unit.remove();

            int bx = 30, by = 30;
            Bench.wall(bx - 3, by, Items.beryllium);
            Unit both = type.spawn(team, (bx - 1) * 8f, by * 8f);
            both.controller(new mindustry.ai.types.CommandAI(){ @Override public void updateUnit(){} });
            Tile w2 = Vars.world.tile(bx - 3, by);
            Call.beginPlace(both, Blocks.tankFabricator, team, bx + 3, by, 0, null);
            both.addBuild(new BuildPlan(bx + 3, by, 0, Blocks.tankFabricator));
            int before = core.items.get(Items.beryllium) + both.stack.amount, t = 0;
            while(Vars.world.tile(bx + 3, by).block() != Blocks.tankFabricator && t < 3000){ both.mineTile = w2; Boot.frames(1); t++; }
            System.out.println(String.format("  digging while building a tank fabricator: done in %d ticks; mine tile kept: %b; carried %d", t, both.mineTile != null, both.stack.amount));
            both.remove();
        }

        //power: what a turbine sees asked of it by a furnace and a tank fabricator that have nothing to work on
        int px = 15, py = 15;
        Vars.world.tile(px, py).getLinkedTilesAs(Blocks.turbineCondenser, t -> t.setFloor(Blocks.rhyoliteVent.asFloor()));
        Building turbine = Bench.put(Blocks.turbineCondenser, px, py, 0, null);
        Building furnace = Bench.put(Blocks.siliconArcFurnace, px + 3, py, 0, null);
        Building fab = Bench.put(Blocks.tankFabricator, px, py + 3, 0, null);
        Boot.frames(120);
        var graph = turbine.power.graph;
        System.out.println(String.format("  power: turbine makes %.1f/s; furnace and fabricator with no input ask for %.1f/s (satisfaction %.2f); furnace efficiency %.2f, fabricator %.2f",
            graph.getLastPowerProduced() * 60f, graph.getLastPowerNeeded() * 60f, graph.getSatisfaction(), furnace.efficiency, fab.efficiency));
        furnace.items.add(Items.sand, 20);
        furnace.items.add(Items.graphite, 5);
        Boot.frames(30);
        System.out.println(String.format("  power: with sand and graphite in the furnace they ask for %.1f/s (satisfaction %.2f); furnace efficiency %.2f",
            graph.getLastPowerNeeded() * 60f, graph.getSatisfaction(), furnace.efficiency));
        System.exit(0);
    }

    /** Has a fresh core unit build one block beside it; ticks until done, or until the limit when one is given. */
    static float build(UnitType type, Block block, int x, int y, CoreBlock.CoreBuild core, int limit){
        Unit unit = type.spawn(team, (x - 3) * 8f, y * 8f);
        unit.controller(new mindustry.ai.types.CommandAI(){ @Override public void updateUnit(){} });
        Call.beginPlace(unit, block, team, x, y, 0, null);
        unit.addBuild(new BuildPlan(x, y, 0, block));
        int ticks = 0;
        Tile tile = Vars.world.tile(x, y);
        while(tile.block() != block && ticks < (limit > 0 ? limit : 60 * 600)){
            Boot.frames(1);
            ticks++;
        }
        unit.remove();
        return ticks;
    }
}
