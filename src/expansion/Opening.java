package expansion;

import arc.util.*;
import expansion.Blueprint.Part;
import expansion.econ.*;
import mindustry.ai.types.*;
import mindustry.game.*;
import mindustry.game.Teams.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.storage.*;
import mindustry.world.blocks.units.*;

import java.util.*;

import static mindustry.Vars.*;

/**
 * The opening, as the economy planner (expansion.econ) works it out: from a bare core, the fastest order of drills,
 * generators, crushers, furnace and yard to a first unit, laid out on the map as a whole, and built by the core's unit in that
 * order. Once it all stands, or it gets no further, the base is the brain's: its drills, crushers and furnaces are taken on as
 * sources of its own, its yards as yards, and the brain grows it from there as it would its own.
 * (measured on 32 generated maps, by the same rules: the first tank came out at a median of 3:23 this way, against 5:41 by the
 * brain's own opening, which laid a drill, then a line, then a furnace, one decision at a time)
 */
public class Opening{
    public enum Stage{idle, planning, building, done, failed}

    public Stage stage = Stage.idle;
    /** What it is doing or why it stopped, for the notes. */
    public String note = "";

    /** The machines the planner may use, and the lines and power that go with them, as far as they are researched. */
    static final String[] machines = {"plasma-bore", "turbine-condenser", "cliff-crusher", "silicon-arc-furnace", "tank-fabricator", "impact-drill", "vent-condenser", "ship-fabricator", "mech-fabricator", "electrolyzer", "tank-refabricator"};
    static final String[] logistics = {"duct", "duct-router", "duct-bridge", "beam-node", "underflow-duct", "overflow-duct", "reinforced-conduit", "reinforced-bridge-conduit", "reinforced-liquid-router"};
    /** The yards it may open with, the first it may build being the one: and the unit each makes. */
    static final String[][] firstUnits = {{"tank-fabricator", "stell"}, {"ship-fabricator", "elude"}, {"mech-fabricator", "merui"}};

    static class Plan{
        Catalog cat;
        Layout works;
        List<Machine> order;
        int unit;
        float modelFirst;
    }

    private @Nullable Plan ready;
    private @Nullable Throwable error;
    private @Nullable Plan plan;
    private @Nullable expansion.econ.Hand hand;
    private float begunAt, lastProgressAt;
    /** How long working the order out took, in milliseconds of the machine's time. */
    public long planMillis;
    private int lastNext = -1;

    public void clear(){
        stage = Stage.idle;
        note = "";
        ready = null;
        error = null;
        plan = null;
        hand = null;
        lastNext = -1;
    }

    /** Whether the brain is to leave its builders and its decisions to the opening this frame. */
    public boolean active(){
        return stage == Stage.planning || stage == Stage.building;
    }

    /**
     * Moves the opening on.
     * @return whether the brain is to leave everything to it this frame
     */
    public boolean update(Brain brain, TeamData data, float clock){
        try{
            return step(brain, data, clock);
        }catch(Throwable e){
            Log.err("[EnemyExpansion] The planned opening failed; the brain opens by itself.", e);
            finish(brain, data, "failed: " + e);
            stage = Stage.failed;
            return false;
        }
    }

    private boolean step(Brain brain, TeamData data, float clock){
        if(stage == Stage.done || stage == Stage.failed) return false;
        CoreBlock.CoreBuild core = data.core();
        if(core == null){
            stage = Stage.failed;
            note = "no core";
            return false;
        }
        if(stage == Stage.idle){
            //only from a bare core at the start of a map, and with what it opens with researched
            if(!Options.plannedOpening.on() || data.buildings.size > data.cores.size || clock > 60f * 60f){
                stage = Stage.failed;
                note = "not a bare start";
                return false;
            }
            String[] first = null;
            for(String[] pair : firstUnits){
                if(may(brain, pair[0]) && content.unit(pair[1]) != null){
                    first = pair;
                    break;
                }
            }
            if(first == null || !may(brain, "plasma-bore") || !may(brain, "turbine-condenser") || !may(brain, "silicon-arc-furnace") || !may(brain, "cliff-crusher") || !may(brain, "duct") || !may(brain, "beam-node")){
                stage = Stage.failed;
                note = "what it opens with is not researched";
                return false;
            }
            List<String> blocks = new ArrayList<>();
            for(String name : machines) if(may(brain, name)) blocks.add(name);
            Set<String> allowed = new HashSet<>(blocks);
            for(String name : logistics) if(may(brain, name)) allowed.add(name);
            Team team = data.team;
            CoreBlock coreBlock = (CoreBlock)core.block;
            //(-Dxp.openingunit=locus: another unit to open towards, for looking into the planner)
            String unitName = System.getProperty("xp.openingunit", first[1]);
            float buildRule = state.rules.buildSpeed(team);
            stage = Stage.planning;
            begunAt = clock;
            note = "working out the fastest order to a " + unitName;
            //worked out here and now, in the game's own thread, which waits for it a few seconds once as the map begins: the game's
            //blocks work out where they may stand in buffers they all share, and worked out beside the game, the opening broke the
            //game's own checks at random
            long t0 = System.nanoTime();
            try{
                ready = plan(blocks, allowed, team, coreBlock, buildRule, unitName);
            }catch(Throwable e){
                error = e;
            }
            planMillis = (System.nanoTime() - t0) / 1000000;
        }
        if(stage == Stage.planning){
            if(error != null) throw new RuntimeException(error);
            if(ready == null){
                stage = Stage.failed;
                note = "no order worked out";
                return false;
            }
            plan = ready;
            if(plan.order.isEmpty()){
                stage = Stage.failed;
                note = "the planner found no order";
                return false;
            }
            hand = new expansion.econ.Hand(plan.works, plan.order);
            stage = Stage.building;
            lastProgressAt = clock;
            begunAt = clock;
            Log.info("[EnemyExpansion] Opening as planned: @ machines to a first @, by @ by the reckoning (worked out in @ ms).", plan.order.size(), plan.cat.get(plan.unit).name, time(plan.modelFirst), planMillis);
        }
        //building: the core's unit is the hand's
        Unit builder = data.units.find(unit -> unit.canBuild() && unit.type == ((CoreBlock)core.block).unitType);
        if(builder == null && data.team.rules().aiCoreSpawn && ((CoreBlock)core.block).unitType.supportsEnv(state.rules.env)){
            builder = ((CoreBlock)core.block).unitType.create(data.team);
            builder.set(core);
            builder.add();
        }
        if(builder != null && builder.controller() != hand) builder.controller(hand);
        if(hand.next != lastNext){
            lastNext = hand.next;
            lastProgressAt = clock;
        }
        note = "building as planned: " + hand.doing;
        //done when it all stands; or it has got no further in two minutes, or taken eight in all, or the base is being hit: from then on
        //what holds it is the brain's to see to
        String over = hand.allBuilt() ? "all of it stands" : clock - lastProgressAt > 60f * 120f ? "no further in two minutes" : clock - begunAt > 60f * 60f * 8f ? "eight minutes" : brain.director.posture == Director.Posture.pressed ? "the base is being hit" : null;
        if(over == null) return true;
        finish(brain, data, over);
        stage = Stage.done;
        return false;
    }

    /** The planner's order, laid out on the map and costed as it is laid out. */
    static Plan plan(List<String> blocks, Set<String> allowed, Team team, CoreBlock core, float buildRule, String unitName){
        Plan out = new Plan();
        Catalog cat = Recipes.read(blocks, core, buildRule);
        cat.logistics(allowed);
        Field field = Sites.scan(cat, team, Integer.getInteger("xp.openingradius", 110));
        expansion.econ.Planner planner = new expansion.econ.Planner(cat, field);
        int unit = cat.id(unitName);
        Recipe making = null;
        for(Recipe recipe : cat.recipes) if(recipe.unitOut == unit) making = recipe;
        if(making == null) throw new IllegalStateException("nothing makes " + unitName);
        List<Machine> plant = planner.balance(unit, 1f / making.unitSeconds);
        Timeline model = planner.order(plant, unit);
        Layout works = new Layout(cat, field, team, allowed);
        works.prepare(model.order);
        //what the layout could not lead its supplies to gets a maker of its own near it, and it is all worked out again (as the
        //planner's own test does it: left out, a crusher stood forty tiles from its furnace with no line, and the furnace never ran)
        for(int round = 0; round < 2; round++){
            List<Object[]> unfed = works.unfed();
            if(unfed.isEmpty()) break;
            List<Machine> more = new ArrayList<>(model.order);
            Set<String> asked = new HashSet<>();
            for(Object[] u : unfed){
                int res = (Integer)u[0];
                Machine taker = (Machine)u[1];
                if(!asked.add(res + "@" + System.identityHashCode(taker))) continue;
                Machine maker = planner.makerNear(res, taker);
                if(maker != null) more.add(maker);
            }
            if(more.size() == model.order.size()) break;
            planner.extras = 0;
            model = planner.order(more, unit);
            works = new Layout(cat, field, team, allowed);
            works.prepare(model.order);
        }
        for(Machine m : model.order){
            int[] real = works.realCost(m);
            m.buildSeconds = works.buildSeconds(m);
            m.draw = works.drawShare(m);
            System.arraycopy(real, 0, m.cost, 0, real.length);
        }
        if(System.getProperty("xp.notes") != null) Log.info("[EnemyExpansion] opening layout: @; problems @", works.summary(), works.problems);
        //built as the lines were laid: what feeds each thing before it, and nothing that feeds nothing (xp.reckonedorder for the planner's own)
        List<Machine> order = model.order;
        Timeline settled = new Timeline(cat, field, order);
        settled.runToGoal(unit, planner.limit);
        if(System.getProperty("xp.reckonedorder") == null){
            //(and what each costs to be had by then: a machine whose cost holds what nothing built so far makes still waits for its maker)
            List<Machine> bySupply = planner.payableFirst(works.bySupply(model.order), null);
            if(!bySupply.equals(model.order)){
                works.reorder(bySupply);
                for(Machine m : bySupply){
                    int[] real = works.realCost(m);
                    m.buildSeconds = works.buildSeconds(m);
                    m.draw = works.drawShare(m);
                    System.arraycopy(real, 0, m.cost, 0, real.length);
                }
                Timeline again = new Timeline(cat, field, bySupply);
                again.runToGoal(unit, planner.limit);
                //only where the reckoning still gets there by it: else the planner's order, as it was
                if(again.first[unit] < 1e6f || settled.first[unit] >= 1e6f){
                    order = bySupply;
                    settled = again;
                }else{
                    works.reorder(model.order);
                }
            }
        }
        out.cat = cat;
        out.works = works;
        out.order = settled.order;
        out.unit = unit;
        out.modelFirst = settled.first[unit];
        return out;
    }

    /** Hands the base to the brain: what stands of the order is taken on as its own, and the core's unit is its builder again. */
    private void finish(Brain brain, TeamData data, String why){
        note = "handed over: " + why;
        int taken = 0;
        if(plan != null){
            for(Machine m : plan.order){
                Part at = plan.works.at.get(m);
                if(at == null) continue;
                Building build = world.build(at.x, at.y);
                if(build == null || build.team != data.team || build.block != at.block) continue;
                int x = build.tileX(), y = build.tileY();
                if(brain.net.things.contains(thing -> thing.x == x && thing.y == y)) continue;
                Item item = m.item >= 0 ? content.item(plan.cat.get(m.item).name) : null;
                if(build.block instanceof BeamDrill || build.block instanceof Drill){
                    if(item == null && build instanceof BeamDrill.BeamDrillBuild beam) item = beam.lastItem;
                    if(item == null && build instanceof Drill.DrillBuild drill) item = drill.dominantItem;
                    if(item == null) continue;
                    brain.net.things.add(new Net.Thing(Net.Kind.mine, x, y, item, m.item >= 0 ? m.out(m.item) : 1f));
                }else if(build.block instanceof WallCrafter crusher){
                    brain.net.things.add(new Net.Thing(Net.Kind.quarry, x, y, crusher.output, m.item >= 0 ? m.out(m.item) : Rates.crusher(crusher, crusher.size)));
                }else if(build.block instanceof mindustry.world.blocks.production.GenericCrafter crafter && crafter.outputItems != null && crafter.outputItems.length > 0){
                    Item made = crafter.outputItems[0].item;
                    brain.net.things.add(new Net.Thing(Net.Kind.maker, x, y, made, Rates.makes(build.block, made)));
                }else{
                    continue;
                }
                taken++;
            }
        }
        brain.net.fresh();
        //the core's unit back to the brain
        for(Unit unit : data.units){
            if(unit.controller() == hand && hand != null){
                unit.clearBuilding();
                unit.mineTile = null;
                unit.controller(new ExpansionAI(brain, new BuilderAI()));
            }
        }
        brain.openedAsPlanned();
        Log.info("[EnemyExpansion] The planned opening hands over (@): @ sources taken on.", why, taken);
        if(hand != null && plan != null){
            Log.info("[EnemyExpansion]   it was @ (#@ of @ begun); trouble: @", hand.doing, hand.next, plan.order.size(), hand.trouble);
            if(System.getProperty("xp.notes") != null){
                StringBuilder all = new StringBuilder();
                for(int i = 0; i < plan.order.size(); i++){
                    Machine m = plan.order.get(i);
                    Part at = plan.works.at.get(m);
                    Building b = at == null ? null : world.build(at.x, at.y);
                    all.append("\n    #").append(i + 1).append(" ").append(m).append(" begun ").append(time(hand.begun[i])).append(" done ").append(time(hand.finished[i]))
                        .append(b == null ? " (not standing)" : String.format(" eff %.2f holding %s%s%s", b.efficiency, b.items == null ? "-" : b.items.toString(), b.liquids == null ? "" : String.format(" liquid %s %.1f", b.liquids.current().name, b.liquids.currentAmount()),
                            b.power == null ? "" : String.format(" power %.2f", b.power.status)));
                }
                Log.info("[EnemyExpansion]   the order: @", all);
                //what of each crusher's and furnace's job stands
                for(int i = 0; i < plan.order.size(); i++){
                    Machine m = plan.order.get(i);
                    if(!m.recipe.name.equals("impact-drill") && !m.recipe.name.equals("vent-condenser") && !m.recipe.name.equals("silicon-arc-furnace")) continue;
                    StringBuilder parts = new StringBuilder();
                    int missing = 0;
                    for(Part p : plan.works.job(m)){
                        Building b = world.build(p.x, p.y);
                        boolean stands = b != null && b.block == p.block;
                        if(!stands) missing++;
                        if(parts.length() < 600) parts.append(p.block.name).append('@').append(p.x).append(',').append(p.y).append(stands ? "" : "!").append(' ');
                    }
                    Log.info("[EnemyExpansion]   #@ @: @ parts, @ missing: @", i + 1, m, plan.works.job(m).size, missing, parts);
                }
            }
        }
    }

    private static boolean may(Brain brain, String name){
        Block block = content.block(name);
        return block != null && brain.may(block);
    }

    static String time(float seconds){
        if(Float.isNaN(seconds) || seconds > 1e6f) return "never";
        return (int)seconds / 60 + ":" + (((int)seconds % 60) < 10 ? "0" : "") + (int)seconds % 60;
    }
}
