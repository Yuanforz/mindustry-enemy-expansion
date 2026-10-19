package expansion.defense;

import java.util.*;

/**
 * The ways the enemy comes to a target: for each way of moving, the shortest path from every source (a spawn or an
 * enemy core) to the target, and how wide the way is at every tile of it. Done the way tools/maptools MapScan measures
 * routes: 8-direction steps over what that kind of unit can cross, and the width from the distance to the nearest rock
 * or deep liquid. Plain Java over the {@link Terrain}.
 */
public final class Lanes{
    /** One way in: from a source to the target, for one way of moving. Tiles run from the source to the target. */
    public static final class Lane{
        public final int source;
        public final Foe.Move move;
        public final int[] tiles;
        /** Path length from each tile to the target, in tiles. */
        public final float[] toTarget;

        Lane(int source, Foe.Move move, int[] tiles, float[] toTarget){
            this.source = source;
            this.move = move;
            this.tiles = tiles;
            this.toTarget = toTarget;
        }

        public float length(){
            return tiles.length == 0 ? 0f : toTarget[0];
        }
    }

    public final Terrain t;
    /** Distance to the nearest tile tanks cannot cross, in thirds of a tile (a 3-4 chamfer measure). */
    public final int[] clear;
    final Map<Foe.Move, float[]> fields = new EnumMap<>(Foe.Move.class);
    final Map<Foe.Move, int[]> parents = new EnumMap<>(Foe.Move.class);
    public final int tx, ty;

    public Lanes(Terrain t, int tx, int ty){
        this.t = t;
        this.tx = tx;
        this.ty = ty;
        clear = clearance(t);
    }

    static final int[] d8x = {1, 0, -1, 0, 1, -1, -1, 1}, d8y = {0, 1, 0, -1, 1, 1, -1, -1};

    /** Steps on what is already built (ours) cost more, as the game's own path costs make units go round where they can. */
    float stepCost(Foe.Move move, int i){
        return (t.flags[i] & Terrain.built) != 0 && move != Foe.Move.air ? 6f : 0f;
    }

    /** Shortest paths to the target over what this way of moving can cross, from every tile. */
    float[] field(Foe.Move move){
        float[] d = fields.get(move);
        if(d != null) return d;
        int n = t.w * t.h;
        d = new float[n];
        int[] parent = new int[n];
        Arrays.fill(d, Float.MAX_VALUE);
        Arrays.fill(parent, -1);
        PriorityQueue<long[]> q = new PriorityQueue<>(Comparator.comparingLong(a -> a[0]));
        //the target may stand on tiles units cannot cross (it is a building): start from the open tiles round it
        for(int r = 0; r <= 6; r++){
            for(int x = tx - r; x <= tx + r; x++) for(int y = ty - r; y <= ty + r; y++){
                if(!t.in(x, y) || !t.passable(move, x, y)) continue;
                int i = x + y * t.w;
                if(d[i] == 0) continue;
                d[i] = 0;
                q.add(new long[]{0, i});
            }
            if(!q.isEmpty()) break;
        }
        while(!q.isEmpty()){
            long[] top = q.poll();
            int i = (int)top[1];
            float di = top[0] / 1000f;
            if(di > d[i] + 0.0001f) continue;
            int x = i % t.w, y = i / t.w;
            for(int k = 0; k < 8; k++){
                int nx = x + d8x[k], ny = y + d8y[k];
                if(!t.in(nx, ny) || !t.passable(move, nx, ny)) continue;
                if(k >= 4 && (!t.passable(move, nx, y) || !t.passable(move, x, ny))) continue;
                int j = nx + ny * t.w;
                float nd = di + (k >= 4 ? 1.4142f : 1f) + stepCost(move, j);
                if(nd < d[j]){
                    d[j] = nd;
                    parent[j] = i;
                    q.add(new long[]{(long)(nd * 1000), j});
                }
            }
        }
        fields.put(move, d);
        parents.put(move, parent);
        return d;
    }

    /** The open tile nearest to (x, y) that this way of moving can reach the target from, or -1. */
    int anchor(Foe.Move move, int x, int y){
        float[] d = field(move);
        for(int r = 0; r <= 12; r++){
            int best = -1;
            float bd = Float.MAX_VALUE;
            for(int ax = x - r; ax <= x + r; ax++) for(int ay = y - r; ay <= y + r; ay++){
                if(!t.in(ax, ay)) continue;
                int i = ax + ay * t.w;
                if(d[i] < bd){
                    bd = d[i];
                    best = i;
                }
            }
            if(best >= 0) return best;
        }
        return -1;
    }

    /** The way from (x, y) to the target for this way of moving, or null if there is none. Air goes straight. */
    public Lane lane(int source, Foe.Move move, int x, int y){
        if(move == Foe.Move.air){
            int steps = Math.max(1, (int)Math.ceil(Math.hypot(tx - x, ty - y)));
            int[] tiles = new int[steps + 1];
            float[] to = new float[steps + 1];
            for(int s = 0; s <= steps; s++){
                int px = Math.round(x + (tx - x) * s / (float)steps), py = Math.round(y + (ty - y) * s / (float)steps);
                px = Math.max(0, Math.min(t.w - 1, px));
                py = Math.max(0, Math.min(t.h - 1, py));
                tiles[s] = px + py * t.w;
                to[s] = (float)Math.hypot(tx - px, ty - py);
            }
            return new Lane(source, move, tiles, to);
        }
        int start = anchor(move, x, y);
        if(start < 0) return null;
        float[] d = field(move);
        int[] parent = parents.get(move);
        List<Integer> path = new ArrayList<>();
        for(int at = start; at != -1 && path.size() < t.w * t.h; at = parent[at]) path.add(at);
        int[] tiles = new int[path.size()];
        float[] to = new float[path.size()];
        for(int i = 0; i < tiles.length; i++){
            tiles[i] = path.get(i);
            to[i] = d[tiles[i]];
        }
        return new Lane(source, move, tiles, to);
    }

    /**
     * How wide the way for tanks is at a tile, in tiles. A shortest path hugs the rock at every bend, so the tile's own
     * distance to rock says little; the middle of the way is the open tile with the most room near it, and the way is
     * twice that room wide. Looked for within 12 tiles: a way wider than about 24 counts as open ground.
     */
    public int width(int i){
        int x = i % t.w, y = i / t.w, best = 0;
        for(int dx = -12; dx <= 12; dx++) for(int dy = -12; dy <= 12; dy++){
            if(dx * dx + dy * dy > 144 || !t.in(x + dx, y + dy)) continue;
            best = Math.max(best, clear[x + dx + (y + dy) * t.w]);
        }
        return Math.max(1, Math.round(best * 2 / 3f - 1));
    }

    /** The middle of the way near a tile: the open tile within 8 tiles with the most room round it, the nearest of those. */
    public int middle(int i){
        int x = i % t.w, y = i / t.w, best = i, room = clear[i];
        float near = 0f;
        for(int dx = -8; dx <= 8; dx++) for(int dy = -8; dy <= 8; dy++){
            if(dx * dx + dy * dy > 64 || !t.in(x + dx, y + dy)) continue;
            int j = x + dx + (y + dy) * t.w;
            float d = dx * dx + dy * dy;
            if(clear[j] > room || (clear[j] == room && d < near)){
                room = clear[j];
                best = j;
                near = d;
            }
        }
        return best;
    }

    /** The direction (0 = +x .. 3 = -y) the enemy comes from at a tile of a lane: the way back along it, a few tiles out. */
    public static int facing(Terrain t, Lane lane, int index){
        int back = lane.tiles[Math.max(0, index - 8)], here = lane.tiles[Math.min(lane.tiles.length - 1, index + 4)];
        int dx = back % t.w - here % t.w, dy = back / t.w - here / t.w;
        if(Math.abs(dx) >= Math.abs(dy)) return dx >= 0 ? 0 : 2;
        return dy >= 0 ? 1 : 3;
    }

    static int[] clearance(Terrain t){
        int n = t.w * t.h;
        int[] c = new int[n];
        final int inf = 1 << 28;
        for(int y = 0; y < t.h; y++) for(int x = 0; x < t.w; x++){
            int i = x + y * t.w;
            c[i] = !t.passable(Foe.Move.ground, x, y) ? 0 : (x == 0 || y == 0 || x == t.w - 1 || y == t.h - 1) ? 3 : inf;
        }
        for(int y = 0; y < t.h; y++) for(int x = 0; x < t.w; x++){
            int i = x + y * t.w, v = c[i];
            if(v == 0) continue;
            if(x > 0) v = Math.min(v, c[i - 1] + 3);
            if(y > 0){
                v = Math.min(v, c[i - t.w] + 3);
                if(x > 0) v = Math.min(v, c[i - t.w - 1] + 4);
                if(x < t.w - 1) v = Math.min(v, c[i - t.w + 1] + 4);
            }
            c[i] = v;
        }
        for(int y = t.h - 1; y >= 0; y--) for(int x = t.w - 1; x >= 0; x--){
            int i = x + y * t.w, v = c[i];
            if(v == 0) continue;
            if(x < t.w - 1) v = Math.min(v, c[i + 1] + 3);
            if(y < t.h - 1){
                v = Math.min(v, c[i + t.w] + 3);
                if(x < t.w - 1) v = Math.min(v, c[i + t.w + 1] + 4);
                if(x > 0) v = Math.min(v, c[i + t.w - 1] + 4);
            }
            c[i] = v;
        }
        return c;
    }
}
