package expansion;

import arc.util.*;
import mindustry.gen.*;
import mindustry.type.*;

/**
 * Something to be done, put on one of the two queues by what reads the world model, and taken up by what
 * carries it out: the builders for the one, the army for the other. A task says what kind of thing is to be
 * done, where (a zone of the {@link Atlas}, or a thing to go for), what it is thought to be worth and to
 * cost, and why it was asked for.
 */
public class Task{
    public enum Kind{
        //for the builders
        /** More ore taken up: drills on ore in the walls or the floor. */
        mine,
        /** Power: generators on vents, more for a grid that can't keep up. */
        power,
        /** A factory that makes something from what is mined. */
        factory,
        /** A unit yard, or what makes its units better. */
        yard,
        /** A turret, with its supply and water. */
        turret,
        /** Something that sees far, over ground nothing of its own can get to. */
        radar,
        /** Lines put right: led home a better way, led on where cut, taken up where they serve nothing. */
        rewire,
        /** Something standing that is short of what it takes, seen to. */
        feed,
        /** Something standing made better: added to, given what makes it faster, put in a better one's place. */
        upgrade,
        /** A building of its own that is hurt, mended by a builder's beam. */
        repair,
        /** What the map asks for. */
        objective,
        //for the army
        /** All it can spare against one thing of the other side's. */
        attack,
        /** A few quick units at what keeps the other side's turrets firing. */
        harass,
        /** Units to where its own things are being hit. */
        defend,
        /** A unit to look at ground no one of its side has seen. */
        scout;

        public boolean army(){
            return ordinal() >= attack.ordinal();
        }
    }

    public final Kind kind;
    /** The zone it is about, or -1. */
    public final int zone;
    /** The item it is about, if any. */
    public final @Nullable Item item;
    /** What it goes for, if a thing. */
    public @Nullable Teamc target;
    /** What it is thought to bring, and to cost, in the same terms as everything else on the queue. */
    public float value, cost;
    /** Why it was asked for. */
    public String why = "";
    /** When it was last asked for, by the side's clock. */
    public float asked;

    public Task(Kind kind, int zone, @Nullable Item item){
        this.kind = kind;
        this.zone = zone;
        this.item = item;
    }

    /** What tells two tasks about the same thing apart from others. */
    public long key(){
        return key(kind, zone, item);
    }

    public static long key(Kind kind, int zone, @Nullable Item item){
        return ((long)kind.ordinal() << 48) | ((long)(zone + 1) << 16) | (item == null ? 0 : item.id + 1);
    }

    @Override
    public String toString(){
        return kind + (zone >= 0 ? "@" + zone : "") + (item != null ? ":" + item.name : "") + " (" + Strings.fixed(value, 2) + (why.isEmpty() ? "" : ", " + why) + ")";
    }
}
