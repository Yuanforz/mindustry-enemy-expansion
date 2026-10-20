package expansion.econ;

import arc.struct.*;
import expansion.Blueprint.Part;
import mindustry.*;
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.world.blocks.ConstructBlock.*;
import mindustry.world.meta.*;

import java.util.*;

/**
 * The plant once built: every few seconds, each finished job's parts are looked at and what is missing is handed back
 * to be built again; and each machine's state is counted (running, starved of something, blocked with nowhere to put what
 * it made, short of power), so that what the layout or the plan got wrong shows up as a number, machine by machine.
 */
public final class Watch{
    final Layout layout;
    final List<Machine> order;
    /** Parts to put back, for the hand to take up between jobs. */
    public Seq<Part> repairs = new Seq<>();
    /** Parts taken up on purpose (a line to the core cut), not to be put back. */
    public final Set<Part> taken = new HashSet<>();
    /** Seconds each machine of the order spent in each state, once built. */
    public final Map<Machine, float[]> seconds = new LinkedHashMap<>();
    public static final String[] states = {"running", "starved", "blocked", "no power", "missing"};
    public int repaired;
    /** What was put back, and when. */
    public final List<String> what = new ArrayList<>();
    /** When each machine's state changed, as "time state" entries. */
    public final Map<Machine, StringBuilder> changes = new LinkedHashMap<>();
    final Map<Machine, Integer> last = new HashMap<>();
    /** For each machine, its main output a second as the game has it, summed over the samples since it was first seen running. */
    public final Map<Machine, float[]> rates = new LinkedHashMap<>();
    float at;
    static final float every = 2f;

    public Watch(Layout layout, List<Machine> order){
        this.layout = layout;
        this.order = order;
    }

    /** When each part (by place and block) was last seen gone, and how often lately: for not putting it back into the fire. */
    public Map<Long, float[]> losses = new HashMap<>();
    /** How often a part may be lost in {@link #lossWindow} seconds before it is left alone for {@link #coolOff} seconds. */
    public static int lossLimit = 3;
    public static float lossWindow = 120f, coolOff = 60f;
    /** For the report: how often a part to put back was held back, under fire or lost too often. */
    public int heldBack;

    static long key(Part p){
        return ((long)p.x << 32) ^ ((long)p.y << 16) ^ p.block.id;
    }

    void lost(Part p, float now){
        float[] l = losses.computeIfAbsent(key(p), k -> new float[3]);
        //[0]: losses in the window, [1]: when the window began, [2]: the last loss
        if(now - l[1] > lossWindow){
            l[0] = 0f;
            l[1] = now;
        }
        l[0]++;
        l[2] = now;
    }

    /**
     * Whether a part is better not put back now: one of the other side's fighting units is within its reach of it (and a
     * few tiles), or it has been lost again and again lately. Raiders stay a while; a site begun under them is shot down
     * at once, and begun again, and shot down: on lake, raided by an elude or two, one turbine's place was begun 485 times
     * while the core unit did nothing else.
     */
    public boolean hot(Part p){
        float now = (float)(Vars.state.tick / 60.0);
        float[] l = losses.get(key(p));
        if(l != null && l[0] >= lossLimit && now - l[2] < coolOff) return true;
        return enemyNear(layout.team, p.x * Vars.tilesize, p.y * Vars.tilesize, 6f * Vars.tilesize);
    }

    /** Whether one of the other sides' fighting units has this place within its reach and the margin. */
    public static boolean enemyNear(mindustry.game.Team team, float x, float y, float margin){
        boolean[] near = {false};
        mindustry.entities.Units.nearbyEnemies(team, x, y, 40f * Vars.tilesize, u -> {
            if(near[0] || u.spawnedByCore || !u.type.canAttack || u.dead()) return;
            if(u.within(x, y, u.range() + margin)) near[0] = true;
        });
        return near[0];
    }

    /**
     * Steps of the order not looked at though they come before {@code done}: put off and not built yet, or being built
     * again (set by the hand). Else a step put off for want of silicon counted as built and lost, and the hand waited to
     * "put it back", for the same silicon, for the rest of the game.
     */
    public java.util.function.IntPredicate notBuilt = i -> false;

    /** Looks at the jobs done so far (the first {@code done} of the order). */
    int lastDone;

    public void update(int done){
        lastDone = done;
        float now = (float)(Vars.state.tick / 60.0);
        if(now < at) return;
        at = now + every;
        for(int i = 0; i < Math.min(done, order.size()); i++){
            if(notBuilt.test(i)) continue;
            Machine m = order.get(i);
            for(Part p : layout.job(m)){
                if(taken.contains(p)) continue;
                Tile t = Vars.world.tile(p.x, p.y);
                if(t == null) continue;
                boolean there = t.block() == p.block || t.build instanceof ConstructBuild;
                if(!there && !repairs.contains(p, true) && Build.validPlace(p.block, layout.team, p.x, p.y, p.rotation)){
                    repairs.add(p);
                    lost(p, now);
                    repaired++;
                    if(what.size() < 20) what.add(String.format("%s at %d,%d (of #%d %s; there: %s) at %s", p.block.name, p.x, p.y, i + 1, m.recipe.name, t.block().name, clock(now)));
                }
            }
            Part self = layout.at.get(m);
            if(self == null) continue;
            float[] s = seconds.computeIfAbsent(m, x -> new float[states.length]);
            Building b = Vars.world.build(self.x, self.y);
            int st = state(b, self);
            s[st] += every;
            float r = rate(b);
            if(!Float.isNaN(r)){
                float[] acc = rates.computeIfAbsent(m, x -> new float[4]);
                //from the first time it runs: before that it was waiting to be fed, which the rate says nothing about
                if(r > 0 || acc[1] > 0){
                    acc[0] += r;
                    acc[1]++;
                    //and the last minute on its own, for the steady state
                    acc[2] = acc[2] * (1f - every / 60f) + r * every / 60f;
                    acc[3] = Math.max(acc[3], r);
                }
            }
            if(last.getOrDefault(m, -1) != st){
                last.put(m, st);
                changes.computeIfAbsent(m, x -> new StringBuilder()).append(String.format(" %d:%02d %s", (int)now / 60, (int)now % 60, states[st]));
            }
        }
    }

    /** The main thing a machine makes, a second, right now, by the game's own counters; NaN for what has none. */
    static float rate(Building b){
        if(b == null) return Float.NaN;
        if(b instanceof mindustry.world.blocks.production.Drill.DrillBuild d) return d.lastDrillSpeed * 60f;
        if(b instanceof mindustry.world.blocks.production.BeamDrill.BeamDrillBuild d) return d.lastDrillSpeed * 60f;
        if(b instanceof mindustry.world.blocks.production.WallCrafter.WallCrafterBuild w) return w.lastEfficiency * 60f / ((mindustry.world.blocks.production.WallCrafter)b.block).drillTime;
        if(b.block instanceof mindustry.world.blocks.production.GenericCrafter gc && b instanceof mindustry.world.blocks.production.GenericCrafter.GenericCrafterBuild g){
            float per = 60f / gc.craftTime * (g.shouldConsume() ? b.efficiency : 0f);
            if(gc.outputItems != null && gc.outputItems.length > 0) return per * gc.outputItems[0].amount;
            if(gc.outputLiquids != null && gc.outputLiquids.length > 0) return (g.shouldConsume() ? b.efficiency : 0f) * gc.outputLiquids[0].amount * 60f;
        }
        if(b.block instanceof mindustry.world.blocks.power.ThermalGenerator && b instanceof mindustry.world.blocks.power.PowerGenerator.GeneratorBuild gb) return gb.productionEfficiency * ((mindustry.world.blocks.power.PowerGenerator)b.block).powerProduction * 60f;
        return Float.NaN;
    }

    static String clock(float seconds){
        return String.format("%d:%02d", (int)seconds / 60, (int)seconds % 60);
    }

    static int state(Building b, Part p){
        if(b == null || b.block != p.block) return 4;
        //short of power when its grid is (a machine that waits for an item asks for no power, and is starved, not unpowered)
        if(b.power != null && b.block.consPower != null && b.power.graph.getSatisfaction() < 0.99f && b.shouldConsume()) return 3;
        BlockStatus st = b.status();
        if(st == BlockStatus.noOutput) return 2;
        if(st == BlockStatus.noInput || b.efficiency <= 0.01f) return 1;
        return 0;
    }

    /** How each machine ran, as shares of the time since it was built. */
    /** Where a line of ducts and bridges that starts at this one goes: the block it ends in, or the tile it stops at. */
    /** What the order has planned on a tile: which step's part, and whether that step is begun. */
    String planned(int x, int y){
        for(int i = 0; i < order.size(); i++){
            for(Part p : layout.job(order.get(i))){
                int s = p.block.size, lo = -(s - 1) / 2;
                if(x >= p.x + lo && x < p.x + lo + s && y >= p.y + lo && y < p.y + lo + s){
                    Machine m = order.get(i);
                    String kind = layout.bodies.containsKey(m) && layout.bodies.get(m).contains(p, true) ? "its body" : layout.lines.containsKey(m) && layout.lines.get(m).contains(p, true) ? "its lines" : "its wires";
                    return String.format(" (planned there: %s r%d, in %s of #%d %s, %s%s)", p.block.name, p.rotation, kind, i + 1, m.recipe.name, i < lastDone ? "begun" : "not begun", notBuilt.test(i) ? ", put off" : "");
                }
            }
        }
        return " (nothing planned there)";
    }

    String trace(Building from){
        Building at = from;
        IntSet seen = new IntSet();
        int steps = 0;
        while(steps++ < 400 && seen.add(at.pos())){
            Building next;
            if(at instanceof mindustry.world.blocks.distribution.DirectionBridge.DirectionBridgeBuild bridge){
                next = bridge.findLink();
                if(next == null) next = at.front();
            }else if(at.block instanceof mindustry.world.blocks.distribution.Duct || at.block instanceof mindustry.world.blocks.distribution.DuctRouter || at.block.rotate && at.block.group == BlockGroup.transportation){
                next = at.front();
            }else{
                StringBuilder held = new StringBuilder();
                if(at.items != null) at.items.each((item, n) -> held.append(item.name).append('=').append(n).append(' '));
                return String.format("%d steps, into %s at %d,%d holding [%s]", steps - 1, at.block.name, at.tileX(), at.tileY(), held.toString().trim());
            }
            if(next == null){
                Tile ahead = at.tile.nearby(at.rotation);
                return String.format("%d steps, stops at %s %d,%d r%d, before %s", steps, at.block.name, at.tileX(), at.tileY(), at.rotation, ahead == null ? "the map edge" : ahead.block().name + " at " + ahead.x + "," + ahead.y + planned(ahead.x, ahead.y));
            }
            at = next;
        }
        return steps >= 400 ? "more than 400 steps" : "a loop at " + at.tileX() + "," + at.tileY();
    }

    public String report(Catalog cat){
        StringBuilder out = new StringBuilder();
        for(var e : seconds.entrySet()){
            float[] s = e.getValue();
            float total = 0;
            for(float v : s) total += v;
            if(total <= 0) continue;
            out.append(String.format("    %-30s %-9s", e.getKey().recipe.name, e.getKey().item >= 0 ? cat.get(e.getKey().item).name : ""));
            for(int k = 0; k < states.length; k++) if(s[k] > 0) out.append(String.format(" %s %.0f%%", states[k], 100f * s[k] / total));
            out.append(String.format("  (%.0f s)\n", total));
            StringBuilder c = changes.get(e.getKey());
            if(c != null) out.append("        ").append(c.length() > 300 ? c.substring(0, 300) + " ..." : c).append('\n');
            //one that is starved or blocked now: where it is, what it holds, and what it touches (what should feed it or
            //take from it)
            Part self = layout.at.get(e.getKey());
            Building b = self == null ? null : Vars.world.build(self.x, self.y);
            if(b != null && c != null && c.toString().trim().endsWith("no power")){
                //what power it is on: how many on its grid, what makes power there, the nearest node of ours and whether it reaches
                StringBuilder pw = new StringBuilder();
                if(b.power != null){
                    var g = b.power.graph;
                    pw.append(String.format("grid of %d, %d producers, satisfaction %.2f", g.all.size, g.producers.size, g.getSatisfaction()));
                }
                Building node = null;
                float best = Float.MAX_VALUE;
                for(Building o : layout.team.data().buildings) if(o.block instanceof mindustry.world.blocks.power.BeamNode && o.dst(b) < best){
                    best = o.dst(b);
                    node = o;
                }
                if(node != null) pw.append(String.format("; nearest node at %d,%d, %.0f tiles, on a grid of %d", node.tileX(), node.tileY(), best / Vars.tilesize, node.power.graph.all.size));
                out.append(String.format("        at %d,%d: %s\n", b.tileX(), b.tileY(), pw));
            }
            if(b != null && c != null && (c.toString().trim().endsWith("starved") || c.toString().trim().endsWith("blocked"))){
                StringBuilder by = new StringBuilder();
                for(Building o : b.proximity) by.append(' ').append(o.block.name).append('@').append(o.tileX()).append(',').append(o.tileY()).append(o.block.rotate ? " r" + o.rotation : "");
                StringBuilder held = new StringBuilder();
                if(b.items != null) b.items.each((item, n) -> held.append(item.name).append('=').append(n).append(' '));
                if(b.liquids != null) b.liquids.each((l, n) -> { if(n > 0.1f) held.append(l.name).append('=').append((int)n).append(' '); });
                out.append(String.format("        at %d,%d holds [%s], touches:%s\n", b.tileX(), b.tileY(), held.toString().trim(), by.length() == 0 ? " nothing" : by));
                if(c.toString().trim().endsWith("blocked")) for(Building o : b.proximity) if(o.block.rotate && o.front() != b) out.append("        its line from ").append(o.tileX()).append(',').append(o.tileY()).append(": ").append(trace(o)).append('\n');
            }
        }
        return out.toString();
    }
}
