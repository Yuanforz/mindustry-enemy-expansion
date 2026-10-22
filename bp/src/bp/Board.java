package bp;

import mindustry.*;
import mindustry.world.*;
import mindustry.world.blocks.liquid.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.sandbox.*;
import mindustry.world.blocks.storage.*;

import java.util.*;

/**
 * The map as the layout sees it, and the game's rules for what passes between neighbours, as the game's code has them
 * (source v160.7), for a block holding nothing:
 * <ul>
 * <li>a machine hands what it makes to every neighbour that takes it (Building.dump, round them all); a liquid only out of
 * the side its crafter puts it out of, if it has one (GenericCrafter.liquidOutputDirections); it takes what it uses from
 * anything that hands it;
 * <li>a duct hands on only ahead and takes from behind and the sides (Duct.acceptItem); an armoured duct takes from the
 * sides only what a duct pointing into it hands it; a duct router takes only from behind and hands ahead and to the sides
 * (DuctRouter); a duct bridge takes like a duct if it reaches another, hands to the bridge it reaches (the first of its kind
 * ahead within its range, DirectionBridge.findLink), and that one, reaching none, hands ahead and takes from no neighbour;
 * <li>a surge conveyor line: its first tile takes from behind and the sides, from anything (its loading dock); the rest
 * take nothing from about them; the last hands ahead (StackConveyor, outputRouter off; the states as the game works them
 * out, read off the game for each case);
 * <li>a reinforced conduit takes from a conduit, a bridge or a junction on any side but its front, and from anything else
 * only from behind (ArmoredConduit); a reinforced bridge conduit like a duct bridge;
 * <li>power: a block touching another is on its grid unless both only use power (Building.getPowerConnections); a beam
 * node reaches along each axis the first block with power within its range, other than a power node (BeamNode).
 * </ul>
 * Where a block can stand is the game's own Build.validPlace on the bare map.
 */
public final class Board{
    public enum Kind{
        duct(Carriers.Role.duct), armored(Carriers.Role.armored), router(Carriers.Role.router), bridgeIn(Carriers.Role.bridge), bridgeOut(Carriers.Role.bridge),
        surge(Carriers.Role.surge), conduit(Carriers.Role.conduit), lbridgeIn(Carriers.Role.liquidBridge), lbridgeOut(Carriers.Role.liquidBridge),
        lrouter(Carriers.Role.liquidRouter), node(Carriers.Role.node), power(Carriers.Role.powerSource),
        /** A payload conveyor (3x3 on Erekir), its tile the centre. */
        payload(Carriers.Role.payload),
        /** The empty tile a drain's last conduit leaks onto (no block; kept empty). */
        leak(null);

        public final Carriers.Role role;

        Kind(Carriers.Role role){
            this.role = role;
        }
    }

    /** A block the routing puts down. */
    public static final class Piece{
        public Kind kind;
        public final int x, y, rot;
        public final int net;
        public final String res;
        /** For a surge conveyor: 0 the loading dock, 1 between, 2 the last. */
        public int role;
        /** The direction it is fed from (from the feeder to it; -1 none), and the directions it is meant to hand to (bits). */
        public int from = -1, outs;
        /** The game's block it is (of the kind, as Carriers found them). */
        public Block block;

        public Piece(Kind kind, Block block, int x, int y, int rot, int net, String res){
            this.kind = kind;
            this.block = block;
            this.x = x;
            this.y = y;
            this.rot = rot;
            this.net = net;
            this.res = res;
        }

        public boolean itemCarrier(){
            return kind == Kind.duct || kind == Kind.armored || kind == Kind.router || kind == Kind.bridgeIn || kind == Kind.bridgeOut || kind == Kind.surge;
        }

        public boolean liquidCarrier(){
            return kind == Kind.conduit || kind == Kind.lbridgeIn || kind == Kind.lbridgeOut || kind == Kind.lrouter;
        }

        @Override
        public String toString(){
            return kind + "@" + x + "," + y + ">" + rot;
        }
    }

    public final int w, h;
    public final Spec ground;
    /** Parts of bodies on each tile (null: none). */
    public final Body.Part[] partAt;
    /**
     * Tiles no solid block may stand on (Block.solid): a beam drill's beams pass over ducts, conduits and routers and stop
     * at the first solid tile (BeamDrill.updateFacing, Tile.solid), and units come out of a yard over what is not solid.
     */
    public final boolean[] clear;
    /** The goals that go to the core: an item the core takes of these is no harm, of others a loss. */
    public final Set<String> goals = new HashSet<>();

    public final Carriers carriers;

    public Board(Spec ground, Carriers carriers){
        this.ground = ground;
        this.carriers = carriers;
        w = ground.width();
        h = ground.height();
        partAt = new Body.Part[w * h];
        clear = new boolean[w * h];
        //(the bare map in the game's world, for asking where a block can stand)
        Spec bare = new Spec();
        bare.rows.addAll(ground.rows);
        new Scratch(bare);
        solidGround = new boolean[w * h];
        floorAt = new mindustry.world.blocks.environment.Floor[w * h];
        for(int y = 0; y < h; y++) for(int x = 0; x < w; x++){
            solidGround[idx(x, y)] = Vars.world.tile(x, y).solid();
            floorAt[idx(x, y)] = Vars.world.tile(x, y).floor();
        }
    }

    /** The floor of each tile. */
    public final mindustry.world.blocks.environment.Floor[] floorAt;

    /** Tiles of the bare map that are solid (walls). */
    public final boolean[] solidGround;

    /**
     * The liquid a part must be rid of all the time, whether or not anything takes it (a generator that blows up when
     * full of what it makes, ConsumeGenerator.explodeOnFull), or null.
     */
    public static String mustDrain(Body.Part p){
        if(p.block instanceof mindustry.world.blocks.power.ConsumeGenerator g && g.explodeOnFull && g.outputLiquid != null) return g.outputLiquid.liquid.name;
        return null;
    }

    public boolean in(int x, int y){
        return x >= 0 && y >= 0 && x < w && y < h;
    }

    public int idx(int x, int y){
        return y * w + x;
    }

    /** Whether a block can stand with its lowest corner here, so turned, on the bare map (the game's Build.validPlace). */
    final Map<Block, boolean[][]> validBy = new IdentityHashMap<>();

    public boolean canStand(Block b, int x0, int y0, int rot){
        if(x0 < 0 || y0 < 0 || x0 + b.size > w || y0 + b.size > h) return false;
        int ax = Geo.at(x0, b.size), ay = Geo.at(y0, b.size);
        //(only blocks whose standing depends on their turn are asked by turn: drills facing ore)
        int r = b.rotate && (b instanceof BeamDrill || b instanceof WallCrafter) ? rot : 0;
        boolean[][] byRot = validBy.computeIfAbsent(b, k -> new boolean[4][]);
        boolean[] v = byRot[r];
        if(v == null){
            v = byRot[r] = new boolean[w * h];
            for(int y = 0; y < h; y++) for(int x = 0; x < w; x++) v[idx(x, y)] = mindustry.world.Build.validPlace(b, Scratch.team, x, y, r, false, false);
        }
        return v[idx(ax, ay)];
    }

    /** Whether a one-tile carrier can stand here (floor, not deep, no wall). */
    boolean[] floorOk;

    public boolean floor(int x, int y){
        if(!in(x, y)) return false;
        if(floorOk == null){
            floorOk = new boolean[w * h];
            Block duct = carriers.of(Carriers.Role.duct).get(0);
            for(int yy = 0; yy < h; yy++) for(int xx = 0; xx < w; xx++) floorOk[idx(xx, yy)] = canStand(duct, xx, yy, 0);
        }
        return floorOk[idx(x, y)];
    }

    /** Marks the ground the bodies keep clear (their areas) as no place for a solid block. */
    public void putClear(Collection<Body> bodies){
        Arrays.fill(clear, false);
        for(Body b : bodies) for(int[] r : b.clearWorld) for(int y = Math.max(0, r[1]); y <= Math.min(h - 1, r[3]); y++) for(int x = Math.max(0, r[0]); x <= Math.min(w - 1, r[2]); x++) clear[idx(x, y)] = true;
    }

    public void putParts(Collection<Body> bodies){
        Arrays.fill(partAt, null);
        for(Body b : bodies) for(Body.Part p : b.parts) for(int y = p.y0; y <= p.y1(); y++) for(int x = p.x0; x <= p.x1(); x++) if(in(x, y)) partAt[idx(x, y)] = p;
    }

    // ------------------------------------------------------------------ what blocks are and do

    public static boolean isCore(Body.Part p){
        return p.block instanceof CoreBlock;
    }

    /** Items a part hands to its neighbours. */
    public static List<String> itemsOut(Body.Part p){
        List<String> out = new ArrayList<>();
        if(p.block instanceof ItemSource){
            if(p.config != null) out.add(p.config);
            return out;
        }
        if(p.recipe == null) return out;
        for(String k : p.recipe.out.keySet()) if(Vars.content.item(k) != null) out.add(k);
        for(String k : p.recipe.fixedOut.keySet()) if(Vars.content.item(k) != null && !out.contains(k)) out.add(k);
        return out;
    }

    /** Liquids a part hands to a neighbour in this direction from it. */
    public static List<String> liquidsOut(Body.Part p, int dir){
        List<String> out = new ArrayList<>();
        if(p.block instanceof LiquidSource){
            if(p.config != null) out.add(p.config);
            return out;
        }
        if(p.recipe == null) return out;
        if(p.block instanceof GenericCrafter g && g.outputLiquids != null){
            for(int i = 0; i < g.outputLiquids.length; i++){
                int d = g.liquidOutputDirections.length > i ? g.liquidOutputDirections[i] : -1;
                if(d == -1 || (d + p.rot) % 4 == dir) out.add(g.outputLiquids[i].liquid.name);
            }
            return out;
        }
        for(String k : p.recipe.out.keySet()) if(Vars.content.liquid(k) != null) out.add(k);
        return out;
    }

    /** Whether a thing is carried as a payload: a unit or a block, by name. */
    public static boolean isPayload(String res){
        return Vars.content.item(res) == null && Vars.content.liquid(res) == null && (Vars.content.unit(res) != null || Vars.content.block(res) != null);
    }

    /** The tile a part hands its payload into: ahead of its centre (PayloadBlock.moveOutPayload, Building.front()). */
    public static int[] front(Body.Part p){
        int t = p.size / 2 + 1;
        return new int[]{p.ax() + Geo.dx(p.rot) * t, p.ay() + Geo.dy(p.rot) * t};
    }

    /** Whether a part takes a payload from a block on this tile against it: a refabricator not from ahead of it (Reconstructor.acceptPayload). */
    public static boolean takesPayloadFrom(Body.Part to, int x, int y){
        int side = x > to.x1() ? 0 : y > to.y1() ? 1 : x < to.x0 ? 2 : 3;
        if(to.block instanceof mindustry.world.blocks.units.Reconstructor) return side != to.rot;
        return true;
    }

    /** Whether a part takes this (item or liquid) from a neighbour. */
    public boolean partTakes(Body.Part p, String res){
        if(isCore(p)) return Vars.content.item(res) != null;
        if(p.block instanceof LiquidVoid) return Vars.content.liquid(res) != null;
        if(p.recipe == null) return false;
        return p.recipe.in.containsKey(res) || p.recipe.fixedIn.containsKey(res);
    }

    /** Whether a part is a block that holds and hands items as a duct does (for an armoured duct's sides). */
    static boolean ductLike(Piece q){
        return q.kind == Kind.duct || q.kind == Kind.armored || q.kind == Kind.bridgeIn || q.kind == Kind.bridgeOut;
    }

    static int ox(int d){
        return Geo.dx(d);
    }

    static int oy(int d){
        return Geo.dy(d);
    }

    /** The tiles a piece hands to (items or liquids), as directions from it; a bridge's start hands to its end only. */
    public static int[] handsDirs(Piece q){
        return switch(q.kind){
            case duct, armored, bridgeOut, conduit, lbridgeOut -> new int[]{q.rot};
            case router -> new int[]{q.rot, (q.rot + 1) % 4, (q.rot + 3) % 4};
            //(a liquid router hands to all about it, LiquidRouter: dumpLiquid)
            case lrouter -> new int[]{0, 1, 2, 3};
            case surge -> q.role == 2 ? new int[]{q.rot} : new int[0];
            default -> new int[0];
        };
    }

    /**
     * Whether a piece takes what comes from its neighbour in direction dir (from it), that neighbour being a piece
     * (src) or a part (srcPart).
     */
    public static boolean pieceTakes(Piece q, int dir, Piece src, Body.Part srcPart){
        int back = (q.rot + 2) % 4;
        switch(q.kind){
            case duct, bridgeIn, lbridgeIn:
                return dir != q.rot;
            case armored:
                if(dir == back) return true;
                return src != null && ductLike(src) && (src.x + ox(src.rot) == q.x && src.y + oy(src.rot) == q.y);
            case router:
                return dir == back;
            case lrouter:
                return true;
            case surge:
                return q.role == 0 && dir != q.rot;
            case conduit:
                if(dir == q.rot) return false;
                if(dir == back) return true;
                return src != null && src.liquidCarrier();
            default:
                return false;
        }
    }
}
