package xtest;

import arc.files.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.ai.types.*;
import mindustry.content.*;
import mindustry.core.GameState.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.*;
import mindustry.world.blocks.storage.*;

import java.io.*;
import java.util.*;

/**
 * Line three grown from nothing: a map, its core with what it lands with, the core unit, and the final form's plan. When
 * what it was given to build is up, the base is planned a step further (bp.Grow, in a process of its own, the game
 * waiting): what to add next, laid out among what stands; the core unit builds it, at its own speed and within its reach
 * as a player's does, and digs what it can dig when the core is short. Nobody steps in.
 *
 * Usage: Climb data-dir map.msav plan-file work-dir minutes [spans span-seconds think-seconds]
 */
public class Climb{
    static Team us;

    /** One thing to build: its id in the ledger, its blocks (name, x, y, turn, config or null). */
    static final class Job{
        int id;
        String kind, unit;
        /** To be taken down, not built. */
        boolean remove;
        final List<Object[]> blocks = new ArrayList<>();
    }

    public static void main(String[] args){
        Boot.boot(args[0]);
        Fi map = new Fi(args[1]);
        String plan = args[2];
        Fi work = new Fi(args[3]);
        work.mkdirs();
        float minutes = Float.parseFloat(args[4]);
        String spans = args.length > 5 ? args[5] : "12", spanSeconds = args.length > 6 ? args[6] : "90", think = args.length > 7 ? args[7] : "60";
        Fi ledger = work.child("ledger.txt"), state = work.child("state.txt"), orders = work.child("orders.txt");
        ledger.delete();

        Vars.logic.reset();
        SaveIO.load(map);
        Rules rules = Vars.state.rules;
        Planets.erekir.applyRules(rules, false);
        rules.waves = false;
        rules.attackMode = false;
        rules.fog = false;
        rules.coreIncinerates = true;
        rules.infiniteResources = false;
        us = rules.defaultTeam;
        Vars.state.set(State.playing);
        Vars.state.tick = 0;
        Time.setDeltaProvider(() -> 1f);
        Boot.frames(1);
        CoreBlock.CoreBuild core = us.core();
        if(core == null){
            System.out.println("CLIMB: no core of ours on the map");
            System.exit(1);
        }
        System.out.println(String.format("CLIMB %s: core %s at %d,%d with %s", map.nameWithoutExtension(), core.block.name, core.tileX(), core.tileY(), core.items));

        Builder hand = new Builder();
        Unit unit = null;
        List<Job> jobs = new ArrayList<>();
        int frames = (int)(minutes * 3600);
        float nextTell = 0f, thought = 0f, nextPlan = 0f, span = Float.parseFloat(spanSeconds);
        int steps = 0;
        for(int f = 0; f < frames; f++){
            float now = f / 60f;
            core = us.core();
            if(core == null) break;
            if(unit == null || unit.dead() || !unit.isAdded()){
                unit = ((CoreBlock)core.block).unitType.spawn(us, core.x, core.y);
                unit.controller(hand);
            }
            //what it was given is up, or a span has gone by: planned a step further (what was given and not begun is
            //given back and planned again with the rest)
            if(hand.idle() || now >= nextPlan){
                nextPlan = now + span;
                writeState(state, jobs, core, unit, hand, now);
                jobs.removeIf(j -> !j.remove && !standing(j) && !begun(j));
                hand.keepOnly(jobs);
                long t0 = System.nanoTime();
                List<String> out = grow(args[0], map.path(), plan, ledger.path(), state.path(), orders.path(), spans, spanSeconds, think);
                float secs = (System.nanoTime() - t0) / 1e9f;
                thought += secs;
                steps++;
                List<Job> next = readOrders(orders);
                jobs.addAll(next);
                hand.give(next, digPlan(orders));
                List<String> names = new ArrayList<>();
                for(Job j : next) names.add(j.unit == null ? j.kind : j.unit);
                String summary = out.stream().filter(l -> l.startsWith("  built by then")).reduce("", (a, c) -> a + " " + c.trim());
                System.out.println(String.format("  %s: step %d planned in %.0f s:%s; to build: %s", time(now), steps, secs, summary, names));
                for(String l : out) if(l.contains("NOT laid out") || l.contains("Exception") || l.contains("things wrong") || l.startsWith("schedule")) System.out.println("    " + l.trim());
            }
            Boot.frames(1);
            if(now >= nextTell){
                nextTell = now + 60f;
                int standing = 0;
                for(Job j : jobs) if(standing(j)) standing++;
                System.out.println(String.format("  %s: %d of %d things given stand; core %s; %s", time(now), standing, jobs.size(), core.items, hand.doing));
            }
        }
        int standing = 0;
        for(Job j : jobs) if(standing(j)) standing++;
        System.out.println(String.format("CLIMB RESULT %s: %d of %d things given stand after %s; %d steps planned in %.0f s", map.nameWithoutExtension(), standing, jobs.size(), time(minutes * 60f), steps, thought));
        System.exit(0);
    }

    /** One step of the planner, in a process of its own (the game waits meanwhile); its output lines. */
    static List<String> grow(String dataDir, String map, String plan, String ledger, String state, String orders, String spans, String spanSeconds, String think){
        List<String> lines = new ArrayList<>();
        try{
            ProcessBuilder pb = new ProcessBuilder("sh", "bp/run.sh", "-Dbp.wholeSpans=1", "bp.Grow", dataDir, map, plan, ledger, state, orders, spans, spanSeconds, think);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try(BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))){
                String l;
                while((l = r.readLine()) != null) if(!l.startsWith("[")) lines.add(l);
            }
            p.waitFor();
        }catch(Exception e){
            lines.add("Exception: " + e);
        }
        return lines;
    }

    static void writeState(Fi file, List<Job> jobs, CoreBlock.CoreBuild core, Unit unit, Builder hand, float now){
        StringBuilder s = new StringBuilder();
        for(Item item : Vars.content.items()) if(core.items.get(item) > 0) s.append("stock ").append(item.name).append(' ').append(core.items.get(item)).append('\n');
        for(Item item : Vars.content.items()) if(unit.canMine(item) && hand.wall(item, core) != null) s.append(String.format(Locale.ROOT, "dig %s %.3f%n", item.name, hand.digRate(item)));
        for(Job j : jobs) if(!j.remove && standing(j)) s.append("built ").append(j.id).append('\n');
        for(Job j : jobs) if(!j.remove && !standing(j) && begun(j)) s.append("begun ").append(j.id).append('\n');
        s.append(String.format(Locale.ROOT, "buildSpeed %.3f%ncoreCap %d%ntime %.1f%n", unit.type.buildSpeed * Vars.state.rules.buildSpeed(us), core.storageCapacity, now));
        file.writeString(s.toString());
    }

    static List<Job> readOrders(Fi file){
        List<Job> out = new ArrayList<>();
        if(!file.exists()) return out;
        Job cur = null;
        for(String line : file.readString().split("\n")){
            String[] w = line.trim().split("\\s+");
            if(w[0].equals("inst") || w[0].equals("remove")){
                cur = new Job();
                cur.id = Integer.parseInt(w[1]);
                cur.kind = w[2];
                cur.unit = w[3].equals("-") ? null : w[3].replace('|', ' ');
                cur.remove = w[0].equals("remove");
                out.add(cur);
            }else if(w[0].equals("block") && cur != null){
                cur.blocks.add(new Object[]{w[1], Integer.parseInt(w[2]), Integer.parseInt(w[3]), Integer.parseInt(w[4]), w[5].equals("-") ? null : w[5]});
            }
        }
        return out;
    }

    static Map<String, Float> digPlan(Fi file){
        Map<String, Float> out = new LinkedHashMap<>();
        if(!file.exists()) return out;
        for(String line : file.readString().split("\n")){
            String[] w = line.trim().split("\\s+");
            if(w[0].equals("dig")) out.put(w[1], Float.parseFloat(w[2]));
        }
        return out;
    }

    static String time(float s){
        return String.format("%d:%02d", (int)(s / 60), (int)(s % 60));
    }

    /** Whether every block of a thing stands, built (one to be taken down: whether none does). */
    static boolean standing(Job j){
        for(Object[] b : j.blocks) if(built(b) == j.remove) return false;
        return true;
    }

    /** Whether some block of a thing stands or is going up. */
    static boolean begun(Job j){
        for(Object[] b : j.blocks){
            Tile t = Vars.world.tile((Integer)b[1], (Integer)b[2]);
            if(t != null && t.build != null && t.build.team == us && (t.block().name.equals(b[0]) || t.build instanceof ConstructBlock.ConstructBuild c && c.current != null && c.current.name.equals(b[0]))) return true;
        }
        return false;
    }

    static boolean built(Object[] b){
        Tile t = Vars.world.tile((Integer)b[1], (Integer)b[2]);
        return t != null && t.build != null && t.build.team == us && t.block().name.equals(b[0]) && !(t.build instanceof ConstructBlock.ConstructBuild);
    }

    /**
     * The core unit: builds the things given it, in order, once the core can pay for what of one is left, flying within
     * reach of each block (the game builds at the unit's speed); digs meanwhile what the core is short of that it can dig
     * (else what the plan has it dig), handing it to the core.
     */
    static class Builder extends AIController{
        final ArrayDeque<Job> queue = new ArrayDeque<>();
        Job job;
        Map<String, Float> digPlan = new HashMap<>();
        String doing = "";
        float stallSince = -1f;
        int lastLeft = -1;
        final Set<String> skipped = new HashSet<>();
        final ObjectMap<Item, Tile> walls = new ObjectMap<>();
        /** When nothing was given: dig until then, then ask again. */
        float waitUntil;

        /** Keeps of what it was given only these (the rest given back to the plan). */
        void keepOnly(List<Job> jobs){
            queue.removeIf(j -> !jobs.contains(j));
            if(job != null && !jobs.contains(job)){
                job = null;
                unit.plans.clear();
            }
        }

        void give(List<Job> jobs, Map<String, Float> dig){
            queue.addAll(jobs);
            digPlan = dig;
            waitUntil = jobs.isEmpty() ? (float)(Vars.state.tick / 60.0) + 30f : 0f;
        }

        boolean idle(){
            if(job != null || !queue.isEmpty()) return false;
            return Vars.state.tick / 60.0 >= waitUntil;
        }

        float digRate(Item item){
            //(MinerComp: an item each 50 + 15 ticks of digging at the unit's speed, with no scaling by hardness; the way to
            //the core and back with each stack is left to the plan's slack)
            return unit == null ? 5f : 60f * unit.type.mineSpeed / (50f + (unit.type.mineHardnessScaling ? item.hardness * 15f : 15f));
        }

        @Override
        public void updateMovement(){
        }

        @Override
        public void updateUnit(){
            var core = unit.team.core();
            if(core == null) return;
            offload(core);
            while(job == null && !queue.isEmpty()){
                job = queue.poll();
                if(standing(job)) job = null;
            }
            if(job == null){
                unit.plans.clear();
                String pick = null;
                float most = 0f;
                for(var e : digPlan.entrySet()) if(e.getValue() > most){
                    most = e.getValue();
                    pick = e.getKey();
                }
                doing = "digging " + (pick == null ? "what is least" : pick) + " (nothing to build)";
                dig(pick == null ? least(core) : Vars.content.item(pick), core);
                return;
            }
            if(job.remove){
                List<Object[]> stand = new ArrayList<>();
                for(Object[] b : job.blocks) if(built(b)) stand.add(b);
                if(stand.isEmpty()){
                    job = null;
                    return;
                }
                unit.mineTile = null;
                if(unit.plans.isEmpty()) for(Object[] b : stand) unit.addBuild(new BuildPlan((Integer)b[1], (Integer)b[2]));
                BuildPlan first = unit.buildPlan();
                doing = "taking down " + (job.unit == null ? job.kind : job.unit) + " (" + stand.size() + " blocks left)";
                if(first != null) moveTo(Vars.world.tile(first.x, first.y), Vars.buildingRange * 0.5f);
                return;
            }
            //what of the thing is left, and whether the core can pay for it
            List<Object[]> left = new ArrayList<>();
            for(Object[] b : job.blocks) if(!built(b) && !skipped.contains(key(b))) left.add(b);
            if(left.isEmpty()){
                job = null;
                stallSince = -1f;
                return;
            }
            ObjectIntMap<Item> need = new ObjectIntMap<>();
            for(Object[] b : left){
                Tile t = Vars.world.tile((Integer)b[1], (Integer)b[2]);
                if(t != null && t.build instanceof ConstructBlock.ConstructBuild) continue;
                for(ItemStack st : Vars.content.block((String)b[0]).requirements) need.increment(st.item, st.amount);
            }
            Item short_ = null;
            for(var e : need) if(core.items.get(e.key) < e.value){
                short_ = e.key;
                break;
            }
            if(short_ != null){
                unit.plans.clear();
                doing = "saving for " + (job.unit == null ? job.kind : job.unit) + " (short of " + short_.name + ")";
                Item dig = unit.canMine(short_) && wall(short_, core) != null ? short_ : least(core);
                dig(dig, core);
                return;
            }
            unit.mineTile = null;
            //builds: every block left planned, the unit within reach of the first
            if(unit.plans.isEmpty()) for(Object[] b : left){
                Block block = Vars.content.block((String)b[0]);
                BuildPlan plan = new BuildPlan((Integer)b[1], (Integer)b[2], (Integer)b[3], block, b[4] == null ? null : bp.Scratch.configOf((String)b[4]));
                unit.addBuild(plan);
            }
            BuildPlan first = unit.buildPlan();
            doing = "building " + (job.unit == null ? job.kind : job.unit) + " (" + left.size() + " blocks left)";
            if(first != null) moveTo(Vars.world.tile(first.x, first.y), Vars.buildingRange * 0.5f);
            //(a block that does not go up for a minute is put by)
            float now = (float)(Vars.state.tick / 60.0);
            if(left.size() != lastLeft){
                lastLeft = left.size();
                stallSince = now;
            }else if(stallSince >= 0f && now - stallSince > 60f && first != null){
                skipped.add(first.block.name + "@" + first.x + "," + first.y);
                System.out.println("    put by: " + first.block.name + " at " + first.x + "," + first.y + " (no progress for a minute)");
                unit.plans.removeFirst();
                stallSince = now;
            }
        }

        static String key(Object[] b){
            return b[0] + "@" + b[1] + "," + b[2];
        }

        Item least(CoreBlock.CoreBuild core){
            Item pick = null;
            for(Item item : Vars.content.items()) if(unit.canMine(item) && wall(item, core) != null && (pick == null || core.items.get(item) < core.items.get(pick))) pick = item;
            return pick;
        }

        void offload(CoreBlock.CoreBuild core){
            if(unit.stack.amount <= 0 || !unit.within(core, Vars.mineTransferRange)) return;
            if(unit.mineTile != null && unit.stack.amount < unit.type.itemCapacity) return;
            int accepted = core.acceptStack(unit.item(), unit.stack.amount, unit);
            if(accepted > 0) Call.transferItemTo(unit, unit.item(), accepted, unit.x, unit.y, core);
            else unit.clearItem();
        }

        void dig(Item item, CoreBlock.CoreBuild core){
            if(item == null) return;
            Tile wall = wall(item, core);
            if(wall == null) return;
            boolean full = unit.stack.amount >= unit.type.itemCapacity || (unit.stack.amount > 0 && unit.item() != item);
            if(full){
                unit.mineTile = null;
                moveTo(core.tile, Vars.mineTransferRange * 0.6f);
                return;
            }
            if(unit.mineTile != wall) unit.mineTile = wall;
            moveTo(wall, unit.type.mineRange * 0.6f);
        }

        Tile wall(Item item, Building core){
            Tile known = walls.get(item);
            if(known != null && known.wallDrop() == item) return known;
            Tile best = null;
            float bestD = Float.MAX_VALUE;
            for(Tile t : Vars.world.tiles){
                if(t.wallDrop() != item || !unit.canMine(item)) continue;
                float d = core.dst(t.worldx(), t.worldy());
                if(d < bestD){
                    bestD = d;
                    best = t;
                }
            }
            walls.put(item, best);
            return best;
        }
    }
}
