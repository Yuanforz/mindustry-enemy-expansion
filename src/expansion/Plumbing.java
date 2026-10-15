package expansion;

import arc.math.geom.*;
import arc.struct.*;
import expansion.Blueprint.*;
import expansion.Rates.*;
import mindustry.type.*;

/**
 * What a layout needs and gives in the way of liquids, as far as its own blocks do not settle it
 * between themselves.
 *
 * Blocks that touch hand liquid over directly: a condenser standing against an electrolyzer feeds it
 * with no conduit at all, which is how players build them. Only what is left over, on either side,
 * has to be piped.
 */
public class Plumbing{
    /** A liquid one block of a layout wants from outside, or has to spare. */
    public static class Tap{
        public final Part part;
        public final Liquid liquid;
        /** Units a second. */
        public float rate;
        /** For what is given: the side of the block it leaves on, 0 to 3, or -1 for any. */
        public final int side;

        Tap(Part part, Liquid liquid, float rate, int side){
            this.part = part;
            this.liquid = liquid;
            this.rate = rate;
            this.side = side;
        }
    }

    /** Whether one block hands a liquid it makes straight to another that stands against it. */
    private static boolean hands(Part from, Flow gives, Part to){
        int side = Shape.touching(from, to);
        return side != -1 && (gives.side == -1 || (gives.side + from.rotation) % 4 == side);
    }

    /**
     * What the blocks of a layout need piped to them. Where a container or a liquid router of the
     * layout stands against the block that needs it, the liquid is piped to that instead: it hands
     * it on by touch, and keeps a store of it. Piped straight to the block, the container would stay empty.
     */
    public static Seq<Tap> needs(Seq<Part> parts){
        Seq<Tap> out = new Seq<>();
        for(Part part : parts){
            for(Flow need : Rates.liquidNeeds(part.block)){
                boolean met = false;
                for(Part other : parts){
                    if(other == part) continue;
                    for(Flow gives : Rates.liquidGives(other.block)){
                        if(gives.liquid == need.liquid && hands(other, gives, part)) met = true;
                    }
                }
                if(met) continue;
                Part into = part;
                for(Part other : parts){
                    if(other != part && store(other.block) && Shape.touching(other, part) != -1) into = other;
                }
                Part entry = into;
                Tap same = out.find(tap -> tap.part == entry && tap.liquid == need.liquid);
                if(same != null) same.rate += need.rate;
                else out.add(new Tap(into, need.liquid, need.rate, -1));
            }
        }
        return out;
    }

    /** Whether a block only holds liquid and hands it to whatever stands against it. */
    public static boolean store(mindustry.world.Block block){
        return block instanceof mindustry.world.blocks.liquid.LiquidRouter;
    }

    /** What the blocks of a layout make beyond what the blocks standing against them use. */
    public static Seq<Tap> gives(Seq<Part> parts){
        Seq<Tap> out = new Seq<>();
        for(Part part : parts){
            for(Flow gives : Rates.liquidGives(part.block)){
                float left = gives.rate;
                for(Part other : parts){
                    if(other == part || !hands(part, gives, other)) continue;
                    for(Flow need : Rates.liquidNeeds(other.block)){
                        if(need.liquid == gives.liquid) left -= need.rate;
                    }
                }
                if(left > 0.01f) out.add(new Tap(part, gives.liquid, left, gives.side == -1 ? -1 : (gives.side + part.rotation) % 4));
            }
        }
        return out;
    }

    /**
     * The places a conduit could leave a block from to carry off a liquid: open tiles along the side
     * it comes out of, or along any side. A conduit there has to point straight away from the block.
     * @return x, y and the way the conduit faces, for each
     */
    public static Seq<int[]> outlets(Part part, int side, Ground ground){
        Seq<int[]> out = new Seq<>();
        IntSeq tiles = new IntSeq();
        for(int s = 0; s < 4; s++){
            if(side != -1 && s != side) continue;
            Shape.along(part, s, tiles);
            for(int i = 0; i < tiles.size; i++){
                int x = Point2.x(tiles.get(i)), y = Point2.y(tiles.get(i));
                if(ground.open(x, y)) out.add(new int[]{x, y, s});
            }
        }
        return out;
    }
}
