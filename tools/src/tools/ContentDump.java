package tools;

import arc.struct.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.content.TechTree.*;
import mindustry.ctype.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.heat.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.storage.*;
import mindustry.world.blocks.units.*;
import mindustry.world.consumers.*;

/** Prints what every block and unit of the Erekir tech tree is, costs, needs and makes, straight from the game. */
public class ContentDump{
    static String stacks(ItemStack[] s){
        if(s == null) return "-";
        StringBuilder b = new StringBuilder();
        for(ItemStack i : s) b.append(b.length() > 0 ? "+" : "").append(i.item.name).append(i.amount);
        return b.length() == 0 ? "-" : b.toString();
    }
    static String liquids(LiquidStack[] s){
        if(s == null) return "-";
        StringBuilder b = new StringBuilder();
        for(LiquidStack i : s) b.append(b.length() > 0 ? "+" : "").append(i.liquid.name).append(String.format("%.3f/t", i.amount));
        return b.length() == 0 ? "-" : b.toString();
    }

    public static void main(String[] args){
        Boot.boot(args[0]);
        Seq<TechNode> nodes = new Seq<>();
        Planets.erekir.techTree.each(nodes::add);
        System.out.println("# tech nodes: " + nodes.size);
        for(TechNode node : nodes){
            UnlockableContent c = node.content;
            StringBuilder b = new StringBuilder();
            b.append(c.getContentType()).append(' ').append(c.name).append(" | parent=").append(node.parent == null ? "-" : node.parent.content.name);
            b.append(" | research=").append(stacks(node.requirements));
            if(node.objectives.size > 0) b.append(" | objectives=").append(node.objectives.map(o -> o.getClass().getSimpleName() + ":" + o.display()));
            if(c instanceof Block k){
                b.append("\n    ").append(k.getClass().getSimpleName()).append(" size=").append(k.size).append(" hp=").append(k.health).append(" cost=").append(stacks(k.requirements))
                    .append(" buildTime=").append(String.format("%.0f", k.buildTime)).append(" rotate=").append(k.rotate).append(" itemCap=").append(k.itemCapacity).append(" liquidCap=").append(k.liquidCapacity)
                    .append(" hasPower=").append(k.hasPower).append(" outputsPower=").append(k.outputsPower).append(" consumesPower=").append(k.consumesPower)
                    .append(" group=").append(k.group).append(" solid=").append(k.solid).append(" envReq=").append(k.envRequired);
                b.append("\n    consumes:");
                for(Consume cons : k.consumers){
                    b.append(' ');
                    if(cons instanceof ConsumeItems ci) b.append("items[").append(stacks(ci.items)).append("]");
                    else if(cons instanceof ConsumeItemFilter) b.append(cons.getClass().getSimpleName());
                    else if(cons instanceof ConsumeLiquid cl) b.append("liquid[").append(cl.liquid.name).append(String.format(" %.4f/t", cl.amount)).append("]");
                    else if(cons instanceof ConsumeLiquids cl) b.append("liquids[").append(liquids(cl.liquids)).append("]");
                    else if(cons instanceof ConsumePower cp) b.append("power[").append(String.format("%.4f/t", cp.usage)).append(cp.buffered ? " buffered " + cp.capacity : "").append("]");
                    else b.append(cons.getClass().getSimpleName());
                    if(cons.optional) b.append("(optional)");
                    if(cons.booster) b.append("(booster)");
                }
                if(k instanceof GenericCrafter g){
                    b.append("\n    crafter: time=").append(g.craftTime).append(" outItems=").append(stacks(g.outputItems)).append(" outLiquids=").append(liquids(g.outputLiquids))
                        .append(" liquidDirs=").append(java.util.Arrays.toString(g.liquidOutputDirections)).append(" dumpExtraLiquid=").append(g.dumpExtraLiquid).append(" ignoreLiquidFullness=").append(g.ignoreLiquidFullness);
                    if(g instanceof HeatCrafter h) b.append(" heatReq=").append(h.heatRequirement).append(" overheatScale=").append(h.overheatScale).append(" maxEff=").append(h.maxEfficiency);
                    if(g instanceof HeatProducer h) b.append(" heatOut=").append(h.heatOutput);
                    if(g instanceof AttributeCrafter a) b.append(" attribute=").append(a.attribute).append(" base=").append(a.baseEfficiency).append(" boostScale=").append(a.boostScale).append(" minEff=").append(a.minEfficiency);
                }
                if(k instanceof BeamDrill d) b.append("\n    beamdrill: tier=").append(d.tier).append(" range=").append(d.range).append(" drillTime=").append(d.drillTime).append(" optionalBoost=").append(d.optionalBoostIntensity);
                if(k instanceof Drill d) b.append("\n    drill: tier=").append(d.tier).append(" drillTime=").append(d.drillTime).append(" class=").append(d.getClass().getSimpleName());
                if(k instanceof WallCrafter d) b.append("\n    wallcrafter: drillTime=").append(d.drillTime).append(" attribute=").append(d.attribute).append(" output=").append(d.output.name);
                if(k instanceof PowerGenerator p) b.append("\n    generator: production=").append(String.format("%.4f/t", p.powerProduction));
                if(k instanceof BeamNode n) b.append("\n    beamnode: range=").append(n.range);
                if(k instanceof Duct d) b.append("\n    duct: speed=").append(d.speed).append(" armored=").append(d.armored);
                if(k instanceof DuctBridge d) b.append("\n    ductbridge: range=").append(d.range).append(" speed=").append(d.speed);
                if(k instanceof DirectionalUnloader d) b.append("\n    unloader: speed=").append(d.speed).append(" allowCoreUnload=").append(d.allowCoreUnload);
                if(k instanceof CoreBlock cb) b.append("\n    core: unit=").append(cb.unitType.name).append(" capacity=").append(cb.itemCapacity).append(" unitCapModifier=").append(cb.unitCapModifier).append(" incinerateNonBuildable=").append(cb.incinerateNonBuildable);
                if(k instanceof Turret t){
                    b.append("\n    turret: ").append(t.getClass().getSimpleName()).append(" range=").append(t.range / 8f).append("t air=").append(t.targetAir).append(" ground=").append(t.targetGround).append(" reload=").append(t.reload)
                        .append(" heatReq=").append(t.heatRequirement).append(" maxAmmo=").append(t.maxAmmo).append(" coolant=").append(t.coolant != null);
                    if(t instanceof ItemTurret it){ b.append(" ammo="); for(var e : it.ammoTypes) b.append(e.key.name).append("(dmg ").append(e.value.damage).append(" splash ").append(e.value.splashDamage).append(") "); }
                    if(t instanceof ContinuousLiquidTurret it){ b.append(" liquidAmmo="); for(var e : it.ammoTypes) b.append(e.key.name).append("(dmg ").append(e.value.damage).append(") "); b.append(" use=").append(it.liquidConsumed); }
                }
                if(k instanceof UnitFactory f){ b.append("\n    factory:"); for(var p : f.plans) b.append(" ").append(p.unit.name).append("[").append(stacks(p.requirements)).append(" ").append(p.time).append("t]"); }
                if(k instanceof Reconstructor r){ b.append("\n    reconstructor: time=").append(r.constructTime).append(" upgrades="); for(var u : r.upgrades) b.append(u[0].name).append(">").append(u[1].name).append(" "); }
                if(k instanceof UnitAssembler a){ b.append("\n    assembler: areaSize=").append(a.areaSize).append(" droneType=").append(a.droneType.name).append(" drones=").append(a.dronesCreated); for(var p : a.plans){ b.append(" plan[").append(p.unit.name).append(" time=").append(p.time).append(" needs="); for(var r : p.requirements) b.append(r.item.name).append("x").append(r.amount).append(","); b.append("]"); } }
            }else if(c instanceof UnitType u){
                b.append("\n    unit hp=").append(u.health).append(" armor=").append(u.armor).append(" speed=").append(u.speed).append(" flying=").append(u.flying).append(" hitSize=").append(u.hitSize).append(" canBoost=").append(u.canBoost).append(" allowLegStep=").append(u.allowLegStep).append(" naval=").append(u.naval);
            }
            System.out.println(b);
        }
        
        for(Item i : Vars.content.items()) if(i.isOnPlanet(Planets.erekir)) System.out.println("item " + i.name + " hardness=" + i.hardness + " buildable=" + i.buildable + " cost=" + i.cost);
        System.exit(0);
    }
}
