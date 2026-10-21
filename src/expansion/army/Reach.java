package expansion.army;

import arc.struct.*;
import mindustry.*;
import mindustry.ai.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import arc.util.*;

/**
 * Whether a kind of unit can get from one place to another over the ground as the game's own path finder sees it: ground
 * units do not cross deep water, boats keep to it, hovering units cross both. (The other side's buildings are in the way
 * but not impassable: a unit shoots its way through.) What decides which units a side should make at all: on a map where
 * the two bases are parted by deep water, tanks never get to the other side's base.
 */
public final class Reach{
    /** Whether units of this kind of this team can get from (fx, fy) to within a few tiles of (tx, ty), in tiles. */
    public static boolean can(UnitType type, Team team, int fx, int fy, int tx, int ty){
        if(type.flying) return true;
        boolean[] seen = region(type, team, fx, fy);
        return seen != null && near(seen, tx, ty, 3);
    }

    /** Whether any tile within r of (x, y) is in the region. */
    public static boolean near(boolean[] region, int x, int y, int r){
        int w = Vars.world.width(), h = Vars.world.height();
        for(int dx = -r; dx <= r; dx++) for(int dy = -r; dy <= r; dy++){
            int nx = x + dx, ny = y + dy;
            if(nx >= 0 && ny >= 0 && nx < w && ny < h && region[nx + ny * w]) return true;
        }
        return false;
    }

    /** Whether a unit of this kind drowns in deep liquid (slag, deep water): one that neither flies, hovers nor swims. */
    public static boolean drowns(UnitType type){
        return !type.flying && !type.hovering && !type.naval && type.canDrown;
    }

    /**
     * Every tile units of this kind can get to from (fx, fy) safely: by the game's path costs, and, for a kind that drowns,
     * never across deep liquid (the game's ground path only makes it dear, and a way to a target past a pool of slag
     * takes it: on atlas 18 tanks of 18 lost walked into molten slag going for one furnace). Null for a place off the map.
     */
    public static @Nullable boolean[] region(UnitType type, Team team, int fx, int fy){
        int w = Vars.world.width(), h = Vars.world.height();
        boolean[] seen = new boolean[w * h];
        if(type.flying){
            java.util.Arrays.fill(seen, true);
            return seen;
        }
        Pathfinder.PathCost cost = type.pathCost != null ? type.pathCost : ControlPathfinder.costGround;
        boolean drowns = drowns(type);
        IntQueue queue = new IntQueue();
        if(fx < 0 || fy < 0 || fx >= w || fy >= h) return null;
        int start = fx + fy * w;
        seen[start] = true;
        queue.addLast(start);
        while(!queue.isEmpty()){
            int c = queue.removeFirst();
            int x = c % w, y = c / w;
            for(int d = 0; d < 4; d++){
                int nx = x + (d == 0 ? 1 : d == 2 ? -1 : 0), ny = y + (d == 1 ? 1 : d == 3 ? -1 : 0);
                if(nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                int n = nx + ny * w;
                if(seen[n]) continue;
                //the tiles of a building of one's own side or another's are not walls: one's own a unit walks round, another's
                //it shoots through (cores included, which is where it is going)
                //(nothing beyond the map's open area: units cannot leave it, as a sector shut till its tasks are done keeps them)
                if(Vars.state.rules.limitMapArea && !Vars.world.isInMapArea(nx, ny)) continue;
                var tile = Vars.world.tiles.geti(n);
                boolean building = tile.build != null;
                if(!building && cost.getCost(team.id, Vars.pathfinder.get(nx, ny)) == -1) continue;
                if(drowns && tile.floor().isDeep()) continue;
                seen[n] = true;
                queue.addLast(n);
            }
        }
        return seen;
    }

    /**
     * How far, in tiles along the way, units of this kind of this team have to walk from (fx, fy) to every tile they can get
     * to (the region's tiles, eight ways round, a diagonal step its length); infinite where they cannot; flying, as the crow
     * flies. Null for a place off the map. (Dijkstra over the region.)
     */
    public static @Nullable float[] distance(UnitType type, Team team, int fx, int fy){
        int w = Vars.world.width(), h = Vars.world.height();
        if(fx < 0 || fy < 0 || fx >= w || fy >= h) return null;
        //(the ground does not change in a game: worked out once a kind, a team and a start)
        String key = type.name + "|" + team.id + "|" + fx + "," + fy;
        synchronized(distances){
            //(and again once the map's open part grows, as a campaign map's does: what lay past its edge was out of reach)
            var r = Vars.state.rules;
            long area = r.limitMapArea ? (((long)r.limitX * 4099L + r.limitY) * 4099L + r.limitWidth) * 4099L + r.limitHeight : -1L;
            if(distancesWorld != Vars.world.tiles || distancesArea != area){
                distances.clear();
                distancesWorld = Vars.world.tiles;
                distancesArea = area;
            }
            float[] had = distances.get(key);
            if(had != null) return had;
        }
        float[] out = distanceNow(type, team, fx, fy);
        synchronized(distances){
            distances.put(key, out);
        }
        return out;
    }

    static final java.util.HashMap<String, float[]> distances = new java.util.HashMap<>();
    static Object distancesWorld;
    static long distancesArea = Long.MIN_VALUE;

    static float[] distanceNow(UnitType type, Team team, int fx, int fy){
        int w = Vars.world.width(), h = Vars.world.height();
        float[] dist = new float[w * h];
        if(type.flying){
            for(int y = 0; y < h; y++) for(int x = 0; x < w; x++) dist[x + y * w] = (float)Math.hypot(x - fx, y - fy);
            return dist;
        }
        boolean[] region = region(type, team, fx, fy);
        java.util.Arrays.fill(dist, Float.POSITIVE_INFINITY);
        if(region == null) return dist;
        int start = fx + fy * w;
        dist[start] = 0f;
        java.util.PriorityQueue<long[]> queue = new java.util.PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));
        queue.add(new long[]{0L, start});
        float diag = (float)Math.sqrt(2);
        while(!queue.isEmpty()){
            long[] top = queue.poll();
            int c = (int)top[1];
            float dc = top[0] / 1000f;
            if(dc > dist[c] + 1e-3f) continue;
            int x = c % w, y = c / w;
            for(int dx = -1; dx <= 1; dx++) for(int dy = -1; dy <= 1; dy++){
                if(dx == 0 && dy == 0) continue;
                int nx = x + dx, ny = y + dy;
                if(nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                int n = nx + ny * w;
                if(!region[n]) continue;
                //(a diagonal step only where both tiles beside it can be walked: no cutting a corner of rock)
                if(dx != 0 && dy != 0 && (!region[nx + y * w] || !region[x + ny * w])) continue;
                float nd = dc + (dx != 0 && dy != 0 ? diag : 1f);
                if(nd < dist[n] - 1e-4f){
                    dist[n] = nd;
                    queue.add(new long[]{(long)(nd * 1000f), n});
                }
            }
        }
        return dist;
    }

    /** Whether units of this kind of ours can get to any core of the other side's. */
    public static boolean toFoe(UnitType type, Team team, Team foe){
        var home = team.core();
        if(home == null) return false;
        for(var core : foe.cores()) if(can(type, team, home.tileX(), home.tileY(), core.tileX(), core.tileY())) return true;
        return false;
    }
}
