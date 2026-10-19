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
 * Usage: Duel data-dir blueprint-dir other-classes other-blueprints minutes seed...
 *   other-classes is a folder of the other build's compiled classes, other-blueprints the blueprints it was made with
 */
public class Duel{
    public static void main(String[] args) throws Exception{
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
            if(Boolean.getBoolean("xtest.rtsself")){
                mine.rules().rtsAi = true;
                mine.rules().rtsMaxSquad = 15;
                System.setProperty("xp.nomarshal", mine.name);
            }
            //and each starts again from its seat: what it made of the map when it was loaded was for the seat it had before
            Sim.brain.clear();
            Sim.brain.load();
            otherBrain.getMethod("clear").invoke(other);
            otherBrain.getMethod("load").invoke(other);
            Boot.frames(1);
            System.out.println("######## seed " + seed + " " + Vars.world.width() + "x" + Vars.world.height() + (swap ? " (seats swapped)" : "") + ": this build at " + mine.core().tileX() + "," + mine.core().tileY()
                + ", the other at " + theirs.core().tileX() + "," + theirs.core().tileY());
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
                if(m % 5 == 0) System.out.println(String.format("  min %2d: this %s | other %s", m, side(mine), side(theirs)));
            }
            System.out.println("  units by minute (this/other):" + line);
            System.out.println("  DUEL " + seed + " " + result);
            if(result.startsWith("WON")) wins++; else if(result.startsWith("LOST")) losses++; else draws++;
        }
        System.out.println("RESULT: this build won " + wins + ", lost " + losses + ", neither in time " + draws);
        System.exit(0);
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
            + (core == null ? "" : ", core " + core.items.get(mindustry.content.Items.silicon) + " si " + core.items.get(mindustry.content.Items.beryllium) + " be "
            + core.items.get(mindustry.content.Items.tungsten) + " w");
    }

    /**
     * The other build's mod, made and started in a class loader of its own that looks at its own classes first, so that its
     * brain and everything else of it are apart from this one's: set to play the player's side, held to the same research,
     * timed the same way, given its blueprints, and told what goes into and out of the cores.
     */
    static Object other(String classes, String blueprints) throws Exception{
        ClassLoader parent = Duel.class.getClassLoader();
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
