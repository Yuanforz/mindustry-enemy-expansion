package expansion.econ;

import arc.files.*;
import expansion.Blueprint.Part;
import mindustry.*;
import mindustry.game.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.storage.*;

import java.io.*;
import java.util.*;

/**
 * From a map and an aim to a plan laid out on the ground and ordered: what to build, where, and in what order. The
 * planner's balance and order, then the layout, then round again on what the layout could not feed (makers of their
 * own for those, or the map's machines left out), then the order again on what each job really costs.
 *
 * Takes what the core holds and the team's machines as they stand: a later stage is planned the same way as the first,
 * from what is there by then.
 */
public final class Plan{
    public Catalog cat;
    public Field field;
    public Planner planner;
    public Timeline model;
    public Layout works;
    /** What the planner and the layout said on the way. */
    public final PrintStream out;
    public int unit;
    public float wanted;
    public long layoutNanos;
    /**
     * Whether only the blocks that can be paid for now are planned with: those whose every material the core holds
     * enough of, or a drill (that can itself be paid for) or the core unit digs here. A player does not lay large bores
     * that cost oxide and tungsten on a map with no tungsten and no oxide coming in; the planner, given all the tech,
     * would, and then plan a whole chain of crafters to pay for its own drills. (Off for the economy bench, which plans
     * with each sector's tech.)
     */
    public boolean gate;
    /**
     * Where the side's own turrets reach (by tile, x + y * width), for a side that is being raided: machines that may stand
     * anywhere (furnaces, factories) are put inside it where they can be. Null: anywhere.
     */
    public @arc.util.Nullable boolean[] cover;
    /** Put before the plan's heading line (a map's name, say). */
    public String label = "";

    public Plan(PrintStream out){
        this.out = out;
    }

    /** Every block Erekir has that a player can build. */
    public static Set<String> erekir(){
        Set<String> all = new HashSet<>();
        for(Block b : Vars.content.blocks()) if(b.isOnPlanet(mindustry.content.Planets.erekir) && b.buildVisibility != mindustry.world.meta.BuildVisibility.sandboxOnly) all.add(b.name);
        return all;
    }

    /** The modules of the libraries named (our own designs first; the community's, where they are here, after). */
    public static List<Design> library(String dirs){
        List<Design> library = new ArrayList<>();
        for(String dir : dirs.split(",")){
            Fi d = new Fi(dir);
            if(d.exists()) library.addAll(Design.load(d, dir.endsWith("own") ? "" : "c-"));
        }
        return library;
    }

    /**
     * How fast the core unit can carry what a machine makes to the core: what one trip takes (the unit's stack, or what
     * the machine holds) over the trip there and back, with a few seconds for taking and handing over; 0 if the way is
     * shot over or the machine holds nothing.
     */
    public static float haulRate(Machine m, Team team){
        Block b = Vars.content.block(m.recipe.name);
        var core = team.core();
        if(b == null || !b.hasItems || core == null) return 0f;
        UnitType u = ((CoreBlock)core.block).unitType;
        float cx = core.tileX(), cy = core.tileY(), d = (float)Math.hypot(m.x - cx, m.y - cy);
        //the straight way there must keep out of the other side's turrets
        boolean[] shot = Sites.danger(team);
        int w = Vars.world.width();
        for(int i = 0; i <= (int)d; i++){
            int x = Math.round(cx + (m.x - cx) * i / Math.max(1f, d)), y = Math.round(cy + (m.y - cy) * i / Math.max(1f, d));
            if(shot[x + y * w]) return 0f;
        }
        float tilesPerSecond = u.speed * 60f / Vars.tilesize;
        float stack = Math.min(u.itemCapacity, b.itemCapacity);
        return stack / (2f * d / tilesPerSecond + 4f);
    }

    /**
     * Plans the making of a unit (machines: how many of its makers) or an item (how much a second the core is to get).
     * blocks: what may be built as machines; allowed: those and the lines and power. income: what the map's own base
     * brings the core a second, as watched (for leaving out what of it does not really run). Null if there is no way.
     */
    public Plan make(Team me, List<String> blocks, Set<String> allowed, List<Design> library, String unitName, float machines, Map<String, Float> income){
        //with the gate on, planned with the tech that is near at hand, a little further, and all of it, and laid out as the
        //one that gets there first (with all of Erekir's tech, two stell fabricators were planned behind large bores that
        //cost oxide, and so an electrolyzer and an oxidation chamber: the first stell at 5:09 where 3:52 would do)
        int[] tries = gate ? new int[]{2, 3, Integer.MAX_VALUE} : new int[]{Integer.MAX_VALUE};
        Object[] best = null;
        for(int d : tries){
            if(!model(me, blocks, allowed, library, unitName, machines, income, d)) continue;
            float first = model.first[unit], t = model.t;
            if(best == null || first < (Float)best[5] - 0.5f || Math.abs(first - (Float)best[5]) <= 0.5f && t < (Float)best[6] - 0.5f){
                best = new Object[]{cat, field, planner, model, allowedNow, first, t, d, wanted};
            }
        }
        if(best == null) return null;
        cat = (Catalog)best[0];
        field = (Field)best[1];
        planner = (Planner)best[2];
        model = (Timeline)best[3];
        allowed = allowedNow = (Set<String>)best[4];
        wanted = (Float)best[8];
        unit = cat.id(unitName);
        long t0;
        if(tries.length > 1) out.println("  taken: the plan with materials " + ((Integer)best[7] == Integer.MAX_VALUE ? "as far away as need be" : "at most " + best[7] + " steps of making away"));
        //laid out on the map; what it all really costs goes back to the planner, which may then order it otherwise
        long l0 = System.nanoTime();
        check("after the balance");
        works = new Layout(cat, field, me, allowed);
        works.cover = cover;
        check("after a new layout");
        t0 = Took.start();
        works.prepare(model.order);
        Took.end("plan: layout (1st)", t0);
        check("after the layout");
        Set<String> noTap = new HashSet<>();
        //takers the layout could not feed get makers of their own, and it is all done again
        for(int round = 0; round < 3; round++){
            t0 = Took.start();
            List<Object[]> unfed = works.unfed();
            List<Machine> cut = works.cutOff();
            Took.end("plan: unfed", t0);
            //a tap dearer than a maker of the taker's own near it: the maker instead (the map's line goes on to the core)
            List<Machine> instead = new ArrayList<>();
            //(off unless asked, -Dxp.deartap: the maker is often built late, and the line from it ends up slower than the tap)
            if(Boolean.getBoolean("xp.deartap")) for(Object[] t : works.taps()){
                int res = (Integer)t[0];
                Machine c = (Machine)t[1];
                float tapCost = (Float)t[2];
                Part cp = works.at.get(c);
                if(cp == null || noTap.contains(res + ":" + c.recipe.name)) continue;
                Machine m = planner.makerNear(res, c, cp.x, cp.y, false);
                if(m == null) continue;
                int makerCost = 0;
                for(int x : m.cost) makerCost += x;
                if(tapCost <= makerCost) continue;
                noTap.add(res + ":" + c.recipe.name);
                instead.add(planner.makerNear(res, c, cp.x, cp.y, true));
                out.println(String.format("  the tap of %s for %s costs %.0f, a %s of its own %d: that instead", cat.get(res).name, c.recipe.name, tapCost, m.recipe.name, makerCost));
            }
            if(unfed.isEmpty() && cut.isEmpty() && instead.isEmpty()) break;
            List<Machine> more = new ArrayList<>(model.order);
            more.addAll(instead);
            Set<String> asked = new HashSet<>();
            boolean replan = false, hauled = false;
            for(Object[] u : unfed){
                int res = (Integer)u[0];
                Machine c = (Machine)u[1];
                if(!asked.add(res + "@" + System.identityHashCode(c))) continue;
                out.println("    unfed: " + c + " of " + cat.get(res).name + ": " + u[2]);
                //a maker of the plan's with no way to the core: its place is given up, and the plan made again
                if(u.length > 3){
                    //what it holds can be carried by hand, if the core unit can get there and back unshot (a stack a trip)
                    float rate = haulRate(c, me);
                    if(rate > 0f){
                        c.hauled = true;
                        c.haulRate = rate;
                        hauled = true;
                        out.println(String.format("  %s can't send %s to the core: the core unit carries it, %.2f/s", c, cat.get(res).name, rate));
                        continue;
                    }
                    field.sites.removeIf(st -> st.x == c.x && st.y == c.y && st.ground == c.recipe.ground);
                    field.revivable.removeIf(o -> o.x == c.x && o.y == c.y);
                    replan = true;
                    out.println("  " + c + " can't send " + cat.get(res).name + " to the core: its place is given up");
                    continue;
                }
                //what the map's own make of it can't be got to: the plan may not count on them, and is made again
                List<Machine> theirs = new ArrayList<>();
                for(Machine m : field.existing) if(m.out(res) > 1e-4f) theirs.add(m);
                if(!theirs.isEmpty() && cat.get(res).kind == Res.Kind.item && String.valueOf(u[2]).startsWith("tap")){
                    field.existing.removeAll(theirs);
                    replan = true;
                    out.println("  " + c.recipe.name + " can't be fed " + cat.get(res).name + " off the map's lines: its " + theirs.size() + " makers are left out of the plan");
                    continue;
                }
                Machine m = planner.makerNear(res, c);
                if(m != null) more.add(m);
            }
            //the map's machines on a grid that can't be joined to the plan's give it no power
            if(!cut.isEmpty()){
                field.existing.removeAll(cut);
                replan = true;
                out.println("  " + cut.size() + " machines of the map's on a grid of their own, left out of the plan's power");
            }
            if(replan){
                planner = new Planner(cat, field);
                more = planner.balance(unit, wanted);
            }else if(more.size() == model.order.size() && !hauled) break;
            if(replan) more.addAll(instead);
            out.println("  the layout could not feed " + unfed.size() + " machine(s); makers of their own added: " + planner.notes.toString().trim().replace("\n", "; "));
            planner.extras = 0;
            model = planner.order(more, unit);
            works = new Layout(cat, field, me, allowed);
        works.cover = cover;
            works.noTap.addAll(noTap);
            t0 = Took.start();
            works.prepare(model.order);
            Took.end("plan: layout (again)", t0);
        }
        long l1 = System.nanoTime();
        layoutNanos = l1 - l0;
        out.println(String.format("  laid out in %.1fs: %s", (l1 - l0) / 1e9, works.summary()));
        //what it all really costs (and how long each job takes) goes back to the planner, which orders it again; who owns
        //which line depends on the order, so this goes round until the order holds still
        int modelSpent = 0, realSpent = 0;
        for(Machine m : model.order) for(int c : m.cost) modelSpent += c;
        planner.extras = 0;
        float firstModel = model.first[unit], doneModel = model.t;
        t0 = Took.start();
        for(int round = 0; round < 3; round++){
            for(Machine m : model.order){
                int[] real = works.realCost(m);
                m.buildSeconds = works.buildSeconds(m);
                m.draw = works.drawShare(m);
                System.arraycopy(real, 0, m.cost, 0, real.length);
            }
            Timeline again = planner.order(new ArrayList<>(model.order), unit);
            boolean same = again.order.equals(model.order);
            model = again;
            works.reorder(model.order);
            if(same) break;
        }
        //what must come first comes first (a pipe's maker before what it feeds)
        List<Machine> fixed = works.mustComeFirst(model.order);
        if(!fixed.equals(model.order)){
            out.println("  moved up in the order: makers of liquids before what they feed");
            works.reorder(fixed);
            model.order = fixed;
        }
        //and the model's times for the order as it now is, with what each job now really costs
        for(Machine m : model.order){
            System.arraycopy(works.realCost(m), 0, m.cost, 0, m.cost.length);
            m.buildSeconds = works.buildSeconds(m);
            m.draw = works.drawShare(m);
            for(int c : m.cost) realSpent += c;
        }
        Timeline settled = new Timeline(cat, field, model.order);
        settled.runToGoal(unit, planner.limit);
        model = settled;
        Took.end("plan: reorder on real costs", t0);
        if(Boolean.getBoolean("xp.time")) out.print("  where the time went:\n" + Took.report());
        out.println(String.format("  the plan cost %d in items, the layout %d; ordered again on what it really costs: done by %s, first %s at %s (was %s, %s)",
            modelSpent, realSpent, time(model.t), unitName, time(model.first[unit]), time(doneModel), time(firstModel)));
        for(String p : works.problems) out.println("  checker: " + p);
        return this;
    }

    /**
     * The catalog and the places on the map, for these blocks; with {@link #gate} on, for those of them that can be paid
     * for now only. What may be built, as it then is.
     */
    /** What may be built in the plan worked out last. */
    Set<String> allowedNow;

    /**
     * The model of the making of a unit or an item, with the blocks whose materials are at most this many steps away (see
     * {@link #depths}): the catalog, the places, the balance and the order. Whether it gets there at all.
     */
    boolean model(Team me, List<String> blocks, Set<String> allowed, List<Design> library, String unitName, float machines, Map<String, Float> income, int maxDepth){
        long t0 = Took.start();
        allowedNow = read(me, blocks, allowed, maxDepth);
        Took.end("plan: recipes and sites", t0);
        if(income != null){
            String pruned = Sites.prune(cat, field, income, me);
            if(!pruned.isEmpty()) out.println("  the map's own: " + pruned);
        }
        check("after the scan");
        planner = new Planner(cat, field);
        t0 = Took.start();
        Recipes.modules(cat, library, allowedNow);
        Took.end("plan: modules", t0);
        check("after the modules");
        Recipe making = null;
        unit = cat.id(unitName);
        if(unit < 0) return false;
        for(Recipe r : cat.recipes) if(r.unitOut == unit) making = r;
        wanted = making != null ? machines / making.unitSeconds : machines;
        t0 = Took.start();
        List<Machine> plant = planner.balance(unit, wanted);
        Took.end("plan: balance", t0);
        if(Boolean.getBoolean("xp.chains")){
            out.print(planner.chains.describe());
            Map<String, Integer> kinds = new TreeMap<>();
            for(Field.Site st : field.sites) kinds.merge(st.ground + "/" + (st.item >= 0 ? cat.get(st.item).name : "-"), 1, Integer::sum);
            out.println("  places: " + kinds + " (of " + field.candidates + " looked at)");
        }
        if(!planner.notes.toString().isEmpty()) out.print("  planner: " + planner.notes.toString().trim().replace("\n", "\n  planner: ") + "\n");
        t0 = Took.start();
        model = planner.order(plant, unit);
        Took.end("plan: order", t0);
        out.println("######## " + label + unitName + " from " + machines + "; the model has it done by " + time(model.t)
            + ", first " + unitName + " at " + time(model.first[unit]) + "; " + model.order.size() + " machines"
            + (gate ? maxDepth == Integer.MAX_VALUE ? " (all the tech that can be paid for)" : " (materials at most " + maxDepth + " steps away)" : ""));
        //never got to: nothing laid out (a later stage may ask for something the map has no way to make)
        return !Float.isInfinite(model.first[unit]) && !Float.isNaN(model.first[unit]);
    }

    Set<String> read(Team me, List<String> blocks, Set<String> allowed){
        return read(me, blocks, allowed, Integer.MAX_VALUE);
    }

    /**
     * The catalog and the places on the map, for these blocks; with {@link #gate} on, only those that can be paid for now,
     * and of those only the ones whose materials are at most this many steps of making away (see {@link #depths}). What
     * may be built, as it then is.
     */
    Set<String> read(Team me, List<String> blocks, Set<String> allowed, int maxDepth){
        CoreBlock.CoreBuild core = me.core();
        cat = Recipes.read(blocks, (CoreBlock)core.block, Vars.state.rules.buildSpeed(me));
        cat.logistics(allowed);
        field = Sites.scan(cat, me, Integer.getInteger("xp.radius", 110));
        if(!gate) return allowed;
        Map<String, Integer> depth = depths(blocks, me);
        List<String> kept = new ArrayList<>();
        for(String name : blocks) if(depth.getOrDefault(name, Integer.MAX_VALUE) <= maxDepth || (maxDepth == Integer.MAX_VALUE && depth.containsKey(name))) kept.add(name);
        if(kept.size() == blocks.size()) return allowed;
        Set<String> left = new TreeSet<>(blocks);
        left.removeAll(kept);
        if(maxDepth == Integer.MAX_VALUE) out.println("  not yet (what they are built of is neither in the core nor dug here): " + String.join(" ", left));
        Set<String> fewer = new HashSet<>(allowed);
        fewer.removeAll(left);
        cat = Recipes.read(kept, (CoreBlock)core.block, Vars.state.rules.buildSpeed(me));
        cat.logistics(fewer);
        field = Sites.scan(cat, me, Integer.getInteger("xp.radius", 110));
        return fewer;
    }

    /**
     * For each of these blocks that can be paid for now (see {@link #gate}), how many steps of making away its materials
     * are: 0 for what the core holds enough of or what is dug (by the core unit, or by a drill that is itself 0), and for
     * what a machine makes one more than the furthest of the machine's own materials and its inputs. Silicon is two away (a
     * crusher's sand into a furnace), tungsten three (an impact drill costs silicon), oxide five. Blocks that can't be paid
     * for at all are left out. (Worked out round by round, from the catalog and places just read.)
     */
    Map<String, Integer> depths(List<String> blocks, Team me){
        CoreBlock.CoreBuild core = me.core();
        int[] depth = new int[cat.size()];
        Arrays.fill(depth, Integer.MAX_VALUE);
        for(int k = 0; k < cat.size(); k++){
            Res r = cat.get(k);
            if(r.kind != Res.Kind.item) continue;
            Item item = Vars.content.item(r.name);
            if(item != null && core.items.get(item) > 0) depth[k] = 0;
            if(field.handDistance != null && k < field.handDistance.length && !Float.isInfinite(field.handDistance[k])) depth[k] = 0;
        }
        //places on the ground, by block, and what each gives
        Map<String, Set<Integer>> placed = new HashMap<>();
        boolean vents = false;
        for(Field.Site st : field.sites){
            if(st.ground == Recipe.Ground.vent) vents = true;
            if(st.item >= 0 && st.block != null) placed.computeIfAbsent(st.block, x -> new HashSet<>()).add(st.item);
        }
        for(boolean more = true; more; ){
            more = false;
            for(Recipe r : cat.recipes){
                if(r.makesUnits() || r.block == null) continue;
                Block b = Vars.content.block(r.block);
                if(b == null || (r.ground == Recipe.Ground.vent && !vents)) continue;
                int at = blockDepth(b, core, depth);
                for(int k = 0; k < cat.size() && at != Integer.MAX_VALUE; k++) if(r.in[k] > 0f) at = Math.max(at, depth[k]);
                if(at == Integer.MAX_VALUE) continue;
                //(a drill on the ground gives what it digs at the depth of what it is made of; a crafter one step further)
                for(int k = 0; k < cat.size(); k++){
                    boolean gives = r.out[k] > 0f;
                    boolean dug = r.perFactor[k] > 0f && placed.getOrDefault(r.block, Set.of()).contains(k);
                    if(!gives && !dug) continue;
                    int d = dug && r.ground != Recipe.Ground.none ? at : at + 1;
                    if(d < depth[k]){
                        depth[k] = d;
                        more = true;
                    }
                }
            }
        }
        Map<String, Integer> out = new HashMap<>();
        for(String name : blocks){
            Block b = Vars.content.block(name);
            if(b == null || b instanceof CoreBlock){
                out.put(name, 0);
                continue;
            }
            int d = blockDepth(b, core, depth);
            if(d != Integer.MAX_VALUE) out.put(name, d);
        }
        return out;
    }

    /** How far away the furthest of a block's materials is (0 for what the core holds enough of); MAX_VALUE if one can't be had. */
    int blockDepth(Block b, CoreBlock.CoreBuild core, int[] depth){
        int d = 0;
        for(mindustry.type.ItemStack stack : b.requirements){
            if(core.items.get(stack.item) >= stack.amount) continue;
            int k = cat.id(stack.item.name);
            if(k < 0 || depth[k] == Integer.MAX_VALUE) return Integer.MAX_VALUE;
            d = Math.max(d, depth[k]);
        }
        return d;
    }

    /** (debug, -Dxp.checksites: how many of the places found can no longer be built on) */
    void check(String when){
        if(!Boolean.getBoolean("xp.checksites")) return;
        int bad = 0;
        for(Field.Site st : field.sites){
            Recipe r = null;
            for(Recipe q : cat.recipes) if(q.ground == st.ground) r = q;
            Block b = null;
            for(Recipe q : cat.recipes) if(q.ground == st.ground && Vars.content.block(q.name) != null && Vars.content.block(q.name).canPlaceOn(Vars.world.tile(st.x, st.y), Vars.state.rules.defaultTeam, st.rotation)) b = Vars.content.block(q.name);
            if(b == null) bad++;
        }
        out.println("  sites " + when + ": " + bad + " of " + field.sites.size() + " can't be built on");
    }

    /**
     * Plans a defence: the defence planner's modules pinned where it put them, makers for what they fire (the balance, as
     * for any machine), the lines into them laid; makers first, then the modules in the planner's order, walls last.
     */
    public Plan defend(Team me, List<String> blocks, Set<String> allowed, expansion.defense.Planner.Plan defence, Collection<String> feedable){
        CoreBlock.CoreBuild core = me.core();
        allowed = read(me, blocks, allowed);
        StringBuilder notes = new StringBuilder();
        //our turrets standing dry first (only a line to build), then what the defence planner laid
        List<Machine> guard = new ArrayList<>(Defence.existing(cat, me, feedable, notes));
        if(defence != null) guard.addAll(Defence.machines(cat, defence, notes));
        if(notes.length() > 0) out.print("  defence: " + notes.toString().trim().replace("\n", "\n  defence: ") + "\n");
        if(guard.isEmpty()) return null;
        planner = new Planner(cat, field);
        planner.chains = new Chains(cat, field);
        planner.demand = cat.zeros();
        unit = -1;
        //each module fed by drills of its own, the nearest that dig what it fires with nothing put into them (a line from
        //a drill by the choke is short and is there for it alone); then the balance for the rest (power, say)
        Map<Machine, List<Machine>> feeders = new LinkedHashMap<>();
        List<Machine> plant = new ArrayList<>(guard);
        for(Machine g : guard){
            List<Machine> mine = new ArrayList<>();
            feeders.put(g, mine);
            int cx = g.x + g.recipe.width(0) / 2, cy = g.y + g.recipe.height(0) / 2;
            for(int k = 0; k < cat.size(); k++){
                if(g.recipe.in[k] <= 0f) continue;
                float got = 0f;
                for(int guardN = 0; guardN < 8 && got < g.recipe.in[k] - 1e-4f; guardN++){
                    Machine m = bareMaker(k, cx, cy);
                    if(m == null) break;
                    mine.add(m);
                    plant.add(m);
                    got += m.out(k);
                }
            }
        }
        plant = planner.balanceRest(plant);
        if(!planner.notes.toString().isEmpty()) out.print("  planner: " + planner.notes.toString().trim().replace("\n", "\n  planner: ") + "\n");
        long l0 = System.nanoTime();
        this.feeders = feeders;
        List<Machine> order = defenceOrder(plant, guard);
        works = new Layout(cat, field, me, allowed);
        works.cover = cover;
        works.prepare(order);
        //takers no line could be laid to get makers of their own near them, and it is laid again
        for(int round = 0; round < 2; round++){
            List<Object[]> unfed = works.unfed();
            if(unfed.isEmpty()) break;
            List<Machine> more = new ArrayList<>(order);
            Set<String> asked = new HashSet<>();
            for(Object[] u : unfed){
                int res = (Integer)u[0];
                Machine c = (Machine)u[1];
                if(!asked.add(res + "@" + System.identityHashCode(c))) continue;
                out.println("    unfed: " + c + " of " + cat.get(res).name + ": " + u[2]);
                if(u.length > 3) continue;
                Machine m = planner.makerNear(res, c);
                if(m != null) more.add(m);
            }
            if(more.size() == order.size()) break;
            order = defenceOrder(more, guard);
            works = new Layout(cat, field, me, allowed);
        works.cover = cover;
            works.prepare(order);
        }
        layoutNanos = System.nanoTime() - l0;
        order = works.mustComeFirst(order);
        works.reorder(order);
        int spent = 0;
        for(Machine m : order){
            System.arraycopy(works.realCost(m), 0, m.cost, 0, m.cost.length);
            m.buildSeconds = works.buildSeconds(m);
            for(int c : m.cost) spent += c;
        }
        model = new Timeline(cat, field, order);
        out.println(String.format("######## %sdefence: %d modules and wall lines, %d machines in all, %d items; laid out in %.1fs: %s", label, guard.size(), order.size(), spent, layoutNanos / 1e9, works.summary()));
        for(String p : works.problems) out.println("  checker: " + p);
        return this;
    }

    Map<Machine, List<Machine>> feeders = new HashMap<>();

    /**
     * Module by module in the defence's order: its drills, then it, so the first module fires before the last is begun;
     * then what else the balance added.
     */
    List<Machine> defenceOrder(List<Machine> plant, List<Machine> guard){
        Set<Machine> fed = new HashSet<>();
        for(List<Machine> l : feeders.values()) fed.addAll(l);
        List<Machine> rest = new ArrayList<>();
        for(Machine m : plant) if(!guard.contains(m) && !fed.contains(m)) rest.add(m);
        List<Machine> order = new ArrayList<>();
        for(Machine g : guard){
            for(Machine m : feeders.getOrDefault(g, List.of())) if(plant.contains(m)) order.add(m);
            order.add(g);
        }
        //(what the balance added besides, power say, after: a module that needs it waits for it, the others fire)
        order.addAll(planner.plainOrder(rest));
        return order;
    }

    /** A drill on the free place nearest a spot that digs this with nothing put into it; taken. Null if none. */
    Machine bareMaker(int res, int x, int y){
        Field.Site best = null;
        Recipe bestRecipe = null;
        double bestD = Double.MAX_VALUE;
        for(Recipe r : cat.recipes){
            if(r.ground == Recipe.Ground.none || !(r.out[res] > 0f || r.perFactor[res] > 0f)) continue;
            boolean bare = true;
            for(float v : r.in) if(v > 0f) bare = false;
            if(!bare) continue;
            for(Field.Site s : planner.free){
                if(s.ground != r.ground || (r.perFactor[res] > 0f && s.item != res)) continue;
                double rate = r.perFactor[res] > 0f ? r.perFactor[res] * s.factor : r.out[res];
                if(rate <= 1e-4f) continue;
                //near, and giving much
                double d = Math.hypot(s.x - x, s.y - y) - rate * 8;
                if(d < bestD){
                    bestD = d;
                    best = s;
                    bestRecipe = r;
                }
            }
        }
        if(best == null) return null;
        planner.free.remove(best);
        return planner.machine(bestRecipe, best);
    }

    public static String time(float s){
        if(Float.isNaN(s) || Float.isInfinite(s)) return "never";
        return String.format("%d:%02d", (int)s / 60, (int)s % 60);
    }
}
