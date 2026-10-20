package xtest;

import arc.*;
import arc.files.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import expansion.*;
import mindustry.*;
import mindustry.core.GameState.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.ConstructBlock.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.storage.*;
import mindustry.world.blocks.units.*;

/**
 * Runs real campaign maps without a window, with the mod, under the rules the campaign gives the enemy,
 * and watches for anything it should never do.
 *
 * Usage: Sim data-dir blueprint-dir minutes open|closed [-tech early] map.msav...
 */
public class Sim{
    static int problems;
    static ObjectIntMap<String> begun = new ObjectIntMap<>(), finished = new ObjectIntMap<>(), turnedOut = new ObjectIntMap<>();
    /** The minute its first unit came out of a yard of its own, or -1. */
    static float firstUnitAt = -1f;
    static IntSet original = new IntSet();
    static int nearestToPlayer = Integer.MAX_VALUE;
    /** Of everything the enemy added: the furthest any of it is along the way from its own cores to the player's, as a part of that way. */
    static float furthest;
    static Brain brain;
    /** Whether the game's own base-building AI is switched on for the enemy, as the Nightmare mod's "smart AI" does. Without a window it has no ready-made pieces to build and falls over, so it can only be on while the mod keeps it from running. */
    static boolean smartAi = true;

    static void problem(String what){
        problems++;
        System.out.println("  PROBLEM: " + what);
    }

    /** How far a tile is over open ground from the nearest enemy core and from the nearest core of the player; -1 where there is no way. Worked out here on its own, not the way the mod does it. */
    static int[] sides(Tile from){
        int w = Vars.world.width(), h = Vars.world.height();
        int[] dist = new int[w * h];
        java.util.Arrays.fill(dist, -1);
        IntQueue queue = new IntQueue();
        dist[from.x + from.y * w] = 0;
        queue.addLast(from.x + from.y * w);
        int[] out = {-1, -1};
        while(!queue.isEmpty() && (out[0] < 0 || out[1] < 0)){
            int c = queue.removeFirst();
            Tile t = Vars.world.tile(c % w, c / w);
            if(t.build instanceof CoreBlock.CoreBuild){
                int side = t.team() == Vars.state.rules.waveTeam ? 0 : t.team() == Vars.state.rules.defaultTeam ? 1 : -1;
                if(side >= 0 && out[side] < 0) out[side] = dist[c];
            }
            for(int[] d : new int[][]{{1, 0}, {0, 1}, {-1, 0}, {0, -1}}){
                int x = c % w + d[0], y = c / w + d[1];
                if(x < 0 || y < 0 || x >= w || y >= h || dist[x + y * w] >= 0) continue;
                Tile o = Vars.world.tile(x, y);
                if(!Vars.world.isInMapArea(x, y) || o.floor().solid || o.floor().isDeep() || (o.build == null && o.block().solid)) continue;
                dist[x + y * w] = dist[c] + 1;
                queue.addLast(x + y * w);
            }
        }
        return out;
    }

    static void boot(String dataDir, String blueprintDir){
        //the mod is made before the game's content, as the game does it
        ExpansionMod[] mod = {null};
        Boot.boot(dataDir, () -> mod[0] = new ExpansionMod());
        mod[0].init();
        brain = ExpansionMod.brain;
        //the runs here are about what the enemy builds, not about when it starts
        Core.settings.put("expansion-grace", 0);
        //-Dxtest.strongest=true: the whole run with the enemy set to its strongest
        if(Boolean.getBoolean("xtest.strongest")) Core.settings.put("expansion-strongest", true);
        for(Fi file : new Fi(blueprintDir).list()){
            if(file.extEquals("txt")) brain.blueprints.addAll(Blueprint.parse(file.readString()));
        }
        System.out.println("blueprints: " + brain.blueprints.map(b -> b.name));

        Events.on(WorldLoadEvent.class, e -> {
            //what the enemy had to begin with: putting those back is not expansion
            original.clear();
            for(Tile tile : Vars.world.tiles) if(tile.build != null && tile.team() == Vars.state.rules.waveTeam) original.add(tile.pos());
        });
        Boot.modTime = () -> brain.lastThink;
        Events.on(BlockBuildBeginEvent.class, e -> {
            if(e.team != Vars.state.rules.waveTeam || e.breaking || !(e.tile.build instanceof ConstructBuild site)) return;
            begun.increment(site.current.name);
            if(original.contains(e.tile.pos())) return;
            long checkStart = System.nanoTime();

            int[] far = sides(e.tile);
            boolean playerSide = far[1] >= 0 && (far[0] < 0 || far[1] + 1 < far[0]);
            if(far[0] < 0 && far[1] < 0){
                var mine = Geometry.findClosest(e.tile.worldx(), e.tile.worldy(), Vars.state.rules.waveTeam.cores());
                var theirs = Geometry.findClosest(e.tile.worldx(), e.tile.worldy(), Vars.state.rules.defaultTeam.cores());
                playerSide = mine == null || (theirs != null && theirs.dst2(e.tile) < mine.dst2(e.tile));
            }
            if(far[1] >= 0) nearestToPlayer = Math.min(nearestToPlayer, far[1]);
            if(far[0] >= 0 && far[1] >= 0) furthest = Math.max(furthest, far[0] / (float)(far[0] + far[1]));
            if(playerSide){
                //beyond its home ground it may only build right by what it already has: a step at a time, never out of nowhere
                beyond++;
                int step = Options.hold.get() + 8;
                boolean[] backed = {false};
                Vars.indexer.eachBlock(e.team, e.tile.worldx(), e.tile.worldy(), step * Vars.tilesize, other -> other.tile != e.tile && !(other instanceof ConstructBuild), other -> backed[0] = true);
                if(!backed[0]){
                    unbacked++;
                    //for the first source of something it needs, a mine or a factory may stand anywhere; what it fights with never may
                    boolean military = site.current instanceof Turret || site.current instanceof UnitFactory || site.current instanceof Reconstructor || site.current instanceof mindustry.world.blocks.defense.Wall;
                    if(military || !Options.creep.on()) problem(site.current.name + " @" + e.tile.x + "," + e.tile.y + " went up on the player's side with nothing of the enemy's within " + step + " tiles: " + far[0] + " over land from an enemy core, " + far[1] + " from the player's");
                }
            }
            //and never under the player's guns. what the player has merely built is only in its way
            if(Vars.indexer.findEnemyTile(e.team, e.tile.worldx(), e.tile.worldy(), 60f * Vars.tilesize, other -> other.block instanceof BaseTurret gun && !other.block.privileged && other.within(e.tile.worldx(), e.tile.worldy(), gun.range - Vars.tilesize)) != null) problem(site.current.name + " @" + e.tile.x + "," + e.tile.y + " went up inside the range of one of the player's turrets");

            IntSet reserved = Scripts.reserved();
            boolean[] bad = {false};
            e.tile.getLinkedTilesAs(site.current, t -> { if(!Vars.world.isInMapArea(t.x, t.y) || reserved.contains(t.pos())) bad[0] = true; });
            if(bad[0]) problem("built outside the open map or on a scripted tile: " + site.current.name + " @" + e.tile.x + "," + e.tile.y);
            Boot.checking += System.nanoTime() - checkStart;
        });
        Events.on(UnitCreateEvent.class, e -> {
            if(e.spawner != null && e.spawner.team == Vars.state.rules.waveTeam && !original.contains(e.spawner.tile.pos())){
                turnedOut.increment(e.unit.type.name);
                if(firstUnitAt < 0f) firstUnitAt = (float)(Vars.state.tick / 3600f);
            }
        });
        Events.on(BlockBuildEndEvent.class, e -> {
            if(e.team == Vars.state.rules.waveTeam && !e.breaking) finished.increment(e.tile.block().name);
        });
    }

    /** The rules the campaign sets for the enemy when a map is loaded. The mod has to undo the part that hands out resources. */
    static void campaignRules(boolean open){
        Rules r = Vars.state.rules;
        if(open && r.limitMapArea){
            //the way the map's own script opens the map up, so that what was closed starts running
            int x = r.limitX, y = r.limitY, w = r.limitWidth, h = r.limitHeight;
            r.limitMapArea = false;
            Vars.world.checkMapArea(x, y, w, h);
        }
        Team enemy = r.waveTeam;
        enemy.rules().infiniteResources = true;
        enemy.rules().fillItems = true;
        enemy.rules().buildSpeedMultiplier *= 0.4f;
        r.coreIncinerates = true;
        //the game's own base-building AI, as the Nightmare mod's "smart AI" switches it on: it keeps the core full too
        enemy.rules().buildAi = smartAi;
        //and one round of the game topping the enemy's core up, as happens in the real game before the mod first gets a say
        if(!enemy.cores().isEmpty()){
            var core = enemy.cores().first();
            for(Item item : Vars.content.items()) core.items.set(item, core.getMaximumAccepted(item));
        }
        //keep the player's side standing, so the run goes on
        r.defaultTeam.rules().blockHealthMultiplier = 1e6f;
    }

    /** How long the enemy has been in the posture it is in, in seconds, as its status says. */
    static float postureFor(){
        String status = brain.status();
        int at = status.indexOf(" for ", Math.max(status.indexOf("posture: "), 0)), end = status.indexOf("s,", at);
        return at < 0 || end < 0 ? 0f : arc.util.Strings.parseFloat(status.substring(at + 5, end), 0f);
    }

    static String stock(){
        StringBuilder sb = new StringBuilder();
        var core = Stock.core();
        for(Item item : Vars.content.items()){
            int have = brain.stock.have(item);
            if(have < 0) problem("negative stock of " + item.name);
            if(core != null && have > core.storageCapacity) problem("more " + item.name + " than fits: " + have);
            if(have != 0 || brain.stock.income(item) != 0) sb.append(item.name).append('=').append(have).append("(+").append(brain.stock.income(item)).append(") ");
        }
        return sb.toString().trim();
    }

    static int count(Class<?> type){
        int n = 0;
        for(Building b : Vars.state.rules.waveTeam.data().buildings) if(type.isInstance(b.block) && !original.contains(b.tile.pos())) n++;
        return n;
    }

    public static void main(String[] args) throws Exception{
        boot(args[0], args[1]);
        int minutes = Integer.parseInt(args[2]);
        boolean open = args[3].equals("open");
        int first = 4;
        brain.unlocked = content -> true;
        if(args[first].equals("-tech")){
            tech(args[first + 1]);
            first += 2;
        }

        for(int a = first; a < args.length; a++){
            Vars.logic.reset();
            begun.clear(); finished.clear(); turnedOut.clear(); firstUnitAt = -1f; Boot.slowest = Boot.slowestGame = Boot.modShare = 0; nearestToPlayer = Integer.MAX_VALUE;
            SaveIO.load(new Fi(args[a]));
            Vars.state.set(State.playing);
            //a map file can carry the clock of whoever saved it; the game sets it to zero when a map is started
            Vars.state.tick = 0;
            Time.setDeltaProvider(() -> 1f);
            //the game only knows which teams are on the map after an update, and opening the map needs that
            Boot.frames(1);
            campaignRules(open);
            Team enemy = Vars.state.rules.waveTeam;
            //the mod's first say after the game has topped the core up
            Boot.frames(1);
            System.out.println("######## " + new Fi(args[a]).name() + " open=" + open + " enemy cores=" + enemy.cores().size + " start stock: " + stock());
            watch(minutes, enemy);
        }
        System.out.println(problems == 0 ? "RESULT: no problems" : "RESULT: " + problems + " PROBLEMS");
        System.exit(problems == 0 ? 0 : 1);
    }

    /**
     * Holds the enemy to what a player has researched at some point of the campaign. Nothing in the mod
     * knows of these lists: they only stand in for what the game would say is unlocked.
     * @param level "early" for the first sector's worth, "half" for about the middle of the tree, anything else for all of it
     */
    static void tech(String level){
        Seq<String> early = Seq.with("duct", "duct-router", "duct-bridge", "plasma-bore", "turbine-condenser", "beam-node", "cliff-crusher", "silicon-arc-furnace", "tank-fabricator", "stell", "breach", "beryllium-wall", "beryllium-wall-large", "core-bastion",
            "beryllium", "graphite", "silicon", "sand");
        Seq<String> half = new Seq<>(early);
        half.addAll("impact-drill", "vent-condenser", "reinforced-conduit", "reinforced-bridge-conduit", "reinforced-liquid-junction", "reinforced-liquid-router", "reinforced-liquid-container",
            "overflow-duct", "underflow-duct", "armored-duct", "ship-fabricator", "elude", "mech-fabricator", "merui", "diffuse", "tungsten-wall", "tungsten-wall-large",
            "electrolyzer", "tank-refabricator", "locus", "tungsten", "water", "hydrogen", "ozone");
        if(level.equals("early")) brain.unlocked = content -> early.contains(content.name);
        else if(level.equals("half")) brain.unlocked = content -> half.contains(content.name);
        else brain.unlocked = content -> true;
    }

    /** Whether a building is a factory that makes items. What makes a liquid stops when it is full, which says nothing about how it is supplied. */
    static boolean itemMaker(Building b){
        return b instanceof mindustry.world.blocks.production.GenericCrafter.GenericCrafterBuild && ((mindustry.world.blocks.production.GenericCrafter)b.block).outputItems != null;
    }

    /** The minute at which the enemy first began each kind of thing, in a run. */
    static ObjectIntMap<String> firsts = new ObjectIntMap<>();
    static int armed, turrets, running, yards, idleMakers;
    /** How much of what the enemy added went up beyond its home ground, and how much of that with nothing of its own near. */
    static int beyond, unbacked;

    /** Runs the world that is loaded for so many minutes of the game, watching, and says how it went. */
    /** Run once a second of game time while a game is watched, when set: what the player's side does in a raid test. */
    static Runnable perSecond;

    static void watch(int minutes, Team enemy){
        {
            firsts.clear();
            beyond = unbacked = 0;
            long start = System.nanoTime();
            for(int m = 1; m <= minutes; m++){
                if(perSecond == null){
                    Boot.frames(3600);
                }else{
                    for(int second = 0; second < 60; second++){
                        Boot.frames(60);
                        perSecond.run();
                    }
                }
                if(enemy.rules().fillItems || enemy.rules().infiniteResources) problem("the game's free resources for the enemy are on");
                for(Net.Kind kind : new Net.Kind[]{Net.Kind.mine, Net.Kind.maker, Net.Kind.yard, Net.Kind.post}){
                    if(brain.count(kind) > 0 && !firsts.containsKey(kind.name())) firsts.put(kind.name(), m);
                }
                //what it was thinking, every minute, when asked for: -Dxp.notes=key,...
                if(System.getProperty("xp.notes") != null){
                    System.out.println(String.format("  min %2d: %s | mines=%d makers=%d yards=%d posts=%d | posture %s | units %d", m, stock(), brain.count(Net.Kind.mine), brain.count(Net.Kind.maker), brain.count(Net.Kind.yard), brain.count(Net.Kind.post), brain.director.posture, enemy.data().units.size));
                    for(String key : System.getProperty("xp.notes").split(",")) System.out.println("           note " + key + ": " + brain.note(key));
                }
                if(m % 5 == 0 || m == minutes){
                    System.out.println(String.format("  min %2d: %s | mines=%d makers=%d yards=%d posts=%d | to build %d | aim=%s | new turrets=%d units from new yards=%s",
                        m, stock(), brain.count(Net.Kind.mine), brain.count(Net.Kind.maker), brain.count(Net.Kind.yard), brain.count(Net.Kind.post),
                        enemy.data().plans.size, brain.aim() == null ? "-" : brain.aim().name, count(Turret.class), turnedOut));
                }
            }
            System.out.println("  " + brain.status().replace("\n", "\n  "));
            //with several cores: how what it built is shared out among them, by which core each thing is nearest
            if(enemy.cores().size > 1){
                ObjectIntMap<String> share = new ObjectIntMap<>();
                for(Net.Thing thing : brain.net.things){
                    if(thing.kind == Net.Kind.branch || thing.kind == Net.Kind.pipe || thing.kind == Net.Kind.quarry) continue;
                    var core = Geometry.findClosest(thing.x * Vars.tilesize, thing.y * Vars.tilesize, enemy.cores());
                    share.increment("core " + core.tileX() + "," + core.tileY());
                }
                System.out.println("  " + enemy.cores().size + " cores; mines, factories, yards and turrets by the core nearest each: " + share);
            }
            System.out.println(String.format("  slowest working-out: %.0f ms (%s)", brain.slowestPlan, brain.slowestWhat));
            System.out.println(String.format("  slowest turn of the mod's own, everything counted: %.0f ms", brain.slowestThink));
            brain.slowestPlan = brain.slowestThink = 0f;
            System.out.println("  time: " + Prof.report());
            System.out.println("  begun: " + begun + "\n  finished: " + finished);
            //what is waiting to be built, and whether the game would allow it now
            ObjectIntMap<String> waiting = new ObjectIntMap<>();
            for(var plan : enemy.data().plans){
                Tile tile = Vars.world.tile(plan.x, plan.y);
                if(tile == null || (tile.block() == plan.block && tile.team() == enemy)) continue;
                boolean ok = Build.validPlace(plan.block, enemy, plan.x, plan.y, plan.rotation);
                waiting.increment(plan.block.name + (original.contains(tile.pos()) ? " (rebuild)" : "") + (ok ? "" : " NOT ALLOWED" + (tile.build != null ? " over " + tile.block().name : "")));
                if(waiting.get(plan.block.name, 0) == 1 && ok) System.out.println("    e.g. " + plan.block.name + " at " + plan.x + "," + plan.y + ": " + brain.whyWaiting(plan));
                if(!ok && waiting.get(plan.block.name + " NOT ALLOWED" + (tile.build != null ? " over " + tile.block().name : "")) == 1) System.out.println("    e.g. " + plan.block.name + " at " + plan.x + "," + plan.y);
            }
            if(waiting.size > 0) System.out.println("  waiting: " + waiting);
            armed = turrets = running = yards = 0;
            for(Building b : enemy.data().buildings){
                if(original.contains(b.tile.pos())) continue;
                if(b instanceof Turret.TurretBuild t){
                    turrets++;
                    //item ammunition, or the liquid it fires; and whatever else it has to be given
                    boolean ready = b instanceof ItemTurret.ItemTurretBuild item ? item.totalAmmo > 0 : b.liquids != null && b.liquids.currentAmount() > 1f;
                    for(Rates.Flow need : Rates.liquidNeeds(b.block)) ready &= b.liquids.get(need.liquid) > 0.5f;
                    if(ready) armed++; else System.out.println("    not ready to fire: " + b.block.name + " at " + b.tileX() + "," + b.tileY());
                }
                if(b instanceof UnitFactory.UnitFactoryBuild f && (f.progress <= 0f || f.efficiency < 0.3f) && b.enabled) System.out.println(String.format("    yard not turning anything out: %s at %d,%d: power %.0f%%, holds %s, wants %s", b.block.name, b.tileX(), b.tileY(), b.power == null ? 100f : b.power.status * 100f, f.items, f.currentPlan == -1 ? "nothing set" : java.util.Arrays.toString(((UnitFactory)b.block).plans.get(f.currentPlan).requirements)));
                if(b.block == mindustry.content.Blocks.electrolyzer) System.out.println(String.format("    electrolyzer at %d,%d: power %.0f%%, efficiency %.0f%%, water %.1f hydrogen %.1f ozone %.1f",
                    b.tileX(), b.tileY(), b.power == null ? 100f : b.power.status * 100f, b.efficiency * 100f, b.liquids.get(mindustry.content.Liquids.water), b.liquids.get(mindustry.content.Liquids.hydrogen), b.liquids.get(mindustry.content.Liquids.ozone)));
                if(b instanceof Reconstructor.ReconstructorBuild r) System.out.println(String.format("    refabricator %s at %d,%d: power %.0f%%, hydrogen %.1f, holds %s, %s, progress %.0f%%, in front of %s",
                    b.block.name, b.tileX(), b.tileY(), b.power == null ? 100f : b.power.status * 100f, b.liquids == null ? 0f : b.liquids.get(mindustry.content.Liquids.hydrogen), b.items,
                    r.payload == null ? "no unit in it" : "holds a " + r.payload.unit.type.name, r.fraction() * 100f, r.back() == null ? "nothing" : r.back().block.name));
                if(b instanceof UnitFactory.UnitFactoryBuild f){ yards++; if(f.progress > 0f || f.items.total() > 0) running++; else System.out.println("    not supplied: " + b.block.name + " at " + b.tileX() + "," + b.tileY() + (b.enabled ? "" : " (stopped: the enemy has all of that unit it may have)")); }
            }
            //what it built to make things has to be making them: watched for ten seconds more, as one that is
            //short of something works in fits and starts
            ObjectFloatMap<Building> worked = new ObjectFloatMap<>();
            for(int look = 0; look < 60; look++){
                Boot.frames(10);
                for(Building b : enemy.data().buildings){
                    if(itemMaker(b) && !original.contains(b.tile.pos())) worked.increment(b, 0f, b.efficiency / 60f);
                }
            }
            int busy = 0, makers = 0;
            for(Building b : enemy.data().buildings){
                if(original.contains(b.tile.pos()) || !itemMaker(b)) continue;
                makers++;
                if(worked.get(b, 0f) > 0.3f) busy++;
                else System.out.println(String.format("    (at work %.0f%% of the time)", worked.get(b, 0f) * 100f) + String.format("    standing still: %s at %d,%d: power %.0f%%, holds %s%s", b.block.name, b.tileX(), b.tileY(), b.power == null ? 100f : b.power.status * 100f, b.items == null ? "-" : b.items.toString(), b.liquids == null ? "" : ", liquid " + b.liquids.currentAmount()));
            }
            //for the ones standing still: where each line into them comes from, and what is on it
            int shown = 0;
            for(Building b : enemy.data().buildings){
                if(original.contains(b.tile.pos()) || !itemMaker(b) || worked.get(b, 0f) > 0.3f || b.items == null || shown++ >= 4) continue;
                for(Building in : b.proximity){
                    if(in.team != enemy || !(in.block instanceof mindustry.world.blocks.distribution.Duct) || in.front() != b) continue;
                    StringBuilder way = new StringBuilder("      line into " + b.block.name + " " + b.tileX() + "," + b.tileY() + " by " + in.tileX() + "," + in.tileY() + ":");
                    Building at = in;
                    IntSet walked = new IntSet();
                    int length = 0, loaded = 0;
                    ObjectIntMap<String> carrying = new ObjectIntMap<>();
                    String end = "nothing";
                    while(at != null && walked.add(at.id) && length < 600){
                        length++;
                        if(at.items != null && at.items.any()){ loaded++; carrying.increment(at.items.first().name); }
                        Building back = null;
                        for(Building p : at.proximity){
                            if(p.team != enemy || p == b) continue;
                            boolean pushes = p.block instanceof mindustry.world.blocks.distribution.Duct || p.block instanceof mindustry.world.blocks.distribution.DuctBridge ? p.front() == at
                                : p.block instanceof mindustry.world.blocks.distribution.DuctRouter ? p.back() != at : false;
                            if(p.block instanceof mindustry.world.blocks.distribution.DuctBridge bridgeBlock && at.block instanceof mindustry.world.blocks.distribution.DuctBridge && p.rotation == at.rotation) pushes = true;
                            if(pushes && !walked.contains(p.id)){ back = p; break; }
                        }
                        //a bridge is fed by the bridge that links to it, some tiles off
                        if(back == null && at instanceof mindustry.world.blocks.distribution.DirectionBridge.DirectionBridgeBuild bridge){
                            for(Building from : bridge.occupied){
                                if(from != null && from.team == enemy && !walked.contains(from.id)){ back = from; break; }
                            }
                        }
                        if(back == null){
                            for(Building p : at.proximity){
                                if(p.team == enemy && p != b && Ground.makesItems(p.block) && !(p.block instanceof mindustry.world.blocks.distribution.DuctRouter)){ end = p.block.name + " at " + p.tileX() + "," + p.tileY(); break; }
                            }
                            if(at.block instanceof mindustry.world.blocks.distribution.DuctRouter) end = "a router at " + at.tileX() + "," + at.tileY() + " with nothing behind it";
                            if(at.block instanceof mindustry.world.blocks.distribution.DuctBridge) end = "a bridge at " + at.tileX() + "," + at.tileY() + " with nothing coming to it";
                        }
                        at = back;
                    }
                    System.out.println(way.append(" ").append(length).append(" long, ").append(loaded).append(" of them holding ").append(carrying).append(", starts at ").append(end));
                }
            }
            idleMakers = makers - busy;
            if(makers > 0) System.out.println("  new factories at work: " + busy + "/" + makers);
            //a bridge hands on to the first bridge in front of it, whichever way that one runs: none of the new ones may be caught by a stranger
            for(Building b : enemy.data().buildings){
                if(original.contains(b.tile.pos()) || !(b instanceof mindustry.world.blocks.distribution.DirectionBridge.DirectionBridgeBuild bridge)) continue;
                var link = bridge.findLink();
                if(link != null && link.rotation != b.rotation) problem("the bridge at " + b.tileX() + "," + b.tileY() + " hands on to one that runs another way, at " + link.tileX() + "," + link.tileY());
            }
            System.out.println("  new turrets with ammunition: " + armed + "/" + turrets + "; new yards supplied: " + running + "/" + yards
                + "; nearest thing added to the player's core: " + (nearestToPlayer == Integer.MAX_VALUE ? "-" : nearestToPlayer + " tiles over land")
                + "; beyond its home ground " + beyond + " blocks, " + unbacked + " of them a first source standing alone"
                + String.format("   (%.1fs, slowest update %.1f ms of which the mod %.1f; slowest with the mod's share taken off %.1f ms)", (System.nanoTime() - start) / 1e9, Boot.slowest / 1e6, Boot.modShare / 1e6, Boot.slowestGame / 1e6));
        }
    }
}
