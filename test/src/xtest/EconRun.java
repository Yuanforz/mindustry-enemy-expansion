package xtest;

import arc.*;
import arc.files.*;
import arc.util.*;
import expansion.Blueprint.Part;
import expansion.econ.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.entities.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.storage.*;

import java.util.*;

/**
 * Plays the economy planner's order in the game itself, with the enemy kept still, and sets what happens against what the
 * model said would: when each machine was begun and finished, when the first unit came out, and how each machine is
 * running at the end.
 *
 * Usage: EconRun data-dir map.msav sector unit machines minutes
 */
public class EconRun{
    /** Lines and power that each sector adds, on top of the machines {@link EconLab#tech} lists. */
    static final String[][] logistics = {
        {"onset", "duct", "duct-router", "duct-bridge", "beam-node"},
        {"aegis", "underflow-duct", "overflow-duct", "reinforced-conduit", "reinforced-bridge-conduit", "reinforced-liquid-router"},
    };

    static final Map<String, Float> firstMade = new LinkedHashMap<>();
    static int lastProgress = -1, lastProgressAt;

    /** The lines and power a sector has, and every one before it. */
    static Set<String> logisticsOf(String sector){
        Set<String> out = new HashSet<>();
        for(String[] row : logistics){
            for(int i = 1; i < row.length; i++) out.add(row[i]);
            if(row[0].equals(sector)) break;
        }
        return out;
    }

    /**
     * How fast the core unit can carry what a machine makes to the core: what one trip takes (the unit's stack, or what
     * the machine holds) over the trip there and back, with a few seconds for taking and handing over; 0 if the way is
     * shot over or the machine holds nothing.
     */
    static float haulRate(Machine m, Catalog cat, Team team){
        Block b = Vars.content.block(m.recipe.name);
        var core = team.core();
        if(b == null || !b.hasItems || core == null) return 0f;
        UnitType u = ((CoreBlock)core.block).unitType;
        float cx = core.tileX(), cy = core.tileY(), d = (float)Math.hypot(m.x - cx, m.y - cy);
        //the straight way there must keep out of the other side's turrets
        boolean[] shot = expansion.econ.Sites.danger(team);
        int w = Vars.world.width();
        for(int i = 0; i <= (int)d; i++){
            int x = Math.round(cx + (m.x - cx) * i / Math.max(1f, d)), y = Math.round(cy + (m.y - cy) * i / Math.max(1f, d));
            if(shot[x + y * w]) return 0f;
        }
        float tilesPerSecond = u.speed * 60f / Vars.tilesize;
        float stack = Math.min(u.itemCapacity, b.itemCapacity);
        return stack / (2f * d / tilesPerSecond + 4f);
    }

    /** What stands in an area asked for (-Dxp.dump=x0,y0,x1,y1): each building's block, facing and what it holds. */
    static void dumpArea(String when){
        String d = System.getProperty("xp.dump");
        if(d == null) return;
        String[] a = d.split(",");
        int x0 = Integer.parseInt(a[0]), y0 = Integer.parseInt(a[1]), x1 = Integer.parseInt(a[2]), y1 = Integer.parseInt(a[3]);
        System.out.println("  what stands " + when + ":");
        for(int y = y1; y >= y0; y--){
            for(int x = x0; x <= x1; x++){
                Building b = Vars.world.build(x, y);
                if(b == null || b.tileX() != x || b.tileY() != y) continue;
                System.out.println(String.format("    %d,%d %s r%d %s", x, y, b.block.name, b.rotation, b.items == null ? "" : b.items.toString()));
            }
        }
    }

    public static void main(String[] args){
        Boot.boot(args[0]);
        String sector = args[2], unitName = args[3];
        //(for a unit: how many of its machines; for an item: how much a second the core is to get)
        float machines = Float.parseFloat(args[4]);
        float minutes = Float.parseFloat(args[5]);
        List<String> blocks = new ArrayList<>();
        for(String[] row : EconLab.tech){
            for(int i = 1; i < row.length; i++) blocks.add(row[i]);
            if(row[0].equals(sector)) break;
        }
        //"late": everything Erekir has
        if(sector.equals("late")) for(Block b : Vars.content.blocks()) if(b.isOnPlanet(Planets.erekir) && b.buildVisibility != mindustry.world.meta.BuildVisibility.sandboxOnly && !blocks.contains(b.name)) blocks.add(b.name);
        Set<String> allowed = new HashSet<>(blocks);
        for(String[] row : logistics){
            for(int i = 1; i < row.length; i++) allowed.add(row[i]);
            if(row[0].equals(sector)) break;
        }

        //the sector as the campaign lands on it
        Vars.logic.reset();
        SaveIO.load(new Fi(args[1]));
        Rules rules = Vars.state.rules;
        Planets.erekir.applyRules(rules, false);
        Team me = rules.defaultTeam, enemy = rules.waveTeam;
        CoreBlock.CoreBuild core = me.core();
        if(rules.loadout != null && rules.loadout.size > 0){
            core.items.clear();
            for(ItemStack stack : rules.loadout) core.items.set(stack.item, stack.amount);
        }
        rules.coreIncinerates = true;
        //the mod plays the enemy, which the game lets see the whole map: so does the test, unless asked (xp.fog)
        if(!Boolean.getBoolean("xp.fog")) rules.fog = false;
        rules.infiniteResources = false;
        //the enemy is kept still: this is about the economy alone
        enemy.rules().rtsAi = false;
        rules.waves = false;
        Time.setDeltaProvider(() -> 1f);
        Vars.logic.play();
        Vars.state.tick = 0;
        Boot.frames(2);

        //the map's own base, watched a while: what of it really gets to the core (some of it may stand idle, or feed
        //something else); then the core and the clock as they were
        Map<String, Float> income = new HashMap<>();
        int[] before = new int[Vars.content.items().size];
        for(Item item : Vars.content.items()) before[item.id] = core.items.get(item);
        float watched = 20f;
        arc.struct.IntSet live = new arc.struct.IntSet();
        for(int f = 0; f < watched * 60; f++){
            if(f % 60 == 0) for(Unit u : Groups.unit) if(u.team == enemy) u.kill();
            Boot.frames(1);
            //which of the map's ducts carry anything: a line seen empty all the while is not fed (or goes nowhere)
            if(f % 20 == 0) for(Building b : me.data().buildings) if(b.block instanceof mindustry.world.blocks.distribution.Duct && b.items != null && b.items.total() > 0) live.add(b.tileX() + b.tileY() * Vars.world.width());
        }
        //(not used: a duct holds its item a moment only, and sampling misses most lines that run)
        for(Item item : Vars.content.items()) income.put(item.name, (core.items.get(item) - before[item.id]) / watched);
        for(Item item : Vars.content.items()) core.items.set(item, before[item.id]);
        Vars.state.tick = 0;

        dumpArea("at the start");
        //the plan
        Catalog cat = Recipes.read(blocks, (CoreBlock)core.block, rules.buildSpeed(me));
        cat.logistics(allowed);
        Field field = Sites.scan(cat, me, Integer.getInteger("xp.radius", 110));
        String pruned = Sites.prune(cat, field, income, me);
        if(!pruned.isEmpty()) System.out.println("  the map's own: " + pruned);
        Planner planner = new Planner(cat, field);
        //the modules of the library that may be built here
        Recipes.modules(cat, Design.load(new Fi("blueprints/modules")), allowed);
        Recipe making = null;
        int unit = cat.id(unitName);
        for(Recipe r : cat.recipes) if(r.unitOut == unit) making = r;
        final float wanted = making != null ? machines / making.unitSeconds : machines;
        List<Machine> plant = planner.balance(unit, wanted);
        if(Boolean.getBoolean("xp.chains")) System.out.print(planner.chains.describe());
        if(!planner.notes.toString().isEmpty()) System.out.print("  planner: " + planner.notes.toString().trim().replace("\n", "\n  planner: ") + "\n");
        Timeline model = planner.order(plant, unit);
        System.out.println("######## " + new Fi(args[1]).nameWithoutExtension() + ": " + unitName + " from " + machines + "; the model has it done by " + EconLab.time(model.t)
            + ", first " + unitName + " at " + EconLab.time(model.first[unit]) + "; " + model.order.size() + " machines");

        //laid out on the map; what it all really costs goes back to the planner, which may then order it otherwise
        long l0 = System.nanoTime();
        Layout works = new Layout(cat, field, me, allowed);
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
                System.out.println(String.format("  the tap of %s for %s costs %.0f, a %s of its own %d: that instead", cat.get(res).name, c.recipe.name, tapCost, m.recipe.name, makerCost));
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
                System.out.println("    unfed: " + c + " of " + cat.get(res).name + ": " + u[2]);
                //a maker of the plan's with no way to the core: its place is given up, and the plan made again
                if(u.length > 3){
                    //what it holds can be carried by hand, if the core unit can get there and back unshot (a stack a trip)
                    float rate = haulRate(c, cat, me);
                    if(rate > 0f){
                        c.hauled = true;
                        c.haulRate = rate;
                        hauled = true;
                        System.out.println(String.format("  %s can't send %s to the core: the core unit carries it, %.2f/s", c, cat.get(res).name, rate));
                        continue;
                    }
                    field.sites.removeIf(st -> st.x == c.x && st.y == c.y && st.ground == c.recipe.ground);
                    field.revivable.removeIf(o -> o.x == c.x && o.y == c.y);
                    replan = true;
                    System.out.println("  " + c + " can't send " + cat.get(res).name + " to the core: its place is given up");
                    continue;
                }
                //what the map's own make of it can't be got to: the plan may not count on them, and is made again
                List<Machine> theirs = new ArrayList<>();
                for(Machine m : field.existing) if(m.out(res) > 1e-4f) theirs.add(m);
                if(!theirs.isEmpty() && cat.get(res).kind == Res.Kind.item){
                    field.existing.removeAll(theirs);
                    replan = true;
                    System.out.println("  " + c.recipe.name + " can't be fed " + cat.get(res).name + " off the map's lines: its " + theirs.size() + " makers are left out of the plan");
                    continue;
                }
                Machine m = planner.makerNear(res, c);
                if(m != null) more.add(m);
            }
            //the map's machines on a grid that can't be joined to the plan's give it no power
            if(!cut.isEmpty()){
                field.existing.removeAll(cut);
                replan = true;
                System.out.println("  " + cut.size() + " machines of the map's on a grid of their own, left out of the plan's power");
            }
            if(replan){
                planner = new Planner(cat, field);
                more = planner.balance(unit, wanted);
            }else if(more.size() == model.order.size() && !hauled) break;
            if(replan) more.addAll(instead);
            System.out.println("  the layout could not feed " + unfed.size() + " machine(s); makers of their own added: " + planner.notes.toString().trim().replace("\n", "; "));
            planner.extras = 0;
            model = planner.order(more, unit);
            works = new Layout(cat, field, me, allowed);
            works.noTap.addAll(noTap);
            works.prepare(model.order);
        }
        long l1 = System.nanoTime();
        System.out.println(String.format("  laid out in %.1fs: %s", (l1 - l0) / 1e9, works.summary()));
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
            System.out.println("  moved up in the order: makers of liquids before what they feed");
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
        System.out.println(String.format("  the plan cost %d in items, the layout %d; ordered again on what it really costs: done by %s, first %s at %s (was %s, %s)",
            modelSpent, realSpent, EconLab.time(model.t), unitName, EconLab.time(model.first[unit]), EconLab.time(doneModel), EconLab.time(firstModel)));
        for(String p : works.problems) System.out.println("  checker: " + p);
        expansion.econ.Hand hand = new expansion.econ.Hand(works, model.order);
        //what each module means to do, as words and as a picture (with the debug option on the game draws the same)
        Intent intent = works.intent();
        Intent.current = intent;
        hand.intent = intent;
        String mapName = new Fi(args[1]).nameWithoutExtension();
        IntentImage.write(intent, me, field.coreX, field.coreY, new java.io.File("build/intent/" + mapName + "-plan.png"));
        //a close-up asked for: -Dxp.look=x,y
        String look = System.getProperty("xp.look");
        if(look != null){
            int lx = Integer.parseInt(look.split(",")[0]), ly = Integer.parseInt(look.split(",")[1]);
            IntentImage.write(intent, me, lx - 14, ly - 14, lx + 14, ly + 14, new java.io.File("build/intent/" + mapName + "-look.png"));
        }
        int[] box = cellBox(works);
        if(box != null) IntentImage.write(intent, me, box[0], box[1], box[2], box[3], new java.io.File("build/intent/" + mapName + "-cells.png"));
        List<String> destroyed = new ArrayList<>();
        Events.on(BlockDestroyEvent.class, e -> {
            if(e.tile.team() == me && destroyed.size() < 40) destroyed.add(String.format("%s at %d,%d at %s", e.tile.block().name, e.tile.x, e.tile.y, EconLab.time((float)(Vars.state.tick / 60.0))));
        });
        Events.on(UnitCreateEvent.class, e -> {
            if(e.unit.team == me && !firstMade.containsKey(e.unit.type.name)) firstMade.put(e.unit.type.name, (float)(Vars.state.tick / 60.0));
        });
        Item goalItem = Vars.content.item(unitName);
        int lastGoal = goalItem == null ? 0 : core.items.get(goalItem);
        float goalIn = 0f;
        Unit builder = null;
        Timeline shadow = new Timeline(cat, field, new ArrayList<>(model.order));
        String wa = System.getProperty("xp.watchat");
        int[] watchAt = wa == null ? null : new int[]{Integer.parseInt(wa.split(",")[0]), Integer.parseInt(wa.split(",")[1])};
        int frames = (int)(minutes * 3600);
        for(int f = 0; f < frames; f++){
            if(builder == null || builder.dead()){
                builder = ((CoreBlock)core.block).unitType.spawn(me, core.x, core.y);
                builder.controller(hand);
            }
            if(f % 60 == 0) for(Unit u : Groups.unit) if(u.team == enemy) u.kill();
            Boot.frames(1);
            if(f % (60 * 30) == 0 && f > 0){
                System.out.println(String.format("  %s: %d of %d begun; core %s; %s", EconLab.time(f / 60f), hand.next, model.order.size(), holds(core),
                    hand.machine != null ? "building " + hand.machine.recipe.name : "digging " + (builder.mineTile == null ? "nothing" : builder.mineTile.wallDrop().name)));
                //the model at the same time, for set against it
                shadow.runUntil(f / 60f);
                StringBuilder ms = new StringBuilder();
                for(int k = 0; k < cat.size(); k++) if(cat.get(k).kind == Res.Kind.item && shadow.stock[k] >= 1f) ms.append(cat.get(k).name).append('=').append((int)shadow.stock[k]).append(' ');
                System.out.println(String.format("        model: %d of %d begun; core %s", shadow.next, model.order.size(), ms.toString().trim()));
            }
            //one building watched closely, asked for: -Dxp.watchat=x,y
            if(watchAt != null && f % 300 == 0){
                Building wb = Vars.world.build(watchAt[0], watchAt[1]);
                String pay = "";
                if(wb instanceof mindustry.world.blocks.payloads.PayloadBlock.PayloadBlockBuild<?> pb && pb.payload != null){
                    pay = " payload " + pb.payload + (pb.payload instanceof mindustry.world.blocks.payloads.UnitPayload up ? " cancreate " + Units.canCreate(me, up.unit.type) + " cap " + Units.getCap(me) + " count " + me.data().countType(up.unit.type) : "");
                }
                if(wb != null) System.out.println(String.format("    watch %s %s: efficiency %.2f, items %s, liquids %s, core %s%s", EconLab.time(f / 60f), wb.block.name, wb.efficiency, wb.items, wb.liquids == null ? "-" : wb.liquids.currentAmount(), holds(core), pay));
            }
            //an item aimed at: what comes into the core (spending taken no account of), till enough has
            if(goalItem != null){
                int now = core.items.get(goalItem);
                if(now > lastGoal) goalIn += now - lastGoal;
                lastGoal = now;
                if(goalIn >= Timeline.itemGoal && !firstMade.containsKey(unitName)) firstMade.put(unitName, (float)(Vars.state.tick / 60.0));
            }
            if(firstMade.containsKey(unitName) && f % 600 == 0) break;
            //no job begun or finished for three minutes, and no unit: it is stuck, and says where
            if(f % 600 == 0){
                int now = hand.next * 1000 + hand.watch.repaired;
                if(now != lastProgress){
                    lastProgress = now;
                    lastProgressAt = f;
                }else if(f - lastProgressAt > 3 * 3600) break;
            }
        }

        System.out.println("  machine                          model begun/done     game begun/done   parts cost (model cost)");
        for(int i = 0; i < model.order.size(); i++){
            Machine m = model.order.get(i);
            int modelCost = 0;
            for(int c : m.cost) modelCost += c;
            System.out.println(String.format("  %-30s %-9s %7s %7s     %7s %7s   %4d %5d (%d)", m.recipe.name, m.item >= 0 ? cat.get(m.item).name : "", EconLab.time(model.begun[i]), EconLab.time(model.finished[i]), EconLab.time(hand.begun[i]), EconLab.time(hand.finished[i]), hand.partsOf[i], hand.costOf[i], modelCost));
        }
        System.out.println("  first units, model: " + firsts(cat, model) + "; game: " + firstMade);
        //how the machines run once it is all up
        Boot.frames(60 * 30);
        System.out.println("  after 30 s more, how each runs:");
        for(Machine m : model.order){
            Part p = works.at.get(m);
            if(p == null) continue;
            Building b = Vars.world.build(p.x, p.y);
            String state = b == null || b.block != p.block ? "not there" : String.format("efficiency %.2f%s%s%s%s", b.efficiency,
                b.power != null ? String.format(", grid %d (%.0f made, %.0f wanted)", System.identityHashCode(b.power.graph) % 1000, b.power.graph.getLastPowerProduced() * 60f, b.power.graph.getLastPowerNeeded() * 60f) : "",
                b.items != null && b.items.total() > 0 ? " holding " + b.items : "",
                b.liquids != null && b.liquids.currentAmount() > 0.01f ? String.format(" liquid %s %.1f", b.liquids.current().name, b.liquids.currentAmount()) : "",
                b instanceof mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild uf ? (uf.payload != null ? " a unit waiting to get out" : "") + String.format(" progress %.0f/%.0f", uf.progress, uf.currentPlan >= 0 ? ((mindustry.world.blocks.units.UnitFactory)uf.block).plans.get(uf.currentPlan).time : 0f) : "");
            System.out.println(String.format("    %-30s at %d,%d: %s", m.recipe.name, p.x, p.y, state));
        }
        System.out.println("  what each module meant to do, and how it went (intent):");
        System.out.print(intent.report());
        IntentImage.write(intent, me, field.coreX, field.coreY, new java.io.File("build/intent/" + mapName + "-end.png"));
        if(box != null) IntentImage.write(intent, me, box[0], box[1], box[2], box[3], new java.io.File("build/intent/" + mapName + "-cells-end.png"));
        //the model against the game, machine by machine: what it makes at full speed, what the model's plant gives it once
        //all is built, and what the game measured (on average since it first ran, and over the last minute)
        Plant steady = new Plant(cat);
        List<Machine> all = new ArrayList<>(field.existing);
        all.addAll(model.order);
        boolean[] on = new boolean[all.size()];
        Arrays.fill(on, true);
        steady.solve(all, on, true);
        dumpArea("at the end");
        //each line of the plan's as it stands at the end: how many of its pieces stand, and what they hold (-Dxp.lines)
        if(Boolean.getBoolean("xp.lines")){
            System.out.print(works.linesNow());
        }
        //where things sit that are not in the core: what holds the most of each (a sink the plan does not know of shows here)
        {
            Map<String, List<String>> held = new TreeMap<>();
            for(Building b : me.data().buildings){
                if(b.items == null || b instanceof CoreBlock.CoreBuild) continue;
                for(Item item : Vars.content.items()){
                    int a = b.items.get(item);
                    if(a >= 10) held.computeIfAbsent(item.name, x -> new ArrayList<>()).add(String.format("%s@%d,%d:%d", b.block.name, b.tileX(), b.tileY(), a));
                }
            }
            for(var e : held.entrySet()){
                List<String> l = e.getValue();
                System.out.println("  held outside the core: " + e.getKey() + ": " + l.size() + " buildings; " + String.join(" ", l.subList(0, Math.min(6, l.size()))));
            }
        }
        for(Item item : Vars.content.items()){
            if(hand.digSeconds[item.id] < 5f) continue;
            int id = cat.id(item.name);
            System.out.println(String.format("  hand calibration: %s dug %.0f in %.0f s of digging = %.2f/s (model %.2f/s)", item.name, hand.delivered[item.id], hand.digSeconds[item.id], hand.delivered[item.id] / hand.digSeconds[item.id], field.handRate(cat, id)));
        }
        System.out.println("  calibration: machine, what it makes, model at full speed / model in the plant / game on average / game in the last minute / game at best");
        for(var e : hand.watch.rates.entrySet()){
            Machine m = e.getKey();
            float[] r = e.getValue();
            int k = m.item >= 0 ? m.item : main(cat, m);
            float full = k >= 0 ? m.out(k) : m.powerOut() / 60f;
            int idx = all.indexOf(m);
            float inPlant = idx >= 0 && idx < steady.eff.length ? full * steady.eff[idx] : Float.NaN;
            float avg = r[1] > 0 ? r[0] / r[1] : 0f;
            System.out.println(String.format("    %-30s %-9s %6.2f %6.2f %6.2f %6.2f %6.2f%s", m.recipe.name, k >= 0 ? cat.get(k).name : "power", full, inPlant, avg, r[2], r[3],
                avg < inPlant * 0.85f ? "   <- short" : r[3] > full * 1.15f ? "   <- more than the model's best" : ""));
        }
        System.out.println("  how each ran since it was built (watch):");
        System.out.print(hand.watch.report(cat));
        if(hand.watch.repaired > 0) System.out.println("  parts put back: " + hand.watch.repaired + ": " + String.join("; ", hand.watch.what));
        System.out.println(picture(works, me));
        System.out.println("  the cells close up:");
        System.out.println(cells(works, me));
        //where what each maker sends out ends up: followed duct by duct from it
        for(Machine m : model.order){
            Part p = works.at.get(m);
            if(p == null || m.recipe.makesUnits()) continue;
            Building b = Vars.world.build(p.x, p.y);
            if(b == null || b.block != p.block) continue;
            for(Building near : b.proximity){
                boolean conduit = near.block instanceof mindustry.world.blocks.liquid.Conduit;
                if(!(near.block instanceof mindustry.world.blocks.distribution.Duct || near.block instanceof mindustry.world.blocks.distribution.DirectionBridge || conduit)) continue;
                StringBuilder path = new StringBuilder();
                Building at = near;
                int steps = 0;
                java.util.Set<Building> seen = new java.util.HashSet<>();
                while(at != null && steps++ < 200 && seen.add(at)){
                    Building next = null;
                    if(at.block instanceof mindustry.world.blocks.distribution.Duct || at.block instanceof mindustry.world.blocks.liquid.Conduit) next = at.front();
                    else if(at instanceof mindustry.world.blocks.liquid.LiquidBridge.LiquidBridgeBuild lb) next = Vars.world.build(lb.link);
                    else if(at instanceof mindustry.world.blocks.distribution.DuctBridge.DuctBridgeBuild br) next = br.findLink() != null ? br.findLink() : at.front();
                    else break;
                    if(conduit && at.liquids != null) path.append(String.format(" %s%d,%d:%.1f", at.block.name.contains("bridge") ? "B" : "", at.tileX(), at.tileY(), at.liquids.currentAmount()));
                    if(next == null){ path.append(" -> nothing at the end (").append(at.tileX()).append(',').append(at.tileY()).append(')'); at = null; break; }
                    at = next;
                }
                String end = at == null ? "" : at.block.name + " at " + at.tileX() + "," + at.tileY() + (at.items != null ? " holding " + at.items : "") + (at.liquids != null ? String.format(" liquid %.1f", at.liquids.currentAmount()) : "");
                System.out.println(String.format("  out of %s at %d,%d via %d,%d (facing %d, %s): %d steps to %s%s", m.recipe.name, p.x, p.y, near.tileX(), near.tileY(), near.rotation, near.front() == b ? "INTO it" : "away", steps, end, path));
            }
        }
        if(works.log.length() > 0) System.out.print("  works: " + works.log.toString().replace("\n", "\n  works: ").trim() + "\n");
        for(String t : hand.trouble) System.out.println("  could not put down: " + t);
        //why each part that could not go down was refused, tile by tile
        for(String t : hand.trouble){
            String[] w = t.split(" ");
            Block b = Vars.content.block(w[0]);
            String[] xy = w[2].split(",");
            int x = Integer.parseInt(xy[0]), y = Integer.parseInt(xy[1]);
            StringBuilder why = new StringBuilder(String.format("  why %s at %d,%d was refused: placeable %b (banned %b), enemy core near %b, polygon rule %b, over the limit %b, valid ignoring units %b, canPlaceOn %b, units on it %b;",
                b.name, x, y, b.isPlaceable(), Vars.state.rules.isBanned(b), Vars.state.teams.anyEnemyCoresWithinBuildRadius(me, x * 8 + b.offset, y * 8 + b.offset), Vars.state.rules.polygonCoreProtection,
                b.isOverPlacementLimit(me), Build.validPlaceIgnoreUnits(b, me, x, y, 0, true, true), b.canPlaceOn(Vars.world.tile(x, y), me, 0),
                mindustry.entities.Units.anyEntities(x * 8 + b.offset - b.size * 4f, y * 8 + b.offset - b.size * 4f, b.size * 8f, b.size * 8f)));
            Vars.world.tile(x, y).getLinkedTilesAs(b, o -> {
                if(o.build != null || o.solid() || !Vars.fogControl.isDiscovered(me, o.x, o.y) || o.floor().isDeep() || !o.floor().placeableOn)
                    why.append(String.format(" %d,%d %s/%s seen %b;", o.x, o.y, o.floor().name, o.block().name, Vars.fogControl.isDiscovered(me, o.x, o.y)));
            });
            System.out.println(why);
        }
        for(String d : destroyed) System.out.println("  destroyed: " + d);
        //one line for the benchmark table
        Float made = firstMade.get(unitName);
        float lastDone = 0f;
        for(float f : hand.finished) if(!Float.isNaN(f)) lastDone = Math.max(lastDone, f);
        int done = 0;
        for(float f : hand.finished) if(!Float.isNaN(f)) done++;
        System.out.println(String.format("BENCH %s %s %s %s | model %s game %s | %s | built %d/%d model %s game %s | nets unlaid %d checker %d | layout %.1fs",
            mapName, sector, unitName, Strings.autoFixed(machines, 2), EconLab.time(model.first[unit]), made == null ? "never" : EconLab.time(made),
            made == null ? "-" : String.format("%.0f%%", 100f * model.first[unit] / made), done, model.order.size(), EconLab.time(model.t), done == model.order.size() ? EconLab.time(lastDone) : "-",
            works.failed, works.problems.size(), (l1 - l0) / 1e9) + (made == null ? " | stuck: " + hand.doing : ""));
        System.exit(0);
    }

    /** The ground round everything the plan put down, as text: blocks by their codes, ore walls by their item, vents '::'. */
    static String picture(Layout works, Team me){
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        for(Part p : works.at.values()){
            if(Math.hypot(p.x - works.field.coreX, p.y - works.field.coreY) > 45) continue;
            x0 = Math.min(x0, p.x - 3); y0 = Math.min(y0, p.y - 3); x1 = Math.max(x1, p.x + 3); y1 = Math.max(y1, p.y + 3);
        }
        x0 = Math.min(x0, works.field.coreX - 4); x1 = Math.max(x1, works.field.coreX + 4);
        y0 = Math.min(y0, works.field.coreY - 4); y1 = Math.max(y1, works.field.coreY + 4);
        return picture(me, x0, y0, x1, y1);
    }

    /** Round the cells and the core, for a close-up. */
    static int[] cellBox(Layout works){
        int x0 = works.field.coreX - 6, y0 = works.field.coreY - 6, x1 = works.field.coreX + 6, y1 = works.field.coreY + 6;
        boolean any = false;
        for(Machine m : works.order){
            Part p = works.at.get(m);
            if(p == null || m.recipe.ground != Recipe.Ground.none) continue;
            any = true;
            x0 = Math.min(x0, p.x - 8); y0 = Math.min(y0, p.y - 8); x1 = Math.max(x1, p.x + 8); y1 = Math.max(y1, p.y + 8);
        }
        return any ? new int[]{x0, y0, x1, y1} : null;
    }

    /** The cells close up: what the plan placed off the ground, and a margin. */
    static String cells(Layout works, Team me){
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        for(Machine m : works.order){
            Part p = works.at.get(m);
            if(p == null || m.recipe.ground != Recipe.Ground.none) continue;
            x0 = Math.min(x0, p.x - 8); y0 = Math.min(y0, p.y - 8); x1 = Math.max(x1, p.x + 8); y1 = Math.max(y1, p.y + 8);
        }
        if(x0 > x1) return "";
        return picture(me, x0, y0, x1, y1);
    }

    static String picture(Team me, int x0, int y0, int x1, int y1){
        arc.struct.ObjectIntMap<String> used = new arc.struct.ObjectIntMap<>();
        StringBuilder out = new StringBuilder();
        for(int y = y1; y >= y0; y--){
            out.append(String.format("  %4d ", y));
            for(int x = x0; x <= x1; x++){
                Tile t = Vars.world.tile(x, y);
                if(t == null){ out.append("  "); continue; }
                if(t.build != null && t.team() == me){
                    boolean origin = t.build.tile == t;
                    if(origin) used.increment(t.block().name);
                    out.append(tools.Ascii.cell(t.block() instanceof mindustry.world.blocks.ConstructBlock ? ((mindustry.world.blocks.ConstructBlock.ConstructBuild)t.build).current : t.block(), t.build.rotation, origin));
                }else if(t.build != null){
                    out.append("[]");
                }else if(t.block().solid){
                    Item drop = t.wallDrop();
                    float sand = t.block().attributes.get(mindustry.world.meta.Attribute.sand);
                    out.append(drop != null ? ("" + drop.name.charAt(0) + drop.name.charAt(0)) : sand > 0 ? "s" + (int)(sand * 2) : "%%");
                }else if(t.floor() instanceof mindustry.world.blocks.environment.SteamVent){
                    out.append("::");
                }else{
                    out.append("  ");
                }
            }
            out.append('\n');
        }
        out.append("       ");
        for(int x = x0; x <= x1; x++) out.append(x % 5 == 0 ? String.format("%-2d", x % 100) : "  ");
        out.append("\n  ").append(tools.Ascii.legend(used)).append("  (s2 = a wall of 1 point of sand, s4 = 2 points...)");
        return out.toString();
    }

    /** A machine's main output: the first thing it makes. */
    static int main(Catalog cat, Machine m){
        for(int k = 0; k < cat.size(); k++) if(m.recipe.out[k] > 0f && cat.get(k).kind != Res.Kind.unit) return k;
        return -1;
    }

    static String firsts(Catalog cat, Timeline t){
        StringBuilder b = new StringBuilder();
        for(int k = 0; k < cat.size(); k++) if(cat.get(k).kind == Res.Kind.unit && !Float.isInfinite(t.first[k])) b.append(cat.get(k).name).append(' ').append(EconLab.time(t.first[k])).append(' ');
        return b.toString().trim();
    }

    static String holds(CoreBlock.CoreBuild core){
        StringBuilder b = new StringBuilder();
        for(Item item : Vars.content.items()) if(core.items.get(item) > 0) b.append(item.name).append('=').append(core.items.get(item)).append(' ');
        return b.toString().trim();
    }
}
