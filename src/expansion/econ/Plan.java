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
        CoreBlock.CoreBuild core = me.core();
        cat = Recipes.read(blocks, (CoreBlock)core.block, Vars.state.rules.buildSpeed(me));
        cat.logistics(allowed);
        field = Sites.scan(cat, me, Integer.getInteger("xp.radius", 110));
        if(income != null){
            String pruned = Sites.prune(cat, field, income, me);
            if(!pruned.isEmpty()) out.println("  the map's own: " + pruned);
        }
        planner = new Planner(cat, field);
        Recipes.modules(cat, library, allowed);
        Recipe making = null;
        unit = cat.id(unitName);
        if(unit < 0) return null;
        for(Recipe r : cat.recipes) if(r.unitOut == unit) making = r;
        wanted = making != null ? machines / making.unitSeconds : machines;
        List<Machine> plant = planner.balance(unit, wanted);
        if(Boolean.getBoolean("xp.chains")){
            out.print(planner.chains.describe());
            Map<String, Integer> kinds = new TreeMap<>();
            for(Field.Site st : field.sites) kinds.merge(st.ground + "/" + (st.item >= 0 ? cat.get(st.item).name : "-"), 1, Integer::sum);
            out.println("  places: " + kinds + " (of " + field.candidates + " looked at)");
        }
        if(!planner.notes.toString().isEmpty()) out.print("  planner: " + planner.notes.toString().trim().replace("\n", "\n  planner: ") + "\n");
        model = planner.order(plant, unit);
        out.println("######## " + label + unitName + " from " + machines + "; the model has it done by " + time(model.t)
            + ", first " + unitName + " at " + time(model.first[unit]) + "; " + model.order.size() + " machines");

        //laid out on the map; what it all really costs goes back to the planner, which may then order it otherwise
        long l0 = System.nanoTime();
        works = new Layout(cat, field, me, allowed);
        works.prepare(model.order);
        Set<String> noTap = new HashSet<>();
        //takers the layout could not feed get makers of their own, and it is all done again
        for(int round = 0; round < 3; round++){
            List<Object[]> unfed = works.unfed();
            List<Machine> cut = works.cutOff();
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
            works.noTap.addAll(noTap);
            works.prepare(model.order);
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
        out.println(String.format("  the plan cost %d in items, the layout %d; ordered again on what it really costs: done by %s, first %s at %s (was %s, %s)",
            modelSpent, realSpent, time(model.t), unitName, time(model.first[unit]), time(doneModel), time(firstModel)));
        for(String p : works.problems) out.println("  checker: " + p);
        return this;
    }

    public static String time(float s){
        if(Float.isNaN(s) || Float.isInfinite(s)) return "never";
        return String.format("%d:%02d", (int)s / 60, (int)s % 60);
    }
}
