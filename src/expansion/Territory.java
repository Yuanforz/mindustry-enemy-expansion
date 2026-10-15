package expansion;

import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.gen.*;
import mindustry.world.*;

import static mindustry.Vars.*;

/**
 * Whose ground is whose, and which way the enemy's army goes.
 *
 * For every tile this knows how far it is over open ground from the nearest enemy core and from the
 * nearest core of the player. Ground nearer to the enemy's cores is the enemy's, and that is the only
 * ground it expands on. Nothing of its can go up on the player's side of the map, because the player's
 * side is never among the places looked at.
 *
 * Going downhill on the distance to the player from an enemy core gives the way its ground units
 * take there. The stretches of that way on the enemy's own ground are where its outposts go.
 */
public class Territory{
    private static final int[] dx = {1, 0, -1, 0}, dy = {0, 1, 0, -1};
    private static final int unreached = Integer.MAX_VALUE;

    private int[] toEnemy = {}, toPlayer = {};
    /**
     * How far along the way from the enemy's cores to the player's the enemy's ground goes: half way,
     * unless the player has set it otherwise.
     */
    public float frontier = 0.5f;
    /**
     * Whether its ground grows with what it builds. Beyond its home ground the enemy may only build
     * right by what it has already put up, within {@link #hold} tiles of it over open ground: so it
     * advances a step at a time, each step resting on the last, and never out of nowhere.
     */
    public boolean creep = true;
    public int hold = 40;
    private int[] toOwn = {};
    private final IntQueue queue = new IntQueue();
    private final Seq<Building> enemyCores = new Seq<>(), playerCores = new Seq<>();
    private int width;

    /** A stretch of the way to the player: one place on it, and how far along it is, from 0 at the enemy's cores to 1 at the player's. */
    public static class Zone{
        public final int x, y;
        public final float advance;

        Zone(int x, int y, float advance){
            this.x = x;
            this.y = y;
            this.advance = advance;
        }
    }

    /** Works everything out again. Takes a moment on a big map, so it is not done often. */
    public void update(Seq<? extends Building> enemy, Seq<? extends Building> player){
        width = world.width();
        enemyCores.set(enemy);
        playerCores.set(player);
        toEnemy = spread(toEnemy, enemy);
        toPlayer = spread(toPlayer, player);
        bridged = spreadBridged(bridged, enemy);
        fromCore.clear();
    }

    /**
     * How far every tile is from its own cores the way a line would be laid: over open ground, and
     * across what a bridge spans where there is open ground again on the other side. Narrow water and
     * thin walls are no end of the ground for a line, though they are for a tank.
     */
    private int[] bridged = {};
    /** How many tiles of what can't be built on a bridge spans. */
    public int bridgeSpan = 3;

    private int[] spreadBridged(int[] previous, Seq<? extends Building> from){
        int height = world.height();
        int[] result = previous.length == width * height ? previous : new int[width * height];
        java.util.Arrays.fill(result, unreached);
        //steps cost 1, a jump across k tiles k + 1: the nearest first, by a queue for each distance
        IntSeq[] buckets = new IntSeq[bridgeSpan + 2];
        for(int i = 0; i < buckets.length; i++) buckets[i] = new IntSeq();
        int pending = 0;
        for(Building build : from){
            int[] count = {0};
            build.tile.getLinkedTiles(tile -> {
                result[tile.x + tile.y * width] = 0;
                buckets[0].add(tile.x + tile.y * width);
                count[0]++;
            });
            pending += count[0];
        }
        for(int d = 0; pending > 0; d++){
            IntSeq bucket = buckets[d % buckets.length];
            while(bucket.size > 0){
                int current = bucket.pop();
                pending--;
                if(result[current] != d) continue;
                int cx = current % width, cy = current / width;
                for(int i = 0; i < 4; i++){
                    for(int k = 0; k <= bridgeSpan; k++){
                        int x = cx + dx[i] * (k + 1), y = cy + dy[i] * (k + 1);
                        if(x < 0 || y < 0 || x >= width || y >= height) break;
                        if(!open(world.tile(x, y))){
                            //what can't be built on is jumped only where a wall of the map does not end the bridge's reach
                            continue;
                        }
                        int next = x + y * width, far = d + k + 1;
                        if(far < result[next]){
                            result[next] = far;
                            buckets[far % buckets.length].add(next);
                            pending++;
                        }
                        break;
                    }
                }
            }
        }
        return result;
    }

    /**
     * The core whose turn it is, when the enemy has several: each in turn sees to the ground around
     * itself, so that all its bases grow at once instead of one after the other. Null when there is
     * only the one.
     */
    public @Nullable Building focus;
    private final IntMap<int[]> fromCore = new IntMap<>();

    /**
     * How near a place is for choosing between places: over open ground from the core whose turn it
     * is, or from the nearest core when none has a turn. What a place may be used for at all is never
     * a matter of whose turn it is; that goes by {@link #fromHome}.
     */
    public int near(int x, int y){
        Building core = focus;
        if(core == null || !core.isValid() || !ready() || x < 0 || y < 0 || x >= width || y >= world.height()) return fromHome(x, y);
        int[] far = fromCore.get(core.pos());
        if(far == null) fromCore.put(core.pos(), far = spread(new int[0], Seq.with(core)));
        int steps = far[x + y * width];
        if(steps == unreached && bridged.length == far.length && bridged[x + y * width] != unreached) return bridged[x + y * width] + 10;
        return steps != unreached ? steps : (Math.abs(core.tile.x - x) + Math.abs(core.tile.y - y)) * 2 + 20;
    }

    public boolean ready(){
        return toEnemy.length == world.width() * world.height() && toEnemy.length > 0;
    }

    /** How far every tile is from the nearest of some buildings, going over open ground only. */
    private int[] spread(int[] previous, Seq<? extends Building> from){
        int height = world.height();
        int[] result = previous.length == width * height ? previous : new int[width * height];
        java.util.Arrays.fill(result, unreached);
        queue.clear();

        for(Building build : from){
            build.tile.getLinkedTiles(tile -> {
                result[tile.x + tile.y * width] = 0;
                queue.addLast(tile.x + tile.y * width);
            });
        }

        while(!queue.isEmpty()){
            int current = queue.removeFirst();
            int cx = current % width, cy = current / width;
            for(int i = 0; i < 4; i++){
                int x = cx + dx[i], y = cy + dy[i];
                if(x < 0 || y < 0 || x >= width || y >= height || result[x + y * width] != unreached || !open(world.tile(x, y))) continue;
                result[x + y * width] = result[current] + 1;
                queue.addLast(x + y * width);
            }
        }
        return result;
    }

    /** Buildings count as open ground: they come and go. */
    private static boolean open(Tile tile){
        return world.isInMapArea(tile.x, tile.y) && !tile.floor().solid && !tile.floor().isDeep() && !(tile.build == null && tile.block().solid);
    }

    /**
     * Whether a tile is ground the enemy may build on: its home ground, and what it has advanced to
     * from there. For home ground, where there is a way over land to both sides' cores the shorter
     * way decides; ground only one side can walk to is that side's; and anything else, such as an
     * island, goes by the distance as the crow flies.
     */
    public boolean ours(int x, int y){
        if(!ready() || x < 0 || y < 0 || x >= width || y >= world.height()) return false;
        return home(x, y) || (creep && toOwn.length == toEnemy.length && toOwn[x + y * width] <= hold);
    }

    /**
     * Marks out again the ground near what the enemy has standing. Cheap: it only looks as far from each
     * building as a step may go.
     */
    public void claim(Seq<Building> own){
        if(!ready()) return;
        int height = world.height();
        if(toOwn.length != width * height) toOwn = new int[width * height];
        java.util.Arrays.fill(toOwn, unreached);
        queue.clear();
        for(Building build : own){
            build.tile.getLinkedTiles(tile -> {
                toOwn[tile.x + tile.y * width] = 0;
                queue.addLast(tile.x + tile.y * width);
            });
        }
        while(!queue.isEmpty()){
            int current = queue.removeFirst();
            if(toOwn[current] >= hold) continue;
            int cx = current % width, cy = current / width;
            for(int i = 0; i < 4; i++){
                int x = cx + dx[i], y = cy + dy[i];
                if(x < 0 || y < 0 || x >= width || y >= height || toOwn[x + y * width] != unreached || !open(world.tile(x, y))) continue;
                toOwn[x + y * width] = toOwn[current] + 1;
                queue.addLast(x + y * width);
            }
        }
    }

    /** Whether a tile is on the enemy's home ground: its side of the line between its cores and the player's. */
    public boolean home(int x, int y){
        if(!ready() || x < 0 || y < 0 || x >= width || y >= world.height()) return false;
        int enemy = toEnemy[x + y * width], player = toPlayer[x + y * width];
        if(enemy != unreached && player != unreached) return enemy * (1f - frontier) < player * frontier;
        if(enemy != unreached) return true;
        if(player != unreached) return false;

        Building nearEnemy = Geometry.findClosest(x * tilesize, y * tilesize, enemyCores), nearPlayer = Geometry.findClosest(x * tilesize, y * tilesize, playerCores);
        return nearEnemy != null && (nearPlayer == null || nearEnemy.dst(x * tilesize, y * tilesize) * (1f - frontier) < nearPlayer.dst(x * tilesize, y * tilesize) * frontier);
    }

    /**
     * The way from a core of the enemy's to the player, as far as it runs over the enemy's own ground:
     * tile positions (x + y * width), starting at the core.
     */
    public IntSeq lane(Building from){
        IntSeq path = new IntSeq();
        if(!ready()) return path;
        int current = from.tile.x + from.tile.y * width;

        if(toPlayer[current] == unreached){
            //no way over land, on a map fought across water or by air: straight towards the player instead
            Building target = Geometry.findClosest(from.x, from.y, playerCores);
            if(target == null) return path;
            int steps = Math.max(Math.abs(target.tile.x - from.tile.x), Math.abs(target.tile.y - from.tile.y));
            for(int i = 0; i <= steps; i++){
                float progress = steps == 0 ? 0f : (float)i / steps;
                int x = Math.round(from.tile.x + (target.tile.x - from.tile.x) * progress), y = Math.round(from.tile.y + (target.tile.y - from.tile.y) * progress);
                if(!ours(x, y)) break;
                path.add(x + y * width);
            }
            return path;
        }

        while(toPlayer[current] > 0 && ours(current % width, current / width)){
            path.add(current);
            int best = current, cx = current % width, cy = current / width;
            for(int i = 0; i < 4; i++){
                int x = cx + dx[i], y = cy + dy[i];
                if(x < 0 || y < 0 || x >= width || y >= world.height()) continue;
                if(toPlayer[x + y * width] < toPlayer[best]) best = x + y * width;
            }
            if(best == current) break;
            current = best;
        }
        return path;
    }

    /** How far a tile is over open ground from the nearest of the enemy's cores, or a very large number where there is no way. */
    public int fromHome(int x, int y){
        if(!ready() || x < 0 || y < 0 || x >= width || y >= world.height()) return unreached;
        int far = toEnemy[x + y * width];
        if(far != unreached) return far;
        //no way over land, as with a core on an island or shut in by its own walls. builders fly and ducts bridge: how
        //far a line would have to go, bridges and all, and a little more for the bridges
        if(bridged.length == toEnemy.length && bridged[x + y * width] != unreached) return bridged[x + y * width] + 10;
        Building near = Geometry.findClosest(x * tilesize, y * tilesize, enemyCores);
        return near == null ? unreached : (int)(Math.abs(near.tile.x - x) + Math.abs(near.tile.y - y)) * 2 + 20;
    }

    /** How many steps over open ground a tile is from the nearest of the enemy's cores, or -1 where there is no way over land. */
    public int steps(int x, int y){
        if(!ready() || x < 0 || y < 0 || x >= width || y >= world.height()) return -1;
        int far = toEnemy[x + y * width];
        return far == unreached ? -1 : far;
    }

    /** Whether there is a way over land from a building to the player. */
    public boolean walkable(Building from){
        return ready() && toPlayer[from.tile.x + from.tile.y * width] != unreached;
    }

    /** How far along the way to the player a tile is: 0 at the enemy's cores, 1 at the player's. */
    public float advance(int x, int y){
        int enemy = toEnemy[x + y * width], player = toPlayer[x + y * width];
        if(enemy == unreached || player == unreached || enemy + player == 0) return 0f;
        return (float)enemy / (enemy + player);
    }

    /** The stretches a lane is made up of, one for every so many tiles of it, nearest the enemy's cores first. */
    public Seq<Zone> zones(IntSeq lane, int every){
        Seq<Zone> out = new Seq<>();
        for(int i = every; i < lane.size; i += every){
            int x = lane.get(i) % width, y = lane.get(i) / width;
            out.add(new Zone(x, y, advance(x, y)));
        }
        return out;
    }
}
