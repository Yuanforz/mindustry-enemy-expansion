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
    static final ObjectIntMap<Item> lastStock = new ObjectIntMap<>(), planStock = new ObjectIntMap<>();
    static float lastPlanAt;
    static final Set<String> builtKeys = new HashSet<>();
    /**
     * The core unit's build speed as it goes: the build seconds of every block it put up or took down, over the time it had
     * a block to build and was not digging (its flights between blocks in it; its waits for what the core lacks, digging,
     * not). The planner plans the core unit's time at this speed.
     */
    static float buildWork, buildBusy;
    static final Map<String, Boolean> stoodThen = new HashMap<>();

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
        work.child("shapes.txt").delete();
        work.child("steps").deleteDirectory();

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
                //(what is not up yet comes again in the orders, as much of it as the planner keeps, with what is new;
                //what is being taken down goes on)
                jobs.removeIf(j -> !j.remove && !standing(j));
                hand.keepOnly(jobs);
                long t0 = System.nanoTime();
                //(what the planner is given, kept by step, to plan a step again apart; orders only from this step)
                Fi stepDir = work.child("steps");
                stepDir.mkdirs();
                if(ledger.exists()) ledger.copyTo(stepDir.child("step-" + (steps + 1) + ".ledger"));
                state.copyTo(stepDir.child("step-" + (steps + 1) + ".state"));
                orders.delete();
                List<String> out = grow(args[0], map.path(), plan, ledger.path(), state.path(), orders.path(), spans, spanSeconds, think);
                //(the planner's whole say, kept by step)
                work.child("steps").child("step-" + (steps + 1) + ".log").writeString(String.join("\n", out) + "\n");
                float secs = (System.nanoTime() - t0) / 1e9f;
                thought += secs;
                steps++;
                List<Job> next = readOrders(orders);
                //(what takes down first, then what joins power, then lines, then the rest: what makes what stands work goes
                //up before what is new)
                next.sort(Comparator.comparingInt(j -> j.remove ? 0 : j.kind.equals("nodes") ? 1 : j.kind.equals("line") ? 2 : 3));
                jobs.addAll(next);
                hand.give(next, digPlan(orders));
                List<String> names = new ArrayList<>();
                for(Job j : next) names.add(j.unit == null ? j.kind : j.unit);
                String summary = out.stream().filter(l -> l.startsWith("  built by then")).reduce("", (a, c) -> a + " " + c.trim());
                System.out.println(String.format("  %s: step %d planned in %.0f s:%s; to build: %s", time(now), steps, secs, summary, names));
                for(String l : out) if(l.contains("NOT laid out") || l.contains("Exception") || l.contains("things wrong") || l.startsWith("schedule") || l.contains("expected into the core")) System.out.println("    " + l.trim());
                //(what came into the core over the span just gone, a second, against what was expected of it: what the
                //stock grew by, and what was spent on the blocks built meanwhile)
                ObjectIntMap<Item> spent = new ObjectIntMap<>();
                for(Job j : jobs) if(!j.remove) for(Object[] b : j.blocks) if(built(b) && builtKeys.add(Builder.key(b))) for(ItemStack st : Vars.content.block((String)b[0]).requirements) spent.increment(st.item, st.amount);
                StringBuilder got = new StringBuilder();
                float spanGone = Math.max(1f, now - lastPlanAt);
                for(Item item : Vars.content.items()){
                    int d = core.items.get(item) - planStock.get(item, 0) + spent.get(item, 0);
                    if(d != 0) got.append(String.format(Locale.ROOT, "%s %.2f ", item.name, d / spanGone));
                    planStock.put(item, core.items.get(item));
                }
                System.out.println("    came into the core over the last " + Math.round(spanGone) + " s, a second: " + got.toString().trim());
                lastPlanAt = now;
            }
            Boot.frames(1);
            if(!unit.mining() && unit.plans.size > 0) buildBusy += 1f / 60f;
            //(each block given that went up, or that was taken down, since the last look: its build seconds)
            if(f % 30 == 0) for(Job j : jobs) for(Object[] b : j.blocks){
                String key = Builder.key(b);
                boolean stands = built(b);
                Boolean was = stoodThen.put(key, stands);
                if(was == null) was = false;
                if(stands && !was || !stands && was && j.remove) buildWork += Vars.content.block((String)b[0]).buildTime / 60f;
            }
            //(how hard each machine that stands works, sampled each half second: what the planner is told it makes)
            if(f % 30 == 0) for(Job j : jobs){
                if(j.remove || j.unit == null || j.blocks.isEmpty() || !standing(j)) continue;
                Object[] b0 = j.blocks.get(0);
                Tile t = Vars.world.tile((Integer)b0[1], (Integer)b0[2]);
                if(t == null || t.build == null || t.build.block.consumers.length == 0) continue;
                float[] w = worked.computeIfAbsent(j.id, k -> new float[2]);
                w[0] += t.build.efficiency;
                w[1] += 1;
            }
            if(now >= nextTell){
                nextTell = now + 60f;
                int standing = 0;
                for(Job j : jobs) if(standing(j)) standing++;
                //(the machines that stand, and how many of them work; what came into the core this minute)
                int machines = 0, working = 0;
                Map<String, Integer> idle = new TreeMap<>();
                for(Building b : us.data().buildings){
                    //(what works by something: crafters, drills, generators that burn; not carriers, nodes, the core)
                    if(b.block.consumers.length == 0 || b instanceof CoreBlock.CoreBuild || b.block.group == mindustry.world.meta.BlockGroup.transportation || b.block.group == mindustry.world.meta.BlockGroup.liquids || b.block.group == mindustry.world.meta.BlockGroup.power) continue;
                    machines++;
                    if(b.efficiency > 0.5f) working++;
                    else idle.merge(b.block.name, 1, Integer::sum);
                }
                //(power: each grid with what it makes and needs; each generator's own efficiency)
                Set<mindustry.world.blocks.power.PowerGraph> grids = new LinkedHashSet<>();
                StringBuilder gens = new StringBuilder();
                for(Building b : us.data().buildings){
                    if(b.power != null && b.block.hasPower) grids.add(b.power.graph);
                    if(b instanceof mindustry.world.blocks.power.PowerGenerator.GeneratorBuild gb) gens.append(String.format(Locale.ROOT, "%s@%d,%d:%.2f ", b.block.name, b.tileX(), b.tileY(), gb.productionEfficiency));
                }
                StringBuilder grid = new StringBuilder();
                for(var gr : grids) if(gr.all.size > 1) grid.append(String.format(Locale.ROOT, "[%d blocks, makes %.1f needs %.1f, %.0f%%] ", gr.all.size, gr.getPowerProduced() * 60f, gr.getPowerNeeded() * 60f, gr.getSatisfaction() * 100f));
                System.out.println("    power: " + grids.size() + " grids " + grid + "| generators " + gens);
                StringBuilder came = new StringBuilder();
                for(Item item : Vars.content.items()){
                    int d = core.items.get(item) - lastStock.get(item, 0);
                    if(d != 0) came.append(item.name).append(d > 0 ? "+" : "").append(d).append(' ');
                    lastStock.put(item, core.items.get(item));
                }
                System.out.println(String.format("  %s: %d of %d things given stand; %d of %d machines work (idle: %s); core %s (this minute: %s); %s", time(now), standing, jobs.size(), working, machines, idle, core.items, came.toString().trim(), hand.doing));
                if((int)(now / 60f) % 5 == 0) System.out.println("    " + finalForm() + String.format(Locale.ROOT, "; built at %.2f a second (%.0f s of building over %.0f s)", buildWork / Math.max(1f, buildBusy), buildWork, buildBusy));
                System.out.println(String.format(Locale.ROOT, "    core unit at %.0f,%.0f; digs %s; holds %s %d; plans %d, building %s, mining %s", unit.x / 8f, unit.y / 8f, unit.mineTile == null ? "-" : unit.mineTile.x + "," + unit.mineTile.y + " (" + unit.mineTile.wallDrop() + ")", unit.item() == null ? "-" : unit.item().name, unit.stack.amount, unit.plans.size, unit.activelyBuilding(), unit.mining()));
            }
        }
        int standing = 0;
        for(Job j : jobs) if(standing(j)) standing++;
        System.out.println(String.format("CLIMB RESULT %s: %d of %d things given stand after %s; %d steps planned in %.0f s", map.nameWithoutExtension(), standing, jobs.size(), time(minutes * 60f), steps, thought));
        System.out.println("  " + finalForm());
        System.exit(0);
    }

    /**
     * How near the final form is: of Erekir's blocks a player may build (bp.Final.erekir), how many stand, and of those
     * that work by something, how many work at full by the game's own measure (efficiency); power to spare on the grids.
     */
    static String finalForm(){
        List<Block> all = bp.Final.erekir();
        Map<Block, Float> best = new HashMap<>();
        for(Building b : us.data().buildings){
            float e = b.block.consumers.length == 0 ? 1f : b.efficiency;
            best.merge(b.block, e, Math::max);
        }
        int stand = 0, full = 0;
        List<String> missing = new ArrayList<>(), slow = new ArrayList<>();
        for(Block bl : all){
            if(bl instanceof CoreBlock) continue;
            Float e = best.get(bl);
            if(e == null){
                missing.add(bl.name);
                continue;
            }
            stand++;
            if(e >= 0.99f) full++;
            else slow.add(bl.name + String.format(Locale.ROOT, ":%.2f", e));
        }
        float spare = 0f;
        Set<mindustry.world.blocks.power.PowerGraph> grids = new HashSet<>();
        for(Building b : us.data().buildings) if(b.power != null) grids.add(b.power.graph);
        for(var g : grids) spare += (g.getPowerProduced() - g.getPowerNeeded()) * 60f;
        return String.format(Locale.ROOT, "final form: %d of %d kinds of block stand, %d at full; power to spare %.0f/s; not yet: %d %s; not at full: %s", stand, all.size(), full, spare, missing.size(), missing.subList(0, Math.min(12, missing.size())), slow.subList(0, Math.min(12, slow.size())));
    }

    /** One step of the planner, in a process of its own (the game waits meanwhile); its output lines. */
    static List<String> grow(String dataDir, String map, String plan, String ledger, String state, String orders, String spans, String spanSeconds, String think){
        List<String> lines = new ArrayList<>();
        try{
            //(the planner's settings this was started with, -Dbp.*, passed on)
            List<String> cmd = new ArrayList<>(List.of("sh", "bp/run.sh", "-Dbp.wholeSpans=1", "-Dbp.attempts=4"));
            for(String k : System.getProperties().stringPropertyNames()) if(k.startsWith("bp.")) cmd.add("-D" + k + "=" + System.getProperty(k));
            cmd.addAll(List.of("bp.Grow", dataDir, map, plan, ledger, state, orders, spans, spanSeconds, think));
            ProcessBuilder pb = new ProcessBuilder(cmd);
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

    /** Of each thing that stands and works by something, how hard its machine worked since the last plan: {sum, samples}. */
    static final Map<Integer, float[]> worked = new HashMap<>();
    /** And over the window before that: the planner is told the better of the two (a machine just fed is not given up). */
    static final Map<Integer, Float> workedBefore = new HashMap<>();

    static void writeState(Fi file, List<Job> jobs, CoreBlock.CoreBuild core, Unit unit, Builder hand, float now){
        StringBuilder s = new StringBuilder();
        for(var e : worked.entrySet()){
            float[] w = e.getValue();
            if(w[1] < 60) continue;
            float avg = w[0] / w[1];
            Float before = workedBefore.get(e.getKey());
            s.append(String.format(Locale.ROOT, "work %d %.3f%n", e.getKey(), before == null ? avg : Math.max(avg, before)));
        }
        workedBefore.clear();
        for(var e : worked.entrySet()) if(e.getValue()[1] >= 60) workedBefore.put(e.getKey(), e.getValue()[0] / e.getValue()[1]);
        worked.clear();
        for(Item item : Vars.content.items()) if(core.items.get(item) > 0) s.append("stock ").append(item.name).append(' ').append(core.items.get(item)).append('\n');
        for(Item item : Vars.content.items()) if(unit.canMine(item) && hand.wall(item, core) != null) s.append(String.format(Locale.ROOT, "dig %s %.3f%n", item.name, hand.digRate(item)));
        for(Job j : jobs) if(!j.remove && standing(j)) s.append("built ").append(j.id).append('\n');
        for(Job j : jobs) if(!j.remove && !standing(j) && begun(j)) s.append("begun ").append(j.id).append('\n');
        s.append(String.format(Locale.ROOT, "buildSpeed %.3f%ncoreCap %d%ntime %.1f%n", unit.type.buildSpeed * Vars.state.rules.buildSpeed(us), core.storageCapacity, now));
        s.append(String.format(Locale.ROOT, "buildWork %.1f%nbuildBusy %.1f%n", buildWork, buildBusy));
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

    /** Whether every block of a thing stands, built (one to be taken down: whether none does, nor is half taken down). */
    static boolean standing(Job j){
        for(Object[] b : j.blocks) if(j.remove ? built(b) || comingDown(b) : !built(b)) return false;
        return true;
    }

    /** Whether a block is being taken down there (the game's ConstructBuild of it, not building it). */
    static boolean comingDown(Object[] b){
        Tile t = Vars.world.tile((Integer)b[1], (Integer)b[2]);
        return t != null && t.build instanceof ConstructBlock.ConstructBuild c && t.build.team == us && !c.wasConstructing && c.current != null && c.current.name.equals(b[0]);
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
            if(unit != null) unit.plans.clear();
        }

        void give(List<Job> jobs, Map<String, Float> dig){
            queue.addAll(jobs);
            digPlan = dig;
            //(what was put by is tried again: what stood in its way may be gone)
            skipped.clear();
            building = null;
            waitUntil = jobs.isEmpty() ? (float)(Vars.state.tick / 60.0) + 30f : 0f;
        }

        boolean idle(){
            if(!queue.isEmpty()) return false;
            return Vars.state.tick / 60.0 >= waitUntil;
        }

        /** Seconds spent digging each item (the flights to the core with it too), and how much of it came of that. */
        final ObjectFloatMap<Item> digTime = new ObjectFloatMap<>();
        final ObjectIntMap<Item> dugIn = new ObjectIntMap<>();

        float digRate(Item item){
            //(as it has gone, once a load of it has come in: what came of the digging, and what is in hand, over the time
            //spent on it, the flights too; before, as MinerComp has it, an item each 50 + 15 (by hardness) ticks of
            //digging at the unit's speed)
            float t = digTime.get(item, 0f);
            if(dugIn.get(item, 0) > 0 && t > 0f) return (dugIn.get(item, 0) + (unit != null && unit.item() == item ? unit.stack.amount : 0)) / t;
            if(unit == null) return 5f;
            return 60f * unit.type.mineSpeed * Vars.state.rules.unitMineSpeed(us) / (50f + (unit.type.mineHardnessScaling ? item.hardness * 15f : 15f));
        }

        @Override
        public void updateMovement(){
        }

        /** The block being built now, since when it last went up, how far it was then (one that does not go up for a minute is put by). */
        Object[] building;
        float buildingSince, lastProgress;

        @Override
        public void updateUnit(){
            var core = unit.team.core();
            if(core == null) return;
            offload(core);
            queue.removeIf(Climb::standing);
            job = queue.isEmpty() ? null : queue.peek();
            //what to take down, first
            for(Job j : queue) if(j.remove){
                //(what still stands of it, a block half taken down too: it goes on being taken down)
                List<Object[]> stand = new ArrayList<>();
                for(Object[] b : j.blocks) if(built(b) || comingDown(b)) stand.add(b);
                if(stand.isEmpty()) continue;
                unit.mineTile = null;
                if(unit.plans.isEmpty() || !unit.plans.first().breaking) {
                    unit.plans.clear();
                    for(Object[] b : stand) unit.addBuild(new BuildPlan((Integer)b[1], (Integer)b[2]));
                }
                BuildPlan first = unit.buildPlan();
                doing = "taking down " + (j.unit == null ? j.kind : j.unit) + " (" + stand.size() + " blocks left)";
                if(first != null) moveTo(Vars.world.tile(first.x, first.y), Vars.buildingRange * 0.5f);
                return;
            }
            //the first block, in the order given, that goes up already or the core can pay for: what cannot be paid for
            //waits, what can goes up meanwhile
            Object[] next = null, wanting = null;
            Job of = null;
            for(Job j : queue){
                for(Object[] b : j.blocks){
                    if(built(b) || skipped.contains(key(b))) continue;
                    Tile t = Vars.world.tile((Integer)b[1], (Integer)b[2]);
                    boolean going = t != null && t.build instanceof ConstructBlock.ConstructBuild c && c.current != null && c.current.name.equals(b[0]);
                    if(going || pays(b, core)){
                        next = b;
                        of = j;
                        break;
                    }
                    if(wanting == null) wanting = b;
                }
                if(next != null) break;
            }
            if(next == null){
                unit.plans.clear();
                building = null;
                Item short_ = wanting == null ? null : shortOf(wanting, core);
                String pick = null;
                float most = 0f;
                for(var e : digPlan.entrySet()) if(e.getValue() > most){
                    most = e.getValue();
                    pick = e.getKey();
                }
                Item dig = short_ != null && unit.canMine(short_) && wall(short_, core) != null ? short_ : pick != null ? Vars.content.item(pick) : least(core);
                doing = wanting == null ? "digging " + (dig == null ? "nothing" : dig.name) + " (nothing to build)" : "digging " + (dig == null ? "nothing" : dig.name) + " (short of " + (short_ == null ? "?" : short_.name) + " for " + wanting[0] + ")";
                dig(dig, core);
                return;
            }
            unit.mineTile = null;
            Block block = Vars.content.block((String)next[0]);
            Tile at = Vars.world.tile((Integer)next[1], (Integer)next[2]);
            boolean going = at != null && at.build instanceof ConstructBlock.ConstructBuild c0 && c0.current == block;
            //(one the game would not let be built there now (what stands in its way): put by at once, tried again later)
            //(a block put over one of ours it may replace, as a player puts a router over a duct: the game's check with no
            //core radius asked, which in its other form refuses anything over a block, as for a payload set down)
            boolean over = at != null && at.build != null && at.build.team == us && block.canReplace(at.block()) && Build.validPlace(block, us, (Integer)next[1], (Integer)next[2], (Integer)next[3], false, false);
            if(!going && !over && !Build.validPlace(block, us, (Integer)next[1], (Integer)next[2], (Integer)next[3], false, true)){
                skipped.add(key(next));
                System.out.println("    put by: " + next[0] + " at " + next[1] + "," + next[2] + " (cannot be built there now)");
                unit.plans.clear();
                building = null;
                return;
            }
            BuildPlan cur = unit.buildPlan();
            if(cur == null || cur.breaking || cur.x != (Integer)next[1] || cur.y != (Integer)next[2] || cur.block != block){
                unit.plans.clear();
                unit.addBuild(new BuildPlan((Integer)next[1], (Integer)next[2], (Integer)next[3], block, next[4] == null ? null : bp.Scratch.configOf((String)next[4])));
            }
            doing = "building " + (of.unit == null ? of.kind : of.unit) + " (" + next[0] + ")";
            moveTo(Vars.world.tile((Integer)next[1], (Integer)next[2]), Vars.buildingRange * 0.5f);
            float now = (float)(Vars.state.tick / 60.0);
            float progress = at != null && at.build instanceof ConstructBlock.ConstructBuild c1 ? c1.progress : 0f;
            if(building != next){
                building = next;
                buildingSince = now;
                lastProgress = progress;
            }else if(progress > lastProgress){
                buildingSince = now;
                lastProgress = progress;
            }else if(now - buildingSince > 60f){
                skipped.add(key(next));
                System.out.println("    put by: " + next[0] + " at " + next[1] + "," + next[2] + " (no progress for a minute)");
                unit.plans.clear();
                building = null;
            }
        }

        /** Whether the core can pay for a block now. */
        static boolean pays(Object[] b, CoreBlock.CoreBuild core){
            for(ItemStack st : Vars.content.block((String)b[0]).requirements) if(core.items.get(st.item) < st.amount) return false;
            return true;
        }

        static Item shortOf(Object[] b, CoreBlock.CoreBuild core){
            for(ItemStack st : Vars.content.block((String)b[0]).requirements) if(core.items.get(st.item) < st.amount) return st.item;
            return null;
        }

        static String key(Object[] b){
            return b[0] + "@" + b[1] + "," + b[2];
        }

        /** The first item the core is short of for what of a thing is left to build (null: it can pay for all of it). */
        Item short_(Job j, CoreBlock.CoreBuild core){
            ObjectIntMap<Item> need = new ObjectIntMap<>();
            for(Object[] b : j.blocks){
                if(built(b) || skipped.contains(key(b))) continue;
                Tile t = Vars.world.tile((Integer)b[1], (Integer)b[2]);
                if(t != null && t.build instanceof ConstructBlock.ConstructBuild) continue;
                for(ItemStack st : Vars.content.block((String)b[0]).requirements) need.increment(st.item, st.amount);
            }
            for(var e : need) if(core.items.get(e.key) < e.value) return e.key;
            return null;
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
            if(accepted > 0){
                dugIn.increment(unit.item(), accepted);
                Call.transferItemTo(unit, unit.item(), accepted, unit.x, unit.y, core);
            }else unit.clearItem();
        }

        void dig(Item item, CoreBlock.CoreBuild core){
            if(item == null) return;
            Tile wall = wall(item, core);
            if(wall == null) return;
            digTime.increment(item, 0f, Time.delta / 60f);
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
            //(looked for once, found or not: walls of ore do not come and go)
            if(walls.containsKey(item)){
                Tile known = walls.get(item);
                if(known == null || known.wallDrop() == item) return known;
            }
            Tile best = null;
            float bestD = Float.MAX_VALUE;
            if(unit.canMine(item)) for(Tile t : Vars.world.tiles){
                if(t.wallDrop() != item) continue;
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
