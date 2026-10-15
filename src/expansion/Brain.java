package expansion;

import arc.*;
import arc.func.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import expansion.Blueprint.*;
import expansion.ExpansionAI.*;
import expansion.Planner.*;
import expansion.Territory.*;
import mindustry.ai.types.*;
import mindustry.content.*;
import mindustry.ctype.*;
import mindustry.entities.*;
import mindustry.game.*;
import mindustry.game.Teams.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.ConstructBlock.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.storage.*;
import mindustry.world.blocks.storage.CoreBlock.*;
import mindustry.world.blocks.units.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.defense.*;

import static mindustry.Vars.*;

/**
 * Runs the enemy's side as an opponent that plays by the player's rules.
 *
 * Its cores hold what was really delivered to them and nothing is built for free. With that it does
 * what a player does: opens mines and lays ducts from them to its core, builds furnaces, unit yards
 * and turrets and branches their supplies off those lines, and claims further cores. What to build
 * is decided here; how each piece is put together is the {@link Planner}'s business, and the pieces
 * themselves are blueprints.
 *
 * Building goes through the game's own machinery. Every block of a plan is added to the team's build
 * queue, the same queue destroyed buildings go into, and is put up as a construction site that takes
 * what the block costs out of the core. Its builder units do that, one for each core: each flies to
 * where the block goes and builds it from within its reach, at the pace a player's unit builds. So
 * what the player destroys costs the enemy time to put back, and is not put back at all while the
 * player's army or turrets keep its builders away. The player may switch that off and have it put
 * blocks up by itself as fast as its core can pay, without a builder going anywhere.
 */
public class Brain{
    static final String tag = "enemy-expansion";
    static final float thinkPeriod = 30f, scriptPeriod = 60f * 10f;
    /** How long a place that can't be built on is waited for, and then left alone. */
    static final float avoidTime = 60f * 60f * 4f;
    /** How long a block that can't be put where it was laid out is waited for when the enemy builds by itself. Anything standing there in passing is gone by then. */
    static final float blockedTime = 60f * 5f;
    /** How many blocks a builder is given at a time. */
    static final int stretch = 40;
    /** How long after an attempt at something came to nothing it is tried again. */
    static final float retryPeriod = 60f * 60f;
    private static final float territoryPeriod = 60f * 180f;
    /** How long a place is left alone after something could not be worked out there. */
    static final float shunTime = 60f * 60f * 15f;
    /** How many places the laying of lines may look at in working out one thing to build. Keeps a look-ahead from holding the game up. */
    static final int searchAllowance = 90000;
    /** The longest any working-out took, in milliseconds, and what it was. */
    public float slowestPlan, slowestThink;
    /** How long the mod took in the last update of the game, in milliseconds. */
    public float lastThink;
    public String slowestWhat = "";


    public final Stock stock = new Stock();
    public Seq<Blueprint> blueprints = new Seq<>();
    /**
     * Whether the enemy may build something. In the campaign it is held to what the player has
     * researched, as the game holds enemy builders: it never fields what the player has no answer to yet.
     */
    public Boolf<UnlockableContent> unlocked = UnlockableContent::unlockedNowHost;

    public final Territory territory = new Territory();
    public final Survey survey = new Survey();
    public final Terrain terrain = new Terrain();
    /** The world model: the map in zones, and what is in each and goes on there. */
    public final Atlas atlas = new Atlas();
    /** The two queues of what is to be done, and the account of how what was done turned out. */
    public final Tasks tasks = new Tasks();
    public final Ledger ledger = new Ledger();
    public final Ground ground = new Ground();
    public final Fitter fitter = new Fitter(ground, survey, territory);
    public final Lines lines = new Lines(ground), pipes = new Lines(ground);
    public final Power power = new Power(ground, terrain);
    public final Net net = new Net(ground);
    public final Planner planner = new Planner(this);
    /** What the enemy is about at the moment: decides in what order it goes about things. */
    public final Director director = new Director();
    /** Everything it has laid out, each as a whole, with the stage of its life it is in. */
    public final Seq<Asset> assets = new Seq<>();

    private final Seq<Order> orders = new Seq<>();
    /** Where the enemy has laid out blocks of its own. */
    private final IntSeq built = new IntSeq();
    /** The same tiles as a set: asked of every block in the build queue, again and again. */
    private final IntSet laid = new IntSet();
    /** Walls of its own that are to come down to make way for a line. */
    private final IntSeq removals = new IntSeq();
    private final IntFloatMap avoid = new IntFloatMap(), stuck = new IntFloatMap(), away = new IntFloatMap();
    private final IntSet corridor = new IntSet();
    /**
     * The parts of the map too near the player's buildings for a builder to work in, as squares of a
     * few tiles. Builders leave when the player is close, so what was planned there would never go up.
     */
    private short[] danger = {};
    private int dangerWidth;
    static final int dangerCell = 6, dangerFar = 40;
    private IntSet scripted = new IntSet();
    //by what was tried, such as "mine:beryllium" or "yard": when it may be tried again, how often it has come to nothing, and what was last said about it
    private final ObjectFloatMap<String> retry = new ObjectFloatMap<>();
    private final ObjectIntMap<String> failures = new ObjectIntMap<>(), timeouts = new ObjectIntMap<>();
    private final OrderedMap<String, String> notes = new OrderedMap<>();
    private final ObjectMap<Item, Seq<int[]>> spots = new ObjectMap<>();
    private final ObjectMap<Item, IntSet> seenLines = new ObjectMap<>();

    private float clock, thinkTimer, scriptTimer, nextPlan, nextTerritory, savingSince = -1f, aimClock, quiet, reach;
    private @Nullable Block aim;
    private int started, territoryCores = -1, coreTurn;
    private boolean loaded, awake, adopted, saving;

    public Brain(){
        ground.rule = (x, y) -> {
            int pos = Point2.pack(x, y);
            //only ever on its own side of the map, never where the map's own scripts do things, and not where its builders would be driven off
            //for the first source of something it can't do without, any ground will do that its builders can work on
            //where it has been told to keep to its home ground, that holds for this too
            return ((needy && territory.creep) || territory.ours(x, y)) && !scripted.contains(pos) && avoid.get(pos, 0f) <= clock
                && (ground.laying || shunned.get(pos, 0f) <= clock) && workable(x, y);
        };
        lines.homeward = territory::fromHome;
        //what units can't walk over stays off the ways the enemy's army takes; and core zones are for cores
        ground.solidRule = (x, y) -> !corridor.contains(Point2.pack(x, y)) && !world.tile(x, y).floor().allowCorePlacement && !byCore(x, y);
    }

    /**
     * The side the mod plays where that is not the enemy's. Only a test sets this, to have it play a
     * map from the player's side, under the player's rules, against the game's own enemy: the best
     * measure there is of whether it plays as a player would.
     */
    public static @Nullable Team side;

    /**
     * Whether a tile is right by one of its cores. That ground is kept for the lines that run into the
     * core: nothing solid goes there. A base that builds its turrets, yards and nodes up against its
     * core leaves no way in for the line from its next mine; the bases people build keep it clear.
     */
    private static boolean byCore(int x, int y){
        for(CoreBuild core : team().cores()){
            int reach = core.block.size / 2 + 3;
            if(Math.abs(core.tileX() - x) <= reach && Math.abs(core.tileY() - y) <= reach) return true;
        }
        return false;
    }

    public static Team team(){
        return side != null ? side : state.rules.waveTeam;
    }

    /** The side it is up against. */
    public static Team foe(){
        return team() == state.rules.defaultTeam ? state.rules.waveTeam : state.rules.defaultTeam;
    }

    /** Items it is to get hold of besides what its own building calls for: what a test that has it play the campaign needs for research. */
    public final Seq<Item> wanted = new Seq<>();

    /**
     * Only where this game runs the enemy, and only when there is an enemy base to grow from.
     * Erekir only: that is where the blueprints are from.
     */
    public boolean active(){
        return loaded && Options.enabled.on() && state.isGame() && !mindustry.Vars.net.client() && !state.isEditor()
            && !state.rules.pvp && (side != null || team() != state.rules.defaultTeam) && team().cores().size > 0 && erekir();
    }

    /** Whether this is a map of Erekir's: one that says so, or, as some custom maps do not, one where the enemy's core is one of Erekir's. */
    private static boolean erekir(){
        if(state.getPlanet() == Planets.erekir) return true;
        Block core = team().core() == null ? null : team().core().block;
        return core == Blocks.coreBastion || core == Blocks.coreCitadel || core == Blocks.coreAcropolis;
    }

    public int count(Net.Kind kind){
        return net.count(kind, null);
    }

    /** Why each kind of thing is or is not being laid out at the moment. For looking into how the enemy behaves. */
    public String status(){
        StringBuilder out = new StringBuilder();
        for(String key : notes.orderedKeys()){
            out.append(key).append(": ").append(notes.get(key));
            if(retry.get(key, 0f) > clock) out.append(" (again in ").append((int)((retry.get(key, 0f) - clock) / 60f)).append("s)");
            out.append("\n");
        }
        TeamData data = team().data();
        out.append("tasks: ").append(tasks.describe()).append("\n  repairs begun ").append(repairs).append(", loops now ").append(ledger.loops(clock).size).append("\n");
        out.append("posture: ").append(director.posture).append(" for ").append((int)((clock - director.since) / 60f)).append("s, ").append(director.idle).append(" rounds with nothing to do, lost lately ").append(Strings.fixed(director.losses, 1)).append("\n");
        ObjectIntMap<Asset.Stage> stages = new ObjectIntMap<>();
        for(Asset asset : assets) stages.increment(asset.stage);
        out.append("assets: ").append(stages).append("\n");
        return out.append("units ").append(data.unitCount).append(", step ").append(started).append(", to build ").append(data.plans.size).toString();
    }

    /** How much of an item is spoken for by what it is saving up for at the moment. */
    public int savingFor(Item item){
        Block wanted = aim();
        if(wanted == null) return 0;
        for(ItemStack stack : cost(wanted)) if(stack.item == item) return stack.amount;
        return 0;
    }

    /** The time by its own clock, which stands still while the game is paused. */
    public float now(){
        return clock;
    }

    /** What the enemy is waiting to afford, if anything. */
    public @Nullable Block aim(){
        return clock - aimClock < 60f * 5f ? aim : null;
    }

    //region saving

    /** A world was loaded. */
    public void load(){
        clear();
        loaded = true;
        ground.team = team();

        //what the cores hold now is the enemy's stock from here on; a save from before says otherwise below
        stock.settle();
        String saved = state.rules.tags.get(tag);
        if(saved != null) read(saved);
    }

    /** The world is gone. */
    public void clear(){
        loaded = awake = adopted = false;
        stock.reset();
        orders.clear();
        built.clear();
        laid.clear();
        removals.clear();
        net.clear();
        ground.clear();
        ground.ore.clear();
        ground.lane.clear();
        reachNow.clear();
        reachClock = -1f;
        terrain.clear();
        survey.clear();
        nextTerritory = 0f;
        territoryCores = -1;
        retry.clear();
        failures.clear();
        timeouts.clear();
        notes.clear();
        avoid.clear();
        shunned.clear();
        stuck.clear();
        away.clear();
        danger = new short[0];
        corridor.clear();
        scripted.clear();
        aim = null;
        owed = null;
        lateRounds = 0;
        needy = false;
        deadZones.clear();
        health.clear();
        assets.clear();
        retiring.clear();
        siegeSpots.clear();
        director.reset();
        rested.clear();
        fallen.clear();
        cellLost.clear();
        cellLostAt.clear();
        firsts.clear();
        inherited = looked = ventsKnown = false;
        ground.vent.clear();
        urgent.clear();
        guardCost = ItemStack.empty;
        guardUntil = 0f;
        needClock = -1f;
        sightedUntil = 0f;
        everSighted = false;
        sightedAt.clear();
        lastFired.clear();
        repairs = 0;
        atlas.reset();
        tasks.reset();
        ledger.reset();
        tileTask.clear();
        connectFails.clear();
        building.clear();
        buildingAt.clear();
        buildingMine.clear();
        keptOut.clear();
        coverFirst.clear();
        armyPeakDps = armyPeakHealth = 0f;
        homeShort = false;
        turretsHold = true;
        minedOut.clear();
        lastLossAt = -1e9f;
        revealAt = -1;
        revealed.clear();
        field.clear();
        setOut = 0;
        strike = false;
        stageFor = null;
        siegeLoss = -1f;
        siegeFrom = 0;
        siegeStand = null;
        aimAt = null;
        frontAt = -1;
        contactTime = 0f;
        lastContact = -1e9f;
        incomeDry = 0;
        tidiedAt = -1e9f;
        tidyAfter = 0f;
        approaches.clear();
        approachClock = -1e9f;
        roomClock = -1f;
        roomAbout = null;
        asked.clear();
        retiringLines.clear();
        nextSweep = 0f;
        scout = -1;
        lookout = null;
        field.clear();
        setOut = repulsed = 0;
        marchTarget = null;
        staplesNow = null;
        sparedNow.clear();
        clock = thinkTimer = nextPlan = staffTimer = nextClaim = 0f;
        gamePace = -1f;
        aimClock = Float.NEGATIVE_INFINITY;
        scriptTimer = scriptPeriod;
        savingSince = -1f;
        started = 0;
    }

    /**
     * The save is about to be written. What is in the enemy's cores and what is waiting to be built the
     * game saves by itself; this adds what the enemy knows about its own base.
     */
    public void save(){
        if(!loaded || (team().cores().size == 0 && !state.rules.tags.containsKey(tag))) return;
        state.rules.tags.put(tag, "2;" + started + ";" + join(built) + ";" + net.write() + ";" + stock.write() + ";" + join(removals) + ";" + assets.toString(",", Asset::write));
    }

    private void read(String text){
        String[] parts = text.split(";", -1);
        if(parts.length >= 5 && parts[0].equals("2")){
            started = Strings.parseInt(parts[1], 0);
            split(parts[2], built);
            relaid();
            net.read(parts[3]);
            stock.read(parts[4]);
            if(parts.length > 5) split(parts[5], removals);
            if(parts.length > 6){
                for(String entry : parts[6].split(",")){
                    Asset asset = Asset.read(entry);
                    if(asset != null) assets.add(asset);
                }
            }
            return;
        }
        //a save from when the enemy's stock was kept on the side: "ledger;built;outposts;started;…".
        //what it had then goes into its cores now
        if(parts.length < 4) return;
        for(Item item : content.items()) stock.set(item, 0);
        for(String entry : parts[0].split(",")){
            String[] fields = entry.split(":");
            Item item = fields.length == 4 ? content.item(fields[0]) : null;
            if(item != null) stock.set(item, Strings.parseInt(fields[1], 0));
        }
        split(parts[1], built);
        relaid();
        started = Strings.parseInt(parts[3], 0);
    }

    private void relaid(){
        laid.clear();
        for(int i = 0; i < built.size; i++) laid.add(built.get(i));
    }

    private static String join(IntSeq values){
        StringBuilder out = new StringBuilder();
        for(int i = 0; i < values.size; i++) out.append(i == 0 ? "" : ",").append(values.get(i));
        return out.toString();
    }

    private static void split(String text, IntSeq into){
        for(String value : text.split(",")){
            if(Strings.canParseInt(value)) into.add(Strings.parseInt(value));
        }
    }

    //endregion
    //region work

    /** Called on every update of the game. */
    public void update(){
        lastThink = 0f;
        awake = active() && !state.isPaused();
        if(!awake){
            //switched off in the middle of a game: the enemy goes back to the game's own rules for it
            if(loaded && state.isGame() && !Options.enabled.on()) release();
            return;
        }

        //the game puts its own rules for the enemy back whenever a map is loaded
        if(Options.realStock.on()) stock.enforce(); else stock.release();
        pace();
        stock.update(Time.delta);
        director.tick(Time.delta);
        //the player's units stand where they are until they are sent somewhere: playing that side, the mod sends them
        if(side != null) marshal(team().data());

        clock += Time.delta;
        if((thinkTimer += Time.delta) < thinkPeriod) return;
        thinkTimer = 0f;

        long thinkStart = System.nanoTime();
        if((scriptTimer += thinkPeriod) >= scriptPeriod){
            scriptTimer = 0f;
            //where the map's scripts do things is kept clear of by the enemy; the side the scripts are written for builds there
            scripted = team() == state.rules.defaultTeam ? new IntSet() : Scripts.reserved();
            watch();
        }

        TeamData data = team().data();
        ground.team = team();
        stock.reserve(orders);
        holdYards(data);
        staff(data);
        checkup();
        if((lookTimer += thinkPeriod) >= 60f){
            lookTimer = 0f;
            lookOut(data);
        }
        //how near the units the player fights with it still works
        quiet = Options.nerve.get() * tilesize;
        if(Options.hands.on() && data.cores.contains(core -> world.isInMapArea(core.tileX(), core.tileY()))){
            //one or the other each time: together they would make for one long frame
            if(buildTurn = !buildTurn) build(data); else plan(data);
        }

        for(Unit unit : data.units){
            if(unit.controller() instanceof BuilderAI ai && ai.getClass() == BuilderAI.class && unit.canBuild()){
                unit.controller(new ExpansionAI(this, ai));
                adopted = true;
            }
            //with the enemy building by itself, a builder has no site of its own. one it took up before it was taken on
            //would be held for good when the core can't pay for it, and the builder would never go mining
            if(Options.hands.on() && unit.controller() instanceof ExpansionAI held && held.order == null && unit.buildPlan() != null) unit.clearBuilding();
            if(unit.controller() instanceof ExpansionAI ai && ai.idle()){
                CoreBuild home = unit.closestCore();
                //a builder whose base lies in a part of the map that is still closed waits with it
                if(home != null && world.isInMapArea(home.tileX(), home.tileY())) assign(ai, unit, data);
            }
        }
        lastThink = (System.nanoTime() - thinkStart) / 1e6f;
        slowestThink = Math.max(slowestThink, lastThink);
    }

    /**
     * How well each factory and yard it has built has been running lately: 1 is flat out. This is what
     * it is seen to be doing, not what was worked out for it when it was planned: power runs short,
     * a line gets cut, a drill gives less than it should. Before more of something is built, the ones
     * there are have to be getting on; if they are not, what they are short of is seen to first.
     */
    private final ObjectFloatMap<Net.Thing> health = new ObjectFloatMap<>();

    private void checkup(){
        for(Net.Thing thing : net.things){
            if(thing.kind != Net.Kind.maker && thing.kind != Net.Kind.yard) continue;
            Building build = works(thing);
            //one that is stopped because the enemy has all of that unit it may have is not short of anything
            if(build == null || !build.enabled) continue;
            health.put(thing, Mathf.lerp(health.get(thing, 1f), Mathf.clamp(build.efficiency), 0.012f));
        }
    }

    /** The factory or yard a note of the net stands for, once it is standing. */
    private @Nullable Building works(Net.Thing thing){
        if(thing.kind == Net.Kind.yard){
            return world.build(thing.x, thing.y) instanceof UnitFactory.UnitFactoryBuild yard && yard.team == team() ? yard : null;
        }
        for(int d = 0; d < 4; d++){
            Building build = world.build(thing.x + Geometry.d4x(d), thing.y + Geometry.d4y(d));
            if(build instanceof GenericCrafter.GenericCrafterBuild && build.team == team() && thing.item != null && Rates.makes(build.block, thing.item) > 0f) return build;
        }
        return null;
    }

    /** The first thing a factory or yard takes that it has not got enough of to go on. */
    private static @Nullable Item shortOf(Building build){
        ItemStack[] needs = build instanceof UnitFactory.UnitFactoryBuild yard
            ? (yard.currentPlan == -1 ? null : ((UnitFactory)build.block).plans.get(yard.currentPlan).requirements) : Rates.inputs(build.block);
        if(needs == null || build.items == null) return null;
        for(ItemStack stack : needs){
            if(build.items.get(stack.item) < stack.amount) return stack.item;
        }
        return null;
    }

    /** One of its factories for an item, or one of its yards, that has been running at less than half and is short of something. */
    private @Nullable Building starving(Net.Kind kind, @Nullable Item made){
        for(Net.Thing thing : net.things){
            if(thing.kind != kind || (made != null && thing.item != made) || health.get(thing, 1f) >= 0.5f) continue;
            Building build = works(thing);
            if(build != null && shortOf(build) != null) return build;
        }
        return null;
    }

    /**
     * One of its factories for an item, or one of its yards, that stands on a grid which can't keep up:
     * it runs at a part of its pace, and another of its kind on that grid would only slow them all by
     * as much as it added. The bases people build have no more that draws power than they have power for.
     */
    private @Nullable Building underpowered(Net.Kind kind, @Nullable Item made){
        for(Net.Thing thing : net.things){
            if(thing.kind != kind || (made != null && thing.item != made)) continue;
            Building build = works(thing);
            //one with no power at all is not one that is short of it: getting it some is seen to for its own sake
            if(build != null && build.enabled && build.power != null && build.block.consPower != null && build.power.status < 0.65f && !build.power.graph.producers.isEmpty()) return build;
        }
        return null;
    }

    /**
     * Gets more of what something of its own is short of, and leads it to that thing: the new line is
     * to join the one that supplies it, upstream of where it is led off, rather than run to a core.
     * @return what to build, or null if there is nothing to be done about it
     */
    private @Nullable Project relieve(Building hungry, TeamData data, int depth){
        return relieve(hungry, shortOf(hungry), data, depth);
    }

    private @Nullable Project relieve(Building hungry, @Nullable Item lack, TeamData data, int depth){
        //(sand was left to a factory's own crushers, and a furnace that takes 4.8 a second ran at a tenth of its pace beside
        //one or two of them: more crushers are laid for it like any other source, their line joining the one into it)
        if(lack == null) return null;
        //when the line that brought it is gone, cut by the player, more of the item made somewhere else is no help:
        //what it wants is another line, led off wherever enough of it goes by
        String key = "refeed:" + hungry.pos() + ":" + lack.name;
        float rate = feedRate(lack, wants(hungry, lack));
        if(depth == 0 && ready(key) && (feeder(hungry, lack) == null ? spare(lack) > 0f : spare(lack) >= rate * Planner.enough)){
            Project line = done(key, planner.refeed(hungry, lack, rate));
            //what it does for the thing shows only after a while: not another line the moment this one is laid
            if(line != null) retry.put(key, clock + retryPeriod * 3f);
            if(line != null) return line;
        }
        //what there is of it already comes before getting more: a line of it that runs to a core by another way is led
        //into the line this thing is fed from, so that it passes the thing first. the cores are no use to what is
        //going short: nothing can be taken out of them again
        IntSet feed = feeder(hungry, lack);
        String pool = "pool:" + hungry.pos() + ":" + lack.name;
        if(feed != null && Options.refit.on() && ready(pool)){
            Seq<Net.Thing> others = net.things.select(thing -> (thing.kind == Net.Kind.mine || thing.kind == Net.Kind.maker) && thing.item == lack && net.whole(thing) && !detached(thing) && !feed.contains(thing.pos()));
            others.sort(thing -> Math.abs(thing.x - hungry.tileX()) + Math.abs(thing.y - hungry.tileY()));
            for(int i = 0; i < Math.min(others.size, 3) && !planner.late(); i++){
                Project led = planner.relead(others.get(i), feed, hungry.tileX(), hungry.tileY());
                if(led != null){
                    notes.put(pool, "the line for " + lack.name + " from " + others.get(i).x + "," + others.get(i).y + " is led past the " + hungry.block.name + " at " + hungry.tileX() + "," + hungry.tileY());
                    retry.put(pool, clock + retryPeriod * 3f);
                    return led;
                }
            }
            notes.put(pool, others.isEmpty() ? "no other line of " + lack.name + " to lead past it" : "none of the " + others.size + " other lines of " + lack.name + " could be led past it");
            retry.put(pool, clock + retryPeriod * 2f);
        }
        //its cores are well filled with it, and no line, or not enough of one, comes to the thing: one of the lines that runs
        //into them is led into the thing instead. what runs into a core that is full is burned, and what comes along it does
        //more here (only with no line to it at all, a furnace that got a trickle off a shared line stood at a tenth of its
        //pace with the cores full of graphite)
        String turn = "turn:" + hungry.pos() + ":" + lack.name;
        if(Options.refit.on() && ready(turn) && stock.have(lack) >= stock.cap() * 0.4f){
            Seq<Net.Thing> others = net.things.select(thing -> (thing.kind == Net.Kind.mine || thing.kind == Net.Kind.maker) && thing.item == lack && net.whole(thing) && !detached(thing) && (feed == null || !feed.contains(thing.pos())));
            others.sort(thing -> Math.abs(thing.x - hungry.tileX()) + Math.abs(thing.y - hungry.tileY()));
            notes.put(turn, others.isEmpty() ? "no line of " + lack.name + " to turn into it" : "none of the " + others.size + " lines of " + lack.name + " could be led into it:");
            IntSet body = new IntSet();
            hungry.tile.getLinkedTiles(tile -> body.add(tile.pos()));
            for(int i = 0; i < Math.min(others.size, 4) && !planner.late(); i++){
                planner.note = "";
                Project led = planner.relead(others.get(i), null, hungry.tileX(), hungry.tileY(), body);
                if(led == null) notes.put(turn, notes.get(turn) + " " + planner.note + ";");
                if(led != null){
                    notes.put(turn, "the line for " + lack.name + " from " + others.get(i).x + "," + others.get(i).y + " now runs into the " + hungry.block.name + " at " + hungry.tileX() + "," + hungry.tileY());
                    retry.put(turn, clock + retryPeriod * 3f);
                    return led;
                }
            }
            retry.put(turn, clock + retryPeriod * 2f);
        }
        //what crushers make goes nowhere but into what takes it: a row of them for this one, onto its line or into it
        if(Options.ratios.on()){
            Blueprint crushers = find(Kind.quarry);
            String quarryKey = "quarry:" + hungry.pos();
            if(crushers != null && crushers.main() instanceof WallCrafter crusher && crusher.output == lack && ready(quarryKey)){
                if(!pays(crushers.cost())){
                    saveFor(crushers.main(), crushers.cost());
                    notes.put(quarryKey, "saving for crushers");
                    return null;
                }
                int copies = Math.max(Math.min(affordable(crushers.cost(), 60), Options.rowLength.get()), 1);
                Project rowOf = done(quarryKey, planner.quarry(crushers, copies, reachFor(lack), hungry, feed));
                if(rowOf != null) return rowOf;
            }
            if(!builtWith(lack)) return null;
        }
        planner.toward = feed;
        planner.towardX = hungry.tileX();
        planner.towardY = hungry.tileY();
        //nothing of it reaches the thing, and no line of it could be led there: a new source is laid for it alone
        if(feed == null && (hungry instanceof UnitFactory.UnitFactoryBuild || hungry.block instanceof GenericCrafter)){
            IntSet body = new IntSet();
            hungry.tile.getLinkedTiles(tile -> body.add(tile.pos()));
            planner.into = body;
        }
        try{
            return provide(lack, data, depth);
        }finally{
            planner.toward = null;
            planner.into = null;
        }
    }

    /**
     * Whether a factory stands in the wrong place: it has run at less than a third of its pace for a good while, short of
     * something its cores are well filled with. The item is there; it is the factory that can't be reached with it. What it
     * would take of that is then not counted against another factory, which goes up where the item passes.
     */
    private boolean unfed(Building build, @Nullable Item lacking){
        if(lacking == null || stock.have(lacking) < stock.cap() * 0.4f) return false;
        Net.Thing thing = net.things.find(t -> t.kind == Net.Kind.maker && works(t) == build);
        return thing != null && health.get(thing, 1f) < 0.3f;
    }

    /** How much of an item a second something takes when it is working. */
    private static float wants(Building build, Item item){
        if(build instanceof UnitFactory.UnitFactoryBuild yard && yard.currentPlan != -1){
            UnitFactory.UnitPlan plan = ((UnitFactory)build.block).plans.get(yard.currentPlan);
            for(ItemStack stack : plan.requirements){
                if(stack.item == item) return stack.amount / (plan.time / 60f);
            }
        }
        if(build.block instanceof GenericCrafter crafter && Rates.inputs(build.block) != null){
            for(ItemStack stack : Rates.inputs(build.block)){
                if(stack.item == item) return stack.amount / (crafter.craftTime / 60f);
            }
        }
        if(build instanceof ItemTurret.ItemTurretBuild) return Planner.ammoUse((ItemTurret)build.block, item);
        return 0.5f;
    }

    /** The tiles of the line that supplies something with an item, as far as where the item is led off it for that thing. */
    private @Nullable IntSet feeder(Building into, Item item){
        for(Net.Thing branch : net.things){
            if(branch.kind != Net.Kind.branch || branch.item != item) continue;
            int start = net.spur(branch.x, branch.y);
            if(start == -1) continue;
            net.trace(Point2.x(start), Point2.y(start));
            if(world.build(net.endX, net.endY) != into) continue;
            //found the line into it. now the line that one was led off
            for(Net.Thing source : net.things){
                if((source.kind != Net.Kind.mine && source.kind != Net.Kind.maker) || source.item != item) continue;
                IntSeq tiles = net.trace(source.x, source.y);
                int at = tiles.indexOf(branch.pos());
                if(at <= 0) continue;
                IntSet out = new IntSet();
                for(int i = 0; i < at; i++) out.add(tiles.get(i));
                return out;
            }
        }
        return null;
    }

    /**
     * Sees to it that the enemy has builders: one of its core's own units for each core. In the campaign
     * the game's AI for the enemy does this, by this same rule; on a map where that AI is not switched
     * on, as in most custom games, there would be no one to build anything.
     */
    private void staff(TeamData data){
        if((staffTimer += thinkPeriod) < 60f * 7f) return;
        staffTimer = 0f;
        if(!team().rules().aiCoreSpawn || data.cores.isEmpty()) return;
        CoreBuild core = data.cores.random();
        UnitType type = ((CoreBlock)core.block).unitType;
        if(data.countType(type) >= data.cores.size || !type.supportsEnv(state.rules.env) || !world.isInMapArea(core.tileX(), core.tileY())) return;
        Unit unit = type.create(team());
        unit.set(core);
        unit.add();
        Fx.spawn.at(unit);
        //on the player's side a unit waits to be told what to do: this one is told by the mod
        if(side != null && !(unit.controller() instanceof BuilderAI)){
            unit.controller(new ExpansionAI(this, new BuilderAI()));
            adopted = true;
        }
    }

    private float staffTimer, nextClaim;
    /** Stretches of the way to the player where no turret could be fitted in, and until when they are left alone. */
    private final IntFloatMap deadZones = new IntFloatMap();
    private int lateRounds;

    /** Notes what is passing over the ducts of the enemy's base, to learn what its lines carry. */
    private void watch(){
        for(Building build : team().data().buildings){
            if(build.block instanceof Duct && build.items != null && build.items.any() && world.isInMapArea(build.tileX(), build.tileY())) net.watch(build.pos(), build.items.first());
        }
    }

    /**
     * What the base it started with will put on its lines, before any of it has been seen there: the
     * duct by everything that stands there to make an item is noted as carrying that item. Without
     * this it would take a furnace that has not turned out its first piece for no furnace at all, and
     * build another beside it. Only in the first minutes: after that what it has seen is what counts.
     */
    private void foresee(TeamData data){
        for(Building build : data.buildings){
            if(!world.isInMapArea(build.tileX(), build.tileY())) continue;
            Item item = build.block instanceof GenericCrafter crafter && crafter.outputItems != null && crafter.outputItems.length == 1 ? crafter.outputItems[0].item
                : build instanceof BeamDrill.BeamDrillBuild drill ? drill.lastItem
                : build.block instanceof WallCrafter crafter ? crafter.output : null;
            if(item == null) continue;
            int size = build.block.size, offset = -(size - 1) / 2, x0 = build.tileX() + offset, y0 = build.tileY() + offset;
            for(int i = 0; i < size; i++){
                for(int side = 0; side < 4; side++){
                    int x = side == 0 ? x0 + size : side == 2 ? x0 - 1 : x0 + i, y = side == 1 ? y0 + size : side == 3 ? y0 - 1 : y0 + i;
                    if(!(world.build(x, y) instanceof Duct.DuctBuild duct) || duct.team != team()) continue;
                    //a duct that runs into the building takes nothing from it
                    int nx = x + Geometry.d4x(duct.rotation), ny = y + Geometry.d4y(duct.rotation);
                    if(nx >= x0 && nx < x0 + size && ny >= y0 && ny < y0 + size) continue;
                    net.watch(duct.pos(), item);
                }
            }
        }
    }

    /** Where an item goes by on the lines the enemy's base came with. Worked out once for each time something is planned. */
    IntSet seen(Item item){
        IntSet out = seenLines.get(item);
        if(out == null) seenLines.put(item, out = Options.baseLines.on() ? net.seen(item) : new IntSet());
        return out;
    }

    /** Ground it wants to build on that it has not seen, as a packed position, or -1. */
    private int scout = -1;
    /** The unit that has been sent to look at such ground, where, and until when it is waited for. */
    private @Nullable Unit lookout;
    private final Vec2 lookoutAt = new Vec2();
    private float lookoutUntil;
    private float marchTimer;
    private @Nullable Building marchTarget;
    /** The units that are out on an attack together, and how many of them set out. Those built since wait at home for the next. */
    private final IntSet field = new IntSet();
    /** Whether the group out lays siege and holds the ground it took, being joined by what is built at home. */
    private boolean holding;
    /** How near the group out on an attack has got to what it went for, and when it last got nearer; when it was last found stuck. */
    private float closest, closestAt = -1f, stuckAt = -1e9f;
    private int setOut, repulsed;

    /**
     * Leads its army when it plays the player's side, the way a careful player does. Its units wait at
     * home until there are enough of them, and then go out together for one thing of the enemy's, and
     * for the next when that is down. Units built in the meantime do not trickle after them: they
     * wait to be the next group. A group that has lost most of its number comes home, and the next
     * one is bigger. Whenever the enemy's units are at its base, everything it has turns on them first.
     * What the map asks to have destroyed is gone for before anything else, and only what lies in the
     * open part of the map: on a map that opens up by stages the rest can't be got to.
     */
    private void marshal(TeamData data){
        if((marchTimer += Time.delta) < 90f) return;
        marchTimer = 0f;
        //the quick ones (what flies, what hovers) are steered one by one: each comes at the other side from a side of its
        //own, goes for what feeds its turrets, and keeps moving. the slow ones go together, straight at what they are sent
        //against: one by one they would only be picked off. (tried: tanks steered one by one lost half the second sector)
        for(Unit unit : data.units){
            if(unit.controller() instanceof CommandAI && unit.type.canAttack && !unit.spawnedByCore && (unit.type.flying || unit.type.hovering || unit.type.speed >= 1.2f)) unit.controller(new FighterAI(this));
        }
        Seq<Unit> army = data.units.select(unit -> (unit.controller() instanceof FighterAI || unit.controller() instanceof CommandAI) && unit.type.canAttack && !unit.spawnedByCore);
        if(army.isEmpty()){
            field.clear();
            return;
        }

        //the enemy at the gates comes before any attack of its own
        Unit raider = null;
        float nearest = Float.MAX_VALUE;
        for(CoreBuild core : data.cores){
            Unit close = Units.closestEnemy(team(), core.x, core.y, tilesize * 45f, unit -> !unit.spawnedByCore && world.isInMapArea(unit.tileX(), unit.tileY()));
            if(close != null && close.dst2(core) < nearest){
                raider = close;
                nearest = close.dst2(core);
            }
        }
        //what is at home turns on them. the group out on an attack keeps on, unless home is in real danger (it is losing
        //buildings fast, or a core is badly hurt): where the other side sends each unit over as soon as it is made, one
        //raider after another would otherwise call every attack off before it got anywhere
        if(raider != null){
            //(time spent turning on raiders is not time the group out on an attack failed to get on)
            closestAt = -1f;
            boolean dire = director.losses >= Director.pressure || data.cores.contains(core -> core.health < core.maxHealth * 0.6f);
            for(Unit unit : army) if(dire || !field.contains(unit.id)) send(unit, raider);
            if(dire || field.isEmpty()) return;
        }

        //ground it wants to build on and has not seen: one of its units goes and looks. the unit a core gives its
        //player to build with uncovers nothing in the game's fog, so it has to be one that fights
        CoreBuild base = data.core();
        if(lookout != null && (!lookout.isValid() || clock > lookoutUntil || lookout.within(lookoutAt, tilesize * 5f))){
            //one that did not get there in the time it had: that place is out of the way of anything that walks
            if(lookout.isValid() && !lookout.within(lookoutAt, tilesize * 8f) && !lookout.type.flying && !lookout.type.hovering) walkedShort.add(Point2.pack((int)(lookoutAt.x / tilesize), (int)(lookoutAt.y / tilesize)));
            if(lookout.isValid() && base != null) post(lookout, base.x, base.y);
            lookout = null;
        }
        if(scout != -1 && lookout == null){
            lookoutAt.set(Point2.x(scout) * tilesize, Point2.y(scout) * tilesize);
            //what flies or hovers gets anywhere, across water too; what walks only where something walking got before
            boolean afar = walkedShort.contains(scout);
            lookout = army.select(unit -> !field.contains(unit.id) && (!afar || unit.type.flying || unit.type.hovering))
                .min(unit -> unit.dst2(lookoutAt) * (unit.type.flying || unit.type.hovering ? 0.25f : 1f));
            //nothing it has can get there: something that sees far is put up near it instead
            if(lookout == null && afar){
                revealAt = scout;
                scout = -1;
            }
            if(lookout != null){
                post(lookout, lookoutAt.x, lookoutAt.y);
                lookoutUntil = clock + 60f * 120f;
                Log.info("[EnemyExpansion] A @ goes to look at @,@.", lookout.type.name, Point2.x(scout), Point2.y(scout));
                scout = -1;
            }
        }
        Unit away = lookout;
        //what can't get to the other side stays as a guard
        Seq<Unit> out = army.select(unit -> field.contains(unit.id)), home = army.select(unit -> !field.contains(unit.id) && unit != away && reaches(unit.type));
        //a group out on an attack that has not got any nearer what it went for in a minute, and is not there yet, is stuck: its
        //way out is shut. it comes home, the way is looked into afresh, and that place is let be for a while. (on the fourth
        //sector a group was sent against a turret and stood by its own core for a quarter of an hour)
        if(out.any() && aimAt != null && (gathered || !strike)){
            float cx = 0f, cy = 0f;
            for(Unit unit : out){
                cx += unit.x / out.size;
                cy += unit.y / out.size;
            }
            float gap = Mathf.dst(cx, cy, aimAt.x, aimAt.y);
            if(gap < closest - tilesize * 3f || closestAt < 0f){
                closest = gap;
                closestAt = clock;
            }else if(clock - closestAt > 60f * 60f && gap > tilesize * 30f){
                Log.info("[EnemyExpansion] The group of @ out against the @ at @,@ has got no nearer in a minute (it stands round @,@, @ tiles off): its way is shut. It comes home.", out.size, aimAt.block.name, aimAt.tileX(), aimAt.tileY(), (int)(cx / tilesize), (int)(cy / tilesize), (int)(gap / tilesize));
                keptOut.put(Task.key(Task.Kind.attack, atlas.zoneAt(aimAt.tileX(), aimAt.tileY()), null), clock + 60f * 60f * 8f);
                reachClock = -1e9f;
                stuckAt = clock;
                //and what of its own stands on the way its units are to take is taken down: walls and the like that shut it in
                int cleared = clearLanes(data);
                if(cleared > 0) Log.info("[EnemyExpansion] @ of its own blocks on its units' way out are taken down.", cleared);
                for(Unit unit : out) if(base != null) post(unit, base.x, base.y);
                field.clear();
                setOut = 0;
                marchTarget = null;
                closestAt = -1f;
                return;
            }
        }else{
            closestAt = -1f;
        }
        //the quick ones at home stand round the core, each in a place of its own, and not in one heap
        for(Unit unit : army){
            if(raider != null || field.contains(unit.id) || unit == away || base == null || !(unit.controller() instanceof FighterAI ai)) continue;
            float angle = (unit.id * 137.5f) % 360f, far = tilesize * (8f + unit.id % 5 * 2f);
            ai.standAt(base.x + Angles.trnsx(angle, far), base.y + Angles.trnsy(angle, far));
        }
        //a group that is mostly gone comes back, and the next has to be stronger
        //(but not with what it went for about to fall: that is finished first)
        float left = 0f;
        for(Unit unit : out) left += dps(unit.type, aimAt == null ? 0f : aimAt.block.armor);
        boolean finishing = strike && gathered && aimAt != null && aimAt.isValid() && aimAt.team != team() && aimAt.health < left * 4f;
        //and a group much reduced that has just brought down what it went for, and is not strong enough for the next, comes
        //back to be joined by those built since rather than go on as it is. (on the third sector the eludes took the outpost
        //and went on to the other side's core with four of fourteen, and were thrown back every time)
        //(a group that dodges lays siege one turret at a time and needs no edge over all of them: it goes on while it is half
        //of what set out)
        boolean siege = out.any() && out.allMatch(unit -> unit.controller() instanceof FighterAI fighter && fighter.agile());
        boolean spent = setOut > 0 && out.size < setOut * (siege ? 0.45f : 0.6f) && aimAt != null && !aimAt.isValid() && !finishing;
        if(spent){
            float sx = 0f, sy = 0f;
            for(Unit unit : out){
                sx += unit.x / out.size;
                sy += unit.y / out.size;
            }
            Building next = quarry(sx, sy);
            spent = siege || (next != null && !overwhelms(out, next));
        }
        //a group that lays siege holds what it has taken: the other side builds again what was pushed down, and only units
        //standing there keep it down. it is not called back for being reduced; those built at home come out to it (the way a
        //player takes the third sector: push down the key places, leave units there, send more when they run low)
        if(siege && setOut > 0 && out.size >= 2){
            spent = false;
            holding = true;
        }else{
            holding = false;
        }
        if(setOut > 0 && !holding && (out.size < Math.max(setOut / 3, 2) || spent) && !finishing){
            Log.info("[EnemyExpansion] The attack of @ is thrown back with @ left; @.", setOut, out.size, sizedUp);
            if(aimAt != null){
                int zone = atlas.zoneAt(aimAt.tileX(), aimAt.tileY());
                long key = Task.key(Task.Kind.attack, zone, null);
                ledger.repulsed(key, clock);
                if(ledger.looping(key, clock) && !avoided(Task.Kind.attack, zone)){
                    keptOut.put(key, clock + 60f * 60f * 8f);
                    Log.info("[EnemyExpansion] Going round in a loop: thrown back at zone @ again (@). Nothing more is sent there for a while.", zone, ledger.describe(key, clock));
                }
            }
            for(Unit unit : out){
                if(base != null) post(unit, base.x, base.y);
            }
            field.clear();
            setOut = 0;
            repulsed++;
            marchTarget = null;
            return;
        }
        if(out.isEmpty()){
            field.clear();
            setOut = 0;
        }

        //enough to set out with: most of what it may have of a unit to begin with, and more each time it was thrown back
        int most = 0;
        for(Unit unit : army) most = Math.max(most, Units.getCap(team()));
        //all it may have, or near it: an army that goes out by the dozen is picked off by the dozen. (it went at four fifths,
        //and on a sector where the other side turns out ships faster than it, every wave was lost on the way)
        int enough = Math.max(most - 1, 4);
        //or as many as bring down what they would go for, by the square law, with a margin: with two cores the cap was
        //thirty-odd, the yards never got there, and twenty-seven units stood at home for a quarter of an hour
        boolean able = false;
        if(out.isEmpty() && home.size >= 6 && home.size < enough && turretsHold){
            float hx = 0f, hy = 0f;
            for(Unit unit : home){
                hx += unit.x / home.size;
                hy += unit.y / home.size;
            }
            Building first = quarry(hx, hy);
            if(first != null){
                Seq<Unit> half = home.copy();
                half.truncate(Math.max((int)(home.size / 1.5f), 1));
                able = overwhelms(half, first);
            }
        }
        if(home.size >= enough || able || (setOut > 0 && home.size >= Math.max(enough / 2, 3)) || (holding && home.size >= 3 && turretsHold)){
            //those waiting at home go out, as a group of their own or to join the one that is out
            for(Unit unit : home) field.add(unit.id);
            Log.info("[EnemyExpansion] @ units set out (@ out already; it may have @).", home.size, out.size, most);
            setOut = out.size + home.size;
            out.addAll(home);
        }
        if(out.isEmpty()) return;

        float x = 0f, y = 0f;
        for(Unit unit : out){
            x += unit.x / out.size;
            y += unit.y / out.size;
        }
        if(marchTarget == null || !marchTarget.isValid() || marchTarget.team == team() || !world.isInMapArea(marchTarget.tileX(), marchTarget.tileY())) marchTarget = quarry(x, y);
        if(marchTarget == null) return;
        Building was = strike ? aimAt : null;
        Building aim = marchTarget;
        //a group quick enough to dodge lays siege to one turret at a time, the one the fewest others cover: cuts its supply,
        //dodges its fire until it has fired the last of what it had, and pushes it down. it has only the one turret to
        //dodge that way, and it is not fired on by all of them at once. (that is how a player takes the third sector on its
        //hardest setting, leaving the island outpost alone)
        boolean dodgers = out.allMatch(unit -> unit.controller() instanceof FighterAI fighter && fighter.agile());
        Building lone = dodgers ? loneTurret(out, x, y, aim) : null;
        if(lone != null){
            aimAt = lone;
            strike = true;
            if(lone != was){
                Log.info("[EnemyExpansion] @ units lay siege to the @ at @,@: the one fewest other turrets cover.", out.size, lone.block.name, lone.tileX(), lone.tileY());
            }
            //they come at it together, from out of reach of its guns: one by one they would be picked off as they came up
            if(lone != stageFor){
                boolean took = stageFor != null && !stageFor.isValid();
                //what the last siege cost, measured: from when it began to when what it besieged was down
                if(took && siegeFrom > 0) siegeLoss = siegeLoss < 0f ? siegeFrom - out.size : (siegeLoss + siegeFrom - out.size) / 2f;
                stage(lone);
                //a place just taken is held from where it was besieged, out of reach of the rest: the other side builds again what
                //was pushed down, and units standing there keep it down while more come out to them. (gathering again further
                //back, the group came on to find the breach it had taken built again)
                if(took && siegeStand != null && danger(siegeStand.x, siegeStand.y, tilesize * 4f) == 0){
                    stageX = siegeStand.x;
                    stageY = siegeStand.y;
                }
                stageFor = lone;
                gathered = false;
                gatherSince = clock;
            }
            if(!gathered){
                //until most of it has come up and there are enough of it for what a siege has cost
                int near = out.count(unit -> unit.within(stageX, stageY, tilesize * 10f));
                int need = Math.min(Math.max(5, (int)Math.ceil(Math.max(siegeLoss, 0f) * 2f) + 2), Math.max(Units.getCap(team()) - 1, 5));
                boolean strong = out.size >= need;
                if((near >= out.size * 0.75f && strong) || clock - gatherSince > 60f * (strong ? 45f : 180f)){
                    gathered = true;
                    siegeFrom = out.size;
                    float[] spot = siegeSpot(lone, out.first().range());
                    siegeStand = spot == null ? null : new Vec2(lone.x + Angles.trnsx(spot[0], out.first().range() * 0.6f), lone.y + Angles.trnsy(spot[0], out.first().range() * 0.6f));
                }else{
                    for(Unit unit : out) post(unit, stageX, stageY);
                    return;
                }
            }
            for(Unit unit : out) send(unit, lone);
            return;
        }
        strike = overwhelms(out, aim);
        //what it set out to bring down it keeps at while it still can: a group that turns from one thing to the next as it
        //moves gets strung out between them
        if(!strike && was != null && was.isValid() && was.team != team() && overwhelms(out, was)){
            aim = was;
            strike = true;
        }
        //not strong enough for it as it stands: what guards it is taken away first, one turret at a time, each one that the
        //group can bring down well before the turrets that reach it bring the group down; the nearest such first
        if(!strike){
            Building peel = peel(out, x, y);
            if(peel != null){
                aim = peel;
                strike = true;
            }
        }
        aimAt = aim;
        if((strike ? aim : null) != was){
            overwhelms(out, aim);
            Log.info("[EnemyExpansion] @ units against the @ at @,@: @ (@).", out.size, aim.block.name, aim.tileX(), aim.tileY(), strike ? "straight for it" : "at what keeps it", sizedUp);
        }
        //the group goes in together: first to a place out of reach of the other side's turrets on its way there, and on
        //only when most of it has come up. units that come up one by one are shot down one by one
        if(strike){
            if(aim != stageFor){
                //a group that has just brought down what it went for, and is still together, goes straight on to the next: falling
                //back to gather again, it stood about out there while what guards the next came at it. (on the third sector the
                //eludes took a breach, went back to gather, and were picked off by the other side's ships while they waited)
                //(and a group that is together already does not go back to gather because what it means to go for changed: weighed
                //afresh every round, the aim went back and forth between two things, each time with a new place to gather at, and
                //the group stood gathering at its own core for a quarter of an hour)
                float cx = x, cy = y;
                boolean together = (gathered || stageFor != null) && out.count(unit -> unit.within(cx, cy, tilesize * 12f)) >= out.size * 0.75f
                    && (gathered || clock - gatherSince > 60f * 10f);
                stageFor = aim;
                gathered = together;
                gatherSince = clock;
                if(!together) stage(aim);
            }
            if(!gathered){
                int near = out.count(unit -> unit.within(stageX, stageY, tilesize * 10f));
                if(near >= out.size * 0.75f || clock - gatherSince > 60f * 45f){
                    gathered = true;
                }else{
                    for(Unit unit : out) post(unit, stageX, stageY);
                    return;
                }
            }
        }
        for(Unit unit : out) send(unit, aim);
    }

    /** How many units a siege has cost lately, measured, or less than 0 before any; how many began the one going on; and where they stood for it. */
    private float siegeLoss = -1f;
    private int siegeFrom;
    private @Nullable Vec2 siegeStand;

    /** Where the group out on the attack comes together before it goes in, and whether it has. */
    private @Nullable Building stageFor;
    private float stageX, stageY, gatherSince;
    private boolean gathered;

    /** The nearest place on the way from its own nearest core to something that none of the other side's turrets reach. */
    private void stage(Building target){
        CoreBuild home = Geometry.findClosest(target.x, target.y, team().cores());
        float dx = home == null ? 0f : home.x - target.x, dy = home == null ? 0f : home.y - target.y, len = Mathf.len(dx, dy);
        stageX = target.x;
        stageY = target.y;
        if(len < 1f) return;
        for(float far = tilesize * 20f; far < len; far += tilesize * 2f){
            stageX = target.x + dx / len * far;
            stageY = target.y + dy / len * far;
            if(danger(stageX, stageY, tilesize * 6f) == 0) break;
        }
    }

    /** What the group out on the attack is going for at the moment. */
    private @Nullable Building aimAt;

    /** Whether the whole group out on the attack is going for this one thing together. */
    public boolean striking(@Nullable Teamc what){
        return strike && what != null && what == aimAt;
    }

    /** The nearest of the other side's turrets that a group can bring down well before what reaches it brings the group down. */
    private @Nullable Building peel(Seq<Unit> group, float x, float y){
        Seq<Building> turrets = new Seq<>();
        for(TeamData other : state.teams.active){
            if(other.team == team() || other.team == Team.derelict) continue;
            for(Building build : other.buildings){
                if(build instanceof Turret.TurretBuild turret && turret.hasAmmo() && world.isInMapArea(build.tileX(), build.tileY())) turrets.add(build);
            }
        }
        turrets.removeAll(build -> avoided(Task.Kind.attack, atlas.zoneAt(build.tileX(), build.tileY())));
        //a group that can go round what does not guard what it was sent against takes away only what does: turrets whose
        //guns reach the ground it will fight on there. (on the third sector the eludes took the same outpost on an island on
        //the way, rebuilt each time, every attack, and got to the core with a quarter of their number; ships go round it)
        Building aim = marchTarget;
        boolean around = aim != null && group.allMatch(unit -> unit.type.flying || unit.type.hovering);
        if(around){
            float reach = 0f;
            for(Unit unit : group) reach = Math.max(reach, unit.range());
            float r = reach;
            Seq<Building> guarding = turrets.select(build -> build.within(aim, ((Turret)build.block).range + r + tilesize * 4f));
            if(guarding.any()) turrets = guarding;
        }
        turrets.sort(build -> build.dst2(x, y));
        for(int i = 0; i < Math.min(turrets.size, 16); i++){
            if(overwhelms(group, turrets.get(i))) return turrets.get(i);
        }
        return null;
    }

    /**
     * Of the other side's turrets that guard what the group was sent against, or stand on its way, the one the fewest
     * others cover; of those, the nearest. Null with none left that guard it.
     */
    private @Nullable Building loneTurret(Seq<Unit> group, float x, float y, Building aim){
        float reach = 0f;
        for(Unit unit : group) reach = Math.max(reach, unit.range());
        Seq<Building> turrets = new Seq<>();
        for(TeamData other : state.teams.active){
            if(other.team == team() || other.team == Team.derelict) continue;
            for(Building build : other.buildings){
                if(build instanceof Turret.TurretBuild && world.isInMapArea(build.tileX(), build.tileY())) turrets.add(build);
            }
        }
        float r = reach;
        Seq<Building> guarding = turrets.select(build -> build.within(aim, ((Turret)build.block).range + r + tilesize * 4f));
        if(guarding.isEmpty()) return null;
        Building best = null;
        float bestScore = Float.MAX_VALUE;
        for(Building build : guarding){
            //the others whose guns reach where the group would stand to fire on it: where it is furthest out of their reach
            float[] spot = siegeSpot(build, r);
            float px = build.x, py = build.y;
            if(spot != null){
                px += Angles.trnsx(spot[0], r * 0.6f);
                py += Angles.trnsy(spot[0], r * 0.6f);
            }
            int cover = 0;
            for(Building other : turrets){
                if(other != build && other.within(px, py, ((Turret)other.block).range + tilesize * 2f)) cover++;
            }
            float score = cover * 1000f + build.dst(x, y) / tilesize;
            if(score < bestScore){
                best = build;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * Where its besiegers stand round a turret under siege: of the ring at the distance they fire from, the stretch
     * furthest out of reach of every other turret near it, as the angle at its middle and how many degrees either side of
     * that stay out of their reach (0 where nowhere does, and the angle is then where their reach is least). Null with no
     * other turret near. (going by which way the others lay on the whole, ships spread a third of the way round the ring,
     * and the next turret's guns took half of them before the one besieged had fired its last)
     */
    public @Nullable float[] siegeSpot(Teamc aim, float range){
        int key = (aim instanceof Building build ? build.pos() : 0) * 31 + (int)(range / tilesize);
        float[] known = siegeSpots.get(key);
        if(known != null && clock - known[2] < 60f) return known[3] == 0f ? null : known;
        Seq<Building> others = new Seq<>();
        for(TeamData other : state.teams.active){
            if(other.team == team() || other.team == Team.derelict) continue;
            indexer.eachBlock(other.team, aim.getX(), aim.getY(), tilesize * 45f, build -> build instanceof Turret.TurretBuild && build != aim, others::add);
        }
        float[] out = known != null ? known : new float[4];
        out[2] = clock;
        out[3] = others.isEmpty() ? 0f : 1f;
        siegeSpots.put(key, out);
        if(others.isEmpty()) return null;
        int steps = 36;
        float r = range * 0.6f;
        float[] clear = new float[steps];
        for(int i = 0; i < steps; i++){
            float px = aim.getX() + Angles.trnsx(i * 360f / steps, r), py = aim.getY() + Angles.trnsy(i * 360f / steps, r);
            float least = Float.MAX_VALUE;
            for(Building other : others) least = Math.min(least, (other.dst(px, py) - ((Turret)other.block).range) / tilesize);
            clear[i] = least;
        }
        //the widest run round the ring of places a few tiles out of every other's reach
        int bestStart = -1, bestLength = 0;
        for(int start = 0; start < steps; start++){
            if(clear[start] < siegeMargin || clear[(start + steps - 1) % steps] >= siegeMargin && bestStart != -1) continue;
            int length = 0;
            while(length < steps && clear[(start + length) % steps] >= siegeMargin) length++;
            if(length > bestLength){
                bestStart = start;
                bestLength = length;
            }
        }
        if(bestStart == -1){
            int most = 0;
            for(int i = 1; i < steps; i++) if(clear[i] > clear[most]) most = i;
            out[0] = most * 360f / steps;
            out[1] = 0f;
        }else{
            out[0] = (bestStart + (bestLength - 1) / 2f) * 360f / steps;
            out[1] = bestLength >= steps ? 180f : (bestLength - 1) * 180f / steps;
        }
        return out;
    }

    /** How far out of the reach of other turrets, in tiles, a place to besiege one from has to be. */
    static final float siegeMargin = 3f;
    private final IntMap<float[]> siegeSpots = new IntMap<>();

    /** Whether the group out on the attack is strong enough to go straight for what it was sent against. */
    private boolean strike;
    /** How the last attack was sized up, for looking into it. */
    private String sizedUp = "";

    /**
     * Whether a group can bring something down well before what defends it brings the group down: the
     * time its fire takes to wear the thing's health away, against the time the other side's turrets that
     * reach the ground the group would fire from, and its units near there, take to wear away the group's.
     * Then the group goes straight for it and nothing else. If not, it picks at what keeps those turrets
     * firing first. (A battle of fire against fire is won by the side that brings more of it to bear at once.)
     */
    private boolean overwhelms(Seq<Unit> group, Building target){
        float fire = 0f, lasts = 0f, reach = 0f;
        float armor = target.block.armor;
        for(Unit unit : group){
            fire += dps(unit.type, armor);
            lasts += unit.health + unit.shield;
            reach = Math.max(reach, unit.range());
        }
        float[] against = {0f};
        float from = reach;
        for(TeamData other : state.teams.active){
            if(other.team == team() || other.team == Team.derelict) continue;
            indexer.eachBlock(other.team, target.x, target.y, from + tilesize * 40f, build -> build instanceof Turret.TurretBuild turret && turret.hasAmmo(), build -> {
                if(build.within(target, ((Turret)build.block).range + from)) against[0] += turretDps((Turret.TurretBuild)build);
            });
            for(Unit unit : other.units){
                if(unit.type.canAttack && !unit.spawnedByCore && unit.within(target, from + tilesize * 25f)) against[0] += dps(unit.type, 0f);
            }
        }
        //and what of the other side's army has been seen there and next to it lately: what is not there as the group sets out
        //comes when it is fighting (it had been reckoned three times too weak where the other side's ships came in behind)
        Atlas.Zone at = atlas.zone(target.tileX(), target.tileY());
        if(at != null){
            float seen = at.peakDps;
            IntSet.IntSetIterator next = at.near.iterator();
            while(next.hasNext) seen += atlas.zones.get(next.next()).peakDps * 0.5f;
            float turrets = 0f;
            against[0] = Math.max(against[0], against[0] + seen * 0.5f);
        }
        //what is fired at it from the side the group comes from hits whatever stands in the way first: walls round a turret
        //have to be got through before the turret itself is hurt
        float gx = 0f, gy = 0f;
        for(Unit unit : group){
            gx += unit.x / group.size;
            gy += unit.y / group.size;
        }
        float[] shield = {0f};
        float fx = gx, fy = gy, edge = target.block.size * tilesize / 2f + tilesize * 2.5f;
        indexer.eachBlock(target.team, target.x, target.y, edge, build -> build != target && !(build.block instanceof BaseTurret)
            && (build.x - target.x) * (fx - target.x) + (build.y - target.y) * (fy - target.y) > 0f, build -> shield[0] += build.health);
        float kill = (target.health + shield[0]) / Math.max(fire, 1f), lost = lasts / Math.max(against[0], 1f);
        sizedUp = Strings.format("its @ units deal @/s and last @ s; the @ has @ health and @ in front of it, what guards it deals @/s, so it falls in @ s", group.size, (int)fire, (int)lost, target.block.name, (int)target.health, (int)shield[0], (int)against[0], (int)kill);
        return kill < lost * 0.5f;
    }

    /** About how much damage a second a kind of unit deals to something with so much armour. */
    static float dps(UnitType type, float armor){
        float dps = type.dpsEstimate > 0f ? type.dpsEstimate : type.estimateDps();
        if(armor > 0f && !type.weapons.isEmpty()){
            float hit = type.weapons.first().bullet.damage;
            if(hit > 0f) dps *= Math.max(hit - armor, hit * 0.25f) / hit;
        }
        return dps;
    }

    /** About how much damage a second a turret deals with what it is loaded with. */
    static float turretDps(Turret.TurretBuild turret){
        mindustry.entities.bullet.BulletType bullet = turret.peekAmmo();
        if(bullet == null) return 0f;
        Turret type = (Turret)turret.block;
        float dps = (bullet.damage + bullet.splashDamage) * type.shoot.shots * 60f / Math.max(type.reload, 1f);
        //water makes it fire a good deal faster (about two and a half times, on this planet's turrets)
        if(type.coolant != null && turret.liquids != null && turret.liquids.currentAmount() > 0.1f) dps *= 2.5f;
        //and a shot that goes through one unit into the next hits a group that comes on together more than once
        if(bullet.pierce || bullet.pierceCap > 1) dps *= 1.5f;
        return dps;
    }

    /** Sends one of its units against something: one steered by itself chooses what to fire at near there. */
    private void send(Unit unit, Teamc target){
        if(unit.controller() instanceof FighterAI fighter) fighter.attack(target);
        else if(unit.controller() instanceof CommandAI ai && ai.attackTarget != target) ai.commandTarget(target);
    }

    /** Sends one of its units to stand at a place. */
    private void post(Unit unit, float x, float y){
        if(unit.controller() instanceof FighterAI fighter) fighter.standAt(x, y);
        else if(unit.controller() instanceof CommandAI ai) ai.commandPosition(Tmp.v1.set(x, y));
    }

    /** The other side's ducts and the like that hand its turrets their ammunition, all the way back up each line, and how many turrets each feeds. Looked at once a second. */
    private final IntSeq feeders = new IntSeq();
    private final IntIntMap served = new IntIntMap();
    /**
     * What stands and does nothing comes first: a yard or a factory that has been running at less than half its pace for
     * want of something it takes is given it, one short of power gets power, before anything new is begun. What it has
     * paid for already brings nothing until it runs, and gets running for the price of a line.
     */
    private @Nullable Project activate(TeamData data){
        if(!Options.ratios.on() || !ready("activate")) return null;
        for(Net.Kind kind : new Net.Kind[]{Net.Kind.yard, Net.Kind.maker}){
            Building hungry = starving(kind, null);
            if(hungry != null){
                Project relief = relieve(hungry, data, 0);
                if(relief != null || saving){
                    notes.put("activate", "the " + hungry.block.name + " at " + hungry.tileX() + "," + hungry.tileY() + " is short of " + shortOf(hungry).name);
                    return relief;
                }
            }
            Building weak = underpowered(kind, null);
            if(weak != null){
                Project more = power(data);
                if(more != null || saving){
                    notes.put("activate", "the " + weak.block.name + " at " + weak.tileX() + "," + weak.tileY() + " is short of power");
                    return more;
                }
            }
        }
        retry.put("activate", clock + retryPeriod);
        return null;
    }

    /** Ground it wants to build on, has not seen, and has nothing to send to look at, as a packed tile; or -1. */
    private int revealAt = -1;
    /** The stretches of ground, sixteen tiles across, it has put up something to look at. */
    private final IntSet revealed = new IntSet();

    /** Puts up something that sees far near ground it wants and can't get a unit of its own to. */
    private @Nullable Project reveal(){
        if(revealAt == -1 || !may(Blocks.radar) || !ready("reveal")) return null;
        int x = Point2.x(revealAt), y = Point2.y(revealAt);
        //one for each stretch of ground it wants: what one there does not uncover, another beside it would not either
        if(revealed.contains(Point2.pack(x / 16, y / 16))){
            revealAt = -1;
            return null;
        }
        if(!state.rules.fog || fogControl.isDiscovered(team(), x, y)){
            revealAt = -1;
            return null;
        }
        if(!slack(Blocks.radar.requirements)){
            notes.put("reveal", "a radar to look at " + x + "," + y + " waits for room to spare");
            return null;
        }
        Project project = done("reveal", planner.lookout(Blocks.radar, x, y));
        if(project != null){
            revealAt = -1;
            revealed.add(Point2.pack(x / 16, y / 16));
        }
        return project;
    }

    /** Places a unit that walks was sent to look at and did not get to. */
    private final IntSet walkedShort = new IntSet();
    private final IntIntMap sentAt = new IntIntMap();
    private float feedersAt = -1000f;

    private void findFeeders(){
        if(clock - feedersAt < 60f) return;
        feedersAt = clock;
        feeders.clear();
        served.clear();
        sentAt.clear();
        for(TeamData other : state.teams.active){
            if(other.team == team() || other.team == Team.derelict) continue;
            for(Building build : other.buildings){
                if(!(build instanceof ItemTurret.ItemTurretBuild) || !world.isInMapArea(build.tileX(), build.tileY())) continue;
                for(Building next : build.proximity){
                    if(next.team != other.team || next.block.group != mindustry.world.meta.BlockGroup.transportation) continue;
                    //what faces another way hands the turret nothing
                    if(next.block.rotate && next.front() != build) continue;
                    //all the way back up the line, through its routers, to the drills that fill it: a tile further up feeds every
                    //turret its branches lead to, and stands further from their guns; a drill feeds all of them. what is cut
                    //there leaves all of them to run dry
                    IntSet seen = new IntSet();
                    IntQueue queue = new IntQueue();
                    queue.addLast(next.pos());
                    seen.add(next.pos());
                    int steps = 0;
                    while(!queue.isEmpty() && steps++ < 160){
                        Building at = world.build(queue.removeFirst());
                        if(at == null) continue;
                        if(!feeders.contains(at.pos())) feeders.add(at.pos());
                        served.increment(at.pos());
                        if(!(at.block.group == mindustry.world.meta.BlockGroup.transportation)) continue;
                        for(Building prev : at.proximity){
                            if(prev.team != other.team || seen.contains(prev.pos())) continue;
                            boolean feeds;
                            if(prev.block.group == mindustry.world.meta.BlockGroup.transportation) feeds = !prev.block.rotate || prev.front() == at;
                            else feeds = prev.block instanceof mindustry.world.blocks.production.Drill || prev.block instanceof mindustry.world.blocks.production.BeamDrill;
                            //(a duct this one hands on to is not what feeds it)
                            if(!feeds || (at.block.rotate && at.front() == prev)) continue;
                            seen.add(prev.pos());
                            queue.addLast(prev.pos());
                        }
                    }
                }
            }
            //and what powers its drills: with its generators down, every drill that runs on power stops, and with them what
            //they fill the turrets with (the game's own flying units go for generators, factories and drills first)
            for(Building build : other.buildings){
                if(!(build.block instanceof mindustry.world.blocks.power.PowerGenerator) || !world.isInMapArea(build.tileX(), build.tileY())) continue;
                int drills = 0;
                if(build.power != null) for(Building on : build.power.graph.all) if(on.block instanceof mindustry.world.blocks.production.BeamDrill || on.block instanceof mindustry.world.blocks.production.Drill) drills++;
                if(drills == 0) continue;
                if(!feeders.contains(build.pos())) feeders.add(build.pos());
                served.put(build.pos(), served.get(build.pos(), 0) + Math.max(drills / 2, 2));
            }
        }
    }

    /** How many of the other side's turrets that have something to fire cover a place. */
    private int danger(float x, float y){
        return danger(x, y, 0f);
    }

    /** How many of the other side's turrets that have something to fire reach within so far of a place. */
    private int danger(float x, float y, float margin){
        int[] count = {0};
        for(TeamData other : state.teams.active){
            if(other.team == team() || other.team == Team.derelict) continue;
            indexer.eachBlock(other.team, x, y, tilesize * 40f + margin, build -> build instanceof Turret.TurretBuild turret && turret.hasAmmo(), build -> {
                if(build.within(x, y, ((Turret)build.block).range + margin)) count[0]++;
            });
        }
        return count[0];
    }

    /**
     * What one of its fighting units fires at near what it was sent against: the supply of the other
     * side's turrets first, the least watched over and the nearest; then turrets that have run dry;
     * else what it was sent against. No more than a few go for the same thing, so that they spread.
     */
    public @Nullable Teamc pickTarget(Unit unit, @Nullable Teamc goal){
        if(goal == null) return null;
        //strong enough to bring it down outright: everything goes for it, from all sides at once. but what is quick enough to
        //dodge cuts what feeds the turrets first even then: a turret that has fired its store and gets no more is no turret,
        //and a duct goes down in two shots where a turret takes forty
        boolean dodges = unit.controller() instanceof FighterAI fighter && fighter.agile();
        if(strike && goal == aimAt && field.contains(unit.id) && !dodges) return goal;
        findFeeders();
        //what the other side is building again within reach of a unit out there comes first: pushed down once, it is kept down
        if(dodges && field.contains(unit.id)){
            Building rebuilt = null;
            for(TeamData other : state.teams.active){
                if(other.team == team() || other.team == Team.derelict) continue;
                Building near = indexer.findTile(other.team, unit.x, unit.y, unit.range() + tilesize * 6f, build -> build instanceof ConstructBuild);
                if(near != null && (rebuilt == null || unit.dst(near) < unit.dst(rebuilt))) rebuilt = near;
            }
            if(rebuilt != null) return rebuilt;
        }
        //laying siege to a turret: what feeds that turret, and nothing else; with that cut, the turret itself, which is then
        //fired on while it fires its last
        if(dodges && goal instanceof Turret.TurretBuild siege){
            if(!siege.hasAmmo()) return goal;
            Building cut = null;
            float least = Float.MAX_VALUE;
            for(int i = 0; i < feeders.size; i++){
                Building build = world.build(feeders.get(i));
                if(build == null || build.team == team() || !build.within(siege, tilesize * 12f)) continue;
                float score = danger(build.x, build.y) * 10f + build.dst(siege) / tilesize;
                if(score < least){
                    cut = build;
                    least = score;
                }
            }
            return cut != null ? cut : goal;
        }
        float reach = unit.range() + tilesize * 25f;
        Building best = null;
        float bestScore = Float.MAX_VALUE;
        int looked = 0;
        for(int i = 0; i < feeders.size && looked < 120; i++){
            Building build = world.build(feeders.get(i));
            if(build == null || build.team == team() || !unit.within(build, reach)) continue;
            looked++;
            int sent = sentAt.get(build.pos(), 0);
            if(sent >= 3) continue;
            //what feeds most turrets, and is least under their guns, first
            float score = unit.dst(build) / tilesize + danger(build.x, build.y) * (dodges ? 3f : 15f) + sent * 10f - served.get(build.pos(), 1) * 6f;
            if(score < bestScore){
                best = build;
                bestScore = score;
            }
        }
        if(best == null){
            for(TeamData other : state.teams.active){
                if(other.team == team() || other.team == Team.derelict) continue;
                Building dry = indexer.findTile(other.team, unit.x, unit.y, reach, build -> build instanceof Turret.TurretBuild turret && !turret.hasAmmo() && build.block instanceof ItemTurret);
                if(dry != null && (best == null || unit.dst(dry) < unit.dst(best))) best = dry;
            }
        }
        if(best == null) return goal;
        sentAt.increment(best.pos());
        return best;
    }

    /** What its army goes for next: what the map asks to have destroyed, or else the nearest thing of the enemy's in the open part of the map. */
    private @Nullable Building quarry(float x, float y){
        for(var objective : state.rules.objectives.all){
            if(objective.isCompleted() || !objective.dependencyFinished()) continue;
            if(objective instanceof mindustry.game.MapObjectives.DestroyBlockObjective one){
                Building at = world.build(one.pos.x, one.pos.y);
                if(at != null && at.team != team() && world.isInMapArea(at.tileX(), at.tileY())) return at;
            }else if(objective instanceof mindustry.game.MapObjectives.DestroyBlocksObjective many){
                for(Point2 pos : many.positions){
                    Building at = world.build(pos.x, pos.y);
                    if(at != null && at.team != team() && world.isInMapArea(at.tileX(), at.tileY())) return at;
                }
            }
        }
        //then the other side's cores: with the last of those down the map is won, whatever else still stands. people
        //who play these maps go for the core with one strong group and do not fight their way through the whole base
        Building best = null;
        float bestFar = Float.MAX_VALUE;
        for(CoreBuild core : foe().cores()){
            if(core.dst2(x, y) < bestFar && world.isInMapArea(core.tileX(), core.tileY())){
                best = core;
                bestFar = core.dst2(x, y);
            }
        }
        if(best != null) return best;
        for(TeamData other : state.teams.active){
            if(other.team == team() || other.team == Team.derelict) continue;
            for(Building build : other.buildings){
                float far = build.dst2(x, y);
                if(far < bestFar && !build.block.privileged && world.isInMapArea(build.tileX(), build.tileY())){
                    best = build;
                    bestFar = far;
                }
            }
        }
        return best;
    }

    /** What the game had the enemy's build speed at before it was set to the player's, or a negative number if it was not touched. */
    private float gamePace = -1f;

    /**
     * Has the enemy's builders work at the pace of the player's: what a team builds at is a rule of the
     * map, and the game sets the enemy's lower than the player's on this planet. An opponent that plays
     * by the player's rules builds neither slower nor faster.
     */
    private void pace(){
        Rules.TeamRule mine = state.rules.teams.get(team()), theirs = state.rules.teams.get(state.rules.defaultTeam);
        if(side != null) return;
        if(Options.sameSpeed.on() && team() != state.rules.defaultTeam){
            if(mine.buildSpeedMultiplier != theirs.buildSpeedMultiplier){
                if(gamePace < 0f) gamePace = mine.buildSpeedMultiplier;
                mine.buildSpeedMultiplier = theirs.buildSpeedMultiplier;
            }
        }else if(gamePace >= 0f){
            mine.buildSpeedMultiplier = gamePace;
            gamePace = -1f;
        }
    }

    /** Gives the builders, and the enemy's stock, back to the game. */
    private void release(){
        stock.release();
        if(gamePace >= 0f){
            state.rules.teams.get(team()).buildSpeedMultiplier = gamePace;
            gamePace = -1f;
        }
        if(!adopted) return;
        adopted = false;
        for(Unit unit : Groups.unit){
            if(unit.controller() instanceof ExpansionAI ai){
                ai.drop();
                unit.resetController();
            }
        }
    }

    private void assign(ExpansionAI ai, Unit unit, TeamData data){
        //builders work from this far away
        reach = (unit.type.buildRange - tilesize * 2f) * 0.8f;

        //deliveries go to the nearest core the player is not close to
        CoreBuild home = Geometry.findClosest(unit.x, unit.y, data.cores.select(core -> calm(core.x, core.y, quiet)));
        if(home == null) return;

        //whatever is left over from the last trip goes home first; what it is mining by hand, when it has a full load
        if(unit.hasItem() && (ai.dig == null || ai.full())){
            ai.stopDigging();
            ai.haul = new Haul(null, home, unit.item());
            return;
        }

        //deciding what to build next is not a job for anyone in particular
        plan(data);

        //with the enemy building by itself, its builders have only the carrying and the mining to do
        Order order = Options.hands.on() ? null : nextOrder(data);
        Haul spare = order == null && Options.haul.on() ? surplus(data, unit, home) : null;
        if(order != null || spare != null){
            //real work: the mining stops, and what was mined so far is taken home on the way
            ai.stopDigging();
            if(unit.hasItem()){
                ai.haul = new Haul(null, home, unit.item());
                return;
            }
            if(order != null){
                ai.order = order;
                orders.add(order);
                stock.hold(order.cost);
                //and the blocks that come after it, as many as a player might lay down in one go: the builder puts them up one
                //after the other without waiting to be told, held back only by how fast it builds and how far it reaches
                for(int i = 1; i < stretch; i++){
                    Order next = nextOrder(data);
                    if(next == null) break;
                    ai.more.addLast(next);
                    orders.add(next);
                    stock.hold(next.cost);
                }
            }else{
                ai.haul = spare;
            }
            return;
        }
        //with nothing to build and nothing to carry: what of its own is hurt is mended first, where that can be done in peace
        Building hurt = canRepair(unit) ? repairTarget(unit, home) : null;
        if(hurt != null){
            ai.stopDigging();
            if(ai.repair != hurt) repairs++;
            ai.repair = hurt;
            tasks.post(Task.Kind.repair, atlas.zoneAt(hurt.tileX(), hurt.tileY()), null, total(hurt.block.requirements) * (1f - hurt.healthf()), 0f, hurt.block.name + " at " + hurt.tileX() + "," + hurt.tileY(), clock);
            return;
        }
        if(ai.repair != null) return;
        //then mining by hand like a player's own unit.
        //what it mines is looked at again as it goes: near a core the load goes in without a trip, so it would never stop to think
        if(ai.dig == null || stock.have(ai.dig.item) >= stock.cap() || short_(ai.dig.item) > 0 || scarcer(unit, ai.dig.item)){
            ExpansionAI.Dig next = dig(unit, home);
            if(next == null || ai.dig == null || next.item != ai.dig.item){
                ai.stopDigging();
                ai.dig = next;
            }
        }
    }

    /** How many times a builder was sent to mend something, since the map began. */
    public int repairs;

    /** What was last noted about a goal, for looking into how it decides. */
    public String note(String key){
        //"name*": every note whose key begins so, one to a line
        if(key.endsWith("*")){
            String prefix = key.substring(0, key.length() - 1);
            StringBuilder out = new StringBuilder();
            for(var entry : notes){
                if(entry.key.startsWith(prefix)) out.append("\n             ").append(entry.key).append(": ").append(entry.value);
            }
            return out.length() == 0 ? "-" : out.toString();
        }
        String found = notes.get(key);
        return found == null ? "-" : found;
    }

    /** Whether a unit has a beam that mends buildings. */
    private static boolean canRepair(Unit unit){
        return unit.type.weapons.contains(weapon -> weapon instanceof mindustry.type.weapons.RepairBeamWeapon beam && beam.targetBuildings);
    }

    /**
     * The building of its own most worth mending now: hurt, not hit for a while, with none of the other side's units
     * near it, not too far from home; the most of what it cost that is missing, the nearest first.
     */
    private @Nullable Building repairTarget(Unit unit, CoreBuild home){
        Building best = null;
        float bestScore = 0f, far = tilesize * 90f;
        for(Building build : team().data().buildings){
            if(!build.damaged() || build.healthf() > 0.97f || build.wasRecentlyDamaged() || build instanceof ConstructBuild || !build.within(home, far)) continue;
            float score = total(build.block.requirements) * (1f - build.healthf()) / (1f + unit.dst(build) / (tilesize * 20f));
            if(score <= bestScore || threat(build.x, build.y, quiet) != null) continue;
            best = build;
            bestScore = score;
        }
        return best;
    }

    /** How pressing an item is for mining by hand: what is being saved up for and is short comes first. */
    private int short_(Item item){
        Block wanted = aim();
        if(wanted != null){
            for(ItemStack stack : cost(wanted)){
                if(stack.item == item && stock.free(item) < stack.amount) return 0;
            }
        }
        return 1;
    }

    /** Whether there is another ore it could be mining by hand that it has a good deal less of. */
    private boolean scarcer(Unit unit, Item than){
        for(Item ore : survey.ores()){
            if(ore != than && unit.canMine(ore) && ore.buildable && (short_(ore) < short_(than) || stock.have(ore) + 150 < stock.have(than))) return true;
        }
        return false;
    }

    /**
     * A wall to mine by hand: of the ores the unit can mine and the core has room for, the one there
     * is least of, at the nearest place to the core on the enemy's own ground.
     */
    private @Nullable ExpansionAI.Dig dig(Unit unit, CoreBuild home){
        if(!Options.handMine.on() || !unit.canMine() || !unit.type.mineWalls || territoryCores == -1 || !survey.ready()) return null;
        //the ores it can get at, the one the cores hold least of first
        Seq<Item> ores = survey.ores().select(ore -> unit.canMine(ore) && ore.buildable && !survey.ore(ore).isEmpty() && stock.have(ore) < stock.cap());
        ores.sort(ore -> short_(ore) * 100000f + stock.have(ore));

        Tile best = null;
        Item item = null;
        for(Item ore : ores){
            IntSeq faces = survey.ore(ore);
            float bestFar = Float.MAX_VALUE;
            for(int i = 0; i < faces.size; i++){
                int face = faces.get(i), x = survey.x(face), y = survey.y(face), d = Survey.direction(face);
                float far = Mathf.dst2(x, y, home.tileX(), home.tileY());
                if(far >= bestFar || !territory.ours(x, y) || !workable(x, y)) continue;
                Tile wall = world.tile(x + Geometry.d4x(d), y + Geometry.d4y(d));
                if(wall == null || unit.getMineResult(wall) != ore) continue;
                best = wall;
                bestFar = far;
            }
            if(best != null){
                item = ore;
                break;
            }
        }
        return best == null ? null : new ExpansionAI.Dig(best, item);
    }

    /**
     * The next block of the build queue that can be reached and paid for. The queue holds what was
     * destroyed and what has been laid out, in that order: the game puts destroyed blocks at the front.
     */
    private @Nullable Order nextOrder(TeamData data){
        Queue<BlockPlan> plans = data.plans;

        //what is in the way comes down before anything goes up
        for(int i = removals.size - 1; i >= 0; i--){
            int pos = removals.get(i);
            Building wall = world.build(pos);
            if(wall == null || wall.team != team() || wall.pos() != pos || wall.block instanceof CoreBlock){
                removals.removeIndex(i);
                continue;
            }
            if(orders.contains(o -> o.pos() == pos)) continue;
            Order order = new Order(wall.block, wall.tileX(), wall.tileY(), wall.rotation, null, ItemStack.empty, true);
            order.breaking = true;
            Vec2 stand = stand(wall.x, wall.y, data, reach, Tmp.v2);
            order.standX = stand.x;
            order.standY = stand.y;
            if(calm(stand.x, stand.y, quiet)) return order;
        }

        //what it laid out to hold an attack off with comes before the rest of the queue, wherever in it that stands; then
        //first sources of what it can't do without
        for(int pass = 0; pass < 3; pass++){
        IntSet only = pass == 0 ? urgent : pass == 1 ? firsts : null;
        if(only != null && only.isEmpty()) continue;
        for(int i = 0, count = only != null ? plans.size : Math.min(plans.size, 40); i < count; i++){
            BlockPlan plan = plans.removeFirst();
            Tile tile = world.tile(plan.x, plan.y);
            int pos = Point2.pack(plan.x, plan.y);
            if(only != null && !only.contains(pos)){
                plans.addLast(plan);
                continue;
            }
            //it is standing
            if(tile == null || standing(tile, plan)){
                stuck.remove(pos, 0f);
                continue;
            }
            boolean expansion = laid.contains(pos);
            if(expansion && givenUp(plan, pos)) continue;
            //to the back of the queue, so that the next builder tries something else
            plans.addLast(plan);

            //playing the player's side, what the map's own base lost is put back only as far as it has been researched, as the game has it for a player
            if(orders.contains(o -> o.pos() == pos) || (!expansion && (!allowed(plan.block) || (side != null && !may(plan.block)))) || !Build.validPlace(plan.block, team(), plan.x, plan.y, plan.rotation)) continue;

            ItemStack[] cost = cost(plan.block);
            Order order = new Order(plan.block, plan.x, plan.y, plan.rotation, plan.config, cost, expansion);
            Vec2 stand = stand(order.x * tilesize + order.block.offset, order.y * tilesize + order.block.offset, data, reach, Tmp.v2);
            order.standX = stand.x;
            order.standY = stand.y;
            //not while the player's army is at the place itself, or where the builder would stand: a builder can reach a long
            //way, and one that put back from a distance what the army beside it had just destroyed would make the destroying pointless
            if(!calm(stand.x, stand.y, quiet) || !calm(order.x * tilesize + order.block.offset, order.y * tilesize + order.block.offset, quiet)){
                //what the player keeps the builders away from for long enough is struck off like what can't be built at all
                if(expansion) keptAway(plans, plan, pos);
                continue;
            }
            away.remove(pos, 0f);

            if(heldBack(plan.block, cost, plan.x, plan.y)) continue;
            if(stock.covers(cost)) return order;
            waitFor(plan.block);
        }
        }
        return null;
    }

    /**
     * Whether a block waits its turn because it is being attacked. Under attack with little in its
     * cores, what was laid out earlier, and what the attack destroys and the builders put back, would
     * otherwise eat up, a duct at a time, what the turret that could save the base is waiting for. So
     * nothing is paid for out of what its defence still needs; and what fell is not put back where no
     * turret of its own has the place under its guns: it would only fall again.
     */
    private boolean heldBack(Block block, ItemStack[] cost, int x, int y){
        int pos = Point2.pack(x, y);
        //(what fell is put back as soon as a builder can get there with the other side's army gone from the place, as the
        //player's would be: holding it back longer, until a turret stood over it, left a line the player had merely cut
        //down for a minute and a half)
        if(block instanceof Turret || urgent.contains(pos) || (!attacked() && firsts.contains(pos))) return false;
        for(ItemStack stack : cost){
            int need = guardNeed(stack.item);
            if(need > 0 && stock.have(stack.item) - stack.amount < need) return true;
        }
        return false;
    }

    /** The game does not let a team's builders put up blocks its campaign has not unlocked. Rebuilding stays within that. */
    private static boolean allowed(Block block){
        return (state.rules.waves && block.isVisible()) || (block.unlockedNowHost() && block.environmentBuildable() && block.isPlaceable());
    }

    private void keptAway(Queue<BlockPlan> plans, BlockPlan plan, int pos){
        float since = away.get(pos, -1f);
        if(since < 0f){
            away.put(pos, clock);
        }else if(clock - since > avoidTime){
            away.remove(pos, 0f);
            built.removeValue(pos);
            laid.remove(pos);
            plans.remove(plan);
        }
    }

    /** A block that was laid out where nothing can be built any more is waited for a while, then struck off. */
    private boolean givenUp(BlockPlan plan, int pos){
        if(Build.validPlaceIgnoreUnits(plan.block, team(), plan.x, plan.y, plan.rotation, true, true)){
            stuck.remove(pos, 0f);
            return false;
        }
        float since = stuck.get(pos, -1f);
        if(since < 0f){
            stuck.put(pos, clock);
            return false;
        }
        //a builder may yet find the place free when it gets there. building by itself there is nothing to wait for:
        //the line or whatever it was part of is led another way
        if(clock - since < (Options.hands.on() ? blockedTime : avoidTime)) return false;

        stuck.remove(pos, 0f);
        built.removeValue(pos);
        laid.remove(pos);
        avoid.put(pos, clock + avoidTime);
        nextPlan = Math.min(nextPlan, clock + 30f);
        return true;
    }

    //endregion
    //region building by itself

    /** Blocks it may still put up this moment. So many a second are added, whatever else is going on. */
    private float handBudget;
    private boolean buildTurn;
    /** Whether the player's guns are off a square of the map, as found a moment ago: asked for every block of the queue. */
    private final IntIntMap safeNow = new IntIntMap();
    private @Nullable Unit hand;

    /**
     * The game wants a unit behind every construction site. This one is never put on the map: it only
     * lends its name to what the enemy builds by itself.
     */
    private Unit hand(){
        if(hand == null || hand.team != team()){
            CoreBuild core = team().core();
            hand = (core != null ? ((CoreBlock)core.block).unitType : UnitTypes.evoke).create(team());
        }
        return hand;
    }

    /**
     * Puts up what is waiting in the build queue, as far as the core can pay for it and the player's
     * guns allow. Each block goes up the way a builder would put it up, as a construction site that
     * takes its cost out of the core, only finished in the same moment it is begun.
     */
    private void build(TeamData data){
        long prof = Prof.start();
        int rate = Options.buildRate.get();
        handBudget = Math.min(handBudget + rate * thinkPeriod * 2f / 60f, rate);
        safeNow.clear();
        CoreBuild core = data.core();
        if(core == null) return;

        //what is in the way comes down before anything goes up
        for(int i = removals.size - 1; i >= 0 && handBudget >= 1f; i--){
            int pos = removals.get(i);
            Building wall = world.build(pos);
            if(wall == null || wall.team != team() || wall.pos() != pos || wall.block instanceof CoreBlock){
                removals.removeIndex(i);
                continue;
            }
            if(!safe(wall.x, wall.y)) continue;
            Call.beginBreak(null, team(), wall.tileX(), wall.tileY());
            //what it was made of goes back into the core
            if(wall.tile.build instanceof ConstructBuild site) site.deconstruct(hand(), core, 1f);
            handBudget -= 1f;
        }

        Queue<BlockPlan> plans = data.plans;
        //so many of the queue are looked at each time, and go to the back of it: the order they go up in stays the order they were laid out in
        for(int i = 0, count = Math.min(plans.size, 160); i < count && handBudget >= 1f; i++){
            BlockPlan plan = plans.removeFirst();
            Tile tile = world.tile(plan.x, plan.y);
            int pos = Point2.pack(plan.x, plan.y);
            //it is standing
            if(tile == null || standing(tile, plan)){
                stuck.remove(pos, 0f);
                continue;
            }
            boolean expansion = laid.contains(pos);
            if(expansion && givenUp(plan, pos)) continue;
            plans.addLast(plan);

            boolean begun = tile.build instanceof ConstructBuild site && site.current == plan.block && tile.team() == team();
            if((!expansion && !allowed(plan.block)) || (!begun && !Build.validPlace(plan.block, team(), plan.x, plan.y, plan.rotation))) continue;
            if(!safe(plan.x * tilesize + plan.block.offset, plan.y * tilesize + plan.block.offset)){
                //what the player's army stands on for long enough is struck off like what can't be built at all
                if(expansion) keptAway(plans, plan, pos);
                continue;
            }
            away.remove(pos, 0f);

            if(!stock.covers(cost(plan.block))){
                waitFor(plan.block);
                continue;
            }
            if(put(plan, tile, core, begun)){
                handBudget -= 1f;
                if(expansion && plan.block instanceof Turret) ExpansionMod.announce("expansion.outpost");
                if(expansion && plan.block instanceof CoreBlock) ExpansionMod.announce("expansion.core.done");
            }
        }
        Prof.end("build", prof);
    }

    /** Whether what a plan asks for is there already: the same block, facing the same way. A duct that was laid out afresh to face another way is not. */
    static boolean standing(Tile tile, BlockPlan plan){
        return tile.block() == plan.block && tile.team() == team() && (!plan.block.rotate || tile.build == null || tile.build.rotation == plan.rotation);
    }

    /** One block, begun and finished at once. @return whether it stands */
    private boolean put(BlockPlan plan, Tile tile, CoreBuild core, boolean begun){
        if(!begun) Call.beginPlace(null, plan.block, team(), plan.x, plan.y, plan.rotation, plan.config);
        //the site takes out of the core what the block costs, all of it now; the core was seen to hold that much
        if(tile.build instanceof ConstructBuild site && site.current == plan.block && tile.team() == team()) site.construct(hand(), core, 1f, plan.config);
        return standing(tile, plan);
    }

    /**
     * Whether a place is out of the way of the player's guns just now: outside the range of its
     * turrets, and with none of the units it fights with close by.
     */
    private boolean safe(float x, float y){
        int tx = (int)(x / tilesize), ty = (int)(y / tilesize);
        if(!workable(tx, ty)) return false;
        //looked into once for each small square of ground: what is built together stands together
        int cell = Point2.pack(tx / 4, ty / 4), known = safeNow.get(cell, -1);
        if(known == -1) safeNow.put(cell, known = quiet <= 0f || calm((tx / 4 * 4 + 2) * tilesize, (ty / 4 * 4 + 2) * tilesize, quiet + tilesize * 2f) ? 1 : 0);
        return known == 1;
    }

    /** Leaves a place alone for a while after an attempt to put something there came to nothing. */
    void shun(int x, int y){
        shunned.put(Point2.pack(x, y), clock + shunTime);
    }

    void unshun(int x, int y){
        shunned.remove(Point2.pack(x, y), 0f);
    }

    /** Places where an attempt to put something came to nothing, and until when nothing more is tried there. A line may still pass over them. */
    private final IntFloatMap shunned = new IntFloatMap();

    private void waitFor(Block block){
        aim = block;
        aimClock = clock;
    }

    /**
     * Unit yards stop once the enemy has as many of a unit as its cores allow, counted the way it is
     * for the player. The game itself sets the enemy no limit.
     */
    private void holdYards(TeamData data){
        int cap = unitCap(data);
        for(Net.Thing thing : net.things){
            if(thing.kind != Net.Kind.yard || !(world.build(thing.x, thing.y) instanceof UnitFactory.UnitFactoryBuild yard) || yard.team != team()) continue;
            UnitFactory type = (UnitFactory)yard.block;
            if(type.plans.isEmpty()) continue;
            //what counts is the unit that comes out at the end: a refabricator in front turns it into another
            UnitType unit = type.plans.first().unit;
            Building next = yard.front();
            for(int step = 0; step < 4 && next != null && next.team == team() && next.block instanceof Reconstructor rebuilder; step++){
                UnitType before = unit;
                for(UnitType[] upgrade : rebuilder.upgrades){
                    if(upgrade[0] == unit){
                        unit = upgrade[1];
                        break;
                    }
                }
                if(unit == before) break;
                next = next.front();
            }
            yard.enabled = data.countType(unit) < cap;
        }
    }


    /** How many it has of the kind of fighting unit it has most of. */
    private static int mostOfAKind(TeamData data){
        ObjectIntMap<UnitType> counts = new ObjectIntMap<>();
        int most = 0;
        for(Unit unit : data.units){
            if(!fights(unit)) continue;
            counts.increment(unit.type);
            most = Math.max(most, counts.get(unit.type, 0));
        }
        return most;
    }

    /** How many units of a kind the enemy may have: a base amount, and more for every core, as for the player; times what the player has set. */
    public static int unitCap(TeamData data){
        int same = Math.max(state.rules.unitCapVariable ? state.rules.unitCap + data.unitCap : state.rules.unitCap, 0);
        //an enemy with many cores would by that rule be allowed far more than a game runs well with
        return Math.min(Math.round(same * Options.unitCap.fraction()), Options.unitMost.get());
    }

    /** Output that is piling up in one of the base's own factories or drills, to be carried to the core. */
    private @Nullable Haul surplus(TeamData data, Unit unit, CoreBuild home){
        ItemStack[] wanted = aim() == null ? ItemStack.empty : cost(aim());
        boolean burns = ((CoreBlock)home.block).incinerateNonBuildable;
        Building best = null;
        Item bestItem = null;
        float bestScore = 0f;

        for(Building build : data.buildings){
            if(build.items == null || !build.enabled) continue;
            Item item = spare(build);
            //a core that burns what nothing can be built from has no use for it
            if(item == null || (burns && !item.buildable)) continue;

            float score = need(wanted, item) * build.items.get(item) / (1f + unit.dst(build) / 800f);
            if(score > bestScore && world.isInMapArea(build.tileX(), build.tileY()) && !hauled(data, build) && calm(build.x, build.y, quiet)){
                best = build;
                bestItem = item;
                bestScore = score;
            }
        }
        return best == null ? null : new Haul(best, home, bestItem);
    }

    /**
     * Only output that has nowhere to go is taken: a full factory or drill has stopped working, so
     * emptying it sets it going again and takes nothing away from what the base was already using.
     */
    private static @Nullable Item spare(Building build){
        Block block = build.block;
        if(block instanceof GenericCrafter crafter){
            if(crafter.outputItems != null){
                for(ItemStack out : crafter.outputItems){
                    if(build.items.get(out.item) + out.amount > block.itemCapacity) return out.item;
                }
            }
        }else if(block instanceof Drill || block instanceof BeamDrill){
            //one with a line holds next to nothing. one that is half full has nowhere to put what it mines; and a drill
            //that brings its ore up in bursts stops before it is quite full, when the next burst would not fit
            if(build.items.total() * 2 >= block.itemCapacity) return build.items.first();
        }
        return null;
    }

    private float need(ItemStack[] wanted, Item item){
        for(ItemStack stack : wanted){
            if(stack.item == item && stock.free(item) < stack.amount) return 3f;
        }
        //nothing is carried home that the cores have no room for
        return stock.have(item) < stock.cap() ? 1f : 0f;
    }

    /** Whether a builder is already on its way to fetch from this building. */
    private boolean hauled(TeamData data, Building build){
        return data.units.contains(u -> u.controller() instanceof ExpansionAI ai && ai.haul != null && ai.haul.from == build);
    }

    //endregion
    //region orders

    /** Whether an order that has not been started still can be. Units standing in the way do not count: they move on. */
    boolean valid(Order order){
        if(order.breaking) return order.tile() != null && order.tile().build != null && order.tile().team() == team();
        return Build.validPlaceIgnoreUnits(order.block, team(), order.x, order.y, order.rotation, true, true);
    }

    /** Puts down the construction site. From here on the game takes what the block costs out of the core as it goes up. */
    boolean begin(Order order, Unit unit){
        Tile tile = order.tile();
        Call.beginPlace(unit, order.block, unit.team, order.x, order.y, order.rotation, order.config);
        boolean standing = tile.build instanceof ConstructBuild site ? site.current == order.block : tile.block() == order.block && tile.team() == unit.team;
        if(!standing) return false;

        order.begun = true;
        if(order.expansion && order.block instanceof CoreBlock) ExpansionMod.announce("expansion.core.begin");
        return true;
    }

    /** The order is over, one way or another. Whatever is not standing is still in the build queue, to be tried again. */
    void closed(Order order){
        orders.remove(order, true);
        if(order.built && order.expansion){
            if(order.block instanceof Turret) ExpansionMod.announce("expansion.outpost");
            if(order.block instanceof CoreBlock) ExpansionMod.announce("expansion.core.done");
        }
    }

    /** A builder is at the source: takes on the load. */
    boolean load(Haul haul, Unit unit){
        Item item = haul.item;
        int amount = haul.from.items == null ? 0 : haul.from.items.get(item);
        if(amount <= 0) return false;
        Call.takeItems(haul.from, item, amount, unit);
        //anything else within arm's reach that is full of the same goes along, such as the other drills of a mine
        indexer.eachBlock(unit.team, unit.x, unit.y, itemTransferRange, other -> other != haul.from && other.items != null && other.enabled && spare(other) == item, other -> {
            if(unit.maxAccepted(item) > 0) Call.takeItems(other, item, other.items.get(item), unit);
        });
        return unit.hasItem();
    }

    /** A builder is at the core: hands over the load. What arrives there is counted on the way in, like any other delivery. */
    void unload(Haul haul, Unit unit){
        //into a core and nothing else: Erekir lets a unit put what it carries nowhere but there (the game's own rule,
        //onlyDepositCore), so nothing is fed by hand. a yard or a turret its lines do not reach goes without
        if(unit.hasItem() && haul.to instanceof CoreBuild) Call.transferItemTo(unit, unit.item(), unit.stack.amount, unit.x, unit.y, haul.to);
    }

    //endregion
    //region planning

    /**
     * Decides on the next thing to build, once the last one is up, and lays it out.
     * Nothing is paid for here. Each block is paid for as it is built.
     */
    private void plan(TeamData data){
        if(clock < nextPlan) return;
        //with several cores each has its turn at deciding what goes up around it, and each as often as a single one would:
        //its bases grow side by side. what they build with is one stock, and a line of one may run into a line of another
        Seq<CoreBuild> homes = data.cores.select(core -> world.isInMapArea(core.tileX(), core.tileY()));
        nextPlan = clock + Options.planInterval.get() * 60f / Math.max(homes.size, 1);
        territory.focus = homes.size > 1 ? homes.get(coreTurn++ % homes.size) : null;

        Seq<CoreBuild> targets = foe().cores();
        //with no core left to the other side the enemy has nothing more to do. playing the player's side it may be a map
        //where there are only waves to hold out against: there it builds its base up all the same
        if(targets.isEmpty() && side == null) return;

        //the clock for this round runs from here: getting the picture of the map up to date is part of what the round takes.
        //when round after round runs out of time with nothing to show, each gets a little longer: a search that is
        //cut short starts again from nothing, and on a slow machine or a big map could otherwise never get to its end
        planner.timeLimit = Options.timeLimit.get() * (1f + Math.min(lateRounds, 4) * 0.25f);
        net.fresh();
        planner.begin();

        //whose ground is whose changes when a core is won or lost or the map opens up, and is looked at afresh now and then
        if(clock >= nextTerritory || territoryCores != data.cores.size * 1000 + targets.size){
            tools();
            territory.update(data.cores.select(core -> world.isInMapArea(core.tileX(), core.tileY())), targets);
            nextTerritory = clock + territoryPeriod;
            territoryCores = data.cores.size * 1000 + targets.size;
            markCorridor(data);
        }
        if(!survey.ready()) survey.scan();
        //the world model, cut into zones once a map
        if(!atlas.ready() && survey.ready()) atlas.build(survey);
        if(!ventsKnown){
            ventsKnown = true;
            ground.vent.clear();
            for(Tile tile : world.tiles){
                if(tile.floor().attributes.get(mindustry.world.meta.Attribute.steam) > 0f) ground.vent.add(tile.pos());
            }
        }
        //ore in the floor that it has no source of stays clear of everything but the drill for it
        ground.ore.clear();
        ground.lane.clear();
        for(Item item : content.items()){
            IntSeq floor = survey.floor(item);
            if(floor.isEmpty() || hasSource(item)) continue;
            IntSet left = new IntSet();
            for(int i = 0; i < floor.size; i++){
                ground.ore.add(floor.get(i));
                left.add(floor.get(i));
            }
            //the ore lies in patches. from each patch big enough for a drill, the two nearest home, a way home is kept
            //open: from its tile nearest a core, down the slope of how far each tile is from a core over open ground
            Seq<int[]> patches = new Seq<>();
            IntSeq open = new IntSeq();
            while(!left.isEmpty()){
                open.clear();
                open.add(left.first());
                int size = 0, nearest = -1, least = Integer.MAX_VALUE;
                while(open.size > 0){
                    int pos = open.pop();
                    if(!left.remove(pos)) continue;
                    size++;
                    int px = Point2.x(pos), py = Point2.y(pos), far = territory.ours(px, py) ? territory.steps(px, py) : -1;
                    if(far >= 0 && far < least){
                        least = far;
                        nearest = pos;
                    }
                    for(int dx = -2; dx <= 2; dx++){
                        for(int dy = -2; dy <= 2; dy++){
                            if(left.contains(Point2.pack(px + dx, py + dy))) open.add(Point2.pack(px + dx, py + dy));
                        }
                    }
                }
                if(size >= 6 && nearest != -1) patches.add(new int[]{nearest, least});
            }
            patches.sort(patch -> patch[1]);
            for(int p = 0; p < Math.min(patches.size, 2); p++){
                int x = Point2.x(patches.get(p)[0]), y = Point2.y(patches.get(p)[0]);
                for(int step = 0; step < 600 && territory.steps(x, y) > 1; step++){
                    int bestX = x, bestY = y, best = territory.steps(x, y);
                    for(int d = 0; d < 4; d++){
                        int nx = x + Geometry.d4x(d), ny = y + Geometry.d4y(d), far = territory.steps(nx, ny);
                        if(far >= 0 && far < best){
                            best = far;
                            bestX = nx;
                            bestY = ny;
                        }
                    }
                    if(bestX == x && bestY == y) break;
                    x = bestX;
                    y = bestY;
                    //three tiles wide: a duct can only be laid where nothing beside it would push things into it, and with
                    //the tiles at its sides kept clear as well there is always such a way down the middle
                    ground.lane.add(Point2.pack(x, y));
                    for(int d = 0; d < 4; d++) ground.lane.add(Point2.pack(x + Geometry.d4x(d), y + Geometry.d4y(d)));
                }
            }
        }
        markDanger();
        //what it has built since the last look carries its ground forward
        if(clock >= nextClaim){
            nextClaim = clock + 60f * 15f;
            territory.creep = Options.creep.on();
            territory.hold = Options.hold.get();
            territory.claim(data.buildings);
            markCorridor(data);
        }

        //the game holds the enemy's factories back at the start of a map. expansion waits at least as long
        if(state.tick < Math.max(state.rules.unitActivationDelay(team()), Options.grace.get() * 60f * 60f)) return;
        //a base that came with the map is watched for a little before anything is decided
        if(!looked){
            //nothing has been laid out yet: what stands is what the map gave it
            looked = true;
            inherited = data.buildings.size > data.cores.size;
        }
        if(inherited && clock < lookTime) return;
        if(clock < settleTime && Options.baseLines.on()) foresee(data);
        //with something still to be built nothing new is decided on. being attacked is the exception: what would hold
        //the attack off does not wait for what was laid out before, only for a turret that is already on its way up
        boolean busy = busy(data);
        if(busy && (!attacked() || defending(data))) return;
        prune();
        syncGround();
        spots.clear();
        nearness.clear();
        seenLines.clear();
        staplesNow = null;
        sparedNow.clear();

        if(!tools()) return;

        lines.allowance = searchAllowance;
        long began = System.nanoTime();
        Project project = busy ? guard(data, targets) : choose(data, targets);
        Prof.end("all", began);
        float took = (System.nanoTime() - began) / 1e6f;
        if(took > slowestPlan){
            slowestPlan = took;
            slowestWhat = project != null ? project.name : planner.note;
        }
        lateRounds = project == null && planner.timedOut ? lateRounds + 1 : 0;
        //something would have gone on ground it has not seen: a builder is sent to look at it
        if(fitter.unseenAt != -1){
            scout = fitter.unseenAt;
            //with nothing of its own that fights to send, it can only be uncovered from where it stands
            if(!data.units.contains(Brain::fights)) revealAt = scout;
        }
        //a round that found nothing to lay out and nothing to save for, with time to spare, is one with nothing to do
        if(!busy) director.idle = project == null && !saving && !planner.timedOut ? director.idle + 1 : project != null ? 0 : director.idle;
        if(project == null) return;
        if(project.urgent || project.first){
            IntSet into = project.urgent ? urgent : firsts;
            for(Blueprint.Part part : project.parts) into.add(Point2.pack(part.x, part.y));
            for(Blueprint.Part part : project.last) into.add(Point2.pack(part.x, part.y));
        }
        assets.addAll(project.assets);
        if(project.retire.size > 0 && project.turn != null){
            Planner.Retire old = new Planner.Retire();
            old.x = project.turn.x;
            old.y = project.turn.y;
            old.rotation = project.turn.rotation;
            old.block = project.turn.block;
            old.tiles.addAll(project.retire);
            old.until = clock + 60f * 60f * 10f;
            retiringLines.add(old);
        }
        if(project.boosts != null && project.boostWith != null) project.boosts.boosted |= 1 << project.boostWith.id;
        if(project.boosts != null && project.armWith != null) project.boosts.armed = project.armWith.id;
        net.things.removeAll(project.forget, true);

        net.fresh();
        removals.addAll(project.removals);
        //to the front of the queue, in the order it is to go up: what the queue holds besides, such as what the map's
        //own base lost and can't be put back yet, does not hold it up
        for(int i = project.last.size - 1; i >= 0; i--) queue(data, project.last.get(i));
        for(int i = project.parts.size - 1; i >= 0; i--) queue(data, project.parts.get(i));
        net.things.addAll(project.things);
        started++;
        savingSince = -1f;
        //what went into it, for what it is building a second
        ItemSeq laidCost = new ItemSeq();
        for(Blueprint.Part part : project.parts) laidCost.add(part.block.requirements);
        for(Blueprint.Part part : project.last) laidCost.add(part.block.requirements);
        building.add(laidCost.toArray());
        buildingAt.add(clock);
        buildingMine.add(lastGoal == Director.Goal.ore || project.assets.contains(asset -> asset.kind == Net.Kind.mine));
        //what it was for, and where, goes into the account: and every block of it, so that what becomes of them can be told
        if(lastGoal != null){
            Blueprint.Part where = !project.assets.isEmpty() ? null : project.parts.isEmpty() ? (project.last.isEmpty() ? null : project.last.first()) : project.parts.first();
            int zx = !project.assets.isEmpty() ? project.assets.first().x : where != null ? where.x : -1, zy = !project.assets.isEmpty() ? project.assets.first().y : where != null ? where.y : -1;
            long key = Task.key(kindOf(lastGoal), atlas.zoneAt(zx, zy), null);
            ledger.laid(key, clock);
            for(Blueprint.Part part : project.parts) tileTask.put(Point2.pack(part.x, part.y), key);
            for(Blueprint.Part part : project.last) tileTask.put(Point2.pack(part.x, part.y), key);
        }
        Log.info("[EnemyExpansion] Laid out: @ (@ blocks) for @@, @, at @:@.@", project.name, project.parts.size + project.last.size, lastGoal, project.urgent ? ", at once" : "", director.posture, (int)(clock / 3600f), Strings.fixed(clock / 60f % 60f, 0),
            side != null ? " [" + (attacked() ? "attacked, " : "") + "weights " + java.util.Arrays.toString(director.weights) + ", kept back " + (guardNeed(Items.beryllium) + "be/" + guardNeed(Items.silicon) + "si") + "; foresight: ready in " + java.util.Arrays.toString(forecast.outcome).replaceAll("\\.[0-9]+", "") + " s with " + (int)forecast.needed + " units; under fire " + (int)exposure + "/s, covered by " + (int)coverage + "/s, losing " + Strings.fixed(lossRate, 1) + "/s]" : "");
    }

    /**
     * Forgets what is gone for good. A mine, a yard or a turret the player destroyed is rebuilt like
     * anything else; but where the rebuilding was given up, because the player holds the place now,
     * it no longer counts as one the enemy has, and another may be built somewhere else.
     */
    private void prune(){
        if(net.things.isEmpty()) return;
        syncGround();
        net.things.removeAll(thing -> !ground.look(thing.x, thing.y));
        assets.removeAll(asset -> !ground.look(asset.x, asset.y));
    }

    /**
     * What the enemy lays its lines and its power with: what the player has too.
     * @return whether it has the two things nothing can be done without
     */
    private boolean tools(){
        lines.duct = Blocks.duct;
        power.node = Blocks.beamNode;
        fitter.avoid = (x, y) -> avoided(Task.Kind.mine, atlas.zoneAt(x, y));
        pipes.liquid = true;
        pipes.duct = may(Blocks.reinforcedConduit) ? Blocks.reinforcedConduit : null;
        pipes.bridge = may(Blocks.reinforcedBridgeConduit) ? Blocks.reinforcedBridgeConduit : null;
        lines.bridge = may(Blocks.ductBridge) ? Blocks.ductBridge : null;
        lines.router = may(Blocks.ductRouter) ? Blocks.ductRouter : null;
        lines.underflow = may(Blocks.underflowDuct) ? Blocks.underflowDuct : null;
        lines.walls = Options.openWalls.on();
        liquidRouter = may(Blocks.reinforcedLiquidRouter) ? Blocks.reinforcedLiquidRouter : null;
        power.generator = may(Blocks.turbineCondenser) ? Blocks.turbineCondenser : null;
        power.overload = Options.overload.fraction();
        power.starved = Math.max(Options.starved.fraction(), power.overload);
        power.hops = Options.powerChain.get();
        planner.timeLimit = Options.timeLimit.get();
        territory.frontier = Options.frontier.fraction();
        territory.creep = Options.creep.on();
        territory.hold = Options.hold.get();
        return may(Blocks.duct) && may(Blocks.beamNode);
    }

    /** Whether the enemy may build something: anything, if the player has lifted the limit; else what {@link #unlocked} allows. */
    boolean may(UnlockableContent content){
        return !Options.techLimit.on() || unlocked.get(content);
    }

    /**
     * Why nothing solid may go on a tile, as one letter, or a space where it may. For looking into how the enemy behaves.
     * b built on, w wall or deep water, k kept clear, p planned, o ore kept for a drill, l way home from ore, t not its
     * ground, s where scripts do things, a given up for now, h shunned for now, g under the other side's guns,
     * c on the way its units take, z core zone, n too near a core, m outside the open part of the map.
     */
    public char whyNot(int x, int y){
        Tile tile = world.tile(x, y);
        int pos = Point2.pack(x, y);
        if(tile == null || !world.isInMapArea(x, y)) return 'm';
        if(tile.build != null) return 'b';
        if(tile.solid() || tile.floor().isDeep() || !tile.floor().placeableOn) return 'w';
        if(ground.planned(x, y) != null) return 'p';
        if(ground.kept.contains(pos)) return 'k';
        if(ground.ore.contains(pos)) return 'o';
        if(ground.lane.contains(pos)) return 'l';
        if(!territory.ours(x, y)) return 't';
        if(scripted.contains(pos)) return 's';
        if(avoid.get(pos, 0f) > clock) return 'a';
        if(shunned.get(pos, 0f) > clock) return 'h';
        if(!workable(x, y)) return 'g';
        if(corridor.contains(pos)) return 'c';
        if(tile.floor().allowCorePlacement) return 'z';
        if(byCore(x, y)) return 'n';
        return ' ';
    }

    /** Why a block in the build queue has not been built yet. For looking into how the enemy behaves. */
    public String whyWaiting(BlockPlan plan){
        TeamData data = team().data();
        int pos = Point2.pack(plan.x, plan.y);
        Vec2 stand = stand(plan.x * tilesize + plan.block.offset, plan.y * tilesize + plan.block.offset, data, reach, new Vec2());
        int builders = data.units.count(u -> u.controller() instanceof ExpansionAI), idle = data.units.count(u -> u.controller() instanceof ExpansionAI ai && ai.idle());
        return (laid.contains(pos) ? "its own" : "the map's") + (orders.contains(o -> o.pos() == pos) ? ", a builder is on it" : "")
            + (Build.validPlace(plan.block, team(), plan.x, plan.y, plan.rotation) ? "" : ", the game does not allow it now")
            + (calm(stand.x, stand.y, quiet) ? "" : ", the player is near where a builder would stand")
            + (stock.covers(cost(plan.block)) ? "" : ", can't be paid for")
            + (stuck.get(pos, -1f) >= 0f ? ", has been in the way of something since " + (int)(stuck.get(pos, 0f) / 3600f) + " min" : "")
            + "; " + builders + " builders, " + idle + " with nothing to do, " + orders.size + " orders out, " + removals.size + " walls to come down";
    }

    /** Works one thing out without laying it out, and says how it went. For looking into how the enemy behaves. */
    public String tryOut(String what){
        TeamData data = team().data();
        syncGround();
        spots.clear();
        nearness.clear();
        seenLines.clear();
        lines.allowance = searchAllowance;
        planner.begin();
        tools();
        retry.clear();
        saving = false;
        staplesNow = null;
        sparedNow.clear();
        if(!survey.ready()) survey.scan();
        Seq<CoreBuild> targets = foe().cores();
        Item item = content.item(what);
        assess();
        Project project = item != null ? provide(item, data, 0) : attempt(Director.Goal.valueOf(what), data, targets);
        if(project != null){
            ground.plan(project.parts);
            ground.plan(project.last);
        }
        return (project == null ? "nothing" + (saving ? " (saving)" : "") : project.name + " (" + (project.parts.size + project.last.size) + " blocks)") + "\n" + status();
    }

    /** Whether something laid out earlier can still be worked on. What the player is keeping the builders away from does not hold up the rest. */
    private boolean busy(TeamData data){
        //what is to come down first holds up the rest only where a builder can get at it
        for(int i = 0; i < removals.size; i++){
            Building down = world.build(removals.get(i));
            if(down != null && down.team == team() && calm(down.x, down.y, quiet)) return true;
        }
        for(BlockPlan plan : data.plans){
            Tile tile = world.tile(plan.x, plan.y);
            if(tile == null || !laid.contains(tile.pos()) || standing(tile, plan)) continue;
            float x = plan.x * tilesize + plan.block.offset, y = plan.y * tilesize + plan.block.offset;
            boolean clear;
            if(Options.hands.on()){
                clear = safe(x, y);
            }else{
                Vec2 stand = stand(x, y, data, reach, Tmp.v2);
                clear = calm(stand.x, stand.y, quiet) && calm(x, y, quiet);
            }
            if(clear && Build.validPlace(plan.block, team(), plan.x, plan.y, plan.rotation)) return true;
        }
        return false;
    }

    /**
     * What to build next. The {@link Director} says what the enemy is about and so in what order the
     * goals are tried. Some are tried every round, first to last; the rest take turns, and the one
     * whose turn it is gets saved up for, for a while, or the cheapest would always be bought first and
     * the rest never.
     */
    private @Nullable Project choose(TeamData data, Seq<CoreBuild> targets){
        saving = false;
        assess();
        retire();
        retireLines();
        posture();

        boolean waiting = false;
        kept.clear();
        Director.Goal[] first = director.first();
        //turrets asked for by zones that are not held come before the rest of what may be set about
        if(!coverFirst.isEmpty() && director.posture != Director.Posture.bootstrap){
            Seq<Director.Goal> order = new Seq<>(first);
            order.remove(Director.Goal.defence);
            int at = 0;
            while(at < order.size && (order.get(at) == Director.Goal.mend || order.get(at) == Director.Goal.owed || order.get(at) == Director.Goal.arm || order.get(at) == Director.Goal.staples || order.get(at) == Director.Goal.task)) at++;
            order.insert(at, Director.Goal.defence);
            first = order.toArray(Director.Goal.class);
        }
        //what takes things comes before what makes them: with no yard yet, the first comes right after what is needed first
        //and the turrets asked for; the sources are then added for what it takes
        if(Options.ratios.on() && director.posture != Director.Posture.bootstrap && net.count(Net.Kind.yard, null) == 0){
            Seq<Director.Goal> order = new Seq<>(first);
            order.remove(Director.Goal.firstYard);
            int at = 0;
            while(at < order.size && (order.get(at) == Director.Goal.mend || order.get(at) == Director.Goal.owed || order.get(at) == Director.Goal.arm || order.get(at) == Director.Goal.staples || order.get(at) == Director.Goal.task || (order.get(at) == Director.Goal.defence && !coverFirst.isEmpty()))) at++;
            order.insert(at, Director.Goal.firstYard);
            first = order.toArray(Director.Goal.class);
        }
        //more drills on what it mines come before turrets, yards and factories while its home is not being hit and drills
        //have had no more than half of what it spent lately: a drill pays for itself in a minute or two, and the rest waits on
        //what it brings in
        if(Options.ratios.on() && director.posture != Director.Posture.bootstrap && !homeHit(data) && minesUnderShare()){
            Seq<Director.Goal> order = new Seq<>(first);
            int at = 0;
            while(at < order.size && (order.get(at) == Director.Goal.mend || order.get(at) == Director.Goal.owed || order.get(at) == Director.Goal.arm || order.get(at) == Director.Goal.activate || order.get(at) == Director.Goal.staples || order.get(at) == Director.Goal.task)) at++;
            order.insert(at, Director.Goal.ore);
            first = order.toArray(Director.Goal.class);
        }
        for(int i = 0; i < first.length; i++){
            //when a round ran out of time with nothing to show, the next begins one goal further down the list and comes to
            //the earlier ones last: one that takes all the time there is, round after round, would keep the rest from ever being tried
            Director.Goal goal = first[(i + firstShift) % first.length];
            saving = false;
            Project project = attempt(goal, data, targets);
            if(project != null){
                firstShift = 0;
                return project;
            }
            if(!saving) continue;
            //drills ahead of everything else are built when they can be paid for, and not waited for
            if(goal == Director.Goal.ore){
                saving = false;
                continue;
            }
            //what it can't do without is waited for, and nothing else is begun meanwhile: and so is its first turret, which
            //comes before everything else
            if(goal == Director.Goal.owed || goal == Director.Goal.staples || goal == Director.Goal.firstPost) return null;
            if(!waiting){
                //the rest have their go meanwhile, with what this one is not short of
                waiting = true;
                for(ItemStack stack : savingCost){
                    if(stock.free(stack.item) < stack.amount) kept.add(stack.item);
                }
            }
        }

        Director.Goal[] turns = director.turns();
        for(int i = 0; i < turns.length; i++){
            saving = false;
            //when working one of them out took all the time there was, the next one goes first the next time, so that none holds up the rest
            Project project = attempt(turns[(started + shift + i) % turns.length], data, targets);
            if(project != null){
                kept.clear();
                shift = 0;
                return project;
            }
            if(saving && !waiting){
                //its turn, and it can't be afforded yet. the others have their go meanwhile, with what it is not short of
                waiting = true;
                for(ItemStack stack : savingCost){
                    if(stock.free(stack.item) < stack.amount) kept.add(stack.item);
                }
            }
        }
        kept.clear();
        shift = planner.timedOut ? shift + 1 : 0;
        firstShift = planner.timedOut ? firstShift + 1 : 0;
        saving = waiting;
        if(waiting){
            //nothing else could be done either. it is waited for, but not for ever
            if(savingSince < 0f) savingSince = clock;
            if(clock - savingSince >= Options.patience.get() * 60f * 60f){
                savingSince = -1f;
                started++;
            }
        }
        return null;
    }

    /** Looks at how things stand and lets the {@link Director} say what it is about. */
    private void posture(){
        Seq<Item> staples = staples();
        Director.Facts facts = new Director.Facts();
        facts.lacking = staples.contains(item -> !comesIn(item));
        facts.cut = assets.contains(asset -> asset.stage == Asset.Stage.cut);
        facts.fill = 1f;
        for(Item item : staples) facts.fill = Math.min(facts.fill, stock.have(item) / (float)Math.max(stock.cap(), 1));
        fill = facts.fill;
        facts.saturated = incomeDry >= 5;
        facts.engaged = engaged();
        TeamData data = team().data();
        int fighters = data.units.count(Brain::fights);
        facts.army = Math.min(fighters / (float)Math.max(Units.getCap(team()), 1), 1f);
        facts.pressure = Math.max(director.losses / Director.pressure, clock < sightedUntil ? 0.6f : 0f);
        facts.uncovered = data.cores.contains(core -> world.isInMapArea(core.tileX(), core.tileY()) && !covered(core.x, core.y, tilesize * 20f));
        //what comes at it hits so lightly that a unit it can build shrugs most of it off: such units hold home as a turret does
        if(clock < sightedUntil && raiderHit > 0f){
            for(Blueprint blueprint : blueprints){
                UnitType unit = blueprint.kind() == Kind.yard ? finalUnit(blueprint) : null;
                if(unit != null && may(unit) && blueprint.allowed(this::may) && unit.armor >= raiderHit * 0.4f) facts.counter = true;
            }
        }
        facts.thin = net.count(Net.Kind.post, null) < coverPerCore * data.cores.size;
        int wrong = assets.count(asset -> asset.stage == Asset.Stage.cut || asset.stage == Asset.Stage.starved || asset.stage == Asset.Stage.outdated);
        facts.trouble = Math.min(wrong / (float)Math.max(assets.size, 1) * 2f, 1f);
        director.update(facts, clock);
        director.weigh(facts);
        loops();
        //where it can look ahead, the look-ahead decides the order instead: the choice after which it would be ready to strike
        //soonest without losing its home first, and the rest by how they came out
        if(Options.foresight.on() && foresee(data, staples)) director.order(forecast.outcome);
    }

    /** The look-ahead, and what it was last given. */
    public final Forecast forecast = new Forecast();
    public final Forecast.Inputs foreseen = new Forecast.Inputs();
    private float assetsWorth, assetsClock = -1e9f, targetClock = -1e9f, targetHealth, targetGuard;

    private static float total(ItemStack[] stacks){
        float sum = 0f;
        for(ItemStack stack : stacks) sum += stack.amount;
        return sum;
    }

    /**
     * Fills in what the look-ahead goes by, all of it measured on the map or read from the game, and
     * plays it. @return whether it could: with no kind of unit it can build that gets to the other side,
     * there is nothing to be ready for, and the choices are weighed the plain way
     */
    private boolean foresee(TeamData data, Seq<Item> staples){
        Forecast.Inputs in = foreseen;
        if(staples.isEmpty() || data.cores.isEmpty()) return false;
        //its stock and what comes in, every item counted alike; the item that is scarcest is the one a new source is for
        in.stock = in.income = 0f;
        Item scarce = null;
        float least = Float.MAX_VALUE;
        for(Item item : staples){
            in.stock += stock.have(item);
            in.income += stock.income(item) / 60f;
            float ahead = stock.have(item) + stock.income(item) * 2f;
            if(ahead < least){
                least = ahead;
                scarce = item;
            }
        }
        in.cap = stock.cap() * (float)staples.size;
        Item item = scarce;
        int sources = net.things.count(thing -> (thing.kind == Net.Kind.mine || thing.kind == Net.Kind.maker) && thing.item == item);
        //what one more brings in is what one of those there are brings in now
        float each = stock.income(item) / 60f / Math.max(sources, 1);
        in.sourceGain = incomeDry >= 5 ? 0f : Math.max(each, 0.2f);
        Blueprint mine = mineFor(item);
        in.sourceCost = (mine == null ? 120f : total(mine.cost()) * Math.min(Options.rowLength.get(), 3)) + 50f;

        //the turret it would put up next, and what it kills a second with what its lines carry
        Blueprint post = null;
        float postKill = 0f;
        for(Blueprint blueprint : blueprints){
            if(blueprint.kind() != Kind.post || !usable(blueprint) || !(blueprint.main() instanceof ItemTurret turret)) continue;
            float best = 0f;
            for(var entry : turret.ammoTypes){
                if(!available(entry.key)) continue;
                best = Math.max(best, (entry.value.damage + entry.value.splashDamage) * turret.shoot.shots * 60f / Math.max(turret.reload, 1f));
            }
            if(best > 0f && (post == null || total(blueprint.needs()) < total(post.needs()))){
                post = blueprint;
                postKill = best;
            }
        }
        in.turretKill = postKill;
        in.turretCost = post == null ? 0f : total(post.needs());

        //the other side's army at its base, and what holds it off: measured once a second
        in.exposure = exposure;
        in.coverage = coverage;
        in.lossRate = lossRate;
        if(clock - assetsClock > 60f * 10f){
            assetsClock = clock;
            assetsWorth = 0f;
            for(Building build : data.buildings) assetsWorth += total(build.block.requirements);
        }
        in.assets = Math.max(assetsWorth, 1f);

        //the best kind of unit it can build that gets to the target, and its yard
        UnitType unit = null;
        Blueprint yard = null;
        for(Blueprint blueprint : blueprints){
            UnitType type = blueprint.kind() == Kind.yard ? finalUnit(blueprint) : null;
            if(type == null || !may(type) || !blueprint.allowed(this::may) || !reaches(type)) continue;
            if(unit == null || type.health * dps(type, 0f) > unit.health * dps(unit, 0f)){
                unit = type;
                yard = blueprint;
            }
        }
        //the kind that would hold home best, whether or not it gets to the other side: against the shots of what comes
        UnitType guard = null;
        Blueprint guardYard = null;
        for(Blueprint blueprint : blueprints){
            UnitType type = blueprint.kind() == Kind.yard ? finalUnit(blueprint) : null;
            if(type == null || !may(type) || !blueprint.allowed(this::may) || !(blueprint.main() instanceof UnitFactory)) continue;
            float worth = type.health * Math.max(1f - raiderArmorCut(type), 0.1f) * dps(type, 0f);
            if(guard == null || worth > guard.health * Math.max(1f - raiderArmorCut(guard), 0.1f) * dps(guard, 0f)){
                guard = type;
                guardYard = blueprint;
            }
        }
        in.guardDps = in.guardCost = in.guardRate = in.guardYardCost = in.guards = in.guardYards = 0f;
        if(guard != null){
            UnitType kind = guard;
            UnitFactory.UnitPlan guardPlan = ((UnitFactory)guardYard.main()).plans.find(p -> p.unit == kind);
            if(guardPlan == null) guardPlan = ((UnitFactory)guardYard.main()).plans.first();
            //what it deals counts for more the more of the other side's shots its armour turns: it stays alive to deal it
            in.guardDps = dps(guard, 0f) / Math.max(1f - raiderArmorCut(guard), 0.1f);
            in.guardCost = total(guardPlan.requirements);
            in.guardRate = 60f / guardPlan.time;
            in.guardYardCost = total(guardYard.cost());
            in.guards = data.units.count(other -> other.type == kind && !field.contains(other.id));
            float made = 0f;
            for(Net.Thing thing : net.things){
                if(thing.kind == Net.Kind.yard && world.build(thing.x, thing.y) instanceof UnitFactory.UnitFactoryBuild built && built.team == team() && turnsOut(built) == kind) made += health.get(thing, 1f) * in.guardRate;
            }
            in.guardYards = made;
        }
        //(with none that gets to the other side, there is nothing to be ready for yet, and the look-ahead goes by what its base would be worth instead)
        UnitFactory.UnitPlan plan = null;
        if(unit != null && yard.main() instanceof UnitFactory factory){
            UnitType chosen = unit;
            plan = factory.plans.find(p -> p.unit == chosen);
            if(plan == null) plan = factory.plans.first();
        }
        if(plan == null){
            in.unitDps = in.unitHealth = in.unitCost = in.army = in.yardRate = in.yardGain = in.yardCost = 0f;
            in.armyCap = Units.getCap(team());
            in.targetHealth = in.guard = 0f;
            foreseeRest(in, each, post);
            return true;
        }
        //what the map asks to have destroyed, or the other side's core: what it comes to and what guards it, now and then
        if(clock - targetClock > 60f * 10f){
            targetClock = clock;
            CoreBuild home = data.cores.first();
            Building target = quarry(home.x, home.y);
            if(target == null) return false;
            float[] sized = sizeUp(target, home.x, home.y, unit.range);
            targetHealth = sized[0];
            targetGuard = sized[1];
        }
        in.targetHealth = targetHealth;
        in.guard = targetGuard;
        in.unitDps = dps(unit, 0f);
        in.unitHealth = unit.health;
        in.unitCost = total(plan.requirements);
        in.army = data.units.count(other -> fights(other) && reaches(other.type));
        in.armyCap = Units.getCap(team());
        float rate = 0f;
        for(Net.Thing thing : net.things){
            if(thing.kind == Net.Kind.yard && world.build(thing.x, thing.y) instanceof UnitFactory.UnitFactoryBuild built && built.team == team() && reaches(turnsOut(built))) rate += health.get(thing, 1f) * 60f / plan.time;
        }
        in.yardRate = rate;
        in.yardGain = 60f / plan.time;
        in.yardCost = total(yard.cost());
        foreseeRest(in, each, post);
        return true;
    }

    private void foreseeRest(Forecast.Inputs in, float each, @Nullable Blueprint post){
        //what making the base better would bring: what starved and outdated things stand to make again
        int wrong = assets.count(asset -> asset.stage == Asset.Stage.starved || asset.stage == Asset.Stage.outdated || asset.stage == Asset.Stage.cut);
        in.improveGain = wrong * Math.max(each, 0.2f) * 0.5f;
        in.improveCost = 80f;

        //how much its builders put up a second, in items: what the turret it builds costs over the time the game takes to build it
        Block sample = post != null ? post.main() : Blocks.duct;
        UnitType builder = ((CoreBlock)team().data().cores.first().block).unitType;
        float seconds = sample.buildTime / Math.max(builder.buildSpeed * state.rules.buildSpeed(team()), 0.01f) / 60f;
        in.buildRate = Math.max(total(sample.requirements) / Math.max(seconds, 0.1f), 5f);

        forecast.look(in);
    }

    /** How much of what the other side's raiders fire a unit's armour takes off, from 0 to 1. */
    private float raiderArmorCut(UnitType type){
        if(raiderHit <= 0f) return 0f;
        return Math.min(type.armor / raiderHit, 0.9f);
    }

    /** What something comes to with what stands in front of it on the side its army comes from, and what guards it deals a second. */
    private float[] sizeUp(Building target, float fromX, float fromY, float reach){
        float[] against = {0f}, shield = {0f};
        for(TeamData other : state.teams.active){
            if(other.team == team() || other.team == Team.derelict) continue;
            indexer.eachBlock(other.team, target.x, target.y, reach + tilesize * 40f, build -> build instanceof Turret.TurretBuild turret && turret.hasAmmo(), build -> {
                if(build.within(target, ((Turret)build.block).range + reach)) against[0] += turretDps((Turret.TurretBuild)build);
            });
        }
        float edge = target.block.size * tilesize / 2f + tilesize * 2.5f;
        indexer.eachBlock(target.team, target.x, target.y, edge, build -> build != target && !(build.block instanceof BaseTurret)
            && (build.x - target.x) * (fromX - target.x) + (build.y - target.y) * (fromY - target.y) > 0f, build -> shield[0] += build.health);
        return new float[]{target.health + shield[0], against[0]};
    }

    /** Rounds in a row in which looking for more income came to nothing, with time to spare and nothing to save for. */
    private int incomeDry;
    /** How full its cores were of what it can't do without that they hold least of, at the last look: 0 to 1. */
    private float fill;

    /**
     * What to build while it is being attacked and still has other things to build: only what holds
     * the attack off. Ammunition to a turret that stands empty, and then another turret.
     */
    private @Nullable Project guard(TeamData data, Seq<CoreBuild> targets){
        saving = false;
        assess();
        posture();
        kept.clear();
        Project project = attempt(Director.Goal.arm, data, targets);
        //a drill that can be paid for still comes first where its home is not what is being hit
        if(project == null && !saving && Options.ratios.on() && !homeHit(data) && minesUnderShare()){
            project = attempt(Director.Goal.ore, data, targets);
            saving = false;
        }
        //water makes a turret that stands worth two, for the price of some pipe
        if(project == null && !saving) project = attempt(Director.Goal.cool, data, targets);
        if(project == null && !saving) project = attempt(Director.Goal.defence, data, targets);
        return project;
    }

    /** Sets about one goal. @return what to build for it, or null: {@link #saving} then says if it is only waiting to be paid for */
    private @Nullable Project attempt(Director.Goal goal, TeamData data, Seq<CoreBuild> targets){
        trying = goal;
        Project project;
        try{
            project = attemptPlain(goal, data, targets);
        }finally{
            trying = null;
        }
        if(project != null) lastGoal = goal;
        //a new generator on a vent is taking up more of what lies about, as a new drill is
        if((goal == Director.Goal.vents || goal == Director.Goal.ore) && project != null) incomeDry = 0;
        //all the ore there is to be had is being taken up once looking for more keeps bringing no new drills: factories
        //that work up what is mined are building, not taking up more of what lies about
        if(goal == Director.Goal.income){
            if(project != null && project.assets.contains(asset -> asset.kind == Net.Kind.mine)) incomeDry = 0;
            else if(project != null || (!saving && !planner.timedOut)) incomeDry++;
        }
        //what holds an attack off, laid out while the attack is going on, goes up before anything else
        if(project != null && attacked() && (goal == Director.Goal.arm || goal == Director.Goal.cool || goal == Director.Goal.firstPost || goal == Director.Goal.cover || goal == Director.Goal.defence)) project.urgent = true;
        return project;
    }

    /** What the last thing it laid out was for. For looking into how the enemy behaves. */
    private @Nullable Director.Goal lastGoal;

    private @Nullable Project attemptPlain(Director.Goal goal, TeamData data, Seq<CoreBuild> targets){
        return switch(goal){
            case mend -> mend();
            case owed -> owed(data);
            case staples -> {
                for(Item item : staples()){
                    if(comesIn(item)) continue;
                    Project project = provide(item, data, 0);
                    if(project != null || saving) yield project;
                }
                yield null;
            }
            //an opponent with no army is no opponent: its first yard comes before anything else it would like
            case firstYard -> net.count(Net.Kind.yard, null) == 0 && Options.yards.on() ? army(data, targets) : null;
            //and with an army begun, its first turrets: one for each core it has
            //once something of its own has been destroyed the turret does not wait for the yard
            case firstPost -> net.count(Net.Kind.post, null) < data.cores.size ? defence(data, targets) : null;
            case task -> task(data);
            case arm -> arm(data);
            case cool -> cool(data);
            case cover -> defence(data, targets);
            case speed -> Options.refit.on() ? boosts(data, true) : null;
            case vents -> vents(data);
            case tidy -> tidy(data);
            case power -> power(data);
            case refit -> refit(data);
            case reveal -> reveal();
            case activate -> activate(data);
            case army -> army(data, targets);
            case defence -> defence(data, targets);
            case income -> income(data);
            case ore -> Options.ratios.on() ? mines(data) : null;
            case core -> core(data, false);
        };
    }

    /**
     * Ammunition to turrets of its own that stand empty with no line to them: ones the map gave it to
     * begin with, or ones whose line is gone. A turret that is standing already costs nothing but the
     * duct to it, and is worth as much as one it would have to build.
     */
    private @Nullable Project arm(TeamData data){
        if(!ready("arm")) return null;
        int tried = 0;
        for(Building build : data.buildings){
            if(!(build instanceof ItemTurret.ItemTurretBuild turret) || tried >= 2 || planner.late()) continue;
            if(!world.isInMapArea(build.tileX(), build.tileY()) || rested.get(build.pos(), 0f) > clock) continue;
            ItemTurret type = (ItemTurret)build.block;
            //one that stands empty with no line to it. (giving a second line to one that runs down to its last rounds in a
            //fight was tried: on the third sector it laid seventy lines in sixteen openings, spent the beryllium the turrets
            //and yards were waiting for, and lost eleven of them against three to seven)
            boolean empty = turret.totalAmmo <= 0 && !fedByLine(build), dry = false;
            if(!empty) continue;
            //the best of what it fires that is to be had off a line
            Item best = null;
            for(var entry : type.ammoTypes){
                if(!available(entry.key) || (best != null && worth(entry.value) <= worth(type.ammoTypes.get(best)))) continue;
                best = entry.key;
            }
            if(best == null) continue;
            tried++;
            Project project = planner.refeed(build, best, Planner.ammoUse(type, best));
            if(project != null){
                notes.put("arm", "a line of " + best.name + " to the " + build.block.name + " at " + build.tileX() + "," + build.tileY() + (empty ? ", which stood empty" : ", which runs dry in a fight"));
                //what a second line does for it shows only in the next fight
                if(dry) rested.put(build.pos(), clock + 60f * 60f * 3f);
                return project;
            }
            notes.put("arm", planner.note);
            if(!planner.timedOut) rested.put(build.pos(), clock + shunTime / 5f);
        }
        return null;
    }

    /** Whether a duct, standing or laid out, runs into a building. */
    private boolean fedByLine(Building build){
        int size = build.block.size, offset = -(size - 1) / 2, x0 = build.tileX() + offset, y0 = build.tileY() + offset;
        for(int i = 0; i < size; i++){
            for(int side = 0; side < 4; side++){
                int x = side == 0 ? x0 + size : side == 2 ? x0 - 1 : x0 + i, y = side == 1 ? y0 + size : side == 3 ? y0 - 1 : y0 + i;
                int ix = side == 0 ? x0 + size - 1 : side == 2 ? x0 : x0 + i, iy = side == 1 ? y0 + size - 1 : side == 3 ? y0 : y0 + i;
                if(ground.pushesInto(x, y, ix, iy)) return true;
            }
        }
        return false;
    }

    private float nextSweep;

    /**
     * Puts its lines in order. A base grows a line at a time, each laid by the shortest way there was
     * that day, and ends up with a dozen of them running to its core side by side and across one
     * another. Looked at again, the last stretch of a line can often run into a line that was laid
     * since, so that a few trunks carry everything home: that stretch is laid afresh, and what it no
     * longer runs over is taken up. And duct that nothing feeds any more, left over from what was
     * destroyed or done over, is taken up too. Half of what it was made of comes back.
     */
    private @Nullable Project tidy(TeamData data){
        if(!Options.refit.on()) return null;
        if(clock >= nextSweep){
            nextSweep = clock + 60f * 45f;
            sweep(data);
        }
        //lines are put in order only while none is being laid: a line still going up does not show where it will join the
        //others, and a stretch taken up for being in nobody's way cut it off when it was done. (in the second sector's opening,
        //each line led home a shorter way was followed by one or two laid again to mend what that cut, round after round)
        if(clock < tidyAfter) return null;
        for(BlockPlan plan : data.plans){
            if(plan.block.group == mindustry.world.meta.BlockGroup.transportation) return null;
        }
        for(Net.Thing source : net.things){
            if((source.kind != Net.Kind.mine && source.kind != Net.Kind.maker) || source.item == null || detached(source) || !net.whole(source) || planner.late()) continue;
            String key = "tidy:" + source.x + "," + source.y;
            if(!ready(key)) continue;
            Project project = planner.relead(source);
            if(project == null){
                //looked at again when the base has changed a good deal
                retry.put(key, clock + retryPeriod * 6f);
                continue;
            }
            notes.put("tidy", "the line for " + source.item.name + " from " + source.x + "," + source.y + " is led home a shorter way, " + project.retire.size + " ducts of the old way to be taken up");
            retry.put(key, clock + retryPeriod * 6f);
            tidiedAt = clock;
            return project;
        }
        return null;
    }

    /** When it last led a line home a shorter way, and until when it leaves its lines as they are. */
    private float tidiedAt = -1e9f, tidyAfter;

    /** Stretches of line that are to be taken up once the line runs another way: where the line turns off, how the duct there will face then, and the tiles to clear. */
    private final Seq<Planner.Retire> retiringLines = new Seq<>();

    /** Takes up the old way of a line once the line has been turned onto its new one. */
    private void retireLines(){
        if(retiringLines.isEmpty()) return;
        IntSet live = null;
        for(int i = retiringLines.size - 1; i >= 0; i--){
            Planner.Retire old = retiringLines.get(i);
            Building at = world.build(old.x, old.y);
            boolean turned = at != null && at.team == team() && !(at instanceof ConstructBuild) && at.tileX() == old.x && at.tileY() == old.y && at.rotation == old.rotation && at.block == old.block;
            //given up, if what was to be laid has gone from the list of what is to be built without being built
            boolean waiting = false;
            for(BlockPlan plan : team().data().plans) waiting |= plan.x == old.x && plan.y == old.y;
            if(!turned){
                if(!waiting && clock > old.until) retiringLines.remove(i);
                continue;
            }
            if(live == null) live = liveTiles();
            for(int t = 0; t < old.tiles.size; t++){
                int pos = old.tiles.get(t);
                Building duct = world.build(pos);
                //only what is still plain duct of its own that no line runs over now
                if(duct != null && duct.team == team() && duct.block instanceof Duct && !live.contains(pos) && !removals.contains(pos)) removals.add(pos);
            }
            retiringLines.remove(i);
        }
    }

    /** Every tile its lines run over as they stand: from each mine and factory to where its line ends, and each line led off those. */
    private IntSet liveTiles(){
        IntSet out = new IntSet();
        for(Net.Thing thing : net.things){
            int x = thing.x, y = thing.y;
            if(thing.kind == Net.Kind.branch){
                int start = net.spur(thing.x, thing.y);
                if(start == -1) continue;
                x = Point2.x(start);
                y = Point2.y(start);
                out.add(thing.pos());
            }else if(thing.kind != Net.Kind.mine && thing.kind != Net.Kind.maker){
                continue;
            }
            IntSeq tiles = net.trace(x, y);
            for(int i = 0; i < tiles.size; i++) out.add(tiles.get(i));
        }
        return out;
    }

    /**
     * Takes up duct of its own that nothing feeds: a duct with nothing behind it or beside it that
     * would hand it anything, and the ducts that only such a one leads into, and so on down the line.
     * What a line is built of comes back by half, and the ground is free again.
     */
    private void sweep(TeamData data){
        syncGround();
        IntSet live = liveTiles(), dead = new IntSet();
        Seq<Building> ducts = new Seq<>();
        for(Building build : data.buildings){
            if(build.block == lines.duct && laid.contains(build.pos()) && !live.contains(build.pos()) && world.isInMapArea(build.tileX(), build.tileY())) ducts.add(build);
        }
        boolean more = true;
        for(int pass = 0; pass < 60 && more; pass++){
            more = false;
            for(Building duct : ducts){
                if(dead.contains(duct.pos())) continue;
                boolean fed = false;
                for(int d = 0; d < 4 && !fed; d++){
                    if(d == duct.rotation) continue;
                    int x = duct.tileX() + Geometry.d4x(d), y = duct.tileY() + Geometry.d4y(d);
                    fed = !dead.contains(Point2.pack(x, y)) && ground.pushesInto(x, y, duct.tileX(), duct.tileY());
                }
                if(!fed){
                    dead.add(duct.pos());
                    more = true;
                }
            }
        }
        int added = 0;
        IntSet.IntSetIterator each = dead.iterator();
        while(each.hasNext && added < 120){
            int pos = each.next();
            if(!removals.contains(pos)){
                removals.add(pos);
                added++;
            }
        }
        if(added > 0) Log.info("[EnemyExpansion] Taking up @ ducts that nothing feeds.", added);
    }

    /**
     * Water to its turrets, as soon as there is any to spare within reach of them: a turret with water
     * fires two and a half times as fast, and what a generator on a vent gives off besides its power
     * is enough for it. People who play this planet water nearly every turret they build. Nothing is
     * built to make the water here: that is for when it turns to making things better.
     */
    private @Nullable Project cool(TeamData data){
        if(!Options.refit.on() || pipes.duct == null || !ready("cool")) return null;
        int tried = 0;
        boolean spare = slack(pipes.duct.requirements);
        for(Asset asset : assets){
            if(asset.kind != Net.Kind.post || asset.rest > clock || planner.late() || tried >= 2 || (asset.stage != Asset.Stage.running && asset.stage != Asset.Stage.outdated)) continue;
            //water for a turret that is fighting, which fires two and a half times as fast with it; for the rest, once there is
            //room to spare
            if(!spare && !fighting(asset)) continue;
            Layout layout = asset.layout(blueprints);
            if(layout == null) continue;
            for(Liquid liquid : boosters(layout.blueprint)){
                if((asset.boosted & (1 << liquid.id)) != 0) continue;
                tried++;
                Project project = planner.boost(layout, liquid, liquidRouter);
                if(project != null){
                    project.boosts = asset;
                    project.boostWith = liquid;
                    notes.put("cool", "laid on for the " + asset.blueprint + " at " + asset.x + "," + asset.y);
                    return project;
                }
                notes.put("cool", planner.note);
                if(planner.lacking != null){
                    //there is none of it to spare near enough: something that makes it, by the turret, and the pipe the next time round
                    Project spring = provideLiquid(planner.lacking, planner.lackX, planner.lackY, data);
                    if(spring != null || saving) return spring;
                }
                if(!planner.timedOut) asset.rest = clock + shunTime / 5f;
            }
        }
        return null;
    }

    /** How long something a map asks for is left to come of its own building before it is put up for the asking. */
    static final float taskPatience = 60f * 60f;
    private final ObjectFloatMap<mindustry.game.MapObjectives.MapObjective> asked = new ObjectFloatMap<>();

    /**
     * What the map itself asks of the side it plays: a campaign map sets its player tasks, and opens
     * up as they are done. Most are done in the course of building a base. What is not, after a while,
     * is seen to for its own sake: so many of a block put up near the core, or a first source of an
     * item the core is to hold. Only the side the tasks are set for has any.
     */
    private @Nullable Project task(TeamData data){
        if(team() != state.rules.defaultTeam || !state.rules.objectives.any()) return null;
        for(var objective : state.rules.objectives.all){
            if(objective.isCompleted() || !objective.dependencyFinished()){
                asked.remove(objective, 0f);
                continue;
            }
            float since = asked.get(objective, -1f);
            if(since < 0f) asked.put(objective, since = clock);

            Item item = objective instanceof mindustry.game.MapObjectives.CoreItemObjective core ? core.item : objective instanceof mindustry.game.MapObjectives.ItemObjective held ? held.item : null;
            if(item != null && !hasSource(item) && ready("task:" + item.name)){
                Project project = provide(item, data, 0);
                if(project != null || saving) return project;
                retry.put("task:" + item.name, clock + retryPeriod);
            }
            if(objective instanceof mindustry.game.MapObjectives.BuildCountObjective build){
                Block block = build.block;
                int waiting = 0;
                for(BlockPlan plan : data.plans) if(plan.block == block) waiting++;
                int more = build.count - state.stats.placedBlockCount.get(block, 0) - waiting;
                String key = "task:" + block.name;
                if(block instanceof CoreBlock){
                    //a core the map asks for goes wherever there is a place for one that a builder can get to: taking that
                    //ground is what is asked
                    if(more > 0 && may(block)){
                        Project claimed = core(data, true);
                        if(claimed != null || saving) return claimed;
                    }
                    continue;
                }
                if(more <= 0 || clock - since < taskPatience || !may(block) || !ready(key)) continue;
                //a turret is no use standing bare: one that is asked for goes up the way its turrets always do, with a line to
                //it, in the smallest layout there is of it, on the way to the enemy or else by the core
                Seq<Blueprint> ways = blueprints.select(blueprint -> blueprint.kind() == Kind.post && blueprint.main() == block && usable(blueprint));
                if(ways.size > 0){
                    ways.removeAll(blueprint -> !suppliable(blueprint));
                    if(ways.isEmpty()) continue;
                    ways.sort(blueprint -> blueprint.parts.count(part -> !part.optional));
                    if(!pays(ways.first().needs())){
                        saveFor(block, ways.first().needs());
                        notes.put(key, "saving for it, which the map asks for");
                        return null;
                    }
                    Seq<int[]> places = postSpots(ways.first(), data);
                    for(CoreBuild core : data.cores) places.add(new int[]{core.tileX(), core.tileY()});
                    Project project = done(key, planner.fed(ways, places, foe().cores(), Net.Kind.post));
                    if(project != null) return project;
                    continue;
                }
                //and so does a unit yard: one that stood by itself would never turn anything out
                Blueprint yardWay = blueprints.find(blueprint -> blueprint.kind() == Kind.yard && blueprint.main() == block && usable(blueprint));
                if(yardWay != null){
                    Item lack = lacks(yardWay);
                    if(lack != null){
                        Project first = provide(lack, data, 0);
                        if(first != null || saving) return first;
                        continue;
                    }
                    if(!pays(yardWay.cost())){
                        saveFor(block, yardWay.cost());
                        notes.put(key, "saving for it, which the map asks for");
                        return null;
                    }
                    Project project = done(key, planner.fed(alike(yardWay), yardSpots(yardWay), foe().cores(), Net.Kind.yard));
                    if(project != null) return project;
                    continue;
                }
                if(!pays(ItemStack.mult(cost(block), more))){
                    saveFor(block, ItemStack.mult(cost(block), more));
                    notes.put(key, "saving for " + more + " of it, which the map asks for");
                    return null;
                }
                Project project = done(key, planner.erect(block, more));
                if(project != null) return project;
            }
        }
        return null;
    }

    /** Whether a mine is a drill that was put up without a line: its note is at the drill itself, not at the head of a line. */
    private boolean detached(Net.Thing thing){
        return thing.kind == Net.Kind.mine && ground.look(thing.x, thing.y) && ground.block instanceof Drill;
    }

    /** A line the player has cut is led on to a core again before anything new is begun. */
    private @Nullable Project mend(){
        for(Net.Thing source : net.things){
            if((source.kind != Net.Kind.mine && source.kind != Net.Kind.maker) || source.item == null || detached(source)) continue;
            String key = "mend:" + source.x + "," + source.y;
            if(net.whole(source)){
                notes.remove(key);
                continue;
            }
            if(!ready(key) || planner.late()) continue;
            Project mended = done(key, planner.mend(source, laid));
            if(mended != null && clock - tidiedAt < 60f * 90f){
                //a line cut right after lines were put in order: putting them in order is what cut it. that waits a good while
                tidyAfter = clock + 60f * 60f * 10f;
                tidiedAt = -1e9f;
                Log.info("[EnemyExpansion] Going round in a loop: a line was cut right after its lines were put in order. No more of that for a while.");
            }
            if(mended != null) return mended;
            //one line a round is looked into: the rest of the round is for what else there is to do
            break;
        }
        return null;
    }

    /** A liquid something was found to need, which there was no time left to see to in the round that found it. */
    private @Nullable Project owed(TeamData data){
        if(owed == null) return null;
        Liquid liquid = owed;
        owed = null;
        boolean before = planner.needy;
        planner.needy = needy = owedNeedy;
        try{
            return provideLiquid(liquid, owedX, owedY, data);
        }finally{
            planner.needy = needy = before;
        }
    }

    /**
     * Looks at everything it has laid out and puts each in the stage of its life that what stands on
     * the map says it is in. What is gone and not coming back is forgotten by the pruning; the rest
     * each have a goal that deals with their stage.
     */
    private void assess(){
        takeOn();
        for(Asset asset : assets){
            Building main = world.build(asset.x, asset.y);
            boolean up = main != null && main.team == team() && !(main instanceof ConstructBuild) && main.tileX() == asset.x && main.tileY() == asset.y;
            Net.Thing thing = net.things.find(t -> t.kind == asset.kind && t.x == asset.thingX && t.y == asset.thingY);
            Asset.Stage stage;
            if(!ground.look(asset.x, asset.y)){
                stage = Asset.Stage.lost;
            }else if(!up){
                stage = Asset.Stage.laid;
            }else if(thing != null && (thing.kind == Net.Kind.mine || thing.kind == Net.Kind.maker) && !net.whole(thing) && !detached(thing)){
                stage = Asset.Stage.cut;
            }else if(thing != null && (thing.kind == Net.Kind.maker || thing.kind == Net.Kind.yard) && health.get(thing, 1f) < 0.5f && works(thing) != null && shortOf(works(thing)) != null){
                stage = Asset.Stage.starved;
            }else{
                stage = betterToBeHad(asset, main) ? Asset.Stage.outdated : Asset.Stage.running;
            }
            asset.enter(stage, clock);
        }
    }

    /**
     * Turrets that stand on its side and that it did not lay out, such as the ones a map gives it to
     * start with, are taken on as if it had: from then on they are given water, walls and better
     * ammunition like its own. Each is taken to be the plainest layout there is of that turret.
     */
    private void takeOn(){
        for(Building build : team().data().buildings){
            if(!(build instanceof Turret.TurretBuild) || build.block.privileged || !world.isInMapArea(build.tileX(), build.tileY())) continue;
            int x = build.tileX(), y = build.tileY();
            if(laid.contains(build.pos()) || assets.contains(asset -> asset.kind == Net.Kind.post && Math.abs(asset.x - x) <= 6 && Math.abs(asset.y - y) <= 6 && covers(asset, x, y))) continue;
            Blueprint plain = null;
            for(Blueprint blueprint : blueprints){
                if(blueprint.kind() != Kind.post || blueprint.main() != build.block || blueprint.parts.count(part -> !part.optional && !part.anchor) != 1) continue;
                if(plain == null || blueprint.parts.size < plain.parts.size) plain = blueprint;
            }
            if(plain != null) assets.add(new Asset(Net.Kind.post, plain.name, x, y, 0, false, 1, x, y));
        }
        //and unit factories: one that was put up bare, for a map that asked for it, or that the map gave it, is a yard
        //like any other from now on, to be fed, kept within what its cores allow and added to
        for(Building build : team().data().buildings){
            if(!(build instanceof UnitFactory.UnitFactoryBuild yard) || build.block.privileged || !world.isInMapArea(build.tileX(), build.tileY())) continue;
            int x = build.tileX(), y = build.tileY();
            if(net.things.contains(thing -> thing.kind == Net.Kind.yard && thing.x == x && thing.y == y) || assets.contains(asset -> asset.kind == Net.Kind.yard && asset.x == x && asset.y == y)) continue;
            Blueprint plain = null;
            for(Blueprint blueprint : blueprints){
                if(blueprint.kind() != Kind.yard || blueprint.main() != build.block || blueprint.addon() || !usable(blueprint)) continue;
                if(plain == null || blueprint.parts.size < plain.parts.size) plain = blueprint;
            }
            if(plain == null) continue;
            Part first = plain.parts.first();
            //one that has not been told what to build is told what its blueprint builds
            if(yard.currentPlan == -1 && first.config != null) build.configure(first.config);
            net.things.add(new Net.Thing(Net.Kind.yard, x, y, (Item)null, 0f));
            assets.add(new Asset(Net.Kind.yard, plain.name, x, y, Mathf.mod(build.rotation - first.rotation, 4), false, 1, x, y));
        }
    }

    /** Whether one of the blocks an asset was laid out with stands at a place. */
    private boolean covers(Asset asset, int x, int y){
        if(asset.x == x && asset.y == y) return true;
        Layout layout = asset.layout(blueprints);
        return layout != null && layout.parts.contains(part -> part.x == x && part.y == y);
    }

    /**
     * Whether there is a better way of doing what something does that could be had where it stands:
     * a liquid that makes its blocks faster which has not been led to it and which the enemy makes or
     * could make, or an addition to its main block that the enemy may build.
     */
    private boolean betterToBeHad(Asset asset, Building main){
        if(!Options.refit.on()) return false;
        if(additionFor(main, team().data()) != null) return true;
        if(pipes.duct == null) return false;
        Blueprint from = blueprints.find(b -> b.name.equals(asset.blueprint));
        if(from == null) return false;
        for(Liquid liquid : boosters(from)){
            if((asset.boosted & (1 << liquid.id)) == 0 && (liquidRouter != null || from.boosts.isEmpty()) && obtainable(liquid)) return true;
        }
        return false;
    }

    /** The liquids the blocks of a blueprint are faster with. */
    private static Seq<Liquid> boosters(Blueprint blueprint){
        Seq<Liquid> out = new Seq<>();
        for(Part part : blueprint.parts){
            for(Rates.Flow flow : Rates.liquidBoosts(part.block)){
                if(!out.contains(flow.liquid)) out.add(flow.liquid);
            }
        }
        return out;
    }

    /** Whether the enemy makes a liquid somewhere, or may build something that does. */
    private boolean obtainable(Liquid liquid){
        return liquid.id < 31 && (net.things.contains(thing -> thing.kind == Net.Kind.spring && thing.liquid == liquid) || !springsFor(liquid).isEmpty());
    }

    /** What the enemy leads liquid into something with, where a conduit can't get at it: stands against it and hands the liquid on. */
    @Nullable Block liquidRouter;
    /** Buildings an addition was tried on and would not go, and until when they are left alone. */
    private final IntFloatMap rested = new IntFloatMap();

    /**
     * Makes something it has better. First what adds most: a unit yard is given what turns its units
     * into the next kind up. Then what makes things faster: hydrogen to its wall drills, ozone to the
     * drills that stand on ore, water to its turrets. Last its walls, put up where it did without
     * them and made of better stuff where it has the stuff to spare.
     */
    private @Nullable Project refit(TeamData data){
        if(!Options.refit.on()) return null;
        //each kind of making better goes first in its turn: one that takes all the time there is does not keep the others from theirs
        int from = refitRound++;
        for(int i = 0; i < 4; i++){
            Project project = switch((from + i) % 4){
                case 0 -> additions(data);
                case 1 -> boosts(data, false);
                case 2 -> rearm();
                default -> walls();
            };
            if(project != null || saving || planner.late()) return project;
        }
        return null;
    }

    private int refitRound;

    /** What a turret's ammunition does to what it hits, shot for shot and for how fast it lets the turret fire. */
    private static float worth(mindustry.entities.bullet.BulletType bullet){
        return bullet == null ? 0f : bullet.estimateDPS() * bullet.reloadMultiplier;
    }

    /**
     * Puts its turrets on better ammunition where a line with enough of it to spare goes by: a turret
     * takes several kinds, and what it was built on is only what there was at the time.
     */
    private @Nullable Project rearm(){
        if(!ready("rearm")) return null;
        int tried = 0;
        for(Asset asset : assets){
            if(asset.kind != Net.Kind.post || asset.rest > clock || planner.late() || tried >= 2 || (asset.stage != Asset.Stage.running && asset.stage != Asset.Stage.outdated)) continue;
            if(!(world.build(asset.x, asset.y) instanceof ItemTurret.ItemTurretBuild turret)) continue;
            ItemTurret type = (ItemTurret)turret.block;
            Blueprint from = blueprints.find(blueprint -> blueprint.name.equals(asset.blueprint));
            Port in = from == null ? null : from.ports.find(port -> port.role == Role.in);
            Item now = asset.armed >= 0 ? content.item(asset.armed) : in == null ? null : in.item;
            if(now == null) continue;

            Item best = null;
            float have = worth(type.ammoTypes.get(now));
            for(var entry : type.ammoTypes){
                //a good deal better, and enough of it going by that nothing else is using
                if(entry.key == now || !may(entry.key) || !hasSource(entry.key) || spare(entry.key) < 0.4f || worth(entry.value) < have * 1.15f) continue;
                if(best == null || worth(entry.value) > worth(type.ammoTypes.get(best))) best = entry.key;
            }
            if(best == null) continue;

            //where what it is on now is led off for it
            Net.Thing old = null;
            for(Net.Thing branch : net.things){
                if(branch.kind != Net.Kind.branch || branch.item != now) continue;
                int start = net.spur(branch.x, branch.y);
                if(start == -1) continue;
                net.trace(Point2.x(start), Point2.y(start));
                Building end = world.build(net.endX, net.endY);
                if(end != null && end.block == type && Math.abs(end.tileX() - asset.x) + Math.abs(end.tileY() - asset.y) <= 8){
                    old = branch;
                    break;
                }
            }
            if(old == null){
                asset.rest = clock + shunTime;
                continue;
            }
            tried++;
            Project project = planner.rearm(asset.blueprint, old, best, 0.5f);
            if(project != null){
                project.boosts = asset;
                project.armWith = best;
                notes.put("rearm", best.name + " in place of " + now.name + " for the " + asset.blueprint + " at " + asset.x + "," + asset.y);
                return project;
            }
            notes.put("rearm", planner.note);
            if(!planner.timedOut) asset.rest = clock + shunTime / 3f;
        }
        return null;
    }

    /** The first addition there is, of the ones the enemy may build, to a building that has not got it yet. */
    private @Nullable Blueprint additionFor(Building build, TeamData data){
        Seq<Blueprint> all = additionsFor(build, data);
        return all.isEmpty() ? null : all.first();
    }

    /** The additions the enemy may build to a building that has none of them yet: the ways there are of laying each one out, the one liked best first. */
    private Seq<Blueprint> additionsFor(Building build, TeamData data){
        Seq<Blueprint> out = new Seq<>();
        for(Blueprint addition : blueprints){
            if(addition.kind() != Kind.addon || addition.main() != build.block || !usable(addition)) continue;
            //no more of a unit than it may have
            UnitType result = unitFrom(addition);
            if(result != null && (!may(result) || data.countType(result) >= unitCap(data))) continue;
            //one of them on it is all there is room for: they go on the same ground
            if(added(addition, build)) return new Seq<>();
            out.add(addition);
        }
        return out;
    }

    /** Whether an addition is on a building already, standing or laid out. */
    private boolean added(Blueprint addition, Building build){
        if(addition.parts.size < 2) return true;
        for(int flip = 0; flip < 2; flip++){
            Part part = addition.at(build.tileX(), build.tileY(), build.block.rotate ? build.rotation : 0, flip == 1, 1).parts.get(1);
            if(ground.look(part.x, part.y) && ground.block == part.block && ground.originX == part.x && ground.originY == part.y) return true;
        }
        return false;
    }

    /** The unit that comes out of what an addition is added to once the addition is there, or null if it has nothing to do with units. */
    private static @Nullable UnitType unitFrom(Blueprint addition){
        Block anchor = addition.main();
        UnitType unit = anchor instanceof UnitFactory factory && factory.plans.size > 0 ? factory.plans.first().unit
            : anchor instanceof Reconstructor rebuilder && rebuilder.upgrades.size > 0 ? rebuilder.upgrades.first()[1] : null;
        if(unit == null) return null;
        for(Part part : addition.parts){
            if(part.anchor || !(part.block instanceof Reconstructor rebuilder)) continue;
            for(UnitType[] upgrade : rebuilder.upgrades){
                if(upgrade[0] == unit){
                    unit = upgrade[1];
                    break;
                }
            }
        }
        return unit;
    }

    private @Nullable Project additions(TeamData data){
        int tried = 0;
        for(Building build : data.buildings){
            if(planner.late() || tried >= 2) break;
            if(!(build.block instanceof UnitBlock) || rested.get(build.pos(), 0f) > clock || !world.isInMapArea(build.tileX(), build.tileY())) continue;
            Seq<Blueprint> ways = additionsFor(build, data);
            if(ways.isEmpty()) continue;
            Blueprint addition = ways.first();
            String key = "addition:" + addition.name;
            if(!ready(key)) continue;

            Item lack = lacks(addition);
            if(lack != null){
                //what it is fed with or built from has to be had first
                Project first = provide(lack, data, 0);
                if(first != null || saving) return first;
                notes.put(key, "needs " + lack.name + ", which is not to be had");
                retry.put(key, clock + retryPeriod * 2f);
                continue;
            }
            if(!pays(addition.cost())){
                saveFor(addition.parts.get(1).block, addition.cost());
                notes.put(key, "saving for it");
                return null;
            }
            tried++;
            Project project = null;
            for(Blueprint way : ways){
                if(project != null || planner.late() || planner.lacking != null) break;
                if(way.main() == addition.main() && pays(way.cost())) project = planner.addon(way, build);
            }
            if(project != null) return done(key, project);
            notes.put(key, planner.note);
            if(planner.lacking != null){
                Project spring = provideLiquid(planner.lacking, planner.lackX, planner.lackY, data);
                if(spring != null || saving) return spring;
            }
            //not on this one, for a while: another may have the room
            if(!planner.timedOut) rested.put(build.pos(), clock + shunTime);
        }
        return null;
    }

    private @Nullable Project boosts(TeamData data, boolean drills){
        int tried = 0;
        boolean spare = pipes.duct == null || slack(pipes.duct.requirements);
        for(Asset asset : assets){
            if(asset.stage != Asset.Stage.outdated || asset.rest > clock || planner.late() || tried >= 3 || (drills && asset.kind != Net.Kind.mine)) continue;
            if(asset.kind == Net.Kind.post && !spare && !fighting(asset)) continue;
            Layout layout = asset.layout(blueprints);
            if(layout == null) continue;
            for(Liquid liquid : boosters(layout.blueprint)){
                if((asset.boosted & (1 << liquid.id)) != 0 || !obtainable(liquid)) continue;
                String key = "boost:" + liquid.name;
                tried++;
                Project project = planner.boost(layout, liquid, liquidRouter);
                if(project != null){
                    project.boosts = asset;
                    project.boostWith = liquid;
                    notes.put(key, "laid on for the " + asset.blueprint + " at " + asset.x + "," + asset.y);
                    return project;
                }
                notes.put(key, planner.note);
                if(planner.lacking == liquid){
                    //none to spare within reach: something that makes it goes up near here
                    Project spring = provideLiquid(liquid, asset.x, asset.y, data);
                    if(spring != null || saving) return spring;
                }
                if(!planner.timedOut) asset.rest = clock + shunTime / 3f;
            }
        }
        return null;
    }

    private @Nullable Project walls(){
        if(!ready("walls")) return null;
        for(Asset asset : assets){
            if(asset.kind != Net.Kind.post || asset.rest > clock || planner.late() || (asset.stage != Asset.Stage.running && asset.stage != Asset.Stage.outdated)) continue;
            Layout layout = asset.layout(blueprints);
            if(layout == null || !layout.parts.contains(part -> part.optional)) continue;
            Project project = planner.fortify(layout);
            if(project != null && pays(project.cost()) && (slack(project.cost()) || fighting(asset))){
                notes.put("walls", "for the " + asset.blueprint + " at " + asset.x + "," + asset.y);
                return project;
            }
            if(project != null) syncGround();
            asset.rest = clock + shunTime / 3f;
        }
        return null;
    }

    /**
     * More power for a grid that can't keep up with what is on it. Power is otherwise only laid on
     * when something new is built; a grid that came to be overloaded since stays that way, and
     * everything on it runs slow together.
     */
    /**
     * Power taken up like ore while it spreads: a generator on the nearest free vent its grids can be
     * led to, whether or not anything is short of power yet. A vent it has not seen yet is looked at first.
     */
    /**
     * Water and power a second, what it has makes against what everything of its own would take: a turret that is cooled
     * takes 15 water a second, an impact drill 10, a generator on a vent gives 5, a condenser on one 30. Read from the
     * game's own numbers, for each building there is.
     */
    private float waterMade, waterWanted, powerMade, powerWanted;

    private void liquids(TeamData data){
        waterMade = waterWanted = powerMade = powerWanted = 0f;
        for(Building build : data.buildings){
            if(build.block.outputsPower && build.power != null) powerMade += build.getPowerProduction() * 60f;
            if(build.block.consPower != null && !build.block.outputsPower) powerWanted += build.block.consPower.usage * 60f;
            if(build.block instanceof ThermalGenerator thermal && thermal.outputLiquid != null && thermal.outputLiquid.liquid == Liquids.water) waterMade += thermal.outputLiquid.amount * 60f * build.efficiency;
            if(build.block instanceof GenericCrafter crafter && crafter.outputLiquids != null){
                for(var out : crafter.outputLiquids) if(out.liquid == Liquids.water) waterMade += out.amount * 60f * Math.max(build.efficiency, build.potentialEfficiency);
            }
            for(var consume : build.block.consumers){
                if(consume instanceof mindustry.world.consumers.ConsumeLiquid takes && takes.liquid == Liquids.water && !(build.block instanceof Turret)) waterWanted += takes.amount * 60f;
                //water for turrets is what makes them hold a fight: wanted when one is coming, not before
                if((build.block instanceof Turret) && (homeShort || attacked()) && (consume instanceof mindustry.world.consumers.ConsumeCoolant || consume instanceof mindustry.world.consumers.ConsumeLiquid)) waterWanted += ((mindustry.world.consumers.ConsumeLiquidBase)consume).amount * 60f;
            }
        }
    }

    private @Nullable Project vents(TeamData data){
        if(power.generator == null || !ready("vents") || planner.late()) return null;
        //a vent gives power or water, not both: where more water is wanted than is made, and there is power to spare for a
        //condenser, the next vent gives water. (with every vent given to power, a base had turrets that were never cooled and
        //impact drills that never ran, for want of a vent to put a condenser on)
        liquids(data);
        Blueprint well = springFor(Liquids.water);
        if(well != null && Planner.ventBlock(well) != null && waterWanted - waterMade > 5f && powerMade - powerWanted > Planner.ventBlock(well).consPower.usage * 60f && data.core() != null){
            notes.put("vents", "water " + (int)waterMade + "/" + (int)waterWanted + " a second, power " + (int)powerMade + "/" + (int)powerWanted + ": a condenser");
            Project well_ = provideLiquid(Liquids.water, data.core().tileX(), data.core().tileY(), data);
            if(well_ != null || saving) return well_;
        }
        IntSeq all = terrain.vents(power.generator, team());
        CoreBuild home = data.core();
        if(home == null) return null;
        //the nearest free one
        int best = -1;
        float bestFar = Float.MAX_VALUE;
        for(int i = 0; i < all.size; i++){
            int x = Point2.x(all.get(i)), y = Point2.y(all.get(i));
            float far = Mathf.dst(x, y, home.tileX(), home.tileY());
            if(far >= bestFar || far > Options.reach.get() || !territory.ours(x, y) || world.build(x, y) != null || !ground.clear(power.generator, x, y)) continue;
            best = all.get(i);
            bestFar = far;
        }
        if(best == -1){
            notes.put("vents", "no free vent on its ground");
            retry.put("vents", clock + 60f * 60f);
            return null;
        }
        int bx = Point2.x(best), by = Point2.y(best);
        if(state.rules.staticFog && state.rules.fog && !fogControl.isDiscovered(team(), bx, by)){
            //it may not build where it has not looked. with no unit to send, something that sees far goes up towards it (the
            //fourth sector stood with no units and a vent unseen, and so with no power for the yards that would make some)
            scout = best;
            if(!data.units.contains(Brain::fights)) revealAt = best;
            notes.put("vents", "a vent at " + bx + "," + by + " not seen yet: a unit goes to look");
            retry.put("vents", clock + 60f * 30f);
            return null;
        }
        //from the grid nearest it
        Building near = indexer.findTile(team(), bx * tilesize, by * tilesize, Float.MAX_VALUE, build -> build.block instanceof BeamNode);
        if(near == null || near.power == null) return null;
        Seq<Part> nodes = new Seq<>();
        for(Building other : near.power.graph.all){
            if(other.block instanceof BeamNode && nodes.size < 60) nodes.add(new Part(other.block, other.tileX(), other.tileY(), 0));
        }
        //a search for a way to a vent is long: one a while, not one a round
        retry.put("vents", clock + 60f * 20f);
        Project project = planner.power(nodes, 1f);
        //only one that puts up a generator: sharing a grid that is loaded already is no more power
        if(project != null && !project.parts.contains(part -> part.block == power.generator)){
            planner.drop(project, "no way to a free vent");
            project = null;
        }
        if(project != null) notes.put("vents", "laid out a generator");
        return project;
    }

    private @Nullable Project power(TeamData data){
        if(power.generator == null || !ready("power")) return null;
        //what stands with no power at all comes first: on a grid of its own with nothing on it that makes any
        for(Building build : data.buildings){
            if(build.block.consPower == null || build.block.consPower.buffered || build.power == null || build instanceof ConstructBuild) continue;
            if(!build.power.graph.producers.isEmpty() || !world.isInMapArea(build.tileX(), build.tileY()) || rested.get(build.pos(), 0f) > clock || planner.late()) continue;
            Project project = planner.wireUp(build);
            if(project != null) return done("power", project);
            notes.put("power", planner.note);
            if(!planner.timedOut) rested.put(build.pos(), clock + shunTime / 5f);
            break;
        }
        ObjectSet<PowerGraph> looked = new ObjectSet<>();
        for(Building build : data.buildings){
            if(!(build.block instanceof BeamNode) || build.power == null || !looked.add(build.power.graph) || !world.isInMapArea(build.tileX(), build.tileY())) continue;
            PowerGraph graph = build.power.graph;
            float made = 0f, used = 0f;
            for(Building producer : graph.producers) made += producer.getPowerProduction();
            for(Building consumer : graph.consumers){
                if(consumer.block.consPower != null && !consumer.block.consPower.buffered && consumer.enabled) used += consumer.block.consPower.usage;
            }
            //short by a good deal, on paper and as it runs
            if(used <= made * 1.25f || graph.getSatisfaction() >= 0.75f) continue;
            if(!pays(cost(power.generator))) continue;
            Seq<Part> nodes = new Seq<>();
            for(Building other : graph.all){
                if(other.block instanceof BeamNode && nodes.size < 60) nodes.add(new Part(other.block, other.tileX(), other.tileY(), 0));
            }
            Project project = planner.power(nodes, used - made);
            if(project != null) return done("power", project);
            return done("power", null);
        }
        return null;
    }

    /**
     * The things the enemy can't do without: what its lines and its power are built of, what the
     * cheapest unit yard and the cheapest turret it may build are built of and fed with, and what
     * goes into making any of those that is not found in the ground. Worked out from what it may
     * build, so with other things researched it is another list. What comes out of the ground is
     * first, as that is what the rest is made from.
     */
    public Seq<Item> staples(){
        if(staplesNow != null) return staplesNow;
        Seq<Item> out = new Seq<>();
        for(Block block : new Block[]{lines.duct, power.node, power.generator}){
            if(block == null) continue;
            for(ItemStack stack : block.requirements) if(!out.contains(stack.item)) out.add(stack.item);
        }
        for(Kind kind : new Kind[]{Kind.yard, Kind.post}){
            Blueprint cheapest = null;
            int least = Integer.MAX_VALUE;
            for(Blueprint blueprint : blueprints){
                if(blueprint.kind() != kind || !blueprint.allowed(this::may)) continue;
                int total = 0;
                for(ItemStack stack : blueprint.needs()) total += stack.amount;
                if(total < least){
                    least = total;
                    cheapest = blueprint;
                }
            }
            if(cheapest != null) stapled(cheapest, out);
        }
        for(int i = 0; i < out.size; i++){
            Item item = out.get(i);
            if(raw(item)) continue;
            Blueprint factory = blueprints.find(blueprint -> blueprint.kind() == Kind.craft && blueprint.allowed(this::may) && Rates.makes(blueprint.main(), item) > 0f && blueprint.has(Role.out, null));
            if(factory != null) stapled(factory, out);
        }
        out.sort(item -> raw(item) ? 0f : 1f);
        return staplesNow = out;
    }

    private @Nullable Seq<Item> staplesNow;

    private static void stapled(Blueprint blueprint, Seq<Item> out){
        for(ItemStack stack : blueprint.needs()) if(!out.contains(stack.item)) out.add(stack.item);
        for(Port port : blueprint.ports){
            if(port.role == Role.in && port.item.buildable && !out.contains(port.item)) out.add(port.item);
        }
    }

    /** Whether an item comes out of the ground on this map. */
    private boolean raw(Item item){
        return !survey.ore(item).isEmpty() || !survey.floor(item).isEmpty();
    }

    /** Whether something may be tried now: it has not just come to nothing. */
    private boolean ready(String key){
        return clock >= retry.get(key, 0f);
    }

    /**
     * Takes note of how an attempt went. One that came to nothing is not made again for a while, and
     * for longer each time; unless the time was up before everything had been tried, in which case
     * it is taken up again soon: the places that failed are remembered.
     */
    private @Nullable Project done(String key, @Nullable Project project){
        if(project != null){
            //something new on the map can open up what was not possible before
            failures.clear();
            timeouts.clear();
            for(var entry : retry) retry.put(entry.key, Math.min(entry.value, clock + retryPeriod));
            Part where = project.parts.isEmpty() ? project.last.first() : project.parts.first();
            notes.put(key, "laid out " + project.name + " at " + where.x + "," + where.y);
        }else{
            notes.put(key, planner.note + (planner.timedOut ? " (out of time: to be taken up again)" : ""));
            if(planner.timedOut && timeouts.increment(key, 0, 1) < 20){
                retry.put(key, clock + Options.planInterval.get() * 60f);
            }else{
                timeouts.put(key, 0);
                //what it has none of and can't do without is not left alone for long
                int times = Math.min(failures.increment(key, 0, 1) + 1, needy ? 2 : 3);
                retry.put(key, clock + retryPeriod * (1 << (times - 1)));
            }
        }
        return project;
    }

    /** Whether an item gets onto one of the enemy's own lines somewhere. */
    private boolean hasSource(Item item){
        //one whose line the player has cut brings nothing in: it is no source until that has been led on again
        return net.things.contains(thing -> (thing.kind == Net.Kind.mine || thing.kind == Net.Kind.maker) && thing.item == item && net.whole(thing));
    }

    /** Whether a new source of an item could be laid: there is ore of it in a wall or in the floor, or a factory it may build makes it. */
    boolean sourceable(Item item){
        //ore in the walls counts only while there is some left that is not taken up: the last look for more found none
        boolean walls = !survey.ore(item).isEmpty() && mineFor(item) != null && (clock - minedOut.get(item.id, -1e9f) > 60f * 60f * 5f);
        return walls || (!survey.floor(item).isEmpty() && drillFor(item) != null) || maker(item) != null;
    }

    /** When looking for a wall with an item in it last found none left within reach, by item id. */
    private final IntFloatMap minedOut = new IntFloatMap();

    /** Whether an item comes into its cores at all: off a source of its own, or off the lines of the base it started with. */
    private boolean comesIn(Item item){
        return hasSource(item) || stock.piped(item) > 0 || (clock < settleTime && standsToMake(item));
    }

    /** For how long after a map is loaded what stands there to make an item is taken to be making it: after that, only what its lines are seen to bring in counts. */
    static final float settleTime = 60f * 120f;

    /** Whether the base it started with has something that makes an item and a line from that to a core. Whether it runs is not looked into. */
    private boolean standsToMake(Item item){
        for(Building build : team().data().buildings){
            boolean makes = build.block instanceof GenericCrafter ? Rates.makes(build.block, item) > 0f
                : build instanceof BeamDrill.BeamDrillBuild drill ? drill.lastItem == item
                : build.block instanceof WallCrafter crafter && crafter.output == item;
            if(makes && ledHome(build)) return true;
        }
        return false;
    }

    /** Whether a line takes what a building puts out and runs on to a core. */
    private boolean ledHome(Building build){
        int size = build.block.size, offset = -(size - 1) / 2, x0 = build.tileX() + offset, y0 = build.tileY() + offset;
        for(int i = 0; i < size; i++){
            for(int side = 0; side < 4; side++){
                int x = side == 0 ? x0 + size : side == 2 ? x0 - 1 : x0 + i, y = side == 1 ? y0 + size : side == 3 ? y0 - 1 : y0 + i;
                if(!ground.look(x, y) || !(ground.block instanceof Duct || ground.block instanceof DuctRouter || ground.block instanceof DuctBridge)) continue;
                //a duct that runs into the building takes nothing from it
                int nx = x + Geometry.d4x(ground.rotation), ny = y + Geometry.d4y(ground.rotation);
                if(ground.block instanceof Duct && nx >= x0 && nx < x0 + size && ny >= y0 && ny < y0 + size) continue;
                net.trace(x, y);
                if(ground.look(net.endX, net.endY) && ground.block instanceof CoreBlock) return true;
            }
        }
        return false;
    }

    /**
     * How much of an item a second to look for a line with, for something that takes so much of it.
     * While its cores are well filled with it, what runs into them is more than they need: any line of
     * it there is will do, the best there is, though it brings less than the thing could take. (With a
     * core full to the top, all of it is burned.) A thing that works at half its pace is better than
     * one that stands for want of a line that carries all it could use.
     */
    float feedRate(Item item, float want){
        if(stock.have(item) < stock.cap() * 0.4f) return want;
        return Math.min(want, Math.max(spare(item), 0.2f) / Planner.enough);
    }

    /** The most of an item that goes by anywhere on its own lines without having been led off for something. */
    private float spare(Item item){
        float most = 0f;
        for(var entry : net.carried(item)) most = Math.max(most, entry.value);
        return most;
    }

    /** Whether an item can be had off a line: one of its own, or one the base it started with came with. */
    private boolean available(Item item){
        return hasSource(item) || !seen(item).isEmpty();
    }

    /**
     * Sees to it that there is more of an item on the enemy's lines: a row of drills where it shows in
     * a wall, a drill where it lies in the floor, or a factory that makes it. What a factory is fed
     * with has to be there first, so that may be what comes of this instead.
     */
    private @Nullable Project provide(Item item, TeamData data, int depth){
        if(depth > 3 || planner.late()) return null;
        //without a first source of it nothing that needs it can ever be built, so for that its ground is the whole map
        boolean before = needy;
        needy = planner.needy = !hasSource(item);
        try{
            Project project = supply(item, data, depth);
            //a first source of something goes up before whatever else was laid out: a duct at a time, the rest would
            //use up what comes in by hand, and the drills that would end the shortage would never be paid for
            if(project != null && needy) project.first = true;
            return project;
        }finally{
            needy = planner.needy = before;
        }
    }

    private boolean needy;

    private @Nullable Project supply(Item item, TeamData data, int depth){
        int cores = data.cores.size;

        if(Options.mines.on() && net.count(Net.Kind.mine, item) < limit(Options.minesPerCore, cores)){
            //only what the map has: there is no saving up for a mine of something that is in none of its walls
            Blueprint wall = survey.ore(item).isEmpty() ? null : mineFor(item);
            //(looking only by home is its own search: what it finds or does not find there says nothing of the rest)
            String home = reachCap < Integer.MAX_VALUE ? "home" : "";
            String key = home + "mine:" + item.name;
            if(wall != null && ready(key)){
                //as long a row as can be paid for now, with something left for its line and its power. a mine of something its
                //own drills are built of goes up a drill at a time while that is short: one now brings in what pays for the next
                                int copies = Math.min(affordable(wall.cost(), 120), Options.rowLength.get());
                if(copies < 1){
                    saveFor(wall.main(), wall.cost());
                    notes.put(key, "saving for " + wall.name);
                    return null;
                }
                Project project = planner.mine(wall, item, copies, reachFor(item));
                //no wall with it left within reach: what there is of it is taken up
                if(project == null && planner.lacking == null && !planner.timedOut && planner.note.endsWith("no wall with it in reach") && home.isEmpty()) minedOut.put(item.id, clock);
                if(project != null) minedOut.remove(item.id, 0f);
                if(project == null && planner.lacking != null){
                    //the ore is there; what the drills run on is not. that first, and the drills the next time round
                    notes.put(key, planner.note);
                    Project spring = provideLiquid(planner.lacking, planner.lackX, planner.lackY, data);
                    if(spring != null || saving) return spring;
                }
                project = done(key, project);
                if(project != null) return project;
            }

            Blueprint pad = survey.floor(item).isEmpty() ? null : drillFor(item);
            key = home + "drill:" + item.name;
            //a drill of it that stands without a line: that is led to a core before another is thought of
            Net.Thing loose = net.things.find(thing -> thing.kind == Net.Kind.mine && thing.item == item && detached(thing));
            if(loose != null){
                Asset standing = assets.find(asset -> asset.thingX == loose.x && asset.thingY == loose.y && asset.kind == Net.Kind.mine);
                Layout layout = standing == null ? null : standing.layout(blueprints);
                if(layout == null || !ready("connect:" + item.name)) return null;
                Project led = done("connect:" + item.name, planner.connect(loose, layout));
                //(a search that ran out of time counts too: one that never finishes in the time there is never finishes)
                if(led != null) return led;
                //one that no line can be led from, time after time, is given up: what it brings in comes by hand, and what
                //only a line brings counts for some things (a map that asks for so much of an item in its cores counts only
                //what blocks hand in). it comes down, its ground is left alone for a long while, and another place is found
                int fails = connectFails.get(Point2.pack(loose.x, loose.y), 0) + 1;
                connectFails.put(Point2.pack(loose.x, loose.y), fails);
                if(fails < 3) return null;
                connectFails.remove(Point2.pack(loose.x, loose.y), 0);
                Building drill = world.build(standing.x, standing.y);
                if(drill != null && drill.team == team() && !removals.contains(drill.pos())) removals.add(drill.pos());
                for(int dx = -6; dx <= 6; dx += 3) for(int dy = -6; dy <= 6; dy += 3) shun(standing.x + dx, standing.y + dy);
                fitter.skip.add(Point2.pack(standing.x, standing.y));
                Log.info("[EnemyExpansion] Giving up the @ at @,@: no line can be led from it to a core.", drill == null ? "drill" : drill.block.name, standing.x, standing.y);
                return null;
            }
            if(pad != null && ready(key)){
                if(!pays(pad.cost())){
                    saveFor(pad.main(), pad.cost());
                    notes.put(key, "saving for " + pad.name);
                    return null;
                }
                Project project = planner.pad(pad, item, reachFor(item));
                if(project == null && planner.lacking != null){
                    //the ore is there; what the drill runs on is not. that first, and the drill the next time round
                    notes.put(key, planner.note);
                    Project spring = provideLiquid(planner.lacking, planner.lackX, planner.lackY, data);
                    if(spring != null || saving) return spring;
                }
                project = done(key, project);
                if(project != null) return project;
            }
        }

        if(Options.plants.on() && !minesOnly && net.count(Net.Kind.maker, item) < limit(Options.plantsPerCore, cores)){
            Blueprint factory = maker(item);
            String key = "make:" + item.name;
            Building hungry = factory == null ? null : starving(Net.Kind.maker, item);
            if(hungry != null && depth < 2){
                //another would be short of the same thing. what the ones it has are short of comes first
                notes.put(key, "its " + hungry.block.name + " at " + hungry.tileX() + "," + hungry.tileY() + " is short of " + shortOf(hungry).name + ": more of that first");
                Project relief = relieve(hungry, data, depth + 1);
                if(relief != null || saving) return relief;
                //nothing to be done for that one. another goes up all the same where it can be supplied, as long as the
                //cores are short of what it makes: with plenty of that in them, more of the same would only stand about
                if(stock.have(item) >= stock.cap() / 2) return null;
            }
            Building weak = factory == null ? null : underpowered(Net.Kind.maker, item);
            if(weak != null){
                //more power for the ones it has, where there is any to be had; not another to share what there is. where none is
                //to be had for it, another goes up somewhere that has power, rather than none at all
                notes.put(key, "its " + weak.block.name + " at " + weak.tileX() + "," + weak.tileY() + " has not the power to run flat out: more power first");
                Project more = power(data);
                if(more != null || saving) return more;
            }
            if(factory != null && ready(key)){
                //sand can't go by way of a core: a factory that takes it gets crushers of its own
                boolean sandy = factory.has(Role.in, Items.sand);
                Blueprint crushers = sandy ? find(Kind.quarry) : null;
                if(sandy && crushers == null){
                    notes.put(key, "nothing it may build gets sand");
                    return null;
                }
                for(Port port : factory.ports){
                    if(port.role != Role.in || port.item == Items.sand) continue;
                    //what it is made from first: there has to be a line with it, and with enough of it that nothing else is using.
                    //a furnace on a line that others have already drained makes nothing, and takes from them besides
                    float needs = Rates.uses(factory.main(), port.item);
                    //and by the recipes of all it has: what its sources bring in of the item, less what everything that takes it
                    //uses, has to leave at least half of what this one would take. else more of the item comes first, and with
                    //none to be had no factory goes up (seven furnaces went up on one sector, each short of graphite, each making
                    //a tenth of what it could, and each one more the reason to build another)
                    if(Options.ratios.on() && net.count(Net.Kind.maker, item) > 0 && hasSource(item)){
                        flows(data);
                        //(nor are cores that are well filled with it and still get it by line, whatever the reckoning says: what
                        //comes in by line past what building takes is there for a factory put up where it passes. measured, it is
                        //what the reckoning is about; on the fourth sector the reckoning had graphite short with a thousand of it in
                        //the cores, and one furnace for want of another stood the silicon at a hundred for half an hour)
                        boolean spare = stock.have(port.item) >= stock.cap() * Director.rich || (stock.have(port.item) >= stock.cap() * 0.4f && stock.piped(port.item) / 60f >= needs * 0.5f);
                        if(supply[port.item.id] - running[port.item.id] >= needs * 0.5f || spare) continue;
                        Project first = provide(port.item, data, depth + 1);
                        if(first != null || saving) return first;
                        notes.put(key, "not another: what it takes of " + port.item.name + " is short already, and no more of that is to be had (" + ratesNote() + ")");
                        return null;
                    }
                    if(available(port.item) && (spare(port.item) >= needs * Planner.enough || !seen(port.item).isEmpty())) continue;
                    Project first = provide(port.item, data, depth + 1);
                    if(first != null || saving) return first;
                    if(!available(port.item)){
                        notes.put(key, "nothing to make it from: no " + port.item.name);
                        return null;
                    }
                    //there is some of it, and no more to be had: it makes do with that, and runs as fast as that lets it
                }
                if(sandy){
                    //as many crushers as the factory takes sand, by the recipes: at an ordinary wall one brings in about a
                    //second of what a furnace uses in four or five
                    WallCrafter crusher = (WallCrafter)crushers.main();
                    float each = Rates.crusher(crusher, crusher.size), takes = Rates.uses(factory.main(), crusher.output);
                    int wanted = Math.max(3, (int)Math.ceil(takes / Math.max(each, 0.05f)));
                    int copies = Math.min(affordable(crushers.cost(), 0), Math.min(wanted, Math.max(crushers.copies, 3)));
                    if(copies < 3 || !pays(sum(factory.cost(), ItemStack.mult(crushers.cost(), 3)))){
                        saveFor(factory.main(), sum(factory.cost(), ItemStack.mult(crushers.cost(), 3)));
                        notes.put(key, "saving for " + factory.name);
                        return null;
                    }
                    //with ore of what else it takes still free in the walls, it goes up as a cell: drills of its own on that,
                    //their line straight into it
                    return done(key, planner.plant(crushers, factory, copies, reachFor(item)));
                }

                //everything else is fed off its lines, like a yard, and stands where they pass
                if(!pays(factory.cost())){
                    saveFor(factory.main(), factory.cost());
                    notes.put(key, "saving for " + factory.name);
                    return null;
                }
                Project project = planner.fed(Seq.with(factory), yardSpots(factory), foe().cores(), Net.Kind.maker);
                if(project == null && planner.lacking != null){
                    notes.put(key, planner.note);
                    Project spring = provideLiquid(planner.lacking, planner.lackX, planner.lackY, data);
                    if(spring != null || saving) return spring;
                }
                return done(key, project);
            }
        }
        return null;
    }

    /** How many of something it may have: so many for each core, or as many as the map has room for where no limit is set. */
    private static int limit(Options.Num each, int cores){
        return each.get() == 0 ? Integer.MAX_VALUE : cores * each.get();
    }

    /**
     * How far from a core to look for a place to get an item. The setting holds while it has enough
     * of the item. For the first source of something, and for something it is running short of, there
     * is no limit but its own ground: nearer places are still taken first, but going without is worse
     * than a long line.
     */
    private int reachFor(Item item){
        //short of it too when little is left of it in the cores: what it has does not cover what it uses
        boolean short_ = !hasSource(item) || stock.have(item) < stock.cap() / 4;
        return Math.min(short_ ? Integer.MAX_VALUE / 4 : Options.reach.get(), reachCap);
    }

    /** How far from home anything is looked for just now, whatever else says. */
    private int reachCap = Integer.MAX_VALUE;

    /** Builds something that makes a liquid, near where it is wanted. */
    private @Nullable Project provideLiquid(Liquid liquid, int x, int y, TeamData data){
        String key = "spring:" + liquid.name;
        if(!Options.plants.on() || !ready(key)) return null;
        Blueprint blueprint = springFor(liquid);
        if(blueprint == null){
            notes.put(key, "nothing it may build makes " + liquid.name);
            return null;
        }
        //finding out that it was wanted may have used up this round's time: it is the first thing seen to in the next
        owed = liquid;
        owedX = x;
        owedY = y;
        owedNeedy = planner.needy;
        if(!pays(blueprint.cost())){
            saveFor(blueprint.main(), blueprint.cost());
            notes.put(key, "saving for " + blueprint.name);
            return null;
        }
        Project project = null;
        for(Blueprint way : springsFor(liquid)){
            if(planner.late()) break;
            if(!pays(way.cost())) continue;
            //one that is itself made from a liquid has to have that within reach, or it is that which is wanted first
            project = planner.spring(way, x, y);
            if(project != null) break;
        }
        if(project != null || !planner.timedOut) owed = null;
        return done(key, project);
    }

    /** A liquid something was found to need, and where, that there was no time left to see to. */
    private @Nullable Liquid owed;
    private int owedX, owedY;
    private boolean owedNeedy;

    /**
     * A unit yard: the best kind it may build, taking turns between tanks, mechs and ships. If that
     * kind is fed with or built from something the enemy has no source of, such as tungsten or
     * hydrogen for the second tier, getting that comes first; and if there is no getting it, a
     * lesser yard is built.
     */
    private @Nullable Project army(TeamData data, Seq<CoreBuild> targets){
        if(!Options.yards.on()) return null;
        retireStranded();
        //with as many yards as it may have, the ones it has are made to turn out better units; and where one has no room
        //for that, a better yard is built somewhere else and the old one taken down once the new one is up
        boolean full = net.count(Net.Kind.yard, null) - retiring.size >= data.cores.size * Options.yardsPerCore.get();
        //with a couple of yards and its units near what it may have, better units before more of them: the cap does not
        //count what a unit can do. (the yards only ever got a refabricator once there were eight of them, and no sector saw
        //a unit of the second tier)
        if(!full && Options.refit.on() && net.count(Net.Kind.yard, null) >= 2 && mostOfAKind(data) >= unitCap(data) * 0.6f){
            Project added = additions(data);
            if(added != null || saving) return added;
        }
        Building old = null;
        if(full){
            if(!Options.refit.on()) return null;
            Project added = additions(data);
            if(added != null || saving) return added;
            old = outdatedYard(data);
            if(old == null) return null;
        }
        Building weak = underpowered(Net.Kind.yard, null);
        if(weak != null && old == null){
            notes.put("yard", "its " + weak.block.name + " at " + weak.tileX() + "," + weak.tileY() + " has not the power to run flat out: no more yards until it has");
            //(letting another go up where there was power filled the seventh sector's opening with yards that took the beryllium
            //its turrets were waiting for)
            return power(data);
        }
        Building hungry = full ? null : starving(Net.Kind.yard, null);
        if(hungry != null){
            notes.put("yard", "its " + hungry.block.name + " at " + hungry.tileX() + "," + hungry.tileY() + " is short of " + shortOf(hungry).name + ": more of that first");
            Project relief = relieve(hungry, data, 0);
            if(relief != null || saving) return relief;
            //nothing to be done for that one: another goes up where there is enough going by to supply it. but where that
            //did not help either, a third would only stand idle beside them: what yards there are have to be fed first
            int starved = net.things.count(thing -> thing.kind == Net.Kind.yard && health.get(thing, 1f) < 0.5f && works(thing) != null && shortOf(works(thing)) != null);
            if(starved >= 2) return null;
        }
        Seq<Blueprint> kinds = yardKinds(data);
        //in the place of an old one, only what is better than it
        int least = old == null ? 0 : grade(old);
        if(old != null) kinds.removeAll(blueprint -> blueprint.parts.count(part -> part.block instanceof UnitBlock) <= least);
        if(kinds.isEmpty()){
            if(old == null) notes.put("yard", "no yard it may build, or all the units it may have");
            return null;
        }

        for(Blueprint blueprint : kinds){
            //each kind has its own turn at being tried, so that one that can't be placed does not keep the others from it
            String key = "yard:" + blueprint.name;
            if(!ready(key)) continue;
            Item lack = lacks(blueprint);
            if(lack != null){
                Project first = provide(lack, data, 0);
                if(first != null || saving) return first;
                notes.put(key, "needs " + lack.name + ", which is not to be had");
                retry.put(key, clock + retryPeriod * 2f);
                continue;
            }
            if(!pays(blueprint.cost())){
                saveFor(blueprint.main(), blueprint.cost());
                notes.put(key, "saving for it");
                return null;
            }
            Project project = planner.fed(alike(blueprint), yardSpots(blueprint), targets, Net.Kind.yard);
            if(project != null && old != null){
                retiring.put(Point2.pack(project.assets.first().x, project.assets.first().y), old.pos());
                notes.put("yard", "the " + old.block.name + " at " + old.tileX() + "," + old.tileY() + " has no room for more: a " + blueprint.name + " goes up in its place, and it comes down when that stands");
            }
            if(project != null) return done(key, project);
            if(planner.lacking != null){
                notes.put(key, planner.note);
                Project spring = provideLiquid(planner.lacking, planner.lackX, planner.lackY, data);
                if(spring != null || saving) return spring;
            }
            done(key, null);
            if(planner.timedOut){
                //the others first, the next few times
                retry.put(key, clock + Options.planInterval.get() * 60f * kinds.size);
                return null;
            }
        }
        return null;
    }

    /**
     * A turret on the way to the player. The kinds of turret it may build and can arm take turns: the
     * one it has fewest of comes first, and if that one can't be paid for or placed just now, the next.
     * For each kind, the first layout in the blueprints that its lines can supply is the one used.
     */
    private @Nullable Project defence(TeamData data, Seq<CoreBuild> targets){
        if(net.count(Net.Kind.post, null) >= Options.maxOutposts.get() || !ready("post")) return null;
        Seq<Blueprint> kinds = new Seq<>();
        for(Blueprint blueprint : blueprints){
            if(blueprint.kind() != Kind.post || !usable(blueprint) || !suppliable(blueprint)) continue;
            //each kind of turret is looked for a place by the layout of it that asks least of a place: one that wants no
            //water piped to it goes where there is none near (by the first layout alone, one that wanted water, a sector
            //with no water by its core found no place for any turret at all, and lost its core to the second wave)
            Blueprint same = kinds.find(other -> other.main() == blueprint.main());
            if(same == null) kinds.add(blueprint);
            else if(Plumbing.needs(blueprint.parts).size < Plumbing.needs(same.parts).size){
                kinds.remove(same, true);
                kinds.add(blueprint);
            }
        }
        if(kinds.isEmpty()){
            notes.put("post", "nothing on its lines to arm a turret with");
            return null;
        }
        ObjectIntMap<Block> standing = new ObjectIntMap<>();
        for(Net.Thing thing : net.things){
            if(thing.kind == Net.Kind.post && ground.look(thing.x, thing.y)) standing.increment(ground.block);
        }
        //the one it has fewest of first; but not one that fires what its cores are nearly out of: a turret that goes dry in the
        //first seconds of a wave is so much wall. (diffuses on graphite, on a sector with twenty-five tiles of graphite ore,
        //stood at one round while the breaches beside them still fired)
        kinds.sort(blueprint -> standing.get(blueprint.main(), 0) + (ammoScarce(blueprint) ? 100 : 0));

        Blueprint wanted = null;
        String said = "its lines reach none of the ways to the player";
        for(Blueprint blueprint : kinds){
            if(planner.late()) break;
            Seq<int[]> places = postSpots(blueprint, data);
            if(places.isEmpty()) continue;
            //every layout there is of this turret that can be supplied, the fullest first: where a pair with its walls
            //will not go, a single one may
            Seq<Blueprint> ways = blueprints.select(other -> other.kind() == Kind.post && other.main() == blueprint.main() && usable(other) && suppliable(other));
            //the walls that go with it can wait for what they cost: it is the turrets that have to be paid for to begin.
            //what can't be paid for as a pair goes up as one: a turret now is worth more than two later, and never more
            //than when it is being attacked. only when not even the least of them can be paid for is that saved for
            ways.removeAll(other -> !pays(other.needs()));
            if(ways.isEmpty()){
                if(wanted == null){
                    wanted = blueprints.select(other -> other.kind() == Kind.post && other.main() == blueprint.main() && usable(other) && suppliable(other)).min(other -> {
                        float total = 0f;
                        for(ItemStack stack : other.needs()) total += stack.amount;
                        return total;
                    });
                }
                continue;
            }
            Project project = planner.fed(ways, places, targets, Net.Kind.post);
            if(project != null) return done("post", project);
            said = planner.note;
            //nothing would go at any of these places: they are passed over for a while, so that the next look is further along
            if(planner.lacking == null && !planner.timedOut){
                for(int[] at : places) deadZones.put(Point2.pack(at[0], at[1]), clock + shunTime);
            }
            if(planner.lacking != null){
                notes.put("post", planner.note);
                Project spring = provideLiquid(planner.lacking, planner.lackX, planner.lackY, data);
                if(spring != null || saving) return spring;
            }
        }
        if(wanted != null){
            //none of the kinds that have a place can be paid for: the one whose turn it was is saved up for
            saveFor(wanted.main(), wanted.needs());
            notes.put("post", "saving for " + wanted.name);
            if(attacked()){
                //and while it is attacked, nothing else is paid for out of what this is short of
                guardCost = wanted.needs();
                guardUntil = clock + Math.max(Options.planInterval.get() * 60f * 3f, 60f * 30f);
            }
            return null;
        }
        planner.note = said;
        return done("post", null);
    }

    /**
     * Whether a blueprint could be supplied as things stand: every item it is fed with is on a line,
     * and every liquid it needs piped is one the enemy makes or may build something to make.
     */
    private boolean suppliable(Blueprint blueprint){
        if(!carried(blueprint)) return false;
        for(Plumbing.Tap need : Plumbing.needs(blueprint.parts)){
            if(springFor(need.liquid) == null && !net.things.contains(thing -> thing.kind == Net.Kind.spring && thing.liquid == need.liquid)) return false;
        }
        return true;
    }

    /**
     * Whether every liquid a blueprint needs could be piped to a place: something that makes it stands
     * near enough, or there is a free vent near enough to build that on.
     */
    private boolean piped(Seq<Plumbing.Tap> needs, int x, int y){
        int limit = (int)(Options.feedLimit.get() * 0.7f);
        for(Plumbing.Tap need : needs){
            boolean near = net.things.contains(thing -> thing.kind == Net.Kind.spring && thing.liquid == need.liquid && Math.abs(thing.x - x) + Math.abs(thing.y - y) <= limit);
            Blueprint spring = near ? null : springFor(need.liquid);
            Block onVent = spring == null ? null : Planner.ventBlock(spring);
            if(onVent != null){
                IntSeq vents = terrain.vents(onVent, team());
                for(int i = 0; i < vents.size && !near; i++){
                    int vx = Point2.x(vents.get(i)), vy = Point2.y(vents.get(i));
                    near = Math.abs(vx - x) + Math.abs(vy - y) <= limit && ground.clear(onVent, vx, vy);
                }
            }
            if(!near) return false;
        }
        return true;
    }

    /** More of whatever it builds with or from that it has the fewest sources of. */
    /**
     * Rates a second of each item, by item id: what its sources bring in, and what everything of its own that takes
     * the item uses when it runs, by the game's own recipes. Nothing here looks at what the cores hold: a factory takes
     * its inputs at a fixed pace, a yard turns out a unit on a fixed recipe, a turret fires a fixed amount a shot. A yard
     * it means to have for its best unit counts as if it stood, and so does what it is building: what went into what it
     * laid out over the last few minutes.
     */
    public final float[] supply = new float[64], demand = new float[64], running = new float[64];

    private void flows(TeamData data){
        java.util.Arrays.fill(supply, 0f);
        java.util.Arrays.fill(demand, 0f);
        for(Net.Thing thing : net.things){
            //the crushers a plant has of its own feed its furnace and nothing else, and have no line to a core
            if(thing.kind == Net.Kind.quarry && thing.item != null){
                supply[thing.item.id] += thing.rate;
                continue;
            }
            if((thing.kind != Net.Kind.mine && thing.kind != Net.Kind.maker) || thing.item == null || !net.whole(thing)) continue;
            supply[thing.item.id] += thing.rate * (thing.kind == Net.Kind.maker ? health.get(thing, 1f) : 1f);
        }
        boolean yardStands = false;
        for(Building build : data.buildings){
            if(build instanceof UnitFactory.UnitFactoryBuild yard){
                if(yard.currentPlan == -1) continue;
                yardStands = true;
                UnitFactory.UnitPlan plan = ((UnitFactory)build.block).plans.get(yard.currentPlan);
                for(ItemStack stack : plan.requirements) demand[stack.item.id] += stack.amount * 60f / plan.time;
            }else if(build.block instanceof GenericCrafter && Rates.inputs(build.block) != null){
                //a factory short of one of its inputs takes the others only as fast as that one lets it run: what it is
                //measured to run at counts, with what it lacks counted at the full pace, which is what is wanted of it
                Item lacking = shortOf(build);
                float pace = Mathf.clamp(build.efficiency + 0.1f);
                for(ItemStack stack : Rates.inputs(build.block)) demand[stack.item.id] += Rates.uses(build.block, stack.item) * (stack.item == lacking && !unfed(build, lacking) ? 1f : pace);
            }else if(build.block instanceof Reconstructor rebuilder){
                for(var consume : build.block.consumers){
                    if(consume instanceof mindustry.world.consumers.ConsumeItems items) for(ItemStack stack : items.items) demand[stack.item.id] += stack.amount * 60f / rebuilder.constructTime;
                }
            }else if(build instanceof ItemTurret.ItemTurretBuild turret){
                Item ammo = ammoOf(turret);
                if(ammo != null) demand[ammo.id] += Planner.ammoUse((ItemTurret)build.block, ammo) / 0.6f * Rates.turretDuty;
            }
        }
        //a yard it means to have, for the best unit it may build, before it has one
        if(!yardStands){
            Blueprint yard = null;
            for(Blueprint blueprint : blueprints){
                UnitType type = blueprint.kind() == Kind.yard ? finalUnit(blueprint) : null;
                if(type != null && may(type) && blueprint.allowed(this::may) && blueprint.main() instanceof UnitFactory && (yard == null || reaches(type))) yard = blueprint;
            }
            if(yard != null){
                UnitFactory factory = (UnitFactory)yard.main();
                UnitType type = finalUnit(yard);
                UnitFactory.UnitPlan plan = factory.plans.find(p -> p.unit == type);
                if(plan == null) plan = factory.plans.first();
                for(ItemStack stack : plan.requirements) demand[stack.item.id] += stack.amount * 60f / plan.time;
            }
        }
        //what what stands and what it means to have take, apart from building: what a factory has to share its inputs with
        System.arraycopy(demand, 0, running, 0, demand.length);
        //and what it is building: what went into what it laid out lately
        while(buildingAt.size > 0 && clock - buildingAt.first() > 60f * 180f){
            buildingAt.removeIndex(0);
            building.remove(0);
            buildingMine.removeIndex(0);
        }
        for(int i = 0; i < building.size; i++){
            for(ItemStack stack : building.get(i)) demand[stack.item.id] += stack.amount / 180f;
        }
    }

    /** What went into each thing it laid out, and when, over the last few minutes; and whether it was drills. */
    private final Seq<ItemStack[]> building = new Seq<>();
    private final FloatSeq buildingAt = new FloatSeq();
    private final BoolSeq buildingMine = new BoolSeq();

    /**
     * Whether drills have had less than their share of what it spent over the last few minutes: what it laid out, and
     * what its yards take for their units. Drills that come before everything else may have half of it.
     */
    private boolean minesUnderShare(){
        float mines = 0f, all = 0f;
        for(int i = 0; i < building.size; i++){
            if(clock - buildingAt.get(i) > 60f * 180f) continue;
            float total = total(building.get(i));
            all += total;
            if(buildingMine.get(i)) mines += total;
        }
        for(Building build : team().data().buildings){
            if(build instanceof UnitFactory.UnitFactoryBuild yard && yard.currentPlan != -1 && yard.efficiency > 0f){
                UnitFactory.UnitPlan plan = ((UnitFactory)build.block).plans.get(yard.currentPlan);
                all += total(plan.requirements) * 180f * 60f / plan.time;
            }
        }
        return mines <= 0.5f * all + 200f;
    }

    /**
     * Whether its home is being hit just now: a core of its own was damaged a moment ago, or something of its own was
     * destroyed lately in a core's zone or right by it.
     */
    private boolean homeHit(TeamData data){
        if(!atlas.ready()) return attacked();
        for(CoreBuild core : data.cores){
            if(core.wasRecentlyDamaged()) return true;
            int zone = atlas.zoneAt(core.tileX(), core.tileY());
            if(zone < 0) continue;
            Atlas.Zone at = atlas.zones.get(zone);
            if(at.lost >= 100f) return true;
            for(IntSet.IntSetIterator it = at.near.iterator(); it.hasNext;){
                if(atlas.zones.get(it.next()).lost >= 100f) return true;
            }
        }
        return false;
    }


    /** Whether everything a turret could fire off its lines is something its cores hold less than a fifth of. */
    private boolean ammoScarce(Blueprint blueprint){
        if(!(blueprint.main() instanceof ItemTurret turret)) return false;
        boolean any = false;
        for(var entry : turret.ammoTypes){
            if(!available(entry.key)) continue;
            any = true;
            if(stock.have(entry.key) >= stock.cap() / 5) return false;
        }
        return any;
    }

    /** What a turret of its own is firing, or would fire with what its lines carry. */
    private @Nullable Item ammoOf(ItemTurret.ItemTurretBuild turret){
        if(turret.ammo.any() && turret.ammo.peek() instanceof ItemTurret.ItemEntry entry) return entry.item;
        Item best = null;
        for(var entry : ((ItemTurret)turret.block).ammoTypes){
            if(available(entry.key) && (best == null || worth(entry.value) > worth(((ItemTurret)turret.block).ammoTypes.get(best)))) best = entry.key;
        }
        return best;
    }

    /** Whether anything is built of an item, or any unit made of it, or any turret fires it: what is worth bringing home. */
    private boolean builtWith(Item item){
        for(Block block : content.blocks()){
            if(!may(block)) continue;
            for(ItemStack stack : block.requirements) if(stack.item == item) return true;
            if(block instanceof ItemTurret turret && turret.ammoTypes.containsKey(item)) return true;
            if(block instanceof UnitFactory factory && factory.plans.contains(plan -> Structs.contains(plan.requirements, stack -> stack.item == item))) return true;
        }
        return false;
    }

    /** How often leading a line from each drill that stands without one has come to nothing, by packed position. */
    private final IntIntMap connectFails = new IntIntMap();

    /**
     * Whether there is room to spare of what something is built of: every item of it at two fifths of what the cores
     * hold or more. Walls, water for turrets and things that see far are what a base adds when it has room to spare:
     * early on, the beryllium they take is what more drills and the turrets themselves are waiting for.
     */
    private boolean slack(ItemStack[] cost){
        for(ItemStack stack : cost){
            if(stock.have(stack.item) < stock.cap() * 0.4f) return false;
        }
        return true;
    }

    /** Whether a turret of its own at a place has been firing in the last half minute. */
    private boolean fighting(Asset asset){
        //or about to: the other side's army, or the next wave, is more than home holds, or something was lost a moment ago
        if(homeShort || attacked()) return true;
        Building build = world.build(asset.x, asset.y);
        return build != null && clock - lastFired.get(build.pos(), -1e9f) < 60f * 30f;
    }

    /** Whether only drills on ore are being looked for, no factories. */
    private boolean minesOnly;

    /** Supply against demand, a second, of each item that has either. */
    private String ratesNote(){
        StringBuilder out = new StringBuilder();
        for(Item item : content.items()){
            if(supply[item.id] < 0.01f && demand[item.id] < 0.01f) continue;
            out.append(item.name).append(' ').append(Strings.fixed(supply[item.id], 1)).append('/').append(Strings.fixed(demand[item.id], 1)).append("  ");
        }
        return out.toString();
    }

    /**
     * Drills on every ore it can get at of what it mines already, the item it holds least of first: how much is mined
     * is not a matter of what is wanted. (Only of what it mines already: a first source of something new, wanted or
     * not, is a long search and a long line, and goes before everything else; that is for when the thing is wanted.)
     */
    private @Nullable Project mines(TeamData data){
        flows(data);
        Seq<Item> ores = useful().select(item -> builtWith(item) && hasSource(item) && ((!survey.ore(item).isEmpty() && mineFor(item) != null) || (!survey.floor(item).isEmpty() && drillFor(item) != null)));
        ores.sort(item -> stock.have(item) + stock.income(item) * 2f);
        minesOnly = true;
        try{
            for(Item item : ores){
                Project project = provide(item, data, 0);
                if(project != null || saving){
                    //waiting to pay for a drill is not having taken up all there is to take up
                    if(project == null) incomeDry = 0;
                    return project;
                }
            }
        }finally{
            minesOnly = false;
        }
        return null;
    }

    private @Nullable Project income(TeamData data){
        //a base that came with the map is given a minute to show what it brings in before anything is added to that
        if(inherited && clock < Stock.window) return null;
        if(Options.ratios.on()){
            //ore is taken up wherever there is any, whatever is short: every drill on ore it can get to, the item it holds
            //least of first. how much a factory is wanted is a matter of recipes; how much is mined is not
            Project mined = mines(data);
            if(mined != null || saving) return mined;
            //and factories by the recipes: what everything it has, means to have and is building takes a second against what
            //its sources bring in, the furthest short of what it takes first. what nothing is short of is not added to
            //what is only ever worked up by a factory, never built with, is not brought home: it is laid on for the factory
            //that is short of it, when that one is seen to
            Seq<Item> short_ = useful().select(item -> demand[item.id] - supply[item.id] > 0.05f && builtWith(item));
            short_.sort(item -> -(demand[item.id] - supply[item.id]) / Math.max(demand[item.id], 0.1f));
            notes.put("ratios", ratesNote());
            for(Item item : short_){
                Project project = provide(item, data, 0);
                if(project != null || saving) return project;
            }
            return null;
        }
        Seq<Item> useful = useful();
        //what the cores will hold least of first, going by what they hold and what came in over the last minute; and of
        //those what it has fewest sources of: what is running out is what everything else is waiting for. something
        //the cores have none of yet, but which is coming in fast, is not what is running out
        useful.sort(item -> Math.round(Math.min(stock.have(item) + stock.income(item) * 2f, stock.cap()) * 8f / Math.max(stock.cap(), 1)) * 100f + net.count(Net.Kind.mine, item) + net.count(Net.Kind.maker, item));
        //what the cores are nearly full of is worth nothing more: what more of it came in would be burned. (with no more to be
        //had of what is short, a base went on to put up five more silicon plants with its cores full of silicon, and spent on
        //them the beryllium its turrets were firing)
        useful.removeAll(item -> stock.have(item) >= stock.cap() * 0.8f && !wanted.contains(item));
        for(Item item : useful){
            Project project = provide(item, data, 0);
            if(project != null || saving) return project;
        }
        return null;
    }

    /** @param anywhere whether a place beyond its own ground will do, as when the map it plays asks it for a core */
    private @Nullable Project core(TeamData data, boolean anywhere){
        Block core = coreBlock(data);
        if(!Options.cores.on() || core == null || !ready("core")) return null;
        boolean before = needy;
        needy = needy || anywhere;
        Point2 site;
        try{
            site = findCoreSite(core, data);
        }finally{
            needy = before;
        }
        if(site == null){
            notes.put("core", "no free core zone" + coreWhy);
            retry.put("core", clock + retryPeriod * 4f);
            return null;
        }
        if(!pays(cost(core))){
            saveFor(core, cost(core));
            notes.put("core", "saving for " + core.name);
            return null;
        }
        Project project = new Project("core");
        project.parts.add(new Part(core, site.x, site.y, 0));
        notes.put("core", "laid out at " + site.x + "," + site.y);
        return project;
    }

    /**
     * Whether something can be paid for now. While one thing is being saved up for, the others may
     * still be built, as long as they take nothing of what that one is short of.
     */
    private boolean pays(ItemStack[] cost){
        if(!stock.covers(cost)) return false;
        //what holds the base is not kept waiting by what is being saved up for: a yard waits for what the turrets leave
        //(only while something threatens it: otherwise what a mine is being saved up for is kept from the turrets as from
        //anything else, or a base short of beryllium spends what its beryllium mines wait for on turrets, and never mines more)
        if(trying == Director.Goal.defence || trying == Director.Goal.cover || trying == Director.Goal.firstPost || trying == Director.Goal.arm) return true;
        for(ItemStack stack : cost){
            if(kept.contains(stack.item)) return false;
        }
        return true;
    }

    /** The goal being tried just now. */
    private @Nullable Director.Goal trying;

    /** Says that something is wanted and can't be paid for yet. */
    private void saveFor(Block block, ItemStack[] cost){
        //something else is being saved for already, and this is one of the others having its go meanwhile
        if(!kept.isEmpty()) return;
        saving = true;
        savingCost = cost;
        waitFor(block);
    }

    private int shift, firstShift;
    private final ObjectSet<Item> kept = new ObjectSet<>();
    /** A base that came with the map is watched for this long before anything is decided: what its lines bring in shows what it lacks. */
    static final float lookTime = 60f * 3f;
    /** Whether the base it plays came with buildings besides its cores. */
    private boolean inherited, looked, ventsKnown;
    /** How long a place where something of its own was destroyed counts as one the enemy has its guns on. */
    static final float fallenTime = 60f * 90f;
    /** Where buildings of its own were destroyed, and when. */
    private final IntFloatMap fallen = new IntFloatMap();
    /** The tiles of what it laid out to hold an attack off with. Those go up before anything else. */
    private final IntSet urgent = new IntSet();
    /** The tiles of first sources of what it can't do without: those go up next. Not the same thing: a turret under attack does not wait for a furnace. */
    private final IntSet firsts = new IntSet();
    /** Under attack: what the turret it is waiting to afford costs, and until when that is kept back from everything else. */
    private ItemStack[] guardCost = ItemStack.empty;
    private float guardUntil, needClock = -1f, lookTimer;
    private int[] guardNeed = {};
    private ItemStack[] savingCost = ItemStack.empty;

    /** How many times over a cost can be paid, keeping something back. */
    private int affordable(ItemStack[] cost, int keep){
        for(ItemStack stack : cost){
            if(kept.contains(stack.item)) return 0;
        }
        int times = 99;
        //what is kept back is for the ducts and nodes that go with it, which are built of beryllium
        for(ItemStack stack : cost) times = Math.min(times, (stock.free(stack.item) - (stack.item == Items.beryllium ? keep : 0)) / Math.max(stack.amount, 1));
        return Math.max(times, 0);
    }

    private static ItemStack[] sum(ItemStack[] first, ItemStack[] second){
        ItemSeq total = new ItemSeq();
        total.add(first);
        total.add(second);
        return total.toArray();
    }

    private void queue(TeamData data, Part part){
        //what was laid out on the same tile before gives way: a line led on anew lays its last ducts afresh
        for(BlockPlan old : data.plans){
            if(old.x == part.x && old.y == part.y){
                data.plans.remove(old);
                break;
            }
        }
        data.plans.addFirst(new BlockPlan(part.x, part.y, (short)part.rotation, part.block, part.config));
        int pos = Point2.pack(part.x, part.y);
        if(laid.add(pos)) built.add(pos);
    }

    //endregion
    //region blueprints

    private @Nullable Blueprint find(Kind kind){
        return blueprints.find(blueprint -> blueprint.kind() == kind && usable(blueprint));
    }

    /**
     * Whether a blueprint is one the enemy may build and can ever pay for: nothing in it costs an item
     * it has none of and no way of getting. Saving up for that would be waiting for ever.
     */
    private boolean usable(Blueprint blueprint){
        if(!blueprint.allowed(this::may)) return false;
        for(ItemStack stack : blueprint.needs()){
            if(!payable(stack)) return false;
        }
        return true;
    }

    /** Whether there is, or will be, enough of an item to pay this much of it. */
    private boolean payable(ItemStack stack){
        return stock.have(stack.item) >= stack.amount || stock.income(stack.item) > 0 || net.count(Net.Kind.mine, stack.item) + net.count(Net.Kind.maker, stack.item) > 0 || diggable(stack.item);
    }

    /** Whether a block is one the enemy may build and can ever pay for. Parts a layout can do without are left out if not. */
    boolean worth(Block block){
        if(!may(block)) return false;
        for(ItemStack stack : cost(block)){
            if(!payable(stack)) return false;
        }
        return true;
    }

    /**
     * What a part a layout can do without is put up as: the block the blueprint names, or for a wall
     * the best wall of that size the enemy may build and has the stuff to spare for. What is built of
     * something it is short of, and needs for more than walls, is not spared for them.
     * @return the block, or null if the part is to be left out
     */
    public @Nullable Block spared(Block wanted){
        if(sparedNow.containsKey(wanted)) return sparedNow.get(wanted);
        Block best = null;
        if(!(wanted instanceof Wall) || wanted instanceof AutoDoor){
            best = worth(wanted) && plentiful(wanted) ? wanted : null;
        }else{
            for(Block block : content.blocks()){
                if(!(block instanceof Wall) || block instanceof AutoDoor || block.size != wanted.size || block.consPower != null || !block.environmentBuildable() || !block.isPlaceable()) continue;
                if(worth(block) && plentiful(block) && (best == null || better(block, best))) best = block;
            }
        }
        sparedNow.put(wanted, best);
        return best;
    }

    private final ObjectMap<Block, Block> sparedNow = new ObjectMap<>();

    /** Whether one block stands up to more than another, tile for tile. */
    boolean better(Block one, Block other){
        return one.health / (float)(one.size * one.size) > other.health / (float)(other.size * other.size) * 1.05f;
    }

    /** Whether the cores hold a good deal of everything a block is built of that is not one of the things it always has coming in. */
    private boolean plentiful(Block block){
        for(ItemStack stack : block.requirements){
            if(!staples().contains(stack.item) && stock.have(stack.item) < stock.cap() / 2) return false;
        }
        return true;
    }

    /** Whether its builders can get an item out of a wall by hand: then there is always more of it to come. */
    private boolean diggable(Item item){
        if(!Options.handMine.on() || survey.ore(item).isEmpty()) return false;
        CoreBuild core = team().core();
        return core != null && ((CoreBlock)core.block).unitType.mineWalls && ((CoreBlock)core.block).unitType.mineTier >= item.hardness;
    }

    /** The blueprints that lay the same thing out as this one, in the same way or another, this one first. */
    private Seq<Blueprint> alike(Blueprint blueprint){
        Seq<Blueprint> out = Seq.with(blueprint);
        for(Blueprint other : blueprints){
            if(other != blueprint && other.main() == blueprint.main() && usable(other) && sameInputs(other, blueprint) && sameBlocks(other, blueprint)) out.add(other);
        }
        return out;
    }

    /** Whether two blueprints are made up of the same machines, whatever nodes and walls go with them. */
    private static boolean sameBlocks(Blueprint first, Blueprint second){
        return machines(first).equals(machines(second));
    }

    private static String machines(Blueprint blueprint){
        Seq<String> names = new Seq<>();
        for(Part part : blueprint.parts){
            if(part.block.hasItems || part.block.hasLiquids || part.block instanceof UnitBlock) names.add(part.block.name);
        }
        names.sort();
        return names.toString();
    }

    private static boolean sameInputs(Blueprint first, Blueprint second){
        for(Port port : first.ports){
            if(port.role == Role.in && !second.has(Role.in, port.item)) return false;
        }
        for(Port port : second.ports){
            if(port.role == Role.in && !first.has(Role.in, port.item)) return false;
        }
        return true;
    }

    /** A row of drills that can mine an item. */
    private @Nullable Blueprint mineFor(Item item){
        return blueprints.find(blueprint -> blueprint.kind() == Kind.mine && usable(blueprint)
            && blueprint.main() instanceof BeamDrill drill && drill.tier >= item.hardness && blueprint.has(Role.out, null));
    }

    /** A factory that makes an item, with the places its inputs come in and its product goes out. */
    private @Nullable Blueprint maker(Item item){
        return blueprints.find(blueprint -> blueprint.kind() == Kind.craft && usable(blueprint) && Rates.makes(blueprint.main(), item) > 0f
            && blueprint.has(Role.out, null) && inputs(blueprint));
    }

    /** Whether a blueprint has an input for every item its main block takes. */
    private boolean inputs(Blueprint blueprint){
        ItemStack[] needs = Rates.inputs(blueprint.main());
        if(needs == null) return true;
        for(ItemStack stack : needs){
            if(!blueprint.has(Role.in, stack.item)) return false;
        }
        return true;
    }

    /** A drill that stands on ore in the floor and can mine an item. */
    private @Nullable Blueprint drillFor(Item item){
        return blueprints.find(blueprint -> blueprint.kind() == Kind.drill && usable(blueprint) && blueprint.has(Role.out, null)
            && blueprint.main() instanceof Drill drill && drill.tier >= item.hardness && drill.blockedItem != item);
    }

    /** What to build to get a liquid: of what it may build, what gives the most of it. */
    private @Nullable Blueprint springFor(Liquid liquid){
        Seq<Blueprint> all = springsFor(liquid);
        return all.isEmpty() ? null : all.first();
    }

    /**
     * Everything the enemy may build that gives a liquid, what gives the most of it first; and of
     * equals the one that stands on a vent, which gets what it is made from out of the ground it
     * stands on. One that needs no vent is what is left when the vents are all taken.
     */
    private Seq<Blueprint> springsFor(Liquid liquid){
        Seq<Blueprint> out = new Seq<>();
        ObjectFloatMap<Blueprint> gives = new ObjectFloatMap<>();
        for(Blueprint blueprint : blueprints){
            if(blueprint.addon() || !usable(blueprint)) continue;
            for(Plumbing.Tap spare : Plumbing.gives(blueprint.parts)){
                if(spare.liquid != liquid) continue;
                if(!out.contains(blueprint)) out.add(blueprint);
                gives.put(blueprint, Math.max(gives.get(blueprint, 0f), spare.rate));
            }
        }
        out.sort(blueprint -> -gives.get(blueprint, 0f) - (Planner.ventBlock(blueprint) != null ? 0.01f : 0f));
        return out;
    }

    /**
     * The first thing a blueprint can't do without that the enemy has no way of getting as things
     * stand: an item it is fed with that is on none of its lines, or one it is built from that it
     * has none of and nothing brings in.
     */
    private @Nullable Item lacks(Blueprint blueprint){
        for(Port port : blueprint.ports){
            if(port.role == Role.in && !available(port.item)) return port.item;
        }
        for(ItemStack stack : blueprint.cost()){
            if(stock.have(stack.item) < stack.amount && stock.income(stack.item) == 0 && !hasSource(stack.item) && !diggable(stack.item)) return stack.item;
        }
        return null;
    }

    /** The unit a yard blueprint turns out in the end: what its factory makes, as its refabricators leave it. */
    private @Nullable UnitType finalUnit(Blueprint blueprint){
        if(!(blueprint.main() instanceof UnitFactory factory) || factory.plans.isEmpty()) return null;
        UnitType unit = factory.plans.first().unit;
        for(Part part : blueprint.parts){
            if(!(part.block instanceof Reconstructor refabricator)) continue;
            for(UnitType[] upgrade : refabricator.upgrades){
                if(upgrade[0] == unit){
                    unit = upgrade[1];
                    break;
                }
            }
        }
        return unit;
    }

    /**
     * The yards it may build, the ones that turn out better units first, and of equals the one whose
     * unit it has fewest of. One for each kind: other ways of laying the same thing out are left to
     * the fitting.
     */
    private Seq<Blueprint> yardKinds(TeamData data){
        Seq<Blueprint> out = new Seq<>();
        int cap = unitCap(data);
        for(Blueprint blueprint : blueprints){
            UnitType unit = finalUnit(blueprint);
            if(blueprint.kind() != Kind.yard || unit == null || !blueprint.allowed(this::may) || !may(unit) || data.countType(unit) >= cap) continue;
            if(out.contains(other -> other.main() == blueprint.main() && finalUnit(other) == unit)) continue;
            out.add(blueprint);
        }
        ObjectIntMap<UnitType> yards = new ObjectIntMap<>();
        int standing = 0;
        for(Net.Thing thing : net.things){
            if(thing.kind == Net.Kind.yard && world.build(thing.x, thing.y) instanceof UnitFactory.UnitFactoryBuild yard && yard.team == team()){
                yards.increment(turnsOut(yard));
                standing++;
            }
        }
        //where some kinds of unit can get to the other side's cores and some can't, as across water, only the ones that can.
        //where none it may build can, its units can only stand guard at home: one yard of them is enough, and what more would
        //cost goes into what will let it build some that can
        //(a yard that can only stand guard is still worth its price early: holding it back until the cores were well filled
        //lost the third sector's opening in 9 of 16 tries against 3 of 16, its tanks' armour being what the raiders there
        //can least get through)
        if(out.contains(blueprint -> reaches(finalUnit(blueprint)))) out.removeAll(blueprint -> !reaches(finalUnit(blueprint)));
        else if(standing > 0) out.clear();
        //a second yard for a unit it has one for only when that army is well short of what it may number: the one yard is
        //then not keeping up with what is lost. yards that stand idle because their unit is at its number are so much waste
        out.removeAll(blueprint -> yards.get(finalUnit(blueprint), 0) > 0 && data.countType(finalUnit(blueprint)) >= cap * 0.6f);
        //of yards as good as each other, the one whose units are made of what its cores hold plenty of first: a unit made of
        //what everything else is short of takes it from the drills and turrets that wait for it, unit after unit
        //(where beryllium ran short and silicon stood full, three tank yards took more beryllium than its drills brought in)
        out.sort(blueprint -> -1000f * blueprint.parts.count(part -> part.block instanceof UnitBlock) + Math.min(scarceCost(blueprint) / 20f, 900f) + data.countType(finalUnit(blueprint)));
        return out;
    }

    /**
     * What one unit of a yard's kind costs, each item counted by how short its cores are of it: an item they are full
     * of counts once, one they hold a twentieth of or less counts twenty times.
     */
    private float scarceCost(Blueprint blueprint){
        if(!(blueprint.main() instanceof UnitFactory factory)) return 0f;
        UnitType unit = finalUnit(blueprint);
        UnitFactory.UnitPlan plan = factory.plans.find(p -> p.unit == unit);
        if(plan == null) plan = factory.plans.first();
        float cost = 0f;
        for(ItemStack stack : plan.requirements) cost += stack.amount / Math.max(stock.have(stack.item) / (float)Math.max(stock.cap(), 1), 0.05f);
        return cost;
    }

    private final IntIntMap reachNow = new IntIntMap();
    private float reachClock = -1f;

    /**
     * Whether a kind of unit can get from one of its cores to one of the other side's: by air always,
     * and otherwise over whatever ground that kind of unit crosses, as the game's own path-finding has
     * it. Tanks stop at deep water and at walls; ships that hover cross water; walkers on legs cross
     * both. Looked into afresh now and then: maps open up, and bases grow across the ways.
     */
    boolean reaches(@Nullable UnitType type){
        if(type == null || type.flying) return true;
        Seq<CoreBuild> theirs = foe().cores(), ours = team().cores();
        if(theirs.isEmpty() || ours.isEmpty() || pathfinder == null) return true;
        if(clock - reachClock > 60f * 60f){
            reachNow.clear();
            reachClock = clock;
        }
        int known = reachNow.get(type.pathCostId, -1);
        if(known != -1) return known == 1;

        //the unit's own cost; its id counts in the control path-finder's list, not in the field path-finder's, whose order
        //differs (read through the wrong list, ships that hover were taken for walkers on legs and the walkers for boats)
        mindustry.ai.Pathfinder.PathCost cost = type.pathCost != null ? type.pathCost : mindustry.ai.ControlPathfinder.costTypes.get(type.pathCostId);
        int width = world.width(), height = world.height(), side = team().id;
        boolean[] seen = new boolean[width * height];
        IntQueue queue = new IntQueue();
        for(CoreBuild core : ours){
            //from the ground round a core: the core itself is no ground for its own units
            int offset = -(core.block.size - 1) / 2 - 1, across = core.block.size + 2;
            for(int dx = 0; dx < across; dx++){
                for(int dy = 0; dy < across; dy++){
                    int x = core.tileX() + offset + dx, y = core.tileY() + offset + dy;
                    if(x < 0 || y < 0 || x >= width || y >= height || seen[x + y * width] || cost.getCost(side, pathfinder.get(x, y)) < 0) continue;
                    seen[x + y * width] = true;
                    queue.addLast(x + y * width);
                }
            }
        }
        boolean found = false;
        while(!queue.isEmpty() && !found){
            int at = queue.removeFirst(), ax = at % width, ay = at / width;
            for(int d = 0; d < 4 && !found; d++){
                int x = ax + Geometry.d4x(d), y = ay + Geometry.d4y(d);
                if(x < 0 || y < 0 || x >= width || y >= height || seen[x + y * width] || !world.isInMapArea(x, y)) continue;
                seen[x + y * width] = true;
                Building there = world.build(x, y);
                if(there instanceof CoreBuild && there.team == foe()){
                    found = true;
                }else if(cost.getCost(side, pathfinder.get(x, y)) >= 0){
                    queue.addLast(x + y * width);
                }
            }
        }
        reachNow.put(type.pathCostId, found ? 1 : 0);
        return found;
    }

    /** Every item something the enemy may build is built from or fed with. */
    private Seq<Item> useful(){
        Seq<Item> out = new Seq<>();
        for(Blueprint blueprint : blueprints){
            if(!blueprint.allowed(this::may)) continue;
            for(ItemStack stack : blueprint.cost()){
                if(!out.contains(stack.item)) out.add(stack.item);
            }
            for(Port port : blueprint.ports){
                if(port.item != null && port.item.buildable && !out.contains(port.item)) out.add(port.item);
            }
        }
        for(Item item : wanted) if(!out.contains(item)) out.add(item);
        return out;
    }

    /** Whether everything a blueprint has an input for is on one of the enemy's lines. */
    private boolean carried(Blueprint blueprint){
        for(Port port : blueprint.ports){
            if(port.role == Role.in && net.count(Net.Kind.mine, port.item) + net.count(Net.Kind.maker, port.item) == 0 && seen(port.item).isEmpty()) return false;
        }
        return true;
    }

    /** New cores are of the smallest kind the enemy has. */
    private @Nullable Block coreBlock(TeamData data){
        Block best = null;
        for(CoreBuild core : data.cores){
            if((best == null || core.block.size < best.size) && may(core.block)) best = core.block;
        }
        return best;
    }

    //endregion
    //region where

    /**
     * The tiles of the enemy's lines that carry an item, thinned out to one for each stretch of ground:
     * a few hundred at most, however big the base. With them goes a coarse map of where they are, so
     * that how far a place is from the nearest of them need not be worked out by going through them all.
     */
    private Seq<int[]> carriedSpots(Item item){
        Seq<int[]> out = spots.get(item);
        if(out != null) return out;
        long prof = Prof.start();
        out = new Seq<>();
        IntSet seen = new IntSet();
        IntSet all = seen(item);
        //bigger stretches on a bigger base
        int cell = net.things.size + all.size / 40 > 400 ? 12 : 6;
        for(var entry : net.carried(item)){
            int x = Point2.x(entry.key), y = Point2.y(entry.key);
            if(entry.value > 0.05f && seen.add(Point2.pack(x / cell, y / cell))) out.add(new int[]{x, y});
        }
        IntSet.IntSetIterator old = all.iterator();
        while(old.hasNext){
            int pos = old.next(), x = Point2.x(pos), y = Point2.y(pos);
            if(seen.add(Point2.pack(x / cell, y / cell))) out.add(new int[]{x, y});
        }
        spots.put(item, out);

        //how far each square of the map is from the nearest of them, spreading out from where they are
        int width = world.width() / nearCell + 1, height = world.height() / nearCell + 1;
        short[] far = new short[width * height];
        java.util.Arrays.fill(far, Short.MAX_VALUE);
        IntQueue queue = new IntQueue();
        for(int[] at : out){
            int square = at[0] / nearCell + at[1] / nearCell * width;
            if(far[square] != 0){
                far[square] = 0;
                queue.addLast(square);
            }
        }
        while(!queue.isEmpty()){
            int square = queue.removeFirst(), sx = square % width, sy = square / width;
            for(int d = 0; d < 4; d++){
                int x = sx + Geometry.d4x(d), y = sy + Geometry.d4y(d);
                if(x < 0 || y < 0 || x >= width || y >= height || far[x + y * width] <= far[square] + 1) continue;
                far[x + y * width] = (short)(far[square] + 1);
                queue.addLast(x + y * width);
            }
        }
        nearness.put(item, far);
        Prof.end("spots", prof);
        return out;
    }

    static final int nearCell = 4;
    private final ObjectMap<Item, short[]> nearness = new ObjectMap<>();

    /** About how far a place is from the nearest line that carries an item, as the crow flies, or a very large number if none does. */
    int toLine(Item item, int x, int y){
        if(carriedSpots(item).isEmpty()) return Integer.MAX_VALUE;
        short[] far = nearness.get(item);
        int width = world.width() / nearCell + 1, square = x / nearCell + y / nearCell * width;
        return far == null || square < 0 || square >= far.length ? Integer.MAX_VALUE : far[square] * nearCell;
    }

    /** Whether everything a blueprint is fed with goes by close enough to a place to be branched off to it. */
    private boolean supplied(Blueprint blueprint, int x, int y){
        for(Port port : blueprint.ports){
            if(port.role == Role.in && toLine(port.item, x, y) > Options.feedLimit.get() * 0.7f) return false;
        }
        return true;
    }

    /**
     * How much room there is around every tile near its base: how many tiles it is, in any direction,
     * to the nearest one nothing solid may go on. A base that has grown for a while is crowded
     * wherever its lines run, and that is where the things its lines feed were looked for a place:
     * with this, each such place is moved to the nearest ground with room enough round it.
     */
    private short[] room = {};
    private int roomX, roomY, roomW, roomH;
    private float roomClock = -1f;
    private @Nullable Building roomAbout;

    private void measureRoom(){
        //good for a few seconds, and for the core whose turn it is: little changes in that time, and going over the
        //ground costs more than anything else that is done to find a place
        Building about = territory.focus != null ? territory.focus : team().core();
        if(roomClock >= 0f && clock - roomClock < 60f * 3f && about == roomAbout) return;
        roomClock = clock;
        roomAbout = about;
        //round everything it has built, and some way beyond
        int x0 = world.width(), y0 = world.height(), x1 = 0, y1 = 0;
        for(Building build : team().data().buildings){
            if(!world.isInMapArea(build.tileX(), build.tileY())) continue;
            x0 = Math.min(x0, build.tileX());
            y0 = Math.min(y0, build.tileY());
            x1 = Math.max(x1, build.tileX());
            y1 = Math.max(y1, build.tileY());
        }
        int margin = 14;
        //a base that covers a big map is not gone over whole: only the part round the core whose turn it is
        Building centre = about;
        if((long)(x1 - x0 + 2 * margin) * (y1 - y0 + 2 * margin) > 110 * 110 && centre != null){
            x0 = Math.max(x0, centre.tileX() - 42);
            x1 = Math.min(x1, centre.tileX() + 42);
            y0 = Math.max(y0, centre.tileY() - 42);
            y1 = Math.min(y1, centre.tileY() + 42);
        }
        roomX = Math.max(x0 - margin, 0);
        roomY = Math.max(y0 - margin, 0);
        roomW = Math.max(Math.min(x1 + margin, world.width() - 1) - roomX + 1, 0);
        roomH = Math.max(Math.min(y1 + margin, world.height() - 1) - roomY + 1, 0);
        if(room.length < roomW * roomH) room = new short[roomW * roomH];
        //how far to the nearest tile that is taken, going over the ground twice: down and to the right, then back
        for(int y = 0; y < roomH; y++){
            for(int x = 0; x < roomW; x++){
                int best = 0;
                if(ground.open(roomX + x, roomY + y) && ground.solidRule.allows(roomX + x, roomY + y)){
                    best = x == 0 || y == 0 || x == roomW - 1 ? 1 : Math.min(Math.min(room[x - 1 + y * roomW], room[x + (y - 1) * roomW]), Math.min(room[x - 1 + (y - 1) * roomW], room[x + 1 + (y - 1) * roomW])) + 1;
                }
                room[x + y * roomW] = (short)best;
            }
        }
        for(int y = roomH - 1; y >= 0; y--){
            for(int x = roomW - 1; x >= 0; x--){
                int here = room[x + y * roomW];
                if(here == 0) continue;
                int best = x == roomW - 1 || y == roomH - 1 || x == 0 ? 1 : Math.min(Math.min(room[x + 1 + y * roomW], room[x + (y + 1) * roomW]), Math.min(room[x + 1 + (y + 1) * roomW], room[x - 1 + (y + 1) * roomW])) + 1;
                if(best < here) room[x + y * roomW] = (short)best;
            }
        }
    }

    /** How many tiles it is from a tile to the nearest one that is taken: 0 if it is taken itself. */
    public int room(int x, int y){
        measureRoom();
        x -= roomX;
        y -= roomY;
        return x < 0 || y < 0 || x >= roomW || y >= roomH ? 0 : room[x + y * roomW];
    }

    /** The tile nearest a place with so much room round it, no more than so far off, as a packed position; or -1. */
    private int roomNear(int x, int y, int need, int within){
        for(int radius = 0; radius <= within; radius++){
            int best = -1, most = 0;
            for(int dx = -radius; dx <= radius; dx++){
                for(int dy = -radius; dy <= radius; dy++){
                    if(Math.max(Math.abs(dx), Math.abs(dy)) != radius) continue;
                    int has = room(x + dx, y + dy);
                    if(has >= need && has > most){
                        most = has;
                        best = Point2.pack(x + dx, y + dy);
                    }
                }
            }
            if(best != -1) return best;
        }
        return -1;
    }

    /** How much room a blueprint wants round its main block to begin with: that block and a tile or two beside it. The rest is fitted in around. */
    private static int reach(Blueprint blueprint){
        return blueprint.main().size / 2 + 2;
    }

    /**
     * Moves places that were picked for what is near them to where there is room to build. Each goes
     * to the nearest ground with room enough for the blueprint; one with no such ground near is left
     * where it was, and comes after the rest. Places that end up beside each other count once.
     */
    private Seq<int[]> settle(Seq<int[]> places, Blueprint blueprint, int within, boolean dropShut, Boolf<int[]> good){
        int need = reach(blueprint);
        Seq<int[]> moved = new Seq<>(), left = new Seq<>();
        IntSet once = new IntSet();
        for(int[] at : places){
            int to = roomNear(at[0], at[1], need, within);
            if(to == -1) to = roomNear(at[0], at[1], Math.max(need - 1, 2), within);
            //no ground near it to build on at all, as where the place is out on the water
            if(to == -1 && dropShut) continue;
            int[] now = to == -1 ? at : new int[]{Point2.x(to), Point2.y(to)};
            if(!once.add(Point2.pack(now[0] / 4, now[1] / 4))) continue;
            if(to != -1 && good.get(now)) moved.add(now); else left.add(at);
        }
        moved.addAll(left);
        return moved;
    }

    /** Whether most of the ground around a place is open. */
    private boolean roomy(int x, int y){
        int open = 0;
        for(int dx = -4; dx <= 4; dx++){
            for(int dy = -4; dy <= 4; dy++){
                if(ground.open(x + dx, y + dy)) open++;
            }
        }
        return open >= 54;
    }

    /** Where a unit yard goes: by the enemy's lines, where all of its supplies are close, nearest home first. */
    private Seq<int[]> yardSpots(Blueprint blueprint){
        Seq<int[]> out = new Seq<>();
        for(Port port : blueprint.ports){
            if(port.role != Role.in) continue;
            for(int[] at : carriedSpots(port.item)){
                if(supplied(blueprint, at[0], at[1])) out.add(at);
            }
        }
        IntSet once = new IntSet();
        out.removeAll(at -> !once.add(Point2.pack(at[0], at[1])));
        //and where what it needs piped can be had
        Seq<Plumbing.Tap> needs = Plumbing.needs(blueprint.parts);
        if(needs.size > 0) out.removeAll(at -> !piped(needs, at[0], at[1]));
        //where the base is already crowded there is no fitting anything in; if it is crowded everywhere, try all the same
        Seq<int[]> roomy = out.select(at -> roomy(at[0], at[1]));
        if(roomy.size > 0) out = roomy;
        //worked out once for each: the sorting asks many times
        ObjectFloatMap<int[]> score = new ObjectFloatMap<>();
        for(int[] at : out){
            float far = territory.near(at[0], at[1]) * 0.3f;
            for(Port port : blueprint.ports){
                if(port.role == Role.in) far += toLine(port.item, at[0], at[1]);
            }
            score.put(at, far);
        }
        out.sort(at -> score.get(at, 0f));
        if(out.size > 24) out.truncate(24);
        //by its lines is where a base is fullest: each place goes to the nearest ground with room for a yard
        out = settle(out, blueprint, 16, false, at -> supplied(blueprint, at[0], at[1]) && (needs.size == 0 || piped(needs, at[0], at[1])));
        if(out.size > 12) out.truncate(12);
        //first of all right beside a factory that makes what it takes: the factory hands it over with no line at all, and
        //nothing has to be led across the base. (with its cores full of silicon a whole quarter of an hour, a sector's yards
        //stood empty: each had been put where a line passed, and no line from the furnaces could get to them again)
        Seq<int[]> beside = new Seq<>();
        int size = blueprint.main().size;
        //(only once its turrets hold home by themselves: a yard there takes the silicon at the source, before the turrets
        //get any, and a sector's opening was lost to the first raids with three turrets and a yard at the furnace)
        if(turretsHold) for(Net.Thing thing : net.things){
            if(thing.kind != Net.Kind.maker || thing.item == null || !blueprint.has(Role.in, thing.item)) continue;
            Building maker = works(thing);
            if(maker == null) continue;
            int reach = maker.block.size / 2 + size / 2 + 1;
            for(int d = 0; d < 4; d++){
                for(int slide = -1; slide <= 1; slide++){
                    int x = maker.tileX() + Geometry.d4x(d) * reach + Geometry.d4y(d) * slide, y = maker.tileY() + Geometry.d4y(d) * reach + Geometry.d4x(d) * slide;
                    beside.add(new int[]{x, y});
                }
            }
        }
        beside.removeAll(at -> needs.size > 0 && !piped(needs, at[0], at[1]));
        beside.addAll(out);
        return beside;
    }

    /**
     * Where the next turret goes: on one of the stretches of the ways the enemy's army takes to the
     * player, as far as those run over its own ground and its lines reach. First on the stretches no
     * turret watches over yet, the nearest home first, so each one goes up a little further out than
     * the last. With all of them watched over, the furthest forward get more.
     */
    /**
     * The ways the other side's ground units come at its base: from each place they set out from (where the waves of a
     * map with waves appear, and the other side's cores) down the shortest way over ground to its nearest core, as tiles
     * from there to here. Worked out again every minute. Its own buildings are no end of the way: they are what the other
     * side's units fire on as they come.
     */
    private final Seq<IntSeq> approaches = new Seq<>();
    private float approachClock = -1e9f;

    private void approaches(TeamData data){
        if(clock - approachClock < 60f * 60f) return;
        approachClock = clock;
        approaches.clear();
        int width = world.width(), height = world.height();
        int[] far = new int[width * height];
        java.util.Arrays.fill(far, Integer.MAX_VALUE);
        IntQueue queue = new IntQueue();
        for(CoreBuild core : data.cores){
            core.tile.getLinkedTiles(tile -> {
                far[tile.array()] = 0;
                queue.addLast(tile.array());
            });
        }
        while(!queue.isEmpty()){
            int at = queue.removeFirst(), ax = at % width, ay = at / width;
            for(int d = 0; d < 4; d++){
                int x = ax + Geometry.d4x(d), y = ay + Geometry.d4y(d);
                if(x < 0 || y < 0 || x >= width || y >= height) continue;
                int next = x + y * width;
                if(far[next] != Integer.MAX_VALUE) continue;
                Tile tile = world.tiles.geti(next);
                if((tile.solid() && tile.build == null) || tile.floor().isDeep()) continue;
                far[next] = far[at] + 1;
                queue.addLast(next);
            }
        }
        Seq<Tile> from = new Seq<>();
        if(state.rules.waves && state.rules.waveTeam != team()) from.addAll(spawner.getSpawns());
        for(CoreBuild core : foe().cores()) from.add(core.tile);
        for(Tile start : from){
            //the nearest tile to it the way reaches
            int at = -1;
            for(int r = 0; r <= 6 && at == -1; r++){
                for(int dx = -r; dx <= r && at == -1; dx++){
                    for(int dy = -r; dy <= r; dy++){
                        int x = start.x + dx, y = start.y + dy;
                        if(x >= 0 && y >= 0 && x < width && y < height && far[x + y * width] != Integer.MAX_VALUE){
                            at = x + y * width;
                            break;
                        }
                    }
                }
            }
            if(at == -1) continue;
            IntSeq path = new IntSeq();
            while(far[at] > 0 && path.size < 4000){
                path.add(at);
                int ax = at % width, ay = at / width, best = at;
                for(int d = 0; d < 4; d++){
                    int x = ax + Geometry.d4x(d), y = ay + Geometry.d4y(d);
                    if(x >= 0 && y >= 0 && x < width && y < height && far[x + y * width] < far[best]) best = x + y * width;
                }
                if(best == at) break;
                at = best;
            }
            if(path.size > 0) approaches.add(path);
        }
    }

    /**
     * Where turrets hold a way the other side's ground units come by: on each way, a little short of where they first
     * come within reach of what it has built, towards home, so that what they meet first is under the guns of all of
     * them together and not of one at a time.
     */
    private Seq<int[]> approachSpots(TeamData data, float range){
        approaches(data);
        Seq<int[]> out = new Seq<>();
        int width = world.width();
        for(IntSeq path : approaches){
            int contact = -1;
            for(int i = 0; i < path.size && contact == -1; i += 2){
                int x = path.get(i) % width, y = path.get(i) / width;
                if(indexer.findTile(team(), x * tilesize, y * tilesize, tilesize * 10f, build -> !(build instanceof ConstructBuild)) != null) contact = i;
            }
            if(contact == -1) continue;
            int at = path.get(Math.min(contact + (int)(range * 0.5f / tilesize), path.size - 1)), x = at % width, y = at / width;
            if(!out.contains(other -> Math.abs(other[0] - x) + Math.abs(other[1] - y) < 12)) out.add(new int[]{x, y});
        }
        return out;
    }

    private Seq<int[]> postSpots(Blueprint blueprint, TeamData data){
        Block main = blueprint.main();
        float cover = main instanceof BaseTurret turret ? turret.range * 0.85f : tilesize * 20f;
        Seq<Plumbing.Tap> needs = Plumbing.needs(blueprint.parts);
        Boolf<int[]> usable = at -> deadZones.get(Point2.pack(at[0], at[1]), 0f) <= clock && supplied(blueprint, at[0], at[1]) && (needs.size == 0 || piped(needs, at[0], at[1]));
        Seq<int[]> out = new Seq<>();
        //home does not hold against what the other side has: the turrets go where its ground units come in, all together
        if(homeShort){
            for(int[] at : approachSpots(data, main instanceof BaseTurret turret ? turret.range : tilesize * 20f)){
                if(usable.get(at)) out.add(at);
            }
        }
        boolean pressing = attacked() || director.posture == Director.Posture.pressed;
        String spotsNote;
        //how far from the place it was meant for a turret may go to find room
        int within = 6;

        //zones where what it builds keeps being lost: a turret over them comes before anything else goes there again
        if(!coverFirst.isEmpty()){
            IntSet.IntSetIterator each = coverFirst.iterator();
            while(each.hasNext){
                //(whether one turret has it under its guns already is not the question: how many it takes there was worked out
                //by what comes at it, and it has fewer)
                Atlas.Zone zone = atlas.zones.get(each.next());
                if(usable.get(new int[]{zone.x(), zone.y()})) out.add(new int[]{zone.x(), zone.y()});
            }
        }
        if(pressing){
            //being attacked: where it is losing things. its cores, if no turret with anything to fire has them under its
            //guns; then the places things fell at that none has, from the inside out: a turret near home has the ones
            //before it and the core's stock behind it, one far out has neither. and where things still fall though a
            //turret stands over them, that one does not hold: more there
            Seq<int[]> hit = new Seq<>();
            for(CoreBuild core : data.cores){
                if(world.isInMapArea(core.tileX(), core.tileY()) && !guarded(core.x, core.y)) hit.add(new int[]{core.tileX(), core.tileY(), -1});
            }
            IntSet cells = new IntSet(), fresh = new IntSet();
            //only where more has been lost lately, within what a turret there would cover, than the turret costs: raiders that
            //pick off a drill here and a duct there cost less than a turret at each place (the third sector put up fifteen
            //breaches, each the graphite of two and a half of its ships, after raids on its outlying drills)
            float price = total(main.requirements) * state.rules.buildCostMultiplier;
            int around = Math.max(1, Math.round(cover / tilesize / 8f));
            for(var entry : fallen){
                if(clock - entry.value >= fallenTime) continue;
                int cell = Point2.pack(Point2.x(entry.key) / 8, Point2.y(entry.key) / 8);
                if(cells.contains(cell) || lostNear(Point2.x(cell), Point2.y(cell), around) < price) continue;
                cells.add(cell);
                if(clock - entry.value < 60f * 30f) fresh.add(cell);
            }
            //what of its own stands nearest the units it has seen coming is where they will strike next. (counting such a place
            //as one just hit, which one turret alone does not hold, was tried: 17 of 48 openings of the third sector lost against 11)
            if(clock < sightedUntil){
                for(int i = 0; i < sightedAt.size; i++) cells.add(Point2.pack(Point2.x(sightedAt.get(i)) / 8, Point2.y(sightedAt.get(i)) / 8));
            }
            IntSet.IntSetIterator each = cells.iterator();
            while(each.hasNext){
                int cell = each.next(), x = Point2.x(cell) * 8 + 4, y = Point2.y(cell) * 8 + 4;
                CoreBuild near = Geometry.findClosest(x * tilesize, y * tilesize, data.cores);
                if(near == null) continue;
                int[] turrets = {0};
                indexer.eachBlock(team(), x * tilesize, y * tilesize, tilesize * 10f, Brain::isTurret, build -> turrets[0]++);
                boolean holding = guarded(x * tilesize, y * tilesize) && !(fresh.contains(cell) && turrets[0] < Options.turretsPerStretch.get());
                if(holding) continue;
                //a turret reaches well past where it stands: it goes up some way back towards home from where things fall, and
                //not in the middle of the fighting, where what is being built is shot down before it stands. from there it still
                //has that place under its guns, and the way between as well
                float far = near.dst(x * tilesize, y * tilesize) / tilesize, back = Math.min(far, cover / tilesize * 0.5f);
                int bx = x, by = y;
                if(far > 0.5f){
                    bx = Math.round(x + (near.tileX() - x) * back / far);
                    by = Math.round(y + (near.tileY() - y) * back / far);
                    //(pushing it on back until out of reach of their units there was tried: it then had nothing it lived on under
                    //its guns, and lost 36 of 48 openings of the third sector against 13)
                }
                hit.add(new int[]{bx, by, (int)(far - back)});
            }
            hit.sort(at -> at[2]);
            spotsNote = cells.size + " places hit, " + hit.size + " of them and its cores not held";
            for(int[] at : hit) if(usable.get(at)) out.add(new int[]{at[0], at[1]});
            if(out.size > 3) out.truncate(3);
        }else if(director.posture == Director.Posture.front && frontAt != -1){
            //the armies at a stand: turrets on the way between home and the front, nearest the front first, several on a
            //stretch, so that the line holds and can be pushed on from there
            int fx = Point2.x(frontAt), fy = Point2.y(frontAt);
            Seq<Zone> zones = new Seq<>();
            for(CoreBuild from : data.cores){
                if(world.isInMapArea(from.tileX(), from.tileY())) zones.addAll(territory.zones(territory.lane(from), 6));
            }
            zones.removeAll(zone -> Math.max(Math.abs(zone.x - fx), Math.abs(zone.y - fy)) > 45 || !usable.get(new int[]{zone.x, zone.y}));
            zones.sort(zone -> Mathf.dst(zone.x, zone.y, fx, fy));
            for(Zone zone : zones){
                if(!covered(zone.x * tilesize, zone.y * tilesize, cover)) out.add(new int[]{zone.x, zone.y});
            }
            for(Zone zone : zones){
                int[] turrets = {0};
                indexer.eachBlock(team(), zone.x * tilesize, zone.y * tilesize, tilesize * 8f, Brain::isTurret, build -> turrets[0]++);
                if(turrets[0] < Options.turretsPerStretch.get()) out.add(new int[]{zone.x, zone.y});
            }
            //no way to it over land: as near it as its lines reach
            if(out.isEmpty()) out.add(new int[]{fx, fy});
            spotsNote = "front at " + fx + "," + fy + ", " + zones.size + " stretches near it";
        }else if(net.count(Net.Kind.post, null) < data.cores.size){
            //its first turret of its own goes up by each core, before anything else, whatever stands there already
            for(CoreBuild core : data.cores){
                if(world.isInMapArea(core.tileX(), core.tileY()) && usable.get(new int[]{core.tileX(), core.tileY()})) out.add(new int[]{core.tileX(), core.tileY()});
            }
            spotsNote = "first turret by the core";
            //nothing solid goes right by a core: the room for it is a little way off
            within = 14;
        }else{
            //otherwise a few turrets, roughly: its cores, and each patch of what it lives on that has a yard in it or a good
            //deal else, where no turret has it under its guns; one turret for each, and no more than a few for each core in
            //all. the rest goes into units; more turrets come where it is hit, and at the front
            if(net.count(Net.Kind.post, null) < coverPerCore * data.cores.size){
                for(CoreBuild core : data.cores){
                    if(world.isInMapArea(core.tileX(), core.tileY()) && !covered(core.x, core.y, cover) && usable.get(new int[]{core.tileX(), core.tileY()})) out.add(new int[]{core.tileX(), core.tileY()});
                }
                Seq<Asset> exposed = assets.select(asset -> (asset.kind == Net.Kind.yard || asset.kind == Net.Kind.maker || asset.kind == Net.Kind.mine)
                    && asset.stage != Asset.Stage.laid && asset.stage != Asset.Stage.lost && usable.get(new int[]{asset.x, asset.y}) && !covered(asset.x * tilesize, asset.y * tilesize, cover));
                IntIntMap weight = new IntIntMap();
                IntMap<Asset> first = new IntMap<>();
                int cell = (int)(cover / tilesize);
                for(Asset asset : exposed){
                    int key = Point2.pack(asset.x / cell, asset.y / cell);
                    weight.increment(key, asset.kind == Net.Kind.yard ? 3 : 1);
                    if(!first.containsKey(key)) first.put(key, asset);
                }
                Seq<Asset> patches = new Seq<>();
                for(var entry : weight) if(entry.value >= 3) patches.add(first.get(entry.key));
                //the nearest the other side first: that is where it comes from
                patches.sort(asset -> -territory.advance(asset.x, asset.y));
                for(Asset asset : patches) out.add(new int[]{asset.x, asset.y});
            }
            spotsNote = "to cover: " + out.size;
        }
        //a turret reaches a long way: it need not stand on the spot it watches over, and goes where there is room near it.
        //a place with no ground near it to build on, such as one out on the water, is no place for one
        if(out.size > 6) out.truncate(6);
        int found = out.size;
        out = settle(out, blueprint, within, true, usable);
        notes.put("post-places", spotsNote + "; " + found + " places its lines reach, " + out.size + " with ground to build on near");
        return out;
    }

    /**
     * Works out, for every square of the map, how many squares it is from the nearest ground the player's
     * turrets can fire on. Nothing else the player has built is kept away from: a duct or a wall is only
     * something to go round, and to treat it as a danger would let a few ducts laid across the enemy's
     * way stop it for good.
     */
    private void markDanger(){
        dangerWidth = world.width() / dangerCell + 1;
        int height = world.height() / dangerCell + 1;
        if(danger.length != dangerWidth * height) danger = new short[dangerWidth * height];
        java.util.Arrays.fill(danger, (short)dangerFar);
        IntQueue queue = new IntQueue();
        for(TeamData other : state.teams.active){
            if(other.team == team() || other.team == Team.derelict) continue;
            for(Building build : other.buildings){
                if(!(build.block instanceof BaseTurret turret) || build.block.privileged) continue;
                int cx = build.tileX() / dangerCell, cy = build.tileY() / dangerCell;
                //a turret holds all the ground it can fire on
                int around = (int)(turret.range / tilesize / dangerCell) + 1;
                for(int dx = -around; dx <= around; dx++){
                    for(int dy = -around; dy <= around; dy++){
                        int x = cx + dx, y = cy + dy;
                        if(x < 0 || y < 0 || x >= dangerWidth || y >= height || dx * dx + dy * dy > around * around + 1) continue;
                        int cell = x + y * dangerWidth;
                        if(danger[cell] != 0){
                            danger[cell] = 0;
                            queue.addLast(cell);
                        }
                    }
                }
            }
        }
        while(!queue.isEmpty()){
            int cell = queue.removeFirst(), cx = cell % dangerWidth, cy = cell / dangerWidth;
            if(danger[cell] >= dangerFar - 1) continue;
            for(int dx = -1; dx <= 1; dx++){
                for(int dy = -1; dy <= 1; dy++){
                    int x = cx + dx, y = cy + dy;
                    if(x < 0 || y < 0 || x >= dangerWidth || y >= height || danger[x + y * dangerWidth] <= danger[cell] + 1) continue;
                    danger[x + y * dangerWidth] = (short)(danger[cell] + 1);
                    queue.addLast(x + y * dangerWidth);
                }
            }
        }
    }

    /** How far a tile is from the nearest ground the player's turrets can fire on, in tiles, give or take a square; less than nothing on that ground. */
    private float playerAway(float x, float y){
        int cx = (int)(x / dangerCell), cy = (int)(y / dangerCell);
        if(danger.length == 0 || cx < 0 || cy < 0 || cx >= dangerWidth || cx + cy * dangerWidth >= danger.length) return dangerFar * dangerCell;
        return (danger[cx + cy * dangerWidth] - 1) * dangerCell;
    }

    /** Whether a tile is outside the range of the player's turrets, from what is known of where those stand: cheap enough to ask of every tile a line might cross. */
    private boolean workable(int x, int y){
        if(danger.length != 0 && playerAway(x, y) < 0f) return false;
        //a few maps have a rule of their own that keeps both sides from building within some tiles of each other's
        //buildings. where it is in force the enemy keeps to it like the player, and that ground is not to be had
        return !state.rules.placeRangeCheck || Build.getEnemyOverlap(Blocks.duct, team(), x, y) == null;
    }

    /**
     * Takes down what of its own stands on the ways its ground units take out of its base, and they can't walk over: walls,
     * drills and the like put there before the way ran there, or when it ran otherwise. Not turrets, nor cores.
     * @return how many are to come down
     */
    private int clearLanes(TeamData data){
        int count = 0, width = world.width();
        IntSet done = new IntSet();
        for(CoreBuild from : data.cores){
            if(!world.isInMapArea(from.tileX(), from.tileY())) continue;
            IntSeq lane = territory.lane(from);
            for(int i = 0; i < lane.size; i++){
                int x = lane.get(i) % width, y = lane.get(i) / width;
                for(int dx = -1; dx <= 1; dx++){
                    for(int dy = -1; dy <= 1; dy++){
                        Building build = world.build(x + dx, y + dy);
                        if(build == null || build.team != team() || !build.block.solid || build.block instanceof CoreBlock || build instanceof Turret.TurretBuild || !done.add(build.pos())) continue;
                        removals.add(build.pos());
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /** The ways the enemy's ground units take stay open: nothing they can't walk over goes on them. */
    private void markCorridor(TeamData data){
        corridor.clear();
        int width = world.width();
        for(CoreBuild from : data.cores){
            if(!world.isInMapArea(from.tileX(), from.tileY()) || !territory.walkable(from)) continue;
            IntSeq lane = territory.lane(from);
            for(int i = 0; i < lane.size; i++){
                int x = lane.get(i) % width, y = lane.get(i) / width;
                for(int dx = -1; dx <= 1; dx++){
                    for(int dy = -1; dy <= 1; dy++) corridor.add(Point2.pack(x + dx, y + dy));
                }
            }
        }
    }

    private String coreWhy = "";

    /** A free core zone, the closest one to a core the enemy already has. */
    private @Nullable Point2 findCoreSite(Block core, TeamData data){
        Point2 best = null;
        float bestDistance = Float.MAX_VALUE;
        int zones = 0;
        coreWhy = "";

        for(Tile tile : world.tiles){
            if(!tile.floor().allowCorePlacement || tile.build != null) continue;
            zones++;
            if(!coreFits(core, tile.x, tile.y)){
                //why the first of them will not do, for looking into it
                if(coreWhy.isEmpty() && world.isInMapArea(tile.x, tile.y)){
                    coreWhy = " [zone tile " + tile.x + "," + tile.y + ": " + (!ground.open(tile.x, tile.y) ? (!ground.rule.allows(tile.x, tile.y) ? "not ground it may build on"
                        + (workable(tile.x, tile.y) ? "" : ", in reach of a turret") : "not open") : !Build.validPlace(core, team(), tile.x, tile.y, 0) ? "the game does not allow a core there" : "the zone is too small or partly taken") + "]";
                }
                continue;
            }
            float x = tile.worldx() + core.offset, y = tile.worldy() + core.offset;
            float distance = Geometry.findClosest(x, y, data.cores).dst2(x, y);
            if(distance < bestDistance && reachable(core, tile.x, tile.y)){
                best = new Point2(tile.x, tile.y);
                bestDistance = distance;
            }
        }
        return best;
    }

    private boolean coreFits(Block core, int x, int y){
        int offset = -(core.size - 1) / 2;
        for(int dx = 0; dx < core.size; dx++){
            for(int dy = 0; dy < core.size; dy++){
                Tile tile = world.tile(x + dx + offset, y + dy + offset);
                if(tile == null || !tile.floor().allowCorePlacement || !ground.open(tile.x, tile.y)) return false;
            }
        }
        return Build.validPlace(core, team(), x, y, 0);
    }

    /**
     * Brings the picture of the ground up to date: everything waiting to be built counts as standing,
     * and the ground between every drill and the ore it is aimed at, and in front of every unit yard, stays clear.
     */
    public void syncGround(){
        long prof = Prof.start();
        ground.clear();
        TeamData data = team().data();
        for(BlockPlan plan : data.plans){
            Tile tile = world.tile(plan.x, plan.y);
            if(tile == null || standing(tile, plan)) continue;
            ground.plan(new Part(plan.block, plan.x, plan.y, plan.rotation, plan.config));
            keepFront(plan.block, plan.x, plan.y, plan.rotation);
        }
        for(Building build : data.buildings) keepFront(build.block, build.tileX(), build.tileY(), build.rotation);
        Prof.end("sync", prof);
    }

    private void keepFront(Block block, int x, int y, int rotation){
        if(block instanceof BeamDrill drill){
            keepBeams(drill, x, y, rotation);
        }else{
            fronts.clear();
            frontOf(block, x, y, rotation, fronts);
            for(int i = 0; i < fronts.size; i++) ground.kept.add(fronts.get(i));
        }
    }

    private final IntSeq fronts = new IntSeq();

    /**
     * The ground a block that turns out units keeps clear in front of itself: three rows across its
     * width for its units to leave by. One that may later be given a block in front of it that takes
     * its units keeps as much again, for that block to stand on and let them out in its turn: what is
     * built on that ground in the meantime could never be made better.
     */
    public void frontOf(Block block, int x, int y, int rotation, IntSeq out){
        if(!(block instanceof UnitFactory || block instanceof Reconstructor)) return;
        int rows = Options.refit.on() && blueprints.contains(blueprint -> blueprint.addon() && blueprint.main() == block && blueprint.allowed(this::may)) ? 6 : 3;
        int dx = Geometry.d4x(rotation), dy = Geometry.d4y(rotation);
        for(int lane = 0; lane < block.size; lane++){
            block.nearbySide(x, y, rotation, lane, Tmp.p1);
            int startX = Tmp.p1.x, startY = Tmp.p1.y;
            for(int i = 0; i < rows; i++) out.add(Point2.pack(startX + dx * i, startY + dy * i));
        }
    }

    /** Yards being built in the place of old ones: where the new one's main block goes, and the old one's. The old comes down when the new stands. */
    private final IntIntMap retiring = new IntIntMap();

    /** The unit that leaves a yard in the end: what its factory makes, as the blocks in front of it that take units leave it. */
    private @Nullable UnitType turnsOut(UnitFactory.UnitFactoryBuild yard){
        UnitFactory type = (UnitFactory)yard.block;
        if(type.plans.isEmpty()) return null;
        UnitType unit = yard.currentPlan >= 0 && yard.currentPlan < type.plans.size ? type.plans.get(yard.currentPlan).unit : type.plans.first().unit;
        Building next = yard.front();
        for(int step = 0; step < 4 && next != null && next.team == team() && next.block instanceof Reconstructor rebuilder; step++){
            UnitType before = unit;
            for(UnitType[] upgrade : rebuilder.upgrades){
                if(upgrade[0] == unit){
                    unit = upgrade[1];
                    break;
                }
            }
            if(unit == before) break;
            next = next.front();
        }
        return unit;
    }

    /**
     * Once a yard of its own stands whose units can get to the other side's cores, a yard whose units
     * can't (tanks, where there is water between) does nothing but eat the power and the stock the other
     * one needs: it is taken down. What it turned out stays and guards home.
     */
    private void retireStranded(){
        boolean reaching = false;
        for(Net.Thing thing : net.things){
            if(thing.kind == Net.Kind.yard && world.build(thing.x, thing.y) instanceof UnitFactory.UnitFactoryBuild yard && yard.team == team() && turnsOut(yard) != null && reaches(turnsOut(yard))) reaching = true;
        }
        if(!reaching) return;
        for(Net.Thing thing : net.things){
            if(thing.kind != Net.Kind.yard || !(world.build(thing.x, thing.y) instanceof UnitFactory.UnitFactoryBuild yard) || yard.team != team()) continue;
            UnitType unit = turnsOut(yard);
            if(unit == null || reaches(unit) || removals.contains(yard.pos())) continue;
            removals.add(yard.pos());
            Log.info("[EnemyExpansion] Taking down the @ at @,@: its @s can't get to the other side, and a yard whose units can stands.", yard.block.name, yard.tileX(), yard.tileY(), unit.name);
        }
    }

    /** How many blocks a unit goes through before it leaves a yard: one for a fabricator alone, more for each block in front of it that takes its units. */
    private int grade(Building yard){
        int grade = 1;
        Building next = yard.front();
        for(int step = 0; step < 4 && next != null && next.team == team() && next.block instanceof Reconstructor; step++){
            grade++;
            next = next.front();
        }
        return grade;
    }

    /** A yard of its own that turns out the least of units and has been found to have no room for more, and is not being replaced already. */
    private @Nullable Building outdatedYard(TeamData data){
        Building worst = null;
        for(Net.Thing thing : net.things){
            if(thing.kind != Net.Kind.yard || !(world.build(thing.x, thing.y) instanceof UnitFactory.UnitFactoryBuild yard) || yard.team != team()) continue;
            if(rested.get(yard.pos(), 0f) <= clock || retiring.containsValue(yard.pos())) continue;
            if(worst == null || grade(yard) < grade(worst)) worst = yard;
        }
        return worst;
    }

    /**
     * Takes down what has been replaced: an old yard whose better successor stands. Its builders do
     * it, as they put it up, and half of what it was built of comes back to the core.
     */
    private void retire(){
        if(retiring.isEmpty()) return;
        IntSeq done = new IntSeq();
        for(var entry : retiring){
            Building fresh = world.build(entry.key), old = world.build(entry.value);
            boolean gone = old == null || old.team != team() || old.pos() != entry.value;
            if(gone || !ground.look(Point2.x(entry.key), Point2.y(entry.key))){
                //the old one is down, or the new one was given up
                done.add(entry.key);
            }else if(fresh != null && fresh.team == team() && fresh.pos() == entry.key && !(fresh instanceof ConstructBuild)){
                if(!removals.contains(entry.value)){
                    removals.add(entry.value);
                    Log.info("[EnemyExpansion] Taking down the @ at @,@: a better one stands in its place.", old.block.name, old.tileX(), old.tileY());
                }
            }
        }
        for(int i = 0; i < done.size; i++) retiring.remove(done.get(i), 0);
    }

    /** One of its buildings was destroyed. */
    /** The kind of task each goal of the old ordering stands for. */
    static Task.Kind kindOf(Director.Goal goal){
        return switch(goal){
            case income, ore, staples, core -> Task.Kind.mine;
            case vents, power -> Task.Kind.power;
            case speed, refit -> Task.Kind.upgrade;
            case tidy, mend -> Task.Kind.rewire;
            case owed -> Task.Kind.feed;
            case cover, defence, firstPost, arm, cool -> Task.Kind.turret;
            case firstYard, army -> Task.Kind.yard;
            case reveal -> Task.Kind.radar;
            case activate -> Task.Kind.feed;
            case task -> Task.Kind.objective;
        };
    }

    /** Kinds of task kept out of zones for a while, by task key, and until when. */
    private final LongMap<Float> keptOut = new LongMap<>();
    /** Zones it has been losing what it built in over and over, where a turret is to go first. */
    private final IntSet coverFirst = new IntSet();

    /** Whether a kind of task is being kept out of a zone just now. */
    boolean avoided(Task.Kind kind, int zone){
        if(zone < 0) return false;
        Float until = keptOut.get(Task.key(kind, zone, null));
        return until != null && until > clock;
    }

    /**
     * Looks for loops: what it builds somewhere being lost and built again, over and over. The way it goes about
     * that there has to change: something other than a turret is kept out of that zone for a while, and a turret is
     * put up over it first; a turret that keeps being lost there is put somewhere else for a while.
     */
    private void loops(){
        coverFirst.clear();
        defenceNeeds();
        LongSeq loops = ledger.loops(clock);
        for(int i = 0; i < loops.size; i++){
            long key = loops.get(i);
            Task.Kind kind = Task.Kind.values()[(int)(key >>> 48)];
            int zone = (int)((key >>> 16) & 0xffffffffL) - 1;
            if(zone < 0 || kind.army()) continue;
            Float until = keptOut.get(key);
            if(until == null || until <= clock){
                keptOut.put(key, clock + 60f * 60f * (kind == Task.Kind.turret ? 3f : 5f));
                Atlas.Zone at = atlas.zones.get(zone);
                Log.info("[EnemyExpansion] Going round in a loop: @ in zone @ at @,@ (@). @", kind, zone, at.x(), at.y(), ledger.describe(key, clock),
                    kind == Task.Kind.turret ? "Turrets go somewhere else for a while." : "No more of that there for a while; a turret over it first.");
            }
            if(kind != Task.Kind.turret && !avoided(Task.Kind.turret, zone)){
                coverFirst.add(zone);
                Atlas.Zone at = atlas.zones.get(zone);
                tasks.post(Task.Kind.turret, zone, null, 2f, 400f, "what it builds there keeps being lost", clock);
            }
        }
    }

    /**
     * The defence of each zone, by the square law of a fight in which both sides fire at once: the most of the other
     * side's health H and damage a second D seen in a zone at one time, against turrets each dealing f a second and
     * lasting h. n of them kill what comes before it kills them when n squared f h is at least 2 H D. A zone where
     * something of its own stands and that has fewer turrets than that over it gets a turret task, and is where the next
     * turret goes, before anything else is built.
     */
    private void defenceNeeds(){
        if(!atlas.ready()) return;
        Blueprint post = null;
        float f = 0f;
        for(Blueprint blueprint : blueprints){
            if(blueprint.kind() != Kind.post || !usable(blueprint) || !(blueprint.main() instanceof ItemTurret turret)) continue;
            float best = 0f;
            for(var entry : turret.ammoTypes){
                if(available(entry.key)) best = Math.max(best, (entry.value.damage + entry.value.splashDamage) * turret.shoot.shots * 60f / Math.max(turret.reload, 1f));
            }
            if(best > f){
                f = best;
                post = blueprint;
            }
        }
        if(post == null) return;
        float h = post.main().health, cost = total(post.needs());
        //the whole of the other side's army that its side can see, against what holds its home: the turrets near its cores
        //that have enough to fire and the units at home. by the square law, home holds while what it deals times what it can
        //take is well above what the other side's army deals times what it can take. where it does not, each core's zone is
        //where turrets go first, before the other side's army comes rather than after
        float theirDps = 0f, theirHealth = 0f;
        for(Unit unit : Groups.unit){
            if(unit.team == team() || unit.team == Team.derelict || !fights(unit) || unit.spawnedByCore || unit.inFogTo(team()) || !world.isInMapArea(unit.tileX(), unit.tileY())) continue;
            theirDps += dps(unit.type, 0f);
            theirHealth += unit.health + unit.shield;
        }
        //on a map of waves, the next wave is known before it comes: what the game will send, by its own list of them, counts
        //as an army that is already there, from a few minutes before it is due
        if(state.rules.waves && state.rules.waveTimer && state.rules.waveTeam != team()){
            float due = state.wavetime / 60f;
            if(due < 60f * 4f){
                float waveDps = 0f, waveHealth = 0f;
                for(mindustry.game.SpawnGroup group : state.rules.spawns){
                    if(group.type == null) continue;
                    int count = group.getSpawned(state.wave - 1);
                    if(count <= 0) continue;
                    waveDps += count * dps(group.type, 0f);
                    waveHealth += count * (group.type.health * state.rules.unitHealth(state.rules.waveTeam) + group.getShield(state.wave - 1));
                }
                theirDps = Math.max(theirDps, waveDps);
                theirHealth = Math.max(theirHealth, waveHealth);
            }
        }
        armyPeakDps = Math.max(armyPeakDps * 0.995f, theirDps);
        armyPeakHealth = Math.max(armyPeakHealth * 0.995f, theirHealth);
        float ownDps = 0f, ownHealth = 0f;
        for(CoreBuild core : team().cores()){
            float[] fire = {0f, 0f};
            indexer.eachBlock(team(), core.x, core.y, tilesize * 30f, build -> build instanceof Turret.TurretBuild turret && holds(turret), build -> {
                fire[0] += turretDps((Turret.TurretBuild)build);
                fire[1] += build.health;
            });
            ownDps += fire[0];
            ownHealth += fire[1];
        }
        //whether the turrets hold home by themselves, without the units that stand there: only then may those go out early
        turretsHold = armyPeakDps <= 1f || ownDps * ownHealth >= 2f * armyPeakDps * armyPeakHealth;
        for(Unit unit : team().data().units){
            if(!fights(unit) || field.contains(unit.id)) continue;
            ownDps += dps(unit.type, 0f);
            ownHealth += unit.health;
        }
        homeShort = armyPeakDps > 1f && ownDps * ownHealth < 2f * armyPeakDps * armyPeakHealth;
        if(homeShort){
            for(CoreBuild core : team().cores()){
                int zone = atlas.zoneAt(core.tileX(), core.tileY());
                if(zone < 0 || avoided(Task.Kind.turret, zone)) continue;
                coverFirst.add(zone);
                tasks.post(Task.Kind.turret, zone, null, 3f, cost, "home holds " + (int)ownDps + "/s with " + (int)ownHealth + " against an army of " + (int)armyPeakDps + "/s with " + (int)armyPeakHealth, clock);
            }
        }
        for(Atlas.Zone zone : atlas.zones){
            if(zone.ownWorth < 50f || zone.peakDps < 1f || avoided(Task.Kind.turret, zone.id)) continue;
            int needed = (int)Math.ceil(Math.sqrt(2f * zone.peakHealth * zone.peakDps / Math.max(f * h, 1f)));
            float wx = zone.x() * tilesize, wy = zone.y() * tilesize;
            int[] have = {0};
            indexer.eachBlock(team(), wx, wy, tilesize * 40f, build -> build instanceof Turret.TurretBuild turret && holds(turret), build -> {
                if(build.within(wx, wy, ((Turret)build.block).range)) have[0]++;
            });
            if(have[0] >= needed) continue;
            coverFirst.add(zone.id);
            tasks.post(Task.Kind.turret, zone.id, null, zone.ownWorth / 500f + zone.lost / 100f, cost, "needs " + needed + " turrets against " + (int)zone.peakHealth + " health dealing " + (int)zone.peakDps + "/s, has " + have[0], clock);
        }
    }

    /** The most of the other side's army seen at once lately, fading; and whether what holds its home falls short of it. */
    private float armyPeakDps, armyPeakHealth;
    public boolean homeShort, turretsHold = true;

    /** For each tile of what it laid out, the task that was for, by packed position. */
    private final IntMap<Long> tileTask = new IntMap<>();

    public void lost(Tile tile){
        if(!awake || tile == null || tile.team() != team()) return;
        director.losses += 1f;
        fallen.put(tile.pos(), clock);
        lastLossAt = clock;
        lostWorth += total(tile.block().requirements);
        int cell = Point2.pack(tile.x / 8, tile.y / 8);
        cellLost.put(cell, lostIn(cell) + total(tile.block().requirements));
        cellLostAt.put(cell, clock);
        //what stood and was destroyed counts against the task it was built for; a site still going up does not
        Long key = tileTask.get(tile.pos());
        if(key != null && !(tile.build instanceof ConstructBuild)){
            ledger.lost(key, clock);
            tileTask.remove(tile.pos());
        }
        atlas.lost(tile.x, tile.y, total(tile.block().requirements));
    }

    /** When it last lost a building. */
    private float lastLossAt = -1e9f;

    /** What it has lost lately on each stretch of ground eight tiles across, by the cost of what stood there, halving every minute and a half. */
    private final IntFloatMap cellLost = new IntFloatMap(), cellLostAt = new IntFloatMap();

    private float lostIn(int cell){
        float worth = cellLost.get(cell, 0f);
        return worth <= 0f ? 0f : worth * (float)Math.pow(0.5, (clock - cellLostAt.get(cell, clock)) / (60f * 90f));
    }

    /** What it has lost lately within so many stretches of one. */
    private float lostNear(int cx, int cy, int around){
        float sum = 0f;
        for(int dx = -around; dx <= around; dx++){
            for(int dy = -around; dy <= around; dy++) sum += lostIn(Point2.pack(cx + dx, cy + dy));
        }
        return sum;
    }

    /**
     * Whether the other side's army is at its base: something of its own was destroyed a moment ago
     * (one building lost counts for half a minute, more for longer), or a unit of theirs that fights
     * has been seen near one of its cores. Seeing it come is worth a turret's building time.
     */
    private boolean attacked(){
        return director.losses >= 1f || clock < sightedUntil;
    }

    /** How near one of its cores a fighting unit of the other side has to be seen for it to make ready, and for how long after it does. */
    static final float sightRange = tilesize * 50f, sightTime = 60f * 30f;
    private float sightedUntil;
    /** Whether anything of the other side's army has been seen near its cores since the map began. */
    private boolean everSighted;
    /** How long the price of a turret is kept back at the start, if nothing comes. */
    static final float openingTime = 60f * 60f * 6f;
    /** Where such units were last seen: what of its own stands nearest each, as packed positions. */
    private final IntSeq sightedAt = new IntSeq();

    /** How many turrets of its own, for each core, cover what it has before the fighting starts in earnest. */
    static final int coverPerCore = 3;

    /** Where its army is in a fight with the other side's, as a packed tile, or -1; and for how long it has been at it of late. */
    private int frontAt = -1;
    private float contactTime, lastContact = -1e9f;

    /** Whether its army has been in a fight for half a minute out of the last few, and still is. */
    private boolean engaged(){
        return contactTime >= 60f * 30f && clock - lastContact < 60f * 30f;
    }

    /** Looks at where its fighting units are up against the other side's, once a second. */
    private void contact(TeamData data){
        int count = 0;
        float sx = 0f, sy = 0f;
        for(Unit unit : data.units){
            if(!fights(unit) || !world.isInMapArea(unit.tileX(), unit.tileY())) continue;
            if(Units.closestTarget(team(), unit.x, unit.y, unit.range() + tilesize * 6f, Brain::fights, build -> build.block instanceof BaseTurret) == null) continue;
            count++;
            sx += unit.x;
            sy += unit.y;
        }
        if(count >= 3){
            contactTime += 60f;
            lastContact = clock;
            frontAt = Point2.pack((int)(sx / count / tilesize), (int)(sy / count / tilesize));
        }else if(clock - lastContact > 60f * 120f){
            contactTime = 0f;
            frontAt = -1;
        }
    }

    /** How much the hardest-hitting unit of the other side's seen at its base lately deals with a shot, and how far the furthest-reaching fires. */
    private float raiderHit, raiderRange;

    /** When each of its turrets last had something to fire at, by packed position. */
    private final IntFloatMap lastFired = new IntFloatMap();

    /**
     * What is measured once a second for looking ahead: the other side's health arriving at its base (each
     * unit counted once, when it is first seen there), the damage that comes with it, and what its own
     * turrets that have enough to fire, and its units at home, kill a second. Averaged over a couple of minutes.
     */
    public float arriving, hitPerHealth = 0.05f, killing;
    /**
     * And what is measured to say how hard it is hit: the damage a second the other side's units at its base
     * could be dealing (what is firing on its things now), and how much of its own turrets' fire with enough to
     * shoot reaches the places those units are at, each unit counted by its health.
     */
    public float exposure, coverage;
    /** And what of its own was destroyed, in what it had cost, a second: measured, not worked out. */
    public float lossRate;
    private float lostWorth;
    private final IntSet raidersSeen = new IntSet();

    private void measure(TeamData data){
        float arrived = 0f, hit = 0f, health = 0f, covered = 0f;
        for(CoreBuild core : data.cores){
            if(!world.isInMapArea(core.tileX(), core.tileY())) continue;
            for(Unit unit : Groups.unit){
                if(unit.team == team() || unit.team == Team.derelict || !fights(unit) || unit.spawnedByCore || unit.inFogTo(team()) || !unit.within(core, sightRange)) continue;
                if(raidersSeen.add(unit.id)) arrived += unit.health + unit.shield;
                hit += dps(unit.type, 0f);
                health += unit.health;
                float[] on = {0f};
                indexer.eachBlock(team(), unit.x, unit.y, tilesize * 40f, build -> build instanceof Turret.TurretBuild turret && holds(turret), build -> {
                    if(build.within(unit, ((Turret)build.block).range)) on[0] += turretDps((Turret.TurretBuild)build);
                });
                covered += on[0] * unit.health;
            }
        }
        //every unit of the other side's that fights and that its side can see, wherever it is, goes into the world model
        for(Unit unit : Groups.unit){
            if(unit.team == team() || unit.team == Team.derelict || !fights(unit) || unit.spawnedByCore || unit.inFogTo(team())) continue;
            atlas.sighted(unit.x, unit.y, dps(unit.type, 0f), unit.health);
        }
        arriving = Mathf.lerp(arriving, arrived, 1f / 120f);
        lossRate = Mathf.lerp(lossRate, lostWorth, 1f / 60f);
        lostWorth = 0f;
        exposure = Mathf.lerp(exposure, hit, 1f / 60f);
        if(health > 0f){
            hitPerHealth = Mathf.lerp(hitPerHealth, hit / health, 0.05f);
            coverage = Mathf.lerp(coverage, covered / health, 0.05f);
        }
        float kill = 0f;
        for(Building build : data.buildings){
            if(build instanceof Turret.TurretBuild turret && holds(turret) && data.cores.contains(core -> core.within(build, sightRange))) kill += turretDps(turret);
        }
        for(Unit unit : data.units){
            if(fights(unit) && !field.contains(unit.id)) kill += dps(unit.type, 0f);
        }
        killing = Mathf.lerp(killing, kill, 1f / 30f);
        atlas.update(team(), clock);
    }

    /** Looks for the other side's army near its cores. Only what its own side can see counts. */
    private void lookOut(TeamData data){
        contact(data);
        measure(data);
        for(Building build : data.buildings){
            if(build instanceof Turret.TurretBuild turret && turret.isShooting()) lastFired.put(build.pos(), clock);
        }
        sightedAt.clear();
        for(CoreBuild core : data.cores){
            if(!world.isInMapArea(core.tileX(), core.tileY())) continue;
            Units.nearbyEnemies(team(), core.x - sightRange, core.y - sightRange, sightRange * 2f, sightRange * 2f, unit -> {
                if(!fights(unit) || unit.inFogTo(team()) || !unit.within(core, sightRange)) return;
                //one that is under the guns of a turret of its own with enough to fire, at a base that has lost nothing for a
                //couple of minutes, is being dealt with: that is no reason to drop everything else. (where the other side sends
                //its units over one at a time, a base that took every one of them for an attack did nothing but build turrets:
                //in an hour of the third sector it widened its mining once. but while it still loses things now and then, as
                //in the first minutes there, getting ready at the sight of them is what holds: without it 18 of 48 openings
                //were lost against 15 of 96)
                if(clock - lastLossAt > 60f * 120f && guarded(unit.x, unit.y)) return;
                //how hard the hardest-hitting of them hits with a shot
                if(clock >= sightedUntil) raiderHit = 0f;
                for(var mount : unit.type.weapons) raiderHit = Math.max(raiderHit, mount.bullet.damage + mount.bullet.splashDamage);
                raiderRange = Math.max(clock >= sightedUntil ? 0f : raiderRange, unit.range());
                if(sightedAt.size >= 3){
                    sightedUntil = clock + sightTime;
                    return;
                }
                Building near = indexer.findTile(team(), unit.x, unit.y, sightRange, build -> !(build.block instanceof CoreBlock));
                if(near != null && !sightedAt.contains(near.pos())) sightedAt.add(near.pos());
                sightedUntil = clock + sightTime;
                everSighted = true;
            });
        }
    }

    /** Whether a place is under the guns of a turret of its own that has something to fire. */
    private boolean guarded(float x, float y){
        return indexer.findTile(team(), x, y, tilesize * 45f, build -> build instanceof Turret.TurretBuild turret && holds(turret) && build.within(x, y, ((Turret)build.block).range * 0.85f)) != null;
    }

    /**
     * Whether a turret has enough to fire to hold a place: one down to its last few rounds, its line
     * bringing it less than it fires, is losing the fight however long it takes.
     */
    private static boolean holds(Turret.TurretBuild turret){
        if(!turret.hasAmmo()) return false;
        return !(turret instanceof ItemTurret.ItemTurretBuild item) || item.totalAmmo >= ((ItemTurret)turret.block).maxAmmo / 4;
    }

    /** Whether something it laid out to hold an attack off with is still to be built. What is up, or was given up, is forgotten. */
    private boolean defending(TeamData data){
        if(urgent.isEmpty()) return false;
        IntSeq left = new IntSeq();
        boolean workable = false;
        for(BlockPlan plan : data.plans){
            Tile tile = world.tile(plan.x, plan.y);
            int pos = Point2.pack(plan.x, plan.y);
            if(tile == null || !urgent.contains(pos) || standing(tile, plan)) continue;
            left.add(pos);
            //only what a builder can get on with holds up deciding anything else: a site in the middle of the fighting does
            //not go up, and waiting on it left a base doing nothing while it was taken apart
            float x = plan.x * tilesize + plan.block.offset, y = plan.y * tilesize + plan.block.offset;
            if(calm(x, y, quiet)) workable = true;
        }
        urgent.clear();
        for(int i = 0; i < left.size; i++) urgent.add(left.get(i));
        return workable;
    }

    /** How much of an item is not to be spent on anything else while it is attacked: what the turret it waits for costs, and what the one being built still needs. */
    private int guardNeed(Item item){
        if(needClock != clock){
            needClock = clock;
            if(guardNeed.length != content.items().size) guardNeed = new int[content.items().size];
            java.util.Arrays.fill(guardNeed, 0);
            boolean fighting = attacked();
            if(fighting && clock < guardUntil){
                for(ItemStack stack : guardCost) guardNeed[stack.item.id] += stack.amount;
            }

            //under attack, what holds it off; otherwise the first sources of what it can't do without
            IntSet kept = fighting ? urgent : firsts;
            if(!kept.isEmpty()){
                IntSeq left = new IntSeq();
                for(BlockPlan plan : team().data().plans){
                    Tile tile = world.tile(plan.x, plan.y);
                    int pos = Point2.pack(plan.x, plan.y);
                    if(tile == null || !kept.contains(pos) || standing(tile, plan)) continue;
                    left.add(pos);
                    for(ItemStack stack : cost(plan.block)) guardNeed[stack.item.id] += stack.amount;
                }
                if(!fighting){
                    firsts.clear();
                    for(int i = 0; i < left.size; i++) firsts.add(left.get(i));
                }
            }
        }
        return guardNeed[item.id];
    }

    void keepBeams(BeamDrill drill, int x, int y, int rotation){
        int dx = Geometry.d4x(rotation), dy = Geometry.d4y(rotation);
        for(int beam = 0; beam < drill.size; beam++){
            drill.nearbySide(x, y, rotation, beam, Tmp.p1);
            int startX = Tmp.p1.x, startY = Tmp.p1.y;
            for(int i = 0; i < drill.range; i++){
                Tile other = world.tile(startX + dx * i, startY + dy * i);
                if(other == null || other.solid()) break;
                ground.kept.add(other.pos());
            }
        }
    }

    private static ItemStack[] cost(Block block){
        return ItemStack.mult(block.requirements, state.rules.buildCostMultiplier);
    }

    /** Whether something could be put up on this spot: out of range of the player's turrets, and, where builders do the work, with a place for one to stand that the player's army is not at. */
    boolean reachable(Block block, int x, int y){
        if(!workable(x, y)) return false;
        if(Options.hands.on()) return true;
        float wx = x * tilesize + block.offset, wy = y * tilesize + block.offset;
        Vec2 stand = stand(wx, wy, team().data(), reach, Tmp.v2);
        return calm(stand.x, stand.y, quiet);
    }

    /**
     * Builders work from a distance. They stop on the side of their own base, which keeps them as far
     * from the player as they can be while still reaching.
     */
    private static Vec2 stand(float x, float y, TeamData data, float reach, Vec2 out){
        out.set(x, y);
        CoreBuild home = Geometry.findClosest(x, y, data.cores);
        if(home != null && !home.within(x, y, reach)) out.add(Tmp.v3.set(home.x - x, home.y - y).setLength(reach));
        return out;
    }

    /** Whether nothing the player fights with is within range of a spot. */
    private static boolean calm(float x, float y, float range){
        return range <= 0f || threat(x, y, range) == null;
    }

    /** The nearest thing within range that the player fights with: a turret, or a unit. What the player has merely built is not one. */
    static @Nullable Teamc threat(float x, float y, float range){
        return Units.closestTarget(team(), x, y, range, Brain::fights, build -> build.block instanceof BaseTurret);
    }

    /** Whether a unit is one the player fights with. The unit a core gives its player to build with is not: nothing need stop where that flies. */
    static boolean fights(Unit unit){
        return unit.type.canAttack && !unit.spawnedByCore;
    }

    private static boolean isTurret(Building build){
        return build.block instanceof Turret || (build instanceof ConstructBuild site && site.current instanceof Turret);
    }

    /** Whether a turret, standing or laid out, already watches over a spot. */
    private boolean covered(float x, float y, float range){
        if(indexer.findTile(team(), x, y, range, Brain::isTurret) != null) return true;
        for(Net.Thing thing : net.things){
            if(thing.kind == Net.Kind.post && Mathf.within(thing.x * tilesize, thing.y * tilesize, x, y, range)) return true;
        }
        return false;
    }

    //endregion
}
