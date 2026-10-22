package xtest;

import arc.files.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.core.GameState.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.storage.*;

import java.util.*;

/**
 * What a grown base does in the game: the map, every block the ledger has committed put down at once, the game run so
 * many seconds; then each grid's power, each machine whether it works and why not (what it lacks), what came into the
 * core. A look at whether what was laid out works, apart from how it was built.
 *
 * Usage: GrowProbe data-dir map.msav ledger seconds
 */
public class GrowProbe{
    public static void main(String[] args){
        Boot.boot(args[0]);
        Vars.logic.reset();
        SaveIO.load(new Fi(args[1]));
        Rules rules = Vars.state.rules;
        Planets.erekir.applyRules(rules, false);
        rules.waves = false;
        rules.fog = false;
        rules.coreIncinerates = true;
        rules.infiniteResources = false;
        Team us = rules.defaultTeam;
        Vars.state.set(State.playing);
        Time.setDeltaProvider(() -> 1f);
        int placed = 0, failed = 0;
        String kind = "";
        for(String line : new Fi(args[2]).readString().split("\n")){
            String[] w = line.trim().split("\\s+");
            if(w[0].equals("inst")){
                kind = w[4];
                continue;
            }
            if(kind.equals("removed")) continue;
            Block bl = null;
            int x = 0, y = 0, rot = 0;
            String config = null;
            if(w[0].equals("part")){
                bl = Vars.content.block(w[1]);
                x = bp.Geo.at(Integer.parseInt(w[2]), bl.size);
                y = bp.Geo.at(Integer.parseInt(w[3]), bl.size);
                rot = Integer.parseInt(w[4]);
                config = w[8].equals("-") ? null : w[8];
            }else if(w[0].equals("piece")){
                bl = Vars.content.block(w[2]);
                x = Integer.parseInt(w[3]);
                y = Integer.parseInt(w[4]);
                rot = Integer.parseInt(w[5]);
            }
            if(bl == null) continue;
            Tile t = Vars.world.tile(x, y);
            if(t == null || !Build.validPlace(bl, us, x, y, rot, false, false)){
                failed++;
                System.out.println("  cannot place " + bl.name + " at " + x + "," + y);
                continue;
            }
            t.setNet(bl, us, rot);
            if(config != null && t.build != null) t.build.configureAny(bp.Scratch.configOf(config));
            placed++;
        }
        System.out.println(String.format("placed %d blocks (%d could not be)", placed, failed));
        CoreBlock.CoreBuild core = us.core();
        Map<Item, Integer> before = new HashMap<>();
        for(Item item : Vars.content.items()) before.put(item, core.items.get(item));
        Boot.frames((int)(Float.parseFloat(args[3]) * 60));
        //power
        Set<PowerGraph> grids = new LinkedHashSet<>();
        for(Building b : us.data().buildings) if(b.power != null && b.block.hasPower) grids.add(b.power.graph);
        for(PowerGraph g : grids){
            StringBuilder who = new StringBuilder();
            Map<String, Integer> kinds = new TreeMap<>();
            for(Building b : g.all) kinds.merge(b.block.name, 1, Integer::sum);
            System.out.println(String.format(Locale.ROOT, "  grid: makes %.1f needs %.1f (%.0f%%): %s", g.getPowerProduced() * 60f, g.getPowerNeeded() * 60f, g.getSatisfaction() * 100f, kinds));
        }
        for(Building b : us.data().buildings) if(b instanceof PowerGenerator.GeneratorBuild gb) System.out.println(String.format(Locale.ROOT, "  generator %s@%d,%d: efficiency %.2f", b.block.name, b.tileX(), b.tileY(), gb.productionEfficiency));
        //machines
        for(Building b : us.data().buildings){
            if(b.block.consumers.length == 0 || b instanceof CoreBlock.CoreBuild || b.block.group == mindustry.world.meta.BlockGroup.transportation || b.block.group == mindustry.world.meta.BlockGroup.liquids) continue;
            StringBuilder lack = new StringBuilder();
            for(var c : b.block.consumers) if(c.efficiency(b) < 0.99f) lack.append(c.getClass().getSimpleName()).append(String.format(Locale.ROOT, "=%.2f ", c.efficiency(b)));
            System.out.println(String.format(Locale.ROOT, "  %s@%d,%d: efficiency %.2f %s%s", b.block.name, b.tileX(), b.tileY(), b.efficiency, lack.length() == 0 ? "" : "lacks ", lack));
        }
        //(liquids held: in what makes or takes them and along the lines)
        if(Boolean.getBoolean("probe.liquids")) for(Building b : us.data().buildings){
            if(b.liquids == null || b.liquids.currentAmount() < 0.001f && !(b.block instanceof mindustry.world.blocks.production.Drill)) continue;
            System.out.println(String.format(Locale.ROOT, "    %s@%d,%d rot %d holds %s %.2f", b.block.name, b.tileX(), b.tileY(), b.rotation, b.liquids.current().name, b.liquids.currentAmount()));
        }
        //(a close look at some: what each holds, whether it would work, what about it takes what it makes)
        for(String at : System.getProperty("probe.at", "").split(";")){
            if(at.isBlank()) continue;
            String[] xy = at.split(",");
            Building b = Vars.world.build(Integer.parseInt(xy[0].trim()), Integer.parseInt(xy[1].trim()));
            if(b == null){
                System.out.println("  at " + at + ": nothing");
                continue;
            }
            StringBuilder about = new StringBuilder();
            for(Building o : b.proximity){
                about.append(o.block.name).append('@').append(o.tileX()).append(',').append(o.tileY());
                if(b.items != null) for(Item item : Vars.content.items()) if(b.items.get(item) > 0) about.append(o.acceptItem(b, item) ? " takes " : " refuses ").append(item.name);
                about.append("; ");
            }
            System.out.println(String.format(Locale.ROOT, "  at %s: %s rot %d efficiency %.2f shouldConsume %s enabled %s items %s liquids %s | about: %s", at, b.block.name, b.rotation, b.efficiency, b.shouldConsume(), b.enabled, b.items, b.liquids == null ? "-" : b.liquids.current().name + " " + b.liquids.currentAmount(), about));
        }
        StringBuilder came = new StringBuilder();
        for(Item item : Vars.content.items()){
            int d = core.items.get(item) - before.get(item);
            if(d != 0) came.append(item.name).append(d > 0 ? "+" : "").append(d).append(' ');
        }
        System.out.println("into the core: " + came);
        System.exit(0);
    }
}
