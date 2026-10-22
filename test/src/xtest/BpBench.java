package xtest;

import arc.*;
import arc.files.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.core.GameState.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.storage.*;
import mindustry.world.blocks.units.*;

import java.util.*;

/**
 * The test bench for laid-out factories: a small map drawn in characters, a layout of blocks put down on it at once
 * (nothing is built, nothing is paid for), the real game run for so many seconds, and for every machine what it did:
 * the share of the time it ran, stood blocked (its output with nowhere to go), starved or without power, and what it
 * turned out a second against what it turns out at full speed; and what came into the core a second.
 *
 * Usage: BpBench data-dir spec-file...
 *
 * A spec file:
 * <pre>
 * # a comment
 * map                      the rows, top first; the bottom row is y 0
 * ..........
 * ..B#......
 * end
 * place                    block x y rotation [config]: the game's names; x, y the block's tile as the game has it
 * item-source 0 0 0 graphite
 * end
 * seconds 60               how long the game is run (default 60)
 * </pre>
 * Map characters: . floor (rhyolite), # wall (rhyolite), S sand wall (yellow stone, 1.5 sand), D dune wall (2 sand),
 * B beryllium ore in a wall, G graphitic wall, T tungsten ore in a wall, H thorium ore in a wall, v vent (rhyolite),
 * b beryllium ore, t tungsten ore, h thorium ore on the floor, ~ molten slag, a arkycite.
 */
public class BpBench{
    static final Team team = Team.sharded;

    public static void main(String[] args){
        Boot.boot(args[0]);
        int failed = 0;
        for(int i = 1; i < args.length; i++){
            try{
                run(new Fi(args[i]));
            }catch(Throwable t){
                failed++;
                System.out.println("BENCH " + new Fi(args[i]).nameWithoutExtension() + " failed: " + t);
                t.printStackTrace(System.out);
            }
        }
        System.exit(failed == 0 ? 0 : 1);
    }

    static Block floorOf(char c){
        return switch(c){
            case 'v' -> Blocks.rhyoliteVent;
            case '~' -> Blocks.slag;
            case 'a' -> Blocks.arkyciteFloor;
            default -> Blocks.rhyolite;
        };
    }

    static Block wallOf(char c){
        return switch(c){
            case '#', 'B', 'T', 'H' -> Blocks.rhyoliteWall;
            case 'S' -> Blocks.yellowStoneWall;
            case 'D' -> Blocks.duneWall;
            case 'G' -> Blocks.graphiticWall;
            default -> Blocks.air;
        };
    }

    static Block oreOf(char c){
        return switch(c){
            case 'B' -> Blocks.wallOreBeryllium;
            case 'T' -> Blocks.wallOreTungsten;
            case 'H' -> Blocks.wallOreThorium;
            case 'b' -> Blocks.oreBeryllium;
            case 't' -> Blocks.oreTungsten;
            case 'h' -> Blocks.oreThorium;
            default -> Blocks.air;
        };
    }

    record Place(String block, int x, int y, int rotation, String config){
    }

    static void run(Fi file){
        List<String> rows = new ArrayList<>();
        List<Place> places = new ArrayList<>();
        float seconds = 60f;
        boolean prime = false;
        String section = "";
        for(String raw : file.readString().split("\n")){
            String line = raw.replace("\r", "");
            if(line.trim().startsWith("#") && !section.equals("map")) continue;
            if(line.trim().isEmpty()) continue;
            if(line.trim().equals("end")){
                section = "";
                continue;
            }
            if(section.isEmpty()){
                String[] w = line.trim().split("\\s+");
                if(w[0].equals("map") || w[0].equals("place")) section = w[0];
                else if(w[0].equals("seconds")) seconds = Float.parseFloat(w[1]);
                else if(w[0].equals("prime")) prime = true;
                continue;
            }
            if(section.equals("map")) rows.add(line);
            else{
                String[] w = line.trim().split("\\s+");
                places.add(new Place(w[0], Integer.parseInt(w[1]), Integer.parseInt(w[2]), w.length > 3 ? Integer.parseInt(w[3]) : 0, w.length > 4 ? w[4] : null));
            }
        }
        int h = rows.size(), w = 0;
        for(String r : rows) w = Math.max(w, r.length());
        String worldFile = null;
        for(String raw : file.readString().split("\n")) if(raw.trim().startsWith("world ")) worldFile = raw.trim().substring(6).trim();
        final int width = w, height = h;

        //the world: the characters as floors, walls and ores, and the rules of a sandbox on Erekir
        Vars.logic.reset();
        Rules rules = new Rules();
        Planets.erekir.applyRules(rules, true);
        rules.waves = false;
        rules.fog = false;
        rules.staticFog = false;
        rules.infiniteResources = true;
        rules.coreIncinerates = true;
        Vars.state.rules = rules;
        if(worldFile != null){
            //(a map saved by the game: its ground, every building on it taken away; the layout's own blocks go down next)
            mindustry.io.SaveIO.load(new Fi(worldFile));
            Vars.state.rules = rules;
            for(Tile t : Vars.world.tiles) if(t.build != null && t.isCenter()) t.setBlock(Blocks.air);
            for(Unit u : Groups.unit.copy(new Seq<>())) u.remove();
        }else Vars.world.loadGenerator(width, height, tiles -> {
            for(int y = 0; y < height; y++){
                String r = rows.get(height - 1 - y);
                for(int x = 0; x < width; x++){
                    char c = x < r.length() ? r.charAt(x) : '.';
                    tiles.set(x, y, new Tile(x, y, floorOf(c), oreOf(c), wallOf(c)));
                }
            }
        });
        Vars.state.rules = rules;
        Vars.state.set(State.playing);
        Vars.state.tick = 0;
        Time.setDeltaProvider(() -> 1f);

        //the layout, put down at once; then its configs (what a source gives, what a router lets through)
        List<String> problems = new ArrayList<>();
        Seq<Building> placed = new Seq<>();
        for(Place p : places){
            Block block = Vars.content.block(p.block);
            if(block == null){
                problems.add("no block " + p.block);
                continue;
            }
            Tile tile = Vars.world.tile(p.x, p.y);
            if(tile == null){
                problems.add(p.block + " at " + p.x + "," + p.y + " is off the map");
                continue;
            }
            if(!Build.validPlace(block, team, p.x, p.y, p.rotation, false)) problems.add(p.block + " at " + p.x + "," + p.y + " r" + p.rotation + " could not be placed as a player places it");
            tile.setNet(block, team, p.rotation);
            if(tile.build != null) placed.add(tile.build);
        }
        Boot.frames(1);
        for(Place p : places){
            if(p.config == null) continue;
            Tile tile = Vars.world.tile(p.x, p.y);
            if(tile == null || tile.build == null) continue;
            Object value = configOf(p.config);
            if(value == null) problems.add("config " + p.config + " of " + p.block + " not known");
            else tile.build.configureAny(value);
        }

        //(primed: every block starts with its stores of the items and liquids it uses full, as if it had been running: how a layout is
        //started, the bootstrapping, is left out)
        if(prime) for(Building b : placed){
            for(mindustry.world.consumers.Consume c : b.block.consumers){
                if(c instanceof mindustry.world.consumers.ConsumeItems ci) for(ItemStack st : ci.items) b.items.set(st.item, b.block.itemCapacity);
                if(c instanceof mindustry.world.consumers.ConsumeLiquid cl && b.liquids != null) b.liquids.add(cl.liquid, b.block.liquidCapacity);
                if(c instanceof mindustry.world.consumers.ConsumeLiquids cls && b.liquids != null) for(var st : cls.liquids) b.liquids.add(st.liquid, b.block.liquidCapacity);
                if(c instanceof mindustry.world.consumers.ConsumeItemFilter cf) for(Item item : Vars.content.items()) if(cf.filter.get(item)){
                    b.items.set(item, b.block.itemCapacity);
                    break;
                }
            }
        }

        //(and then run a while with every block working as if fed and powered, the team's cheat rule, so that the lines
        //fill: what is measured after is the layout going by itself, the start of it left out)
        if(prime){
            Vars.state.rules.teams.get(team).cheat = true;
            Boot.frames(30 * 60);
            Vars.state.rules.teams.get(team).cheat = false;
            System.out.println("  (primed: stores filled, 30 s run as if fed)");
        }

        //what each machine does, frame by frame
        Seq<Building> machines = placed.select(BpBench::machine);
        int n = machines.size;
        float[] running = new float[n], blocked = new float[n], starved = new float[n], unpowered = new float[n], eff = new float[n], made = new float[n];
        float[] last = new float[n];
        IntIntMap units = new IntIntMap();
        Seq<Unit> assembled = new Seq<>();
        Events.on(UnitCreateEvent.class, e -> {
            if(e.spawner != null) units.increment(e.spawner.pos(), 1);
            //(a unit an assembler makes stands in its area until it leaves, and the next is not begun while it does:
            //taken away, as if sent off)
            if(e.spawner != null && e.spawner.block instanceof UnitAssembler) assembled.add(e.unit);
        });
        ObjectFloatMap<Item> intake = new ObjectFloatMap<>();
        //(what is lost, when, and holding what: a generator blown up when full, a reactor unstable)
        Events.on(BlockDestroyEvent.class, e -> {
            Building b = e.tile.build;
            if(b == null) return;
            StringBuilder held = new StringBuilder();
            if(b.liquids != null) b.liquids.each((l, a) -> held.append(String.format(" %s %.1f/%.0f", l.name, a, b.block.liquidCapacity)));
            if(b.items != null) b.items.each((it, a) -> held.append(" ").append(it.name).append(" ").append(a));
            String extra = b instanceof mindustry.world.blocks.power.VariableReactor.VariableReactorBuild v ? String.format(" heat %.1f instability %.2f", v.heat, v.instability) : "";
            System.out.println(String.format("  LOST %s at %d,%d after %.1f s:%s%s", b.block.name, b.tileX(), b.tileY(), Vars.state.tick / 60f, held, extra));
        });
        int frames = Math.round(seconds * 60f);
        for(int f = 0; f < frames; f++){
            Boot.frames(1);
            for(Unit u : assembled) if(u.isAdded()) u.remove();
            assembled.clear();
            for(int i = 0; i < n; i++){
                Building b = machines.get(i);
                if(!b.isValid()) continue;
                if(!b.shouldConsume()) blocked[i]++;
                else if(b.efficiency > 0f){
                    running[i]++;
                    eff[i] += b.efficiency;
                }else if(b.block.consPower != null && b.power != null && b.power.status <= 0.001f) unpowered[i]++;
                else starved[i]++;
                //what it turned out this frame
                if(b instanceof GenericCrafter.GenericCrafterBuild g){
                    if(g.progress < last[i] - 1e-4f) made[i]++;
                    last[i] = g.progress;
                }else if(b instanceof Drill.DrillBuild d) made[i] += d.lastDrillSpeed;
                else if(b instanceof BeamDrill.BeamDrillBuild d) made[i] += d.lastDrillSpeed;
                else if(b instanceof WallCrafter.WallCrafterBuild d) made[i] += d.lastEfficiency / ((WallCrafter)b.block).drillTime;
            }
            //what came into the core, taken out again so that it never fills
            for(var data : Vars.state.teams.present) if(data.team == team) for(CoreBlock.CoreBuild core : data.cores){
                for(Item item : Vars.content.items()){
                    int a = core.items.get(item);
                    if(a > 0){
                        intake.increment(item, 0f, a);
                        core.items.remove(item, a);
                    }
                }
            }
        }

        //the report
        String name = file.nameWithoutExtension();
        System.out.println("BENCH " + name + ": " + width + "x" + height + ", " + places.size() + " blocks, " + n + " machines, " + seconds + " s");
        for(String p : problems) System.out.println("  problem: " + p);
        for(int i = 0; i < n; i++){
            Building b = machines.get(i);
            String out = rate(b, made[i], units.get(b.pos(), 0), seconds);
            System.out.println(String.format("  %s at %d,%d: running %3.0f%% (at %.2f) blocked %3.0f%% starved %3.0f%% no power %3.0f%%; %s",
                b.block.name, b.tileX(), b.tileY(), 100f * running[i] / frames, running[i] > 0 ? eff[i] / running[i] : 0f, 100f * blocked[i] / frames, 100f * starved[i] / frames, 100f * unpowered[i] / frames, out));
        }
        StringBuilder in = new StringBuilder();
        for(var e : intake) in.append(String.format(" %s %.2f/s", e.key.name, e.value / seconds));
        System.out.println("  into the core:" + (in.length() == 0 ? " nothing" : in));
    }

    /** Whether a building is one whose work is measured: a crafter, a drill, a unit maker, a generator. */
    static boolean machine(Building b){
        return b.block instanceof GenericCrafter || b.block instanceof Drill || b.block instanceof BeamDrill || b.block instanceof WallCrafter
            || b.block instanceof UnitFactory || b.block instanceof Reconstructor || b.block instanceof UnitAssembler || b.block instanceof mindustry.world.blocks.power.PowerGenerator;
    }

    /** What a machine turned out a second, and at full speed. */
    static String rate(Building b, float made, int units, float seconds){
        if(b.block instanceof GenericCrafter g){
            StringBuilder s = new StringBuilder(String.format("%.0f crafts (%.2f/s, full %.2f/s)", made, made / seconds, 60f / g.craftTime));
            if(g.outputItems != null) for(ItemStack st : g.outputItems) s.append(String.format(" %s %.2f/s", st.item.name, st.amount * made / seconds));
            return s.toString();
        }
        if(b.block instanceof Drill || b.block instanceof BeamDrill || b.block instanceof WallCrafter) return String.format("dug %.2f/s", made / seconds);
        if(b.block instanceof UnitFactory || b.block instanceof Reconstructor || b.block instanceof UnitAssembler) return units + " units";
        if(b.block instanceof mindustry.world.blocks.power.PowerGenerator && b instanceof mindustry.world.blocks.power.PowerGenerator.GeneratorBuild gen) return String.format("power at %.2f", gen.productionEfficiency);
        return "";
    }

    /** A config written in a spec: an item, a liquid, a block or a unit by name. */
    static Object configOf(String s){
        Item item = Vars.content.item(s);
        if(item != null) return item;
        Liquid liquid = Vars.content.liquid(s);
        if(liquid != null) return liquid;
        Block block = Vars.content.block(s);
        if(block != null) return block;
        UnitType unit = Vars.content.unit(s);
        if(unit != null) return unit;
        return null;
    }
}
