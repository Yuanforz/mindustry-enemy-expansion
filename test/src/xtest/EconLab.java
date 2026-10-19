package xtest;

import arc.files.*;
import expansion.econ.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.core.GameState.*;
import mindustry.game.*;
import mindustry.io.*;
import mindustry.world.blocks.storage.*;

import java.util.*;

/**
 * Plans the fastest way to a unit on a real map with the economy model, and says how long it takes: the plant it works
 * out, the order it builds it in, and the same plant in a plain order for comparison.
 *
 * Usage: EconLab data-dir map.msav sector unit factories
 *   sector: what has been researched by then (onset, aegis, lake, intersect, atlas)
 *   unit: the unit to make; factories: how many of the machines making it the plant should keep busy
 */
public class EconLab{
    /** The machines of the economy each sector adds, in campaign order (from the tech tree; see reference/research-2026-10-18/maps-and-tech.md). */
    static final String[][] tech = {
        {"onset", "plasma-bore", "turbine-condenser", "cliff-crusher", "silicon-arc-furnace", "tank-fabricator"},
        {"aegis", "impact-drill", "vent-condenser"},
        {"lake", "ship-fabricator"},
        {"intersect", "mech-fabricator"},
        {"atlas", "electrolyzer", "tank-refabricator"},
    };

    public static void main(String[] args){
        Boot.boot(args[0]);
        String sector = args[2], unitName = args[3];
        int factories = Integer.parseInt(args[4]);
        List<String> blocks = new ArrayList<>();
        for(String[] row : tech){
            for(int i = 1; i < row.length; i++) blocks.add(row[i]);
            if(row[0].equals(sector)) break;
        }

        Vars.logic.reset();
        SaveIO.load(new Fi(args[1]));
        Rules rules = Vars.state.rules;
        Planets.erekir.applyRules(rules, false);
        Vars.state.set(State.playing);
        Team me = rules.defaultTeam;
        CoreBlock.CoreBuild core = me.core();

        long t0 = System.nanoTime();
        Catalog cat = Recipes.read(blocks, (CoreBlock)core.block, rules.buildSpeed(me));
        Field field = Sites.scan(cat, me, 110);
        //on landing, the game empties the core and fills it with the sector's loadout (Erekir allows no launch loadout)
        if(rules.loadout != null && rules.loadout.size > 0){
            java.util.Arrays.fill(field.stock, 0f);
            for(mindustry.type.ItemStack stack : rules.loadout) field.stock[cat.id(stack.item.name)] = stack.amount;
        }
        long t1 = System.nanoTime();
        int unit = cat.id(unitName);
        System.out.println("######## " + new Fi(args[1]).nameWithoutExtension() + ", researched as at " + sector + "; goal: " + unitName + " from " + factories + " machine(s)");
        System.out.println(String.format("  core at %d,%d holds %s; core unit digs %.2f/s; read in %.1fs", field.coreX, field.coreY, items(cat, field.stock), cat.mineRate, (t1 - t0) / 1e9));
        System.out.println(String.format("  nearest walls to dig by hand: beryllium %.0f tiles (%.2f/s), graphite %.0f tiles (%.2f/s)",
            field.handDistance[cat.id("beryllium")], field.handRate(cat, cat.id("beryllium")), field.handDistance[cat.id("graphite")], field.handRate(cat, cat.id("graphite"))));
        Map<String, List<Field.Site>> byKind = new TreeMap<>();
        for(Field.Site s : field.sites) byKind.computeIfAbsent(s.ground + (s.item >= 0 ? " " + cat.get(s.item).name : ""), k -> new ArrayList<>()).add(s);
        for(var e : byKind.entrySet()){
            StringBuilder best = new StringBuilder();
            for(int i = 0; i < Math.min(5, e.getValue().size()); i++){
                Field.Site s = e.getValue().get(i);
                best.append(String.format(" %.1f@%.0f", s.factor, s.distance));
            }
            System.out.println(String.format("  places: %-22s %3d; best (yield@tiles):%s", e.getKey(), e.getValue().size(), best));
        }
        Map<String, Integer> standing = new TreeMap<>();
        for(Machine m : field.existing) standing.merge(m.recipe.name + (m.item >= 0 ? "/" + cat.get(m.item).name : ""), 1, Integer::sum);
        System.out.println("  already standing: " + standing);

        Planner planner = new Planner(cat, field);
        Recipe making = null;
        for(Recipe r : cat.recipes) if(r.unitOut == unit) making = r;
        float rate = factories / making.unitSeconds;
        List<Machine> plant = planner.balance(unit, rate);
        Map<String, Integer> kinds = new TreeMap<>();
        for(Machine m : plant) kinds.merge(m.recipe.name + (m.item >= 0 ? "/" + cat.get(m.item).name : ""), 1, Integer::sum);
        System.out.println("  plant to add (" + plant.size() + " machines): " + kinds);
        if(planner.notes.length() > 0) System.out.print("  notes: " + planner.notes);
        List<Machine> all = new ArrayList<>(field.existing);
        all.addAll(plant);
        Plant steady = new Plant(cat);
        steady.solve(all, null, true);
        System.out.println(String.format("  once built: %s %.4f/s (wanted %.4f), power met %.2f (%.0f made, %.0f wanted); to the core %s",
            unitName, steady.unitRate(all, unit), rate, steady.powerMet, steady.supply[cat.size()], steady.powerWanted, rates(cat, steady.toCore)));

        //the plain order
        Timeline plain = new Timeline(cat, field, planner.plainOrder(plant));
        plain.runToGoal(unit, planner.limit);
        report("plain order", cat, plain, unit);
        steps(cat, plain, plant);

        long t2 = System.nanoTime();
        Timeline best = planner.order(plant, unit);
        long t3 = System.nanoTime();
        report(String.format("rollout order (planned in %.1fs)", (t3 - t2) / 1e9), cat, best, unit);
        steps(cat, best, plant);
        //what each extra is worth: the order without it
        float[] full = planner.score(best.order, unit);
        for(int i = 0; i < best.order.size(); i++){
            if(plant.contains(best.order.get(i))) continue;
            List<Machine> without = new ArrayList<>(best.order);
            without.remove(i);
            float[] s = planner.score(without, unit);
            System.out.println(String.format("    without the extra %s at %d,%d: done %s (with it %s)", best.order.get(i).recipe.name, best.order.get(i).x, best.order.get(i).y, time(s[0]), time(full[0])));
        }
        StringBuilder st = new StringBuilder("  by stage (done / first unit):");
        for(var e : planner.stages.entrySet()) st.append(String.format(" %s %s/%s;", e.getKey(), time(e.getValue()[0]), time(e.getValue()[1])));
        System.out.println(st);
        System.out.print("  " + planner.notes.toString().replace("\n", "\n  ").trim() + "\n");
        System.exit(0);
    }

    static void steps(Catalog cat, Timeline line, List<Machine> plant){
        for(int i = 0; i < line.order.size(); i++){
            Machine m = line.order.get(i);
            boolean extra = !plant.contains(m);
            System.out.println(String.format("    %6.1fs  %-30s %-10s yield %4.1f at %3.0f tiles%s", line.begun[i], m.recipe.name, m.item >= 0 ? cat.get(m.item).name : "", m.factor, m.distance, extra ? "  (extra)" : ""));
        }
        if(!line.done()) System.out.println(String.format("    stopped at %.0fs waiting for the next; core holds %s", line.t, items(cat, line.stock)));
    }

    static void report(String what, Catalog cat, Timeline line, int unit){
        float all = Float.NaN;
        for(float f : line.finished) all = Float.isNaN(all) ? f : Math.max(all, f);
        StringBuilder firsts = new StringBuilder();
        for(int k = 0; k < cat.size(); k++) if(cat.get(k).kind == Res.Kind.unit && !Float.isInfinite(line.first[k])) firsts.append(" ").append(cat.get(k).name).append(' ').append(time(line.first[k]));
        System.out.println(String.format("  %s: first %s at %s, everything built at %s, both by %s; %d machines; first of each:%s",
            what, cat.get(unit).name, time(line.first[unit]), time(all), line.done() && line.made[unit] >= 1 ? time(line.t) : "never", line.order.size(), firsts));
    }

    static String time(float s){
        if(Float.isNaN(s) || Float.isInfinite(s)) return "never";
        return String.format("%d:%02d", (int)s / 60, (int)s % 60);
    }

    static String items(Catalog cat, float[] stock){
        StringBuilder b = new StringBuilder();
        for(int k = 0; k < stock.length; k++) if(stock[k] > 0) b.append(b.length() == 0 ? "" : " ").append(cat.get(k).name).append('=').append((int)stock[k]);
        return b.length() == 0 ? "nothing" : b.toString();
    }

    static String rates(Catalog cat, float[] r){
        StringBuilder b = new StringBuilder();
        for(int k = 0; k < r.length; k++) if(r[k] > 0.005f) b.append(b.length() == 0 ? "" : " ").append(cat.get(k).name).append(String.format("=%.2f/s", r[k]));
        return b.length() == 0 ? "nothing" : b.toString();
    }
}
