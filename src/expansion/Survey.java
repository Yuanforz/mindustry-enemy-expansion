package expansion;

import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.meta.*;

import static mindustry.Vars.*;

/**
 * What the ground of a map offers, worked out once when the map is loaded: where ore shows in the
 * walls, and where the walls yield sand.
 *
 * The unit is a face: a tile of open ground with such a wall right next to it, and which way the wall
 * lies. Drills and crushers are lined up against faces.
 */
public class Survey{
    private final ObjectMap<Item, IntSeq> ore = new ObjectMap<>(), floor = new ObjectMap<>();
    private final IntSeq sand = new IntSeq();
    private int width;

    public void clear(){
        floor.clear();
        ore.clear();
        sand.clear();
    }

    public boolean ready(){
        return width == world.width() && (ore.size > 0 || sand.size > 0);
    }

    /** Looks over the whole map. */
    public void scan(){
        clear();
        width = world.width();
        for(Tile tile : world.tiles){
            if(!ground(tile)) continue;
            //ore in the floor, for drills that stand on it
            Item under = tile.drop();
            if(under != null) floor.get(under, IntSeq::new).add(tile.pos());
            for(int d = 0; d < 4; d++){
                Tile wall = world.tile(tile.x + Geometry.d4x(d), tile.y + Geometry.d4y(d));
                if(wall == null || !wall.block().isStatic()) continue;
                Item drop = wall.wallDrop();
                if(drop != null) ore.get(drop, IntSeq::new).add(face(tile.x, tile.y, d));
                //a wall with ore in it is for a drill: a crusher put against it would take the face a drill could mine from
                if(drop == null && wall.block().attributes.get(Attribute.sand) > 0f) sand.add(face(tile.x, tile.y, d));
            }
        }
    }

    /** Open ground something could be built on, whatever stands there at the moment. */
    public static boolean ground(Tile tile){
        return !tile.block().isStatic() && tile.floor().placeableOn && !tile.floor().isDeep() && !tile.floor().solid;
    }

    private int face(int x, int y, int d){
        return (x + y * width) * 4 + d;
    }

    public int x(int face){
        return face / 4 % width;
    }

    public int y(int face){
        return face / 4 / width;
    }

    public static int direction(int face){
        return face % 4;
    }

    /** The faces where an item shows in the wall. */
    public IntSeq ore(Item item){
        return ore.get(item, IntSeq::new);
    }

    /** The items that show in a wall somewhere on the map. */
    public Seq<Item> ores(){
        Seq<Item> out = new Seq<>();
        for(var entry : ore){
            if(entry.value.size > 0) out.add(entry.key);
        }
        return out;
    }

    /** The tiles of open ground with an item in the floor, as packed positions. */
    public IntSeq floor(Item item){
        return floor.get(item, IntSeq::new);
    }

    /** The faces where the wall yields sand. */
    public IntSeq sand(){
        return sand;
    }
}
