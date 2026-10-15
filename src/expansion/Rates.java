package expansion;

import arc.struct.*;
import arc.util.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.production.*;
import mindustry.world.consumers.*;

/** How fast blocks make and use things, in items a second, worked out from the game's own numbers. */
public class Rates{

    /** What one beam of a wall drill brings in when it ends on ore. */
    public static float beam(BeamDrill drill, Item item){
        return 60f / drill.getDrillTime(item);
    }

    /** What a drill that stands on ore brings in, with so many of the tiles under it holding the ore. */
    public static float drill(Drill drill, Item item, int tiles){
        return tiles * 60f / drill.getDrillTime(item);
    }

    /** What a crusher brings in standing against wall worth the given amount in all. */
    public static float crusher(WallCrafter crusher, float worth){
        return 60f / crusher.drillTime * worth;
    }

    /** What a factory makes of an item when it runs flat out. */
    public static float makes(Block block, Item item){
        if(block instanceof GenericCrafter crafter && crafter.outputItems != null){
            for(ItemStack stack : crafter.outputItems){
                if(stack.item == item) return stack.amount * 60f / crafter.craftTime;
            }
        }
        return 0f;
    }

    /** What a factory uses of an item when it runs flat out. */
    public static float uses(Block block, Item item){
        if(!(block instanceof GenericCrafter crafter)) return 0f;
        for(Consume consume : block.consumers){
            if(consume instanceof ConsumeItems items && !consume.optional){
                for(ItemStack stack : items.items){
                    if(stack.item == item) return stack.amount * 60f / crafter.craftTime;
                }
            }
        }
        return 0f;
    }

    /** The part of the time a turret is taken to be firing, in working out what it needs laid on. */
    static final float turretDuty = 0.15f;

    /** A liquid a block takes in or gives out. */
    public static class Flow{
        public final Liquid liquid;
        /** Units a second. */
        public final float rate;
        /** For what is given out: the side it leaves on, counted from the way the block faces, or -1 for any side. */
        public final int side;

        Flow(Liquid liquid, float rate, int side){
            this.liquid = liquid;
            this.rate = rate;
            this.side = side;
        }
    }

    /** The liquids a block has to be given to work. What only makes it faster is left out. */
    public static Seq<Flow> liquidNeeds(Block block){
        Seq<Flow> out = new Seq<>();
        for(Consume consume : block.consumers){
            if(consume.optional || consume.booster) continue;
            if(consume instanceof ConsumeLiquid one) out.add(new Flow(one.liquid, one.amount * 60f, -1));
            if(consume instanceof ConsumeLiquids many){
                for(LiquidStack stack : many.liquids) out.add(new Flow(stack.liquid, stack.amount * 60f, -1));
            }
        }
        //a turret that fires a liquid: the first of the ones it takes, which is the one to be had soonest.
        //it only uses it while it fires, and what stands by it in a container sees it through a fight
        if(block instanceof mindustry.world.blocks.defense.turrets.ContinuousLiquidTurret turret){
            Liquid first = null;
            for(Liquid liquid : turret.ammoTypes.keys()){
                if(first == null || liquid.id < first.id) first = liquid;
            }
            if(first != null) out.add(new Flow(first, turret.liquidConsumed * 60f * turretDuty, -1));
        }
        return out;
    }

    /**
     * The liquids a block can do without but is faster with: hydrogen for a wall drill, ozone for a drill
     * that stands on its ore, water for a turret. A turret only takes it while it is loading, so what
     * has to be laid on for it is a part of what it takes then.
     */
    public static Seq<Flow> liquidBoosts(Block block){
        Seq<Flow> out = new Seq<>();
        float duty = block instanceof mindustry.world.blocks.defense.turrets.BaseTurret ? turretDuty : 1f;
        for(Consume consume : block.consumers){
            if((consume.optional || consume.booster) && consume instanceof ConsumeLiquid one) out.add(new Flow(one.liquid, one.amount * 60f * duty, -1));
        }
        return out;
    }

    /** The liquids a block makes, when it runs flat out on ground that gives it all it can use. */
    public static Seq<Flow> liquidGives(Block block){
        Seq<Flow> out = new Seq<>();
        if(block instanceof GenericCrafter crafter && crafter.outputLiquids != null){
            for(int i = 0; i < crafter.outputLiquids.length; i++){
                int side = crafter.liquidOutputDirections.length > i ? crafter.liquidOutputDirections[i] : -1;
                out.add(new Flow(crafter.outputLiquids[i].liquid, crafter.outputLiquids[i].amount * 60f, side));
            }
        }else if(block instanceof mindustry.world.blocks.power.ThermalGenerator thermal && thermal.outputLiquid != null){
            //given for each tile of vent under it
            out.add(new Flow(thermal.outputLiquid.liquid, thermal.outputLiquid.amount * 60f * block.size * block.size, -1));
        }
        return out;
    }

    /** The items a block has to be given to work, and how many of each for one run of it. */
    public static @Nullable ItemStack[] inputs(Block block){
        for(Consume consume : block.consumers){
            if(consume instanceof ConsumeItems items && !consume.optional) return items.items;
        }
        return null;
    }
}
