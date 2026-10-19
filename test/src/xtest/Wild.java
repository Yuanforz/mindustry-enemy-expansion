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

    public static void main(String[] args){
        Sim.boot(args[0], args[1]);
        Brain brain = Sim.brain;
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
            Sim.perSecond = raid;
            Sim.watch(minutes, enemy);
            Sim.perSecond = null;
            if(raid != null) System.out.println("  RAID " + raid.report());
            System.out.println(String.format("  FIRST UNIT at minute %.1f", Sim.firstUnitAt));

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
            //a look at part of the map, when asked for: -Dxp.draw=x,y,w,h
            if(System.getProperty("xp.draw") != null){
                String[] box = System.getProperty("xp.draw").split(",");
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
