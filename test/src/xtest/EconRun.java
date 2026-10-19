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
        Field field = Sites.scan(cat, me, 110);
        Planner planner = new Planner(cat, field);
        Recipe making = null;
        int unit = cat.id(unitName);
        for(Recipe r : cat.recipes) if(r.unitOut == unit) making = r;
        List<Machine> plant = planner.balance(unit, machines / making.unitSeconds);
        Timeline model = planner.order(plant, unit);
        System.out.println("######## " + new Fi(args[1]).nameWithoutExtension() + ": " + unitName + " from " + machines + "; the model has it done by " + EconLab.time(model.t)
            + ", first " + unitName + " at " + EconLab.time(model.first[unit]) + "; " + model.order.size() + " machines");

        //and the game
        Works works = new Works(cat, field, me, allowed);
        works.prepare(model.order);
        expansion.econ.Hand hand = new expansion.econ.Hand(works, model.order);
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
        System.out.println(picture(works, me));
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
        for(int[] c : new int[][]{{127, 145}, {101, 109}}){
            Block b = Blocks.turbineCondenser;
            Tile t = Vars.world.tile(c[0], c[1]);
            System.out.println(String.format("  check %d,%d: valid %b, canPlaceOn %b, seen %b, darkness %.1f, in area %b, enemy core near %b, floor %s, deep %b",
                c[0], c[1], Build.validPlace(b, me, c[0], c[1], 0), b.canPlaceOn(t, me, 0), Vars.fogControl.isDiscovered(me, c[0], c[1]), (float)Vars.world.getDarkness(c[0], c[1]), Vars.world.isInMapArea(c[0], c[1]),
                Vars.state.teams.anyEnemyCoresWithinBuildRadius(me, t.worldx(), t.worldy()), t.floor().name, t.floor().isDeep()));
        }
        System.exit(0);
    }

    /** The ground round everything the plan put down, as text: blocks by their codes, ore walls by their item, vents '::'. */
    static String picture(Works works, Team me){
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        for(Part p : works.at.values()){
            if(Math.hypot(p.x - works.field.coreX, p.y - works.field.coreY) > 45) continue;
            x0 = Math.min(x0, p.x - 3); y0 = Math.min(y0, p.y - 3); x1 = Math.max(x1, p.x + 3); y1 = Math.max(y1, p.y + 3);
        }
        x0 = Math.min(x0, works.field.coreX - 4); x1 = Math.max(x1, works.field.coreX + 4);
        y0 = Math.min(y0, works.field.coreY - 4); y1 = Math.max(y1, works.field.coreY + 4);
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
