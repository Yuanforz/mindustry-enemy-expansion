package xtest;

import arc.*;
import arc.files.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import expansion.*;
import expansion.Blueprint.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.core.GameState.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.environment.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.storage.*;
import mindustry.world.blocks.units.*;
import mindustry.world.meta.*;

/**
 * Tries out every blueprint on its own: puts it on an empty map with the ground it needs, feeds its
 * inputs, leads its outputs to a core with the mod's own duct laying, runs the game and counts what
 * arrives. Each blueprint is tried facing all four ways, and flipped over.
 *
 * Usage: Bench data-dir [-v] [-only name] blueprint-file...
 */
public class Bench{
    static final Team team = Team.malis;
    static final int size = 70, coreX = 56, coreY = 56;
    static final ObjectIntMap<Item> arrived = new ObjectIntMap<>();
    static final ObjectIntMap<UnitType> turnedOut = new ObjectIntMap<>();
    static Ground ground;
    static Lines lines, pipes;
    /** The tanks that catch what a blueprint gives off in liquids, with what each should get a second. */
    static final Seq<Object[]> tanks = new Seq<>();
    static int failures, runs;
    static boolean verbose;

    /** A core that counts everything it is sent, including what it burns. */
    static class CountingCore extends CoreBlock.CoreBuild{
        CountingCore(CoreBlock block){
            block.super();
        }

        @Override
        public void handleItem(Building source, Item item){
            arrived.increment(item);
            super.handleItem(source, item);
        }
    }

    static void world(){
        Vars.logic.reset();
        Vars.world.loadGenerator(size, size, tiles -> {
            for(int x = 0; x < size; x++){
                for(int y = 0; y < size; y++) tiles.set(x, y, new Tile(x, y, Blocks.rhyolite, Blocks.air, Blocks.air));
            }
        });
        Rules rules = new Rules();
        Planets.erekir.applyRules(rules, true);
        rules.fog = false;
        rules.coreIncinerates = true;
        rules.attackMode = true;
        Vars.state.rules = rules;
        Vars.state.set(State.playing);
        Time.setDeltaProvider(() -> 1f);
        put(Blocks.coreBastion, coreX, coreY, 0, null);
        ground = new Ground();
        ground.team = team;
        lines = new Lines(ground);
        lines.duct = Blocks.duct;
        lines.bridge = Blocks.ductBridge;
        lines.router = Blocks.ductRouter;
        lines.underflow = Blocks.underflowDuct;
        pipes = new Lines(ground);
        pipes.liquid = true;
        pipes.duct = Blocks.reinforcedConduit;
        pipes.bridge = Blocks.reinforcedBridgeConduit;
        tanks.clear();
        arrived.clear();
        turnedOut.clear();
    }

    static Building put(Block block, int x, int y, int rotation, @Nullable Object config){
        Tile tile = Vars.world.tile(x, y);
        tile.setBlock(block, team, rotation);
        if(config != null && tile.build != null) tile.build.configured(null, config);
        return tile.build;
    }

    static void build(Seq<Part> parts){
        for(Part part : parts) put(part.block, part.x, part.y, part.rotation, part.config);
    }

    /** A wall that crushers get sand out of at the plain rate. */
    static Block sandWall(){
        return Vars.content.blocks().find(b -> b instanceof StaticWall && b.attributes.get(Attribute.sand) == 1f && b.itemDrop == null);
    }

    /** Gives every part the ground it works on: ore in front of drills, sand wall in front of crushers, vents under what needs steam. */
    static void terrain(Layout layout, Item ore){
        for(Part part : layout.parts){
            Block block = part.block;
            int dx = Geometry.d4x(part.rotation), dy = Geometry.d4y(part.rotation);
            if(block instanceof BeamDrill drill){
                for(int lane = 0; lane < drill.size; lane++){
                    drill.nearbySide(part.x, part.y, part.rotation, lane, Tmp.p1);
                    //a little way off, as walls are: the beam has to cross open ground first
                    for(int i = 1; i <= 2; i++) wall(Tmp.p1.x + dx * i, Tmp.p1.y + dy * i, ore);
                }
            }else if(block instanceof WallCrafter crusher){
                for(int lane = 0; lane < crusher.size; lane++){
                    crusher.nearbySide(part.x, part.y, part.rotation, lane, Tmp.p1);
                    Vars.world.tile(Tmp.p1.x, Tmp.p1.y).setBlock(sandWall());
                }
            }else if(block instanceof ThermalGenerator || (block instanceof AttributeCrafter crafter && crafter.attribute == Attribute.steam)){
                Vars.world.tile(part.x, part.y).getLinkedTilesAs(block, tile -> tile.setFloor(Blocks.rhyoliteVent.asFloor()));
            }else if(block instanceof Drill){
                Vars.world.tile(part.x, part.y).getLinkedTilesAs(block, tile -> tile.setOverlay(Blocks.oreTungsten));
            }
        }
    }

    static void wall(int x, int y, Item ore){
        Tile tile = Vars.world.tile(x, y);
        if(tile == null) return;
        if(ore == Items.graphite){
            tile.setBlock(Blocks.graphiticWall);
        }else{
            tile.setBlock(Blocks.rhyoliteWall);
            tile.setOverlay(ore == Items.tungsten ? Blocks.wallOreTungsten : ore == Items.thorium ? Blocks.wallOreThorium : Blocks.wallOreBeryllium);
        }
    }

    /**
     * Gives the blueprint power at one point only, so that everything in it has to be joined up by
     * its own wiring: through a beam node of its own if it has one, else at its main block.
     */
    static boolean power(Layout layout){
        if(layout.blueprint.kind() == Kind.power) return true;
        IntSet ports = new IntSet();
        for(Port port : layout.ports) ports.add(Point2.pack(port.x, port.y));
        Part node = layout.parts.find(part -> part.block instanceof BeamNode);
        if(node == null){
            Part main = layout.main();
            if(!main.block.hasPower) return true;
            int offset = -(main.block.size - 1) / 2;
            for(int d = 0; d < 4; d++){
                //straight out from the middle of one side
                int x = main.x + (d == 0 ? main.block.size + offset : d == 2 ? offset - 1 : 0), y = main.y + (d == 1 ? main.block.size + offset : d == 3 ? offset - 1 : 0);
                if(ground.open(x, y) && !ports.contains(Point2.pack(x, y)) && !nextToPowered(layout, x, y, main)){
                    put(Blocks.powerSource, x, y, 0, null);
                    ground.plan(new Part(Blocks.powerSource, x, y, 0));
                    return true;
                }
            }
            return false;
        }
        for(int d = 0; d < 4; d++){
            int dx = Geometry.d4x(d), dy = Geometry.d4y(d);
            boolean clear = true;
            for(int i = 1; i <= 5; i++) clear &= ground.open(node.x + dx * i, node.y + dy * i) && !ports.contains(Point2.pack(node.x + dx * i, node.y + dy * i));
            if(!clear) continue;
            put(Blocks.beamNode, node.x + dx * 4, node.y + dy * 4, 0, null);
            put(Blocks.powerSource, node.x + dx * 5, node.y + dy * 5, 0, null);
            ground.plan(new Part(Blocks.beamNode, node.x + dx * 4, node.y + dy * 4, 0));
            ground.plan(new Part(Blocks.powerSource, node.x + dx * 5, node.y + dy * 5, 0));
            return true;
        }
        return false;
    }

    static boolean nextToPowered(Layout layout, int x, int y, Part except){
        for(int d = 0; d < 4; d++){
            Part other = ground.planned(x + Geometry.d4x(d), y + Geometry.d4y(d));
            if(other != null && other != except && other.block.hasPower) return true;
        }
        return false;
    }

    static IntSet footprint(Layout layout){
        IntSet out = new IntSet();
        for(Part part : layout.parts) Vars.world.tile(part.x, part.y).getLinkedTilesAs(part.block, tile -> out.add(tile.pos()));
        return out;
    }

    /** An endless supply of an item, some way off, with a line laid from it to an input. */
    static boolean feed(Port port, int index, Layout layout){
        int back = (port.rotation + 2) % 4, across = (back + 1) % 4;
        //the places the other lines come in and go out stay clear, as they do when the mod lays lines
        for(Port other : layout.ports) if(other != port) ground.kept.add(Point2.pack(other.x, other.y));
        try{
            //out to the side the input is on; wherever there is room for it there
            for(int away = 7; away <= 11; away += 2){
                for(int step = 0; step <= 12; step++){
                    int shift = (step % 2 == 0 ? 1 : -1) * (step + 1) / 2 + (index * 4 - 2);
                    int sx = port.x + Geometry.d4x(back) * away + Geometry.d4x(across) * shift, sy = port.y + Geometry.d4y(back) * away + Geometry.d4y(across) * shift;
                    int fx = sx + Geometry.d4x(port.rotation), fy = sy + Geometry.d4y(port.rotation);
                    if(!ground.open(sx, sy) || !ground.open(fx, fy) || ground.fedByOthers(fx, fy, port.rotation, IntSet.with(Point2.pack(sx, sy)))) continue;
                    //nothing else may be fed by the supply
                    boolean alone = true;
                    for(int d = 0; d < 4; d++) alone &= (sx + Geometry.d4x(d) == fx && sy + Geometry.d4y(d) == fy) || !ground.look(sx + Geometry.d4x(d), sy + Geometry.d4y(d));
                    if(!alone) continue;
                    Part source = new Part(Blocks.itemSource, sx, sy, 0);
                    ground.plan(source);
                    Lines.Line line = lines.route(fx, fy, IntSet.with(Point2.pack(sx, sy)), Lines.Goal.port(port));
                    if(line == null){
                        ground.unplan(Seq.with(source));
                        continue;
                    }
                    put(Blocks.itemSource, sx, sy, 0, port.item);
                    ground.plan(line.parts);
                    build(line.parts);
                    return true;
                }
            }
            return false;
        }finally{
            ground.kept.clear();
        }
    }

    /**
     * Sees to a layout's liquids: an endless supply right against every block that needs one piped in,
     * and for everything it has to spare a tank some way off with a conduit laid to it by the mod's own
     * conduit laying.
     * @return what went wrong, or null
     */
    static String plumb(Layout layout, IntSet keep){
        for(Plumbing.Tap need : Plumbing.needs(layout.parts)){
            Seq<int[]> spots = Plumbing.outlets(need.part, -1, ground);
            spots.removeAll(at -> keep.contains(Point2.pack(at[0], at[1])));
            if(spots.isEmpty()) return "no room for a supply of " + need.liquid.name + " next to " + need.part.block.name;
            int[] at = spots.first();
            put(Blocks.liquidSource, at[0], at[1], 0, need.liquid);
            ground.plan(new Part(Blocks.liquidSource, at[0], at[1], 0));
        }
        for(Plumbing.Tap spare : Plumbing.gives(layout.parts)){
            boolean laid = false;
            Seq<int[]> spots = Plumbing.outlets(spare.part, spare.side, ground);
            spots.removeAll(at -> keep.contains(Point2.pack(at[0], at[1])));
            for(int[] at : spots){
                //a tank straight out from there, well clear of everything
                int tx = at[0] + Geometry.d4x(at[2]) * 7, ty = at[1] + Geometry.d4y(at[2]) * 7;
                Part tank = new Part(Blocks.reinforcedLiquidTank, tx, ty, 0);
                //tanks hand liquid to whatever liquid block stands against them, so each stands clear of everything by a tile
                boolean apart = true;
                for(int dx = -2; dx <= 2 && apart; dx++){
                    for(int dy = -2; dy <= 2 && apart; dy++) apart = ground.open(tx + dx, ty + dy);
                }
                if(!apart) continue;
                ground.plan(tank);
                IntSet tiles = new IntSet(), from = new IntSet();
                Shape.tiles(tank, tiles);
                Shape.tiles(spare.part, from);
                Lines.Line line = pipes.route(at[0], at[1], at[2], from, Lines.Goal.into(tiles, tx, ty, null));
                if(line == null){
                    ground.unplan(Seq.with(tank));
                    continue;
                }
                put(tank.block, tx, ty, 0, null);
                ground.plan(line.parts);
                build(line.parts);
                tanks.add(new Object[]{Vars.world.build(tx, ty), spare});
                laid = true;
                break;
            }
            if(!laid) return "no way to lay a conduit for the " + spare.liquid.name + " from " + spare.part.block.name;
        }
        return null;
    }

    static String picture(){
        StringBuilder out = new StringBuilder();
        int minX = size, minY = size, maxX = 0, maxY = 0;
        for(Tile tile : Vars.world.tiles){
            if(tile.build != null || tile.block() != Blocks.air){ minX = Math.min(minX, tile.x); minY = Math.min(minY, tile.y); maxX = Math.max(maxX, tile.x); maxY = Math.max(maxY, tile.y); }
        }
        for(int y = maxY + 1; y >= minY - 1; y--){
            out.append(String.format("    %3d ", y));
            for(int x = minX - 1; x <= maxX + 1; x++){
                Tile tile = Vars.world.tile(x, y);
                if(tile == null) continue;
                if(tile.build != null) out.append(Picture.cell(tile.block(), tile.build.rotation, tile.build.tile == tile));
                else if(tile.solid()) out.append(tile.wallDrop() != null ? "" + tile.wallDrop().name.charAt(0) + tile.wallDrop().name.charAt(0) : "%%");
                else if(tile.floor() instanceof SteamVent) out.append("::");
                else if(tile.overlay().itemDrop != null) out.append("''");
                else out.append("  ");
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** Puts a blueprint down with the ground it needs and power laid on. */
    static Layout place(Blueprint blueprint, int x, int y, int turns, boolean mirrored, int count, Item ore){
        Layout layout = blueprint.at(x, y, turns, mirrored, count);
        terrain(layout, ore);
        ground.plan(layout.parts);
        build(layout.parts);
        if(!power(layout)) throw new IllegalStateException("nowhere to connect power for " + blueprint.name);
        return layout;
    }

    /** Lays and builds a line from a layout's output. */
    static Lines.Line lead(Layout layout, Lines.Goal goal){
        Port port = layout.port(Role.out, null);
        Lines.Line line = lines.route(port.x, port.y, footprint(layout), goal);
        if(line == null) return null;
        ground.plan(line.parts);
        build(line.parts);
        return line;
    }

    static IntSet tiles(Lines.Line line){
        IntSet out = new IntSet();
        for(Part part : line.parts) out.add(Point2.pack(part.x, part.y));
        return out;
    }

    static void fail(String what){
        failures++;
        System.out.println("  FAIL  " + what);
    }

    /** One blueprint, one way round. @return a line saying how it went, or null if it could not be set up */
    static void run(Blueprint blueprint, int turns, boolean mirrored, Item ore){
        runs++;
        world();
        String label = blueprint.name + (blueprint.copies > 1 ? " ×" + blueprint.copies : "") + " turned " + turns + (mirrored ? " flipped" : "") + (blueprint.kind() == Kind.mine ? " on " + ore.name : "");
        Layout layout = blueprint.at(28, 28, turns, mirrored, blueprint.copies);
        terrain(layout, ore);

        for(Part part : layout.parts){
            if(!ground.fits(part.block, part.x, part.y, part.rotation)){
                fail(label + ": " + part.block.name + " does not fit at " + part.x + "," + part.y);
                return;
            }
            ground.plan(part);
        }
        build(layout.parts);
        IntSet feeders = footprint(layout);
        //what an addition is added to stands already when the enemy adds to it, supplied and at work: here it gets
        //what it takes from sandbox sources set against it
        for(Part part : layout.parts){
            if(!part.anchor) continue;
            Seq<Object> wants = new Seq<>();
            if(part.block instanceof UnitFactory factory && part.config instanceof UnitType unit){
                for(ItemStack stack : factory.plans.find(plan -> plan.unit == unit).requirements) wants.add(stack.item);
            }
            wants.add(Blocks.powerSource);
            IntSet taken = new IntSet();
            for(Port port : layout.ports) taken.add(Point2.pack(port.x, port.y));
            int offset = -(part.block.size - 1) / 2, size = part.block.size;
            //as far from where the addition's own lines come in as can be: a source hands its item to any duct beside it
            Seq<int[]> places = new Seq<>();
            for(int side = 0; side < 4; side++){
                for(int i = 0; i < size; i++){
                    int x = part.x + offset + (side == 0 ? size : side == 2 ? -1 : i), y = part.y + offset + (side == 1 ? size : side == 3 ? -1 : i);
                    if(ground.open(x, y) && !taken.contains(Point2.pack(x, y))) places.add(new int[]{x, y});
                }
            }
            places.sort(at -> {
                float nearest = Float.MAX_VALUE;
                for(Port port : layout.ports) nearest = Math.min(nearest, Math.abs(port.x - at[0]) + Math.abs(port.y - at[1]));
                return -nearest;
            });
            for(int i = 0; i < places.size && wants.size > 0; i++){
                Object want = wants.pop();
                Block source = want instanceof Item ? Blocks.itemSource : Blocks.powerSource;
                put(source, places.get(i)[0], places.get(i)[1], 0, want instanceof Item ? want : null);
                ground.plan(new Part(source, places.get(i)[0], places.get(i)[1], 0));
            }
        }

        if(!power(layout)){
            fail(label + ": nowhere to connect power");
            return;
        }

        IntSet portTiles = new IntSet();
        for(Port port : layout.ports) portTiles.add(Point2.pack(port.x, port.y));
        //and nothing goes between a wall drill and its wall
        for(Part part : layout.parts){
            if(!(part.block instanceof BeamDrill drill)) continue;
            for(int lane = 0; lane < drill.size; lane++){
                drill.nearbySide(part.x, part.y, part.rotation, lane, Tmp.p1);
                for(int i = 0; i < drill.range; i++) portTiles.add(Point2.pack(Tmp.p1.x + Geometry.d4x(part.rotation) * i, Tmp.p1.y + Geometry.d4y(part.rotation) * i));
            }
        }
        String leak = plumb(layout, portTiles);
        if(leak != null){
            fail(label + ": " + leak);
            if(verbose) System.out.println(picture());
            return;
        }

        int index = 0;
        for(Port port : layout.ports){
            if(port.role == Role.in && !feed(port, index++, layout)){
                fail(label + ": no way to lay a line to the input for " + port.item.name + " at " + port.x + "," + port.y);
                if(verbose) System.out.println(picture());
                return;
            }
        }
        for(Port port : layout.ports){
            if(port.role != Role.out) continue;
            Lines.Line line = lines.route(port.x, port.y, feeders, Lines.Goal.core(null));
            if(line == null){
                fail(label + ": no way to lay a line from the output at " + port.x + "," + port.y + " to the core");
                if(verbose) System.out.println(picture());
                return;
            }
            ground.plan(line.parts);
            build(line.parts);
        }

        //warm up, then measure
        Boot.frames(60 * 60);
        arrived.clear();
        turnedOut.clear();
        float[] tankStart = new float[tanks.size];
        for(int i = 0; i < tanks.size; i++) tankStart[i] = ((Building)tanks.get(i)[0]).liquids.currentAmount();
        int seconds = blueprint.kind() == Kind.yard || blueprint.kind() == Kind.addon ? 240 : 120;
        Boot.frames(60 * seconds);

        //what it should come to
        ObjectFloatMap<Item> expected = new ObjectFloatMap<>();
        boolean powered = true, dry = false;
        StringBuilder notes = new StringBuilder();
        for(Part part : layout.parts){
            Building build = Vars.world.build(part.x, part.y);
            if(build == null || build.block != part.block){
                fail(label + ": " + part.block.name + " is not standing at " + part.x + "," + part.y);
                return;
            }
            if(part.block.consPower != null && !part.block.consPower.buffered && build.power.status < 0.99f) powered = false;
            if(part.block instanceof BeamDrill drill){
                expected.increment(ore, 0f, Rates.beam(drill, ore) * drill.size);
            }else if(part.block instanceof Drill drill){
                expected.increment(Items.tungsten, 0f, Rates.drill(drill, Items.tungsten, drill.size * drill.size));
            }else if(part.block instanceof WallCrafter crusher){
                expected.increment(crusher.output, 0f, Rates.crusher(crusher, crusher.size));
            }else if(part.block instanceof GenericCrafter crafter && crafter.outputItems != null){
                for(ItemStack stack : crafter.outputItems) expected.increment(stack.item, 0f, Rates.makes(crafter, stack.item));
            }
            if(build instanceof ItemTurret.ItemTurretBuild turret) notes.append(" ammo ").append(turret.totalAmmo);
            //whatever a block has to be given in liquids has to have got to it
            for(Rates.Flow need : Rates.liquidNeeds(part.block)){
                notes.append(String.format(" %s %.0f/%.0f", need.liquid.name, build.liquids.get(need.liquid), part.block.liquidCapacity));
                if(build.liquids.get(need.liquid) < 1f) dry = true;
            }
            if(verbose && build instanceof GenericCrafter.GenericCrafterBuild crafter) notes.append(String.format(" [%s holds %s, efficiency %.2f, power %.2f]", part.block.name, build.items, build.efficiency, build.power == null ? -1f : build.power.status));
            if(build instanceof PowerGenerator.GeneratorBuild generator){
                notes.append(String.format(" makes %.2f power/tick", generator.getPowerProduction()));
                if(generator.getPowerProduction() <= 0f) powered = false;
            }
        }

        boolean good = powered && !dry;
        StringBuilder result = new StringBuilder();
        boolean leads = layout.ports.contains(port -> port.role == Role.out);
        for(var entry : expected){
            //what is used up inside the blueprint does not come out of it
            if(layout.parts.contains(part -> Rates.uses(part.block, entry.key) > 0f)) continue;
            float got = arrived.get(entry.key) / (float)seconds;
            result.append(String.format(" %s %.2f/s of %.2f", entry.key.name, got, entry.value));
            if(leads && got < entry.value * 0.9f) good = false;
        }
        //what its liquids came to
        for(int i = 0; i < tanks.size; i++){
            Building tank = (Building)tanks.get(i)[0];
            Plumbing.Tap spare = (Plumbing.Tap)tanks.get(i)[1];
            float got = (tank.liquids.get(spare.liquid) - tankStart[i]) / seconds;
            //a tank that filled up says nothing about the rate
            boolean full = tank.liquids.get(spare.liquid) >= tank.block.liquidCapacity - 1f;
            result.append(String.format(" %s %.1f/s of %.1f%s", spare.liquid.name, got, spare.rate, full ? " (tank full)" : ""));
            if(!full && got < spare.rate * 0.85f) good = false;
        }
        for(var entry : turnedOut) result.append(" ").append(entry.key.name).append(" ×").append(entry.value);
        if(blueprint.kind() == Kind.yard && turnedOut.isEmpty()) good = false;
        //an addition that takes units has to have turned one into the next kind
        if(blueprint.kind() == Kind.addon && layout.parts.contains(part -> part.block instanceof Reconstructor) && turnedOut.size < 2) good = false;
        //every turret of it has to have been given ammunition
        for(Part part : layout.parts){
            if(blueprint.kind() == Kind.post && Vars.world.build(part.x, part.y) instanceof ItemTurret.ItemTurretBuild turret && turret.totalAmmo == 0) good = false;
        }

        if(!good) failures++;
        System.out.println((good ? "  pass  " : "  FAIL  ") + label + ":" + result + notes + (powered ? "" : "  NOT FULLY POWERED"));
        if(verbose || !good) System.out.println(picture());
    }

    /** Makes cores count what reaches them, and counts the units that are turned out. */
    static void install(){
        for(Block block : Vars.content.blocks()){
            if(block instanceof CoreBlock core) core.buildType = () -> new CountingCore(core);
        }
        Events.on(UnitCreateEvent.class, e -> {
            if(e.spawner != null) turnedOut.increment(e.unit.type);
        });
    }

    public static void main(String[] args){
        Boot.boot(args[0]);
        install();

        String only = null;
        Seq<Blueprint> blueprints = new Seq<>();
        for(int i = 1; i < args.length; i++){
            if(args[i].equals("-v")) verbose = true;
            else if(args[i].equals("-only")) only = args[++i];
            else blueprints.addAll(Blueprint.parse(new Fi(args[i]).readString()));
        }

        for(Blueprint blueprint : blueprints){
            if(only != null && !blueprint.name.equals(only)) continue;
            //a wall drill is tried on the hardest ores it can mine
            Item[] ores = blueprint.kind() == Kind.mine ? new Item[]{Items.tungsten, Items.thorium, Items.beryllium, Items.graphite} : new Item[]{Items.beryllium};
            int tried = 0;
            for(Item ore : ores){
                BeamDrill drill = blueprint.main() instanceof BeamDrill d ? d : null;
                if(drill != null && (ore.hardness > drill.tier || tried >= 2)) continue;
                tried++;
                for(int turns = 0; turns < 4; turns++){
                    run(blueprint, turns, false, ore);
                    run(blueprint, turns, true, ore);
                }
            }
        }
        System.out.println(failures == 0 ? "RESULT: " + runs + " runs, all pass" : "RESULT: " + failures + " of " + runs + " runs FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
