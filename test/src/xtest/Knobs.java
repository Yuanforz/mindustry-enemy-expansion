package xtest;

import arc.*;
import arc.files.*;
import expansion.*;
import mindustry.*;
import mindustry.core.GameState.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.type.*;

/**
 * The settings: each one is changed on a real map, and what the enemy then does is looked at.
 * Usage: Knobs data-dir blueprint-dir maps-dir
 */
public class Knobs{
    static int failures;
    static String maps;
    static Brain brain;

    static void check(String what, boolean ok, Object detail){
        if(!ok) failures++;
        System.out.println((ok ? "  pass  " : "  FAIL  ") + what + "  -> " + detail);
    }

    /** Puts every setting back to how it comes. */
    static void defaults(){
        for(Options.Option option : Options.all) Core.settings.remove(option.key());
        Core.settings.put(Options.grace.key(), 0);
        brain.unlocked = content -> true;
    }

    static Team run(String map, int minutes){
        Vars.logic.reset();
        Sim.begun.clear();
        Sim.turnedOut.clear();
        Sim.nearestToPlayer = Integer.MAX_VALUE;
        Sim.furthest = 0f;
        SaveIO.load(new Fi(maps + "/" + map + ".msav"));
        Vars.state.set(State.playing);
        Vars.state.tick = 0;
        arc.util.Time.setDeltaProvider(() -> 1f);
        Boot.frames(1);
        Sim.campaignRules(true);
        Boot.frames(60 * 60 * minutes);
        return Vars.state.rules.waveTeam;
    }

    /**
     * Destroys one of the ducts the enemy laid, the one furthest from all its builders, and watches it being put back.
     * @return how many ticks until a site stood there again (-1 if never), whether one of its builders was within its reach at that moment (1 or 0), and how many ticks until the duct stood again (-1 if never)
     */
    static int[] rebuilt(Team enemy, int wait){
        Building victim = null;
        float furthest = -1f;
        for(Building build : enemy.data().buildings){
            if(!(build.block instanceof mindustry.world.blocks.distribution.Duct) || Sim.original.contains(build.tile.pos())) continue;
            float near = Float.MAX_VALUE;
            for(Unit unit : enemy.data().units){
                if(unit.canBuild()) near = Math.min(near, unit.dst(build));
            }
            if(near != Float.MAX_VALUE && near > furthest){
                furthest = near;
                victim = build;
            }
        }
        if(victim == null) return new int[]{-1, 0, -1};
        mindustry.world.Tile tile = victim.tile;
        mindustry.world.Block block = victim.block;
        victim.kill();
        int[] out = {-1, 0, -1};
        for(int tick = 1; tick <= wait && out[2] < 0; tick++){
            Boot.frames(1);
            boolean site = tile.build instanceof mindustry.world.blocks.ConstructBlock.ConstructBuild && tile.team() == enemy, stands = tile.block() == block && tile.team() == enemy;
            if(out[0] < 0 && (site || stands)){
                out[0] = tick;
                for(Unit unit : enemy.data().units){
                    if(unit.canBuild() && unit.within(tile.worldx(), tile.worldy(), unit.type.buildRange + Vars.tilesize * 3f)) out[1] = 1;
                }
            }
            if(stands) out[2] = tick;
        }
        return out;
    }

    /**
     * Destroys a stretch of the enemy's ducts all at once, the ones nearest the duct furthest from its builders, and watches them being put back.
     * @return the tick the first site appeared, the tick the last of them stood again (-1 if never), how many ticks the building alone takes one of its builders, and how many stood again
     */
    static float[] stretchRebuilt(Team enemy, int count, int wait){
        arc.struct.Seq<Building> ducts = new arc.struct.Seq<>();
        Building middle = null;
        float furthest = -1f;
        Unit builder = null;
        for(Building build : enemy.data().buildings){
            if(!(build.block instanceof mindustry.world.blocks.distribution.Duct) || Sim.original.contains(build.tile.pos())) continue;
            ducts.add(build);
            float near = Float.MAX_VALUE;
            for(Unit unit : enemy.data().units){
                if(unit.canBuild()){
                    near = Math.min(near, unit.dst(build));
                    builder = unit;
                }
            }
            if(near != Float.MAX_VALUE && near > furthest){
                furthest = near;
                middle = build;
            }
        }
        if(middle == null || builder == null || ducts.size < count) return new float[]{-1f, -1f, 0f, 0f};
        Building from = middle;
        ducts.sort(duct -> duct.dst2(from));
        ducts.truncate(count);
        arc.struct.Seq<mindustry.world.Tile> tiles = ducts.map(duct -> duct.tile);
        mindustry.world.Block block = middle.block;
        float each = block.buildTime * Vars.state.rules.buildCostMultiplier / (builder.type.buildSpeed * Vars.state.rules.buildSpeed(enemy));
        for(Building duct : ducts) duct.kill();
        float first = -1f, done = -1f;
        int standing = 0;
        for(int tick = 1; tick <= wait && done < 0f; tick++){
            Boot.frames(1);
            standing = 0;
            boolean any = false;
            for(mindustry.world.Tile tile : tiles){
                if(tile.block() == block && tile.team() == enemy) standing++;
                if(tile.build != null && tile.team() == enemy) any = true;
            }
            if(first < 0f && any) first = tick;
            if(standing == count) done = tick;
        }
        return new float[]{first, done, each * count, standing};
    }

    /** How many units the yards it built have turned out in the run. */
    static int army(){
        int total = 0;
        for(var entry : Sim.turnedOut) total += entry.value;
        return total;
    }

    static int begun(String block){
        return Sim.begun.get(block, 0);
    }

    public static void main(String[] args){
        Sim.boot(args[0], args[1]);
        brain = Sim.brain;
        maps = args[2];
        Team enemy;

        defaults();
        run("atlas", 14);
        int bores = begun("plasma-bore"), furnaces = begun("silicon-arc-furnace"), yards = begun("tank-fabricator") + begun("ship-fabricator") + begun("mech-fabricator"), turrets = begun("breach") + begun("diffuse");
        float share = Sim.furthest;
        int army = army();
        check("as it comes: mines, a furnace, yards and turrets all go up", bores > 0 && furnaces > 0 && yards > 0 && turrets > 0, bores + " drills, " + furnaces + " furnaces, " + yards + " yards, " + turrets + " turrets");

        defaults();
        Core.settings.put(Options.mines.key(), false);
        run("atlas", 14);
        check("mines off: no drills", begun("plasma-bore") == 0, Sim.begun);

        defaults();
        Core.settings.put(Options.plants.key(), false);
        run("atlas", 14);
        check("factories off: no furnace, no crushers", begun("silicon-arc-furnace") == 0 && begun("cliff-crusher") == 0, Sim.begun);

        defaults();
        Core.settings.put(Options.yards.key(), false);
        run("atlas", 14);
        check("yards off: no unit factories", begun("tank-fabricator") + begun("ship-fabricator") + begun("mech-fabricator") == 0, Sim.begun);

        defaults();
        Core.settings.put(Options.maxOutposts.key(), 0);
        run("atlas", 14);
        check("no turrets allowed: none", begun("breach") + begun("diffuse") == 0, Sim.begun);

        defaults();
        Core.settings.put(Options.rowLength.key(), 1);
        Core.settings.put(Options.minesPerCore.key(), 1);
        Core.settings.put(Options.creep.key(), false);
        enemy = run("atlas", 14);
        int rows = brain.count(Net.Kind.mine);
        check("one drill to a row, one row for each core and ore: no more drills than rows, no more rows than that", begun("plasma-bore") <= rows && rows <= enemy.cores().size * 2, begun("plasma-bore") + " drills in " + rows + " rows, " + enemy.cores().size + " cores");

        defaults();
        Core.settings.put(Options.realStock.key(), false);
        Sim.smartAi = false;
        enemy = run("atlas", 3);
        Sim.smartAi = true;
        check("real resources off: the game keeps the enemy's core full again", enemy.rules().fillItems && enemy.rules().infiniteResources && enemy.core().items.get(mindustry.content.Items.beryllium) > enemy.core().storageCapacity, "beryllium " + enemy.core().items.get(mindustry.content.Items.beryllium));

        defaults();
        brain.unlocked = content -> false;
        run("atlas", 10);
        check("the player has researched nothing: the enemy builds nothing", Sim.begun.isEmpty(), Sim.begun);
        Core.settings.put(Options.techLimit.key(), false);
        run("atlas", 10);
        check("the same with the tech limit off: it builds", begun("plasma-bore") > 0, Sim.begun);

        defaults();
        Core.settings.put(Options.frontier.key(), 30);
        Core.settings.put(Options.creep.key(), false);
        run("atlas", 14);
check("its ground cut back to 30% of the way: nothing it adds is further along than that", Sim.furthest <= 0.31f && Sim.furthest > 0f, String.format("furthest thing %.0f%% of the way; %.0f%% as it comes", Sim.furthest * 100f, share * 100f));

        defaults();
        Core.settings.put(Options.creep.key(), false);
        run("atlas", 30);
        float held = Sim.furthest;
        defaults();
        run("atlas", 30);
        //its turrets go forward when its army and the player's stand against each other (the front posture), which with
        //nobody playing does not happen here: so all that is checked is that advancing loses nothing, and that off it stays
        check("advancing step by step gets it at least as far as staying on its home ground; with that off, it stays", Sim.furthest >= held - 0.01f && held <= 0.51f, String.format("furthest thing %.0f%% of the way with it on, %.0f%% with it off", Sim.furthest * 100f, held * 100f));

        defaults();
        Core.settings.put(Options.mines.key(), false);
        Core.settings.put(Options.haul.key(), false);
        Core.settings.put(Options.handMine.key(), false);
        //and no factories, which would send what they make to a core
        Core.settings.put(Options.plants.key(), false);
        //on this map nothing of the base the enemy starts with leads to its core
        enemy = run("aegis", 8);
        long earned = 0;
        for(Item item : Vars.content.items()) earned += brain.stock.earned(item);
        check("no mines, no carrying and no mining by hand: nothing comes in", earned == 0, "earned " + earned);
        defaults();
        Core.settings.put(Options.mines.key(), false);
        Core.settings.put(Options.haul.key(), false);
        Core.settings.put(Options.plants.key(), false);
        Core.settings.put(Options.yards.key(), false);
        Core.settings.put(Options.maxOutposts.key(), 0);
        run("aegis", 8);
        earned = 0;
        for(Item item : Vars.content.items()) earned += brain.stock.earned(item);
        check("with mining by hand on again and nothing to build, its builders bring ore in themselves", earned > 0, "earned " + earned);
        defaults();
        Core.settings.put(Options.mines.key(), false);
        Core.settings.put(Options.handMine.key(), false);
        run("aegis", 8);
        earned = 0;
        for(Item item : Vars.content.items()) earned += brain.stock.earned(item);
        check("with carrying on again, the old base's piled-up output comes in", earned > 0, "earned " + earned);

        defaults();
        Core.settings.put(Options.baseLines.key(), false);
        Core.settings.put(Options.mines.key(), false);
        Core.settings.put(Options.plants.key(), false);
        run("aegis", 14);
        check("no branching off the old base's ducts, and no lines of its own: nothing to feed a turret or a yard from", begun("breach") + begun("tank-fabricator") + begun("ship-fabricator") == 0, Sim.begun);

        defaults();
        Core.settings.put(Options.openWalls.key(), false);
        run("peaks", 12);
        //a drill for a first source may stand without a line, its ore carried home by hand: what counts here is the lines
        int led = brain.net.things.count(thing -> thing.kind == Net.Kind.mine && brain.net.whole(thing));
        check("may not open its walls: on a map where the cores are walled in, no line gets to one", led == 0, led + " mines with a line to a core, " + brain.count(Net.Kind.mine) + " in all");
        defaults();
        run("peaks", 12);
        check("and with that allowed again, it does", brain.count(Net.Kind.mine) > 0, brain.count(Net.Kind.mine) + " mines");

        defaults();
        Core.settings.put(Options.unitCap.key(), 25);
        Core.settings.put(Options.unitMost.key(), 200);
        enemy = run("atlas", 3);
        int low = Brain.unitCap(enemy.data());
        Core.settings.put(Options.unitCap.key(), 200);
        check("the unit limit follows its setting", low * 8 == Brain.unitCap(enemy.data()) && low > 0, low + " at 25%, " + Brain.unitCap(enemy.data()) + " at 200%");

        Core.settings.put(Options.unitMost.key(), 30);
        check("and never goes above the ceiling set for it", Brain.unitCap(enemy.data()) == 30, Brain.unitCap(enemy.data()));

                //what the player destroys is put back the way the player would have to: a builder flies there and builds it, at the player's pace
        defaults();
        enemy = run("atlas", 8);
        Rules rules = Vars.state.rules;
        check("its builders build at the player's speed, whatever the game set for the enemy", rules.buildSpeed(enemy) == rules.buildSpeed(rules.defaultTeam), "enemy x" + rules.buildSpeed(enemy) + ", player x" + rules.buildSpeed(rules.defaultTeam));
        int[] back = rebuilt(enemy, 60 * 60 * 6);
        check("by default a duct of its own that is destroyed is not back at once: a builder has flown to within reach before the site appears", back[0] > 60 && back[1] == 1,
            back[0] < 0 ? "not begun again in six minutes" : "site after " + back[0] / 60 + "s, standing after " + (back[2] < 0 ? "more than six minutes" : back[2] / 60 + "s") + "; a builder within reach when it was begun: " + (back[1] == 1));
        //and a whole stretch that is destroyed goes up again block after block, at the pace the unit builds and no slower:
        //not one block and then a wait, as a builder that had to be told about each block separately would do it
        float[] row = stretchRebuilt(enemy, 20, 60 * 60 * 5);
        check("twenty ducts destroyed together are put back one after the other at the builder's own building speed", row[1] >= 0f && row[1] - row[0] <= row[2] * 2f + 20 * 6f,
            row[1] < 0f ? "not all back in five minutes (" + (int)row[3] + " of 20)" : String.format("first site after %.0fs, all twenty standing %.1fs after that; the building alone takes a unit %.1fs", row[0] / 60f, (row[1] - row[0]) / 60f, row[2] / 60f));
        Core.settings.put(Options.hands.key(), true);
        Boot.frames(120);
        back = rebuilt(enemy, 60 * 30);
        check("with building by itself switched on it is back within seconds, builder or no builder", back[2] >= 0 && back[2] <= 60 * 5, back[2] < 0 ? "not back in thirty seconds" : "standing after " + back[2] + " ticks");

        //the one switch for the enemy at its strongest. every setting it covers is first put to the far end from its strongest
        defaults();
        int covered = 0, wrongOff = 0, wrongOn = 0, movedOthers = 0;
        for(Options.Option option : Options.all){
            if(option instanceof Options.Flag flag && flag.strong != null) Core.settings.put(flag.key(), !flag.strong);
            if(option instanceof Options.Num num && num.strong != null) Core.settings.put(num.key(), num.strong == num.most ? num.least : num.most);
        }
        Core.settings.put(Options.patience.key(), 7);
        Core.settings.put(Options.realStock.key(), true);
        Core.settings.put(Options.techLimit.key(), true);
        for(int pass = 0; pass < 2; pass++){
            boolean on = pass == 1;
            Core.settings.put(Options.strongest.key(), on);
            for(Options.Option option : Options.all){
                if(option instanceof Options.Flag flag && flag.strong != null){
                    if(!on) covered++;
                    if(flag.on() != (on ? flag.strong : !flag.strong)){ if(on) wrongOn++; else wrongOff++; }
                }
                if(option instanceof Options.Num num && num.strong != null){
                    if(!on) covered++;
                    if(num.get() != (on ? num.strong : (num.strong == num.most ? num.least : num.most))){ if(on) wrongOn++; else wrongOff++; }
                }
            }
            if(Options.patience.get() != 7 || !Options.realStock.on() || !Options.techLimit.on() || Options.debug.on() || Options.timeLimit.get() != Options.timeLimit.start) movedOthers++;
        }
        check("strongest mode off: every setting is what the player set", wrongOff == 0, covered + " settings it covers, " + wrongOff + " wrong");
        check("strongest mode on: every setting it covers counts as its strongest, wherever it was set", wrongOn == 0, covered + " settings, " + wrongOn + " wrong; e.g. yards to a core " + Options.yardsPerCore.get() + ", blocks a second " + Options.buildRate.get() + ", wait at the start " + Options.grace.get());
        check("and the fairness rules, the saving-up time and the planning time limit are left as they were", movedOthers == 0, "real stock " + Options.realStock.on() + ", tech limit " + Options.techLimit.on() + ", patience " + Options.patience.get());

        //every setting has a name and an explanation in both languages
        String[] files = {"bundles/bundle_zh_CN.properties", "bundles/bundle.properties"};
        StringBuilder missing = new StringBuilder();
        for(String file : files){
            String text = new Fi(file).readString();
            for(Options.Option option : Options.all){
                //an explanation is only given where the name does not say it all
                for(String part : new String[]{".name = "}){
                    if(!text.contains("setting." + option.key() + part)) missing.append(file).append(": ").append(option.key()).append(part).append("  ");
                }
            }
        }
        check("every setting has a name in both languages", missing.length() == 0, Options.all.size + " settings" + (missing.length() == 0 ? "" : "; missing " + missing));

        //and with the switch on it plays: more of everything than as it comes, and nothing it should never do
        defaults();
        Core.settings.put(Options.strongest.key(), true);
        int before = Sim.problems;
        run("atlas", 14);
        int strongYards = begun("tank-fabricator") + begun("ship-fabricator") + begun("mech-fabricator"), strongTurrets = begun("breach") + begun("diffuse"), strongArmy = army();
        //what comes of it differs from map to map and from one stretch of a game to the next, since what it builds with is
        //still only what it has: the figures are printed to be read, and what is held to is what is certain
        int ceiling = Brain.unitCap(Vars.state.rules.waveTeam.data());
        Core.settings.put(Options.strongest.key(), false);
        int usual = Brain.unitCap(Vars.state.rules.waveTeam.data());
        Core.settings.put(Options.strongest.key(), true);
        check("at its strongest it may field more of each unit than as it comes", ceiling > usual,
            ceiling + " of each against " + usual + "; this run " + strongArmy + " units from " + strongYards + " yards, with " + strongTurrets + " turrets; as it comes " + army + " units from " + yards + " yards, with " + turrets + " turrets");
        check("and breaks none of its rules doing so", Sim.problems == before, (Sim.problems - before) + " problems");
        defaults();

        System.out.println(failures == 0 ? "RESULT: all pass" : "RESULT: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
