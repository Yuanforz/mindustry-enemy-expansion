package expansion;

import arc.struct.*;
import arc.util.*;
import expansion.Blueprint.*;

/**
 * One thing of the enemy's base taken as a whole: a row of drills, a factory, a unit yard, a group of
 * turrets, something that makes a liquid. It remembers which blueprint it was laid out from and how
 * that was turned, so that the same layout can be worked out again when the thing is to be added to,
 * given something that makes it faster, or done over.
 *
 * What state it is in is not remembered but seen: each time the enemy plans, every one of these is
 * looked at and put in the stage that what stands on the map says it is in. Only how long it has been
 * there is kept, so that a moment's trouble does not count as trouble.
 */
public class Asset{
    /** Where something of the enemy's is in its life. Each stage has something that is done about it. */
    public enum Stage{
        /** Laid out, and not all of it up yet. Nothing more is asked of it. */
        laid,
        /** Up and doing what it was built for. */
        running,
        /** Up, and standing still for want of something it is fed with. More of that is got to it. */
        starved,
        /** What it puts on its line no longer gets to a core. The line is led on again. */
        cut,
        /** Running, and there is a better way of doing what it does that could be had here. It is done over. */
        outdated,
        /** Its main block is gone and is not coming back. It is forgotten. */
        lost
    }

    public final Net.Kind kind;
    /** The name of the blueprint it was laid out from. */
    public final String blueprint;
    /** Where the main block of its first copy stands, and how the blueprint was turned to put it there. */
    public final int x, y, turns, copies;
    public final boolean mirrored;
    /** Where the note of it in the net is: for what puts items on a line, that is the first tile of the line. */
    public final int thingX, thingY;

    public Stage stage = Stage.laid;
    /** When it came to be in this stage, by the enemy's clock. */
    public float since;
    /** The liquids that make it faster which have been led to it: a bit for each. */
    public int boosted;
    /** The ammunition its turrets were put on since they were built, as the item's number, or -1 if they are on what they were built with. */
    public int armed = -1;
    /** When doing something about it may next be tried: what came to nothing is not tried again at once. Not kept over a save. */
    public float rest;

    public Asset(Net.Kind kind, String blueprint, int x, int y, int turns, boolean mirrored, int copies, int thingX, int thingY){
        this.kind = kind;
        this.blueprint = blueprint;
        this.x = x;
        this.y = y;
        this.turns = turns;
        this.mirrored = mirrored;
        this.copies = copies;
        this.thingX = thingX;
        this.thingY = thingY;
    }

    public Asset(Net.Kind kind, Layout layout, int thingX, int thingY){
        this(kind, layout.blueprint.name, layout.main().x, layout.main().y, layout.turns, layout.mirrored, layout.copies, thingX, thingY);
    }

    /** Puts it in a stage, noting the time if that is a change. */
    public void enter(Stage next, float clock){
        if(next == stage) return;
        stage = next;
        since = clock;
    }

    /** The layout as it stands, worked out afresh from the blueprint, or null if the blueprint is no longer there. */
    public @Nullable Layout layout(Seq<Blueprint> blueprints){
        Blueprint from = blueprints.find(b -> b.name.equals(blueprint));
        return from == null ? null : from.at(x, y, turns, mirrored, copies);
    }

    public String write(){
        return kind.code + ":" + blueprint + ":" + x + ":" + y + ":" + turns + ":" + (mirrored ? 1 : 0) + ":" + copies + ":" + thingX + ":" + thingY + ":" + boosted + ":" + armed;
    }

    public static @Nullable Asset read(String text){
        String[] parts = text.split(":");
        if(parts.length < 10 || parts[0].length() != 1) return null;
        Net.Kind kind = Structs.find(Net.Kind.values(), k -> k.code == parts[0].charAt(0));
        if(kind == null) return null;
        Asset out = new Asset(kind, parts[1], Strings.parseInt(parts[2], 0), Strings.parseInt(parts[3], 0), Strings.parseInt(parts[4], 0), parts[5].equals("1"),
            Strings.parseInt(parts[6], 1), Strings.parseInt(parts[7], 0), Strings.parseInt(parts[8], 0));
        out.boosted = Strings.parseInt(parts[9], 0);
        if(parts.length > 10) out.armed = Strings.parseInt(parts[10], -1);
        return out;
    }
}
