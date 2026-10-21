package bp;

import mindustry.*;
import mindustry.content.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;
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
        if(b instanceof Turret t && (t.heatRequirement > 0f || b.consPower != null)){
            //a turret kept ready: what it takes to fire at full (its power, its heat); what it "makes" is itself working
            Recipe r = new Recipe(b.name, b, Recipe.Kind.turret, Recipe.Ground.none, null);
            consumers(b, r, 0f, false);
            if(t.heatRequirement > 0f){
                r.heatNeed = t.heatRequirement;
                r.maxEfficiency = t.maxHeatEfficiency;
            }
            r.out.put(b.name, 1f);
            out.add(r);
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

    /**
     * A machine that needs heat, with the heaters that give it: against its sides, or gathered into heat redirectors that
     * stand against it. The game's rule (Building.calculateHeat): a block gets from each heat block whose face points at
     * it that one's heat over its size, times the tiles in contact; a redirector (HeatConductor, not split) gathers all
     * that points at it the same way and gives it on, whole, out of its face. So along a side of S tiles, S / h heaters of
     * size h touch with their whole face and one more with the tiles left over (a corner each: a 5x5 takes eight 2x2
     * heaters whole and four by one tile); a redirector of size c against a side takes c of its tiles and gathers from
     * its other three sides as many heaters as touch them. With this many redirectors (none to one a side), the fewest
     * heaters that give the need, the best placed first; with too few for the need, it works at the share it gets
     * (efficiency is heat over need, Turret, HeatCrafter). One way for each number of redirectors that does better than
     * fewer; empty if no heater of this kind fits.
     */
    public static List<Recipe> heated(Recipe r, Recipe heater, List<mindustry.world.Block> conductors){
        List<Recipe> out = new ArrayList<>();
        if(r.heatNeed <= 0f || heater.heatOut <= 0f) return out;
        int s = r.size, h = heater.size;
        //(whole heaters for the whole need: each gives all its heat through a face that touches whole)
        int k = (int)Math.ceil(r.heatNeed / Math.min(heater.heatOut, heater.heatOut) - 1e-6);
        if(s >= h && k <= 4 * (s / h)) out.add(compose(r, heater, k, null, 0, 1f));
        for(mindustry.world.Block cond : conductors){
            int c = cond.size;
            if(c > s || c < h) continue;
            //redirectors against its sides, as many as a side holds and the rest of the side for heaters; each gathers
            //through its three other faces, each taking a heater or another redirector (a chain: redirectors pass all on)
            int perSide = s / c, best = Integer.MAX_VALUE;
            for(int r0 = 1; r0 <= 4 * perSide; r0++){
                int direct = 0, left = r0;
                for(int side = 0; side < 4; side++){
                    int here = Math.min(perSide, left);
                    left -= here;
                    direct += (s - here * c) / h;
                }
                int slots = direct + 3 * r0 * (c / h);
                int r1 = slots >= k ? 0 : (int)Math.ceil((k - slots) / (3.0 * (c / h) - 1.0));
                int reds = r0 + r1;
                //(the fewest redirectors for these heaters: one more against the sides helps only while it does)
                if(reds >= best) continue;
                best = reds;
                out.add(compose(r, heater, k, cond, reds, 1f));
            }
        }
        return out;
    }

    /**
     * What heaters of size h give touching a stretch of so many tiles: only whole ones, whose whole face touches (a heater
     * touching by part of its face gives that part of its heat: a heater wasted in part, where a redirector gathers whole
     * ones instead; ways that work at less than full are left out the same way, the user's rule for layouts).
     */
    static void slots(List<Float> give, int tiles, int h, float q){
        if(tiles < h) return;
        for(int i = 0; i < tiles / h; i++) give.add(q);
    }

    static Recipe compose(Recipe r, Recipe heater, int k, mindustry.world.Block cond, int reds, float share){
        Recipe c = new Recipe(r.name + "+" + k + "x" + heater.name + (reds > 0 ? "+" + reds + "x" + cond.name : ""), r.block, r.kind, r.ground, r.dug);
        c.parts.put(heater.block, k);
        if(reds > 0) c.parts.put(cond, reds);
        for(var e : r.in.entrySet()) c.in.put(e.getKey(), e.getValue() * (Vars.content.liquid(e.getKey()) != null ? 1f : share));
        for(var e : r.out.entrySet()) c.out.put(e.getKey(), e.getValue() * share);
        //(its heaters burn as long as they stand, busy or not)
        for(var e : heater.in.entrySet()) c.fixedIn.merge(e.getKey(), e.getValue() * k, Float::sum);
        for(var e : heater.out.entrySet()) c.out.merge(e.getKey(), e.getValue() * k, Float::sum);
        c.powerUse = r.powerUse;
        c.fixedPowerUse = heater.powerUse * k;
        c.stopsWhenFull.addAll(r.stopsWhenFull);
        return c;
    }

    /** The heat redirectors there are (conductors that pass heat on whole, not split). */
    public static List<mindustry.world.Block> conductors(){
        List<mindustry.world.Block> out = new ArrayList<>();
        for(mindustry.world.Block b : Vars.content.blocks()) if(b instanceof HeatConductor hc && !hc.splitHeat && b.isOnPlanet(Planets.erekir)) out.add(b);
        return out;
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
