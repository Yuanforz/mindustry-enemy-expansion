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

    /** Looks at the jobs done so far (the first {@code done} of the order). */
    public void update(int done){
        float now = (float)(Vars.state.tick / 60.0);
        if(now < at) return;
        at = now + every;
        for(int i = 0; i < Math.min(done, order.size()); i++){
            Machine m = order.get(i);
            for(Part p : layout.job(m)){
                if(taken.contains(p)) continue;
                Tile t = Vars.world.tile(p.x, p.y);
                if(t == null) continue;
                boolean there = t.block() == p.block || t.build instanceof ConstructBuild;
                if(!there && !repairs.contains(p, true) && Build.validPlace(p.block, layout.team, p.x, p.y, p.rotation)){
                    repairs.add(p);
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
        }
        return out.toString();
    }
}
