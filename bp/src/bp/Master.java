package bp;

import arc.files.*;
import mindustry.*;
import mindustry.type.*;
import mindustry.world.*;
import bp.Board.Piece;

import java.util.*;

/**
 * The master plan of the final form on a map: the plan (Final, on the map's own places, with each material of building
 * going to the core besides, so that while the form goes up what its makers make has a way to the core), kept in a file;
 * then laid out: machines on ground standing on their places, the rest cut into modules (Modular), blocks that only stand
 * put down once each, all joined, powered, checked by the game's rules.
 *
 * Usage: bp.Master plan data-dir world.msav surplus out.plan [places kept per recipe] [goals:item=rate,...]
 *        bp.Master layout data-dir world.msav in.plan out.txt
 */
public final class Master{
    public static void main(String[] args){
        Game.boot(args[1]);
        if(args[0].equals("plan")){
            Map<String, Float> goals = new LinkedHashMap<>();
            for(String a : args) if(a.startsWith("goals:")) for(String kv : a.substring(6).split(",")) goals.put(kv.split("=")[0], Float.parseFloat(kv.split("=")[1]));
            plan(args[1], args[2], Float.parseFloat(args[3]), args[4], args.length > 5 && !args[5].startsWith("goals:") ? Integer.parseInt(args[5]) : 40, goals);
        }else layout(args[1], args[2], args[3], args[4]);
        System.exit(0);
    }

    /** The items any block of the final form is built of: each goes to the core a little besides, so a line runs there. */
    static Set<String> materials(){
        Set<String> out = new TreeSet<>();
        for(Block b : Final.erekir()) for(ItemStack st : b.requirements) out.add(st.item.name);
        return out;
    }

    /**
     * The plan: the final form, or (goals given) only so much of some things to the core a second, on the map's places, a
     * form on the way to the final one.
     */
    static void plan(String dataDir, String world, float surplus, String out, int keep, Map<String, Float> goals){
        Balance bal = Final.onWorld(dataDir, world, surplus, keep);
        boolean fin = goals.isEmpty();
        if(!fin){
            bal.goals.clear();
            bal.runAtLeast.clear();
            bal.goals.putAll(goals);
        }
        if(fin) for(String m : materials()) bal.goals.put(m, 0.1f);
        long t0 = System.nanoTime();
        Balance.Result r = bal.solve();
        System.out.println(r.toString().lines().filter(l -> !l.startsWith("  to the core")).reduce("", (a, c) -> a + c + "\n"));
        System.out.println(String.format("  solved in %.1f s", (System.nanoTime() - t0) / 1e9));
        StringBuilder s = new StringBuilder("# " + (fin ? "the final form's plan" : "a plan for " + goals) + " on " + world + "\nform " + (fin ? "final" : "goals") + "\n");
        for(Object[] m : r.machines){
            if(((Recipe)m[0]).ground != Recipe.Ground.none) continue;
            s.append(String.format(Locale.ROOT, "machine %s %d %.6f %s%n", ((Recipe)m[0]).name, (Integer)m[1], (Float)m[2], m[4] == null ? "-" : ((String)m[4]).replace(' ', '_')));
        }
        Map<String, List<Sites.Site>> byName = new HashMap<>();
        for(var e : Final.lastSites.entrySet()) byName.put(e.getKey().name, e.getValue());
        for(Object[] t : r.placesTaken){
            Sites.Site site = byName.get((String)t[0]).get((Integer)t[1]);
            s.append(String.format(Locale.ROOT, "site %s %d %d %d %.4f %.6f %s %s%n", t[0], site.x0(), site.y0(), site.rot(), site.yield(), (Float)t[3], join(site.body()), join(site.lane())));
        }
        for(var e : r.delivered.entrySet()) s.append(String.format(Locale.ROOT, "delivered %s %.6f%n", e.getKey(), e.getValue()));
        new Fi(out).writeString(s.toString());
        System.out.println("plan written to " + out);
    }

    /**
     * The master laid out, as the parts it goes up in: each body (a module, a machine on its place, a block put down once)
     * with its blocks, what it costs and takes to build, what it makes and takes a second at work and its power; each line
     * with its blocks, what it carries and how much at most, from which part to which; each beam node with the part it
     * stands nearest that has power. The core is part 0.
     */
    static void structure(Gen g, String file){
        StringBuilder s = new StringBuilder("# the parts the final form goes up in\n");
        Map<Body, Integer> ids = new HashMap<>();
        int id = 1;
        for(Body b : g.bodies){
            if(Board.isCore(b.main())){
                ids.put(b, 0);
                continue;
            }
            if(b.given) continue;
            ids.put(b, id++);
        }
        for(Body b : g.bodies){
            Integer c = ids.get(b);
            if(c == null || c == 0) continue;
            String kind = b.fixed ? "site" : b.parts.size() == 1 && b.main().recipe == null ? "single" : "module";
            Map<String, Float> cost = new TreeMap<>(), rate = new TreeMap<>();
            float seconds = 0f, power = 0f;
            for(Body.Part p : b.parts){
                for(var st : p.block.requirements) cost.merge(st.item.name, (float)st.amount, Float::sum);
                seconds += p.block.buildTime / 60f;
                if(p.recipe == null) continue;
                Recipe.Rates r = p.recipe.at(p.yield);
                for(var e : r.out.entrySet()) rate.merge(e.getKey(), e.getValue() * p.work, Float::sum);
                for(var e : r.in.entrySet()) rate.merge(e.getKey(), -e.getValue() * p.work, Float::sum);
                for(var e : p.recipe.fixedOut.entrySet()) rate.merge(e.getKey(), e.getValue(), Float::sum);
                for(var e : p.recipe.fixedIn.entrySet()) rate.merge(e.getKey(), -e.getValue(), Float::sum);
                power += (r.powerOut - r.powerUse) * p.work + p.recipe.fixedPowerOut - p.recipe.fixedPowerUse;
            }
            s.append(String.format(Locale.ROOT, "comp %d %s %s %.2f %s %.2f %s%n", c, kind, b.label.replace(' ', '_'), seconds, kv(cost), power, kv(rate)));
            for(Body.Part p : b.parts) s.append(String.format(Locale.ROOT, "block %d %s %d %d %d %s%n", c, p.block.name, p.ax(), p.ay(), p.rot, p.block.configurable && p.config != null ? p.config : "-"));
        }
        int n = 0;
        for(Router.Net net : g.bestNets){
            Integer from = ids.get(net.from.body);
            if(from == null) continue;
            List<String> to = new ArrayList<>();
            for(Body.Part t : net.to) if(ids.containsKey(t.body)) to.add(String.valueOf(ids.get(t.body)));
            Block carrier = net.pieces.stream().filter(q -> q.block != null).map(q -> q.block).findFirst().orElse(null);
            float cap = carrier == null ? 1e6f : g.carriers.rate.getOrDefault(carrier, 1e6f);
            Map<String, Float> cost = new TreeMap<>();
            float seconds = 0f;
            for(Piece q : net.pieces) if(q.block != null){
                for(var st : q.block.requirements) cost.merge(st.item.name, (float)st.amount, Float::sum);
                seconds += q.block.buildTime / 60f;
            }
            s.append(String.format(Locale.ROOT, "net %d %s %.3f %d %s %.2f %s%n", n, net.res, cap, from, to.isEmpty() ? "-" : String.join(",", to), seconds, kv(cost)));
            for(Piece q : net.pieces) if(q.block != null) s.append(String.format(Locale.ROOT, "netblock %d %s %d %d %d%n", n, q.block.name, q.x, q.y, q.rot));
            n++;
        }
        //(each node with the part nearest it that has power)
        for(Piece q : g.bestPower){
            Body near = null;
            float best = Float.MAX_VALUE;
            for(Body b : g.bodies){
                if(!ids.containsKey(b)) continue;
                for(Body.Part p : b.parts){
                    if(!p.block.hasPower) continue;
                    float d = Math.abs(p.ax() - q.x) + Math.abs(p.ay() - q.y);
                    if(d < best){
                        best = d;
                        near = b;
                    }
                }
            }
            s.append(String.format(Locale.ROOT, "node %d %s %d %d%n", near == null ? 0 : ids.get(near), q.block.name, q.x, q.y));
        }
        new Fi(file).writeString(s.toString());
        System.out.println("parts written to " + file);
    }

    static String kv(Map<String, Float> m){
        if(m.isEmpty()) return "-";
        StringBuilder s = new StringBuilder();
        for(var e : m.entrySet()) if(Math.abs(e.getValue()) > 1e-6f) s.append(s.length() == 0 ? "" : ",").append(e.getKey()).append('=').append(String.format(Locale.ROOT, "%.5f", e.getValue()));
        return s.length() == 0 ? "-" : s.toString();
    }

    static String join(int[] a){
        if(a.length == 0) return "-";
        StringBuilder s = new StringBuilder();
        for(int i = 0; i < a.length; i++) s.append(i == 0 ? "" : ",").append(a[i]);
        return s.toString();
    }

    static int[] split(String s){
        if(s.equals("-")) return new int[0];
        return Arrays.stream(s.split(",")).mapToInt(Integer::parseInt).toArray();
    }

    static void layout(String dataDir, String world, String planFile, String out){
        long t0 = System.nanoTime();
        Map<String, Recipe> recipes = new HashMap<>();
        for(Recipe r : Recipes.all()) recipes.put(r.name, r);
        Problem prob = Problem.parse("world " + world + "\n");
        prob.name = new Fi(out).nameWithoutExtension();
        //(what goes to the core: what the plan delivers there, and of the final form the materials, a little, that a line
        //run there)
        String planText = new Fi(planFile).readString();
        for(String line : planText.split("\n")){
            String[] w = line.trim().split("\\s+");
            if(w[0].equals("delivered") && Vars.content.item(w[1]) != null) prob.goals.put(w[1], Float.parseFloat(w[2]));
            if(w[0].equals("form") && w[1].equals("final")) for(String m : materials()) prob.goals.putIfAbsent(m, 0.1f);
        }
        Modular mod = new Modular(prob, dataDir);
        Balance.Result plan = new Balance.Result();
        plan.solved = true;
        List<Body> extra = new ArrayList<>();
        int id = 5000;
        boolean fin = true;
        for(String line : planText.split("\n")){
            String[] w = line.trim().split("\\s+");
            if(w[0].equals("form")) fin = w[1].equals("final");
            else if(w[0].equals("machine")){
                Recipe r = recipes.get(w[1]);
                plan.machines.add(new Object[]{r, Integer.parseInt(w[2]), Float.parseFloat(w[3]), null, w[4].equals("-") ? null : w[4].replace('_', ' ')});
            }else if(w[0].equals("site")){
                Recipe r = recipes.get(w[1]);
                int x0 = Integer.parseInt(w[2]), y0 = Integer.parseInt(w[3]), rot = Integer.parseInt(w[4]);
                Body body = new Body(id++, r.name, true);
                Body.Part p = body.add(r.block, 0, 0, rot, r, Float.parseFloat(w[6]), null);
                p.yield = Float.parseFloat(w[5]);
                body.put(x0, y0, 0);
                for(int i : split(w[8])) body.clearTiles.add(i);
                extra.add(body);
            }else if(w[0].equals("delivered")){
                plan.delivered.put(w[1], Float.parseFloat(w[2]));
            }
        }
        mod.whole.balance = plan;
        //(blocks that only stand: each put down once, anywhere it may stand; the final form's)
        Set<String> working = new HashSet<>();
        for(Recipe r : recipes.values()) if(r.kind != Recipe.Kind.source) working.add(r.block.name);
        if(fin) for(Block b : Final.erekir()){
            if(working.contains(b.name) || b instanceof mindustry.world.blocks.storage.CoreBlock) continue;
            Body body = new Body(id++, b.name, false);
            body.add(b, 0, 0, 0, null, 1f, null);
            body.put(0, 0, 0);
            extra.add(body);
        }
        List<List<Object[]>> groups = mod.cut();
        System.out.println(String.format("the plan: %d machines in %d modules, %d bodies besides (%d on their places)", plan.machines.size(), groups.size(), extra.size(), extra.stream().filter(b -> b.fixed).count()));
        List<Object[]> pairs = mod.build(groups);
        System.out.println(String.format("%d modules in %.1f s", pairs.size(), (System.nanoTime() - t0) / 1e9));
        Spec s = mod.composeFitting(pairs, extra, 1L);
        s.name = prob.name;
        s.world = world;
        new Fi(out).writeString("# the final form laid out by bp.Master from " + planFile + "\n" + s.write() + "prime\n");
        try{
            Plot.draw(mod.last.board, mod.last.bodies, mod.last.bestNets, mod.last.bestPower, prob.name + ": the final form" + (mod.last.problems > 0 ? "  (" + mod.last.problems + " problems left)" : ""), new java.io.File(new Fi(out).pathWithoutExtension() + ".png"));
        }catch(Exception e){
            System.out.println("could not draw: " + e);
        }
        structure(mod.last, new Fi(out).pathWithoutExtension() + ".master");
        int bad = mod.last.verify(s, false);
        System.out.println(String.format("laid out in %.1f s; verified: %s", (System.nanoTime() - t0) / 1e9, bad == 0 ? "clean by the game's rules" : bad + " things wrong by the game's rules"));
    }
}
