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
        node(Carriers.Role.node), power(Carriers.Role.powerSource);

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
            return kind == Kind.conduit || kind == Kind.lbridgeIn || kind == Kind.lbridgeOut;
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
    final Map<String, boolean[]> valid = new HashMap<>();
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
    }

    public boolean in(int x, int y){
        return x >= 0 && y >= 0 && x < w && y < h;
    }

    public int idx(int x, int y){
        return y * w + x;
    }

    /** Whether a block can stand with its lowest corner here, so turned, on the bare map (the game's Build.validPlace). */
    public boolean canStand(Block b, int x0, int y0, int rot){
        int ax = Geo.at(x0, b.size), ay = Geo.at(y0, b.size);
        if(!in(x0, y0) || !in(x0 + b.size - 1, y0 + b.size - 1)) return false;
        //(only blocks whose standing depends on their turn are asked by turn: drills facing ore)
        int r = b.rotate && (b instanceof BeamDrill || b instanceof WallCrafter) ? rot : 0;
        boolean[] v = valid.computeIfAbsent(b.name + "/" + r, k -> {
            boolean[] out = new boolean[w * h];
            for(int y = 0; y < h; y++) for(int x = 0; x < w; x++) out[idx(x, y)] = mindustry.world.Build.validPlace(b, Scratch.team, x, y, r, false, false);
            return out;
        });
        return v[idx(ax, ay)];
    }

    /** Whether a one-tile carrier can stand here (floor, not deep, no wall). */
    public boolean floor(int x, int y){
        return in(x, y) && canStand(carriers.of(Carriers.Role.duct).get(0), x, y, 0);
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

    /** Whether a part takes this (item or liquid) from a neighbour. */
    public boolean partTakes(Body.Part p, String res){
        if(isCore(p)) return Vars.content.item(res) != null;
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
