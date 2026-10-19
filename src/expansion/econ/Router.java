package expansion.econ;

import arc.math.geom.*;
import arc.struct.*;
import expansion.Blueprint.Part;
import mindustry.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.liquid.*;

import java.util.*;

/**
 * Lays every line the plant needs, the way chips are routed: each net by A* over (tile, facing) states with the game's own
 * rules for what a piece takes from where, and all of them together by negotiated congestion (PathFinder, McMurchie and
 * Ebeling): nets are routed one by one letting them share tiles at a price, then ripped up and routed again with the
 * price of every tile two nets wanted raised, until no two want the same tile.
 *
 * Kinds of net:
 * <ul>
 * <li>a bus, for an item the core keeps: a spine from the makers past a dock beside each machine that takes it, on to the
 * core. A dock is an underflow duct whose side faces the machine: it hands the machine all it will take and lets the rest
 * go on (where the side across from it is kept clear of anything that takes items). Each maker other than the first is led
 * by a branch onto the spine before its first dock. Without underflow ducts a router does the dock's work, sharing by turns,
 * and the spine ends in the last machine instead of the core;</li>
 * <li>a direct net: from its makers to one machine (or to the core), the first maker's line the trunk and the others'
 * joining it (a duct or conduit takes from its sides);</li>
 * <li>a tap: a running duct of the map's that carries the item, turned into an underflow duct, with a line from its side
 * to the machine.</li>
 * </ul>
 * Lines cross by bridge only (an item line has no junction); a bridge is placed only where the game will link it to the
 * one meant, and nowhere a bridge behind it would take it for its own link.
 */
final class Router{
    final Grid grid;
    final Catalog cat;
    final Block duct, bridge, dock, conduit, pipeBridge;
    /** How often each tile was fought over, and how many nets use it now. */
    final float[] hist;
    final int[] use;
    float pres = 0.6f;
    public int iterations = 14, budget = 250000;
    final StringBuilder log;

    enum Kind{bus, direct, tap}

    static final class Net{
        final int id, res;
        final boolean liquid;
        final Kind kind;
        final List<Machine> from = new ArrayList<>(), to = new ArrayList<>();
        boolean toCore;
        /** What the net was routed to: the pieces from the first maker to the end, each other maker's branch, quiet tiles. */
        final Seq<Part> trunk = new Seq<>();
        final Map<Machine, Seq<Part>> branch = new LinkedHashMap<>();
        final IntSeq quiet = new IntSeq();
        /** The machine whose line is the trunk. */
        Machine first;
        /** Whether its makers are listed in the order they are built, the first to be the trunk (else the furthest is). */
        boolean byOrder;
        /** How each taker is fed: "dock", "spur", "touching", "end of the line", "tap". */
        final Map<Machine, String> feeds = new LinkedHashMap<>();
        boolean ok;
        final List<String> why = new ArrayList<>();
        final IntSet tiles = new IntSet();

        Net(int id, int res, boolean liquid, Kind kind){
            this.id = id;
            this.res = res;
            this.liquid = liquid;
            this.kind = kind;
        }

        Seq<Part> all(){
            Seq<Part> out = new Seq<>(trunk);
            for(Seq<Part> b : branch.values()) out.addAll(b);
            return out;
        }

        @Override
        public String toString(){
            return kind + " " + id + " res " + res;
        }
    }

    /** Where each machine stands. */
    final Map<Machine, Part> at;
    final int[] coreTiles;

    Router(Grid grid, Catalog cat, Map<Machine, Part> at, Set<String> allowed, StringBuilder log){
        this.grid = grid;
        this.cat = cat;
        this.at = at;
        this.log = log;
        duct = Vars.content.block("duct");
        bridge = allowed.contains("duct-bridge") ? Vars.content.block("duct-bridge") : null;
        dock = allowed.contains("underflow-duct") ? Vars.content.block("underflow-duct") : allowed.contains("duct-router") ? Vars.content.block("duct-router") : null;
        conduit = allowed.contains("reinforced-conduit") ? Vars.content.block("reinforced-conduit") : null;
        pipeBridge = allowed.contains("reinforced-bridge-conduit") ? Vars.content.block("reinforced-bridge-conduit") : null;
        hist = new float[grid.w * grid.h];
        use = new int[grid.w * grid.h];
        var core = grid.team.core();
        coreTiles = grid.tiles(core.block, core.tileX(), core.tileY());
    }

    boolean underflow(){
        return dock != null && dock instanceof OverflowDuct;
    }

    static float price(Block b){
        if(b == null) return 1e6f;
        float c = 0;
        for(ItemStack s : b.requirements) c += s.amount;
        return c;
    }

    // ---------------------------------------------------------------- all nets together

    /** Routes every net, negotiating over shared tiles; then puts them on the grid. Returns how many could not be laid whole. */
    int routeAll(List<Net> nets){
        boolean clean = false;
        for(int it = 0; it < iterations && !clean; it++){
            for(Net n : nets){
                //after the first round only the nets that share a tile with another are ripped up
                if(it > 0 && n.ok && !contested(n)) continue;
                rip(n);
                route(n);
                claim(n);
            }
            clean = true;
            for(int p = 0; p < use.length; p++){
                if(use[p] > 1){
                    clean = false;
                    hist[p] += 1f;
                }
            }
            pres *= 1.7f;
        }
        if(!clean) log.append("router: some tiles still wanted by two nets after ").append(iterations).append(" rounds; those nets are laid again one by one\n");
        //on to the grid, in turn; a net that would now overlap or break a rule against what is there is laid again on its own
        for(int i = 0; i < use.length; i++) use[i] = 0;
        int failed = 0;
        for(Net n : nets){
            if(!commit(n)){
                log.append("router: ").append(cat.get(n.res).name).append(" laid again on its own: ").append(refusal).append('\n');
                n.tiles.clear();
                pres = 1e4f;
                route(n);
                if(!commit(n)){
                    n.ok = false;
                    n.why.add("no way through what the other lines took");
                    n.trunk.clear();
                    n.branch.clear();
                    n.quiet.clear();
                }
            }
            if(!n.ok) failed++;
        }
        return failed;
    }

    String refusal = "";

    boolean contested(Net n){
        for(var it = n.tiles.iterator(); it.hasNext;) if(use[it.next()] > 1) return true;
        return false;
    }

    void rip(Net n){
        for(var it = n.tiles.iterator(); it.hasNext;) use[it.next()]--;
        n.tiles.clear();
    }

    void claim(Net n){
        for(Part p : n.all()) for(int t : grid.tiles(p)) n.tiles.add(t);
        for(int i = 0; i < n.quiet.size; i++) n.tiles.add(n.quiet.get(i));
        for(var it = n.tiles.iterator(); it.hasNext;) use[it.next()]++;
    }

    /** Puts a net's pieces on the grid, if every tile is still free and the quiet ones hold nothing that would take. */
    boolean commit(Net n){
        Seq<Part> all = n.all();
        for(Part p : all){
            //a tap's dock goes over the map's own duct
            boolean over = p.block == dock && grid.planned(p.x, p.y) == null && grid.block(p.x, p.y) instanceof Duct;
            for(int t : grid.tiles(p)) if((!over && !grid.free(t % grid.w, t / grid.w)) || grid.quiet[t]){
                refusal = String.format("%s at %d,%d: %s", p.block.name, p.x, p.y, grid.quiet[t] ? "a quiet tile" : "taken by " + (grid.block(t % grid.w, t / grid.w) == null ? "?" : grid.block(t % grid.w, t / grid.w).name));
                return false;
            }
        }
        for(int i = 0; i < n.quiet.size; i++){
            int q = n.quiet.get(i);
            Block b = grid.block(q % grid.w, q / grid.w);
            if(b != null && Grid.takesItems(b)){
                refusal = "quiet tile holds " + b.name;
                return false;
            }
        }
        for(Part p : all) grid.plan(p, n.id, n.res, -1);
        for(int i = 0; i < n.quiet.size; i++) grid.quiet[n.quiet.get(i)] = true;
        //and what it now is against the rest: anything pushing a wrong thing into it, a bridge linking across nets
        for(Part p : all){
            String bad = wrongInto(p, n);
            if(bad == null && p.block instanceof DirectionBridge db) bad = crossLink(p, db, n);
            if(bad != null){
                refusal = bad;
                for(Part q : all) grid.unplan(q);
                for(int i = 0; i < n.quiet.size; i++) grid.quiet[n.quiet.get(i)] = false;
                return false;
            }
        }
        for(Part p : all) for(int t : grid.tiles(p)) use[t]++;
        return true;
    }

    /** A bridge of this net that would link to another net's, or another net's that would link to it. */
    String crossLink(Part p, DirectionBridge db, Net n){
        int[] link = grid.bridgeLink(p.x, p.y);
        if(link != null && grid.net[grid.pos(link[0], link[1])] != n.id) return "bridge at " + p.x + "," + p.y + " would link to another line's";
        //(a bridge links to the first of its kind ahead of it, whichever way that one faces)
        for(int d = 0; d < 4; d++){
            for(int i = 1; i <= db.range; i++){
                int x = p.x + Geometry.d4x(d) * i, y = p.y + Geometry.d4y(d) * i;
                if(!grid.in(x, y)) break;
                if(grid.block(x, y) != p.block) continue;
                int[] l = grid.bridgeLink(x, y);
                if(l != null && l[0] == p.x && l[1] == p.y && grid.net[grid.pos(x, y)] != n.id) return "another line's bridge at " + x + "," + y + " would link to this one's";
                break;
            }
        }
        return null;
    }

    /** What, if anything, would push something else into this piece of a net, as it stands on the grid. */
    String wrongInto(Part p, Net n){
        //a bridge end (no link of its own) takes nothing from beside it
        if(p.block instanceof DirectionBridge && grid.bridgeLink(p.x, p.y) == null) return null;
        for(int j = 0; j < 4; j++){
            int mx = p.x + Geometry.d4x(j), my = p.y + Geometry.d4y(j);
            if(!grid.in(mx, my) || !accepts(p.block, p.rotation, j, grid.block(mx, my))) continue;
            int v = grid.pushes(mx, my, p.x, p.y, n.liquid);
            if(v != -1 && v != n.res) return (grid.block(mx, my) == null ? "?" : grid.block(mx, my).name) + " at " + mx + "," + my + " into " + p.block.name + " at " + p.x + "," + p.y;
        }
        return null;
    }

    // ---------------------------------------------------------------- one net

    void route(Net n){
        n.trunk.clear();
        n.branch.clear();
        n.quiet.clear();
        n.why.clear();
        n.feeds.clear();
        n.ok = true;
        own.clear();
        ownQuiet.clear();
        join.clear();
        Block piece = n.liquid ? conduit : duct;
        if(piece == null){
            n.ok = false;
            n.why.add("nothing to lay it with");
            return;
        }
        switch(n.kind){
            case bus -> bus(n);
            case direct -> direct(n);
            case tap -> tap(n);
        }
    }

    /** The net's pieces laid so far, and its quiet tiles, while it is being routed. */
    final IntMap<Part> own = new IntMap<>();
    final IntSet ownQuiet = new IntSet();
    /** Pieces of the net a branch may run into. */
    final IntMap<Part> join = new IntMap<>();

    void keep(Net n, Seq<Part> into, Path path, boolean joinable){
        for(Part p : path.parts){
            into.add(p);
            for(int t : grid.tiles(p)) own.put(t, p);
            if(joinable) join.put(grid.pos(p.x, p.y), p);
        }
        for(int i = 0; i < path.quiet.size; i++){
            ownQuiet.add(path.quiet.get(i));
            n.quiet.add(path.quiet.get(i));
        }
    }

    void bus(Net n){
        //the machines that a maker of the net already touches and feeds need no dock
        List<Machine> takers = new ArrayList<>();
        for(Machine c : n.to){
            if(!fedDirect(n, c)) takers.add(c);
            else n.feeds.put(c, "touching a maker");
        }
        if(n.from.isEmpty()) return;
        boolean last = !n.toCore && !takers.isEmpty();
        Goal end;
        int[] endAt;
        if(n.toCore){
            end = core(n);
            endAt = new int[]{grid.team.core().tileX(), grid.team.core().tileY()};
        }else if(!takers.isEmpty()){
            Machine c = farthestTaker(n, takers);
            takers.remove(c);
            takers.add(c);
            end = into(at.get(c));
            endAt = new int[]{at.get(c).x, at.get(c).y};
        }else{
            return;
        }
        //the first maker: the one furthest from the end, so the others join on the way
        Machine first = null;
        float far = -1;
        for(Machine m : n.from){
            Part p = at.get(m);
            if(p == null) continue;
            float d = Math.abs(p.x - endAt[0]) + Math.abs(p.y - endAt[1]);
            if(d > far){
                far = d;
                first = m;
            }
        }
        if(first == null) return;
        n.first = first;
        List<Machine> docks = new ArrayList<>(takers);
        if(last) docks.remove(docks.size() - 1);
        docks = tour(at.get(first), docks, endAt);
        //takers with no room for a dock against them are fed by a spur: a dock on the spine where there is room, and a
        //short line from its side to them
        List<Stage> stages = new ArrayList<>();
        List<Machine> spurs = new ArrayList<>();
        for(Machine c : docks){
            Stage s = dockStage(n, c);
            if(s.docks.isEmpty()) spurs.add(c);
            else stages.add(s);
        }
        Path trunk = search(n, starts(n, first), stages, end, endAt);
        //a spine that has to come back over itself (a dock beyond the end) is no spine: those takers go on spurs
        if(trunk != null && !stages.isEmpty() && crosses(trunk)) trunk = null;
        if(trunk == null && !stages.isEmpty()){
            //no way past every dock: the spine straight to the end, and every taker on a spur
            for(Stage s : stages) spurs.add(s.taker);
            stages.clear();
            trunk = search(n, starts(n, first), stages, end, endAt);
        }
        if(trunk == null){
            n.ok = false;
            n.why.add(cat.get(n.res).name + " from " + first.recipe.name + " at " + at.get(first).x + "," + at.get(first).y + " to " + (n.toCore ? "the core" : "the last taker"));
            return;
        }
        //a branch joins the spine before its first dock: what comes after it would pass the takers by
        int upTo = trunk.firstDock < 0 ? trunk.parts.size : trunk.firstDock;
        for(int i = 0; i < trunk.parts.size; i++){
            Part p = trunk.parts.get(i);
            n.trunk.add(p);
            for(int t : grid.tiles(p)) own.put(t, p);
            if(i < upTo) join.put(grid.pos(p.x, p.y), p);
        }
        for(int i = 0; i < trunk.quiet.size; i++){
            ownQuiet.add(trunk.quiet.get(i));
            n.quiet.add(trunk.quiet.get(i));
        }
        for(Stage st : stages) n.feeds.put(st.taker, "dock on the spine");
        if(last) n.feeds.put(takers.get(takers.size() - 1), "end of the spine");
        branches(n, first, last ? at.get(takers.get(takers.size() - 1)) : null);
        for(Machine c : spurs){
            spur(n, c);
            n.feeds.put(c, n.branch.containsKey(c) ? "spur off the spine" : "not fed: no spur found");
        }
    }

    /** Whether a path puts two pieces on one tile (its stages let it pass a tile again on the way back). */
    boolean crosses(Path path){
        IntSet seen = new IntSet();
        for(Part p : path.parts) for(int t : grid.tiles(p)) if(!seen.add(t)) return true;
        return false;
    }

    /**
     * Feeds a taker off its own spine: a plain duct of the spine (one no branch runs into from the side) becomes a dock,
     * and a line goes from the dock's side to the taker. Docks further down the spine are a little preferred: everything
     * that joins the spine has joined by then.
     */
    void spur(Net n, Machine c){
        Part cp = at.get(c);
        List<Start> starts = new ArrayList<>();
        for(int i = 0; i < n.trunk.size; i++){
            Part p = n.trunk.get(i);
            if(p.block != duct) continue;
            if(Math.abs(p.x - cp.x) + Math.abs(p.y - cp.y) > 60) continue;
            //a branch that runs into this piece from the side would find a dock shut to it
            boolean fed = false;
            for(int j = 0; j < 4; j++){
                if(j == p.rotation || j == (p.rotation + 2) % 4) continue;
                Part o = own.get(grid.pos(p.x + Geometry.d4x(j), p.y + Geometry.d4y(j)));
                if(o != null && o.x + Geometry.d4x(o.rotation) == p.x && o.y + Geometry.d4y(o.rotation) == p.y) fed = true;
            }
            //and an underflow duct takes only from straight behind: the piece before must push straight in
            if(i == 0) continue;
            Part before = n.trunk.get(i - 1);
            if(before.rotation != p.rotation || before.x + Geometry.d4x(before.rotation) != p.x || before.y + Geometry.d4y(before.rotation) != p.y) fed = true;
            if(fed) continue;
            for(int side : new int[]{(p.rotation + 1) % 4, (p.rotation + 3) % 4}){
                int ax = p.x - Geometry.d4x(side), ay = p.y - Geometry.d4y(side);
                if(!grid.in(ax, ay)) continue;
                int a = grid.pos(ax, ay);
                Block across = grid.block(ax, ay);
                Part mine = own.get(a);
                if(mine != null || (across != null && (Grid.takesItems(across) || grid.uses(across, n.res)))) continue;
                Start st = new Start(p.x, p.y, side, new Part(dock, p.x, p.y, p.rotation, null));
                st.quiet = across == null && grid.free(ax, ay) ? a : -1;
                st.cost = 0.05f * (n.trunk.size - i);
                st.replaces = i;
                starts.add(st);
            }
        }
        Path path = starts.isEmpty() ? null : search(n, starts, List.of(), into(cp), new int[]{cp.x, cp.y});
        if(path == null){
            n.ok = false;
            n.why.add(cat.get(n.res).name + " on a spur off its spine to " + c.recipe.name + " at " + cp.x + "," + cp.y);
            return;
        }
        Part d = path.parts.first();
        int i = path.replaces;
        n.trunk.set(i, d);
        for(int t : grid.tiles(d)) own.put(t, d);
        Seq<Part> mine = new Seq<>();
        for(int k = 1; k < path.parts.size; k++){
            Part p = path.parts.get(k);
            mine.add(p);
            for(int t : grid.tiles(p)) own.put(t, p);
        }
        for(int k = 0; k < path.quiet.size; k++){
            ownQuiet.add(path.quiet.get(k));
            n.quiet.add(path.quiet.get(k));
        }
        n.branch.put(c, mine);
    }

    void direct(Net n){
        if(n.from.isEmpty()) return;
        Machine c = n.to.isEmpty() ? null : n.to.get(0);
        if(c != null && at.get(c) == null) return;
        Goal end = c == null ? core(n) : into(at.get(c));
        int[] endAt = c == null ? new int[]{grid.team.core().tileX(), grid.team.core().tileY()} : new int[]{at.get(c).x, at.get(c).y};
        //makers that touch the taker and hand it straight over have nothing to lay
        List<Machine> makers = new ArrayList<>();
        for(Machine m : n.from) if(at.get(m) != null && !(c != null && touchFeeds(n, m, c))) makers.add(m);
        if(makers.isEmpty()) return;
        //the trunk from the furthest maker, so the others join on the way; or, where the order matters (a pipe goes up with
        //the later of its ends), from the first maker to be built
        if(!n.byOrder) makers.sort(Comparator.comparingDouble(m -> -(Math.abs(at.get(m).x - endAt[0]) + Math.abs(at.get(m).y - endAt[1]))));
        Machine first = makers.get(0);
        n.first = first;
        Path trunk = search(n, starts(n, first), List.of(), end, endAt);
        //no way from that one: from another of its makers, then
        for(int i = 1; trunk == null && i < makers.size(); i++){
            Path p = search(n, starts(n, makers.get(i)), List.of(), end, endAt);
            if(p != null){
                trunk = p;
                first = makers.get(i);
                n.first = first;
            }
        }
        if(trunk == null){
            n.ok = false;
            n.why.add(cat.get(n.res).name + " from " + first.recipe.name + " at " + at.get(first).x + "," + at.get(first).y + " to " + (c == null ? "the core" : c.recipe.name + " at " + at.get(c).x + "," + at.get(c).y) + stats());
            return;
        }
        keep(n, n.trunk, trunk, true);
        if(c != null) n.feeds.put(c, "end of the line");
        branches(n, first, c == null ? null : at.get(c));
    }

    void tap(Net n){
        Machine c = n.to.get(0);
        Part cp = at.get(c);
        if(cp == null || dock == null) return;
        List<Start> starts = new ArrayList<>();
        for(var e : grid.running){
            if(e.value != n.res) continue;
            int t = e.key, tx = t % grid.w, ty = t / grid.w;
            Block b = grid.block(tx, ty);
            if(!(b instanceof Duct)) continue;
            if(Math.abs(tx - cp.x) + Math.abs(ty - cp.y) > 120) continue;
            int rot = grid.rotation(tx, ty);
            for(int side : new int[]{(rot + 1) % 4, (rot + 3) % 4}){
                int qx = tx - Geometry.d4x(side), qy = ty - Geometry.d4y(side);
                //the side across from the branch must not take anything
                if(!grid.in(qx, qy)) continue;
                Block across = grid.block(qx, qy);
                if(across != null && Grid.takesItems(across)) continue;
                if(across == null && !grid.free(qx, qy) && grid.ground[grid.pos(qx, qy)]) continue;
                Start s = new Start(tx, ty, side, new Part(dock, tx, ty, rot, null));
                s.quiet = across == null && grid.free(qx, qy) ? grid.pos(qx, qy) : -1;
                starts.add(s);
            }
        }
        if(starts.isEmpty()){
            n.ok = false;
            n.why.add("no running line of " + cat.get(n.res).name + " to take it off");
            return;
        }
        Path path = search(n, starts, List.of(), into(cp), new int[]{cp.x, cp.y});
        if(path == null){
            n.ok = false;
            n.why.add(cat.get(n.res).name + " off a running line to " + c.recipe.name);
            return;
        }
        keep(n, n.trunk, path, false);
        n.feeds.put(c, "tap off a line of the map's");
    }

    /** Leads every maker of a net but the first onto what is laid of it, the nearest first. */
    void branches(Net n, Machine first, Part end){
        List<Machine> rest = new ArrayList<>();
        for(Machine m : n.from) if(m != first && at.get(m) != null) rest.add(m);
        Machine c = n.kind == Kind.direct && !n.to.isEmpty() ? n.to.get(0) : null;
        while(!rest.isEmpty()){
            Machine next = null;
            float bestD = Float.MAX_VALUE;
            for(Machine m : rest){
                Part p = at.get(m);
                float d = Float.MAX_VALUE;
                for(var e : join){
                    d = Math.min(d, Math.abs(e.value.x - p.x) + Math.abs(e.value.y - p.y));
                    if(d <= 2) break;
                }
                if(d < bestD){
                    bestD = d;
                    next = m;
                }
            }
            rest.remove(next);
            if(c != null && touchFeeds(n, next, c)) continue;
            //on a bus, a maker against a taker hands it straight over (and what it can't take waits)
            //(not when the spine goes on to the core: what the taker can't take would be lost to the core)
            boolean against = false;
            if(n.kind == Kind.bus && !n.toCore) for(Machine t : n.to) if(touchFeeds(n, next, t)) against = true;
            if(against) continue;
            Seq<Part> mine = new Seq<>();
            n.branch.put(next, mine);
            //it may touch the spine already, and hand straight onto it
            if(touches(n, next)) continue;
            //onto the line, or (when the line ends in a taker) straight into that taker
            Goal goal = end == null ? joinGoal(n) : either(joinGoal(n), into(end));
            Path path = search(n, starts(n, next), List.of(), goal, null);
            if(path == null){
                n.ok = false;
                n.why.add(cat.get(n.res).name + " from " + next.recipe.name + " at " + at.get(next).x + "," + at.get(next).y + " onto its line");
                continue;
            }
            keep(n, mine, path, true);
        }
    }

    /** Whether a maker stands against a piece of its net that takes from it. */
    boolean touches(Net n, Machine m){
        Part p = at.get(m);
        for(int[] t : Grid.around(p)){
            Part q = join.get(grid.pos(t[0], t[1]));
            if(q != null && accepts(q.block, q.rotation, (t[2] + 2) % 4, p.block) && pushesOn(n, m, t[2])) return true;
        }
        return false;
    }

    /** Whether a maker gives the net's thing out of one of its sides. */
    boolean pushesOn(Net n, Machine m, int side){
        if(!n.liquid) return m.out(n.res) > 1e-4f;
        Part p = at.get(m);
        return grid.liquidOut(p.block, p.rotation, side) == n.res;
    }

    boolean touchFeeds(Net n, Machine m, Machine c){
        Part p = at.get(m), q = at.get(c);
        if(p == null || q == null) return false;
        int[] tq = grid.tiles(q);
        for(int[] t : Grid.around(p)){
            int pos = grid.pos(t[0], t[1]);
            for(int x : tq) if(x == pos && pushesOn(n, m, t[2])) return true;
        }
        return false;
    }

    boolean fedDirect(Net n, Machine c){
        for(Machine m : n.from) if(touchFeeds(n, m, c)) return true;
        return false;
    }

    Machine farthestTaker(Net n, List<Machine> takers){
        Machine best = null;
        float far = -1;
        for(Machine c : takers){
            float d = 0;
            for(Machine m : n.from) if(at.get(m) != null) d += Math.abs(at.get(m).x - at.get(c).x) + Math.abs(at.get(m).y - at.get(c).y);
            if(d > far){
                far = d;
                best = c;
            }
        }
        return best;
    }

    /** The order to pass the takers in, from a start to an end: nearest first, then bettered by 2-opt. */
    List<Machine> tour(Part start, List<Machine> stops, int[] end){
        List<Machine> order = new ArrayList<>();
        List<Machine> left = new ArrayList<>(stops);
        int cx = start.x, cy = start.y;
        while(!left.isEmpty()){
            Machine best = null;
            int bd = Integer.MAX_VALUE;
            for(Machine m : left){
                int d = Math.abs(at.get(m).x - cx) + Math.abs(at.get(m).y - cy);
                if(d < bd){ bd = d; best = m; }
            }
            order.add(best);
            left.remove(best);
            cx = at.get(best).x;
            cy = at.get(best).y;
        }
        boolean better = true;
        while(better){
            better = false;
            for(int i = 0; i < order.size(); i++){
                for(int j = i + 1; j < order.size(); j++){
                    List<Machine> t = new ArrayList<>(order);
                    Collections.reverse(t.subList(i, j + 1));
                    if(length(start, t, end) < length(start, order, end)){
                        order = t;
                        better = true;
                    }
                }
            }
        }
        return order;
    }

    int length(Part start, List<Machine> order, int[] end){
        int x = start.x, y = start.y, d = 0;
        for(Machine m : order){
            d += Math.abs(at.get(m).x - x) + Math.abs(at.get(m).y - y);
            x = at.get(m).x;
            y = at.get(m).y;
        }
        return d + Math.abs(end[0] - x) + Math.abs(end[1] - y);
    }

    // ---------------------------------------------------------------- goals, starts and stages

    interface Goal{
        /** Whether a piece pushing in direction d into tile q is done there. */
        boolean hit(int q, int d);

        /** Some of its tiles, for the estimate. */
        IntSeq sample();
    }

    Goal into(Part machine){
        IntSet tiles = new IntSet();
        IntSeq sample = new IntSeq();
        for(int t : grid.tiles(machine)){
            tiles.add(t);
            sample.add(t);
        }
        return new Goal(){
            public boolean hit(int q, int d){
                return tiles.contains(q);
            }

            public IntSeq sample(){
                return sample;
            }
        };
    }

    /** The core, or a running duct of the map's that carries the thing there. */
    Goal core(Net n){
        IntSet tiles = new IntSet();
        IntSeq sample = new IntSeq();
        for(int t : coreTiles){
            tiles.add(t);
            sample.add(t);
        }
        IntSet lines = new IntSet();
        if(!n.liquid) for(var e : grid.running) if(e.value == n.res){ lines.add(e.key); if(sample.size < 48) sample.add(e.key); }
        return new Goal(){
            public boolean hit(int q, int d){
                if(tiles.contains(q)) return true;
                if(!lines.contains(q)) return false;
                Block b = grid.block(q % grid.w, q / grid.w);
                return b instanceof Duct && accepts(b, grid.rotation(q % grid.w, q / grid.w), (d + 2) % 4, duct);
            }

            public IntSeq sample(){
                return sample;
            }
        };
    }

    Goal joinGoal(Net n){
        IntSeq sample = new IntSeq();
        int k = 0;
        for(var e : join) if(k++ % Math.max(1, join.size / 48) == 0) sample.add(e.key);
        Block piece = n.liquid ? conduit : duct;
        return new Goal(){
            public boolean hit(int q, int d){
                Part p = join.get(q);
                return p != null && grid.pos(p.x, p.y) == q && accepts(p.block, p.rotation, (d + 2) % 4, piece);
            }

            public IntSeq sample(){
                return sample;
            }
        };
    }

    Goal either(Goal a, Goal b){
        IntSeq sample = new IntSeq(a.sample());
        sample.addAll(b.sample());
        return new Goal(){
            public boolean hit(int q, int d){
                return a.hit(q, d) || b.hit(q, d);
            }

            public IntSeq sample(){
                return sample;
            }
        };
    }

    /** A place a line may begin: a piece pushing in direction d into tile (x, y); with a part to put down there first (a tap's dock). */
    static final class Start{
        final int x, y, d;
        final Part first;
        int quiet = -1, replaces = -1;
        float cost;

        Start(int x, int y, int d, Part first){
            this.x = x;
            this.y = y;
            this.d = d;
            this.first = first;
        }
    }

    /** Lines out of a machine: from each side it gives the thing out of, into the tile beside it. */
    List<Start> starts(Net n, Machine m){
        Part p = at.get(m);
        List<Start> out = new ArrayList<>();
        for(int[] t : Grid.around(p)){
            if(!pushesOn(n, m, t[2])) continue;
            //(x, y) is where the first piece goes; the virtual piece before it is the machine, pushing outwards
            out.add(new Start(t[0] - Geometry.d4x(t[2]), t[1] - Geometry.d4y(t[2]), t[2], null));
        }
        return out;
    }

    /** Where a dock beside a machine may go: on a tile against its edge, facing along the edge, so one side faces the machine. */
    static final class Stage{
        final Machine taker;
        /** Dock tile -> the facings it may have, each with its quiet tile (or -1). */
        final IntMap<IntSeq> docks = new IntMap<>();
        final IntSeq sample = new IntSeq();

        Stage(Machine taker){
            this.taker = taker;
        }
    }

    Stage dockStage(Net n, Machine c){
        Stage s = new Stage(c);
        Part p = at.get(c);
        for(int[] t : Grid.around(p)){
            int u = grid.pos(t[0], t[1]);
            if(!grid.free(t[0], t[1]) || grid.quiet[u]) continue;
            int qx = t[0] + Geometry.d4x(t[2]), qy = t[1] + Geometry.d4y(t[2]);
            if(!grid.in(qx, qy)) continue;
            Block across = grid.block(qx, qy);
            if(across != null && (Grid.takesItems(across) || grid.uses(across, n.res))) continue;
            int quiet = across == null && grid.free(qx, qy) ? grid.pos(qx, qy) : -1;
            //a tile across that is not ours, not free and not rock (another side's building) is left alone
            if(across == null && quiet < 0 && grid.ground[grid.pos(qx, qy)] && Vars.world.build(qx, qy) != null) continue;
            IntSeq ways = s.docks.get(u, IntSeq::new);
            ways.add((t[2] + 1) % 4);
            ways.add(quiet);
            ways.add((t[2] + 3) % 4);
            ways.add(quiet);
            s.sample.add(u);
        }
        return s;
    }

    // ---------------------------------------------------------------- the search

    static final class Path{
        final Seq<Part> parts = new Seq<>();
        final IntSeq quiet = new IntSeq();
        int firstDock = -1, replaces = -1;
    }

    /** Whether a line piece facing rot takes from the neighbour in direction j (a block that may be null). */
    static boolean accepts(Block piece, int rot, int j, Block from){
        if(j == rot) return false;
        if(piece instanceof OverflowDuct || piece instanceof DuctRouter) return j == (rot + 2) % 4;
        if(piece instanceof DirectionBridge) return true;
        if(piece instanceof Conduit){
            boolean pipe = from instanceof Conduit || from instanceof DirectionBridge || from instanceof LiquidJunction;
            return pipe || j == (rot + 2) % 4;
        }
        if(piece instanceof Duct) return true;
        return false;
    }

    /** Whether a piece may go on a tile at all for this net. */
    boolean open(int q, Block piece, Net n){
        int x = q % grid.w, y = q / grid.w;
        if(!grid.free(x, y) || grid.quiet[q] || ownQuiet.contains(q) || own.containsKey(q) || blocked.contains(q)) return false;
        if(piece.solid && grid.noSolid[q]) return false;
        return true;
    }

    /** Whether a piece put on tile q facing rot gets nothing it should not from what stands or is planned round it. */
    boolean clean(int q, Block piece, int rot, Net n){
        int x = q % grid.w, y = q / grid.w;
        for(int j = 0; j < 4; j++){
            int mx = x + Geometry.d4x(j), my = y + Geometry.d4y(j);
            if(!grid.in(mx, my)) continue;
            Block from = grid.block(mx, my);
            if(from == null || !accepts(piece, rot, j, from)) continue;
            int v = grid.pushes(mx, my, x, y, n.liquid);
            if(v != -1 && v != n.res) return false;
        }
        return true;
    }

    /** No bridge of this kind between two tiles, none ahead of the end that it would link on to, none behind the start that would take it as its link. */
    boolean bridgeClear(Block b, int sx, int sy, int ex, int ey, int rot, int range){
        int dx = Geometry.d4x(rot), dy = Geometry.d4y(rot);
        for(int x = sx + dx, y = sy + dy; x != ex || y != ey; x += dx, y += dy) if(grid.block(x, y) == b || own.containsKey(grid.pos(x, y)) && own.get(grid.pos(x, y)).block == b) return false;
        for(int i = 1; i <= range; i++){
            int x = ex + dx * i, y = ey + dy * i;
            if(!grid.in(x, y)) break;
            if(grid.block(x, y) == b) return false;
        }
        for(int i = 1; i <= range; i++){
            int x = sx - dx * i, y = sy - dy * i;
            if(!grid.in(x, y)) break;
            if(grid.block(x, y) == b && grid.rotation(x, y) == rot) return false;
            Part o = own.get(grid.pos(x, y));
            if(o != null && o.block == b && o.rotation == rot) return false;
        }
        //and no bridge of another line facing either end from any side with nothing between: it would link to it
        for(int[] end : new int[][]{{sx, sy}, {ex, ey}}){
            for(int d = 0; d < 4; d++){
                for(int i = 1; i <= range; i++){
                    int x = end[0] + Geometry.d4x(d) * i, y = end[1] + Geometry.d4y(d) * i;
                    if(!grid.in(x, y)) break;
                    if(grid.block(x, y) != b) continue;
                    if(grid.rotation(x, y) == (d + 2) % 4 && grid.planned(x, y) != null || grid.rotation(x, y) == (d + 2) % 4 && Vars.world.build(x, y) != null) return false;
                    break;
                }
            }
        }
        return true;
    }

    float step(int q, float base){
        //ground not seen yet: a part there waits for a unit to look, so a way round it is worth a little
        float c = base + grid.kept[q] + hist[q] + (grid.dark[q] ? 2f : 0f);
        return c * (1f + pres * use[q]);
    }

    /**
     * A* from the starts through every stage (a dock beside each taker in turn) to the goal. A state is a piece on a tile
     * with a facing, pushing into the tile in front of it, at a stage; from it the next tile gets a piece facing ahead or to
     * either side (a duct takes from behind and its sides), or a bridge to up to its range ahead, or, when it is a dock's
     * tile, the dock.
     */
    /** How the last search went: states looked at, and places it could begin. */
    int lastExpanded, lastStarts;

    String stats(){
        return String.format(" [searched %d states%s from %d starts]", lastExpanded, lastExpanded > budget ? ", out of budget" : "", lastStarts);
    }

    /** Tiles a search may not use, for laying a path again that had crossed itself. */
    final IntSet blocked = new IntSet();

    /** A path from the starts through the stages to the goal that never puts two pieces on one tile. */
    Path search(Net n, List<Start> starts, List<Stage> stages, Goal goal, int[] endAt){
        blocked.clear();
        for(int round = 0; round < 5; round++){
            Path p = searchOnce(n, starts, stages, goal, endAt);
            if(p == null || !crosses(p)){
                blocked.clear();
                return p;
            }
            //the tiles it came back over are shut to it, and it is laid again
            IntSet seen = new IntSet();
            for(Part part : p.parts) for(int t : grid.tiles(part)) if(!seen.add(t)) blocked.add(t);
        }
        blocked.clear();
        return null;
    }

    Path searchOnce(Net n, List<Start> starts, List<Stage> stages, Goal goal, int[] endAt){
        Block piece = n.liquid ? conduit : duct, jump = n.liquid ? pipeBridge : bridge;
        float pieceCost = price(piece), jumpCost = price(jump), dockCost = price(dock);
        int N = grid.w * grid.h, S = stages.size() + 1;
        int range = jump instanceof DirectionBridge db ? db.range : 0;
        IntFloatMap g = new IntFloatMap();
        IntIntMap parent = new IntIntMap(), how = new IntIntMap();
        Heap open = new Heap();
        //estimates: distance to the stage's targets, plus what is left of the tour
        IntSeq[] targets = new IntSeq[S];
        float[] tail = new float[S];
        for(int s = 0; s < S; s++) targets[s] = s < S - 1 ? stages.get(s).sample : goal.sample();
        for(int s = S - 2; s >= 0; s--) tail[s] = tail[s + 1] + 0.7f * pieceCost * between(targets[s], targets[s + 1]);
        //starts: the piece before the first is the machine (or a tap's dock, put down with it)
        for(int i = 0; i < starts.size(); i++){
            Start st = starts.get(i);
            int key = (st.y * grid.w + st.x) * 4 + st.d;
            float h0 = st.cost;
            if(!g.containsKey(key) || g.get(key, 0f) > h0){
                g.put(key, h0);
                parent.put(key, -1);
                how.put(key, -1 - i);
                open.add(key, h0 + estimate(key, 0, targets, tail, N));
            }
        }
        int expanded = 0, done = -1;
        while(open.size > 0){
            int key = open.poll();
            float gk = g.get(key, Float.MAX_VALUE);
            if(open.lastF > gk + estimate(key, (key / 4) / N, targets, tail, N) + 1e-3f) continue;
            if(++expanded > budget) break;
            int d = key % 4, cell = key / 4, s = cell / N, p = cell % N;
            int px = p % grid.w, py = p / grid.w;
            int qx = px + Geometry.d4x(d), qy = py + Geometry.d4y(d);
            if(!grid.in(qx, qy)) continue;
            int q = grid.pos(qx, qy);
            if(s == S - 1){
                if(goal.hit(q, d)){
                    done = key;
                    break;
                }
            }else{
                IntSeq ways = stages.get(s).docks.get(q);
                if(ways != null && open(q, dock, n)){
                    for(int w = 0; w < ways.size; w += 2){
                        if(ways.get(w) != d) continue;
                        int quiet = ways.get(w + 1);
                        if(quiet >= 0 && (own.containsKey(quiet) || ownQuiet.contains(quiet))) continue;
                        int next = ((s + 1) * N + q) * 4 + d;
                        relax(g, parent, how, open, key, next, gk + step(q, dockCost), DOCK | (quiet + 1), targets, tail, N);
                    }
                }
            }
            if(!open(q, piece, n)) continue;
            //a conduit takes from a machine only when it points straight away from it
            int hk = how.get(key, 0);
            boolean fromMachine = hk < 0 && starts.get(-1 - hk).first == null;
            for(int turn : new int[]{0, 1, 3}){
                if(turn != 0 && n.liquid && fromMachine) continue;
                int r = (d + turn) % 4;
                if(clean(q, piece, r, n)){
                    int next = (s * N + q) * 4 + r;
                    relax(g, parent, how, open, key, next, gk + step(q, pieceCost) + (turn == 0 ? 0f : 0.15f), 0, targets, tail, N);
                }
                if(jump != null && open(q, jump, n) && clean(q, jump, r, n)){
                    for(int k = 2; k <= range; k++){
                        int ex = qx + Geometry.d4x(r) * k, ey = qy + Geometry.d4y(r) * k;
                        if(!grid.in(ex, ey)) break;
                        int e = grid.pos(ex, ey);
                        if(!open(e, jump, n) || !bridgeClear(jump, qx, qy, ex, ey, r, range)) continue;
                        int next = (s * N + e) * 4 + r;
                        relax(g, parent, how, open, key, next, gk + step(q, jumpCost) + step(e, jumpCost), BRIDGE + k, targets, tail, N);
                    }
                }
            }
        }
        lastExpanded = expanded;
        lastStarts = starts.size();
        if(done < 0 && endAt != null && Boolean.getBoolean("xp.searchmap")){
            //what the search got to round its goal: * reached, o taken, # not ground, . open, G the goal
            IntSet reached = new IntSet();
            for(var e : g) reached.add((e.key / 4) % N);
            StringBuilder b = new StringBuilder();
            int[][] centres = {endAt, {starts.get(0).x, starts.get(0).y}};
            for(int[] at : centres){
            b.append("router: search for " + cat.get(n.res).name + " failed; round " + at[0] + "," + at[1] + ":\n");
            for(int y = at[1] + 10; y >= at[1] - 10; y--){
                for(int x = at[0] - 16; x <= at[0] + 16; x++){
                    if(!grid.in(x, y)){ b.append(' '); continue; }
                    int q = grid.pos(x, y);
                    boolean goalTile = false;
                    for(int i = 0; i < goal.sample().size; i++) if(goal.sample().get(i) == q) goalTile = true;
                    b.append(goalTile ? 'G' : reached.contains(q) ? '*' : !grid.ground[q] ? '#' : !grid.free(x, y) ? 'o' : '.');
                }
                b.append('\n');
            }
            }
            log.append(b);
        }
        if(done < 0) return null;
        //back from the end: each state's move put down its pieces
        Path path = new Path();
        List<Part> rev = new ArrayList<>();
        List<Integer> docksAt = new ArrayList<>();
        int key = done;
        while(key >= 0){
            int m = how.get(key, 0), d = key % 4, p = (key / 4) % N;
            int x = p % grid.w, y = p / grid.w;
            if(m < 0){
                Start st = starts.get(-1 - m);
                if(st.first != null){
                    rev.add(st.first);
                    if(st.quiet >= 0) path.quiet.add(st.quiet);
                }
                path.replaces = st.replaces;
                break;
            }
            if(m == 0){
                rev.add(new Part(piece, x, y, d, null));
            }else if(isDock(m)){
                rev.add(new Part(dock, x, y, d, null));
                docksAt.add(rev.size() - 1);
                int quiet = (m & ~DOCK) - 1;
                if(quiet >= 0) path.quiet.add(quiet);
            }else{
                int k = m - BRIDGE;
                rev.add(new Part(jump, x, y, d, null));
                rev.add(new Part(jump, x - Geometry.d4x(d) * k, y - Geometry.d4y(d) * k, d, null));
            }
            key = parent.get(key, -1);
        }
        int size = rev.size();
        Collections.reverse(rev);
        for(Part part : rev) path.parts.add(part);
        for(int i : docksAt) path.firstDock = path.firstDock < 0 ? size - 1 - i : Math.min(path.firstDock, size - 1 - i);
        return path;
    }

    /** Moves, as kept for each state: 0 a piece, BRIDGE + k a bridge of span k, DOCK | (quiet tile + 1) a dock; a start is negative. */
    static boolean isDock(int m){
        return m > 0 && (m & DOCK) != 0;
    }

    static final int DOCK = 1 << 30, BRIDGE = 16;

    void relax(IntFloatMap g, IntIntMap parent, IntIntMap how, Heap open, int from, int to, float cost, int move, IntSeq[] targets, float[] tail, int N){
        if(cost >= g.get(to, Float.MAX_VALUE)) return;
        g.put(to, cost);
        parent.put(to, from);
        how.put(to, move);
        open.add(to, cost + estimate(to, (to / 4) / N, targets, tail, N));
    }

    float estimate(int key, int s, IntSeq[] targets, float[] tail, int N){
        int p = (key / 4) % N, x = p % grid.w, y = p / grid.w;
        IntSeq t = targets[Math.min(s, targets.length - 1)];
        int best = t.size == 0 ? 0 : Integer.MAX_VALUE;
        for(int i = 0; i < t.size; i++){
            int v = t.get(i);
            best = Math.min(best, Math.abs(v % grid.w - x) + Math.abs(v / grid.w - y));
        }
        return Math.max(0, best - 1) * minStep() + tail[Math.min(s, tail.length - 1)];
    }

    float minStep(){
        return 1f;
    }

    int between(IntSeq a, IntSeq b){
        if(a.size == 0 || b.size == 0) return 0;
        int best = Integer.MAX_VALUE;
        for(int i = 0; i < a.size; i++) for(int j = 0; j < b.size; j++){
            int u = a.get(i), v = b.get(j);
            best = Math.min(best, Math.abs(u % grid.w - v % grid.w) + Math.abs(u / grid.w - v / grid.w));
        }
        return best;
    }

    /** A binary heap of state keys by estimated cost. */
    static final class Heap{
        int[] keys = new int[1024];
        float[] f = new float[1024];
        int size;
        float lastF;

        void add(int key, float v){
            if(size == keys.length){
                keys = Arrays.copyOf(keys, size * 2);
                f = Arrays.copyOf(f, size * 2);
            }
            int i = size++;
            while(i > 0){
                int up = (i - 1) / 2;
                if(f[up] <= v) break;
                keys[i] = keys[up];
                f[i] = f[up];
                i = up;
            }
            keys[i] = key;
            f[i] = v;
        }

        int poll(){
            int top = keys[0];
            lastF = f[0];
            size--;
            if(size > 0){
                int key = keys[size];
                float v = f[size];
                int i = 0;
                while(true){
                    int l = 2 * i + 1;
                    if(l >= size) break;
                    int c = l + 1 < size && f[l + 1] < f[l] ? l + 1 : l;
                    if(f[c] >= v) break;
                    keys[i] = keys[c];
                    f[i] = f[c];
                    i = c;
                }
                keys[i] = key;
                f[i] = v;
            }
            return top;
        }
    }
}
