package bp;

import mindustry.*;
import mindustry.content.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.heat.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.sandbox.*;
import mindustry.world.blocks.units.*;
import mindustry.world.consumers.*;
import mindustry.world.meta.*;

import java.util.*;

/**
 * Every way of working of Erekir's production blocks, read off the game's objects as their code uses them (source
 * v160.7): a crafter's consumption is per craft and its time is craftTime ticks; liquids and power are per tick; a beam
 * drill digs every facing ore tile once per drillTime; a drill digs its ore tiles once per (drillTime + 50 x hardness);
 * a wall crafter gives its output once per drillTime per unit of the wall's attribute; a vent turbine makes its power
 * and water per unit of the vents' attribute sum; a pump gives pumpAmount a tick per tile, times the floor's
 * multiplier; a fabricator makes its unit in plan.time ticks; a refabricator turns one unit into the next in
 * constructTime ticks.
 */
public final class Recipes{
    private Recipes(){
    }

    /** Every recipe of the blocks on Erekir (and the sandbox's sources and sinks). */
    public static List<Recipe> all(){
        List<Recipe> out = new ArrayList<>();
        for(Block b : Vars.content.blocks()){
            boolean sandbox = b instanceof ItemSource || b instanceof LiquidSource || b instanceof PowerSource || b instanceof HeatProducer && b.name.equals("heat-source");
            if(!b.isOnPlanet(Planets.erekir) && !sandbox) continue;
            if(b.buildVisibility == BuildVisibility.sandboxOnly && !sandbox) continue;
            try{
                read(b, out);
            }catch(Throwable t){
                System.out.println("  (could not read " + b.name + ": " + t + ")");
            }
        }
        return out;
    }

    static float perSecond(float perTick){
        return perTick * 60f;
    }

    /** What a block's consumers take a second, at full speed: items per craft time, liquids and power per tick. */
    static void consumers(Block b, Recipe r, float craftTicks, boolean withBooster){
        for(Consume c : b.consumers){
            if(c.booster && !withBooster) continue;
            if(c.optional && !c.booster) continue;
            if(c instanceof ConsumeItems ci && craftTicks > 0f) for(ItemStack s : ci.items) r.in.merge(s.item.name, s.amount * 60f / craftTicks, Float::sum);
            else if(c instanceof ConsumeLiquid cl) r.in.merge(cl.liquid.name, perSecond(cl.amount), Float::sum);
            else if(c instanceof ConsumeLiquids cl) for(LiquidStack s : cl.liquids) r.in.merge(s.liquid.name, perSecond(s.amount), Float::sum);
            else if(c instanceof ConsumePower cp) r.powerUse += perSecond(cp.usage);
        }
    }

    static boolean hasBooster(Block b){
        for(Consume c : b.consumers) if(c.booster) return true;
        return false;
    }

    static void read(Block b, List<Recipe> out){
        if(b instanceof ItemSource){
            out.add(new Recipe("item-source", b, Recipe.Kind.source, Recipe.Ground.none, null));
            return;
        }
        if(b instanceof LiquidSource){
            out.add(new Recipe("liquid-source", b, Recipe.Kind.source, Recipe.Ground.none, null));
            return;
        }
        if(b instanceof PowerSource ps){
            Recipe r = new Recipe("power-source", b, Recipe.Kind.source, Recipe.Ground.none, null);
            r.powerOut = perSecond(ps.powerProduction);
            out.add(r);
            return;
        }
        if(b instanceof HeatProducer hp){
            //a heater: a crafter whose output is heat (its own consumers, its own craft time for items)
            Recipe r = new Recipe(b.name, b, Recipe.Kind.heater, Recipe.Ground.none, null);
            consumers(b, r, hp.craftTime, false);
            r.heatOut = hp.heatOutput;
            if(hp.outputItems != null) for(ItemStack s : hp.outputItems) r.out.merge(s.item.name, s.amount * 60f / hp.craftTime, Float::sum);
            out.add(r);
            return;
        }
        if(b instanceof GenericCrafter g){
            Recipe.Ground ground = Recipe.Ground.none;
            if(g instanceof AttributeCrafter a && a.attribute == Attribute.steam) ground = Recipe.Ground.vent;
            Recipe r = new Recipe(b.name, b, Recipe.Kind.crafter, ground, null);
            consumers(b, r, g.craftTime, false);
            if(g.outputItems != null) for(ItemStack s : g.outputItems) r.out.merge(s.item.name, s.amount * 60f / g.craftTime, Float::sum);
            if(g.outputLiquids != null) for(LiquidStack s : g.outputLiquids) r.out.merge(s.liquid.name, perSecond(s.amount), Float::sum);
            if(g instanceof AttributeCrafter a){
                r.attrBase = a.baseEfficiency;
                r.attrScale = a.boostScale;
                r.attrMax = a.maxBoost;
                //(crafting faster takes items faster; liquids and power go per tick)
                r.inScales = true;
            }
            if(g instanceof HeatCrafter h){
                r.heatNeed = h.heatRequirement;
                r.maxEfficiency = h.maxEfficiency;
                r.overheatScale = h.overheatScale;
            }
            //what stops it when full: an item output always; a liquid output when it has only one, or dumps none
            if(g.outputItems != null) for(ItemStack s : g.outputItems) r.stopsWhenFull.add(s.item.name);
            if(g.outputLiquids != null && !g.ignoreLiquidFullness && (g.outputLiquids.length == 1 || !g.dumpExtraLiquid)) for(LiquidStack s : g.outputLiquids) r.stopsWhenFull.add(s.liquid.name);
            out.add(r);
            return;
        }
        if(b instanceof BeamDrill d){
            for(Item item : Vars.content.items()){
                if(item.hardness > d.tier || d.blockedItems != null && d.blockedItems.contains(item)) continue;
                if(!item.isOnPlanet(Planets.erekir)) continue;
                for(boolean boost : hasBooster(b) ? new boolean[]{false, true} : new boolean[]{false}){
                    Recipe r = new Recipe(b.name + ":" + item.name + (boost ? "+boost" : ""), b, Recipe.Kind.drill, Recipe.Ground.wallOre, item.name);
                    consumers(b, r, 0f, boost);
                    r.boosted = boost;
                    r.boost = boost ? d.optionalBoostIntensity : 1f;
                    r.perYield.put(item.name, 60f / d.getDrillTime(item) * r.boost);
                    out.add(r);
                }
            }
            return;
        }
        if(b instanceof Drill d){
            for(Item item : Vars.content.items()){
                if(item.hardness > d.tier || item == d.blockedItem || d.blockedItems != null && d.blockedItems.contains(item)) continue;
                if(!item.isOnPlanet(Planets.erekir)) continue;
                for(boolean boost : hasBooster(b) ? new boolean[]{false, true} : new boolean[]{false}){
                    Recipe r = new Recipe(b.name + ":" + item.name + (boost ? "+boost" : ""), b, Recipe.Kind.drill, Recipe.Ground.floorOre, item.name);
                    consumers(b, r, 0f, boost);
                    r.boosted = boost;
                    r.boost = boost ? d.liquidBoostIntensity : 1f;
                    r.perYield.put(item.name, 60f / d.getDrillTime(item) * r.boost);
                    out.add(r);
                }
            }
            return;
        }
        if(b instanceof WallCrafter w){
            Recipe r = new Recipe(b.name, b, Recipe.Kind.drill, Recipe.Ground.sandWall, w.output.name);
            consumers(b, r, 0f, false);
            r.perYield.put(w.output.name, 60f / w.drillTime);
            out.add(r);
            return;
        }
        if(b instanceof Pump p){
            Recipe r = new Recipe(b.name, b, Recipe.Kind.pump, Recipe.Ground.liquidFloor, null);
            consumers(b, r, 0f, false);
            r.perYield.put("liquid", perSecond(p.pumpAmount));
            out.add(r);
            return;
        }
        if(b instanceof ThermalGenerator t){
            Recipe r = new Recipe(b.name, b, Recipe.Kind.generator, t.attribute == Attribute.steam ? Recipe.Ground.vent : Recipe.Ground.none, null);
            consumers(b, r, 0f, false);
            r.powerPerYield = perSecond(t.powerProduction);
            if(t.outputLiquid != null) r.perYield.put(t.outputLiquid.liquid.name, perSecond(t.outputLiquid.amount));
            out.add(r);
            return;
        }
        if(b instanceof ConsumeGenerator cg){
            Recipe r = new Recipe(b.name, b, Recipe.Kind.generator, Recipe.Ground.none, null);
            consumers(b, r, cg.itemDuration, false);
            r.powerOut = perSecond(cg.powerProduction);
            if(cg.outputLiquid != null) r.out.put(cg.outputLiquid.liquid.name, perSecond(cg.outputLiquid.amount));
            out.add(r);
            return;
        }
        if(b instanceof UnitFactory f){
            for(var plan : f.plans){
                Recipe r = new Recipe(b.name + ":" + plan.unit.name, b, Recipe.Kind.units, Recipe.Ground.none, null);
                for(ItemStack s : plan.requirements) r.in.merge(s.item.name, s.amount * 60f / plan.time, Float::sum);
                for(Consume c : b.consumers) if(c instanceof ConsumePower cp) r.powerUse += perSecond(cp.usage);
                r.out.put(plan.unit.name, 60f / plan.time);
                out.add(r);
            }
            return;
        }
        if(b instanceof Reconstructor re){
            for(UnitType[] up : re.upgrades){
                Recipe r = new Recipe(b.name + ":" + up[1].name, b, Recipe.Kind.upgrade, Recipe.Ground.none, null);
                consumers(b, r, re.constructTime, false);
                r.in.merge(up[0].name, 60f / re.constructTime, Float::sum);
                r.out.put(up[1].name, 60f / re.constructTime);
                out.add(r);
            }
        }
    }

    public static void main(String[] args){
        Game.boot(args.length > 0 ? args[0] : "build/bp-data");
        List<Recipe> all = all();
        for(Recipe r : all) System.out.println(r);
        System.out.println(all.size() + " recipes");
        //a few at a place, as checks: a plasma bore facing two beryllium tiles, a turbine on a whole vent, a condenser on one
        for(Recipe r : all){
            if(r.name.equals("plasma-bore:beryllium")) System.out.println("  plasma-bore facing 2 beryllium: " + r.at(2));
            if(r.name.equals("turbine-condenser")) System.out.println("  turbine-condenser on 9 vent tiles: " + r.at(9));
            if(r.name.equals("vent-condenser")) System.out.println("  vent-condenser on 9 vent tiles: " + r.at(9));
            if(r.name.equals("impact-drill:tungsten")) System.out.println("  impact-drill on 16 tungsten tiles: " + r.at(16));
        }
        System.exit(0);
    }
}
