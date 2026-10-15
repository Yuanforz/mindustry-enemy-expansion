package expansion;

import arc.math.geom.*;
import arc.util.*;
import mindustry.ai.types.*;
import mindustry.entities.units.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.ConstructBlock.*;

import static mindustry.Vars.*;

/**
 * The game's own builder AI, taking its work from the {@link Brain} instead of rebuilding for free.
 *
 * It keeps the game's rule that an enemy builder does not work under the player's guns: with a turret
 * or a fighting unit of the player's in range it drops what it is doing and goes home. What the player
 * has merely built is nothing to run from. Only on the way to a job does it not turn back: the places
 * builders are sent to were checked to be clear, and one that turned back whenever its way led past
 * the player would never get anywhere.
 */
public class ExpansionAI extends BuilderAI{
    /** A site that could not be started in this long is given up on. */
    static final float patience = 60f * 90f;
    static final float haulPatience = 60f * 60f;
    /** How long it stays away after the player has driven it off a job. */
    static final float restTime = 60f * 15f;

    private final Brain brain;
    private final Vec2 stand = new Vec2();
    private boolean travelling;
    private float rest;

    public @Nullable Order order;
    /**
     * What it goes on to when the order in hand is done. A builder is given a whole stretch of work at
     * a time, the way a player lays down a line of blocks and lets the unit get on with it: one block
     * follows the other with nothing in between but the building itself.
     */
    public final arc.struct.Queue<Order> more = new arc.struct.Queue<>();
    public @Nullable Haul haul;
    /**
     * A block to pick up and set down into something that takes blocks, such as a container into a loader that the map's
     * mass drivers carry across water: the builder can lift a block of its size, and nothing else would put it there.
     */
    public @Nullable Building carry, carryTo;
    private float carryAge;

    /**
     * Spare output being carried to a core. That is all the enemy carries by hand: on this planet nothing
     * can be put into a turret or a factory by hand, by the player either, so those are supplied by duct.
     */
    public static class Haul{
        /** Where to fetch it, or null if the builder has it already. */
        public final @Nullable Building from;
        public final Building to;
        public final Item item;
        public boolean loaded;
        public float age;

        public Haul(@Nullable Building from, Building to, Item item){
            this.from = from;
            this.to = to;
            this.item = item;
            this.loaded = from == null;
        }
    }

    /**
     * Ore being mined out of a wall by hand, the way the player's own core unit does it. Something for a
     * builder to do when there is nothing to build, and what keeps the enemy from ever being left with
     * nothing coming in at all.
     */
    public static class Dig{
        public final Tile wall;
        public final Item item;

        public Dig(Tile wall, Item item){
            this.wall = wall;
            this.item = item;
        }
    }

    public @Nullable Dig dig;
    /**
     * Ground to go and look at. On a map with fog nothing can be built where the side has not seen,
     * so a place that is wanted and unseen has to be flown to first.
     */
    public @Nullable Vec2 scout;
    private float scoutAge;

    /**
     * A building of its own that is hurt, to mend with the beam a core's unit has. Only one that has not
     * been hit for a while and has none of the other side's units near: the beam mends what was hit a moment
     * ago much more slowly, and mending under fire is undone as fast as it is done.
     */
    public @Nullable Building repair;

    public ExpansionAI(Brain brain, BuilderAI previous){
        super(previous.alwaysFlee, previous.fleeRange);
        this.brain = brain;
        rebuildPeriod = Float.POSITIVE_INFINITY;
    }

    /** Which destroyed buildings get rebuilt is the brain's decision, since rebuilding costs resources now. */
    @Override
    public void init(){
        rebuildPeriod = Float.POSITIVE_INFINITY;
    }

    /** Whether it has nothing it was told to do. Mining by hand does not count: that is dropped for any real work. */
    public boolean idle(){
        return order == null && haul == null && scout == null && carry == null && unit.buildPlan() == null;
    }

    /** Hands back whatever this builder was doing. */
    public void drop(){
        while(order != null) close(false);
        haul = null;
        scout = null;
        repair = null;
        if(carry != null && unit instanceof Payloadc pay && pay.hasPayload()) pay.dropLastPayload();
        carry = carryTo = null;
        stopDigging();
    }

    public void stopDigging(){
        if(dig != null) unit.mineTile = null;
        dig = null;
    }

    /** Whether it has mined as much as it can carry, or holds something else it has to be rid of first. */
    public boolean full(){
        return unit.stack.amount >= unit.type.itemCapacity || (dig != null && unit.hasItem() && unit.item() != dig.item);
    }

    /** The builder AI looks for something to retreat from with this. On the way to a job it finds nothing, and otherwise only what could hurt. */
    @Override
    public Teamc target(float x, float y, float range, boolean air, boolean ground){
        if(range == fleeRange) return travelling ? null : Brain.threat(x, y, range);
        return super.target(x, y, range, air, ground);
    }

    @Override
    public void updateMovement(){
        //how close to the player it will work is the player's to set
        fleeRange = Options.nerve.get() * tilesize;
        //going off to help another builder would abandon the job this one was given
        if(order != null || haul != null) following = null;

        super.updateMovement();

        travelling = false;
        if(rest > 0f){
            rest -= Time.delta;
        }else if(order != null){
            updateOrder();
        }else if(haul != null){
            updateHaul();
        }else if(carry != null){
            updateCarry();
        }else if(scout != null){
            //there when the place is well within what it sees; given up if it can't be got to
            if(unit.within(scout, tilesize * 6f) || (scoutAge += Time.delta) > 60f * 90f){
                scout = null;
                scoutAge = 0f;
            }else{
                travel(scout, tilesize * 3f);
            }
        }else if(repair != null){
            updateRepair();
        }else if(dig != null){
            updateDig();
        }
    }

    private void updateCarry(){
        Payloadc pay = unit instanceof Payloadc p ? p : null;
        boolean holding = pay != null && pay.hasPayload();
        if(pay == null || carryTo == null || !carryTo.isValid() || (carryAge += Time.delta) > haulPatience * 2f || (!holding && (carry == null || !carry.isValid()))){
            if(holding) pay.dropLastPayload();
            carry = carryTo = null;
            carryAge = 0f;
            return;
        }
        if(!holding){
            if(!unit.within(carry, tilesize * 1.5f)){
                travel(carry, tilesize);
                return;
            }
            if(!pay.canPickup(carry)){
                carry = carryTo = null;
                return;
            }
            Call.pickedBuildPayload(unit, carry, true);
            return;
        }
        if(!unit.within(carryTo, tilesize * 0.8f)){
            travel(carryTo, tilesize * 0.5f);
            return;
        }
        pay.dropLastPayload();
        carry = carryTo = null;
        carryAge = 0f;
    }

    private void updateRepair(){
        Building build = repair;
        if(!build.isValid() || build.team != unit.team || !build.damaged() || build.wasRecentlyDamaged() || Brain.threat(build.x, build.y, fleeRange) != null){
            repair = null;
            return;
        }
        float range = unit.type.weapons.isEmpty() ? tilesize * 5f : unit.type.weapons.first().range();
        if(!unit.within(build, range * 0.7f)){
            travel(Tmp.v1.set(build.x, build.y), range * 0.6f);
            return;
        }
        unit.lookAt(build);
        unit.aim(build.x, build.y);
        unit.controlWeapons(true, true);
    }

    private void updateDig(){
        Tile wall = dig.wall;
        if(!unit.canMine() || unit.getMineResult(wall) != dig.item){
            stopDigging();
            return;
        }
        //with a full load it waits to be sent home with it
        if(full()){
            unit.mineTile = null;
            return;
        }
        if(!unit.within(wall.worldx(), wall.worldy(), unit.type.mineRange * 0.8f)){
            unit.mineTile = null;
            travel(wall, unit.type.mineRange * 0.5f);
            return;
        }
        //like any other work, not next to the player
        enemy = Brain.threat(unit.x, unit.y, fleeRange);
        if(enemy != null){
            rest = restTime;
            stopDigging();
            return;
        }
        unit.mineTile = wall;
    }

    private void updateOrder(){
        Order o = order;
        o.age += Time.delta;
        Tile tile = o.tile();
        if(tile == null){
            close(false);
            return;
        }

        if(o.breaking){
            updateBreaking(o, tile);
            return;
        }

        boolean building = tile.build instanceof ConstructBuild site && site.current == o.block && tile.team() == unit.team;
        if(o.begun && !building){
            //either it is finished, or the site was destroyed
            close(tile.block() == o.block && tile.team() == unit.team);
            return;
        }
        if(!o.begun && (o.age > patience || !brain.valid(o))){
            close(false);
            return;
        }
        //the game's builder logic is on it: it moves into range and builds, and backs off if the player comes
        if(unit.buildPlan() != null) return;

        //anything within its reach it builds from where it is, like a player's unit; only for what is further off does it move
        if(!unit.within(o.x * tilesize + o.block.offset, o.y * tilesize + o.block.offset, reach()) && !unit.within(stand.set(o.standX, o.standY), tilesize * 3f)){
            travel(stand, 6f);
            return;
        }

        //arrived. the place was clear when the job was handed out; if the player is here now, come back later
        enemy = Brain.threat(unit.x, unit.y, fleeRange);
        if(enemy != null){
            rest = restTime;
            return;
        }

        if(!o.begun){
            //anything standing on the site will have moved on in a moment
            if(!Build.checkNoUnitOverlap(o.block, o.x, o.y)) return;
            if(!brain.begin(o, unit)){
                close(false);
                return;
            }
        }
        unit.addBuild(new BuildPlan(o.x, o.y, o.rotation, o.block, o.config));
    }

    /** Taking down something of the enemy's own that is in the way. What it was made of goes back into the core. */
    private void updateBreaking(Order o, Tile tile){
        boolean standing = tile.build != null && tile.team() == unit.team && (tile.block() == o.block || tile.build instanceof ConstructBuild);
        if(!standing || o.age > patience){
            close(!standing);
            return;
        }
        if(unit.buildPlan() != null) return;

        if(!unit.within(stand.set(o.standX, o.standY), tilesize * 3f)){
            travel(stand, 6f);
            return;
        }
        enemy = Brain.threat(unit.x, unit.y, fleeRange);
        if(enemy != null){
            rest = restTime;
            return;
        }
        o.begun = true;
        unit.addBuild(new BuildPlan(o.x, o.y));
    }

    private void updateHaul(){
        Haul h = haul;
        h.age += Time.delta;
        if(h.age > haulPatience || !h.to.isValid() || (!h.loaded && !h.from.isValid())){
            haul = null;
            return;
        }

        Building goal = h.loaded ? h.to : h.from;
        if(!unit.within(goal, itemTransferRange * 0.8f)){
            travel(goal, itemTransferRange * 0.5f);
            return;
        }

        if(!h.loaded){
            h.loaded = brain.load(h, unit);
            if(!h.loaded) haul = null;
        }else{
            brain.unload(h, unit);
            haul = null;
        }
    }

    private void travel(Position to, float within){
        travelling = true;
        enemy = null;
        moveTo(to, within, 20f);
    }

    /** How far off it builds from: a little inside what its kind of unit can reach, as the places it is sent to stand at are worked out. */
    public float reach(){
        return (unit.type.buildRange - tilesize * 2f) * 0.8f;
    }

    private void close(boolean built){
        Order o = order;
        //straight on to the next of the stretch it was given
        order = more.isEmpty() ? null : more.removeFirst();
        o.built = built;
        brain.closed(o);
    }
}
