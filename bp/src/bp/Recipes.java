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

    static Set<Item> ores;
    static Set<Liquid> floorLiquids;

    /** The items some ground gives (an ore on the floor or in a wall, a floor's own drop): what a drill may dig. */
    static Set<Item> ores(){
        if(ores == null){
            ores = new HashSet<>();
            for(Block b : Vars.content.blocks()) if(b instanceof mindustry.world.blocks.environment.OverlayFloor || b instanceof mindustry.world.blocks.environment.Floor || b instanceof mindustry.world.blocks.environment.StaticWall){
                if(b.itemDrop != null) ores.add(b.itemDrop);
            }
        }
        return ores;
    }

    /** The liquids some floor gives a pump. */
    static Set<Liquid> floorLiquids(){
        if(floorLiquids == null){
            floorLiquids = new LinkedHashSet<>();
            for(Block b : Vars.content.blocks()) if(b instanceof mindustry.world.blocks.environment.Floor f && f.liquidDrop != null && f.liquidDrop.isOnPlanet(Planets.erekir)) floorLiquids.add(f.liquidDrop);
        }
        return floorLiquids;
    }

    /** What payloads are made on Erekir: the units of its yards and refabricators, the blocks of its constructors. */
    static List<Recipe> payloadsMade(){
        List<Recipe> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for(Block b : Vars.content.blocks()){
            if(!b.isOnPlanet(Planets.erekir)) continue;
            if(!(b instanceof UnitFactory || b instanceof Reconstructor || b instanceof mindustry.world.blocks.payloads.Constructor || b instanceof UnitAssembler)) continue;
            List<Recipe> rs = new ArrayList<>();
            try{
                read(b, rs);
            }catch(Throwable ignored){
            }
            for(Recipe r : rs) for(String k : r.out.keySet()) if((Vars.content.unit(k) != null || Vars.content.block(k) != null) && seen.add(k)) out.add(r);
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
                if(!item.isOnPlanet(Planets.erekir) || !ores().contains(item)) continue;
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
                if(!item.isOnPlanet(Planets.erekir) || !ores().contains(item)) continue;
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
            //(one way for each liquid a floor gives: a pump draws from the floor it stands on, pumpAmount a tick a tile, times
            //the floor's multiplier, the place's yield)
            for(Liquid l : floorLiquids()){
                Recipe r = new Recipe(b.name + ":" + l.name, b, Recipe.Kind.pump, Recipe.Ground.liquidFloor, l.name);
                consumers(b, r, 0f, false);
                r.perYield.put(l.name, perSecond(p.pumpAmount));
                out.add(r);
            }
            return;
        }
        if(b instanceof mindustry.world.blocks.payloads.PayloadDeconstructor dc){
            //a deconstructor: each payload it may take (a block a constructor makes, a unit that rides payloads), taken
            //apart in its build time over the deconstructor's speed, its whole cost handed back (PayloadDeconstructor)
            for(Recipe made : payloadsMade()){
                String what = made.out.keySet().iterator().next();
                Block bl = Vars.content.block(what);
                UnitType u = Vars.content.unit(what);
                float size = bl != null ? bl.size : u != null ? u.hitSize / Vars.tilesize : 99f;
                ItemStack[] req = bl != null ? bl.requirements : u != null ? u.getTotalRequirements() : null;
                float time = bl != null ? bl.buildTime : u != null ? u.getBuildTime() : 0f;
                if(req == null || req.length == 0 || size > dc.maxPayloadSize || time <= 0f) continue;
                float seconds = time / dc.deconstructSpeed / 60f;
                Recipe r = new Recipe(b.name + ":" + what, b, Recipe.Kind.crafter, Recipe.Ground.none, null);
                consumers(b, r, 0f, false);
                r.in.put(what, 1f / seconds);
                for(ItemStack st : req) r.out.merge(st.item.name, st.amount / seconds, Float::sum);
                out.add(r);
            }
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
            //(one that gives heat too, the neoplasia reactor: what its liquid output fills up it blows up with, and so
            //must be led away all)
            if(b instanceof HeaterGenerator hg) r.heatOut = hg.heatOutput;
            r.out.put("~" + b.name, 1f);
            out.add(r);
            return;
        }
        if(b instanceof mindustry.world.blocks.payloads.Constructor c){
            //a constructor: a block of those it may make, from its cost, in its build time over the constructor's build
            //speed (BlockProducer.updateTile), handed out as a payload by its name
            for(Block made : Vars.content.blocks()){
                if(!made.isOnPlanet(Planets.erekir) || !c.canProduce(made) || made.requirements.length == 0) continue;
                float seconds = made.buildTime / c.buildSpeed / 60f;
                Recipe r = new Recipe(b.name + ":" + made.name, b, Recipe.Kind.crafter, Recipe.Ground.none, null);
                consumers(b, r, 0f, false);
                for(ItemStack st : made.requirements) r.in.merge(st.item.name, st.amount / seconds, Float::sum);
                r.out.put(made.name, 1f / seconds);
                out.add(r);
            }
            return;
        }
        if(b instanceof UnitAssembler a){
            //an assembler: each plan, from the payloads it asks (units and blocks by name) in its time; a plan past the
            //first wants modules of each tier up to its own standing against its area (UnitAssembler.checkTier), counted
            //as parts of it, their power with it
            Block module = null;
            for(Block m : Vars.content.blocks()) if(m instanceof UnitAssemblerModule && m.isOnPlanet(Planets.erekir)) module = m;
            for(int i = 0; i < a.plans.size; i++){
                var plan = a.plans.get(i);
                if(i > 0 && module == null) continue;
                Recipe r = new Recipe(b.name + ":" + plan.unit.name, b, Recipe.Kind.units, Recipe.Ground.none, null);
                consumers(b, r, 0f, false);
                float seconds = plan.time / 60f;
                for(var st : plan.requirements) r.in.merge(st.item.name, st.amount / seconds, Float::sum);
                if(i > 0){
                    r.parts.put(module, i);
                    for(Consume cm : module.consumers) if(cm instanceof ConsumePower cp) r.fixedPowerUse += perSecond(cp.usage) * i;
                }
                r.out.put(plan.unit.name, 1f / seconds);
                out.add(r);
            }
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
        if(b instanceof Turret t){
            turret(t, out);
            return;
        }
        if(b instanceof VariableReactor v){
            //a reactor run on heat (the flux reactor): power and its fuel both at heat over maxHeat, at most all of it
            //(VariableReactor.updateEfficiencyMultiplier); short of fuel for its heat, it grows unstable and blows up
            Recipe r = new Recipe(b.name, b, Recipe.Kind.generator, Recipe.Ground.none, null);
            consumers(b, r, 0f, false);
            r.powerOut = perSecond(v.powerProduction);
            r.heatNeed = v.maxHeat;
            r.out.put("~" + b.name, 1f);
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
            return;
        }
        //anything else that takes what it needs while it stands (power, a liquid: a radar, a repair tower, a mass driver):
        //working at full is having it, a second, as its consumers ask; what it gives is itself at work, by its name
        Recipe r = new Recipe(b.name, b, Recipe.Kind.consumer, Recipe.Ground.none, null);
        consumers(b, r, 0f, false);
        if(r.in.isEmpty() && r.powerUse <= 0f) return;
        r.out.put("~" + b.name, 1f);
        out.add(r);
    }

    /**
     * A turret kept firing, one way for each ammunition it takes and with or without its coolant: what it takes a second
     * firing all the time. Its shots a second are 60 / reload times the ammunition's reload multiplier, and with coolant
     * more by the coolant's amount a tick times its heat capacity times the turret's coolant multiplier
     * (ReloadTurret.updateCooling); each shot takes ammoPerShot of ammunition (per bullet unless it takes it once a
     * shot), and an item gives the bullet's ammoMultiplier of it (ItemTurret.handleItem). A liquid turret takes its
     * liquid a tick while it fires; power and heat as it needs them. What it gives is a firing turret, by the way's name.
     */
    static void turret(Turret t, List<Recipe> out){
        Map<String, mindustry.entities.bullet.BulletType> ammo = new LinkedHashMap<>();
        if(t instanceof ItemTurret it) for(var e : it.ammoTypes.entries()) ammo.put(e.key.name, e.value);
        else if(t instanceof LiquidTurret lt) for(var e : lt.ammoTypes.entries()) ammo.put(e.key.name, e.value);
        else if(t instanceof ContinuousLiquidTurret ct) for(var e : ct.ammoTypes.entries()) ammo.put(e.key.name, e.value);
        else ammo.put("", null);
        boolean coolable = t.coolant != null;
        for(var e : ammo.entrySet()) for(boolean cooled : coolable ? new boolean[]{false, true} : new boolean[]{false}){
            String name = t.name + (e.getKey().isEmpty() ? "" : ":" + e.getKey()) + (cooled ? "+coolant" : "");
            Recipe r = new Recipe(name, t, Recipe.Kind.turret, Recipe.Ground.none, null);
            //(what it takes besides its ammunition and coolant: power, a liquid it needs, as the titan's hydrogen)
            for(Consume c : t.consumers){
                if(c == t.coolant || c.optional) continue;
                if(c instanceof ConsumeLiquid cl) r.in.merge(cl.liquid.name, perSecond(cl.amount), Float::sum);
                else if(c instanceof ConsumePower cp) r.powerUse += perSecond(cp.usage);
            }
            mindustry.entities.bullet.BulletType bullet = e.getValue();
            float rm = bullet == null ? 1f : bullet.reloadMultiplier;
            float shots = 60f / t.reload * rm;
            if(cooled){
                Liquid coolant = t.coolant instanceof ConsumeLiquid cl ? cl.liquid : Liquids.water;
                shots *= 1f + t.coolant.amount * coolant.heatCapacity * t.coolantMultiplier;
                r.in.merge(coolant.name, perSecond(t.coolant.amount), Float::sum);
            }
            if(t instanceof ItemTurret && bullet != null){
                float perShot = t.ammoPerShot * (t.consumeAmmoOnce ? 1 : t.shoot.shots);
                r.in.merge(e.getKey(), shots * perShot / Math.max(1f, bullet.ammoMultiplier), Float::sum);
            }else if(t instanceof ContinuousLiquidTurret ct){
                r.in.merge(e.getKey(), perSecond(ct.liquidConsumed), Float::sum);
            }else if(t instanceof LiquidTurret && bullet != null){
                r.in.merge(e.getKey(), shots * t.ammoPerShot / Math.max(1f, bullet.ammoMultiplier), Float::sum);
            }
            if(t.heatRequirement > 0f){
                r.heatNeed = t.heatRequirement;
                r.maxEfficiency = t.maxHeatEfficiency;
            }
            r.out.put(name, 1f);
            out.add(r);
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
        for(var e : heater.out.entrySet()) c.fixedOut.merge(e.getKey(), e.getValue() * k, Float::sum);
        c.powerUse = r.powerUse;
        c.powerOut = r.powerOut * share;
        c.fixedPowerUse = heater.powerUse * k;
        c.fixedPowerOut = heater.powerOut * k;
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
