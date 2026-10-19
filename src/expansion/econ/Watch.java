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
    /** Seconds each machine of the order spent in each state, once built. */
    public final Map<Machine, float[]> seconds = new LinkedHashMap<>();
    public static final String[] states = {"running", "starved", "blocked", "no power", "missing"};
    public int repaired;
    /** When each machine's state changed, as "time state" entries. */
    public final Map<Machine, StringBuilder> changes = new LinkedHashMap<>();
    final Map<Machine, Integer> last = new HashMap<>();
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
                Tile t = Vars.world.tile(p.x, p.y);
                if(t == null) continue;
                boolean there = t.block() == p.block || t.build instanceof ConstructBuild;
                if(!there && !repairs.contains(p, true) && Build.validPlace(p.block, layout.team, p.x, p.y, p.rotation)){
                    repairs.add(p);
                    repaired++;
                }
            }
            Part self = layout.at.get(m);
            if(self == null) continue;
            float[] s = seconds.computeIfAbsent(m, x -> new float[states.length]);
            Building b = Vars.world.build(self.x, self.y);
            int st = state(b, self);
            s[st] += every;
            if(last.getOrDefault(m, -1) != st){
                last.put(m, st);
                changes.computeIfAbsent(m, x -> new StringBuilder()).append(String.format(" %d:%02d %s", (int)now / 60, (int)now % 60, states[st]));
            }
        }
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
