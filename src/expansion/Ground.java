package expansion;

import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import expansion.Blueprint.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.world.blocks.ConstructBlock.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.production.*;

import static mindustry.Vars.*;

/**
 * What stands, and what is about to stand, on every tile, and whether something new may go there.
 *
 * Planning happens ahead of building: a whole group of blocks and the ducts between them is worked out
 * before the first of them goes up. So everything here answers for the map as it will be, with what
 * has been planned counted as if it stood already.
 */
public class Ground{
    /** A rule about where the enemy may build. */
    public interface Rule{
        boolean allows(int x, int y);
    }

    public Team team = Team.derelict;
    /** Ground the enemy keeps off altogether: outside its own side, and where the map's scripts do things. */
    public Rule rule = (x, y) -> true;
    /** Ground it keeps clear of what units can't walk over. Ducts may cross it. */
    public Rule solidRule = (x, y) -> true;
    /** Tiles that are spoken for without anything standing on them, such as the way a drill's beam goes. */
    public final IntSet kept = new IntSet();
    /**
     * Ducts of its own that the line being worked out may be laid over again: the last few before the
     * place where a line was cut, so that it can turn there. They count as open ground meanwhile.
     */
    public final IntSet relaid = new IntSet();
    private final IntMap<Part> planned = new IntMap<>();
    /**
     * Ore in the floor that it has no source of yet. Nothing goes on it but the drill that is to
     * stand there: a base that grows over the only ore of a kind it has can never mine it.
     */
    public final IntSet ore = new IntSet();
    /** Whether what is being worked out is a drill for ore in the floor, with its lines: that may go on the ore. */
    public boolean drilling;
    /**
     * Vents. Power is made on them and nowhere else, and there are few: nothing goes on one but what
     * makes use of it. A line laid across the only free vent near a base leaves that base short of
     * power for good.
     */
    public final IntSet vent = new IntSet();
    private boolean venting;
    /**
     * A way home kept open from each such ore to a core: the line from the drill that will one day
     * stand there runs along it. Other lines may bridge across it; nothing is put down on it. A base
     * that grows without leaving that way open finds, when it comes to mine the ore, that there is no
     * way left to bring it home.
     */
    public final IntSet lane = new IntSet();
    /** Whether what is being laid is the line home from such a drill: that, and nothing else of the drill's, runs along the way kept open for it. */
    public boolean homing;
    /** Whether a line is being laid at the moment. Places where putting a block came to nothing are no bar to a line passing over them. */
    public boolean laying;

    //what look() found
    public Block block;
    public int rotation, originX, originY;

    public void clear(){
        planned.clear();
        kept.clear();
        relaid.clear();
    }

    /** Counts a block as standing from now on. */
    public void plan(Part part){
        Tile tile = world.tile(part.x, part.y);
        if(tile != null) tile.getLinkedTilesAs(part.block, other -> planned.put(other.pos(), part));
    }

    public void plan(Seq<Part> parts){
        for(Part part : parts) plan(part);
    }

    /** Takes back what was only being tried out. */
    public void unplan(Seq<Part> parts){
        for(Part part : parts){
            Tile tile = world.tile(part.x, part.y);
            if(tile != null) tile.getLinkedTilesAs(part.block, other -> planned.remove(other.pos()));
        }
    }

    public @Nullable Part planned(int x, int y){
        return planned.get(Point2.pack(x, y));
    }

    /**
     * Looks at what stands or will stand on a tile, and leaves it in {@link #block}, {@link #rotation},
     * {@link #originX} and {@link #originY}. Something planned counts before what stands there now: it will replace it.
     * @return whether there is anything of this team's
     */
    public boolean look(int x, int y){
        Part part = planned.get(Point2.pack(x, y));
        if(part != null){
            block = part.block;
            rotation = part.rotation;
            originX = part.x;
            originY = part.y;
            return true;
        }
        Building build = world.build(x, y);
        if(build == null || build.team != team) return false;
        block = build instanceof ConstructBuild site ? site.current : build.block;
        rotation = build.rotation;
        originX = build.tileX();
        originY = build.tileY();
        return true;
    }

    /** Whether a tile is empty ground the enemy may put something on. */
    public boolean open(int x, int y){
        Tile tile = world.tile(x, y);
        int pos = Point2.pack(x, y);
        //nothing goes on deep liquid, or on floor that is not for building on
        if(tile == null) return false;
        if((!drilling && ore.contains(pos)) || (!homing && lane.contains(pos)) || (!venting && vent.contains(pos))) return false;
        if(relaid.contains(pos)) return world.isInMapArea(x, y) && rule.allows(x, y);
        return tile.build == null && !tile.solid() && !tile.floor().isDeep() && tile.floor().placeableOn
            && !planned.containsKey(pos) && !kept.contains(pos) && world.isInMapArea(x, y) && rule.allows(x, y);
    }

    /**
     * Whether a unit that has just been built could move onto a tile: nothing solid stands or is to
     * stand there. A duct or a conduit is no bar: units walk over those.
     */
    public boolean passable(int x, int y){
        Tile tile = world.tile(x, y);
        if(tile == null || !world.isInMapArea(x, y) || tile.floor().isDeep() || tile.floor().solid) return false;
        Part part = planned.get(Point2.pack(x, y));
        if(part != null) return !part.block.solid;
        return tile.build == null ? !tile.solid() : !tile.build.block.solid;
    }

    /**
     * Whether what stands on a tile is a wall of the enemy's own, which it would take down to let a
     * line through. Cores are often walled in all the way round.
     */
    public boolean removable(int x, int y){
        Building build = world.build(x, y);
        return build != null && build.team == team && build.block instanceof mindustry.world.blocks.defense.Wall
            && !(build.block instanceof mindustry.world.blocks.defense.AutoDoor) && !planned.containsKey(Point2.pack(x, y))
            && !kept.contains(Point2.pack(x, y)) && world.isInMapArea(x, y) && rule.allows(x, y);
    }

    /** Whether every tile a block would stand on is open. */
    public boolean clear(Block block, int x, int y){
        int offset = -(block.size - 1) / 2;
        boolean before = venting;
        venting = usesVent(block);
        try{
            for(int dx = 0; dx < block.size; dx++){
                for(int dy = 0; dy < block.size; dy++){
                    if(!open(x + dx + offset, y + dy + offset) || (block.solid && !solidRule.allows(x + dx + offset, y + dy + offset))) return false;
                }
            }
            return true;
        }finally{
            venting = before;
        }
    }

    /** Whether a block is one that gets something out of a vent it stands on. */
    public static boolean usesVent(Block block){
        return block instanceof mindustry.world.blocks.power.ThermalGenerator
            || (block instanceof AttributeCrafter crafter && crafter.attribute == mindustry.world.meta.Attribute.steam);
    }

    /** Whether a block can go here: the ground is open, and the game allows it. */
    public boolean fits(Block block, int x, int y, int rotation){
        return world.tile(x, y) != null && clear(block, x, y) && Build.validPlace(block, team, x, y, rotation);
    }

    /**
     * Whether what stands or is planned on one tile would push items into a duct on a tile next to it.
     * Ducts and bridges push only to their front, routers to everything but their back, and anything
     * that makes items hands them to whatever next to it takes them.
     */
    public boolean pushesInto(int fromX, int fromY, int toX, int toY){
        if(!look(fromX, fromY)) return false;
        Block source = block;
        if(source instanceof Duct || source instanceof DuctBridge || source instanceof DirectionalUnloader || source instanceof StackConveyor){
            return fromX + Geometry.d4x(rotation) == toX && fromY + Geometry.d4y(rotation) == toY;
        }
        if(source instanceof DuctRouter || source instanceof OverflowDuct || source instanceof StackRouter){
            return !(fromX - Geometry.d4x(rotation) == toX && fromY - Geometry.d4y(rotation) == toY);
        }
        return makesItems(source);
    }

    /** Whether what stands or is planned on one tile is a conduit, or the end of a conduit bridge, that empties into the tile next to it. */
    public boolean pipesInto(int fromX, int fromY, int toX, int toY){
        if(!look(fromX, fromY)) return false;
        if(!(block instanceof mindustry.world.blocks.liquid.Conduit || block instanceof DirectionLiquidBridge)) return false;
        return fromX + Geometry.d4x(rotation) == toX && fromY + Geometry.d4y(rotation) == toY;
    }

    /**
     * Whether a block makes or holds liquid and hands it to what stands next to it. A conduit takes
     * from one of these only when it points straight away from it.
     */
    public static boolean givesLiquid(Block block){
        return (block instanceof GenericCrafter crafter && crafter.outputLiquids != null)
            || (block instanceof mindustry.world.blocks.power.ThermalGenerator thermal && thermal.outputLiquid != null)
            || (block instanceof mindustry.world.blocks.power.ConsumeGenerator burner && burner.outputLiquid != null)
            || block instanceof mindustry.world.blocks.liquid.LiquidRouter || block instanceof mindustry.world.blocks.production.Pump
            || block instanceof mindustry.world.blocks.sandbox.LiquidSource;
    }

    /** Whether a block hands items it has made or mined to whatever stands next to it. */
    public static boolean makesItems(Block block){
        return block instanceof BeamDrill || block instanceof Drill || block instanceof WallCrafter
            || (block instanceof GenericCrafter crafter && crafter.outputItems != null)
            || block instanceof Separator || block instanceof mindustry.world.blocks.distribution.Router || block instanceof Sorter || block instanceof OverflowGate
            || block instanceof mindustry.world.blocks.storage.Unloader || block instanceof mindustry.world.blocks.sandbox.ItemSource;
    }

    /** Whether a block takes items in from ducts. */
    public static boolean takesItems(Block block){
        return block.hasItems && block.acceptsItems;
    }

    /**
     * Whether a duct on this tile would be fed by anything other than what is meant to feed it.
     * A duct takes nothing from the block it faces, so that one does not count.
     * @param facing the way the duct would face
     * @param allowed tiles whose blocks are meant to feed it
     */
    public boolean fedByOthers(int x, int y, int facing, IntSet allowed){
        for(int i = 0; i < 4; i++){
            if(i == facing) continue;
            int ox = x + Geometry.d4x(i), oy = y + Geometry.d4y(i);
            if(allowed.contains(Point2.pack(ox, oy))) continue;
            if(pushesInto(ox, oy, x, y)) return true;
        }
        return false;
    }
}
