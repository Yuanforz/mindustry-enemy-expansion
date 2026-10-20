package xtest;

import arc.files.*;
import arc.func.*;
import arc.struct.*;
import expansion.*;
import mindustry.*;
import mindustry.ctype.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;

/**
 * Two versions of the mod against each other on a map made by the game's generator: this one plays the enemy's side, as
 * in a game against a person, and another build of the mod, loaded apart from it, plays the player's side. Each sees
 * only its own side; the cores of both can be destroyed, and the run ends when one side has none left.
 *
 * Usage: BrainDuel data-dir blueprint-dir other-classes other-blueprints minutes seed...
 *   other-classes is a folder of the other build's compiled classes, other-blueprints the blueprints it was made with
 */
public class BrainDuel{
    public static void main(String[] args) throws Exception{
        //which arrangement of identity hash codes the run plays (xp.hashshuffle=n), as in the campaign test: else every run of a build plays the same one
        for(int i = Integer.getInteger("xp.hashshuffle", 0); i > 0; i--) System.identityHashCode(new Object());
        Sim.boot(args[0], args[1]);
        Sim.tech("full");
        Object other = other(args[2], args[3]);
        Class<?> otherBrain = other.getClass();
        //-Dxtest.swap: this build plays the player's seat and the other the enemy's, to set one map's two seats against each other
        boolean swap = Boolean.getBoolean("xtest.swap");
        //what fights on the enemy's seat is given to the brain there to lead, as on the player's: else the game's own AI sends
        //each unit at the other side's core the moment it is made, and the duel is between that and a brain
        arc.Events.on(mindustry.game.EventType.UnitCreateEvent.class, e -> {
            if(e.unit.team == Vars.state.rules.waveTeam && !e.unit.canBuild() && !(e.unit.controller() instanceof mindustry.ai.types.CommandAI)) e.unit.controller(new mindustry.ai.types.CommandAI());
        });
        //what each side made, and what it lost, over a run: what decides a run that ends with both standing
        ObjectIntMap<Team> made = new ObjectIntMap<>(), lostBlocks = new ObjectIntMap<>(), lostUnits = new ObjectIntMap<>();
        arc.Events.on(mindustry.game.EventType.UnitCreateEvent.class, e -> {
            if(!e.unit.spawnedByCore && !e.unit.canBuild()) made.increment(e.unit.team);
        });
        arc.Events.on(mindustry.game.EventType.BlockDestroyEvent.class, e -> lostBlocks.increment(e.tile.team()));
        arc.Events.on(mindustry.game.EventType.UnitDestroyEvent.class, e -> {
            if(!e.unit.spawnedByCore && !e.unit.canBuild()) lostUnits.increment(e.unit.team);
        });
        int minutes = Integer.parseInt(args[4]);
        int wins = 0, losses = 0, draws = 0;
        for(int a = 5; a < args.length; a++){
            int seed = Integer.parseInt(args[a]);
            Team enemy = Wild.make(seed, "plain");
            if(enemy == null){
                System.out.println("######## seed " + seed + ": no place for an enemy core");
                continue;
            }
            Team player = Vars.state.rules.defaultTeam;
            //no fog, as in the user's games against it: the game lets a side its own AI plays build where it has not looked,
            //and the player's side not, so with fog the seat decides more games than the builds do (-Dxtest.fog: kept on)
            if(!Boolean.getBoolean("xtest.fog")) Vars.state.rules.fog = Vars.state.rules.staticFog = false;
            //both sides' cores can fall, and both play by the same rules: the game's own for the enemy's side are put back to the player's
            for(Team team : new Team[]{enemy, player}){
                Rules.TeamRule rule = team.rules();
                rule.blockHealthMultiplier = rule.blockDamageMultiplier = rule.buildSpeedMultiplier = rule.unitBuildSpeedMultiplier = 1f;
                rule.unitDamageMultiplier = rule.unitHealthMultiplier = rule.unitCostMultiplier = rule.unitCrashDamageMultiplier = rule.unitMineSpeedMultiplier = 1f;
                rule.rtsAi = false;
                rule.buildAi = false;
                rule.fillItems = rule.infiniteResources = rule.cheat = false;
                rule.unitFactoryActivationDelay = 0f;
            }
            //each brain told its seat in so many words, so that both lead their units and both build at the same pace
            Brain.side = swap ? player : enemy;
            otherBrain.getField("side").set(null, swap ? enemy : player);
            Team mine = swap ? player : enemy, theirs = swap ? enemy : player;
            //-Dxtest.rtsself: this build's units led by the game's own squad AI, as they are in a game against a person, and not by its brain
            //(-Dxtest.rtsother: the other build's the same way)
            StringBuilder unled = new StringBuilder();
            for(Team team : new Team[]{mine, theirs}){
                if(!Boolean.getBoolean(team == mine ? "xtest.rtsself" : "xtest.rtsother")) continue;
                team.rules().rtsAi = true;
                team.rules().rtsMaxSquad = 15;
                //(-Dxtest.rtsmin.self=n, -Dxtest.rtsweight.self=w, and .other: how big a squad goes out, and how much stronger than what it goes against)
                String who = team == mine ? "self" : "other";
                team.rules().rtsMinSquad = Integer.getInteger("xtest.rtsmin." + who, team.rules().rtsMinSquad);
                if(System.getProperty("xtest.rtsweight." + who) != null) team.rules().rtsMinWeight = Float.parseFloat(System.getProperty("xtest.rtsweight." + who));
                unled.append(unled.length() > 0 ? "," : "").append(team.name);
            }
            System.setProperty("xp.nomarshal", unled.toString());
            //and each starts again from its seat: what it made of the map when it was loaded was for the seat it had before
            Sim.brain.clear();
            Sim.brain.load();
            otherBrain.getMethod("clear").invoke(other);
            otherBrain.getMethod("load").invoke(other);
            Boot.frames(1);
            made.clear();
            lostBlocks.clear();
            lostUnits.clear();
            System.out.println("######## seed " + seed + " " + Vars.world.width() + "x" + Vars.world.height() + (swap ? " (seats swapped)" : "") + ": this build at " + mine.core().tileX() + "," + mine.core().tileY()
                + ", the other at " + theirs.core().tileX() + "," + theirs.core().tileY());
            System.out.println("  rules: waves " + Vars.state.rules.waves + ", timer " + Vars.state.rules.waveTimer + ", attack " + Vars.state.rules.attackMode + ", fog " + Vars.state.rules.fog
                + " | cores: this " + mine.core().block.name + ", the other " + theirs.core().block.name + " | spawns " + Vars.spawner.getSpawns().size);
            for(Team team : new Team[]{mine, theirs}) System.out.println("  vents by " + (team == mine ? "this" : "the other") + ": " + vents(team.core().tileX(), team.core().tileY()));
            int end = -1;
            String result = "both stand";
            StringBuilder line = new StringBuilder();
            for(int m = 1; m <= minutes && end < 0; m++){
                for(int s = 0; s < 60 && end < 0; s++){
                    Boot.frames(60);
                    if(mine.cores().isEmpty()){
                        end = m;
                        result = "LOST: this build's core fell at minute " + m;
                    }else if(theirs.cores().isEmpty()){
                        end = m;
                        result = "WON: the other build's core fell at minute " + m;
                    }
                }
                line.append(String.format(" %d:%d/%d", m, fighters(mine), fighters(theirs)));
                //what this build was thinking, every minute, when asked for: -Dxp.notes=key,...
                if(System.getProperty("xp.notes") != null){
                    for(String key : System.getProperty("xp.notes").split(",")) System.out.println("  min " + m + " note " + key + ": " + Sim.brain.note(key));
                }
                if(System.getProperty("xtest.builders") != null) for(Unit unit : mine.data().units){
                    if(!unit.canBuild()) continue;
                    var plan = unit.buildPlan();
                    System.out.println("  min " + m + " builder " + unit.type.name + " at " + unit.tileX() + "," + unit.tileY() + " " + unit.controller().getClass().getSimpleName() + ": " + unit.plans.size + " plans, at "
                        + (plan == null ? "none" : plan.block.name + " " + plan.x + "," + plan.y + (plan.breaking ? " (breaking)" : "")) + ", holds " + unit.stack + ", building " + unit.activelyBuilding()
                        + (unit.controller() instanceof expansion.ExpansionAI ai ? ", order " + ai.order + " (" + ai.more.size + " more), haul " + (ai.haul == null ? "-" : ai.haul.item.name + " to " + ai.haul.to.block.name)
                        + ", carry " + (ai.carry == null ? "-" : ai.carry.block.name) + (ai.carryTo == null ? "" : " to " + ai.carryTo.block.name + " " + ai.carryTo.tileX() + "," + ai.carryTo.tileY()) + ", dig " + (ai.dig == null ? "-" : ai.dig.item.name) + ", scout " + ai.scout + ", repair " + (ai.repair == null ? "-" : ai.repair.block.name + " at " + ai.repair.tileX() + "," + ai.repair.tileY() + " health " + ai.repair.health + "/" + ai.repair.maxHealth + " valid " + ai.repair.isValid() + " damaged " + ai.repair.damaged()
                            + " recently " + ai.repair.wasRecentlyDamaged() + ", shooting " + unit.isShooting + ", rotation " + (int)unit.rotation + " toward " + (int)unit.angleTo(ai.repair) + ", far " + (int)(unit.dst(ai.repair) / 8f)) : ""));
                }
                if(m == 1) for(Team team : new Team[]{mine, theirs}) System.out.println("  minute 1, " + (team == mine ? "this" : "the other") + ": " + team.data().units.map(u -> u.type.name + " " + u.controller().getClass().getSimpleName() + " at " + u.tileX() + "," + u.tileY()));
                if(m % 5 == 0) System.out.println(String.format("  min %2d: this %s | other %s", m, side(mine), side(theirs)));
            }
            System.out.println("  units by minute (this/other):" + line);
            System.out.println("  SCORE " + seed + " this made " + made.get(mine) + " lost " + lostUnits.get(mine) + " units " + lostBlocks.get(mine) + " blocks"
                + " | other made " + made.get(theirs) + " lost " + lostUnits.get(theirs) + " units " + lostBlocks.get(theirs) + " blocks");
            System.out.println("  DUEL " + seed + " " + result);
            if(result.startsWith("WON")) wins++; else if(result.startsWith("LOST")) losses++; else draws++;
        }
        System.out.println("RESULT: this build won " + wins + ", lost " + losses + ", neither in time " + draws);
        System.exit(0);
    }

    /** How far the steam vents are from a place: the nearest few, by straight distance. */
    static String vents(int x, int y){
        Seq<int[]> found = new Seq<>();
        for(var tile : Vars.world.tiles){
            if(tile.floor().attributes.get(mindustry.world.meta.Attribute.steam) <= 0f) continue;
            boolean near = false;
            for(int[] other : found) if(Math.abs(other[0] - tile.x) < 5 && Math.abs(other[1] - tile.y) < 5) near = true;
            if(!near) found.add(new int[]{tile.x, tile.y});
        }
        found.sort(v -> arc.math.Mathf.dst(v[0], v[1], x, y));
        StringBuilder out = new StringBuilder();
        for(int i = 0; i < Math.min(5, found.size); i++) out.append(found.get(i)[0]).append(",").append(found.get(i)[1]).append(" (").append((int)arc.math.Mathf.dst(found.get(i)[0], found.get(i)[1], x, y)).append(") ");
        return found.size + " vents; nearest " + out;
    }

    static int fighters(Team team){
        return team.data().units.count(unit -> !unit.spawnedByCore && unit.type.canAttack && !unit.canBuild());
    }

    static String side(Team team){
        ObjectIntMap<String> units = new ObjectIntMap<>();
        for(Unit unit : team.data().units) if(!unit.spawnedByCore) units.increment(unit.type.name);
        var core = team.core();
        return team.data().buildings.size + " blocks, " + team.data().buildings.count(b -> b instanceof mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild) + " yards, "
            + team.data().buildings.count(b -> b instanceof mindustry.world.blocks.defense.turrets.Turret.TurretBuild) + " turrets, units " + units
            + (core == null ? "" : ", core " + core.items.get(mindustry.content.Items.silicon) + " si " + core.items.get(mindustry.content.Items.beryllium) + " be " + core.items.get(mindustry.content.Items.graphite) + " gr "
            + core.items.get(mindustry.content.Items.tungsten) + " w");
    }

    /**
     * The other build's mod, made and started in a class loader of its own that looks at its own classes first, so that its
     * brain and everything else of it are apart from this one's: set to play the player's side, held to the same research,
     * timed the same way, given its blueprints, and told what goes into and out of the cores.
     */
    static Object other(String classes, String blueprints) throws Exception{
        ClassLoader parent = BrainDuel.class.getClassLoader();
        URLClassLoader loader = new URLClassLoader(new URL[]{new File(classes).toURI().toURL()}, parent){
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException{
                if(!name.startsWith("expansion.")) return super.loadClass(name, resolve);
                synchronized(getClassLoadingLock(name)){
                    Class<?> found = findLoadedClass(name);
                    if(found == null) found = findClass(name);
                    if(resolve) resolveClass(found);
                    return found;
                }
            }
        };
        Class<?> modClass = loader.loadClass("expansion.ExpansionMod"), brainClass = loader.loadClass("expansion.Brain");
        Object mod = modClass.getConstructor().newInstance();
        modClass.getMethod("init").invoke(mod);
        Object brain = modClass.getField("brain").get(null);
        brainClass.getField("side").set(null, Vars.state.rules.defaultTeam);
        loader.loadClass("expansion.Clock").getField("virtual").set(null, Clock.virtual);
        Boolf<UnlockableContent> all = content -> true;
        brainClass.getField("unlocked").set(brain, all);
        //its blueprints, parsed by its own reader
        Method parse = loader.loadClass("expansion.Blueprint").getMethod("parse", String.class);
        Seq<Object> list = (Seq<Object>)brainClass.getField("blueprints").get(brain);
        for(Fi file : new Fi(blueprints).list()){
            if(file.extEquals("txt")) list.addAll((Seq<Object>)parse.invoke(null, file.readString()));
        }
        System.out.println("the other build: " + list.size + " blueprints");
        //the cores were made counting for this build: what they count is passed on to the other's stock as well
        Object stock = brainClass.getField("stock").get(brain);
        Class<?> listener = loader.loadClass("expansion.Intake$Listener");
        Method arrived = listener.getMethod("arrived", Team.class, Item.class, int.class, boolean.class), taken = listener.getMethod("taken", Team.class, Item.class, int.class);
        Intake.also.add(new Intake.Listener(){
            @Override
            public void arrived(Team team, Item item, int amount, boolean byLine){
                try{
                    arrived.invoke(stock, team, item, amount, byLine);
                }catch(Exception e){
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void taken(Team team, Item item, int amount){
                try{
                    taken.invoke(stock, team, item, amount);
                }catch(Exception e){
                    throw new RuntimeException(e);
                }
            }
        });
        return brain;
    }
}
