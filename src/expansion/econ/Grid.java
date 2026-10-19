package expansion.econ;

import arc.math.geom.*;
import arc.struct.*;
import expansion.Blueprint.Part;
import mindustry.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.liquid.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.storage.*;

import java.util.*;

/**
 * What stands and what is planned on every tile, shared by the layout modules: where a block may go at all, what is on
 * each tile, which tiles must stay free of anything solid (where drill beams cross, where units leave a factory), which
 * must stay free of anything that would take an item (beside a dock, across from the machine it feeds), which are kept for
 * later (fields to be mined one day), and, for the router, what each thing pushes into the tiles around it.
 *
 * What pushes into what is the game's (reference/mechanics-spec.md, part A and D, and the blocks' own code):
 * <ul>
 * <li>a drill, crusher or crafter hands what it makes to any neighbour that takes it, on all four sides, by turns; a
 * crafter with liquid output directions (the electrolyzer) only out of those sides;</li>
 * <li>a duct or conduit pushes only to its front; a router to its front and sides; an underflow duct to its sides first
 * and its front only when neither side takes; a bridge to its link, or with none to its front;</li>
 * <li>a plain duct takes from behind and from its sides, from anything; an underflow duct or a router only from behind;
 * a bridge only when it has a link, from behind and the sides;</li>
 * <li>a conduit takes from another conduit or bridge behind or beside it, and from anything else only when it points
 * straight away from it; a bridge conduit with a link from anything behind or beside it.</li>
 * </ul>
 */
public final class Grid{
    public final int w, h;
    public final Team team;
    public final Catalog cat;
    /** Ground a block may go on at all: not rock, not deep, in the map's area, not dark, not near an enemy core. */
    final boolean[] ground;
    /** Parts of the plan, by every tile they cover. */
    final Part[] planned;
    /** For pieces of line: the net laying them, and what they carry; -1 for none. */
    final int[] net, carry;
    /** Tiles nothing solid may go on: where a drill's beam crosses, where units come out of a factory. */
    final boolean[] noSolid;
    /** Tiles nothing that takes items may go on: across a dock from the machine it feeds. */
    final boolean[] quiet;
    /** Tiles kept for later (fields to be mined one day): what it costs a line or a cell to take one. */
    final float[] kept;
    /** Tiles the side has not seen at the start (with fog on): a part there waits until a unit has been to look. */
    final boolean[] dark;
    /** What a machine of the plan or of the map hands out to its neighbours, by its tiles; -1 for nothing. */
    final int[] dumps;
    /** What the map's own ducts carry, as traced from what feeds them. */
    final IntIntMap running = new IntIntMap();
    /** The map's ducts seen carrying something, when the map was watched before planning; null if it was not. */
    public static IntSet live;

    public Grid(Catalog cat, Team team){
        this.cat = cat;
        this.team = team;
        w = Vars.world.width();
        h = Vars.world.height();
        int n = w * h;
        ground = new boolean[n];
        planned = new Part[n];
        net = new int[n];
        carry = new int[n];
        noSolid = new boolean[n];
        quiet = new boolean[n];
        kept = new float[n];
        dark = new boolean[n];
        dumps = new int[n];
        Arrays.fill(net, -1);
        Arrays.fill(carry, -1);
        Arrays.fill(dumps, -1);
        //what the side can build on is worked out without fog: ground not yet seen is built on once a unit has looked
        boolean fog = Vars.state.rules.fog;
        Vars.state.rules.fog = false;
        boolean[] shot = Sites.danger(team);
        for(int y = 0; y < h; y++){
            for(int x = 0; x < w; x++){
                Tile t = Vars.world.tile(x, y);
                int p = x + y * w;
                //(rocks and crystals that stand on the floor are in the way too, unless the game clears them for whatever is built)
                ground[p] = !shot[p] && !t.block().isStatic() && (t.block() == mindustry.content.Blocks.air || t.block().alwaysReplace || t.build != null) && !t.floor().isDeep() && t.floor().placeableOn && Vars.world.isInMapArea(x, y) && Vars.world.getDarkness(x, y) < 3
                    && !Vars.state.teams.anyEnemyCoresWithinBuildRadius(team, t.worldx(), t.worldy()) && ours(t.worldx(), t.worldy());
                if(t.build != null && t.build.team == team && t.build.tile == t){
                    int r = output(t.build.block, t.build);
                    if(r >= 0) t.getLinkedTilesAs(t.build.block, o -> dumps[o.x + o.y * w] = r);
                }
            }
        }
        Vars.state.rules.fog = fog;
        if(fog && Vars.state.rules.staticFog){
            try{
                Vars.fogControl.update();
            }catch(Throwable ignored){
            }
            for(int y = 0; y < h; y++) for(int x = 0; x < w; x++) dark[x + y * w] = !Vars.fogControl.isDiscovered(team, x, y);
        }
        //what the map's drills reach across stays clear
        for(Building b : team.data().buildings){
            if(b instanceof BeamDrill.BeamDrillBuild && b.tile == Vars.world.tile(b.tileX(), b.tileY())) for(int t : beam((BeamDrill)b.block, b.tileX(), b.tileY(), b.rotation)) noSolid[t] = true;
        }
        trace();
    }

    /** With the rule that ground belongs to the nearest core (polygon core protection), whether that core is the side's. */
    boolean ours(float wx, float wy){
        if(!Vars.state.rules.polygonCoreProtection) return true;
        float best = Float.MAX_VALUE;
        Team closest = null;
        for(var data : Vars.state.teams.active){
            if(!data.team.rules().protectCores) continue;
            for(var core : data.cores){
                float d = core.dst2(wx, wy);
                if(d < best){
                    best = d;
                    closest = data.team;
                }
            }
        }
        return closest == null || closest == team;
    }

    public int pos(int x, int y){
        return x + y * w;
    }

    public boolean in(int x, int y){
        return x >= 0 && y >= 0 && x < w && y < h;
    }

    /** The open tiles a beam drill's beams cross to their walls. */
    int[] beam(BeamDrill drill, int x, int y, int rot){
        IntSeq out = new IntSeq();
        Point2 p = new Point2();
        int dx = Geometry.d4x(rot), dy = Geometry.d4y(rot);
        for(int lane = 0; lane < drill.size; lane++){
            drill.nearbySide(x, y, rot, lane, p);
            for(int i = 0; i < drill.range; i++){
                Tile t = Vars.world.tile(p.x + dx * i, p.y + dy * i);
                if(t == null || t.solid()) break;
                out.add(t.x + t.y * w);
            }
        }
        return out.toArray();
    }

    /** What a machine hands out to its neighbours as an item, if one thing: a drill's ore, a crafter's single item. */
    int output(Block block, Building build){
        if(build instanceof BeamDrill.BeamDrillBuild d && d.lastItem != null) return cat.id(d.lastItem.name);
        if(build instanceof Drill.DrillBuild d && d.dominantItem != null) return cat.id(d.dominantItem.name);
        if(block instanceof WallCrafter wc) return cat.id(wc.output.name);
        if(block instanceof GenericCrafter gc && gc.outputItems != null && gc.outputItems.length == 1) return cat.id(gc.outputItems[0].item.name);
        return -1;
    }

    /** Whether nothing stands and nothing is planned here, on ground a block may go on. */
    public boolean free(int x, int y){
        if(!in(x, y)) return false;
        int p = pos(x, y);
        return ground[p] && planned[p] == null && Vars.world.tile(x, y).build == null;
    }

    /** Whether a block could be planned here: every tile free, and nothing solid on a tile that must stay clear. */
    public boolean fits(Block block, int x, int y){
        int off = -(block.size - 1) / 2;
        for(int dx = 0; dx < block.size; dx++){
            for(int dy = 0; dy < block.size; dy++){
                int tx = x + off + dx, ty = y + off + dy;
                if(!free(tx, ty)) return false;
                int p = pos(tx, ty);
                if(block.solid && noSolid[p]) return false;
                if(quiet[p] && takesItems(block)) return false;
            }
        }
        return true;
    }

    /** Puts a part of the plan down. For a machine that hands out an item, which. */
    public void plan(Part p, int netId, int carries, int dumpsRes){
        Vars.world.tile(p.x, p.y).getLinkedTilesAs(p.block, t -> {
            int q = pos(t.x, t.y);
            planned[q] = p;
            net[q] = netId;
            carry[q] = carries;
            if(dumpsRes >= 0) dumps[q] = dumpsRes;
        });
    }

    public void plan(Part p){
        plan(p, -1, -1, -1);
    }

    public void unplan(Part p){
        Vars.world.tile(p.x, p.y).getLinkedTilesAs(p.block, t -> {
            int q = pos(t.x, t.y);
            if(planned[q] == p){
                planned[q] = null;
                net[q] = -1;
                carry[q] = -1;
                dumps[q] = -1;
            }
        });
    }

    public Part planned(int x, int y){
        return in(x, y) ? planned[pos(x, y)] : null;
    }

    /** The building of the side's standing on a tile, if any (another side's counts as nothing of ours). */
    Building standing(int x, int y){
        Building b = Vars.world.build(x, y);
        return b == null || b.team != team ? null : b;
    }

    /** The block on a tile, planned before standing: what will be there. */
    public Block block(int x, int y){
        if(!in(x, y)) return null;
        Part p = planned[pos(x, y)];
        if(p != null) return p.block;
        Building b = standing(x, y);
        return b == null ? null : b.block;
    }

    /** The way the block on a tile faces, planned before standing. */
    public int rotation(int x, int y){
        Part p = planned[pos(x, y)];
        if(p != null) return p.rotation;
        Building b = standing(x, y);
        return b == null ? 0 : b.rotation;
    }

    /** The centre tile of the block on a tile. */
    int originX(int x, int y){
        Part p = planned[pos(x, y)];
        if(p != null) return p.x;
        Building b = standing(x, y);
        return b == null ? x : b.tileX();
    }

    int originY(int x, int y){
        Part p = planned[pos(x, y)];
        if(p != null) return p.y;
        Building b = standing(x, y);
        return b == null ? y : b.tileY();
    }

    /** What a line piece on a tile carries (planned, or a running line of the map's), -1 if none or unknown. */
    public int carries(int x, int y){
        if(!in(x, y)) return -1;
        int p = pos(x, y);
        if(planned[p] != null) return carry[p];
        return running.get(p, -1);
    }

    static boolean isDuct(Block b){
        return b instanceof Duct;
    }

    static boolean isOneWay(Block b){
        return b instanceof OverflowDuct || b instanceof DuctRouter;
    }

    static boolean isConduit(Block b){
        return b instanceof Conduit;
    }

    static boolean isBridge(Block b){
        return b instanceof DirectionBridge;
    }

    static boolean isLine(Block b){
        return isDuct(b) || isOneWay(b) || isConduit(b) || isBridge(b) || b instanceof LiquidRouter || b instanceof LiquidJunction;
    }

    /** Whether a block takes items from what is beside it at all (machines that use some item, the core, line pieces). */
    static boolean takesItems(Block b){
        if(b instanceof CoreBlock || isDuct(b) || isOneWay(b) || b instanceof DuctBridge) return true;
        if(b.hasItems && b.consumers != null) for(var c : b.consumers) if(c instanceof mindustry.world.consumers.ConsumeItems || c instanceof mindustry.world.consumers.ConsumeItemFilter || c instanceof mindustry.world.consumers.ConsumeItemDynamic) return true;
        return false;
    }

    /** Whether a machine takes this item (or liquid) from its neighbours. */
    boolean uses(Block b, int res){
        Res r = cat.get(res);
        if(r.kind == Res.Kind.item){
            if(b instanceof CoreBlock) return r.storable;
            Item item = Vars.content.item(r.name);
            if(item == null) return false;
            //a factory takes what any of its plans needs
            if(b instanceof mindustry.world.blocks.units.UnitFactory uf){
                for(var plan : uf.plans) for(ItemStack s : plan.requirements) if(s.item == item) return true;
                return false;
            }
            return b.consumesItem(item);
        }
        if(r.kind == Res.Kind.liquid){
            Liquid liquid = Vars.content.liquid(r.name);
            return liquid != null && b.hasLiquids && b.consumesLiquid(liquid);
        }
        return false;
    }

    /**
     * What a machine (not a line piece) pours out as liquid on one side (an absolute direction from it), -1 for none.
     * A crafter's liquids go out on every side unless it has directions for them.
     */
    int liquidOut(Block b, int rotation, int side){
        if(b instanceof GenericCrafter gc && gc.outputLiquids != null){
            for(int i = 0; i < gc.outputLiquids.length; i++){
                int dir = gc.liquidOutputDirections.length > i ? gc.liquidOutputDirections[i] : -1;
                if(dir == -1 || (rotation + dir) % 4 == side) return cat.id(gc.outputLiquids[i].liquid.name);
            }
            return -1;
        }
        if(b instanceof ThermalGenerator tg && tg.outputLiquid != null) return cat.id(tg.outputLiquid.liquid.name);
        return -1;
    }

    /**
     * What the thing on tile (fx, fy) pushes into the neighbouring tile (tx, ty), whether that takes it or not: -1 for
     * nothing, else what (-2 when that is not known); only items, or only liquids.
     */
    public int pushes(int fx, int fy, int tx, int ty, boolean liquid){
        if(!in(fx, fy)) return -1;
        Block b = block(fx, fy);
        if(b == null) return -1;
        int p = pos(fx, fy);
        int dir = dirTo(originX(fx, fy), originY(fx, fy), b.size, tx, ty);
        if(dir < 0) return -1;
        int rot = rotation(fx, fy);
        boolean pipe = isConduit(b) || b instanceof LiquidRouter || b instanceof DirectionLiquidBridge || b instanceof LiquidJunction;
        if(isLine(b)){
            if(pipe != liquid) return -1;
            int what = carries(fx, fy), known = what < 0 ? -2 : what;
            if(isDuct(b) || isConduit(b)) return dir == rot ? known : -1;
            if(isOneWay(b)) return dir == (rot + 2) % 4 ? -1 : known;
            if(isBridge(b)) return dir == rot && bridgeLink(fx, fy) == null ? known : -1;
            if(b instanceof LiquidRouter) return known;
            return -1;
        }
        if(b instanceof CoreBlock) return -1;
        return liquid ? liquidOut(b, rot, dir) : dumps[p];
    }

    /** Where a bridge on a tile links to by the game's rule (the first of its kind within range along its facing), or null. */
    int[] bridgeLink(int x, int y){
        Block b = block(x, y);
        if(!(b instanceof DirectionBridge db)) return null;
        int rot = rotation(x, y);
        for(int i = 1; i <= db.range; i++){
            int ox = x + Geometry.d4x(rot) * i, oy = y + Geometry.d4y(rot) * i;
            if(!in(ox, oy)) break;
            if(block(ox, oy) == b) return new int[]{ox, oy};
        }
        return null;
    }

    /** Which side of a block (by its centre and size) a tile just outside it is on, -1 if it is not just outside. */
    static int dirTo(int ox, int oy, int size, int tx, int ty){
        int off = -(size - 1) / 2;
        int x0 = ox + off, y0 = oy + off, x1 = x0 + size - 1, y1 = y0 + size - 1;
        boolean inX = tx >= x0 && tx <= x1, inY = ty >= y0 && ty <= y1;
        if(tx == x1 + 1 && inY) return 0;
        if(ty == y1 + 1 && inX) return 1;
        if(tx == x0 - 1 && inY) return 2;
        if(ty == y0 - 1 && inX) return 3;
        return -1;
    }

    /** The tiles just outside a block's edge, with the side each is on. */
    static List<int[]> around(Part p){
        return around(p.block.size, p.x, p.y);
    }

    static List<int[]> around(int s, int x, int y){
        List<int[]> out = new ArrayList<>();
        int off = -(s - 1) / 2;
        for(int i = 0; i < s; i++){
            out.add(new int[]{x + off + s, y + off + i, 0});
            out.add(new int[]{x + off + i, y + off + s, 1});
            out.add(new int[]{x + off - 1, y + off + i, 2});
            out.add(new int[]{x + off + i, y + off - 1, 3});
        }
        return out;
    }

    /** Every tile a block covers, as x + y * w. */
    int[] tiles(Block block, int x, int y){
        int s = block.size, off = -(s - 1) / 2, k = 0;
        int[] out = new int[s * s];
        for(int dx = 0; dx < s; dx++) for(int dy = 0; dy < s; dy++) out[k++] = pos(x + off + dx, y + off + dy);
        return out;
    }

    int[] tiles(Part p){
        return tiles(p.block, p.x, p.y);
    }

    /** The rows in front of a block where what it makes comes out (units): its width, two deep. */
    int[] front(Block block, int x, int y, int rot, int depth){
        int s = block.size, half = s / 2, k = 0;
        int[] out = new int[s * depth];
        int off = -(s - 1) / 2;
        for(int d = 1; d <= depth; d++){
            for(int a = 0; a < s; a++){
                int across = off + a;
                int fx = x + Geometry.d4x(rot) * (half + d) + Geometry.d4x((rot + 1) % 4) * across;
                int fy = y + Geometry.d4y(rot) * (half + d) + Geometry.d4y((rot + 1) % 4) * across;
                out[k++] = in(fx, fy) ? pos(fx, fy) : -1;
            }
        }
        return out;
    }

    /** Follows the ducts out of each of the side's drills and crafters, to know what every running line carries. */
    void trace(){
        for(Building b : team.data().buildings){
            int res = output(b.block, b);
            if(res < 0 || isLine(b.block)) continue;
            IntQueue queue = new IntQueue();
            for(Building near : b.proximity) if(near.block instanceof Duct && near.front() != b) queue.addLast(near.pos());
            while(!queue.isEmpty()){
                int pos = queue.removeFirst();
                int x = Point2.x(pos), y = Point2.y(pos), p = pos(x, y);
                if(running.containsKey(p)) continue;
                Building at = Vars.world.build(pos);
                if(at == null || at.team != team) continue;
                if(at.block instanceof Duct){
                    //a line that was watched and seen carrying nothing is not counted on (beyond it, nothing is)
                    if(live != null && !live.contains(p)) continue;
                    running.put(p, res);
                    Building front = at.front();
                    if(front != null) queue.addLast(front.pos());
                }else if(at.block instanceof DuctRouter){
                    running.put(p, res);
                    for(Building near : at.proximity) if(near.block instanceof Duct && near.front() != at) queue.addLast(near.pos());
                }
            }
        }
    }
}
