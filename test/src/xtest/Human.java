package xtest;

import arc.files.*;
import arc.math.geom.*;
import arc.struct.*;
import mindustry.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.liquid.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.storage.*;
import mindustry.world.blocks.units.*;
import mindustry.world.consumers.*;

/**
 * Reads saved games and says what a side has built in each: how much of what, how its turrets and
 * walls are grouped and where they stand, what its drills and turrets are given to make them faster,
 * how its power stands. For setting a base a person built beside one the mod built.
 *
 * Usage: Human data-dir blueprint-dir [-draw] save.msav...
 */
public class Human{
    public static void main(String[] args){
        Sim.boot(args[0], args[1]);
        boolean draw = false;
        for(int a = 2; a < args.length; a++){
            if(args[a].equals("-draw")){ draw = true; continue; }
            Vars.logic.reset();
            SaveIO.load(new Fi(args[a]));
            Rules rules = Vars.state.rules;
            System.out.println("\n######## " + new Fi(args[a]).name() + ": " + (rules.sector != null && rules.sector.preset != null ? rules.sector.preset.name : Vars.state.map.name()) + " " + Vars.world.width() + "x" + Vars.world.height()
                + ", played " + (int)(Vars.state.tick / 3600) + " min, wave " + Vars.state.wave + (rules.attackMode ? ", attack" : "") + (rules.waves ? ", waves" : "")
                + "; built " + Vars.state.stats.buildingsBuilt + ", lost " + Vars.state.stats.buildingsDestroyed + ", took down " + Vars.state.stats.buildingsDeconstructed
                + ", units made " + Vars.state.stats.unitsCreated + ", enemy units destroyed " + Vars.state.stats.enemyUnitsDestroyed);
            describe(rules.defaultTeam, rules.waveTeam, "the player", draw);
            describe(rules.waveTeam, rules.defaultTeam, "the enemy", false);
        }
        System.exit(0);
    }

    static String kind(Block block){
        return block instanceof CoreBlock ? "core" : block instanceof BaseTurret ? "turret" : block instanceof Wall ? "wall"
            : block instanceof UnitFactory || block instanceof Reconstructor || block instanceof UnitAssembler ? "units"
            : block instanceof BeamDrill || block instanceof Drill || block instanceof WallCrafter ? "mining"
            : block instanceof PowerGenerator ? "generator" : block instanceof PowerBlock || block instanceof BeamNode || block instanceof Battery ? "power"
            : block instanceof GenericCrafter ? "factory" : block instanceof Pump || block instanceof Conduit || block instanceof LiquidBlock || block instanceof LiquidRouter || block instanceof DirectionLiquidBridge ? "liquid"
            : block instanceof Duct || block instanceof DuctRouter || block instanceof DuctBridge || block instanceof OverflowDuct || block instanceof StackConveyor || block instanceof DirectionalUnloader || block instanceof StorageBlock ? "transport"
            : "other";
    }

    /** What a side has standing, by what it is for. */
    static void describe(Team team, Team other, String who, boolean draw){
        Seq<Building> all = new Seq<>();
        for(Tile tile : Vars.world.tiles) if(tile.build != null && tile.isCenter() && tile.team() == team) all.add(tile.build);
        OrderedMap<String, ObjectIntMap<String>> byKind = new OrderedMap<>();
        for(String kind : new String[]{"core", "turret", "wall", "units", "mining", "factory", "generator", "power", "liquid", "transport", "other"}) byKind.put(kind, new ObjectIntMap<>());
        for(Building build : all) byKind.get(kind(build.block)).increment(build.block.name);
        Seq<Building> cores = all.select(b -> b.block instanceof CoreBlock);
        System.out.println("  == " + who + ": " + all.size + " buildings, " + cores.size + " cores" + (cores.isEmpty() ? "" : " at " + cores.toString(" ", c -> c.tileX() + "," + c.tileY())));
        if(all.isEmpty()) return;
        if(!cores.isEmpty()){
            StringBuilder items = new StringBuilder();
            cores.first().items.each((item, amount) -> items.append(item.name).append('=').append(amount).append(' '));
            System.out.println("     core holds (of " + ((CoreBlock.CoreBuild)cores.first()).storageCapacity + "): " + items);
        }
        for(var entry : byKind){
            if(entry.value.size == 0 || entry.key.equals("core")) continue;
            int total = 0;
            for(var count : entry.value) total += count.value;
            System.out.println("     " + entry.key + " " + total + ": " + entry.value);
        }
        ObjectIntMap<String> units = new ObjectIntMap<>();
        for(Unit unit : Groups.unit) if(unit.team == team) units.increment(unit.type.name);
        System.out.println("     units: " + units);
        if(who.equals("the enemy")) return;

        //turrets: what they are loaded with, whether they are watered, how they stand together and how far from a core
        Seq<Building> turrets = all.select(b -> b.block instanceof BaseTurret);
        ObjectIntMap<String> loaded = new ObjectIntMap<>();
        int watered = 0, empty = 0;
        FloatSeq far = new FloatSeq();
        for(Building turret : turrets){
            if(turret instanceof ItemTurret.ItemTurretBuild gun){
                if(gun.ammo.isEmpty()) empty++;
                for(var ammo : gun.ammo) loaded.increment(turret.block.name + ":" + ((ItemTurret.ItemEntry)ammo).item.name);
            }
            if(turret.block instanceof Turret type && type.coolant != null && turret.liquids != null && turret.liquids.currentAmount() > 1f) watered++;
            Building home = Geometry.findClosest(turret.x, turret.y, cores);
            if(home != null) far.add(turret.dst(home) / 8f);
        }
        far.sort();
        Seq<Seq<Building>> groups = groups(turrets, 7f);
        Seq<Building> walls = all.select(b -> b.block instanceof Wall);
        int byTurret = walls.count(w -> turrets.contains(t -> t.within(w, 8f * 7f)));
        if(far.isEmpty()) far.add(0f);
        if(turrets.size > 0) System.out.println("     turrets: loaded with " + loaded + ", " + empty + " empty, " + watered + " of " + turrets.size + " have coolant; from the nearest core " + (int)far.first() + "/" + (int)far.get(far.size / 2) + "/" + (int)far.peek()
            + " tiles (nearest/middle/furthest); in " + groups.size + " groups of " + groups.toString(",", g -> g.size + "") + "; walls within 7 tiles of a turret " + byTurret + " of " + walls.size);

        //mining and what makes it faster
        int drills = 0, boosted = 0;
        for(Building build : all){
            if(!(build.block instanceof BeamDrill || build.block instanceof Drill || build.block instanceof WallCrafter)) continue;
            drills++;
            for(Consume consume : build.block.consumers){
                if((consume.optional || consume.booster) && consume instanceof ConsumeLiquid liquid && build.liquids != null && build.liquids.get(liquid.liquid) > 0.5f){ boosted++; break; }
            }
        }
        //power
        float made = 0f, used = 0f, stored = 0f;
        ObjectSet<PowerGraph> graphs = new ObjectSet<>();
        for(Building build : all){
            if(build.power == null) continue;
            graphs.add(build.power.graph);
            if(build.block instanceof PowerGenerator) made += build.getPowerProduction() * 60f;
            if(build.block.consPower != null && !build.block.consPower.buffered) used += build.block.consPower.usage * 60f;
            if(build.block.consPower != null && build.block.consPower.buffered) stored += build.power.status * build.block.consPower.capacity;
        }
        System.out.println(String.format("     drills %d, %d with a liquid that makes them faster; power made %.0f/s, everything running would use %.0f/s, in %d grids, %.0f stored", drills, boosted, made, used, graphs.size, stored));

        //how much of the base is what
        int transport = 0;
        for(var count : byKind.get("transport")) transport += count.value;
        //how tidy its lines are: how many run into a core, and how much duct there is for everything that makes or takes things
        int entering = 0, users = 0;
        for(Building build : all){
            if((build.block instanceof Duct || build.block instanceof DuctBridge) && build.front() instanceof CoreBlock.CoreBuild) entering++;
            String kind = kind(build.block);
            if(kind.equals("turret") || kind.equals("units") || kind.equals("mining") || kind.equals("factory")) users++;
        }
        System.out.println(String.format("     lines running into a core: %d; duct for each thing that makes or takes items: %.1f", entering, transport / (float)Math.max(users, 1)));
        System.out.println(String.format("     of every 100 buildings: %d turrets, %d walls, %d transport; turrets per core %.1f", turrets.size * 100 / all.size, walls.size * 100 / all.size, transport * 100 / all.size, turrets.size / (float)Math.max(cores.size, 1)));

        if(draw){
            Picture.side = team;
            //the biggest group of turrets, and the first core
            Seq<Building> biggest = groups.max(g -> g.size);
            if(biggest != null){
                float cx = 0f, cy = 0f;
                for(Building b : biggest){ cx += b.tileX() / (float)biggest.size; cy += b.tileY() / (float)biggest.size; }
                System.out.println("     its biggest group of turrets, round " + (int)cx + "," + (int)cy + ":");
                System.out.println(Picture.region((int)cx - 26, (int)cy - 16, 52, 32, null));
            }
            if(!cores.isEmpty()){
                System.out.println("     round its first core:");
                System.out.println(Picture.region(cores.first().tileX() - 30, cores.first().tileY() - 18, 60, 36, null));
            }
            Picture.side = null;
        }
    }

    /** Buildings that stand within so many tiles of one another, taken together. */
    static Seq<Seq<Building>> groups(Seq<Building> from, float tiles){
        Seq<Seq<Building>> out = new Seq<>();
        ObjectSet<Building> done = new ObjectSet<>();
        for(Building start : from){
            if(!done.add(start)) continue;
            Seq<Building> group = Seq.with(start);
            for(int i = 0; i < group.size; i++){
                for(Building other : from){
                    if(!done.contains(other) && other.within(group.get(i), tiles * 8f)){
                        done.add(other);
                        group.add(other);
                    }
                }
            }
            out.add(group);
        }
        out.sort(g -> -g.size);
        return out;
    }
}
