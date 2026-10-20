package expansion;

import arc.math.*;
import arc.struct.*;
import arc.util.*;
import mindustry.core.*;
import mindustry.ctype.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.storage.*;
import mindustry.world.blocks.units.*;
import mindustry.world.consumers.*;

import static mindustry.Vars.*;

/**
 * A group of blocks the enemy puts up together: a row of drills with the duct that collects from them,
 * a furnace with the places its inputs arrive at, a turret behind a row of walls.
 *
 * What gets built is kept apart from where and when. A blueprint only says what the group looks like,
 * with its front along +x, and where it joins up with the rest of a base: its ports. Fitting it to the
 * ground, and laying the ducts and power to its ports, is done elsewhere.
 */
public class Blueprint{
    /** What a blueprint is for. It follows from its main block. */
    public enum Kind{
        /** A turret: faces the player. */
        post,
        /** Drills that mine ore out of a wall: face the wall. */
        mine,
        /** Crushers that take sand out of a wall: face the wall. */
        quarry,
        /** A drill that stands on ore. */
        drill,
        /** A generator. */
        power,
        /** A factory that makes items or liquids out of others. */
        craft,
        /** A unit factory: faces the player, and units leave on that side. */
        yard,
        core,
        /** Something added to a block that already stands: a refabricator in front of a fabricator. */
        addon,
        other
    }

    /** What a port is for. */
    public enum Role{
        /** Items leave here: the tile the first duct of the line away from the blueprint goes on. */
        out,
        /** Items arrive here: the tile the last duct of a line goes on, and the way it has to face. */
        in
    }

    public final String name;
    /** The first part is the main block; the others are positioned relative to it. */
    public final Seq<Part> parts = new Seq<>();
    public final Seq<Port> ports = new Seq<>();
    /** A row: the blueprint may be put down this many times over, each copy this far from the one before. */
    public int stepX, stepY, copies = 1;
    /**
     * Where a liquid that only makes its blocks faster is let in, once there is some to be had: on each
     * of these tiles goes a liquid router, standing against what it is for. Without any, the liquid is
     * piped straight to the blocks that take it.
     */
    public final Seq<Boost> boosts = new Seq<>();

    public static class Boost{
        public final Liquid liquid;
        public final int x, y;

        public Boost(Liquid liquid, int x, int y){
            this.liquid = liquid;
            this.x = x;
            this.y = y;
        }
    }

    public static class Part{
        public final Block block;
        public final int x, y, rotation;
        public final @Nullable Object config;
        /**
         * Whether the blueprint can do without this part: a wall that goes up where there is room for
         * it. Walls are written "?block x y" in a blueprint.
         */
        public boolean optional;
        /**
         * Whether this part is not put up but has to stand already: what an addition is added to.
         * Written "@block x y" as the first part of a blueprint.
         */
        public boolean anchor;

        public Part(Block block, int x, int y, int rotation, @Nullable Object config){
            this.block = block;
            this.x = x;
            this.y = y;
            this.rotation = rotation;
            this.config = config;
        }

        public Part(Block block, int x, int y, int rotation){
            this(block, x, y, rotation, null);
        }
    }

    public static class Port{
        public final Role role;
        public final int x, y, rotation;
        /** The item an input takes. Outputs carry whatever the blueprint makes. */
        public final @Nullable Item item;

        public Port(Role role, int x, int y, int rotation, @Nullable Item item){
            this.role = role;
            this.x = x;
            this.y = y;
            this.rotation = rotation;
            this.item = item;
        }
    }

    /** A blueprint as it would stand at some place: where every part goes, and where its ports end up. */
    public static class Layout{
        public final Blueprint blueprint;
        public final Seq<Part> parts = new Seq<>();
        public final Seq<Port> ports = new Seq<>();
        /** Where liquid routers go to let in a liquid that makes it faster, for every copy. */
        public final Seq<Boost> boosts = new Seq<>();
        /** How many copies of a row this is. */
        public int copies = 1;
        /** How it was turned. */
        public int turns;
        public boolean mirrored;

        Layout(Blueprint blueprint){
            this.blueprint = blueprint;
        }

        public Part main(){
            return parts.first();
        }

        public @Nullable Port port(Role role, @Nullable Item item){
            return ports.find(port -> port.role == role && (item == null || port.item == item));
        }
    }

    Blueprint(String name){
        this.name = name;
    }

    /**
     * Reads blueprints from text. A blueprint is a [name] line followed by one line for each part,
     * "block x y [rotation] [setting]", and optionally
     * "row dx dy most" for a blueprint that can be repeated,
     * "out x y" for where items leave, and "in item x y rotation" for where they arrive.
     */
    public static Seq<Blueprint> parse(String text){
        Seq<Blueprint> out = new Seq<>();
        Blueprint current = null;
        boolean broken = false;

        for(String raw : text.split("\n")){
            int comment = raw.indexOf('#');
            String line = (comment >= 0 ? raw.substring(0, comment) : raw).trim();
            if(line.isEmpty()) continue;

            if(line.startsWith("[") && line.endsWith("]")){
                current = new Blueprint(line.substring(1, line.length() - 1).trim());
                broken = false;
                out.add(current);
                continue;
            }
            if(current == null || broken) continue;

            String problem = current.read(line.split("\\s+"));
            if(problem != null){
                Log.warn("[EnemyExpansion] Blueprint @ is left out: @ (\"@\").", current.name, problem, line);
                out.remove(current);
                broken = true;
            }
        }

        out.removeAll(blueprint -> blueprint.parts.isEmpty());
        return out;
    }

    /** @return what is wrong with the line, or null */
    private @Nullable String read(String[] args){
        switch(args[0]){
            case "row" -> {
                if(args.length < 4 || !ints(args, 1, 3)) return "a row is \"row dx dy most\"";
                stepX = Strings.parseInt(args[1]);
                stepY = Strings.parseInt(args[2]);
                copies = Math.max(Strings.parseInt(args[3]), 1);
                return null;
            }
            case "out" -> {
                if(args.length < 3 || !ints(args, 1, 2)) return "an output is \"out x y\"";
                ports.add(new Port(Role.out, Strings.parseInt(args[1]), Strings.parseInt(args[2]), 0, null));
                return null;
            }
            case "boost" -> {
                if(args.length < 4 || !ints(args, 2, 2)) return "a place to let a liquid in is \"boost liquid x y\"";
                Liquid liquid = content.liquid(args[1]);
                if(liquid == null) return "there is no liquid called " + args[1];
                boosts.add(new Boost(liquid, Strings.parseInt(args[2]), Strings.parseInt(args[3])));
                return null;
            }
            case "in" -> {
                if(args.length < 5 || !ints(args, 2, 3)) return "an input is \"in item x y rotation\"";
                Item item = content.item(args[1]);
                if(item == null) return "there is no item called " + args[1];
                ports.add(new Port(Role.in, Strings.parseInt(args[2]), Strings.parseInt(args[3]), Strings.parseInt(args[4]), item));
                return null;
            }
        }

        boolean optional = args[0].startsWith("?"), anchor = args[0].startsWith("@");
        if(optional || anchor) args[0] = args[0].substring(1);
        if(anchor && !parts.isEmpty()) return "what an addition is added to has to be its first part";
        Block block = content.block(args[0]);
        if(block == null) return "there is no block called " + args[0];
        if(args.length < 3 || !ints(args, 1, 2)) return "a part is \"block x y [rotation] [setting]\"";

        int rotation = args.length > 3 && Strings.canParseInt(args[3]) ? Strings.parseInt(args[3]) : 0;
        Object config = null;
        String setting = args.length > 4 ? args[4] : args.length > 3 && !Strings.canParseInt(args[3]) ? args[3] : null;
        if(setting != null){
            config = content.item(setting);
            if(config == null) config = content.liquid(setting);
            if(config == null) config = content.unit(setting);
            if(config == null) return "there is nothing called " + setting + " to set " + block.name + " to";
        }
        Part part = new Part(block, Strings.parseInt(args[1]), Strings.parseInt(args[2]), rotation, config);
        part.optional = optional;
        part.anchor = anchor;
        parts.add(part);
        return null;
    }

    private static boolean ints(String[] args, int from, int count){
        for(int i = from; i < from + count; i++){
            if(!Strings.canParseInt(args[i])) return false;
        }
        return true;
    }

    /** Whether a turret fires on item ammunition alone, with no power, liquid or heat laid on. */
    public static boolean standsAlone(Turret turret){
        if(!(turret instanceof ItemTurret) || turret.heatRequirement > 0f) return false;
        for(Consume consume : turret.consumers){
            if(!consume.optional && !consume.booster && !(consume instanceof ConsumeItemFilter)) return false;
        }
        return true;
    }

    public Block main(){
        return parts.first().block;
    }

    /** Whether this has a port of a kind, for an item if one is given. */
    public boolean has(Role role, @Nullable Item item){
        return ports.contains(port -> port.role == role && (item == null || port.item == item));
    }

    /** Whether this is added to a block that already stands, not put up on its own. */
    public boolean addon(){
        return parts.first().anchor;
    }

    public Kind kind(){
        Block main = main();
        if(addon()) return Kind.addon;
        return main instanceof BeamDrill ? Kind.mine : main instanceof WallCrafter ? Kind.quarry : main instanceof Drill ? Kind.drill
            : main instanceof PowerGenerator ? Kind.power : main instanceof GenericCrafter ? Kind.craft
            : main instanceof UnitFactory || main instanceof Reconstructor || main instanceof UnitAssembler ? Kind.yard
            : main instanceof BaseTurret ? Kind.post : main instanceof CoreBlock ? Kind.core : Kind.other;
    }

    /** Whether every kind of block in this is among the given ones. */
    public boolean uses(Seq<Block> known){
        return !parts.contains(part -> !known.contains(part.block));
    }

    /** Whether everything this can't do without is something the enemy may build. */
    public boolean allowed(arc.func.Boolf<UnlockableContent> unlocked){
        return !parts.contains(part -> !part.optional && !part.anchor && (!unlocked.get(part.block) || (part.config instanceof UnlockableContent set && !unlocked.get(set))));
    }

    /** What one copy costs, with everything in it. */
    public ItemStack[] cost(){
        ItemSeq total = new ItemSeq();
        for(Part part : parts) if(!part.anchor) total.add(part.block.requirements);
        return ItemStack.mult(total.toArray(), state.rules.buildCostMultiplier);
    }

    /** What the parts it can't do without cost. */
    public ItemStack[] needs(){
        ItemSeq total = new ItemSeq();
        for(Part part : parts) if(!part.optional && !part.anchor) total.add(part.block.requirements);
        return ItemStack.mult(total.toArray(), state.rules.buildCostMultiplier);
    }

    /** The power one copy uses when everything in it runs, less what it makes itself, a tick. */
    public float powerUse(){
        float total = 0f;
        for(Part part : parts){
            if(!part.anchor && part.block.consPower != null && !part.block.consPower.buffered) total += part.block.consPower.usage;
        }
        return total;
    }

    /** The parts as they would stand with the main block at the given tile, turned the given number of quarter turns counter-clockwise from +x. */
    public Seq<Part> at(int x, int y, int turns){
        return at(x, y, turns, false, 1).parts;
    }

    /**
     * The blueprint as it would stand with the main block of its first copy at the given tile.
     * @param turns quarter turns counter-clockwise from +x
     * @param mirrored flipped over before turning, so that what was on the left of the front is on the right
     * @param count how many copies of a row
     */
    public Layout at(int x, int y, int turns, boolean mirrored, int count){
        Layout out = new Layout(this);
        out.copies = Math.max(Math.min(count, copies), 1);
        turns = Mathf.mod(turns, 4);
        out.turns = turns;
        out.mirrored = mirrored;

        for(int copy = 0; copy < out.copies; copy++){
            for(Part part : parts){
                //mirrored, a block that gives off from its sides is turned half round as well, or what it gave off towards its
                //neighbour goes the other way: an electrolyzer whose hydrogen faced the refabricator faced away from it, and every
                //mirrored works stood with its electrolyzer full and still
                int rotation = mirrored && part.block.rotate ? Mathf.mod((sideOutputs(part.block) ? 2 : 0) - part.rotation, 4) : part.rotation;
                Tmp.p1.set(place(part.block, part.x + stepX * copy, part.y + stepY * copy, turns, mirrored));
                Part placed = new Part(part.block, x + Tmp.p1.x, y + Tmp.p1.y, part.block.rotate ? Mathf.mod(rotation + turns, 4) : 0, part.config);
                placed.optional = part.optional;
                placed.anchor = part.anchor;
                out.parts.add(placed);
            }
            for(Boost boost : boosts){
                Tmp.p1.set(place(null, boost.x + stepX * copy, boost.y + stepY * copy, turns, mirrored));
                out.boosts.add(new Boost(boost.liquid, x + Tmp.p1.x, y + Tmp.p1.y));
            }
        }
        //ports are where the last copy has them
        for(Port port : ports){
            int rotation = mirrored ? Mathf.mod(-port.rotation, 4) : port.rotation;
            Tmp.p1.set(place(null, port.x + stepX * (out.copies - 1), port.y + stepY * (out.copies - 1), turns, mirrored));
            out.ports.add(new Port(port.role, x + Tmp.p1.x, y + Tmp.p1.y, Mathf.mod(rotation + turns, 4), port.item));
        }
        return out;
    }

    /**
     * Where a block (or with none, a single tile) ends up relative to the main block. Turned about the
     * middle of the main block, the same way the game turns schematics, so that blocks of even size
     * end up on the right tiles.
     */
    /** Whether a block gives off what it makes from its sides (left and right of where it faces) rather than all round. */
    static boolean sideOutputs(Block block){
        if(System.getProperty("xp.oldmirror") != null) return false;
        if(block instanceof mindustry.world.blocks.production.GenericCrafter crafter && crafter.liquidOutputDirections != null){
            for(int dir : crafter.liquidOutputDirections) if(dir == 1 || dir == 3) return true;
        }
        return false;
    }

    private arc.math.geom.Point2 place(@Nullable Block block, int x, int y, int turns, boolean mirrored){
        float pivot = main().offset, offset = block == null ? 0f : block.offset;
        float px = x * tilesize + offset - pivot, py = y * tilesize + offset - pivot;
        if(mirrored) py = -py;
        for(int i = 0; i < turns; i++){
            float previous = px;
            px = -py;
            py = previous;
        }
        return Tmp.p2.set(World.toTile(px + pivot - offset), World.toTile(py + pivot - offset));
    }
}
