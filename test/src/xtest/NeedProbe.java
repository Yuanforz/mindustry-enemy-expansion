package xtest;

import mindustry.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.units.*;
import mindustry.world.consumers.*;

/**
 * What blocks need to be built and to run, as the game has them: requirements, ammunition, liquids (and whether only
 * a boost), power, heat; for unit assemblers, each plan's units and payloads. For working out late-game aims.
 *
 * Usage: NeedProbe data-dir block...
 */
public class NeedProbe{
    public static void main(String[] args){
        Boot.boot(args[0]);
        for(int a = 1; a < args.length; a++){
            Block b = Vars.content.block(args[a]);
            if(b == null){
                System.out.println(args[a] + ": no such block");
                continue;
            }
            StringBuilder s = new StringBuilder(b.name + " (size " + b.size + "): built of");
            for(ItemStack st : b.requirements) s.append(" ").append(st.item.name).append("=").append(st.amount);
            if(b instanceof ItemTurret it){
                s.append("; ammo");
                for(var e : it.ammoTypes) s.append(" ").append(e.key.name);
            }
            if(b instanceof Turret t){
                s.append(String.format("; range %.0f tiles", t.range / Vars.tilesize));
                if(t.heatRequirement > 0f) s.append(String.format("; heat %.0f (up to %.1fx)", t.heatRequirement, t.maxHeatEfficiency));
                if(t.coolant != null) s.append("; coolant ").append(t.coolant.getClass().getSimpleName());
            }
            for(Consume c : b.consumers){
                if(c instanceof ConsumeLiquid cl) s.append(String.format("; liquid %s %.2f/s%s", cl.liquid.name, cl.amount * 60f, c.optional ? " (optional)" : c.booster ? " (boost)" : ""));
                else if(c instanceof ConsumeLiquids cls) for(LiquidStack ls : cls.liquids) s.append(String.format("; liquid %s %.2f/s", ls.liquid.name, ls.amount * 60f));
                else if(c instanceof ConsumePower cp) s.append(String.format("; power %.0f/s", cp.usage * 60f));
                else if(c instanceof ConsumeItems ci) for(ItemStack st : ci.items) s.append("; item ").append(st.item.name).append("=").append(st.amount);
                else if(c instanceof ConsumeCoolant cc) s.append(String.format("; coolant %.2f/s%s", cc.amount * 60f, c.optional ? " (optional)" : ""));
                else s.append("; ").append(c.getClass().getSimpleName());
            }
            if(b instanceof UnitAssembler ua){
                for(var plan : ua.plans){
                    s.append("\n    plan ").append(plan.unit.name).append(String.format(" in %.0f s:", plan.time / 60f));
                    for(var p : plan.requirements) s.append(" ").append(p.item.name).append("x").append(p.amount);
                }
                if(ua.consumers.length > 0) s.append("\n    (areaSize ").append(ua.areaSize).append(")");
            }
            if(b instanceof Reconstructor rc){
                for(var up : rc.upgrades) s.append("\n    ").append(up[0].name).append(" -> ").append(up[1].name);
                s.append(String.format(" in %.0f s", rc.constructTime / 60f));
            }
            System.out.println(s);
        }
        System.exit(0);
    }
}
