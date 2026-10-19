package xtest;

import arc.*;
import arc.files.*;
import arc.util.*;
import expansion.Blueprint.Part;
import expansion.econ.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.*;
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

    /** The lines and power a sector has, and every one before it. */
    static Set<String> logisticsOf(String sector){
        Set<String> out = new HashSet<>();
        for(String[] row : logistics){
            for(int i = 1; i < row.length; i++) out.add(row[i]);
            if(row[0].equals(sector)) break;
        }
        return out;
    }

    public static void main(String[] args){
        Boot.boot(args[0]);
        String sector = args[2], unitName = args[3];
        int machines = Integer.parseInt(args[4]);
        float minutes = Float.parseFloat(args[5]);
        List<String> blocks = new ArrayList<>();
        for(String[] row : EconLab.tech){
            for(int i = 1; i < row.length; i++) blocks.add(row[i]);
            if(row[0].equals(sector)) break;
        }
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
        rules.infiniteResources = false;
        //the enemy is kept still: this is about the economy alone
        enemy.rules().rtsAi = false;
        rules.waves = false;
        Time.setDeltaProvider(() -> 1f);
        Vars.logic.play();
        Vars.state.tick = 0;
        Boot.frames(2);

        //the plan
        Catalog cat = Recipes.read(blocks, (CoreBlock)core.block, rules.buildSpeed(me));
        cat.logistics(allowed);
        Field field = Sites.scan(cat, me, 110);
        Planner planner = new Planner(cat, field);
        Recipe making = null;
        int unit = cat.id(unitName);
        for(Recipe r : cat.recipes) if(r.unitOut == unit) making = r;
        List<Machine> plant = planner.balance(unit, machines / making.unitSeconds);
        Timeline model = planner.order(plant, unit);
        System.out.println("######## " + new Fi(args[1]).nameWithoutExtension() + ": " + unitName + " from " + machines + "; the model has it done by " + EconLab.time(model.t)
            + ", first " + unitName + " at " + EconLab.time(model.first[unit]) + "; " + model.order.size() + " machines");

        //laid out on the map; what it all really costs goes back to the planner, which may then order it otherwise
        long l0 = System.nanoTime();
        Layout works = new Layout(cat, field, me, allowed);
        works.prepare(model.order);
        long l1 = System.nanoTime();
        System.out.println(String.format("  laid out in %.1fs: %s", (l1 - l0) / 1e9, works.summary()));
        int modelSpent = 0, realSpent = 0;
        for(Machine m : model.order){
            int[] real = works.realCost(m);
            m.buildSeconds = works.buildSeconds(m);
            for(int k = 0; k < real.length; k++){
                modelSpent += m.cost[k];
                realSpent += real[k];
                m.cost[k] = real[k];
            }
        }
        planner.extras = 0;
        Timeline again = planner.order(new ArrayList<>(model.order), unit);
        System.out.println(String.format("  the plan cost %d in items, the layout %d; ordered again on what it really costs: done by %s, first %s at %s (was %s, %s)",
            modelSpent, realSpent, EconLab.time(again.t), unitName, EconLab.time(again.first[unit]), EconLab.time(model.t), EconLab.time(model.first[unit])));
        model = again;
        works.reorder(model.order);
        for(String p : works.problems) System.out.println("  checker: " + p);
        expansion.econ.Hand hand = new expansion.econ.Hand(works, model.order);
        //what each module means to do, as words and as a picture (with the debug option on the game draws the same)
        Intent intent = works.intent();
        Intent.current = intent;
        hand.intent = intent;
        String mapName = new Fi(args[1]).nameWithoutExtension();
        IntentImage.write(intent, me, field.coreX, field.coreY, new java.io.File("build/intent/" + mapName + "-plan.png"));
        int[] box = cellBox(works);
        if(box != null) IntentImage.write(intent, me, box[0], box[1], box[2], box[3], new java.io.File("build/intent/" + mapName + "-cells.png"));
        Events.on(UnitCreateEvent.class, e -> {
            if(e.unit.team == me && !firstMade.containsKey(e.unit.type.name)) firstMade.put(e.unit.type.name, (float)(Vars.state.tick / 60.0));
        });
        Unit builder = null;
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
            }
            if(hand.next >= model.order.size() && firstMade.containsKey(unitName) && f % 600 == 0) break;
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
        if(hand.watch.repaired > 0) System.out.println("  parts put back: " + hand.watch.repaired);
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
            StringBuilder why = new StringBuilder(String.format("  why %s at %d,%d was refused: canPlaceOn %b, units on it %b;", b.name, x, y, b.canPlaceOn(Vars.world.tile(x, y), me, 0),
                mindustry.entities.Units.anyEntities(x * 8 + b.offset - b.size * 4f, y * 8 + b.offset - b.size * 4f, b.size * 8f, b.size * 8f)));
            Vars.world.tile(x, y).getLinkedTilesAs(b, o -> {
                if(o.build != null || o.solid() || !Vars.fogControl.isDiscovered(me, o.x, o.y) || o.floor().isDeep() || !o.floor().placeableOn)
                    why.append(String.format(" %d,%d %s/%s seen %b;", o.x, o.y, o.floor().name, o.block().name, Vars.fogControl.isDiscovered(me, o.x, o.y)));
            });
            System.out.println(why);
        }
        //one line for the benchmark table
        Float made = firstMade.get(unitName);
        float lastDone = 0f;
        for(float f : hand.finished) if(!Float.isNaN(f)) lastDone = Math.max(lastDone, f);
        int done = 0;
        for(float f : hand.finished) if(!Float.isNaN(f)) done++;
        System.out.println(String.format("BENCH %s %s %s %d | model %s game %s | %s | built %d/%d model %s game %s | nets unlaid %d checker %d | layout %.1fs",
            mapName, sector, unitName, machines, EconLab.time(model.first[unit]), made == null ? "never" : EconLab.time(made),
            made == null ? "-" : String.format("%.0f%%", 100f * model.first[unit] / made), done, model.order.size(), EconLab.time(model.t), done == model.order.size() ? EconLab.time(lastDone) : "-",
            works.failed, works.problems.size(), (l1 - l0) / 1e9) + (done < model.order.size() ? " | stuck: " + hand.doing : ""));
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
