package bp;

import arc.files.*;
import mindustry.*;

import java.io.*;
import java.util.*;

/**
 * The final form laid out whole on its map, once: every machine of the plan as a body (heated ones with their heaters),
 * the plan's places on the ground where they are, the core where it is; placed, its lines laid, power. What a growing
 * base would build into.
 *
 * Usage: bp.Target data-dir world.msav in.plan out-prefix [seed]
 * Writes out-prefix.png (the picture) and out-prefix.txt (the layout as a spec).
 */
public final class Target{
    public static void main(String[] args) throws IOException{
        long t0 = System.nanoTime();
        Game.boot(args[0]);
        Map<String, Recipe> recipes = new HashMap<>();
        for(Recipe r : Recipes.all()) recipes.put(r.name, r);
        Problem prob = Problem.parse("world " + args[1] + "\n");
        prob.name = new Fi(args[3]).name();
        Balance.Result plan = new Balance.Result();
        plan.solved = true;
        Gen g = new Gen(prob, args[0]);
        List<Body> sites = new ArrayList<>();
        int id = 9000;
        for(String line : new Fi(args[2]).readString().split("\n")){
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
        if(Boolean.parseBoolean(System.getProperty("target.floor", "true"))){
            Districts d = new Districts(g);
            d.run();
            d.report();
            //(then annealed by the same ties, who feeds whom worked out again between rounds)
            for(int round = 0; round < Integer.getInteger("target.anneal", 0); round++){
                long ta = System.nanoTime();
                Placer pl = new Placer(g.board, g.bodies, g.groups, 1L + round);
                pl.ties = d.placerTies();
                pl.wWire = 1f;
                pl.run(Integer.getInteger("target.steps", 1500) * pl.movable.size(), false, Double.parseDouble(System.getProperty("target.heat", "0.3")));
                System.out.println(String.format(Locale.ROOT, "annealed %d in %.1f s: cost %.1f (hard %.1f, wire %.1f, ports %.1f, crowding %.1f)", round, (System.nanoTime() - ta) / 1e9, pl.cost, pl.last[0], pl.last[1], pl.last[2], pl.last[3]));
                if(pl.cost >= pl.wHard) System.out.println("  " + pl.explain().substring(0, Math.min(1500, pl.explain().length())));
                d.report();
            }
            g.keep = true;
            if(!Boolean.parseBoolean(System.getProperty("target.route", "true"))){
                g.board.putParts(g.bodies);
                writeFloor(g, args[3]);
                Plot.draw(g.board, g.bodies, List.of(), List.of(), prob.name + ": the final form placed", new File(args[3] + ".png"));
                System.exit(0);
            }
        }
        Spec s = g.layOut(args.length > 4 ? Long.parseLong(args[4]) : 1L);
        long tl = System.nanoTime();
        int machines = 0, carriers = 0, liquid = 0, touch = 0;
        for(Body b : g.bodies) if(!b.given) for(Body.Part p : b.parts) if(p.recipe != null) machines++;
        for(Router.Net n : g.bestNets){
            for(Board.Piece q : n.pieces) if(q.block != null){
                carriers++;
                if(n.cls == 2) liquid++;
            }
            for(Body.Part t : n.to) if(Router.touches(n.from, t, n.res, n.cls == 2)) touch++;
        }
        System.out.println(String.format(Locale.ROOT, "TARGET %d bodies, %d machines; %d lines, %d carriers (%d of liquids), %.1f a machine; %d handed by touch; %d problems; laid out in %.1f s (bodies %.1f s)",
        g.bodies.size(), machines, g.bestNets.size(), carriers, liquid, carriers / (float)Math.max(1, machines), touch, g.problems, (tl - tb) / 1e9, (tb - t0) / 1e9));
        Map<String, Integer> byRes = new TreeMap<>();
        List<Router.Net> longest = new ArrayList<>(g.bestNets);
        for(Router.Net n : g.bestNets) byRes.merge(n.res, (int)n.pieces.stream().filter(q -> q.block != null).count(), Integer::sum);
        longest.sort((a, c) -> Integer.compare(c.pieces.size(), a.pieces.size()));
        System.out.println("carriers by thing: " + byRes);
        for(Router.Net n : longest.subList(0, Math.min(40, longest.size()))) System.out.println(String.format("  %4d pieces: %s from %s to %s%s", n.pieces.size(), n.res, Gen.where(n.from), n.to.stream().map(Gen::where).toList(), n.unreached.isEmpty() ? "" : " NOT " + n.unreached.size()));
        if(s != null){
            s.name = prob.name;
            s.world = args[1];
            new Fi(args[3] + ".txt").writeString(s.write());
            System.out.println("by the game's rules: " + g.verify(s, true) + " wrong");
        }
        writeFloor(g, args[3]);
        Plot.draw(g.board, g.bodies, g.bestNets, g.bestPower, prob.name + ": the final form laid out whole", new File(args[3] + ".png"));
        System.exit(0);
    }

    /** The floor plan for a growing base: each machine's shape, by its unit, and where each of them stands. */
    static void writeFloor(Gen g, String prefix){
        Map<String, List<Body>> byUnit = new TreeMap<>();
        for(Body b : g.bodies) if(!b.fixed && b.main().recipe != null) byUnit.computeIfAbsent(b.main().recipe.name, k -> new ArrayList<>()).add(b);
        StringBuilder t = new StringBuilder("# the final form's floor plan: by unit, its shape and where each stands (lowest corner of the body's frame, turn)\n");
        for(var e : byUnit.entrySet()){
            Body b0 = e.getValue().get(0);
            t.append("unit ").append(e.getKey()).append('\n');
            for(Body.Part p : b0.parts) t.append(String.format(Locale.ROOT, "part %s %d %d %d %s %.6f %.4f %s\n", p.block.name, p.lx, p.ly, p.lrot, p.recipe == null ? "-" : p.recipe.name, p.work, p.yield, p.config == null ? "-" : p.config));
            for(int[] c : b0.clearLocal) t.append(String.format(Locale.ROOT, "clear %d %d %d %d\n", c[0], c[1], c[2], c[3]));
            if(b0.exclusive) t.append("exclusive\n");
            for(Body b : e.getValue()) t.append("slot ").append(b.ox).append(' ').append(b.oy).append(' ').append(b.rot).append('\n');
        }
        new Fi(prefix + ".floor").writeString(t.toString());
    }
}
