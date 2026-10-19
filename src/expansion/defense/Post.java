package expansion.defense;

import java.util.*;

/**
 * Lays out one defended place: a first row of modules side by side across the way the enemy comes, a wall line in front
 * of them, and a second row behind for the long-range turrets. Plain Java over the {@link Terrain}; the same code is used
 * by the planner on a real map and by the lab (xtest.TurretLab), so what the lab measures is what the planner builds.
 *
 * Directions are the game's: 0 = +x, 1 = +y, 2 = -x, 3 = -y. "Front" is the way the enemy comes from.
 */
public final class Post{
    /** One module as it was put down. */
    public static final class Laid{
        public final Battery module;
        public final int index, x, y, turns;
        public final List<Placement> parts = new ArrayList<>();
        /** The ammunition chosen for it (an item or liquid name), or null for one on power alone. */
        public String ammo;
        public boolean wet;

        Laid(Battery module, int index, int x, int y, int turns){
            this.module = module;
            this.index = index;
            this.x = x;
            this.y = y;
            this.turns = turns;
        }

        public List<Placement> ports(){
            List<Placement> out = new ArrayList<>();
            for(Placement p : parts) if(p.kind == Placement.Kind.port) out.add(p);
            return out;
        }
    }

    /** What to lay at one place. */
    public static final class Ask{
        public final List<Battery> front = new ArrayList<>(), back = new ArrayList<>();
        /** The ammunition for each turret kind, by turret name; null keeps what the module is drawn with. */
        public final Map<String, String> ammo = new HashMap<>();
        /** Whether water for faster fire is laid on. */
        public boolean wet;
        /** The large wall to build in front (e.g. "tungsten-wall-large"), its one-tile version, or none. */
        public String wall, smallWall;
        /** Tiles between the front of the turrets and the wall line, and how many tiles thick the line is. */
        public int wallGap = 2, wallDepth = 2;
        /** How far the wall line may reach to each side looking for rock to close against. */
        public int wallReach = 12;
        /** Tiles between the first row and the second. */
        public int rowGap = 3;
    }

    public final Terrain terrain;
    /** Tiles taken by what has been laid so far (here and at other posts), packed as Battery.key. */
    public final Set<Long> taken;
    public final List<Laid> laid = new ArrayList<>();
    public final List<Placement> walls = new ArrayList<>();
    public final List<String> notes = new ArrayList<>();
    /** Modules asked for that found no room. */
    public final List<Battery> missed = new ArrayList<>();
    public int moduleCount;

    public Post(Terrain terrain, Set<Long> taken, int firstIndex){
        this.terrain = terrain;
        this.taken = taken;
        this.moduleCount = firstIndex;
    }

    static final int[] dx = {1, 0, -1, 0}, dy = {0, 1, 0, -1};

    /** World tile of a point given along the front (u, toward the enemy) and across it (v, to the left). */
    static int wx(int ax, int dir, int u, int v){
        return ax + u * dx[dir] + v * dx[(dir + 1) % 4];
    }

    static int wy(int ay, int dir, int u, int v){
        return ay + u * dy[dir] + v * dy[(dir + 1) % 4];
    }

    /** One row of modules side by side: where its fronts are, how far it reaches to each side, and how far back. */
    static final class Row{
        int front, lo, hi, back;
        boolean empty = true;

        Row(int front){
            this.front = front;
            back = front;
        }
    }

    /** Lays everything asked for at (ax, ay), facing dir. Returns the placements, walls included. */
    public List<Placement> lay(int ax, int ay, int dir, Ask ask){
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(0));
        //the first row and those behind it while there is no room beside: the front modules, nearest the wall first
        for(Battery m : ask.front) if(put(m, ax, ay, dir, rows, 0, ask) == null){
            notes.add("no room for " + m.name + " at " + ax + "," + ay);
            missed.add(m);
        }
        Row first = rows.get(0);
        if(ask.wall != null && !first.empty) wallLine(ax, ay, dir, ask, first.lo, first.hi);
        //the long-range ones in rows of their own behind
        int start = rows.size();
        if(!ask.back.isEmpty()) rows.add(new Row(backOf(rows) - ask.rowGap));
        for(Battery m : ask.back) if(put(m, ax, ay, dir, rows, start, ask) == null){
            notes.add("no room behind for " + m.name + " at " + ax + "," + ay);
            missed.add(m);
        }
        List<Placement> out = new ArrayList<>();
        for(Laid l : laid) out.addAll(l.parts);
        out.addAll(walls);
        return out;
    }

    static int backOf(List<Row> rows){
        int min = 0;
        for(Row r : rows) min = Math.min(min, r.back);
        return min;
    }

    /** Puts a module in the first row from start on that has room beside what is there, or in a new row behind them all. */
    Laid put(Battery m, int ax, int ay, int dir, List<Row> rows, int start, Ask ask){
        for(int i = start; i < rows.size(); i++){
            Laid l = fit(m, ax, ay, dir, rows.get(i), ask, 2);
            if(l != null) return l;
        }
        Row row = new Row(backOf(rows) - ask.rowGap);
        Laid l = fit(m, ax, ay, dir, row, ask, 6);
        if(l != null) rows.add(row);
        return l;
    }

    /** How far back (in u, so negative) a laid module reaches from the anchor. */
    int depthBack(Laid l, int ax, int ay, int dir){
        int min = Integer.MAX_VALUE;
        for(Placement p : l.parts){
            int s = Battery.shape(p.block)[0];
            for(int i = 0; i < s; i++) for(int j = 0; j < s; j++){
                int x = Battery.low(p.x, s) + i, y = Battery.low(p.y, s) + j;
                min = Math.min(min, (x - ax) * dx[dir] + (y - ay) * dy[dir]);
            }
        }
        return min;
    }

    /**
     * Puts a module down with its front at the row's front, beside what is already in the row: in the middle if the row
     * is empty, else out to the left and the right in turn. Tries places further out and a little further back until it
     * fits on free ground with a tile of room round it.
     */
    Laid fit(Battery m, int ax, int ay, int dir, Row row, Ask ask, int maxBack){
        int[] box = m.box();
        int width = box[3] - box[1] + 1;
        for(int back = 0; back <= maxBack; back++){
            for(int step = 0; step < 24; step++){
                boolean left = (step % 2 == 0);
                int shift = step / 2;
                int vLow;
                if(row.empty){
                    vLow = -width / 2 + (left ? shift : -shift);
                }else if(left){
                    vLow = row.hi + 2 + shift;
                }else{
                    vLow = row.lo - 1 - width - shift;
                }
                int vMain = vLow - box[1];
                int uMain = row.front - back - box[2];
                int mx = wx(ax, dir, uMain, vMain), my = wy(ay, dir, uMain, vMain);
                List<Battery.Part> parts = m.at(mx, my, dir);
                if(!fits(parts)) continue;
                Laid l = new Laid(m, moduleCount++, mx, my, dir);
                l.wet = ask.wet;
                Gun gun = m.gun();
                String ammo = gun != null ? ask.ammo.get(gun.name) : null;
                for(Battery.Part p : parts){
                    if(p.optional){
                        //water only when asked for, and only where there is room
                        if(p.port() && "water".equals(p.config) && !ask.wet) continue;
                        if(!free(p)) continue;
                    }
                    String config = p.config;
                    if(p.port() && ammo != null && isAmmoPort(gun, p)) config = ammo;
                    Placement placed = new Placement(p.block, p.x, p.y, p.rotation, config, Placement.kindOf(p.block), l.index);
                    placed.liquid = p.liquidPort();
                    placed.optional = p.optional;
                    l.parts.add(placed);
                    take(p);
                }
                if(gun != null){
                    l.ammo = ammo;
                    if(l.ammo == null) for(Battery.Part p : parts) if(p.port() && isAmmoPort(gun, p)) l.ammo = p.config;
                }
                laid.add(l);
                if(row.empty){
                    row.lo = vLow;
                    row.hi = vLow + width - 1;
                }else{
                    row.lo = Math.min(row.lo, vLow);
                    row.hi = Math.max(row.hi, vLow + width - 1);
                }
                row.empty = false;
                row.back = Math.min(row.back, depthBack(l, ax, ay, dir));
                return l;
            }
        }
        return null;
    }

    /** Whether a port brings the turret's ammunition (and not water, hydrogen or nitrogen besides). */
    static boolean isAmmoPort(Gun gun, Battery.Part p){
        if(gun == null || p.config == null) return false;
        for(Gun.Ammo a : gun.ammo) if(a.what.equals(p.config)) return true;
        return false;
    }

    boolean fits(List<Battery.Part> parts){
        Set<Long> cover = Battery.cover(parts, false);
        for(long k : cover){
            int x = Battery.kx(k), y = Battery.ky(k);
            if(!terrain.free(x, y) || taken.contains(k)) return false;
            //a tile of room on every side, so that one module's routers never push into another's blocks
            for(int d = 0; d < 4; d++){
                long n = Battery.key(x + dx[d], y + dy[d]);
                if(!cover.contains(n) && taken.contains(n)) return false;
            }
        }
        return true;
    }

    boolean free(Battery.Part p){
        int s = p.size();
        for(int i = 0; i < s; i++) for(int j = 0; j < s; j++){
            int x = Battery.low(p.x, s) + i, y = Battery.low(p.y, s) + j;
            if(!terrain.free(x, y) || taken.contains(Battery.key(x, y))) return false;
        }
        return true;
    }

    void take(Battery.Part p){
        int s = p.size();
        for(int i = 0; i < s; i++) for(int j = 0; j < s; j++) taken.add(Battery.key(Battery.low(p.x, s) + i, Battery.low(p.y, s) + j));
    }

    /**
     * A wall line across the way in front of the first row. It reaches out to each side until it meets rock (closing the
     * choke, so tanks have to break through under fire), or, where the way is too wide to close, a little past the row.
     * Large walls where they fit, single walls in the gaps.
     */
    void wallLine(int ax, int ay, int dir, Ask ask, int left, int right){
        int u0 = ask.wallGap + 1;
        //how far the open ground goes to each side along the wall line
        int reachL = reach(ax, ay, dir, u0, right, 1, ask.wallReach), reachR = reach(ax, ay, dir, u0, left, -1, ask.wallReach);
        boolean closedL = reachL != open, closedR = reachR != open;
        int vHigh = closedL ? reachL : right + 3, vLow = closedR ? reachR : left - 3;
        if(!closedL || !closedR) notes.add("the way at " + ax + "," + ay + " is too wide to close: walls only in front of the turrets");
        Set<Long> cells = new LinkedHashSet<>();
        for(int u = u0; u < u0 + ask.wallDepth; u++) for(int v = vLow; v <= vHigh; v++){
            int x = wx(ax, dir, u, v), y = wy(ay, dir, u, v);
            long k = Battery.key(x, y);
            if(terrain.free(x, y) && !taken.contains(k)) cells.add(k);
        }
        //large walls first, by world tile order, so the result does not depend on the direction
        List<Long> order = new ArrayList<>(cells);
        order.sort(Comparator.<Long>comparingInt(Battery::ky).thenComparingInt(Battery::kx));
        Set<Long> used = new HashSet<>();
        for(long k : order){
            if(used.contains(k)) continue;
            int x = Battery.kx(k), y = Battery.ky(k);
            long a = Battery.key(x + 1, y), b = Battery.key(x, y + 1), c = Battery.key(x + 1, y + 1);
            if(cells.contains(a) && cells.contains(b) && cells.contains(c) && !used.contains(a) && !used.contains(b) && !used.contains(c)){
                walls.add(new Placement(ask.wall, x, y, 0, null, Placement.Kind.wall, -1));
                used.add(k);
                used.add(a);
                used.add(b);
                used.add(c);
            }
        }
        if(ask.smallWall != null) for(long k : order){
            if(used.contains(k)) continue;
            walls.add(new Placement(ask.smallWall, Battery.kx(k), Battery.ky(k), 0, null, Placement.Kind.wall, -1));
            used.add(k);
        }
        taken.addAll(used);
    }

    static final int open = Integer.MIN_VALUE;

    /** Along the wall line from v = from, a step at a time to one side: the v of the last open tile before rock, or {@link #open} if there is none within reach. */
    int reach(int ax, int ay, int dir, int u, int from, int side, int max){
        int v = from;
        for(int i = 0; i < max + 8; i++){
            int nv = v + side;
            if(!terrain.passable(Foe.Move.ground, wx(ax, dir, u, nv), wy(ay, dir, u, nv))) return v;
            v = nv;
            if(Math.abs(v) > max) return open;
        }
        return open;
    }
}
