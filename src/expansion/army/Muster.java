package expansion.army;

import arc.struct.*;
import mindustry.*;
import mindustry.game.*;
import mindustry.entities.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.units.*;

import java.util.*;

/**
 * What a side should make next to fight with, from the game as it stands: every kind of unit some block of the tech makes,
 * those that can get to the other side's base (or, if none can, those that hold one's own), the strongest for a place
 * under the unit cap first (the cap, fifteen a core, and not what is spent, is what an army runs into: fifteen tanks of
 * the second tier fight like sixteen times fifteen of the first), and as many of its makers as refill the cap in a few
 * minutes. Nothing here is about a map: it is the same for any side on any map.
 */
public final class Muster{
    /** One kind to make: its name, how many of its makers, and how strong a unit of it is (damage a second times health). */
    public static final class Aim{
        public final UnitType type;
        public final int makers;
        public final float strength;
        public final boolean reaches;

        Aim(UnitType type, int makers, float strength, boolean reaches){
            this.type = type;
            this.makers = makers;
            this.strength = strength;
            this.reaches = reaches;
        }

        @Override
        public String toString(){
            return type.name + ":" + makers;
        }
    }

    /** How many minutes the cap should take to fill, at most, by the makers asked for. */
    public static float refill = Float.parseFloat(System.getProperty("army.refill", "3"));
    public static int maxMakers = Integer.getInteger("army.makers", 4);

    /**
     * The kinds to make, best first: of what these blocks make (fabricators and refabricators), what reaches the foe's cores
     * from ours (all, if none does), by strength for a place under the cap.
     */
    public static List<Aim> aims(Team team, Team foe, Collection<String> blocks){
        Map<UnitType, Float> seconds = new LinkedHashMap<>();
        for(String name : blocks){
            Block b = Vars.content.block(name);
            if(b instanceof UnitFactory f){
                for(var plan : f.plans) seconds.merge(plan.unit, plan.time / 60f, Math::min);
            }else if(b instanceof Reconstructor r){
                for(UnitType[] up : r.upgrades) seconds.merge(up[1], r.constructTime / 60f, Math::min);
            }
        }
        int cap = Math.max(1, Units.getCap(team));
        int have = 0;
        for(Unit u : team.data().units) if(!u.spawnedByCore && u.type.canAttack) have++;
        int room = Math.max(cap / 3, cap - have);
        List<Aim> out = new ArrayList<>();
        for(var e : seconds.entrySet()){
            UnitType type = e.getKey();
            if(!type.canAttack || type.dpsEstimate <= 0f) continue;
            float strength = type.dpsEstimate * type.health * (1f + type.armor / 20f);
            //makers: enough to fill the room under the cap in the refill time
            int makers = Math.max(1, Math.min(maxMakers, (int)Math.ceil(room * e.getValue() / (refill * 60f))));
            out.add(new Aim(type, makers, strength, Reach.toFoe(type, team, foe)));
        }
        boolean any = out.stream().anyMatch(a -> a.reaches);
        if(any) out.removeIf(a -> !a.reaches);
        out.sort((a, b) -> Float.compare(b.strength, a.strength));
        return out;
    }
}
