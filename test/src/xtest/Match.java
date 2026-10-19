package xtest;

import arc.*;
import arc.files.*;
import arc.struct.*;
import arc.util.*;
import expansion.*;
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
 * A whole game, ours against the mod's enemy (the {@link Brain} of the main line): our side is the economy executor
 * (plans laid out and built by the core unit, one stage after another), its units led by the game's squad AI; the
 * other side is the Brain, as it plays the enemy in the campaign.
 *
 * Two ways to start:
 *   sector: the map as the campaign lands on it, the Brain with the base the map gives it, we from our landing core
 *   even:   everything on the map taken off but one core a side, the same block and the same items for both
 *
 * A game ends when one side has no core left, or at the time limit. Each minute it says how both sides stand.
 *
 * Usage: Match data-dir map.msav minutes sector|even
 *   -Dmatch.stages=stell:3,locus:2   what our side builds, stage after stage (unit or item: how many of its makers, or
 *                                    for an item how much a second)
 *   -Dmatch.plain=true               the Brain at its default settings instead of its strongest
 */
public class Match{
    static Team us, them;
    static final ObjectIntMap<String> made = new ObjectIntMap<>(), lostUnits = new ObjectIntMap<>();
    static int blocksLostUs, blocksLostThem;
    /** Seconds our turrets stood, and of those, with no ammunition. */
    static int turretSeconds, drySeconds;
    /** What the last defence planned could feed turrets with. */
    static final Set<String> feedable = new HashSet<>();
    /** What our core held a minute ago, by item name. */
    static final ObjectIntMap<String> lastMinute = new ObjectIntMap<>();

    public static void main(String[] args){
        ExpansionMod[] mod = {null};
        Boot.boot(args[0], () -> mod[0] = new ExpansionMod());
        mod[0].init();
        Brain brain = ExpansionMod.brain;
        for(Fi file : new Fi("blueprints").list()) if(file.extEquals("txt")) brain.blueprints.addAll(Blueprint.parse(file.readString()));
        Core.settings.put("expansion-grace", 0);
        if(!Boolean.getBoolean("match.plain")) Core.settings.put("expansion-strongest", true);
        Core.settings.put("expansion-time-limit", Integer.getInteger("xp.timelimit", 100));
        Boot.modTime = () -> brain.lastThink;
        //nothing is researched in a fresh data dir: the Brain may build all it knows, as in the other tests
        brain.unlocked = content -> true;

        Fi map = new Fi(args[1]);
        float minutes = Float.parseFloat(args[2]);
        String mode = args.length > 3 ? args[3] : "sector";

        Vars.logic.reset();
        SaveIO.load(map);
        Rules rules = Vars.state.rules;
        Planets.erekir.applyRules(rules, false);
        us = rules.defaultTeam;
        them = rules.waveTeam;
        rules.fog = false;
        rules.waves = false;
        rules.coreIncinerates = true;
        rules.infiniteResources = false;
        rules.attackMode = true;
        if(mode.equals("even")) even();
        else{
            //the rules the campaign lays on the enemy of a sector (the mod keeps its own count of what it has)
            Planet planet = Planets.erekir;
            if(planet.enemyInfiniteItems){
                them.rules().infiniteResources = true;
                them.rules().fillItems = true;
            }
            them.rules().buildSpeedMultiplier *= planet.enemyBuildSpeedMultiplier;
            them.rules().unitFactoryActivationDelay = Math.max(them.rules().unitFactoryActivationDelay, planet.enemyFactoryActivationDelay);
            if(!them.cores().isEmpty()){
                var c = them.cores().first();
                for(Item item : Vars.content.items()) c.items.set(item, c.getMaximumAccepted(item));
            }
        }
        CoreBlock.CoreBuild core = us.core();
        if(rules.loadout != null && rules.loadout.size > 0 && mode.equals("sector")){
            core.items.clear();
            for(ItemStack stack : rules.loadout) core.items.set(stack.item, stack.amount);
        }
        //the squad AI, which the game runs for its AI sides only: run here for ours by hand
        us.rules().rtsAi = true;
        mindustry.ai.RtsAI squads = new mindustry.ai.RtsAI(us.data());
        //and our army layer over it (-Dmatch.us=rts for the squad AI alone, or army:parts); the Brain is played as the main
        //line has it, without the layer (-Dmatch.brainarmy=true to give it the layer too)
        String ours = System.getProperty("match.us", "army:defaults,push");
        expansion.army.Army army = ours.startsWith("army") ? new expansion.army.Army(us, expansion.army.Army.Flags.parse(ours)) : null;
        if(!Boolean.getBoolean("match.brainarmy")) System.setProperty("xp.noarmy", "true");
        System.out.println("  our units: " + (army == null ? "the squad AI alone" : "the squad AI with the army layer: " + army.flags.toString().trim()));
        Time.setDeltaProvider(() -> 1f);
        Vars.logic.play();
        Vars.state.tick = 0;
        Boot.frames(1);
        //the whole map open, the way the map's own script opens it up (as the other tests run it, "open")
        if(rules.limitMapArea){
            int x = rules.limitX, y = rules.limitY, w = rules.limitWidth, h = rules.limitHeight;
            rules.limitMapArea = false;
            Vars.world.checkMapArea(x, y, w, h);
        }
        Boot.frames(1);

        Events.on(UnitCreateEvent.class, e -> {
            if(e.unit.team == us) made.increment("us " + e.unit.type.name);
            if(e.unit.team == them) made.increment("them " + e.unit.type.name);
        });
        Events.on(UnitDestroyEvent.class, e -> lostUnits.increment(e.unit.team == us ? "us" : e.unit.team == them ? "them" : "other"));
        Events.on(BlockDestroyEvent.class, e -> {
            if(e.tile.team() == us && Boolean.getBoolean("match.losses")) System.out.println(String.format("    lost %s at %d,%d at %s", e.tile.block().name, e.tile.x, e.tile.y, Plan.time((float)(Vars.state.tick / 60.0))));
            if(e.tile.team() == us) blocksLostUs++;
            else if(e.tile.team() == them) blocksLostThem++;
        });

        System.out.println(String.format("MATCH %s (%s): we have %d core(s), %d blocks; the Brain %d core(s), %d blocks", map.nameWithoutExtension(), mode, us.cores().size, us.data().buildings.size, them.cores().size, them.data().buildings.size));

        //our stages
        List<String> blocks = new ArrayList<>(Plan.erekir());
        Collections.sort(blocks);
        Set<String> allowed = new HashSet<>(blocks);
        List<Design> library = Plan.library(System.getProperty("xp.modules", "blueprints/own,blueprints/modules"));
        String[] stages = System.getProperty("match.stages", "stell:3,locus:2").split(",");
        //a stage not all up after this long is left as it is, and the next begun
        float stageLimit = Float.parseFloat(System.getProperty("match.stagemin", "6")) * 3600f;
        String sector = expansion.defense.Tech.known(map.nameWithoutExtension()) ? map.nameWithoutExtension() : "atlas";
        expansion.defense.GameData.shapes();
        int stage = -1;
        float stageFrom = 0f;
        expansion.econ.Hand hand = null;
        //the stages before: what of them goes missing is put back too, where its tile is empty
        List<expansion.econ.Hand> before = new ArrayList<>();
        Unit builder = null;
        int frames = (int)(minutes * 3600);
        String winner = "-";
        float planSeconds = 0f;
        for(int f = 0; f < frames; f++){
            //the next stage, planned from what stands, once the last is all up (or has had its time)
            boolean over = hand != null && (hand.done() || f - stageFrom > stageLimit);
            if((hand == null || over) && stage + 1 < stages.length && us.core() != null){
                if(hand != null && !hand.done()) System.out.println("  stage " + (stage + 1) + " left at " + hand.next + " of " + hand.order.size() + " after its time: " + hand.doing);
                stage++;
                stageFrom = f;
                long p0 = System.nanoTime();
                Plan plan = null;
                String[] st = null;
                //each stage may name ways to go, the first that can be done is taken: "locus:1|merui:2"
                for(String way : stages[stage].split("\\|")){
                    st = way.split(":");
                    Plan p = new Plan(System.out);
                    p.gate = !Boolean.getBoolean("match.allblocks");
                    p.label = "stage " + (stage + 1) + " at " + Plan.time(f / 60f) + ": ";
                    try{
                        if(st[0].equals("defend")){
                            expansion.defense.Planner.Plan dp = defence(sector, f / 60f, p.label);
                            plan = p.defend(us, blocks, allowed, dp, feedable);
                        }else{
                            plan = p.make(us, blocks, allowed, library, st[0], Float.parseFloat(st[1]), null);
                        }
                    }catch(Throwable t){
                        System.out.println("  stage " + (stage + 1) + " (" + way + ") could not be planned: " + t);
                        t.printStackTrace(System.out);
                        plan = null;
                    }
                    if(plan != null) break;
                    System.out.println("  stage " + (stage + 1) + ": " + way + " can't be done here");
                }
                planSeconds += (System.nanoTime() - p0) / 1e9f;
                if(plan != null && plan.model != null){
                    if(hand != null) before.add(hand);
                    hand = new expansion.econ.Hand(plan.works, plan.model.order);
                    hand.urgent = st[0].equals("defend");
                    //our units are the squad AI's to lead (a unit told where to wait is left alone by it)
                    hand.rallyUnits = false;
                    Intent.current = plan.works.intent();
                    hand.intent = Intent.current;
                    if(builder != null && !builder.dead()) builder.controller(hand);
                }
            }
            CoreBlock.CoreBuild home = us.core();
            if(home != null && hand != null && (builder == null || builder.dead())){
                builder = ((CoreBlock)home.block).unitType.spawn(us, home.x, home.y);
                builder.controller(hand);
            }
            //what the stages before lost: back to the hand of now, where nothing else stands on its tile
            if(hand != null && f % 120 == 0) for(expansion.econ.Hand old : before){
                old.watch.update(old.next);
                for(expansion.Blueprint.Part p : old.watch.repairs){
                    Tile tile = Vars.world.tile(p.x, p.y);
                    if(tile != null && tile.build == null && !hand.watch.repairs.contains(p, true)) hand.watch.repairs.add(p);
                }
                old.watch.repairs.clear();
            }
            Boot.frames(1);
            squads.update();
            if(army != null) army.update();
            if(f % 60 == 0) for(Building b : us.data().buildings) if(b instanceof mindustry.world.blocks.defense.turrets.Turret.TurretBuild tb){
                turretSeconds++;
                if(!tb.hasAmmo()) drySeconds++;
            }
            if(f % 3600 == 0 && f > 0) System.out.println(report(f / 60f, hand, stage));
            if(f % (3600 * 3) == 0 && f > 0 && hand != null && Boolean.getBoolean("match.watch")) System.out.print(hand.watch.report(hand.works.cat));
            if(f % 3600 == 0 && us.core() != null) for(Item item : Vars.content.items()) lastMinute.put(item.name, us.core().items.get(item));
            if(f == 600 || (f % 3600 == 0 && Boolean.getBoolean("match.brainlog"))) System.out.println("  the Brain: active " + brain.active() + "; " + brain.status().replace("\n", "; "));
            if(us.cores().isEmpty()){
                winner = "Brain";
                break;
            }
            if(them.cores().isEmpty()){
                winner = "us";
                break;
            }
        }
        float t = (float)(Vars.state.tick / 60.0);
        System.out.println(report(t, hand, stage));
        System.out.println(String.format("RESULT %s %s winner=%s at %s; units made us %d them %d, lost us %d them %d; blocks lost us %d them %d; planning took %.0fs; our turrets dry %d of %d turret-seconds",
            map.nameWithoutExtension(), mode, winner, Plan.time(t), count("us "), count("them "), lostUnits.get("us"), lostUnits.get("them"), blocksLostUs, blocksLostThem, planSeconds, drySeconds, turretSeconds));
        System.exit(0);
    }

    /**
     * What the defence planner would have us build against the Brain: its cores as where the enemy comes from, with the
     * units it fields now and those it can make by this time of the game (from its yards' blueprints: stell, merui and
     * elude, then their refits), our tech of the sector, and what the economy can feed turrets with.
     */
    static expansion.defense.Planner.Plan defence(String sector, float seconds, String label){
        expansion.defense.Planner.Ask ask = new expansion.defense.Planner.Ask();
        CoreBlock.CoreBuild core = us.core();
        ask.terrain = expansion.defense.GameData.terrain(us);
        ask.coreX = core.tileX();
        ask.coreY = core.tileY();
        ask.coreSize = core.block.size;
        ask.blocks.addAll(expansion.defense.Tech.blocks(sector));
        ask.blocks.removeIf(b -> !b.endsWith("-source") && (Vars.content.block(b) == null || Vars.state.rules.isBanned(Vars.content.block(b))));
        //what turrets can be fed with: what the sector's tech gives that our base makes already, or that a drill on the
        //ground digs with nothing put into it (a plasma bore's beryllium and graphite): a defence that waits on a new
        //chain of crafters is not up when it is wanted
        Catalog cat = Recipes.read(new ArrayList<>(Plan.erekir()), (CoreBlock)core.block, Vars.state.rules.buildSpeed(us));
        Field field = Sites.scan(cat, us, Integer.getInteger("xp.radius", 110));
        Chains chains = new Chains(cat, field);
        Set<String> made = new HashSet<>();
        for(Machine m : field.existing) for(int k = 0; k < cat.size(); k++) if(m.out(k) > 1e-4f) made.add(cat.get(k).name);
        for(int k = 0; k < cat.size(); k++){
            Recipe by = chains.by[k];
            if(by == null || by.ground == Recipe.Ground.none || Float.isInfinite(chains.cost[k])) continue;
            boolean bare = true;
            for(float v : by.in) if(v > 0f) bare = false;
            if(bare && by.powerUse <= 0f && cat.get(k).kind == Res.Kind.item) made.add(cat.get(k).name);
        }
        for(String what : expansion.defense.Tech.supply(sector)) if(made.contains(what)) ask.supply.add(what);
        feedable.clear();
        feedable.addAll(ask.supply);
        //and only what can be paid for: what the core holds or what is made
        ask.blocks.removeIf(name -> {
            Block b = Vars.content.block(name);
            if(b == null || name.endsWith("-source")) return false;
            for(ItemStack stack : b.requirements) if(core.items.get(stack.item) < stack.amount && !made.contains(stack.item.name)) return true;
            return false;
        });
        ask.costs = expansion.defense.GameData.costs();
        //no more than the core holds and what comes in over the next minutes (-Dmatch.defbudget: how many minutes; the
        //income is what the core gained over the last minute, or a guess at the start)
        //(and at least about two of the first line's pairs: the hand saves up for what is not there yet, the budget only
        //keeps the plan to what the next few minutes can pay for)
        float budgetMinutes = Float.parseFloat(System.getProperty("match.defbudget", "3"));
        Map<String, Integer> floor = Map.of("beryllium", 900, "graphite", 600, "silicon", 700, "tungsten", 150);
        for(Item item : Vars.content.items()){
            int have = core.items.get(item);
            int income = Math.max(0, have - lastMinute.get(item.name, have));
            int budget = Math.max(floor.getOrDefault(item.name, 0), have + Math.round(income * budgetMinutes));
            if(budget > 0) ask.budget.put(item.name, budget);
        }
        ask.modules = expansion.defense.Battery.parse(new Fi("blueprints/defense/modules.txt").readString());
        ask.capacity = expansion.defense.Capacity.parse(new Fi("blueprints/defense/capacity.txt").readString());
        //what the Brain may send: what it has now, half as many again, and at least what its yards could have made by now
        //(the kinds: what its yards make and what it has; before it has any, all three of the first tier)
        Map<String, Integer> units = new TreeMap<>();
        float minute = seconds / 60f;
        int early = Math.max(4, Math.min(10, 3 + (int)(minute / 2))), later = Math.min(6, 2 + (int)(minute / 4));
        Set<String> kinds = new TreeSet<>();
        for(Building b : them.data().buildings){
            if(b instanceof mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild yard && yard.currentPlan >= 0){
                kinds.add(((mindustry.world.blocks.units.UnitFactory)yard.block).plans.get(yard.currentPlan).unit.name);
            }
            if(b.block instanceof mindustry.world.blocks.units.Reconstructor re) for(var up : re.upgrades) kinds.add(up[1].name);
        }
        for(Unit u : them.data().units) if(!u.spawnedByCore) kinds.add(u.type.name);
        if(kinds.isEmpty()) kinds.addAll(List.of("stell", "merui", "elude"));
        for(String k : kinds){
            var foe = expansion.defense.Foe.get(k);
            if(foe != null) units.put(k, foe.tier <= 1 ? early : later);
        }
        ObjectIntMap<String> now = new ObjectIntMap<>();
        for(Unit u : them.data().units) if(!u.spawnedByCore) now.increment(u.type.name);
        for(var e : now) if(expansion.defense.Foe.get(e.key) != null) units.merge(e.key, (int)Math.ceil(e.value * 1.5f), Math::max);
        ask.units.putAll(units);
        for(CoreBlock.CoreBuild c : them.cores()){
            expansion.defense.Planner.Source ground = new expansion.defense.Planner.Source(c.tileX(), c.tileY(), "Brain core " + c.tileX() + "," + c.tileY());
            for(var e : units.entrySet()){
                ground.units.put(e.getKey(), e.getValue());
                ground.moves.add(expansion.defense.Foe.get(e.getKey()).move);
            }
            ask.sources.add(ground);
        }
        if(ask.sources.isEmpty()) return null;
        long t0 = System.nanoTime();
        expansion.defense.Planner.Plan plan = new expansion.defense.Planner(ask).plan();
        System.out.println(String.format("######## %sthe defence planner (tech of %s, fed with %s, budget %s) against %s: %d placements in %.0f ms", label, sector, ask.supply, ask.budget, units, plan.placements.size(), (System.nanoTime() - t0) / 1e6));
        System.out.print(plan.describe());
        return plan;
    }

    static int count(String prefix){
        int n = 0;
        for(var e : made) if(e.key.startsWith(prefix)) n += e.value;
        return n;
    }

    /** How the two sides stand. */
    static String report(float t, expansion.econ.Hand hand, int stage){
        StringBuilder b = new StringBuilder();
        b.append(String.format("  %s: ", Plan.time(t)));
        for(Team team : new Team[]{us, them}){
            float hp = 0f, max = 0f;
            for(CoreBlock.CoreBuild c : team.cores()){
                hp += c.health;
                max += c.maxHealth;
            }
            ObjectIntMap<String> units = new ObjectIntMap<>();
            for(Unit u : team.data().units) if(!u.spawnedByCore) units.increment(u.type.name);
            int items = 0;
            if(team.core() != null) for(Item item : Vars.content.items()) items += team.core().items.get(item);
            int turrets = 0;
            for(Building bd : team.data().buildings) if(bd.block instanceof mindustry.world.blocks.defense.turrets.Turret) turrets++;
            b.append(String.format("%s cores %d (%.0f%%) blocks %d turrets %d items %d units %s; ", team == us ? "us" : "Brain", team.cores().size, max > 0 ? hp * 100 / max : 0f, team.data().buildings.size, turrets, items, units));
        }
        if(hand != null) b.append(String.format("our stage %d: %d of %d begun, %s", stage + 1, hand.next, hand.order.size(), hand.doing));
        return b.toString();
    }

    /** Everything taken off but one core a side, the same core and the same items for both. */
    static void even(){
        CoreBlock.CoreBuild ours = us.core();
        CoreBlock.CoreBuild theirs = null;
        for(CoreBlock.CoreBuild c : them.cores()) if(theirs == null || c.dst(ours) > theirs.dst(ours)) theirs = c;
        int ox = ours.tileX(), oy = ours.tileY(), tx = theirs.tileX(), ty = theirs.tileY();
        Block coreBlock = ours.block;
        Seq<Building> all = new Seq<>();
        for(Building b : Groups.build) if(b.team == us || b.team == them) all.add(b);
        for(Building b : all) if(b.tile != null && b.tile.build == b) b.tile.remove();
        Vars.world.tile(ox, oy).setNet(coreBlock, us, 0);
        Vars.world.tile(tx, ty).setNet(coreBlock, them, 0);
        for(Unit u : Groups.unit.copy(new Seq<>())) if(u.team == us || u.team == them) u.remove();
        Seq<ItemStack> loadout = Vars.state.rules.loadout;
        for(Team team : new Team[]{us, them}){
            CoreBlock.CoreBuild c = team.core();
            c.items.clear();
            if(loadout != null) for(ItemStack stack : loadout) c.items.set(stack.item, stack.amount);
        }
    }
}
