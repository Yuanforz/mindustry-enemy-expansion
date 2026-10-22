package xtest;

import arc.files.*;
import arc.struct.*;
import arc.util.*;
import bp.*;
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

import java.util.*;

/**
 * Line three from nothing: a map, its core with what it lands with, the core unit, and the master of the final form; the
 * schedule (bp.Schedule) says what of the master goes up next from what stands, the core unit digs what it can dig and
 * builds, at its own speed and within its reach as a player's does; planned again as it goes. Nobody steps in.
 *
 * Usage: Climb data-dir map.msav parts.master minutes [spans span-seconds think-seconds]
 */
public class Climb{
    static Team us;
    static Schedule sched;

    public static void main(String[] args){
        Boot.boot(args[0]);
        Fi map = new Fi(args[1]);
        sched = Schedule.read(args[2]);
        float minutes = Float.parseFloat(args[3]);
        int spans = args.length > 4 ? Integer.parseInt(args[4]) : 12;
        float spanSeconds = args.length > 5 ? Float.parseFloat(args[5]) : 120f;
        float think = args.length > 6 ? Float.parseFloat(args[6]) : 60f;

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
        System.out.println(String.format("CLIMB %s: core %s at %d,%d with %s; %d parts in the master", map.nameWithoutExtension(), core.block.name, core.tileX(), core.tileY(), core.items, sched.parts.size()));

        Builder hand = new Builder();
        Unit unit = null;
        int frames = (int)(minutes * 3600);
        float nextPlan = 0f, nextTell = 0f;
        for(int f = 0; f < frames; f++){
            float now = f / 60f;
            core = us.core();
            if(core == null) break;
            if(unit == null || unit.dead() || !unit.isAdded()){
                unit = ((CoreBlock)core.block).unitType.spawn(us, core.x, core.y);
                unit.controller(hand);
            }
            //planned again every so often, or once what was planned is up
            if(now >= nextPlan || hand.finished){
                Schedule.State st = new Schedule.State();
                for(Schedule.Part p : sched.parts) if(standing(p)) st.built.add(p);
                for(Item item : Vars.content.items()) if(core.items.get(item) > 0) st.stock.put(item.name, (float)core.items.get(item));
                for(Item item : Vars.content.items()) if(unit.canMine(item) && hand.wall(item, core) != null) st.dig.put(item.name, hand.digRate(item));
                st.buildSpeed = unit.type.buildSpeed * Vars.state.rules.buildSpeed(us);
                st.coreCap = core.storageCapacity;
                long t0 = System.nanoTime();
                Schedule.Result r = sched.plan(st, spans, spanSeconds, think);
                List<Schedule.Part> first = new ArrayList<>();
                for(var e : r.span.entrySet()) if(e.getValue() == 1) first.add(e.getKey());
                //(of the first span's, what costs least first: the cheap ones that pay go up while the dear ones are saved for)
                first.sort(Comparator.comparingDouble(p -> p.cost.values().stream().reduce(0f, Float::sum)));
                hand.set(first, r.digSeconds);
                System.out.println(String.format("  %s: planned (%s, %.1f s): %d of %d parts stand; next: %s; digging %s", time(now), r.status, (System.nanoTime() - t0) / 1e9, st.built.size(), sched.parts.size(), names(first), r.digSeconds));
                nextPlan = now + spanSeconds;
            }
            Boot.frames(1);
            if(now >= nextTell){
                nextTell = now + 60f;
                int standing = 0;
                for(Schedule.Part p : sched.parts) if(standing(p)) standing++;
                System.out.println(String.format("  %s: %d of %d parts stand; core %s; %s", time(now), standing, sched.parts.size(), core.items, hand.doing));
            }
        }
        int standing = 0;
        for(Schedule.Part p : sched.parts) if(standing(p)) standing++;
        System.out.println(String.format("CLIMB RESULT %s: %d of %d parts stand after %s", map.nameWithoutExtension(), standing, sched.parts.size(), time(minutes * 60f)));
        System.exit(0);
    }

    static String time(float s){
        return String.format("%d:%02d", (int)(s / 60), (int)(s % 60));
    }

    static String names(List<Schedule.Part> ps){
        List<String> out = new ArrayList<>();
        for(Schedule.Part p : ps) out.add(p.name);
        return out.toString();
    }

    /** Whether every block of a part stands, built. */
    static boolean standing(Schedule.Part p){
        for(Object[] b : p.blocks) if(!built(b)) return false;
        return true;
    }

    static boolean built(Object[] b){
        Tile t = Vars.world.tile((Integer)b[1], (Integer)b[2]);
        return t != null && t.build != null && t.build.team == us && t.block().name.equals(b[0]) && !(t.build instanceof ConstructBlock.ConstructBuild);
    }

    /**
     * The core unit: builds the parts given it, cheapest first, once the core can pay for what of a part is left, flying
     * within reach of each block (the game builds at the unit's speed); digs meanwhile what the parts want that it can dig
     * (or what the schedule has it dig), handing it to the core.
     */
    static class Builder extends AIController{
        final ArrayDeque<Schedule.Part> queue = new ArrayDeque<>();
        Schedule.Part part;
        Map<String, Float> digPlan = new HashMap<>();
        String doing = "";
        float stallSince = -1f;
        int lastLeft = -1;
        final Set<String> skipped = new HashSet<>();
        final ObjectMap<Item, Tile> walls = new ObjectMap<>();

        /** Whether what it was given last is all up (set once, when the last of it went up). */
        boolean finished;

        void set(List<Schedule.Part> parts, Map<String, Float> dig){
            queue.clear();
            queue.addAll(parts);
            if(part != null && !parts.contains(part)) part = null;
            digPlan = dig;
            finished = false;
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
            while(part == null && !queue.isEmpty()){
                part = queue.poll();
                if(standing(part)){
                    part = null;
                    if(queue.isEmpty()) finished = true;
                }
            }
            if(part == null){
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
            //what of the part is left, and whether the core can pay for it
            List<Object[]> left = new ArrayList<>();
            for(Object[] b : part.blocks) if(!built(b) && !skipped.contains(key(b))) left.add(b);
            if(left.isEmpty()){
                part = null;
                stallSince = -1f;
                if(queue.isEmpty()) finished = true;
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
                doing = "saving for " + part.name + " (short of " + short_.name + ")";
                Item dig = unit.canMine(short_) && wall(short_, core) != null ? short_ : least(core);
                dig(dig, core);
                return;
            }
            unit.mineTile = null;
            //builds: every block left planned, the unit within reach of the first
            if(unit.plans.isEmpty()) for(Object[] b : left){
                Block block = Vars.content.block((String)b[0]);
                BuildPlan plan = new BuildPlan((Integer)b[1], (Integer)b[2], (Integer)b[3], block, b[4] == null ? null : Scratch.configOf((String)b[4]));
                unit.addBuild(plan);
            }
            BuildPlan first = unit.buildPlan();
            doing = "building " + part.name + " (" + left.size() + " blocks left)";
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
