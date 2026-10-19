package xtest;

import arc.files.*;
import expansion.econ.*;
import mindustry.*;
import mindustry.core.GameState.*;
import mindustry.game.*;
import mindustry.io.*;
import mindustry.type.*;
import mindustry.content.*;
import mindustry.world.blocks.storage.*;

import java.util.*;

/** The plant the planner balances on a map, solved as it is and with one more of a machine, machine by machine. Usage: EconWhy data map sector unit machines [extra-recipe] */
public class EconWhy{
    public static void main(String[] args){
        Boot.boot(args[0]);
        String sector = args[2], unitName = args[3];
        int n = Integer.parseInt(args[4]);
        List<String> blocks = new ArrayList<>();
        for(String[] row : EconLab.tech){
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
        Catalog cat = Recipes.read(blocks, (CoreBlock)core.block, rules.buildSpeed(me));
        cat.logistics(EconRun.logisticsOf(sector));
        Field field = Sites.scan(cat, me, Integer.getInteger("xp.radius", 110));
        Planner planner = new Planner(cat, field);
        int unit = cat.id(unitName);
        Recipe making = null;
        for(Recipe r : cat.recipes) if(r.unitOut == unit) making = r;
        List<Machine> plant = planner.balance(unit, n / making.unitSeconds);
        System.out.print(planner.notes);
        show(cat, field, plant, unit, "as balanced");
        //the best order with extras allowed, and with none
        for(int extras : new int[]{16, 0}){
            Planner pl = new Planner(cat, field);
            pl.extras = extras;
            List<Machine> again = pl.balance(unit, n / making.unitSeconds);
            Timeline t = pl.order(again, unit);
            StringBuilder b = new StringBuilder();
            for(int i = 0; i < t.order.size(); i++) b.append(String.format(" %s%s@%s", t.order.get(i).recipe.name.replace("tank-fabricator:", "fab:"), t.order.get(i).item >= 0 ? "/" + cat.get(t.order.get(i).item).name : "", EconLab.time(t.begun[i])));
            System.out.println(String.format("== order with up to %d extras: done %s, first %s, %d machines:%s", extras, EconLab.time(t.t), EconLab.time(t.first[unit]), t.order.size(), b));
            if(extras == 0) continue;
            trace(cat, field, t.order, unit, "all of it");
            List<Machine> w = new ArrayList<>(t.order);
            w.remove(3);
            trace(cat, field, w, unit, "without #4");
            for(int i = 0; i < t.order.size(); i++){
                List<Machine> without = new ArrayList<>(t.order);
                Machine gone = without.remove(i);
                float[] sc = pl.score(without, unit);
                System.out.println(String.format("     without #%d %s%s (begun %s): done %s first %s", i + 1, gone.recipe.name, gone.item >= 0 ? "/" + cat.get(gone.item).name : "", EconLab.time(t.begun[i]), EconLab.time(sc[0]), EconLab.time(sc[1])));
            }
        }
        if(args.length > 5){
            for(Recipe r : cat.recipes){
                if(!r.name.equals(args[5])) continue;
                Field.Site site = null;
                for(Field.Site s : field.sites) if(s.ground == r.ground){ boolean used = false; for(Machine m : plant) if(m.x == s.x && m.y == s.y) used = true; if(!used){ site = s; break; } }
                List<Machine> more = new ArrayList<>(plant);
                more.add(planner.machine(r, site));
                show(cat, field, more, unit, "with one more " + r.name);
            }
        }
        System.exit(0);
    }

    static void trace(Catalog cat, Field field, List<Machine> seq, int unit, String what){
        Timeline t = new Timeline(cat, field, seq);
        System.out.println("== trace, " + what);
        float next = 0f;
        while(t.t < 400f && !(t.done() && t.made[unit] >= 1f)){
            if(!t.step(400f)) break;
            if(t.t >= next){
                next = t.t + 15f;
                StringBuilder b = new StringBuilder(String.format("   %s next #%d", EconLab.time(t.t), t.next + 1));
                for(String n : new String[]{"silicon", "graphite", "beryllium", "sand"}) b.append(String.format(" %s %.0f", n, t.stock[cat.id(n)]));
                for(int i = 0; i < t.built.size(); i++){
                    Machine m = t.built.get(i);
                    if(m.existing) continue;
                    b.append(String.format(" %s=%.2f", m.recipe.name.substring(0, 4), i < t.plant.eff.length ? t.plant.eff[i] : -1f));
                }
                b.append(String.format(" | toCore si %.2f sand s/u %.2f/%.2f", t.plant.toCore[cat.id("silicon")], t.plant.supply[cat.id("sand")], t.plant.used[cat.id("sand")]));
                System.out.println(b);
            }
        }
        System.out.println(String.format("   ended %s, first %s", EconLab.time(t.t), EconLab.time(t.first[unit])));
    }

    static void show(Catalog cat, Field field, List<Machine> plant, int unit, String what){
        List<Machine> all = new ArrayList<>(field.existing);
        all.addAll(plant);
        Plant p = new Plant(cat);
        p.solve(all, null, true);
        System.out.println(String.format("== %s: %s %.4f/s, power met %.3f (%.0f wanted)", what, cat.get(unit).name, p.unitRate(all, unit), p.powerMet, p.powerWanted));
        for(int i = 0; i < all.size(); i++){
            Machine m = all.get(i);
            System.out.println(String.format("   %-28s %-9s %s eff %.3f", m.recipe.name, m.item >= 0 ? cat.get(m.item).name : "", m.existing ? "(stands)" : "        ", p.eff[i]));
        }
        StringBuilder b = new StringBuilder("   supply/use:");
        for(int k = 0; k < cat.size(); k++) if(p.supply[k] > 0 || p.used[k] > 0) b.append(String.format(" %s %.2f/%.2f", cat.get(k).name, p.supply[k], p.used[k]));
        System.out.println(b);
    }
}
