package expansion;

import arc.*;
import arc.struct.*;
import arc.util.*;

/**
 * Everything about how the enemy plays that the player can change, in one place: what each setting
 * is called, what it starts at, and for numbers how far it goes. The settings screen is built from
 * this list, and the rest of the mod asks here.
 */
public class Options{
    public static final Seq<Option> all = new Seq<>();
    /** The headings the settings are shown under, in order. */
    public static final String[] groups = {"rules", "economy", "military", "ground", "pace"};

    //the rules it plays by
    public static final Flag
    enabled = flag("rules", "enabled", true).strong(true),
    /**
     * One switch for the enemy at its strongest: while it is on, every setting that makes the enemy
     * stronger or weaker counts as set to its strongest, whatever it is set to, and goes back to what
     * the player chose when it is turned off. The rules that keep the game fair are left alone: its
     * resources stay real, it stays within the player's research, and it builds the way and at the
     * pace the player does, unless the player says otherwise.
     * Nor does it touch what only costs frame time.
     */
    strongest = flag("rules", "strongest", false),
    realStock = flag("rules", "real-stock", true),
    techLimit = flag("rules", "tech-limit", true),
    /**
     * Whether its builders work at the speed the player's do. The game has the enemy's build slower
     * on this planet, and other mods may have them build faster: with this on it is neither.
     */
    sameSpeed = flag("rules", "same-speed", true),
    /**
     * Whether it puts blocks up by itself, without a builder going there. Off as it comes: everything it
     * builds, and everything it puts back after the player has destroyed it, takes one of its builders
     * flying to the place and building it at the pace a player's unit builds. With this on a thing
     * destroyed is back the moment it can be paid for, and there is nothing the player can do about it
     * but cut off what pays for it. It is the player's to choose, like the other rules here.
     */
    hands = flag("rules", "direct", false),
    debug = flag("rules", "debug", false);
    public static final Num
    grace = num("rules", "grace", 2, 0, 30, 1, "minutes").strong(0);

    //its economy
    public static final Flag
    mines = flag("economy", "mines", true).strong(true),
    plants = flag("economy", "plants", true).strong(true),
    baseLines = flag("economy", "use-base-lines", true).strong(true),
    haul = flag("economy", "haul", true).strong(true),
    handMine = flag("economy", "hand-mine", true).strong(true),
    openWalls = flag("economy", "open-walls", true).strong(true),
    /**
     * Whether it makes what it has better: adds to its unit yards what turns their units into the next
     * kind, leads to its drills and turrets the liquids that make them faster, and puts better walls
     * in the place of lesser ones.
     */
    refit = flag("economy", "refit", true).strong(true),
    /**
     * Whether it looks ahead before choosing what to set about: it plays the next few minutes forward
     * from what it measures (income, the other side's army arriving and what its turrets kill, what the
     * target takes to bring down, how fast its yards work) once for each choice, and takes the one after
     * which it would be ready to strike soonest without losing its home.
     */
    foresight = flag("economy", "foresight", true).strong(true),
    /**
     * Whether it adds sources by the recipes of what it has: what its yards, factories and turrets take a second
     * against what its sources bring in, instead of by how full its cores are.
     */
    ratios = flag("economy", "ratios", true).strong(true);
    public static final Num
    minesPerCore = num("economy", "mines-per-core", 0, 0, 8, 1, "cap").strong(0),
    rowLength = num("economy", "row-length", 6, 1, 6, 1, "").strong(6),
    plantsPerCore = num("economy", "plants-per-core", 0, 0, 6, 1, "cap").strong(0),
    //more on one grid is not stronger past a point, only slower all round: these two go to what was found to work best.
    //(holding it to what people's bases draw, about what they generate, was tried and lost the first campaign sector
    //more slowly and more often: 33 to 56 minutes and one loss in four, against 20 minutes four times in four)
    overload = num("economy", "overload", 120, 100, 160, 5, "percent").strong(120),
    starved = num("economy", "starved", 300, 100, 500, 25, "percent").strong(300),
    powerChain = num("economy", "power-chain", 40, 4, 60, 2, "").strong(60);

    //its army and its defences
    public static final Flag
    yards = flag("military", "yards", true).strong(true),
    /** Whether the army layer (expansion.army) leads its units over the game's squad AI: see docs/PLAN.md §22.6. */
    army = flag("military", "army", true).strong(true),
    cores = flag("military", "cores", true).strong(true);
    public static final Num
    yardsPerCore = num("military", "yards-per-core", 3, 0, 8, 1, "").strong(8),
    unitCap = num("military", "unit-cap", 100, 25, 400, 25, "percent").strong(400),
    unitMost = num("military", "unit-most", 40, 10, 200, 10, "").strong(200),
    maxOutposts = num("military", "max-outposts", 60, 0, 200, 5, "").strong(200),
    //more turrets asked of every stretch than there is room for has it try the same crowded places over and over: on one
    //map it ended up with less than half the turrets that way. it goes to what was found to work best
    turretsPerStretch = num("military", "turrets-per-stretch", 3, 1, 6, 1, "").strong(3);

    //where it builds
    public static final Flag
    creep = flag("ground", "creep", true).strong(true);
    public static final Num
    frontier = num("ground", "frontier", 50, 30, 65, 5, "percent").strong(65),
    hold = num("ground", "hold", 40, 15, 80, 5, "tiles").strong(80),
    //at its boldest it builds whatever the player's army is doing next to it
    nerve = num("ground", "nerve", 10, 0, 40, 2, "tiles").strong(0),
    reach = num("ground", "reach", 110, 40, 250, 10, "tiles").strong(250),
    feedLimit = num("ground", "feed-limit", 90, 30, 200, 10, "tiles").strong(200);

    //how fast it goes about it
    public static final Num
    buildRate = num("pace", "build-rate", 40, 5, 300, 5, "rate").strong(300),
    planInterval = num("pace", "plan-interval", 3, 1, 60, 1, "seconds").strong(1),
    //how long it saves up for one thing is neither stronger nor weaker either way; and the time a round of
    //working-out may take is paid for in the player's frames, not in the enemy's strength
    patience = num("pace", "patience", 3, 1, 15, 1, "minutes"),
    timeLimit = num("pace", "time-limit", 25, 5, 100, 5, "milliseconds");

    public static abstract class Option{
        public final String group, name;

        Option(String group, String name){
            this.group = group;
            this.name = name;
            all.add(this);
        }

        /** What the setting is stored under. */
        public String key(){
            return "expansion-" + name;
        }
    }

    public static class Flag extends Option{
        public final boolean start;
        /** What it counts as while the enemy is set to its strongest, or null if that leaves it alone. */
        public @Nullable Boolean strong;

        Flag(String group, String name, boolean start){
            super(group, name);
            this.start = start;
        }

        Flag strong(boolean value){
            strong = value;
            return this;
        }

        /** What goes: the strongest it can be while that is asked for, and otherwise what the player set. */
        public boolean on(){
            if(strong != null && strongest.set()) return strong;
            return set();
        }

        /** What the player set it to. */
        public boolean set(){
            return Core.settings.getBool(key(), start);
        }
    }

    public static class Num extends Option{
        public final int start, least, most, step;
        /** What the number counts, for showing it. */
        public final String unit;
        /** What it counts as while the enemy is set to its strongest, or null if that leaves it alone. */
        public @Nullable Integer strong;

        Num(String group, String name, int start, int least, int most, int step, String unit){
            super(group, name);
            this.start = start;
            this.least = least;
            this.most = most;
            this.step = step;
            this.unit = unit;
        }

        Num strong(int value){
            strong = value;
            return this;
        }

        /** What goes: the strongest it can be while that is asked for, and otherwise what the player set. */
        public int get(){
            if(strong != null && strongest.set()) return strong;
            return set();
        }

        /** What the player set it to. */
        public int set(){
            return Core.settings.getInt(key(), start);
        }

        /** As a part of one, for a setting given in hundredths. */
        public float fraction(){
            return get() / 100f;
        }
    }

    private static Flag flag(String group, String name, boolean start){
        return new Flag(group, name, start);
    }

    private static Num num(String group, String name, int start, int least, int most, int step, String unit){
        return new Num(group, name, start, least, most, step, unit);
    }
}
