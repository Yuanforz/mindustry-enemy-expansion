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

    public static void main(String[] args){
        ExpansionMod[] mod = {null};
        Boot.boot(args[0], () -> mod[0] = new ExpansionMod());
        mod[0].init();
        Brain brain = ExpansionMod.brain;
        for(Fi file : new Fi("blueprints").list()) if(file.extEquals("txt")) brain.blueprints.addAll(Blueprint.parse(file.readString()));
        Core.settings.put("expansion-grace", 0);
        if(!Boolean.getBoolean("match.plain") && !Boolean.getBoolean("match.nobrain")) Core.settings.put("expansion-strongest", true);
        Core.settings.put("expansion-time-limit", Integer.getInteger("xp.timelimit", 100));
        Boot.modTime = () -> brain.lastThink;
        //(for looking into our side alone: the Brain switched off)
        if(Boolean.getBoolean("match.nobrain")) Core.settings.put("expansion-enabled", false);
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
        Time.setDeltaProvider(() -> 1f);
        Vars.logic.play();
        Vars.state.tick = 0;
        Boot.frames(1);
        //the whole map open, the way the map's own script opens it up (as the other tests run it, "open")
        if(rules.limitMapArea){
            System.out.println("  the map's area was limited to " + rules.limitX + "," + rules.limitY + " " + rules.limitWidth + "x" + rules.limitHeight + ": opened");
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
        int stage = -1;
        expansion.econ.Hand hand = null;
        Unit builder = null;
        int frames = (int)(minutes * 3600);
        String winner = "-";
        float planSeconds = 0f;
        for(int f = 0; f < frames; f++){
            //the next stage, planned from what stands, once the last is all up
            if((hand == null || hand.done()) && stage + 1 < stages.length && us.core() != null){
                stage++;
                long p0 = System.nanoTime();
                Plan plan = null;
                //each stage may name ways to go, the first that can be done is taken: "locus:1|merui:2"
                for(String way : stages[stage].split("\\|")){
                    String[] st = way.split(":");
                    Plan p = new Plan(System.out);
                    p.label = "stage " + (stage + 1) + " at " + Plan.time(f / 60f) + ": ";
                    try{
                        plan = p.make(us, blocks, allowed, library, st[0], Float.parseFloat(st[1]), null);
                    }catch(Throwable t){
                        System.out.println("  stage " + (stage + 1) + " (" + way + ") could not be planned: " + t);
                        t.printStackTrace(System.out);
                        plan = null;
                    }
                    if(plan != null) break;
                    System.out.println("  stage " + (stage + 1) + ": " + way + " can't be done here");
                }
                planSeconds += (System.nanoTime() - p0) / 1e9f;
                if(plan != null && Boolean.getBoolean("match.checkplace")) for(Machine m : plan.model.order){
                    var part = plan.works.at.get(m);
                    if(part != null && !part.block.canPlaceOn(Vars.world.tile(part.x, part.y), us, part.rotation)) System.out.println("    at planning, can't go down: " + m.recipe.name + " at " + part.x + "," + part.y + " r" + part.rotation);
                }
                if(plan != null && plan.model != null){
                    hand = new expansion.econ.Hand(plan.works, plan.model.order);
                    Intent.current = plan.works.intent();
                    hand.intent = Intent.current;
                    hand.rallyUnits = false;
                    if(builder != null && !builder.dead()) builder.controller(hand);
                }
            }
            CoreBlock.CoreBuild home = us.core();
            if(home != null && hand != null && (builder == null || builder.dead())){
                builder = ((CoreBlock)home.block).unitType.spawn(us, home.x, home.y);
                builder.controller(hand);
            }
            Boot.frames(1);
            squads.update();
            if(f % 3600 == 0 && f > 0) System.out.println(report(f / 60f, hand, stage));
            if(f == 1 && System.getProperty("match.around") != null){
                String[] a = System.getProperty("match.around").split(",");
                int ax = Integer.parseInt(a[0]), ay = Integer.parseInt(a[1]);
                for(int y = ay + 6; y >= ay - 6; y--){
                    StringBuilder row = new StringBuilder("    ");
                    for(int x = ax - 6; x <= ax + 6; x++){
                        Tile o = Vars.world.tile(x, y);
                        row.append(o.solid() ? (o.wallDrop() != null ? o.wallDrop().name.charAt(0) : '#') : o.drop() != null ? Character.toUpperCase(o.drop().name.charAt(0)) : '.');
                    }
                    System.out.println(row);
                }
            }
            if(f == Integer.getInteger("match.probe", -1) && hand != null){
                for(int i = 0; i < Math.min(hand.next, hand.order.size()); i++){
                    Machine m = hand.order.get(i);
                    var part = hand.works.at.get(m);
                    if(part == null) continue;
                    Tile tile = Vars.world.tile(part.x, part.y);
                    if(i == 0) for(String tr : hand.trouble) System.out.println("    trouble: " + tr);
                    System.out.println(String.format("    probe #%d %s at %d,%d: there %s (%s), team %s, build %s; valid %b, enemy near %s, dark %.0f", i + 1, m.recipe.name, part.x, part.y, tile.block().name, tile.build == null ? "-" : tile.build.tileX() + "," + tile.build.tileY(), tile.team(), tile.build,
                        Build.validPlace(part.block, us, part.x, part.y, part.rotation), Build.getEnemyOverlap(part.block, us, part.x, part.y), Vars.world.getDarkness(part.x, part.y)));
                    int off = -(part.block.size - 1) / 2;
                    StringBuilder fp = new StringBuilder("      footprint:");
                    for(int dx = 0; dx < part.block.size; dx++) for(int dy = 0; dy < part.block.size; dy++){
                        Tile c = Vars.world.tile(part.x + off + dx, part.y + off + dy);
                        fp.append(String.format(" [%d,%d %s/%s/%s %s deep %b place %b]", c.x, c.y, c.floor().name, c.overlay().name, c.block().name, c.team(), c.floor().isDeep(), c.floor().placeableOn));
                    }
                    fp.append(" canPlaceOn " + part.block.canPlaceOn(tile, us, part.rotation) + " rot " + part.rotation + " limit " + part.block.isOverPlacementLimit(us) + " envOK " + part.block.environmentBuildable() + " supportsEnv " + part.block.supportsEnv(Vars.state.rules.env));
                    if(part.block instanceof mindustry.world.blocks.production.BeamDrill bd){
                        for(int i2 = 0; i2 < bd.size; i2++){
                            bd.nearbySide(tile.x, tile.y, part.rotation, i2, arc.util.Tmp.p1);
                            fp.append("\n        ray " + i2 + ":");
                            for(int j = 0; j < bd.range; j++){
                                Tile o = Vars.world.tile(arc.util.Tmp.p1.x + arc.math.geom.Geometry.d4x(part.rotation) * j, arc.util.Tmp.p1.y + arc.math.geom.Geometry.d4y(part.rotation) * j);
                                fp.append(o == null ? " null" : String.format(" %d,%d:%s/%s/%s%s", o.x, o.y, o.floor().name, o.overlay().name, o.block().name, o.solid() ? "(solid," + o.wallDrop() + ")" : ""));
                            }
                        }
                    }
                    System.out.println(fp);
                }
            }
            if(f % (3600 * 3) == 0 && f > 0 && hand != null && Boolean.getBoolean("match.watch")) System.out.print(hand.watch.report(hand.works.cat));
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
        System.out.println(String.format("RESULT %s %s winner=%s at %s; units made us %d them %d, lost us %d them %d; blocks lost us %d them %d; planning took %.0fs",
            map.nameWithoutExtension(), mode, winner, Plan.time(t), count("us "), count("them "), lostUnits.get("us"), lostUnits.get("them"), blocksLostUs, blocksLostThem, planSeconds));
        System.exit(0);
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
