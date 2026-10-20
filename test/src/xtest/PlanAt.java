package xtest;

import arc.files.*;
import expansion.econ.*;
import mindustry.*;
import mindustry.game.*;
import mindustry.io.*;

import java.util.*;

/**
 * Plans one stage from a world saved as it stood (xtest.Match -Dmatch.save=dir), with all of Erekir's tech, and says
 * what came out and where the time went: for working on how long planning takes on a base that already stands.
 *
 * Usage: PlanAt data-dir saved.msav unit machines
 */
public class PlanAt{
    public static void main(String[] args){
        Boot.boot(args[0]);
        Vars.logic.reset();
        SaveIO.load(new Fi(args[1]));
        Team us = Vars.state.rules.defaultTeam;
        List<String> blocks = new ArrayList<>(Plan.erekir());
        Collections.sort(blocks);
        Set<String> allowed = new HashSet<>(blocks);
        List<Design> library = Plan.library(System.getProperty("xp.modules", "blueprints/own,blueprints/modules"));
        Took.report();
        //"climb:titan": only the next step of the climb there (Plan.next), and the way down from the goal it took
        if(args[2].startsWith("climb:")){
            for(String goal : args[2].substring(6).split(",")){
                Plan probe = new Plan(System.out);
                probe.gate = true;
                String aim = probe.next(us, blocks, allowed, library, goal);
                System.out.println("CLIMB " + goal + ": next " + aim + " (" + probe.climbed + ")");
            }
            System.exit(0);
        }
        long t0 = System.nanoTime();
        Plan plan = new Plan(System.out).make(us, blocks, allowed, library, args[2], Float.parseFloat(args[3]), null);
        System.out.println(String.format("PLANAT %s %s %s: %s in %.1f s", new Fi(args[1]).nameWithoutExtension(), args[2], args[3],
            plan == null ? "no plan" : plan.model.order.size() + " machines, done by " + Plan.time(plan.model.t) + ", first at " + Plan.time(plan.model.first[plan.unit]) + "; " + plan.works.summary(),
            (System.nanoTime() - t0) / 1e9));
        if(plan != null) for(Machine m : plan.model.order){
            var p = plan.works.at.get(m);
            System.out.println("  " + m.recipe.name + (p == null ? " -" : " at " + p.x + "," + p.y + " r" + p.rotation));
        }
        System.out.print(Took.report());
        System.exit(0);
    }
}
