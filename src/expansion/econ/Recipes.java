package expansion.econ;

import mindustry.*;
import mindustry.content.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.liquid.*;
import mindustry.world.blocks.storage.*;
import mindustry.world.blocks.units.*;
import mindustry.world.consumers.*;

import java.util.*;

/** Reads what the game's machines take and make into a {@link Catalog}. Numbers come from the blocks themselves, not from tables. */
public final class Recipes{
    private Recipes(){}

    /**
     * @param blocks the machines that may be built, by name; anything that is not a producer, crafter, generator or unit block is left out
     * @param core what the core is, for its capacity and its unit
     */
    public static Catalog read(Collection<String> blocks, CoreBlock core, float buildRule){
        Catalog cat = new Catalog();
        for(Item item : Vars.content.items()){
            boolean storable = !core.incinerateNonBuildable || item.buildable;
            boolean hand = core.unitType.mineWalls && core.unitType.mineTier >= item.hardness && item.buildable;
            cat.add(item.name, Res.Kind.item, storable, hand);
        }
        for(Liquid liquid : Vars.content.liquids()) cat.add(liquid.name, Res.Kind.liquid, false, false);

        UnitType unit = core.unitType;
        cat.coreCap = core.itemCapacity;
        cat.buildSpeed = unit.buildSpeed;
        float perItem = unit.mineHardnessScaling ? 50f + 15f * 3f : 65f;
        //the unit's timer has to pass the mark, which it does in whole frames
        cat.mineRate = 60f / (float)Math.ceil(perItem / unit.mineSpeed);
        cat.carry = unit.itemCapacity;
        cat.flySpeed = unit.speed * 60f / Vars.tilesize;
        cat.handOverRange = Vars.mineTransferRange / Vars.tilesize;
        cat.mineRange = unit.mineRange / Vars.tilesize;
        cat.buildRange = Vars.buildingRange / Vars.tilesize;
        cat.buildRule = buildRule;

        //every unit any of them makes or takes first, so that every recipe's arrays are as long as the catalog
        List<Block> list = new ArrayList<>();
        for(String name : blocks){
            Block block = Vars.content.block(name);
            if(block == null) throw new IllegalArgumentException("no block called " + name);
            //what the map's rules ban can't be built there
            if(Vars.state.rules.isBanned(block)) continue;
            list.add(block);
            if(block instanceof UnitFactory factory) for(UnitFactory.UnitPlan plan : factory.plans) unit(cat, plan.unit);
            if(block instanceof Reconstructor rec) for(UnitType[] pair : rec.upgrades){ unit(cat, pair[0]); unit(cat, pair[1]); }
            //(units only designs make, by assemblers, are known too, so every recipe's arrays are as long as the catalog)
            if(block instanceof UnitAssembler asm) for(UnitAssembler.AssemblerUnitPlan plan : asm.plans) unit(cat, plan.unit);
            //(and each turret's stand-in for a post of it, blueprints/posts: what a post "makes" while it is fed)
            if(block instanceof mindustry.world.blocks.defense.turrets.Turret && cat.byName.get(block.name) == null) cat.add(block.name, Res.Kind.unit, false, false);
        }
        for(Block block : list) read(cat, block);
        return cat;
    }

    static void read(Catalog cat, Block block){
        int n = cat.size();
        int[] cost = new int[n];
        for(ItemStack stack : block.requirements) cost[cat.id(stack.item.name)] += Math.round(stack.amount * Vars.state.rules.buildCostMultiplier);
        float powerUse = block.consPower != null && !block.consPower.buffered ? block.consPower.usage * 60f : 0f;

        if(block instanceof UnitFactory factory){
            for(UnitFactory.UnitPlan plan : factory.plans){
                float seconds = plan.time / 60f;
                float[] in = new float[n];
                for(ItemStack stack : plan.requirements) in[cat.id(stack.item.name)] += stack.amount / seconds;
                int made = unit(cat, plan.unit);
                cat.recipes.add(new Recipe(block.name + ":" + plan.unit.name, block.name, block.size, Recipe.Ground.none, cost, block.buildTime, powerUse, 0f,
                    in, new float[n], new float[n], -1, 0f, 1f, made, -1, seconds));
            }
            return;
        }
        if(block instanceof Reconstructor rec){
            float seconds = rec.constructTime / 60f;
            for(UnitType[] pair : rec.upgrades){
                int from = unit(cat, pair[0]), to = unit(cat, pair[1]);
                float[] in = new float[n];
                inputs(cat, block, in, seconds);
                cat.recipes.add(new Recipe(block.name + ":" + pair[1].name, block.name, block.size, Recipe.Ground.none, cost, block.buildTime, powerUse, 0f,
                    in, new float[n], new float[n], -1, 0f, 1f, to, from, seconds));
            }
            return;
        }

        float[] in = new float[n], out = new float[n], perFactor = new float[n];
        Recipe.Ground ground = Recipe.Ground.none;
        float powerOut = 0f;
        int boostRes = -1;
        float boostUse = 0f, boostMultiplier = 1f;

        if(block instanceof BeamDrill drill){
            ground = Recipe.Ground.wallOre;
            for(Item item : Vars.content.items()) if(item.hardness <= drill.tier && (drill.blockedItems == null || !drill.blockedItems.contains(item))) perFactor[cat.id(item.name)] = 60f / drill.getDrillTime(item);
            inputs(cat, block, in, 0f);
            ConsumeLiquidBase boost = boost(block);
            if(boost instanceof ConsumeLiquid liquid){
                boostRes = cat.id(liquid.liquid.name);
                boostUse = liquid.amount * 60f;
                boostMultiplier = drill.optionalBoostIntensity;
            }
        }else if(block instanceof Drill drill){
            ground = Recipe.Ground.floorOre;
            for(Item item : Vars.content.items()) if(item.hardness <= drill.tier && (drill.blockedItems == null || !drill.blockedItems.contains(item)) && item != drill.blockedItem) perFactor[cat.id(item.name)] = 60f / drill.getDrillTime(item);
            inputs(cat, block, in, 0f);
            ConsumeLiquidBase boost = boost(block);
            if(boost instanceof ConsumeLiquid liquid){
                boostRes = cat.id(liquid.liquid.name);
                boostUse = liquid.amount * 60f;
                boostMultiplier = drill.liquidBoostIntensity;
            }
        }else if(block instanceof Pump pump){
            //by the tile of liquid floor under it (a floor's multiplier counts, as in the game)
            ground = Recipe.Ground.liquidFloor;
            for(Liquid liquid : Vars.content.liquids()) perFactor[cat.id(liquid.name)] = pump.pumpAmount * 60f;
            inputs(cat, block, in, 0f);
        }else if(block instanceof WallCrafter crusher){
            ground = Recipe.Ground.sandWall;
            perFactor[cat.id(crusher.output.name)] = 60f / crusher.drillTime;
            inputs(cat, block, in, 0f);
        }else if(block instanceof ThermalGenerator gen){
            //on Erekir these need the whole vent: what is listed is per vent tile, nine of them
            ground = Recipe.Ground.vent;
            powerOut = gen.powerProduction * 9f * 60f;
            if(gen.outputLiquid != null) out[cat.id(gen.outputLiquid.liquid.name)] = gen.outputLiquid.amount * 9f * 60f;
        }else if(block instanceof HeatCrafter hc && hc.heatRequirement > 0f){
            //it works only with heaters against it: it comes in modules (designs with their heaters), not alone
            return;
        }else if(block instanceof GenericCrafter crafter){
            if(crafter instanceof AttributeCrafter ac && ac.attribute == mindustry.world.meta.Attribute.steam) ground = Recipe.Ground.vent;
            float seconds = crafter.craftTime / 60f;
            inputs(cat, block, in, seconds);
            if(crafter.outputItems != null) for(ItemStack stack : crafter.outputItems) out[cat.id(stack.item.name)] += stack.amount / seconds;
            if(crafter.outputLiquids != null) for(LiquidStack stack : crafter.outputLiquids) out[cat.id(stack.liquid.name)] += stack.amount * 60f;
        }else if(block instanceof ConsumeGenerator gen){
            inputs(cat, block, in, gen.itemDuration / 60f);
            powerOut = gen.powerProduction * 60f;
            if(gen.outputLiquid != null) out[cat.id(gen.outputLiquid.liquid.name)] = gen.outputLiquid.amount * 60f;
        }else{
            return;
        }
        cat.recipes.add(new Recipe(block.name, block.name, block.size, ground, cost, block.buildTime, powerUse, powerOut,
            in, out, perFactor, boostRes, boostUse, boostMultiplier, -1, -1, 0f));
    }

    /**
     * Modules as recipes: what the lab measured at their ports, their power, what they cost. Only those all of whose blocks
     * may be built (and none banned), and whose ports are things the catalog knows.
     */
    public static void modules(Catalog cat, List<Design> modules, Collection<String> allowed){
        for(Design m : modules){
            boolean ok = true;
            for(mindustry.game.Schematic.Stile t : m.parts(0)) if(!allowed.contains(t.block.name) || Vars.state.rules.isBanned(t.block)) ok = false;
            if(!ok) continue;
            int n = cat.size();
            float[] in = new float[n], out = new float[n];
            for(Design.Port p : m.ports){
                Res r = cat.byName.get(p.what);
                if(r == null){ ok = false; break; }
                (p.in ? in : out)[r.id] += p.rate;
            }
            if(!ok) continue;
            int[] cost = new int[n];
            //(an iterator of its own: the design is shared by plans weighed at once on several threads)
            for(var e : new arc.struct.ObjectIntMap.Entries<>(m.cost)) cost[cat.id(e.key)] += Math.round(e.value * Vars.state.rules.buildCostMultiplier);
            //a design that makes units: the strongest it makes (the others are steps on the way), at the rate measured
            int unitOut = -1;
            float unitSeconds = 0f;
            mindustry.type.UnitType strongest = null;
            for(var e : new arc.struct.ObjectFloatMap.Entries<>(m.units)){
                mindustry.type.UnitType type = Vars.content.unit(e.key);
                //a turret post (blueprints/posts): it "makes" a stand-in named after its turret while it is fed, so that a
                //post built and fed is an aim like a unit
                if(type == null && e.value > 0f && Vars.content.block(e.key) instanceof mindustry.world.blocks.defense.turrets.Turret){
                    Res r = cat.byName.get(e.key);
                    unitOut = r != null ? r.id : cat.add(e.key, Res.Kind.unit, false, false).id;
                    unitSeconds = 1f / e.value;
                    break;
                }
                if(type == null || e.value <= 0f) continue;
                if(strongest == null || type.health > strongest.health){
                    strongest = type;
                    unitOut = unit(cat, type);
                    unitSeconds = 1f / e.value;
                }
            }
            if(unitOut >= 0 && (in.length != cat.size() || out.length != cat.size())){
                in = java.util.Arrays.copyOf(in, cat.size());
                out = java.util.Arrays.copyOf(out, cat.size());
                n = cat.size();
                cost = java.util.Arrays.copyOf(cost, n);
            }
            //(a power plant's own machines' use is taken off what it makes)
            float use = Math.max(0f, m.power - m.powerMade), made = Math.max(0f, m.powerMade - m.power);
            Recipe r = new Recipe("module:" + m.id, "module:" + m.id, Math.max(m.width(0), m.height(0)), Recipe.Ground.none, cost, m.buildTicks, use, made,
                in, out, new float[n], -1, 0f, 1f, unitOut, -1, unitSeconds);
            r.module = m;
            cat.recipes.add(r);
        }
    }

    /** What a block takes that it must have, per second. Items are per craft, so they are spread over the craft's time. */
    static void inputs(Catalog cat, Block block, float[] in, float seconds){
        for(Consume consume : block.consumers){
            if(consume.optional || consume.booster) continue;
            if(consume instanceof ConsumeItems items && seconds > 0f){
                for(ItemStack stack : items.items) in[cat.id(stack.item.name)] += stack.amount / seconds;
            }else if(consume instanceof ConsumeLiquid liquid){
                in[cat.id(liquid.liquid.name)] += liquid.amount * 60f;
            }else if(consume instanceof ConsumeLiquids liquids){
                for(LiquidStack stack : liquids.liquids) in[cat.id(stack.liquid.name)] += stack.amount * 60f;
            }
        }
    }

    static ConsumeLiquidBase boost(Block block){
        for(Consume consume : block.consumers) if(consume.booster && consume instanceof ConsumeLiquidBase liquid) return liquid;
        return null;
    }

    static int unit(Catalog cat, UnitType type){
        Res r = cat.byName.get(type.name);
        int id = r != null ? r.id : cat.add(type.name, Res.Kind.unit, false, false).id;
        cat.unitSpeed.put(id, type.speed * 60f / Vars.tilesize);
        return id;
    }
}
