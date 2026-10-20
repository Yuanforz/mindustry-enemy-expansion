package xtest;

import arc.struct.*;
import arc.util.*;
import expansion.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.core.GameState.*;
import mindustry.game.*;
import mindustry.type.*;
import mindustry.world.*;

/**
 * Maps the mod has never seen. Each one is made by the game's own generator for Erekir from a seed,
 * and the enemy gets what a player starting there would have: one core and a starting stock, with no
 * base built for it. Nothing here was used in working the mod out, so how the enemy does on these says
 * how it does on maps in general.
 *
 * Usage: Wild data-dir blueprint-dir minutes stock seed...
 *   stock is what both cores start with: "plain" (300 beryllium, 300 graphite), "poor" (60 beryllium) or "none"
 */
public class Wild{
    /** How it went on each map. */
    static Seq<String> rows = new Seq<>(), better = new Seq<>();

    /** When each kind of block of the enemy's first stood, in seconds: the opening's path, map by map. */
    static final ObjectFloatMap<String> firstBuilt = new ObjectFloatMap<>();

    public static void main(String[] args){
        Sim.boot(args[0], args[1]);
        Brain brain = Sim.brain;
        arc.Events.on(mindustry.game.EventType.UnitCreateEvent.class, e -> {
            if(e.unit.team != Vars.state.rules.waveTeam || e.unit.spawnedByCore) return;
            if(!firstBuilt.containsKey("unit:" + e.unit.type.name)) firstBuilt.put("unit:" + e.unit.type.name, (float)(Vars.state.tick / 60f));
            firstBuilt.increment("count:" + e.unit.type.name, 0f, 1f);
        });
        arc.Events.on(mindustry.game.EventType.BlockBuildEndEvent.class, e -> {
            if(e.breaking || e.tile.build == null || e.team != Vars.state.rules.waveTeam) return;
            String name = e.tile.block().name;
            if(!firstBuilt.containsKey(name)) firstBuilt.put(name, (float)(Vars.state.tick / 60f));
        });
        //-Dxtest.tech=early|half: the enemy held to what a player has researched by then; all of it otherwise
        Sim.tech(System.getProperty("xtest.tech", "full"));
        int minutes = Integer.parseInt(args[2]);
        String loadout = args[3];
        int stalled = 0;

        for(int a = 4; a < args.length; a++){
            int seed = Integer.parseInt(args[a]);
            Team enemy = make(seed, loadout);
            if(enemy == null){
                System.out.println("######## seed " + seed + ": the generator left no place for an enemy core");
                continue;
            }
            Sim.begun.clear(); Sim.finished.clear(); Sim.turnedOut.clear(); Sim.firstUnitAt = -1f;
            firstBuilt.clear();
            Boot.slowest = Boot.slowestGame = Boot.modShare = 0;
            Sim.nearestToPlayer = Integer.MAX_VALUE;
            Boot.frames(1);
            System.out.println("######## seed " + seed + " " + Vars.world.width() + "x" + Vars.world.height() + ", enemy core at " + enemy.core().tileX() + "," + enemy.core().tileY()
                + ", the player's at " + Vars.state.rules.defaultTeam.core().tileX() + "," + Vars.state.rules.defaultTeam.core().tileY() + ", start stock: " + Sim.stock());
            System.out.println("  " + lay(brain, enemy));
            if(minutes == 0){
                if(args.length == 5){
                    //one map only: what the ground round the enemy's core looks like
                    int cx = enemy.core().tileX(), cy = enemy.core().tileY();
                    for(int y = cy + 14; y >= cy - 14; y--){
                        StringBuilder line = new StringBuilder("    ");
                        for(int x = cx - 30; x <= cx + 30; x++){
                            Tile t = Vars.world.tile(x, y);
                            line.append(t == null ? ' ' : t.build != null ? '@' : t.block().isStatic() ? (t.wallDrop() != null ? 'o' : '#') : t.floor().isDeep() ? '~' : t.floor().isLiquid ? '-' : !t.floor().placeableOn ? 'x' : brain.territory.fromHome(x, y) > 5000 ? '?' : '.');
                        }
                        System.out.println(line);
                    }
                }
                continue;
            }
            //-Dxtest.raid=minute: the player's side raids, as a person would: from that minute a tank and a ship are turned out at
            //its core every forty and forty-five seconds, up to fifteen of each standing, and the game's own squad AI sends them
            //at the enemy. its core can be destroyed, and so can the enemy's: whichever falls first ends the run
            Raid raid = System.getProperty("xtest.raid") != null ? new Raid(enemy, Float.parseFloat(System.getProperty("xtest.raid"))) : null;
            //the first yard, second by second: when it stood, when each thing it takes first came into it, when it first had all of
            //them, and when it began on a unit
            ObjectFloatMap<String> yardTimes = new ObjectFloatMap<>();
            Runnable yardWatch = () -> {
                float now = (float)(Vars.state.tick / 60f);
                mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild yard = null;
                for(var build : enemy.data().buildings){
                    if(build instanceof mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild f){
                        yard = f;
                        break;
                    }
                }
                //how the furnaces ran from the second minute to the sixth, and how well their power was met
                if(now >= 120f && now < 360f){
                    for(var build : enemy.data().buildings){
                        if(build.block != Blocks.siliconArcFurnace) continue;
                        yardTimes.increment("furnace-seconds", 0f, 1f);
                        yardTimes.increment("furnace-eff", 0f, build.efficiency);
                        if(build.power != null) yardTimes.increment("furnace-power", 0f, build.power.status);
                        //and what it was short of, if anything
                        if(build.efficiency <= 0.01f){
                            if(build.items.get(Items.sand) < 4) yardTimes.increment("furnace-nosand", 0f, 1f);
                            else if(build.items.get(Items.graphite) < 1) yardTimes.increment("furnace-nographite", 0f, 1f);
                            else if(build.items.get(Items.silicon) >= build.block.itemCapacity) yardTimes.increment("furnace-full", 0f, 1f);
                            else yardTimes.increment("furnace-other", 0f, 1f);
                        }
                    }
                }
                //every ten seconds of the opening, when asked for (-Dxtest.early=seconds): what it holds and what it has standing
                if(Integer.getInteger("xtest.early", 0) > now && (int)now % 10 == 0){
                    ObjectIntMap<String> standing = new ObjectIntMap<>();
                    for(var build : enemy.data().buildings) if(!(build instanceof mindustry.world.blocks.ConstructBlock.ConstructBuild)) standing.increment(build.block.name);
                    var builder = enemy.data().units.find(u -> u.canBuild());
                    System.out.println(String.format("  t=%3ds %s | %s | builder %s", (int)now, Sim.stock(), standing, builder == null ? "-" : builder.buildPlan() == null ? "idle" : builder.buildPlan().block.name));
                }
                //the last four minutes: how its furnaces and yards ran, what stopped them, and how its power stood
                if(now >= minutes * 60f - 240f){
                    float made = 0f, wanted = 0f;
                    for(var build : enemy.data().buildings){
                        if(build.block.outputsPower && build.power != null) made += build.getPowerProduction() * 60f;
                        if(build.block.consPower != null && !build.block.outputsPower && build.shouldConsume()) wanted += build.block.consPower.usage * 60f;
                        if(build.block == Blocks.siliconArcFurnace){
                            yardTimes.increment("end-furnace", 0f, 1f);
                            yardTimes.increment("end-furnace-eff", 0f, build.efficiency);
                            if(build.power != null) yardTimes.increment("end-furnace-power", 0f, build.power.status);
                            if(build.efficiency <= 0.01f){
                                if(build.items.get(Items.sand) < 4) yardTimes.increment("end-furnace-sand", 0f, 1f);
                                else if(build.items.get(Items.graphite) < 1) yardTimes.increment("end-furnace-graphite", 0f, 1f);
                                else if(build.items.get(Items.silicon) >= build.block.itemCapacity) yardTimes.increment("end-furnace-full", 0f, 1f);
                            }
                        }
                        if(build instanceof mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild f){
                            //touching a furnace that makes what it takes, or fed by line
                            boolean touching = f.proximity.contains(o -> o.block == Blocks.siliconArcFurnace);
                            yardTimes.increment(touching ? "end-touch" : "end-line", 0f, 1f);
                            yardTimes.increment(touching ? "end-touch-run" : "end-line-run", 0f, f.progress > 0f && f.efficiency > 0.01f ? 1f : 0f);
                            yardTimes.increment("end-yard", 0f, 1f);
                            yardTimes.increment("end-yard-run", 0f, f.progress > 0f && f.efficiency > 0.01f ? 1f : 0f);
                            if(f.currentPlan != -1 && f.efficiency <= 0.01f){
                                var fplan = ((mindustry.world.blocks.units.UnitFactory)f.block).plans.get(f.currentPlan);
                                boolean shortItems = false;
                                for(ItemStack stack : fplan.requirements) if(f.items.get(stack.item) < stack.amount) shortItems = true;
                                if(!f.enabled || !mindustry.entities.Units.canCreate(enemy, fplan.unit) || f.payload != null) yardTimes.increment("end-yard-capped", 0f, 1f);
                                else if(shortItems){
                                    yardTimes.increment("end-yard-items", 0f, 1f);
                                    for(ItemStack stack : fplan.requirements) if(f.items.get(stack.item) < stack.amount) yardTimes.increment("end-yard-short-" + stack.item.name, 0f, 1f);
                                }
                                else yardTimes.increment("end-yard-power", 0f, 1f);
                            }
                        }
                    }
                    for(var build : enemy.data().buildings){
                        if(!(build instanceof mindustry.world.blocks.units.Reconstructor.ReconstructorBuild r)) continue;
                        yardTimes.increment("end-refit", 0f, 1f);
                        if(r.efficiency > 0.01f && r.payload != null){
                            yardTimes.increment("end-refit-run", 0f, 1f);
                            continue;
                        }
                        String why;
                        if(r.payload == null) why = "nounit";
                        else if(r.upgrade(r.payload.unit.type) != null && !mindustry.entities.Units.canCreate(enemy, r.upgrade(r.payload.unit.type))) why = "cap";
                        else{
                            why = "else";
                            for(var consume : r.block.consumers){
                                if(consume instanceof mindustry.world.consumers.ConsumeLiquid liquid && r.liquids.get(liquid.liquid) < liquid.amount * 2f){ why = "liquid"; break; }
                                if(consume instanceof mindustry.world.consumers.ConsumeItems items){
                                    for(ItemStack stack : items.items) if(r.items.get(stack.item) < stack.amount){ why = "item-" + stack.item.name; break; }
                                    if(!why.equals("else")) break;
                                }
                            }
                            if(why.equals("else") && r.power != null && r.power.status < 0.5f) why = "power";
                        }
                        yardTimes.increment("end-refit-" + why, 0f, 1f);
                    }
                    yardTimes.increment("end-seconds", 0f, 1f);
                    yardTimes.increment("end-power-made", 0f, made);
                    yardTimes.increment("end-power-wanted", 0f, wanted);
                }
                if(yard == null) return;
                if(!yardTimes.containsKey("built")) yardTimes.put("built", now);
                var block = (mindustry.world.blocks.units.UnitFactory)yard.block;
                var plan = block.plans.get(Math.max(yard.currentPlan, 0));
                boolean all = true;
                for(ItemStack stack : plan.requirements){
                    if(yard.items.get(stack.item) > 0 && !yardTimes.containsKey(stack.item.name)) yardTimes.put(stack.item.name, now);
                    if(yard.items.get(stack.item) < stack.amount) all = false;
                }
                if(all && !yardTimes.containsKey("all")) yardTimes.put("all", now);
                if(yard.progress > 0f && !yardTimes.containsKey("started")) yardTimes.put("started", now);
                if(yard.power != null && yard.power.status > 0.99f && !yardTimes.containsKey("powered")) yardTimes.put("powered", now);
            };
            //-Dxtest.frames=step,dir: a picture of the whole map every so many seconds
            String[] frames = System.getProperty("xtest.frames") != null ? System.getProperty("xtest.frames").split(",") : null;
            Runnable each = raid != null ? raid : yardWatch;
            Sim.perSecond = frames == null ? each : () -> {
                each.run();
                int second = (int)(Vars.state.tick / 60f);
                if(second % Integer.parseInt(frames[0]) == 0) Frames.write(new arc.files.Fi(frames[1]).child(String.format("s%d-%05d.png", seed, second)), 0, 0, Vars.world.width(), Vars.world.height(), enemy);
            };
            Sim.watch(minutes, enemy);
            StringBuilder yardLine = new StringBuilder("  YARD");
            for(String key : new String[]{"built", "powered", "silicon", "beryllium", "graphite", "all", "started"}){
                float at = yardTimes.get(key, -1f);
                if(at >= 0f || !key.equals("graphite")) yardLine.append(" ").append(key).append("=").append(at < 0f ? "-" : String.format("%d:%02d", (int)at / 60, (int)at % 60));
            }
            System.out.println(yardLine);
            float es = Math.max(yardTimes.get("end-seconds", 0f), 1f), ef = Math.max(yardTimes.get("end-furnace", 0f), 1f), ey = Math.max(yardTimes.get("end-yard", 0f), 1f);
            System.out.println(String.format("  ECON last 4 min: power %.0f made / %.0f wanted | furnaces %.1f, efficiency %.2f, power %.2f, stopped for sand %.2f graphite %.2f room %.2f | yards %.1f, running %.2f, stopped for items %.2f, power %.2f, cap %.2f",
                yardTimes.get("end-power-made", 0f) / es, yardTimes.get("end-power-wanted", 0f) / es, yardTimes.get("end-furnace", 0f) / es, yardTimes.get("end-furnace-eff", 0f) / ef, yardTimes.get("end-furnace-power", 0f) / ef,
                yardTimes.get("end-furnace-sand", 0f) / ef, yardTimes.get("end-furnace-graphite", 0f) / ef, yardTimes.get("end-furnace-full", 0f) / ef,
                yardTimes.get("end-yard", 0f) / es, yardTimes.get("end-yard-run", 0f) / ey, yardTimes.get("end-yard-items", 0f) / ey, yardTimes.get("end-yard-power", 0f) / ey, yardTimes.get("end-yard-capped", 0f) / ey));
            for(var build : enemy.data().buildings){
                if(!(build instanceof mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild f)) continue;
                var furnace = f.proximity.find(o -> o.block == Blocks.siliconArcFurnace);
                var plan = f.currentPlan == -1 ? null : ((mindustry.world.blocks.units.UnitFactory)f.block).plans.get(f.currentPlan);
                var front = f.front();
                System.out.println(String.format("  YARDSTATE %s at %d,%d rot %d eff %.2f progress %.2f power %.2f items %s payload %s cap %s front %s | %s", f.block.name, f.tileX(), f.tileY(), f.rotation, f.efficiency, f.progress, f.power == null ? -1f : f.power.status, f.items,
                    f.payload == null ? "-" : f.payload.unit.type.name, plan == null ? "-" : mindustry.entities.Units.canCreate(enemy, plan.unit) ? "ok" : "AT CAP", front == null ? (Vars.world.tileWorld(f.x + arc.math.Angles.trnsx(f.rotation * 90f, f.block.size * 4f + 4f), f.y + arc.math.Angles.trnsy(f.rotation * 90f, f.block.size * 4f + 4f)) == null ? "edge" : Vars.world.tileWorld(f.x + arc.math.Angles.trnsx(f.rotation * 90f, f.block.size * 4f + 4f), f.y + arc.math.Angles.trnsy(f.rotation * 90f, f.block.size * 4f + 4f)).solid() ? "rock" : "open") : front.block.name,
                    furnace == null ? "by line" : String.format("against the furnace at %d,%d eff %.2f holding %s, %d neighbours", furnace.tileX(), furnace.tileY(), furnace.efficiency, furnace.items, furnace.proximity.size)));
            }
            float er = Math.max(yardTimes.get("end-refit", 0f), 1f);
            System.out.println(String.format("  ECONREFIT refits %.1f running %.2f | no unit %.2f, hydrogen %.2f, tungsten %.2f, silicon %.2f, power %.2f, cap %.2f, else %.2f | core tungsten %d",
                yardTimes.get("end-refit", 0f) / es, yardTimes.get("end-refit-run", 0f) / er, yardTimes.get("end-refit-nounit", 0f) / er, yardTimes.get("end-refit-liquid", 0f) / er,
                yardTimes.get("end-refit-item-tungsten", 0f) / er, yardTimes.get("end-refit-item-silicon", 0f) / er, yardTimes.get("end-refit-power", 0f) / er, yardTimes.get("end-refit-cap", 0f) / er, yardTimes.get("end-refit-else", 0f) / er,
                enemy.core() == null ? 0 : enemy.core().items.get(Items.tungsten)));
            System.out.println(String.format("  ECONTOUCH yards against a furnace %.1f running %.2f | fed by line %.1f running %.2f",
                yardTimes.get("end-touch", 0f) / es, yardTimes.get("end-touch-run", 0f) / Math.max(yardTimes.get("end-touch", 0f), 1f), yardTimes.get("end-line", 0f) / es, yardTimes.get("end-line-run", 0f) / Math.max(yardTimes.get("end-line", 0f), 1f)));
            System.out.println(String.format("  ECONSHORT yards short of silicon %.2f beryllium %.2f graphite %.2f | core silicon %d beryllium %d graphite %d",
                yardTimes.get("end-yard-short-silicon", 0f) / ey, yardTimes.get("end-yard-short-beryllium", 0f) / ey, yardTimes.get("end-yard-short-graphite", 0f) / ey,
                enemy.core() == null ? 0 : enemy.core().items.get(Items.silicon), enemy.core() == null ? 0 : enemy.core().items.get(Items.beryllium), enemy.core() == null ? 0 : enemy.core().items.get(Items.graphite)));
            float fs = Math.max(yardTimes.get("furnace-seconds", 0f), 1f);
            System.out.println(String.format("  FURNACES 2-6 min: %.0f furnace-seconds, efficiency %.2f, power %.2f; stopped for want of sand %.2f, graphite %.2f, room %.2f, else %.2f",
                yardTimes.get("furnace-seconds", 0f), yardTimes.get("furnace-eff", 0f) / fs, yardTimes.get("furnace-power", 0f) / fs, yardTimes.get("furnace-nosand", 0f) / fs,
                yardTimes.get("furnace-nographite", 0f) / fs, yardTimes.get("furnace-full", 0f) / fs, yardTimes.get("furnace-other", 0f) / fs));
            Sim.perSecond = null;
            if(raid != null) System.out.println("  RAID " + raid.report());
            System.out.println(String.format("  FIRST UNIT at minute %.1f", Sim.firstUnitAt));
            //the opening: when the first of each thing on the way to a unit stood
            StringBuilder opening = new StringBuilder("  OPENING");
            for(String name : new String[]{"turbine-condenser", "plasma-bore", "cliff-crusher", "silicon-arc-furnace", "tank-fabricator", "ship-fabricator", "mech-fabricator", "breach"}){
                float at = firstBuilt.get(name, -1f);
                opening.append(" ").append(name).append("=").append(at < 0f ? "-" : String.format("%d:%02d", (int)at / 60, (int)at % 60));
            }
            opening.append(" unit=").append(Sim.firstUnitAt < 0f ? "-" : String.format("%d:%02d", (int)(Sim.firstUnitAt * 60f) / 60, (int)(Sim.firstUnitAt * 60f) % 60));
            System.out.println(opening);
            StringBuilder units = new StringBuilder("  UNITS");
            for(var entry : firstBuilt) if(entry.key.startsWith("unit:")) units.append(" ").append(entry.key.substring(5)).append(" first ").append(String.format("%d:%02d", (int)entry.value / 60, (int)entry.value % 60)).append(" made ").append((int)firstBuilt.get("count:" + entry.key.substring(5), 0f)).append(";");
            System.out.println(units);

            for(mindustry.gen.Unit unit : enemy.data().units){
                if(unit.controller() instanceof ExpansionAI ai) System.out.println("  builder at " + unit.tileX() + "," + unit.tileY() + ": " + (ai.order != null ? "building " + ai.order.block.name : ai.haul != null ? "carrying " + ai.haul.item.name : ai.dig != null ? "mining " + ai.dig.item.name + " at " + ai.dig.wall.x + "," + ai.dig.wall.y + (unit.mineTile == null ? " (not at it)" : "") : "nothing to do") + ", holds " + unit.stack.amount + " " + (unit.hasItem() ? unit.item().name : "") + ", stock cap " + brain.stock.cap());
                else System.out.println("  unit " + unit.type.name + " run by " + unit.controller().getClass().getSimpleName());
            }
            //where each line was led off for something, and what is standing there now: -Dxp.taps=item
            if(System.getProperty("xp.taps") != null){
                Item want = Vars.content.item(System.getProperty("xp.taps"));
                for(Net.Thing thing : brain.net.things){
                    if(thing.item != want) continue;
                    var at = Vars.world.build(thing.x, thing.y);
                    StringBuilder line = new StringBuilder("  " + thing.kind + " at " + thing.x + "," + thing.y + " rate " + thing.rate + ": " + (at == null ? "nothing standing" : at.block.name + " facing " + at.rotation + " holding " + (at.items == null ? "-" : at.items.toString())));
                    if(at != null && at.back() != null) line.append("; behind it ").append(at.back().block.name).append(" holding ").append(at.back().items == null ? "-" : at.back().items.toString());
                    if(thing.kind == Net.Kind.branch){
                        int start = brain.net.spur(thing.x, thing.y);
                        if(start != -1){
                            int length = brain.net.trace(arc.math.geom.Point2.x(start), arc.math.geom.Point2.y(start)).size;
                            var end = Vars.world.build(brain.net.endX, brain.net.endY);
                            line.append("; its line is ").append(length).append(" long and ends at ").append(end == null ? "nothing" : end.block.name + " " + end.tileX() + "," + end.tileY());
                        }else line.append("; no line found leading off it");
                    }
                    System.out.println(line);
                }
            }
            //a look at part of the map, when asked for: -Dxtest.box=x,y,w,h (or -Dxp.draw, which the economy planner reads as well)
            if(System.getProperty("xtest.box") != null || (System.getProperty("xp.draw") != null && System.getProperty("xp.draw").contains(","))){
                String[] box = (System.getProperty("xtest.box") != null ? System.getProperty("xtest.box") : System.getProperty("xp.draw")).split(",");
                brain.syncGround();
                System.out.println(Picture.region(Integer.parseInt(box[0]), Integer.parseInt(box[1]), Integer.parseInt(box[2]), Integer.parseInt(box[3]), null));
            }
            int mines = brain.count(Net.Kind.mine), makers = brain.count(Net.Kind.maker), yardCount = brain.count(Net.Kind.yard), posts = brain.count(Net.Kind.post);
            //an enemy that never gets as far as a furnace and something to fight with has not got going
            boolean going = mines > 0 && makers > 0 && (yardCount > 0 || posts > 0);
            if(!going) stalled++;
            //what it made better: things with a liquid that makes them faster led to them, blocks that turn units into the next kind, and how full its cores stand
            int boosted = brain.assets.count(asset -> asset.boosted != 0), rearmed = brain.assets.count(asset -> asset.armed >= 0);
            int rebuilders = enemy.data().buildings.count(b -> b.block instanceof mindustry.world.blocks.units.Reconstructor);
            float fill = 1f;
            for(Item item : new Item[]{Items.beryllium, Items.graphite, Items.silicon}) fill = Math.min(fill, brain.stock.have(item) / (float)Math.max(brain.stock.cap(), 1));
            better.add(String.format("%6d | sped up with a liquid %2d  on better ammunition %d  unit blocks of the next kind %d | cores at least %3.0f%% full, %s for %ds, %d rounds idle",
                seed, boosted, rearmed, rebuilders, fill * 100f, brain.director.posture, (int)(Sim.postureFor()), brain.director.idle));
            rows.add(String.format("%6d %4dx%-4d | first mine %3s  furnace %3s  yard %3s  turret %3s | at the end: mines %2d  factories %2d  yards %2d (%d supplied)  turrets %2d (%d ready) | units %s%s",
                seed, Vars.world.width(), Vars.world.height(), first("mine"), first("maker"), first("yard"), first("post"),
                mines, makers, yardCount, Sim.running, posts, Sim.armed, Sim.turnedOut, going ? "" : "   <-- DID NOT GET GOING"));
        }

        System.out.println("\n==== " + loadout + " start, " + minutes + " minutes each ====");
        System.out.println("  seed  size      | minute it first laid out each thing                    | ");
        for(String row : rows) System.out.println(row);
        for(String row : better) System.out.println(row);
        System.out.println(Sim.problems == 0 ? "RESULT: no rule broken" : "RESULT: " + Sim.problems + " PROBLEMS");
        System.out.println("RESULT: got going on " + (rows.size - stalled) + " of " + rows.size + " maps");
        System.exit(Sim.problems == 0 ? 0 : 1);
    }

    /** What the enemy's side of the map has to offer, and how far from its core over land: so that a map with nothing to work with can be told from one the enemy failed on. */
    static String lay(Brain brain, Team enemy){
        brain.territory.update(enemy.cores(), Vars.state.rules.defaultTeam.cores());
        brain.survey.scan();
        StringBuilder out = new StringBuilder("its side has:");
        for(Item item : new Item[]{Items.beryllium, Items.graphite, Items.tungsten, Items.thorium}){
            IntSeq faces = brain.survey.ore(item);
            int ours = 0, nearest = 99999;
            for(int i = 0; i < faces.size; i++){
                int x = brain.survey.x(faces.get(i)), y = brain.survey.y(faces.get(i));
                if(!brain.territory.ours(x, y)) continue;
                ours++;
                nearest = Math.min(nearest, brain.territory.fromHome(x, y));
            }
            out.append(String.format(" %s wall faces %d (nearest %s, %d on the whole map);", item.name, ours, ours == 0 ? "-" : nearest + "", faces.size));
        }
        IntSeq sand = brain.survey.sand();
        int sandOurs = 0, sandNear = 99999;
        for(int i = 0; i < sand.size; i++){
            int x = brain.survey.x(sand.get(i)), y = brain.survey.y(sand.get(i));
            if(!brain.territory.ours(x, y)) continue;
            sandOurs++;
            sandNear = Math.min(sandNear, brain.territory.fromHome(x, y));
        }
        out.append(" sand faces ").append(sandOurs).append(" (nearest ").append(sandOurs == 0 ? "-" : sandNear + "").append(");");
        IntSeq vents = brain.terrain.vents(Blocks.turbineCondenser, enemy);
        int ventsOurs = 0, ventNear = 99999;
        for(int i = 0; i < vents.size; i++){
            int x = arc.math.geom.Point2.x(vents.get(i)), y = arc.math.geom.Point2.y(vents.get(i));
            if(!brain.territory.ours(x, y)) continue;
            ventsOurs++;
            ventNear = Math.min(ventNear, brain.territory.fromHome(x, y));
        }
        out.append(" vents ").append(ventsOurs).append(" (nearest ").append(ventsOurs == 0 ? "-" : ventNear + "").append(", ").append(vents.size).append(" on the whole map)");
        for(Item item : new Item[]{Items.beryllium, Items.tungsten, Items.thorium}){
            IntSeq floor = brain.survey.floor(item);
            int ours = 0;
            for(int i = 0; i < floor.size; i++) if(brain.territory.ours(arc.math.geom.Point2.x(floor.get(i)), arc.math.geom.Point2.y(floor.get(i)))) ours++;
            if(floor.size > 0) out.append("; ").append(item.name).append(" in the floor ").append(ours);
        }
        return out.toString();
    }

    /** The nearest tile to a place that has open, buildable ground for eight tiles every way. */
    static @Nullable Tile firm(Tile from){
        Tile best = null;
        float bestFar = Float.MAX_VALUE;
        for(Tile tile : Vars.world.tiles){
            float far = tile.dst2(from);
            if(far >= bestFar || far > 60f * 60f * 64f) continue;
            boolean good = true;
            for(int dx = -8; dx <= 8 && good; dx++){
                for(int dy = -8; dy <= 8 && good; dy++){
                    Tile other = Vars.world.tile(tile.x + dx, tile.y + dy);
                    good = other != null && other.build == null && Survey.ground(other);
                }
            }
            if(good){
                best = tile;
                bestFar = far;
            }
        }
        return best;
    }

    static String first(String kind){
        return Sim.firsts.containsKey(kind) ? Sim.firsts.get(kind) + "" : "-";
    }

    /**
     * Makes a map with the game's generator and sets it up as a custom attack game: both sides one
     * core, the same starting stock, and no AI of the game's laid on for the enemy.
     * @return the enemy's team, or null if the map could not be set up
     */
    static @Nullable Team make(int seed, String loadout){
        Vars.logic.reset();
        Rules rules = new Rules();
        Planets.erekir.applyRules(rules, true);
        Gamemode.attack.apply(rules);
        //if asked for, its units gather and attack in squads, as on the campaign's attack maps
        if(Boolean.getBoolean("xtest.rts")){
            rules.teams.get(rules.waveTeam).rtsAi = true;
            rules.teams.get(rules.waveTeam).rtsMaxSquad = 15;
        }
        rules.loadout = switch(loadout){
            case "plain" -> ItemStack.list(Items.beryllium, 300, Items.graphite, 300);
            case "poor" -> ItemStack.list(Items.beryllium, 60);
            default -> new Seq<>();
        };
        Vars.state.rules = rules;

        //which part of the planet the terrain is taken from, and how big the map is, vary with the seed
        Seq<Sector> sectors = Planets.erekir.sectors.select(sector -> sector.preset == null);
        Sector sector = sectors.get(Math.floorMod(seed * 7919, sectors.size));
        int size = 150 + Math.floorMod(seed * 31, 5) * 50;

        WorldParams params = new WorldParams();
        params.seedOffset = seed;
        //the generator puts the player's core down by way of the campaign, which there is none of here
        rules.sector = sector;
        try{
            Vars.world.loadGenerator(size, size, tiles -> Planets.erekir.generator.generate(tiles, sector, params));
        }catch(Throwable error){
            System.out.println("  (the generator failed: " + error + ")");
            return null;
        }finally{
            rules.sector = null;
        }
        Vars.state.rules = rules;
        //the generator switches on a rule of its own, which no ordinary map has: nothing may be built within some tiles of the other side's buildings
        rules.placeRangeCheck = false;

        //where the generator has the enemy come from is where its core goes
        Tile spawn = null;
        for(Tile tile : Vars.world.tiles){
            if(tile.overlay() == Blocks.spawn) spawn = tile;
        }
        if(spawn == null) return null;
        spawn.setOverlay(Blocks.air);
        Team enemy = rules.waveTeam;
        //nobody who made a map would put a core in a lake: the nearest place to there with firm ground all round it
        Tile site = firm(spawn);
        if(site == null) return null;
        site.setBlock(Blocks.coreBastion, enemy);
        if(rules.defaultTeam.cores().isEmpty() && rules.defaultTeam.data().cores.isEmpty()){
            //the game only knows a team's cores after an update
        }

        Vars.state.set(State.playing);
        Vars.state.tick = 0;
        Time.setDeltaProvider(() -> 1f);
        Boot.frames(1);
        if(enemy.cores().isEmpty() || rules.defaultTeam.cores().isEmpty()) return null;

        //the same start for both, as the game gives it in a custom game
        for(Team team : new Team[]{enemy, rules.defaultTeam}){
            var core = team.core();
            core.items.clear();
            for(ItemStack stack : rules.loadout) core.items.add(stack.item, Math.min(stack.amount, core.storageCapacity));
        }
        //keep the player's side standing, so the run goes on
        rules.defaultTeam.rules().blockHealthMultiplier = 1e6f;
        return enemy;
    }
}
