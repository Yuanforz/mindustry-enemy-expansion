package expansion.army;

import arc.struct.*;
import mindustry.*;
import mindustry.ai.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;

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
        Pathfinder.PathCost cost = type.pathCost != null ? type.pathCost : ControlPathfinder.costGround;
        int w = Vars.world.width(), h = Vars.world.height();
        boolean[] seen = new boolean[w * h];
        IntQueue queue = new IntQueue();
        int start = fx + fy * w;
        if(fx < 0 || fy < 0 || fx >= w || fy >= h) return false;
        seen[start] = true;
        queue.addLast(start);
        while(!queue.isEmpty()){
            int c = queue.removeFirst();
            int x = c % w, y = c / w;
            if(Math.abs(x - tx) <= 3 && Math.abs(y - ty) <= 3) return true;
            for(int d = 0; d < 4; d++){
                int nx = x + (d == 0 ? 1 : d == 2 ? -1 : 0), ny = y + (d == 1 ? 1 : d == 3 ? -1 : 0);
                if(nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                int n = nx + ny * w;
                if(seen[n]) continue;
                seen[n] = true;
                //the tiles of a building of one's own side or another's are not walls: one's own a unit walks round, another's
                //it shoots through (cores included, which is where it is going)
                var tile = Vars.world.tiles.geti(n);
                boolean building = tile.build != null;
                if(!building && cost.getCost(team.id, Vars.pathfinder.get(nx, ny)) == -1) continue;
                queue.addLast(n);
            }
        }
        return false;
    }

    /** Whether units of this kind of ours can get to any core of the other side's. */
    public static boolean toFoe(UnitType type, Team team, Team foe){
        var home = team.core();
        if(home == null) return false;
        for(var core : foe.cores()) if(can(type, team, home.tileX(), home.tileY(), core.tileX(), core.tileY())) return true;
        return false;
    }
}
