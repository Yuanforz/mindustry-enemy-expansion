package expansion.army;

import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import mindustry.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;

/**
 * A way for a unit to somewhere round the other side's turrets: A* on a coarse grid (three tiles a cell) over the threat
 * field, where a step costs its length and, much more, the damage the unit would take on it. What walks is kept to
 * ground it can walk; what flies goes anywhere. The game's own path-finding still takes the unit from each point of the
 * way to the next: the way only says which side of the turrets to pass on.
 */
public class Route{
    static final int cell = 3;
    /** How many tiles of going round one point of damage is worth: a unit's whole health is worth two hundred tiles. */
    static final float tilesPerHealth = 200f;

    public final Seq<Vec2> points = new Seq<>();
    /** The damage the unit is expected to take on it. */
    public float damage;

    /** Works out a way, or returns null if there is none. */
    public static Route find(Threat threat, Unit unit, float tx, float ty, boolean dodging){
        int w = Mathf.ceil(Vars.world.width() / (float)cell), h = Mathf.ceil(Vars.world.height() / (float)cell);
        UnitType type = unit.type;
        int start = index(unit.x, unit.y, w, h), goal = index(tx, ty, w, h);
        if(start < 0 || goal < 0) return null;
        float[] cost = new float[w * h];
        int[] from = new int[w * h];
        float[] danger = new float[w * h];
        java.util.Arrays.fill(cost, Float.MAX_VALUE);
        java.util.Arrays.fill(danger, -1f);
        cost[start] = 0f;
        from[start] = start;
        float stepTime = Vars.tilesize * cell / Math.max(type.speed, 0.1f) / 60f;
        float perHealth = tilesPerHealth * Vars.tilesize / Math.max(unit.maxHealth, 1f);
        float gx = tx, gy = ty;
        PQueue<float[]> open = new PQueue<>(64, (a, b) -> Float.compare(a[0], b[0]));
        open.add(new float[]{0f, start});
        int expanded = 0;
        while(!open.empty() && expanded++ < 40000){
            float[] top = open.poll();
            int at = (int)top[1];
            if(at == goal) break;
            int ax = at % w, ay = at / w;
            for(Point2 d : Geometry.d8){
                int nx = ax + d.x, ny = ay + d.y;
                if(nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                int next = nx + ny * w;
                if(next != goal && !passable(type, nx, ny)) continue;
                float len = (d.x != 0 && d.y != 0 ? 1.414f : 1f);
                if(danger[next] < 0f) danger[next] = threat.danger((nx * cell + cell / 2f) * Vars.tilesize, (ny * cell + cell / 2f) * Vars.tilesize, type, dodging);
                float step = len * cell * Vars.tilesize + danger[next] * stepTime * len * perHealth;
                float c = cost[at] + step;
                if(c < cost[next]){
                    cost[next] = c;
                    from[next] = at;
                    float heuristic = Mathf.dst(nx * cell * Vars.tilesize, ny * cell * Vars.tilesize, gx, gy);
                    open.add(new float[]{c + heuristic, next});
                }
            }
        }
        if(cost[goal] == Float.MAX_VALUE) return null;
        Route route = new Route();
        IntSeq cells = new IntSeq();
        for(int at = goal; at != start; at = from[at]) cells.add(at);
        cells.reverse();
        //every fourth cell is a point of the way, and where it turns
        for(int i = 0; i < cells.size; i++){
            int at = cells.get(i);
            if(danger[at] > 0f) route.damage += danger[at] * stepTime;
            boolean turn = i > 0 && i < cells.size - 1 && (cells.get(i) - cells.get(i - 1)) != (cells.get(i + 1) - cells.get(i));
            if(i == cells.size - 1 || i % 4 == 3 || turn && i % 2 == 1){
                route.points.add(new Vec2((at % w * cell + cell / 2f) * Vars.tilesize, (at / w * cell + cell / 2f) * Vars.tilesize));
            }
        }
        if(route.points.isEmpty()) route.points.add(new Vec2(tx, ty));
        else route.points.peek().set(tx, ty);
        return route;
    }

    static int index(float x, float y, int w, int h){
        int cx = (int)(x / Vars.tilesize / cell), cy = (int)(y / Vars.tilesize / cell);
        if(cx < 0 || cy < 0 || cx >= w || cy >= h) return -1;
        return cx + cy * w;
    }

    /** Whether a unit of a kind can be in a cell: anywhere for what flies, else the cell's middle tile is open (and not deep, for what walks). */
    static boolean passable(UnitType type, int cx, int cy){
        if(type.flying) return true;
        Tile tile = Vars.world.tile(cx * cell + cell / 2, cy * cell + cell / 2);
        if(tile == null) return false;
        //what walks on legs steps over buildings and walls, and only the map's own rock stops it
        if(type.allowLegStep) return !tile.legSolid();
        if(tile.solid()) return false;
        return type.hovering || !tile.floor().isDeep();
    }
}
