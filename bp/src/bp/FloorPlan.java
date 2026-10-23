package bp;

import arc.files.*;
import mindustry.*;

import java.io.*;
import java.util.*;

/**
 * The final form placed once on its map, no lines laid: every machine of the plan as a body (heated ones with their
 * heaters), the plan's places on the ground where they are, the core where it is; the placer's annealing alone, so as
 * to see where each thing would stand beside what it takes from and hands to. A probe for a floor plan the growing base
 * builds into.
 *
 * Usage: bp.FloorPlan data-dir world.msav in.plan out.png [steps per body]
 */
public final class FloorPlan{
    public static void main(String[] args) throws IOException{
        long t0 = System.nanoTime();
        Game.boot(args[0]);
        Map<String, Recipe> recipes = new HashMap<>();
        for(Recipe r : Recipes.all()) recipes.put(r.name, r);
        Problem prob = Problem.parse("world " + args[1] + "\n");
        prob.name = new Fi(args[3]).nameWithoutExtension();
        String planText = new Fi(args[2]).readString();
        Balance.Result plan = new Balance.Result();
        plan.solved = true;
        Gen g = new Gen(prob, args[0]);
        List<Body> sites = new ArrayList<>();
        int id = 9000;
        for(String line : planText.split("\n")){
            String[] w = line.trim().split("\\s+");
            if(w[0].equals("machine")){
                Recipe r = recipes.get(w[1]);
                plan.machines.add(new Object[]{r, Integer.parseInt(w[2]), Float.parseFloat(w[3]), null, w[4].equals("-") ? null : w[4].replace('_', ' ')});
            }else if(w[0].equals("site")){
                Recipe r = recipes.get(w[1]);
                Body body = new Body(id++, r.name, true);
                Body.Part p = body.add(r.block, 0, 0, Integer.parseInt(w[4]), r, Float.parseFloat(w[6]), null);
                p.yield = Float.parseFloat(w[5]);
                body.put(Integer.parseInt(w[2]), Integer.parseInt(w[3]), 0);
                if(!w[8].equals("-")) for(String t : w[8].split(",")) body.clearTiles.add(Integer.parseInt(t));
                sites.add(body);
            }else if(w[0].equals("delivered") && Vars.content.item(w[1]) != null){
                plan.delivered.put(w[1], Float.parseFloat(w[2]));
                prob.goals.put(w[1], Float.parseFloat(w[2]));
            }
        }
        g.balance = plan;
        g.bodies();
        g.bodies.addAll(sites);
        g.groups();
        long tb = System.nanoTime();
        int per = args.length > 4 ? Integer.parseInt(args[4]) : 4000;
        Placer placer = new Placer(g.board, g.bodies, g.groups, 1L);
        int movable = placer.movable.size();
        placer.run(per * Math.max(4, movable), true, 1.0);
        long tp = System.nanoTime();
        g.board.putParts(g.bodies);
        //(what touches what it takes from: pairs handed straight)
        int touch = 0, far = 0;
        long gapSum = 0;
        for(Placer.Group gr : g.groups) for(Body.Part t : gr.takers){
            int bg = Integer.MAX_VALUE;
            for(Body.Part m : gr.makers) if(m != t) bg = Math.min(bg, Router.touches(m, t, gr.res, gr.liquid) ? -1 : Placer.gap(m, t));
            if(bg == Integer.MAX_VALUE) continue;
            if(bg < 0) touch++;
            else{
                far++;
                gapSum += bg;
            }
        }
        System.out.println(String.format(Locale.ROOT, "%d bodies (%d movable), placed in %.1f s (bodies %.1f s); cost %.1f: hard %.1f, wire %.1f, ports %.1f, crowding %.1f",
        g.bodies.size(), movable, (tp - tb) / 1e9, (tb - t0) / 1e9, placer.cost, placer.last[0], placer.last[1], placer.last[2], placer.last[3]));
        System.out.println(String.format(Locale.ROOT, "takers: %d touch a maker, %d do not (gap to the nearest %.1f tiles on average)", touch, far, far == 0 ? 0f : gapSum / (float)far));
        if(placer.cost >= placer.wHard) System.out.println("  " + placer.explain().substring(0, Math.min(2000, placer.explain().length())));
        Plot.draw(g.board, g.bodies, List.of(), List.of(), prob.name + ": the final form placed, no lines", new File(args[3]));
        System.exit(0);
    }
}
