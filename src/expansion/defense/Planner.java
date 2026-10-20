package expansion.defense;

import java.util.*;

/**
 * The defence planner. Given the map, our core, where the enemy comes from and what it sends, what we can build and
 * feed, and a budget, it decides which defence modules to build, where, in what order, and what each needs fed to it.
 * Plain Java over plain data: the game is read into these inputs by GameData, and the result is a list of placements
 * and supply needs for the economy to meet.
 *
 * How it decides (the rules of reference/turrets-drills-human.md §4, sized by the lab's measurements):
 * - Where: for every way in (each source, each way of moving), the narrowest place on the way that is far enough out
 *   that the enemy, held at the walls, cannot reach the core with its guns, and not much further (supply lines are long
 *   and exposed). A place on several ways at once is worth more. Air comes straight, so anti-air stands by the core on
 *   the line from where it comes.
 * - What: every unit expected at a place is given to the module that holds it at the least cost, by the capacity table
 *   (Capacity, measured in the lab); then modules holding only a little are folded into the others while that is
 *   cheaper. So a place gets a mix where a mix is cheaper (Sublimates for the swarm and a Lustre for the big ones),
 *   Disperse goes where air comes, and nothing is given a unit it cannot hit. Scathe and Malign are never chosen: they
 *   are not in the table (Scathe is no line defence).
 * - Cost: what a module is built of, and what its supply takes to make: the average it eats (fighting about a third of
 *   the time) priced by what a machine making that much costs, and its power.
 * - Order: places by how soon their enemy arrives, and in each place the module that holds the most first, then walls.
 *   What does not fit the budget is listed as wanted next.
 * - Supply: each module's ammunition (rate while firing, average, buffer), liquids, power and heat, and where its ports
 *   are, for the economy to lay lines to.
 */
public final class Planner{
    /** Where the enemy comes from. */
    public static final class Source{
        public final int x, y;
        public final String label;
        /** The ways of moving that come from here (all, if empty). */
        public final EnumSet<Foe.Move> moves = EnumSet.noneOf(Foe.Move.class);
        /** The largest wave that comes from here, by unit; if empty, the ask's units of the ways of moving above. */
        public final Map<String, Integer> units = new TreeMap<>();
        /** Every wave that comes from here, in order, by unit (the same waves for every source); if empty, one wave of the units above. */
        public final List<Map<String, Integer>> waves = new ArrayList<>();

        public Source(int x, int y, String label){
            this.x = x;
            this.y = y;
            this.label = label;
        }
    }

    /** Everything the planner is told. */
    public static final class Ask{
        public Terrain terrain;
        /** Our core: its tile and size. */
        public int coreX, coreY, coreSize = 4;
        public final List<Source> sources = new ArrayList<>();
        /** The largest wave expected, by unit name, for sources that do not say their own. */
        public final Map<String, Integer> units = new TreeMap<>();
        /** Blocks we may build, by name. */
        public final Set<String> blocks = new HashSet<>();
        /** Items and liquids the economy can feed to turrets (made somewhere, not only held in the core). */
        public final Set<String> supply = new HashSet<>();
        /** What may be spent, by item; empty for no limit. */
        public final Map<String, Integer> budget = new TreeMap<>();
        /** So many modules at each place, of the kind strongest for its cost, instead of as many as hold what comes (0: that). */
        public int fixedModules;
        /** What each block costs, by item. */
        public Map<String, Map<String, Integer>> costs = new HashMap<>();
        public List<Battery> modules = new ArrayList<>();
        public Capacity capacity;
        /** How many times what the table says a module holds is asked of it. */
        public float safety = 1.5f;
        /** The share of the time turrets fire, for the average supply. */
        public float duty = 0.3f;
        /** How far out from the core a defence may stand at most, beyond the nearest it may stand, along the way in. */
        public float reach = 90f;
        /** How far a wall line may reach to each side of a place to close it against rock. */
        public int wallReach = 16;
    }

    /** What one module needs fed, and where. */
    public static final class Supply{
        public final int module;
        public final String name;
        /** Item or liquid, the rate a second while all its turrets fire, the average, and a note. */
        public final List<String[]> needs = new ArrayList<>();
        public float power, heat;
        public final List<Placement> ports = new ArrayList<>();

        Supply(int module, String name){
            this.module = module;
            this.name = name;
        }

        void need(String what, float firing, float average, String note){
            needs.add(new String[]{what, String.format(Locale.ROOT, "%.2f", firing), String.format(Locale.ROOT, "%.2f", average), note});
        }

        @Override
        public String toString(){
            StringBuilder b = new StringBuilder("#" + module + " " + name + ":");
            for(String[] n : needs) b.append(" ").append(n[0]).append(" ").append(n[1]).append("/s firing (").append(n[2]).append(" avg").append(n[3].isEmpty() ? "" : ", " + n[3]).append(")");
            if(power > 0) b.append(" power ").append(Math.round(power)).append("/s");
            if(heat > 0) b.append(" heat ").append(Math.round(heat));
            b.append(" | ports");
            for(Placement p : ports) b.append(" ").append(p.config).append("@").append(p.x).append(",").append(p.y);
            return b.toString();
        }
    }

    /** One defended place in the plan. */
    public static final class Site{
        public final int x, y, dir;
        public final String why;
        public final Map<String, Integer> units;
        /** Every wave that comes here, in order: from all the ways it holds at once. */
        public final List<Map<String, Integer>> waves = new ArrayList<>();
        public final boolean air;
        /** Whether the wall line closes the way from rock to rock here, so tanks must break through (the lab's closed lane). */
        public boolean closed = true;
        /** Whether the wall line is left open in the middle, because closed it would send the enemy round another way. */
        public boolean notched;
        /** The ways in it holds: source and way of moving. */
        public final List<int[]> ways = new ArrayList<>();
        public float arrives;
        public final List<Post.Laid> laid = new ArrayList<>();
        public final List<Placement> walls = new ArrayList<>();
        public final List<String> chosen = new ArrayList<>();

        Site(int x, int y, int dir, String why, Map<String, Integer> units, boolean air){
            this.x = x;
            this.y = y;
            this.dir = dir;
            this.why = why;
            this.units = units;
            this.air = air;
        }
    }

    public static final class Plan{
        /** In the order to build them. */
        public final List<Placement> placements = new ArrayList<>();
        public final List<Supply> supplies = new ArrayList<>();
        public final List<Site> sites = new ArrayList<>();
        public final Map<String, Integer> cost = new TreeMap<>();
        public final List<String> notes = new ArrayList<>();
        /** Modules wanted that the budget does not cover yet. */
        public final List<String> wanted = new ArrayList<>();

        public String describe(){
            StringBuilder b = new StringBuilder();
            for(Site s : sites){
                b.append(String.format("  site %d,%d facing %d (%s), enemy in %.0f s: %s%n", s.x, s.y, s.dir, s.why, s.arrives, s.units));
                for(String c : s.chosen) b.append("    ").append(c).append('\n');
            }
            for(Supply s : supplies) b.append("  supply ").append(s).append('\n');
            b.append("  cost ").append(cost).append('\n');
            for(String w : wanted) b.append("  wanted next: ").append(w).append('\n');
            for(String n : notes) b.append("  note: ").append(n).append('\n');
            return b.toString();
        }
    }

    /**
     * What it takes to make one a second of each item or liquid, in items of machines (reference/erekir-late-spec.md §10):
     * a plasma bore makes 0.75 beryllium or graphite for 40 beryllium, an impact drill 1.3 tungsten for about 220 items
     * and water, an oxidation chamber 0.5 oxide for 420, an electrolyzer 4 ozone and 6 hydrogen for 300, a concentrator
     * 16 nitrogen for 390 and its heaters, a turbine condenser 5 water for 60.
     */
    static final Map<String, Float> making = new HashMap<>();

    static{
        making.put("beryllium", 55f);
        making.put("graphite", 55f);
        making.put("silicon", 110f);
        making.put("tungsten", 200f);
        making.put("thorium", 300f);
        making.put("oxide", 840f);
        making.put("surge-alloy", 500f);
        making.put("carbide", 600f);
        making.put("phase-fabric", 900f);
        making.put("water", 12f);
        making.put("ozone", 75f);
        making.put("hydrogen", 50f);
        making.put("nitrogen", 120f);
        making.put("cyanogen", 60f);
    }

    final Ask ask;
    final Lanes lanes;
    final Set<Long> taken = new HashSet<>();
    /** What the walls planned so far add to the game's path cost, a tile. */
    float[] walled;
    final Plan plan = new Plan();
    int modules;

    public Planner(Ask ask){
        this.ask = ask;
        lanes = new Lanes(ask.terrain, ask.coreX, ask.coreY);
    }

    /** One choice at a place: a module fed a given way, how many of it, and the units it is there for. */
    static final class Choice{
        Capacity.Config config;
        Battery module;
        int count;
        float cost;
        final Map<String, Integer> units = new TreeMap<>();

        EnumSet<Foe.Move> covers(){
            EnumSet<Foe.Move> out = EnumSet.noneOf(Foe.Move.class);
            for(String u : units.keySet()) out.add(Foe.get(u).move);
            return out;
        }
    }

    /** A source's units of one way of moving. */
    /** A source's waves, each as its units of one way of moving. */
    List<Map<String, Integer>> wavesOf(Source s, Foe.Move move){
        List<Map<String, Integer>> out = new ArrayList<>();
        if(s.waves.isEmpty()){
            out.add(unitsOf(s, move));
            return out;
        }
        for(Map<String, Integer> wave : s.waves){
            Map<String, Integer> w = new TreeMap<>();
            for(var e : wave.entrySet()) if(Foe.get(e.getKey()) != null && Foe.get(e.getKey()).move == move && e.getValue() > 0) w.put(e.getKey(), e.getValue());
            out.add(w);
        }
        return out;
    }

    /** Adds waves into a list of waves, one by one in order. */
    static void addWaves(List<Map<String, Integer>> into, List<Map<String, Integer>> waves){
        while(into.size() < waves.size()) into.add(new TreeMap<>());
        for(int i = 0; i < waves.size(); i++) for(var e : waves.get(i).entrySet()) into.get(i).merge(e.getKey(), e.getValue(), Integer::sum);
    }

    /** The waves the place being chosen for gets: modules are sized for the worst of them. */
    List<Map<String, Integer>> sizing = new ArrayList<>();

    /** How much of a module the worst wave takes up, counting only the given units: 1 is all it holds. */
    float load(Capacity.Config c, Set<String> units, boolean closed){
        float worst = 0f;
        for(Map<String, Integer> wave : sizing){
            Map<String, Integer> part = new TreeMap<>();
            for(var e : wave.entrySet()) if(units.contains(e.getKey())) part.put(e.getKey(), e.getValue());
            worst = Math.max(worst, c.load(part, closed));
        }
        return worst;
    }

    Map<String, Integer> unitsOf(Source s, Foe.Move move){
        Map<String, Integer> from = s.units.isEmpty() ? ask.units : s.units, out = new TreeMap<>();
        for(var e : from.entrySet()){
            Foe foe = Foe.get(e.getKey());
            if(foe == null || foe.move != move || e.getValue() <= 0) continue;
            if(s.units.isEmpty() && !s.moves.isEmpty() && !s.moves.contains(move)) continue;
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    public Plan plan(){
        for(String u : ask.units.keySet()) if(Foe.get(u) == null) plan.notes.add("unknown unit " + u + ": left out");
        //the ways in: one for each source and way of moving that comes from it
        List<Lanes.Lane> ground = new ArrayList<>();
        List<Map<String, Integer>> groundUnits = new ArrayList<>();
        List<List<Map<String, Integer>>> groundWaves = new ArrayList<>();
        float range = 0f;
        for(int s = 0; s < ask.sources.size(); s++){
            Source src = ask.sources.get(s);
            for(Foe.Move move : Foe.Move.values()){
                if(move == Foe.Move.air) continue;
                Map<String, Integer> units = unitsOf(src, move);
                if(units.isEmpty()) continue;
                Lanes.Lane lane = lanes.lane(s, move, src.x, src.y);
                if(lane == null || lane.tiles.length < 2){
                    plan.notes.add("no " + move + " way from " + src.label);
                    continue;
                }
                ground.add(lane);
                groundUnits.add(units);
                groundWaves.add(wavesOf(src, move));
                for(String u : units.keySet()) range = Math.max(range, Foe.get(u).range);
            }
        }
        List<Site> sites = new ArrayList<>(groundSites(ground, groundUnits, groundWaves, range));
        sites.addAll(airSites());
        sites.sort(Comparator.comparingDouble(s -> s.arrives));
        //what to put at each, then lay it out, in order, while the budget lasts
        Map<String, Integer> left = new TreeMap<>(ask.budget);
        for(Site site : sites){
            //a closed wall line that would only send them round another way is left with a notch, and sized as open ground
            if(!site.air && site.closed && diverts(site)){
                site.notched = true;
                site.closed = false;
            }
            sizing = site.waves.isEmpty() ? List.of(site.units) : site.waves;
            sizingAir = site.air;
            List<Choice> choices = choose(site.units, site.closed);
            if(choices.isEmpty()){
                plan.notes.add("nothing we can build holds " + site.units + " at " + site.x + "," + site.y);
                continue;
            }
            lay(site, choices, left);
            plan.sites.add(site);
        }
        return plan;
    }

    /** Places to hold the ground ways: the narrowest place far enough out, taking first those on the most ways at once. */
    List<Site> groundSites(List<Lanes.Lane> ground, List<Map<String, Integer>> groundUnits, List<List<Map<String, Integer>>> groundWaves, float range){
        List<Site> out = new ArrayList<>();
        Terrain t = ask.terrain;
        //held at the wall line, the enemy must be out of range of the core: so the wall line is at least its range away
        float near = Math.max(24f, range + 10f + ask.coreSize), far = near + ask.reach;
        boolean[] covered = new boolean[ground.size()];
        while(true){
            int bestLane = -1, bestIndex = -1;
            float bestScore = Float.MAX_VALUE;
            for(int l = 0; l < ground.size(); l++){
                if(covered[l]) continue;
                Lanes.Lane lane = ground.get(l);
                for(int i = 0; i < lane.tiles.length; i++){
                    float d = lane.toTarget[i];
                    //and not on top of the source: its units need room to come out, and a spawn wipes out what is near it
                    if(d < near || d > far || lane.length() - d < 25f) continue;
                    int tile = lane.tiles[i];
                    if(!roomy(tile)) continue;
                    int x = tile % t.w, y = tile / t.w;
                    int shared = 0;
                    for(int o = 0; o < ground.size(); o++) if(!covered[o] && near(ground.get(o), x, y, 6)) shared++;
                    //narrow is good, near is good, on several ways at once is better, and a place the walls can close best
                    int[] cross = cross(lanes.middle(tile), Lanes.facing(t, lane, i));
                    float score = Math.min(cross[0], 40) + (cross[1] == 1 ? 0f : 20f) + 0.12f * (d - near) - 10f * (shared - 1);
                    if(score < bestScore){
                        bestScore = score;
                        bestLane = l;
                        bestIndex = i;
                    }
                }
            }
            if(bestLane < 0 && Boolean.getBoolean("def.debugsites")){
                for(int l = 0; l < ground.size(); l++){
                    if(covered[l]) continue;
                    Lanes.Lane lane = ground.get(l);
                    int inWindow = 0, room = 0;
                    float minD = Float.MAX_VALUE, maxD = 0f;
                    for(int i = 0; i < lane.tiles.length; i++){
                        float d = lane.toTarget[i];
                        minD = Math.min(minD, d);
                        maxD = Math.max(maxD, d);
                        if(d < near || d > far || lane.length() - d < 25f) continue;
                        inWindow++;
                        if(roomy(lane.tiles[i])) room++;
                    }
                    System.out.println(String.format("    def.debugsites: lane %d from %s: %d tiles, toTarget %.0f..%.0f, length %.0f, window %.0f..%.0f: %d in it, %d roomy", l, ask.sources.get(lane.source).label, lane.tiles.length, minD, maxD, lane.length(), near, far, inWindow, room));
                }
            }
            if(bestLane < 0){
                //a way shorter than the nearest place allowed: hold it as far out as it goes
                for(int l = 0; l < ground.size(); l++) if(!covered[l] && bestLane < 0) bestLane = l;
                if(bestLane < 0) break;
                Lanes.Lane lane = ground.get(bestLane);
                bestIndex = 0;
                for(int i = 0; i < lane.tiles.length; i++) if(lane.toTarget[i] >= near * 0.6f && roomy(lane.tiles[i])) bestIndex = i;
                plan.notes.add("the " + lane.move + " way from " + ask.sources.get(lane.source).label + " is short: held " + Math.round(lane.toTarget[bestIndex]) + " tiles out");
            }
            Lanes.Lane lane = ground.get(bestLane);
            int tile = lane.tiles[bestIndex], x = tile % t.w, y = tile / t.w;
            Map<String, Integer> units = new TreeMap<>();
            List<int[]> site_ways = new ArrayList<>();
            List<Map<String, Integer>> site_waves = new ArrayList<>();
            float arrives = Float.MAX_VALUE;
            StringBuilder why = new StringBuilder();
            for(int o = 0; o < ground.size(); o++){
                if(covered[o] || !near(ground.get(o), x, y, 6)) continue;
                covered[o] = true;
                site_ways.add(new int[]{ground.get(o).source, ground.get(o).move.ordinal()});
                Lanes.Lane other = ground.get(o);
                //waves from different sources come at once: they add up
                groundUnits.get(o).forEach((k, v) -> units.merge(k, v, Integer::sum));
                addWaves(site_waves, groundWaves.get(o));
                float fastest = 1f;
                for(String u : groundUnits.get(o).keySet()) fastest = Math.max(fastest, Foe.get(u).speed);
                int at = Math.min(bestIndex, other.tiles.length - 1);
                arrives = Math.min(arrives, (other.length() - other.toTarget[at]) / fastest);
                if(why.length() > 0) why.append(", ");
                why.append(other.move).append(" from ").append(ask.sources.get(other.source).label);
            }
            int mid = lanes.middle(tile), facing = Lanes.facing(t, lane, bestIndex);
            int[] cross = cross(mid, facing);
            //where the walls cannot close the way, the turrets stand on the way itself, not in the middle of open ground
            if(cross[1] == 0){
                mid = nearestFree(tile);
                cross = cross(mid, facing);
            }
            Site site = new Site(mid % t.w, mid / t.w, facing, why + ", " + cross[0] + " wide at the wall line" + (cross[1] == 1 ? ", closed" : ", too wide to close") + ", " + Math.round(lane.toTarget[bestIndex]) + " out", units, false);
            site.closed = cross[1] == 1;
            site.ways.addAll(site_ways);
            site.waves.addAll(site_waves);
            site.arrives = arrives == Float.MAX_VALUE ? 0f : arrives;
            out.add(site);
        }
        return out;
    }

    void wall(int x, int y){
        Terrain t = ask.terrain;
        if(walled == null) walled = new float[t.w * t.h];
        //a large tungsten wall: 720 health a tile, 2880 for the block, which is what the game counts for each of its tiles
        if(t.in(x, y)) walled[x + y * t.w] = Lanes.wallCost(2880f);
    }

    /**
     * Whether closing the way at a place sends what comes along it round another way: the walls it would have (two thick,
     * rock to rock) priced as the game prices them, and the way from each source it holds found again.
     */
    boolean diverts(Site site){
        Terrain t = ask.terrain;
        Lanes test = new Lanes(t, ask.coreX, ask.coreY);
        test.extra = walled == null ? new float[t.w * t.h] : walled.clone();
        int dir = site.dir, sx = Post.dx[(dir + 1) % 4], sy = Post.dy[(dir + 1) % 4];
        for(int u = 3; u <= 4; u++){
            int cx = site.x + Post.dx[dir] * u, cy = site.y + Post.dy[dir] * u;
            for(int sign = -1; sign <= 1; sign += 2){
                for(int n = sign > 0 ? 0 : 1; n <= ask.wallReach + 1; n++){
                    int x = cx + sx * sign * n, y = cy + sy * sign * n;
                    if(!t.passable(Foe.Move.ground, x, y)) break;
                    test.extra[x + y * t.w] = Lanes.wallCost(2880f);
                }
            }
        }
        for(int[] way : site.ways){
            Foe.Move move = Foe.Move.values()[way[1]];
            if(move == Foe.Move.legs) continue;
            Source src = ask.sources.get(way[0]);
            Lanes.Lane lane = test.lane(way[0], move, src.x, src.y);
            if(lane == null || !near(lane, site.x, site.y, 10)) return true;
        }
        return false;
    }

    /**
     * The way across where the wall line in front of a place would stand (3 tiles ahead of it): how many tiles tanks can
     * cross there, and 1 if rock closes it on both sides within the wall line's reach.
     */
    int[] cross(int tile, int dir){
        Terrain t = ask.terrain;
        int x = tile % t.w + Post.dx[dir] * 3, y = tile / t.w + Post.dy[dir] * 3;
        int sx = Post.dx[(dir + 1) % 4], sy = Post.dy[(dir + 1) % 4];
        int[] side = new int[2];
        boolean closed = true;
        for(int k = 0; k < 2; k++){
            int sign = k == 0 ? 1 : -1, n = 0;
            while(n <= 40 && t.passable(Foe.Move.ground, x + sx * sign * (n + 1), y + sy * sign * (n + 1))) n++;
            side[k] = n;
            if(n > ask.wallReach) closed = false;
        }
        return new int[]{side[0] + side[1] + 1, closed ? 1 : 0, side[0], side[1]};
    }

    /** The free tile nearest to a tile (itself if it is free). */
    int nearestFree(int tile){
        Terrain t = ask.terrain;
        int x = tile % t.w, y = tile / t.w;
        for(int r = 0; r <= 6; r++) for(int dx = -r; dx <= r; dx++) for(int dy = -r; dy <= r; dy++){
            if(Math.max(Math.abs(dx), Math.abs(dy)) == r && t.free(x + dx, y + dy)) return x + dx + (y + dy) * t.w;
        }
        return tile;
    }

    /** Whether there is free ground near a tile to build on (a way through deep liquid or a drop zone is no place to hold). */
    boolean roomy(int tile){
        Terrain t = ask.terrain;
        int x = tile % t.w, y = tile / t.w, free = 0;
        for(int dx = -4; dx <= 4; dx++) for(int dy = -4; dy <= 4; dy++) if(t.free(x + dx, y + dy)) free++;
        return free >= 40;
    }

    /** Whether a lane passes within some tiles of a place. */
    boolean near(Lanes.Lane lane, int x, int y, int within){
        int w = ask.terrain.w;
        for(int tile : lane.tiles){
            int dx = tile % w - x, dy = tile / w - y;
            if(dx * dx + dy * dy <= within * within) return true;
        }
        return false;
    }

    /** Anti-air by the core on the line from each air source; sources within 50 degrees of each other share one. */
    List<Site> airSites(){
        List<Site> out = new ArrayList<>();
        List<Double> angles = new ArrayList<>();
        for(Source s : ask.sources){
            Map<String, Integer> air = unitsOf(s, Foe.Move.air);
            if(air.isEmpty()) continue;
            double a = Math.atan2(s.y - ask.coreY, s.x - ask.coreX);
            Site shared = null;
            for(int i = 0; i < angles.size(); i++){
                double o = angles.get(i);
                if(Math.abs(Math.atan2(Math.sin(a - o), Math.cos(a - o))) < Math.toRadians(50)) shared = out.get(i);
            }
            float speed = 1f;
            for(String u : air.keySet()) speed = Math.max(speed, Foe.get(u).speed);
            float arrives = (float)Math.hypot(s.x - ask.coreX, s.y - ask.coreY) / speed;
            if(shared != null){
                for(var e : air.entrySet()) shared.units.merge(e.getKey(), e.getValue(), Integer::sum);
                addWaves(shared.waves, wavesOf(s, Foe.Move.air));
                shared.arrives = Math.min(shared.arrives, arrives);
                continue;
            }
            angles.add(a);
            //far enough out that flyers coming at the core pass over the turrets and are under fire a while before they reach it,
            //near enough that the turrets still cover the core
            int dist = ask.coreSize + 16;
            int x = ask.coreX + (int)Math.round(Math.cos(a) * dist), y = ask.coreY + (int)Math.round(Math.sin(a) * dist);
            int dir = Math.abs(Math.cos(a)) >= Math.abs(Math.sin(a)) ? (Math.cos(a) >= 0 ? 0 : 2) : (Math.sin(a) >= 0 ? 1 : 3);
            Site site = new Site(x, y, dir, "air from " + s.label + ", by the core", air, true);
            addWaves(site.waves, wavesOf(s, Foe.Move.air));
            site.arrives = arrives;
            out.add(site);
        }
        return out;
    }

    /** Whether a module can be built and fed the way a config says. */
    boolean usable(Battery m, Capacity.Config c){
        for(Battery.Part p : m.parts){
            if(p.optional || p.port()) continue;
            if(!ask.blocks.contains(p.block)) return false;
        }
        if(!c.ammo.equals("-") && !ask.supply.contains(c.ammo)) return false;
        if(c.wet && !ask.supply.contains("water")) return false;
        for(Battery.Part p : m.parts) if(p.port() && !p.optional && !Post.isAmmoPort(m.gun(), p) && !ask.supply.contains(p.config)) return false;
        return true;
    }

    Battery module(String name){
        for(Battery m : ask.modules) if(m.name.equals(name)) return m;
        return null;
    }

    /** What one module costs, as one number: the items it is built of, the machines that make what it eats, its power. */
    float cost(Battery m, Capacity.Config c){
        float total = 0f;
        for(int v : m.cost(ask.costs, false).values()) total += v;
        Gun gun = m.gun();
        int guns = m.guns();
        if(gun != null){
            for(Gun.Ammo a : gun.ammo) if(a.what.equals(c.ammo)) total += (c.wet ? a.wetRate : a.rate) * guns * ask.duty * making.getOrDefault(a.what, 200f);
            if(c.wet) total += gun.water * guns * ask.duty * making.get("water");
            if(gun.needs != null) total += gun.needsRate * guns * (gun.needsWhileFiring ? ask.duty : 1f) * making.getOrDefault(gun.needs, 100f);
            //power: a turbine condenser makes 180 a second for 60 beryllium, and the nodes to bring it
            total += gun.power * guns * 0.4f;
        }
        for(Battery.Part p : m.parts) if(p.block.equals("electric-heater")) total += 100f * 0.4f;
        return total;
    }

    /** The configs that can be built and fed here, with their costs. */
    List<Choice> options(){
        List<Choice> out = new ArrayList<>();
        for(Capacity.Config c : ask.capacity.configs.values()){
            Battery m = module(c.module);
            if(m == null || !usable(m, c)) continue;
            Choice o = new Choice();
            o.config = c;
            o.module = m;
            o.cost = cost(m, c);
            out.add(o);
        }
        return out;
    }

    /** How many of a module a set of units needs, and the cost of that many. */
    float price(Choice o, Map<String, Integer> units, boolean closed){
        float load = load(o.config, units.keySet(), closed);
        if(Float.isInfinite(load)) return Float.POSITIVE_INFINITY;
        return Math.max(1, (int)Math.ceil(load * ask.safety - 0.001f)) * o.cost;
    }

    /**
     * Gives every unit to the module that holds it at the least cost, then folds a module's units into the others (or
     * gives them all to one module that holds the lot) while that is cheaper. Units nothing can hold are noted.
     */
    /**
     * So many modules (ask.fixedModules), of the one kind that is strongest for its cost against these units: what suits
     * them (anti-air for flyers), with the most Lanchester strength (its turrets' damage a second times their health) for
     * the square of its cost (so many of them are so many squared as strong, so this is the same for any count). Whether
     * they hold the units is not asked: that is for whoever chose the count, by playing the fight forward.
     */
    List<Choice> chooseFixed(Map<String, Integer> units){
        Choice best = null;
        float bestValue = 0f;
        for(Choice o : options()){
            boolean fits = false;
            for(String u : units.keySet()) if(suits(o, u)) fits = true;
            Gun gun = o.module.gun();
            if(!fits || gun == null) continue;
            mindustry.world.Block b = mindustry.Vars.content.block(gun.name);
            //(its damage a second with the best of its ammunition: the gun's own is that of what it fires, kept by ammunition)
            float gunDps = gun.dps;
            for(Gun.Ammo a : gun.ammo) gunDps = Math.max(gunDps, a.dps);
            float hp = (b == null ? 0f : b.health) * o.module.guns(), dps = gunDps * o.module.guns();
            float value = dps * hp / Math.max(1f, o.cost * o.cost);
            if(value > bestValue){
                bestValue = value;
                best = o;
            }
        }
        if(best == null){
            StringBuilder why = new StringBuilder();
            for(Choice o : options()){
                Gun gun = o.module.gun();
                why.append(o.module.name).append(gun == null ? " (no gun)" : "").append(' ');
                for(String u : units.keySet()) if(suits(o, u)) why.append("suits ").append(u).append(' ');
                why.append("; ");
            }
            plan.notes.add("nothing we can build suits " + units + " [options: " + why + "]");
            return new ArrayList<>();
        }
        best.count = ask.fixedModules;
        best.units.putAll(units);
        return new ArrayList<>(List.of(best));
    }

    /** Whether the place being chosen for is anti-air by the core. */
    boolean sizingAir;

    /**
     * Whether a module is the kind of answer people use for a unit (reference/turrets-drills-human.md §4.1), besides
     * what the lab saw it hold: flyers coming at the core are met by anti-air turrets (Disperse, Afflict, Breach), never
     * by a Lustre, which kills one big unit at a time and lags behind swarms; and a turret that is only a heavy-killer is
     * given tier 3 and up only, so it never stands alone against massed small units.
     */
    boolean suits(Choice o, String unit){
        Gun gun = o.module.gun();
        Foe foe = Foe.get(unit);
        if(gun == null || foe == null) return false;
        if(sizingAir && !gun.roles.contains(Gun.Role.antiAir)) return false;
        boolean heavyOnly = gun.roles.contains(Gun.Role.heavy) && !gun.roles.contains(Gun.Role.line) && !gun.roles.contains(Gun.Role.antiAir);
        return !heavyOnly || foe.tier >= 3;
    }

    List<Choice> choose(Map<String, Integer> units, boolean closed){
        if(ask.fixedModules > 0) return chooseFixed(units);
        List<Choice> options = options();
        Map<String, Choice> owner = new TreeMap<>();
        for(var e : units.entrySet()){
            Choice best = null;
            float bestCost = Float.POSITIVE_INFINITY;
            for(Choice o : options){
                int cap = suits(o, e.getKey()) ? o.config.holds(e.getKey(), closed) : 0;
                if(cap <= 0) continue;
                float c = o.cost * e.getValue() / (float)cap;
                if(c < bestCost){
                    bestCost = c;
                    best = o;
                }
            }
            if(best == null) plan.notes.add("nothing we can build holds " + e.getKey() + " (" + e.getValue() + "): left to the army");
            else owner.put(e.getKey(), best);
        }
        //fold while it is cheaper
        boolean better = true;
        while(better){
            better = false;
            float now = total(owner, units, closed);
            Set<Choice> used = new LinkedHashSet<>(owner.values());
            for(Choice gone : used){
                Map<String, Choice> trial = new TreeMap<>(owner);
                boolean ok = true;
                for(var e : owner.entrySet()){
                    if(e.getValue() != gone) continue;
                    Choice instead = null;
                    float cheapest = Float.POSITIVE_INFINITY;
                    for(Choice o : used){
                        if(o == gone) continue;
                        int cap = suits(o, e.getKey()) ? o.config.holds(e.getKey(), closed) : 0;
                        if(cap <= 0) continue;
                        if(o.cost / cap < cheapest){
                            cheapest = o.cost / cap;
                            instead = o;
                        }
                    }
                    if(instead == null){
                        ok = false;
                        break;
                    }
                    trial.put(e.getKey(), instead);
                }
                if(ok && total(trial, units, closed) < now - 0.5f){
                    owner = trial;
                    better = true;
                    break;
                }
            }
        }
        //or one module for everything, if that is cheaper still
        Map<String, Integer> held = new TreeMap<>();
        for(String u : owner.keySet()) held.put(u, units.get(u));
        float now = total(owner, units, closed);
        for(Choice o : options){
            if(!held.keySet().stream().allMatch(u -> suits(o, u))) continue;
            if(price(o, held, closed) < now - 0.5f){
                now = price(o, held, closed);
                Map<String, Choice> all = new TreeMap<>();
                for(String u : held.keySet()) all.put(u, o);
                owner = all;
            }
        }
        //as choices with counts
        Map<Choice, Choice> made = new LinkedHashMap<>();
        for(var e : owner.entrySet()){
            Choice c = made.computeIfAbsent(e.getValue(), o -> {
                Choice n = new Choice();
                n.config = o.config;
                n.module = o.module;
                n.cost = o.cost;
                return n;
            });
            c.units.put(e.getKey(), units.get(e.getKey()));
        }
        List<Choice> out = new ArrayList<>(made.values());
        for(Choice c : out) c.count = Math.max(1, (int)Math.ceil(load(c.config, c.units.keySet(), closed) * ask.safety - 0.001f));
        return out;
    }

    float total(Map<String, Choice> owner, Map<String, Integer> units, boolean closed){
        Map<Choice, Map<String, Integer>> parts = new HashMap<>();
        for(var e : owner.entrySet()) parts.computeIfAbsent(e.getValue(), k -> new TreeMap<>()).put(e.getKey(), units.get(e.getKey()));
        float sum = 0f;
        for(var e : parts.entrySet()) sum += price(e.getKey(), e.getValue(), closed);
        return sum;
    }

    /** Lays the chosen modules at a site: line ones in front behind a wall, long-range ones behind; within the budget. */
    void lay(Site site, List<Choice> choices, Map<String, Integer> left){
        Post post = new Post(ask.terrain, taken, modules);
        Post.Ask pa = new Post.Ask();
        //the one that holds the most of the wave first
        choices.sort(Comparator.comparingDouble((Choice c) -> -load(c.config, c.units.keySet(), site.closed) * 1000f - c.units.size()));
        pa.wallReach = ask.wallReach;
        boolean ground = false;
        for(Choice c : choices){
            Gun gun = c.module.gun();
            boolean back = !site.air && gun != null && (gun.roles.contains(Gun.Role.artillery) || gun.roles.contains(Gun.Role.heavy)) && !gun.roles.contains(Gun.Role.line);
            if(!c.covers().equals(EnumSet.of(Foe.Move.air))) ground = true;
            int bought = 0;
            for(int k = 0; k < c.count; k++){
                Map<String, Integer> cost = c.module.cost(ask.costs, true);
                if(!affords(left, cost)){
                    plan.wanted.add(c.module.name + " (" + c.config.key() + ") at " + site.x + "," + site.y);
                    continue;
                }
                spend(left, cost);
                bought++;
                (back ? pa.back : pa.front).add(c.module);
                if(gun != null && !c.config.ammo.equals("-")) pa.ammo.put(gun.name, c.config.ammo);
                if(c.config.wet) pa.wet = true;
            }
            site.chosen.add(String.format((site.notched ? "[walls notched: closed, they would go round] " : "") + "%d x %s (%s) for %s: the worst wave is %.0f%% of what the lab measured they hold%s", c.count, c.module.name, c.config.key(), c.units,
                100f * load(c.config, c.units.keySet(), site.closed) / c.count, bought < c.count ? ", " + bought + " within the budget" : ""));
        }
        //walls in front of a ground line, of the best wall we can build and feed
        if(ground && !site.air){
            for(String w : new String[]{"reinforced-surge", "carbide", "tungsten", "beryllium"}){
                String item = w.equals("reinforced-surge") ? "surge-alloy" : w;
                if(ask.blocks.contains(w + "-wall-large") && ask.supply.contains(item)){
                    pa.wall = w + "-wall-large";
                    pa.smallWall = ask.blocks.contains(w + "-wall") ? w + "-wall" : null;
                    break;
                }
            }
            //one regen projector a defended place, when there is hydrogen for it
            Battery regen = module("regen-support");
            if(regen != null && ask.blocks.contains("regen-projector") && ask.supply.contains("hydrogen") && affords(left, regen.cost(ask.costs, true))){
                spend(left, regen.cost(ask.costs, true));
                pa.back.add(regen);
            }
        }
        if(site.notched) pa.notch = 3;
        //at a place the walls close, the modules stay inside the way they close (not across a spur of rock into the next one)
        if(!site.air && (site.closed || site.notched)){
            int[] cross = cross(site.x + site.y * ask.terrain.w, site.dir);
            pa.vMax = cross[2];
            pa.vMin = -cross[3];
        }
        post.lay(site.x, site.y, site.dir, pa);
        //what these walls add to the way the enemy paths, for the places after this one
        for(Placement w : post.walls){
            int size = Battery.shape(w.block)[0];
            for(int i = 0; i < size; i++) for(int j = 0; j < size; j++) wall(Battery.low(w.x, size) + i, Battery.low(w.y, size) + j);
        }
        //what found no room: nearer the core along the way, or round the core for anti-air
        for(Battery m : post.missed){
            boolean placed = false;
            for(int[] at : fallbacks(site)){
                Post other = new Post(ask.terrain, taken, post.moduleCount);
                Post.Ask one = new Post.Ask();
                one.front.add(m);
                one.ammo.putAll(pa.ammo);
                one.wet = pa.wet;
                other.lay(at[0], at[1], site.dir, one);
                post.moduleCount = other.moduleCount;
                if(other.missed.isEmpty()){
                    post.laid.addAll(other.laid);
                    placed = true;
                    break;
                }
            }
            plan.notes.add((placed ? "moved " : "no room anywhere near for ") + m.name + " at " + site.x + "," + site.y);
        }
        modules = post.moduleCount;
        for(String n : post.notes) if(!n.startsWith("no room")) plan.notes.add(n);
        site.laid.addAll(post.laid);
        site.walls.addAll(post.walls);
        //walls first (they are cheap and take the first hits), then each module in the order chosen
        plan.placements.addAll(post.walls);
        for(Post.Laid l : post.laid){
            plan.placements.addAll(l.parts);
            plan.supplies.add(supply(l));
        }
        List<Placement> all = new ArrayList<>(post.walls);
        for(Post.Laid l : post.laid) all.addAll(l.parts);
        for(Placement p : all){
            if(p.kind == Placement.Kind.port) continue;
            Map<String, Integer> c = ask.costs.get(p.block);
            if(c != null) c.forEach((k, v) -> plan.cost.merge(k, v, Integer::sum));
        }
    }

    /** Other anchors for a module that found no room at its site, nearest first. */
    List<int[]> fallbacks(Site site){
        List<int[]> out = new ArrayList<>();
        if(site.air){
            for(int r = ask.coreSize + 6; r <= ask.coreSize + 24; r += 6) for(int k = 0; k < 8; k++){
                double a = k * Math.PI / 4;
                out.add(new int[]{ask.coreX + (int)Math.round(Math.cos(a) * r), ask.coreY + (int)Math.round(Math.sin(a) * r)});
            }
        }else{
            int bx = -Post.dx[site.dir], by = -Post.dy[site.dir], sx = Post.dx[(site.dir + 1) % 4], sy = Post.dy[(site.dir + 1) % 4];
            for(int back = 6; back <= 30; back += 6) for(int side : new int[]{0, 8, -8, 16, -16}) out.add(new int[]{site.x + bx * back + sx * side, site.y + by * back + sy * side});
        }
        return out;
    }

    static boolean affords(Map<String, Integer> left, Map<String, Integer> cost){
        if(left.isEmpty()) return true;
        for(var e : cost.entrySet()) if(left.getOrDefault(e.getKey(), 0) < e.getValue()) return false;
        return true;
    }

    static void spend(Map<String, Integer> left, Map<String, Integer> cost){
        if(left.isEmpty()) return;
        for(var e : cost.entrySet()) left.merge(e.getKey(), -e.getValue(), Integer::sum);
    }

    /** What a laid module needs fed: from the turret's numbers, while it fires and on average, and where its ports are. */
    Supply supply(Post.Laid l){
        Supply s = new Supply(l.index, l.module.name);
        Gun gun = l.module.gun();
        int guns = l.module.guns();
        float duty = ask.duty;
        if(gun != null){
            for(Gun.Ammo a : gun.ammo){
                if(!a.what.equals(l.ammo)) continue;
                float firing = (l.wet ? a.wetRate : a.rate) * guns;
                s.need(a.what, firing, firing * duty, a.liquid ? "tank behind" : l.module.parts.stream().anyMatch(p -> p.block.equals("reinforced-container")) ? "store of 160" : "no store");
            }
            if(l.wet && gun.water > 0) s.need("water", gun.water * guns, gun.water * guns * duty, "only while firing");
            if(gun.needs != null) s.need(gun.needs, gun.needsRate * guns, gun.needsRate * guns * (gun.needsWhileFiring ? duty : 1f), gun.needsWhileFiring ? "only while firing" : "all the time");
            s.power = gun.power * guns;
            s.heat = gun.heat * guns;
        }
        for(Placement p : l.parts){
            if(p.kind == Placement.Kind.port) s.ports.add(p);
            if(p.block.equals("electric-heater")) s.power += 100f;
            if(p.block.equals("regen-projector")){
                s.power += 60f;
                s.need("hydrogen", 1f, 0.5f, "only while healing");
            }
        }
        return s;
    }
}
