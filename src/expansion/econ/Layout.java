package expansion.econ;

import arc.math.geom.*;
import arc.struct.*;
import expansion.Blueprint.Part;
import mindustry.*;
import mindustry.game.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.liquid.*;
import mindustry.world.blocks.production.*;

import java.util.*;

/**
 * Turns a planned order into everything that is to be built on the map, job by job: the layer between the economy
 * planner and the hand that builds.
 *
 * In:  the order (machines, each on the ground the planner chose or to be placed), the map, what may be built.
 * Out: where each machine stands, and for each machine of the order the parts its turn puts up (itself, the lines it
 *      owns, the nodes that power it), with what that really costs; and what the checker found wrong, if anything.
 *
 * The steps, each its own module on a shared {@link Grid}:
 * <ol>
 * <li>machines on the ground go where the planner put them (the places come from {@link Packing}, so none is in
 * another's way); the best fields not used are kept for later, so that lines go round them;</li>
 * <li>{@link Cells} places the other machines;</li>
 * <li>who sends what where becomes nets, and {@link Router} lays them; a try that leaves a net unlaid is done again with
 * the cells placed anew, that net's lines counting for more;</li>
 * <li>{@link Wiring} powers the machines in the order they are built;</li>
 * <li>each line goes to a job: a branch to the maker it comes from, a bus spine or a trunk to the first of its machines to
 * be built, a tap to the machine it feeds.</li>
 * </ol>
 */
public final class Layout{
    public final Catalog cat;
    public final Field field;
    public final Team team;
    final Set<String> allowed;
    public Grid grid;
    /** Where each machine of the order (and each that stands already) is. */
    public final Map<Machine, Part> at = new HashMap<>();
    /** Kept for the hand: lines to cut once building is done (the bus ends in the core and needs none). */
    public final Map<Machine, Part> coreLine = new HashMap<>();
    /**
     * Pieces to take up once a job is done (by its place in the order): the first piece past the last dock of a bus whose
     * docks are routers, once nothing still to build costs what it carries; from then on the machines get all of it.
     */
    public final Map<Integer, Seq<Part>> cuts = new HashMap<>();
    List<Router.Net> nets = new ArrayList<>();
    final Map<Machine, Seq<Part>> lines = new HashMap<>(), wires = new HashMap<>();
    public final StringBuilder log = new StringBuilder();
    public final List<String> problems = new ArrayList<>();
    public List<Machine> order = new ArrayList<>();
    public int failed, tries = 3;
    Wiring wiring;
    /** Why the cells stand where they do, from the try that was kept. */
    List<String> cellNotes = new ArrayList<>();
    String tryNotes = "";

    public Layout(Catalog cat, Field field, Team team, Set<String> allowed){
        this.cat = cat;
        this.field = field;
        this.team = team;
        this.allowed = allowed;
    }

    static Block block(Machine m){
        return Vars.content.block(m.recipe.block);
    }

    // ---------------------------------------------------------------- the whole

    public void prepare(List<Machine> order){
        this.order = new ArrayList<>(order);
        Map<Integer, Float> stress = new HashMap<>();
        Attempt best = null;
        for(int t = 0; t < tries; t++){
            Attempt a = attempt(t, stress);
            log.append(String.format("try %d: %s\n", t + 1, a.note));
            //fewest nets left unlaid, then the fewest items the lines take
            if(best == null || a.failed < best.failed || (a.failed == best.failed && a.linesCost < best.linesCost)) best = a;
            if(a.failed == 0) break;
            for(Router.Net n : a.nets) if(!n.ok) stress.merge(n.res, 2f, (x, y) -> x * y);
        }
        grid = best.grid;
        cellNotes = best.cellNotes;
        tryNotes = best.note;
        at.clear();
        at.putAll(best.at);
        nets = best.nets;
        failed = best.failed;
        for(Router.Net n : nets) if(!n.ok) log.append("not laid: ").append(String.join("; ", n.why)).append('\n');
        wire();
        own();
        check();
    }

    static final class Attempt{
        Grid grid;
        Map<Machine, Part> at = new HashMap<>();
        List<Router.Net> nets = new ArrayList<>();
        int failed;
        float cost, linesCost;
        String note = "";
        List<String> cellNotes = new ArrayList<>();
    }

    Attempt attempt(int seed, Map<Integer, Float> stress){
        Attempt a = new Attempt();
        a.grid = new Grid(cat, team);
        Grid g = a.grid;
        //1. the ground
        Set<Field.Site> used = new HashSet<>();
        for(Machine m : order){
            if(m.recipe.ground == Recipe.Ground.none) continue;
            Part p = new Part(block(m), m.x, m.y, block(m).rotate ? m.rotation : 0, null);
            a.at.put(m, p);
            g.plan(p, -1, -1, dumpOf(m));
            if(p.block instanceof BeamDrill drill) for(int t : g.beam(drill, m.x, m.y, m.rotation)) g.noSolid[t] = true;
            for(Field.Site s : field.sites) if(s.x == m.x && s.y == m.y && s.rotation == m.rotation && s.ground == m.recipe.ground) used.add(s);
        }
        for(Machine m : field.existing) a.at.put(m, new Part(block(m), m.x, m.y, m.rotation, null));
        int keptTiles = Packing.reserve(g, cat, field.sites, used, 3f);
        //2. the cells
        List<Machine> cellMachines = new ArrayList<>();
        for(Machine m : order) if(m.recipe.ground == Recipe.Ground.none) cellMachines.add(m);
        Cells cells = new Cells(g, cat, field, 1000 + seed);
        cells.stress.putAll(stress);
        cells.model(order, field.existing);
        cells.group(cellMachines);
        if(!cellMachines.isEmpty() && !cells.place()){
            a.failed = 100;
            a.note = "no room for the cells";
            return a;
        }
        a.cost = cells.best;
        a.cellNotes = cells.explain();
        for(var e : cells.result().entrySet()){
            Machine m = e.getKey();
            int[] p = e.getValue();
            Part part = new Part(block(m), p[0], p[1], p[2], null);
            a.at.put(m, part);
            g.plan(part, -1, -1, dumpOf(m));
        }
        //where units come out stays clear of anything solid
        for(Cells.Unit u : cells.units){
            for(int i = 0; i < u.ms.size(); i++){
                int[] ex = cells.exitOf(u, i, cells.where.get(u.ms.get(i)));
                if(ex != null) for(int t : ex) if(t >= 0) g.noSolid[t] = true;
            }
        }
        //3. the nets
        Router router = new Router(g, cat, a.at, allowed, log);
        a.nets = nets(router, a.at);
        a.failed = router.routeAll(a.nets);
        int pieces = 0;
        for(Router.Net n : a.nets){
            pieces += n.all().size;
            for(Part p : n.all()) a.linesCost += Router.price(p.block);
        }
        a.note = String.format("%d cells placed (cost %.0f), %d tiles kept for later fields, %d nets with %d pieces, %d not laid", cellMachines.size(), cells.best, keptTiles, a.nets.size(), pieces, a.failed);
        return a;
    }

    /** The one item a machine hands to its neighbours, if it makes one (a drill's ore, a crafter's single item). */
    int dumpOf(Machine m){
        if(m.item >= 0 && cat.get(m.item).kind == Res.Kind.item) return m.item;
        int found = -1;
        for(int k = 0; k < cat.size(); k++){
            if(cat.get(k).kind == Res.Kind.item && m.recipe.out[k] > 0f){
                if(found >= 0) return -1;
                found = k;
            }
        }
        return found;
    }

    // ---------------------------------------------------------------- who sends what where

    List<Router.Net> nets(Router router, Map<Machine, Part> at){
        List<Router.Net> out = new ArrayList<>();
        int id = 0;
        for(int k = 0; k < cat.size(); k++){
            Res r = cat.get(k);
            if(r.kind == Res.Kind.unit) continue;
            List<Machine> makers = new ArrayList<>(), takers = new ArrayList<>();
            for(Machine m : order){
                if(m.out(k) > 1e-4f) makers.add(m);
                if(Cells.takes(m, k)) takers.add(m);
            }
            boolean liquid = r.kind == Res.Kind.liquid;
            if(liquid) for(Machine m : field.existing) if(m.out(k) > 1e-4f) makers.add(m);
            //the map's own machines that the plan feeds: those whose thing nothing of the map's makes (the model counts
            //the plan's makers as feeding them)
            boolean mapMakes = false;
            for(Machine m : field.existing) if(m.out(k) > 1e-4f) mapMakes = true;
            if(!mapMakes) for(Machine m : field.existing) if(Cells.takes(m, k) && at.get(m) != null && !makers.isEmpty()) takers.add(m);
            if(makers.isEmpty() && takers.isEmpty()) continue;
            boolean running = false;
            if(!liquid) for(var e : router.grid.running) if(e.value == k){ running = true; break; }
            if(!liquid && r.storable){
                //what the map's own machines make, the model counts as there for every taker from the start; those lines
                //run to the core, so each taker of the plan's is fed off them too (as well as by the new makers)
                if(running && router.dock != null && mapMakes){
                    for(Machine c : takers){
                        if(c.existing) continue;
                        Router.Net n = new Router.Net(id++, k, false, Router.Kind.tap);
                        n.to.add(c);
                        out.add(n);
                    }
                    if(makers.isEmpty()) continue;
                    log.append(String.format("%s: the takers of the plan are fed off the map's own lines as well\n", r.name));
                }
                if(makers.isEmpty()){
                    if(!takers.isEmpty()) log.append(String.format("%s: nothing makes it for %d takers, and no line of the map's carries it\n", r.name, takers.size()));
                    continue;
                }
                //without underflow ducts a dock is a router, which gives the core as much as the taker (the model counts that:
                //see Catalog.coreShare): only worth it for what building costs, which the core must get; anything else goes
                //whole to its takers
                boolean dockable = router.dock != null && (router.underflow() || buildsWith(k) || takers.isEmpty());
                if(dockable){
                    Router.Net n = new Router.Net(id++, k, false, Router.Kind.bus);
                    n.from.addAll(makers);
                    n.to.addAll(takers);
                    //with underflow docks the spine goes on to the core, which gets what the takers leave; with routers the
                    //core would get as much as the takers, so the spine ends in the last taker, unless what it carries is
                    //something building costs (then the core must get it, or the base waits for ever for what it is short of)
                    n.toCore = router.underflow() || takers.isEmpty() || buildsWith(k);
                    out.add(n);
                    continue;
                }
            }
            //each taker gets whole makers, the nearest first; what is left over goes to the core if it keeps the thing
            Map<Machine, Float> spare = new HashMap<>();
            for(Machine m : makers) spare.put(m, m.out(k));
            for(Machine c : takers){
                float need = c.recipe.in[k] + (c.boosted && c.recipe.boostRes == k ? c.recipe.boostUse : 0f);
                Part cp = at.get(c);
                List<Machine> from = new ArrayList<>(makers);
                //makers that stand by the time the taker does come first (a line from one built later would wait for it,
                //and a pipe is laid with the later of its two ends), the nearest of them first
                int ci = order.indexOf(c);
                from.sort(Comparator.comparingDouble(m -> (order.indexOf(m) > ci ? 1e6 : 0) + (at.get(m) == null || cp == null ? 1e5 : Math.abs(at.get(m).x - cp.x) + Math.abs(at.get(m).y - cp.y))));
                Router.Net n = new Router.Net(id++, k, liquid, Router.Kind.direct);
                n.to.add(c);
                n.byOrder = true;
                for(Machine m : from){
                    if(need <= 1e-4f) break;
                    float give = Math.min(need, spare.get(m));
                    if(give <= 1e-4f) continue;
                    need -= give;
                    spare.put(m, spare.get(m) - give);
                    n.from.add(m);
                }
                if(!n.from.isEmpty()) out.add(n);
                if(need > 1e-3f) log.append(String.format("%s short of %s by %.2f/s with nothing left to lead it from\n", c.recipe.name, r.name, need));
            }
            if(!liquid && r.storable){
                Router.Net n = new Router.Net(id++, k, false, Router.Kind.direct);
                n.toCore = true;
                for(Machine m : makers) if(spare.get(m) > m.out(k) * 0.5f && !m.existing) n.from.add(m);
                if(!n.from.isEmpty()) out.add(n);
            }
        }
        return out;
    }

    /** Whether anything of the plan, or a line piece, costs this item to build. */
    boolean buildsWith(int k){
        for(Machine m : order) if(m.recipe.cost[k] > 0) return true;
        for(String name : allowed){
            Block b = Vars.content.block(name);
            if(b != null) for(ItemStack s : b.requirements) if(cat.id(s.item.name) == k) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- power, and whose job is what

    void wire(){
        for(Seq<Part> w : wires.values()) for(Part p : w) grid.unplan(p);
        wires.clear();
        wiring = new Wiring(grid, log);
        Seq<Part> islands = null;
        for(Machine m : order){
            if(m.recipe.powerUse <= 0f && m.powerOut() <= 0f) continue;
            Part p = at.get(m);
            if(p == null) continue;
            //the map's grids that are not joined to each other are joined with the first machine that needs power
            if(islands == null){
                islands = wiring.joinIslands();
                if(islands.size > 0) log.append("the map's ").append(wiring.islands.size() + 1).append(" grids joined by ").append(islands.size).append(" nodes\n");
            }
            Seq<Part> nodes = wiring.connect(p);
            if(nodes != null && islands.size > 0){
                nodes.addAll(islands);
                islands = new Seq<>();
            }
            if(nodes == null){
                log.append("no way to power ").append(m.recipe.name).append(" at ").append(p.x).append(',').append(p.y).append('\n');
                nodes = new Seq<>();
                wiring.add(grid.pos(p.x, p.y));
            }
            wires.put(m, nodes);
        }
    }

    void own(){
        lines.clear();
        Map<Machine, Integer> index = new HashMap<>();
        for(int i = 0; i < order.size(); i++) index.put(order.get(i), i);
        for(Router.Net n : nets){
            if(!n.ok && n.trunk.isEmpty()) continue;
            Machine owner = null;
            int bestI = Integer.MAX_VALUE;
            List<Machine> members = new ArrayList<>(n.from);
            members.addAll(n.to);
            for(Machine m : members){
                Integer i = index.get(m);
                if(i != null && i < bestI){
                    bestI = i;
                    owner = m;
                }
            }
            if(owner == null) continue;
            //a pipe whose end has nothing to take it spills what it carries on the ground (a conduit leaks out of an open
            //front): a liquid line goes up with the last of the machines at its two ends, never before what it runs into
            if(n.liquid){
                int last = -1;
                List<Machine> ends = new ArrayList<>(n.to);
                if(n.first != null) ends.add(n.first);
                for(Machine m : ends){
                    Integer i = index.get(m);
                    if(i != null && i > last){
                        last = i;
                        owner = m;
                    }
                }
            }
            lines.computeIfAbsent(owner, x -> new Seq<>()).addAll(n.trunk);
            int ownerAt = index.getOrDefault(owner, -1);
            for(var e : n.branch.entrySet()){
                Machine m = index.containsKey(e.getKey()) ? e.getKey() : owner;
                if(n.liquid && index.getOrDefault(m, -1) < ownerAt) m = owner;
                lines.computeIfAbsent(m, x -> new Seq<>()).addAll(e.getValue());
            }
        }
        //where a bus with router docks is cut, and after which job
        cuts.clear();
        for(Router.Net n : nets){
            if(n.kind != Router.Kind.bus || !n.toCore || n.trunk.isEmpty()) continue;
            int lastDock = -1;
            for(int i = 0; i < n.trunk.size; i++) if(n.trunk.get(i).block instanceof DuctRouter) lastDock = i;
            if(lastDock < 0 || lastDock + 1 >= n.trunk.size) continue;
            Part cut = n.trunk.get(lastDock + 1);
            int after = -1;
            for(int i = 0; i < order.size(); i++) if(realCost(order.get(i))[n.res] > 0) after = i;
            cuts.computeIfAbsent(after, x -> new Seq<>()).add(cut);
        }
    }

    /** The plan in a new order (the same machines, matched by kind and place): who powers whom and who owns what are done again. */
    public void reorder(List<Machine> newOrder){
        Map<String, Machine> byKey = new HashMap<>();
        for(Machine m : order) byKey.put(key(m), m);
        Map<Machine, Part> moved = new HashMap<>();
        for(Machine m : newOrder){
            Machine old = byKey.get(key(m));
            if(old == null){
                log.append("reorder: ").append(m).append(" was not laid out\n");
                continue;
            }
            Part p = at.get(old);
            if(p != null) moved.put(m, p);
            //the nets are about machines: the new one stands for the old
            for(Router.Net n : nets){
                n.from.replaceAll(x -> x == old ? m : x);
                n.to.replaceAll(x -> x == old ? m : x);
                if(n.first == old) n.first = m;
                if(n.branch.containsKey(old)){
                    Seq<Part> b = n.branch.remove(old);
                    n.branch.put(m, b);
                }
            }
        }
        for(Machine m : order) at.remove(m);
        at.putAll(moved);
        order = new ArrayList<>(newOrder);
        wire();
        own();
        problems.clear();
        check();
    }

    static String key(Machine m){
        return m.recipe.name + "@" + m.x + "," + m.y + (m.recipe.ground == Recipe.Ground.none ? "#" + System.identityHashCode(m) : "");
    }

    /** What a machine's turn puts up: the machine, the lines it owns, the nodes that power it. */
    public Seq<Part> job(Machine m){
        Seq<Part> parts = new Seq<>();
        Part self = at.get(m);
        if(self == null) return parts;
        parts.add(self);
        parts.addAll(lines.getOrDefault(m, new Seq<>()));
        parts.addAll(wires.getOrDefault(m, new Seq<>()));
        return parts;
    }

    /** What a machine's job really costs, item by item. */
    public int[] realCost(Machine m){
        int[] c = new int[cat.size()];
        for(Part p : job(m)){
            for(ItemStack s : p.block.requirements) c[cat.id(s.item.name)] += Math.round(s.amount * Vars.state.rules.buildCostMultiplier);
        }
        return c;
    }

    /**
     * For a machine that makes units, the share of what flows on to the core it takes while it fills (see
     * {@link Machine#draw}), by how its lines reach it: the most it gets of any one thing.
     */
    public float drawShare(Machine m){
        float share = -1f;
        for(Router.Net n : nets){
            String how = n.feeds.get(m);
            if(how == null) continue;
            float s;
            if(how.startsWith("touching")){
                //a maker hands out by turns to all it touches that take, and to its own lines
                int outlets = 1;
                for(Machine o : n.from){
                    Part p = at.get(o);
                    if(p == null) continue;
                    int around = 0;
                    for(int[] t : Grid.around(p)){
                        Block b = grid.block(t[0], t[1]);
                        if(b != null && (Grid.isLine(b) || grid.uses(b, n.res))) around++;
                    }
                    outlets = Math.max(outlets, around);
                }
                s = 1f / Math.max(1, outlets);
            }else if(how.startsWith("dock") || how.startsWith("spur") || how.startsWith("tap")){
                s = dockIsRouter() ? 0.5f : 1f;
            }else{
                s = 1f;
            }
            share = Math.max(share, s);
        }
        return share < 0f ? 0.5f : share;
    }

    boolean dockIsRouter(){
        return !allowed.contains("underflow-duct");
    }

    /** How long a machine's job takes the core unit to build: every part, and flying along the lines between them. */
    public float buildSeconds(Machine m){
        float ticks = 0f;
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        for(Part p : job(m)){
            ticks += p.block.buildTime;
            x0 = Math.min(x0, p.x); y0 = Math.min(y0, p.y); x1 = Math.max(x1, p.x); y1 = Math.max(y1, p.y);
        }
        float reach = cat.buildRange;
        float span = x0 > x1 ? 0f : Math.max(0f, (x1 - x0) + (y1 - y0) - 2 * reach);
        return ticks / (cat.buildSpeed * cat.buildRule) / 60f + span / cat.flySpeed;
    }

    // ---------------------------------------------------------------- the checker

    /** Goes over the plan as if it were all built, by the game's rules, and says what would go wrong. */
    void check(){
        problems.clear();
        for(Router.Net n : nets){
            for(Part p : n.all()){
                //anything pushing a wrong thing into it (a bridge end, with no link of its own, takes nothing)
                boolean end = p.block instanceof DirectionBridge && grid.bridgeLink(p.x, p.y) == null;
                for(int j = 0; j < 4 && !end; j++){
                    int mx = p.x + Geometry.d4x(j), my = p.y + Geometry.d4y(j);
                    if(!grid.in(mx, my) || !Router.accepts(p.block, p.rotation, j, grid.block(mx, my))) continue;
                    int v = grid.pushes(mx, my, p.x, p.y, n.liquid);
                    if(v != -1 && v != n.res) problems.add(String.format("%s at %d,%d (%s) would get %s from %s at %d,%d", p.block.name, p.x, p.y, cat.get(n.res).name, v >= 0 ? cat.get(v).name : "something", grid.block(mx, my).name, mx, my));
                }
                //where it sends what it carries
                Block b = p.block;
                if(b instanceof DirectionBridge){
                    int[] link = grid.bridgeLink(p.x, p.y);
                    if(link != null){
                        if(grid.net[grid.pos(link[0], link[1])] != n.id) problems.add(String.format("bridge at %d,%d would link to another line's at %d,%d", p.x, p.y, link[0], link[1]));
                        continue;
                    }
                }
                if(b instanceof OverflowDuct || b instanceof DuctRouter){
                    //a dock: the side across from the machine must take nothing
                    for(int side : new int[]{(p.rotation + 1) % 4, (p.rotation + 3) % 4}){
                        int sx = p.x + Geometry.d4x(side), sy = p.y + Geometry.d4y(side);
                        Block o = grid.block(sx, sy);
                        if(o != null && Grid.isLine(o) && grid.net[grid.pos(sx, sy)] != n.id && Router.accepts(o, grid.rotation(sx, sy), (side + 2) % 4, b)) problems.add(String.format("dock at %d,%d hands %s into another line at %d,%d", p.x, p.y, cat.get(n.res).name, sx, sy));
                    }
                }
                int fx = p.x + Geometry.d4x(p.rotation), fy = p.y + Geometry.d4y(p.rotation);
                if(!leadsOn(n, fx, fy, p)) problems.add(String.format("%s at %d,%d (%s, net %d) leads to nothing that takes it at %d,%d (%s, facing %d, net %d)", b.name, p.x, p.y, cat.get(n.res).name, n.id, fx, fy,
                    grid.block(fx, fy) == null ? "open ground" : grid.block(fx, fy).name, grid.in(fx, fy) ? grid.rotation(fx, fy) : -1, grid.in(fx, fy) ? grid.net[grid.pos(fx, fy)] : -1));
            }
        }
        //solid things where beams cross or units come out
        for(Part p : at.values()) solidCheck(p);
        for(Seq<Part> w : wires.values()) for(Part p : w) solidCheck(p);
        //power
        List<Part> powered = new ArrayList<>();
        for(Machine m : order) if((m.recipe.powerUse > 0f || m.powerOut() > 0f) && at.get(m) != null) powered.add(at.get(m));
        IntSet cut = wiring.unreached(powered);
        for(var it = cut.iterator(); it.hasNext;){
            int o = it.next();
            problems.add(String.format("%s at %d,%d gets no power once all is built", grid.block(o % grid.w, o / grid.w).name, o % grid.w, o / grid.w));
        }
    }

    void solidCheck(Part p){
        if(!p.block.solid) return;
        boolean own = false;
        for(Machine m : order) if(at.get(m) == p && m.recipe.ground != Recipe.Ground.none) own = true;
        if(own) return;
        for(int t : grid.tiles(p)) if(grid.noSolid[t]){
            problems.add(String.format("%s at %d,%d stands where a beam crosses or units come out", p.block.name, p.x, p.y));
            return;
        }
    }

    /** Whether what a piece sends forward is taken there: by the next piece of its net, a machine that uses it, or the core. */
    boolean leadsOn(Router.Net n, int fx, int fy, Part from){
        if(!grid.in(fx, fy)) return false;
        Block b = grid.block(fx, fy);
        if(b == null) return false;
        int q = grid.pos(fx, fy);
        if(Grid.isLine(b)){
            //its own net, a running line of the map's carrying the same, or another net's piece carrying the same (a tap's dock on such a line)
            boolean ours = grid.net[q] == n.id || (grid.planned(fx, fy) == null && grid.running.get(q, -1) == n.res) || (grid.planned(fx, fy) != null && grid.carry[q] == n.res);
            return ours && Router.accepts(b, grid.rotation(fx, fy), (from.rotation + 2) % 4, from.block);
        }
        return grid.uses(b, n.res);
    }

    // ---------------------------------------------------------------- what it means to do, to be shown

    /** Everything the layout decided, as marks and notes by layer (see {@link Intent}). */
    public Intent intent(){
        Intent in = new Intent();
        Map<Machine, Integer> index = new HashMap<>();
        for(int i = 0; i < order.size(); i++) index.put(order.get(i), i);
        //L2a: fields, ranked, and what is kept of them; the places used, and the beams that must stay clear
        List<Packing.Group> groups = Packing.fields(field.sites, cat, grid.w);
        Map<String, Integer> rank = new HashMap<>();
        in.note(Intent.Layer.fields, String.format("%d places looked at, %d left once packed so none is in another's way", field.candidates, field.sites.size()));
        for(Packing.Group g : groups){
            String what = g.item >= 0 ? cat.get(g.item).name : "vent";
            int r = rank.merge(g.ground + what, 1, Integer::sum);
            int sx = 0, sy = 0, keptTiles = 0, used = 0;
            for(Field.Site s : g.sites){
                sx += s.x;
                sy += s.y;
                for(int t : s.tiles) if(grid.kept[t] > 0) keptTiles++;
                for(Machine m : order) if(m.x == s.x && m.y == s.y && m.recipe.ground == s.ground) used++;
            }
            if(keptTiles == 0 && used == 0) continue;
            int cx = sx / g.sites.size(), cy = sy / g.sites.size();
            String label = String.format("%s field #%d: %.2f/s, %d of %d places used%s", what, r, g.rate, used, g.sites.size(), keptTiles > 0 ? ", the rest kept" : "");
            in.text(Intent.Layer.fields, cx, cy + 3, Intent.kept | 0xff, label);
            in.note(Intent.Layer.fields, String.format("%s at %d,%d, %.0f tiles out", label, cx, cy, g.distance));
        }
        for(int t = 0; t < grid.kept.length; t++) if(grid.kept[t] > 0) in.rect(Intent.Layer.fields, t % grid.w, t / grid.w, t % grid.w, t / grid.w, Intent.kept, null);
        for(Machine m : order){
            if(m.recipe.ground == Recipe.Ground.none) continue;
            Part p = at.get(m);
            if(p == null) continue;
            in.block(Intent.Layer.fields, p.x, p.y, p.block.size, Intent.green, m.item >= 0 ? String.format("%s %.2f/s", cat.get(m.item).name, m.out(m.item)) : null);
            if(p.block instanceof BeamDrill drill){
                int len = 0;
                for(int i = 1; i <= drill.range; i++){
                    Tile t = Vars.world.tile(p.x + Geometry.d4x(p.rotation) * (i + p.block.size / 2), p.y + Geometry.d4y(p.rotation) * (i + p.block.size / 2));
                    if(t == null || t.solid()) break;
                    len = i;
                }
                if(len > 0) in.line(Intent.Layer.fields, p.x, p.y, p.x + Geometry.d4x(p.rotation) * (len + p.block.size / 2), p.y + Geometry.d4y(p.rotation) * (len + p.block.size / 2), Intent.yellow, null);
            }
        }
        //L2b: the cells and the reasons
        for(Machine m : order){
            if(m.recipe.ground != Recipe.Ground.none) continue;
            Part p = at.get(m);
            if(p != null) in.block(Intent.Layer.cells, p.x, p.y, p.block.size, Intent.violet, m.recipe.name);
        }
        for(String n : cellNotes) in.note(Intent.Layer.cells, n);
        in.note(Intent.Layer.cells, "kept: " + tryNotes);
        //L2c: every net, with what each piece carries, its docks and spurs and quiet tiles
        for(Router.Net n : nets){
            Res r = cat.get(n.res);
            int color = color(r);
            for(Part p : n.all()){
                int fx = p.x + Geometry.d4x(p.rotation), fy = p.y + Geometry.d4y(p.rotation);
                if(p.block instanceof DirectionBridge){
                    int[] link = grid.bridgeLink(p.x, p.y);
                    if(link != null){
                        in.arrow(Intent.Layer.lines, p.x, p.y, link[0], link[1], color, null);
                        continue;
                    }
                }
                in.arrow(Intent.Layer.lines, p.x, p.y, fx, fy, color, null);
                if(p.block instanceof OverflowDuct || p.block instanceof DuctRouter){
                    for(Machine c : n.to){
                        Part cp = at.get(c);
                        if(cp != null && Cells.gap(rectOf(p), rectOf(cp)) == 0) in.arrow(Intent.Layer.lines, p.x, p.y, cp.x, cp.y, Intent.white, "dock: " + r.name + " to " + c.recipe.name + " first");
                    }
                    if(n.kind == Router.Kind.tap) in.text(Intent.Layer.lines, p.x, p.y, Intent.white, "tap: " + r.name + " off the map's line");
                }
            }
            for(int i = 0; i < n.quiet.size; i++) in.cross(Intent.Layer.lines, n.quiet.get(i) % grid.w, n.quiet.get(i) / grid.w, Intent.grey, null);
            if(n.first != null && at.get(n.first) != null){
                Part f = at.get(n.first);
                in.text(Intent.Layer.lines, f.x, f.y - 2, color, String.format("%s %s from here", n.kind, r.name));
            }
            StringBuilder b = new StringBuilder(String.format("%s of %s: %d makers -> ", n.kind, r.name, n.from.size()));
            for(var e : n.feeds.entrySet()) b.append(e.getKey().recipe.name).append(" (").append(e.getValue()).append("), ");
            b.append(n.toCore ? "the rest to the core" : "no further");
            b.append(String.format("; %d pieces, %d quiet tiles%s", n.all().size, n.quiet.size, n.ok ? "" : "; NOT LAID: " + String.join("; ", n.why)));
            in.note(Intent.Layer.lines, b.toString());
        }
        //L2d: the nodes and where each beams to
        int nodes = 0;
        for(var e : wires.entrySet()){
            for(Part p : e.getValue()){
                nodes++;
                in.block(Intent.Layer.power, p.x, p.y, 1, Intent.cyan, null);
                for(int d = 0; d < 4; d++){
                    for(int j = 1; j <= wiring.range; j++){
                        int tx = p.x + Geometry.d4x(d) * j, ty = p.y + Geometry.d4y(d) * j;
                        if(!grid.in(tx, ty) || wiring.insulated(tx, ty)) break;
                        if(wiring.powered(tx, ty) >= 0){
                            in.line(Intent.Layer.power, p.x, p.y, tx, ty, Intent.cyan & 0xffffff80, null);
                            break;
                        }
                    }
                }
            }
            if(!e.getValue().isEmpty()) in.note(Intent.Layer.power, String.format("#%d %s: %d nodes to the grid", index.getOrDefault(e.getKey(), -1) + 1, e.getKey().recipe.name, e.getValue().size));
        }
        in.note(Intent.Layer.power, nodes + " nodes in all; machines joined in the order they are built, each by the fewest nodes to the grid as it then is");
        //L1/L3: the jobs
        for(int i = 0; i < order.size(); i++){
            Machine m = order.get(i);
            Part p = at.get(m);
            if(p == null) continue;
            int cost = 0;
            for(int c : realCost(m)) cost += c;
            in.text(Intent.Layer.jobs, p.x, p.y, Intent.white, "#" + (i + 1));
            in.note(Intent.Layer.jobs, String.format("#%d %s%s at %d,%d: %d parts, %d items, %.0f s to build", i + 1, m.recipe.name, m.item >= 0 ? " (" + cat.get(m.item).name + ")" : "", p.x, p.y, job(m).size, cost, buildSeconds(m)));
        }
        for(String pr : problems) in.note(Intent.Layer.jobs, "checker: " + pr);
        return in;
    }

    int[] rectOf(Part p){
        int s = p.block.size, off = -(s - 1) / 2;
        return new int[]{p.x + off, p.y + off, p.x + off + s - 1, p.y + off + s - 1};
    }

    static int color(Res r){
        if(r.kind == Res.Kind.item){
            Item item = Vars.content.item(r.name);
            if(item != null) return Intent.rgba(item.color, 1f);
        }else if(r.kind == Res.Kind.liquid){
            Liquid liquid = Vars.content.liquid(r.name);
            if(liquid != null) return Intent.rgba(liquid.color, 1f);
        }
        return Intent.white;
    }

    public String summary(){
        int pieces = 0, nodes = 0;
        for(Seq<Part> l : lines.values()) pieces += l.size;
        for(Seq<Part> w : wires.values()) nodes += w.size;
        return String.format("%d machines placed, %d nets (%d not laid), %d line pieces, %d nodes, %d problems found by the checker", at.size(), nets.size(), failed, pieces, nodes, problems.size());
    }
}
