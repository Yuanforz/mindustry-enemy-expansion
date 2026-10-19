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

    /** Whether units of this kind of ours can get to any core of the other side's. */
    public static boolean toFoe(UnitType type, Team team, Team foe){
        var home = team.core();
        if(home == null) return false;
        for(var core : foe.cores()) if(can(type, team, home.tileX(), home.tileY(), core.tileX(), core.tileY())) return true;
        return false;
    }
}
