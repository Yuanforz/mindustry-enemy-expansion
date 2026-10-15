package expansion;

import arc.math.geom.*;
import arc.util.*;
import mindustry.type.*;
import mindustry.world.*;

import static mindustry.Vars.*;

/** One building a builder has been sent to put up, or take down. */
public class Order{
    public final int x, y, rotation;
    public final Block block;
    public final @Nullable Object config;
    /** Set aside while the order is out. */
    public final ItemStack[] cost;
    /** Part of the enemy's own expansion, as opposed to something of the map's that was destroyed. */
    public final boolean expansion;

    /** Where the builder stops to work on it: within reach, on the side of its own base. */
    public float standX, standY;

    /** Not something to put up: a block of the enemy's own that is in the way of something else and is taken down. */
    public boolean breaking;

    /** The construction site is standing. */
    public boolean begun;
    public boolean built;
    public float age;

    public Order(Block block, int x, int y, int rotation, @Nullable Object config, ItemStack[] cost, boolean expansion){
        this.block = block;
        this.x = x;
        this.y = y;
        this.rotation = rotation;
        this.config = config;
        this.cost = cost;
        this.expansion = expansion;
    }

    public int pos(){
        return Point2.pack(x, y);
    }

    public Tile tile(){
        return world.tile(x, y);
    }
}
