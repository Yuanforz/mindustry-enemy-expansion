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
        /** Seconds a maker takes for one, and how many there is room for under the cap. */
        public final float seconds;
        public final int room;

        Aim(UnitType type, int makers, float strength, boolean reaches, float seconds, int room){
            this.type = type;
            this.makers = makers;
            this.strength = strength;
            this.reaches = reaches;
            this.seconds = seconds;
            this.room = room;
        }

        /**
         * The strength it has out by a time to come (seconds from now), its first unit at first: as many as its makers
         * turn out by then, no more than there is room for, times what one is worth.
         */
        public float by(float horizon, float first){
            if(first >= horizon) return 0f;
            float made = Math.min(room, 1f + makers * (horizon - first) / Math.max(1f, seconds));
            return made * strength;
        }

        /** The same kind with another number of makers. */
        public Aim withMakers(int n){
            return new Aim(type, n, strength, reaches, seconds, room);
        }

        @Override
        public String toString(){
            return type.name + ":" + makers;
        }
    }

    /** How many minutes the cap should take to fill, at most, by the makers asked for. */
    public static float refill = Float.parseFloat(System.getProperty("army.refill", "3"));
    public static int maxMakers = Integer.getInteger("army.makers", 4);
    /** How many makers of a kind a stage adds at most. */
    public static int growth = Integer.getInteger("army.growth", 2);
    /** How far ahead the strength an aim brings is weighed, in seconds. */
    public static float horizon = Float.parseFloat(System.getProperty("army.horizon", "480"));

    /** How many of the side's standing blocks make this kind (fabricators, or refabricators that turn it out). */
    static int makersOf(Team team, UnitType type){
        int n = 0;
        for(Building b : team.data().buildings){
            if(b.block instanceof UnitFactory f && f.plans.contains(p -> p.unit == type)) n++;
            else if(b.block instanceof Reconstructor r && r.upgrades.contains(u -> u[1] == type)) n++;
        }
        return n;
    }

    /**
     * The kinds to make, best first: of what these blocks make (fabricators and refabricators), what reaches the foe's cores
     * from ours (all, if none does), by strength for a place under the cap.
     */
    public static List<Aim> aims(Team team, Team foe, Collection<String> blocks){
        return aims(team, foe, blocks, false);
    }

    /** As above; every kind, whether it gets to the foe's cores or not, if asked (what cannot only holds home). */
    public static List<Aim> aims(Team team, Team foe, Collection<String> blocks, boolean everyKind){
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
            //makers: enough to fill the room under the cap in the refill time, but no more than two more than stand already
            //(a plan of four fabricators and all that feeds them from a bare base is paid for slowly, and the first unit with
            //it: two, then more once those run, as a player grows a base)
            int standing = makersOf(team, type);
            int needed = Math.max(1, Math.min(maxMakers, (int)Math.ceil(room * e.getValue() / (refill * 60f))));
            int makers = Math.max(1, Math.min(needed, standing + growth));
            out.add(new Aim(type, makers, strength, Reach.toFoe(type, team, foe), e.getValue(), room));
        }
        boolean any = out.stream().anyMatch(a -> a.reaches);
        if(any && !everyKind) out.removeIf(a -> !a.reaches);
        out.sort((a, b) -> Float.compare(b.strength, a.strength));
        return out;
    }
}
