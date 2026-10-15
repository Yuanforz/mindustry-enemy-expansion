package expansion;

import arc.struct.*;
import arc.util.*;
import mindustry.game.*;
import mindustry.type.*;
import mindustry.world.blocks.ConstructBlock.*;
import mindustry.world.blocks.storage.CoreBlock.*;

import static mindustry.Vars.*;

/**
 * What the enemy has to build with: what is in its cores, and nothing else.
 *
 * In the campaign the game keeps the enemy's core topped up with every item and lets it build for free.
 * This turns both off, so the enemy's side works like the player's. Its cores hold what was delivered
 * to them, up to what fits; building takes items out as it goes; and a block it can't pay for stops
 * half built until more comes in.
 */
public class Stock implements Intake.Listener{
    /** What a core that the game had been filling is given to start with, of the two things everything is built from first. */
    static final int grant = 300;
    /** The stretch of time income is averaged over, and how many parts it is counted in. */
    static final float window = 60f * 60f;
    static final int slots = 6;

    private int[] reserved = {}, mirror = {};
    private long[] earned = {};
    private int[][] recent = {}, piped = {};
    private int slot;
    private float slotTime;
    private boolean counting;

    public void reset(){
        int size = content.items().size;
        reserved = new int[size];
        mirror = new int[size];
        earned = new long[size];
        recent = new int[slots][size];
        piped = new int[slots][size];
        slot = 0;
        slotTime = 0f;
        counting = false;
    }

    public static @Nullable CoreBuild core(){
        Team team = Brain.team();
        return team.cores().isEmpty() ? null : team.cores().first();
    }

    /**
     * Puts the enemy on the same footing as the player: no topping up, and nothing built for free.
     * The game sets both again whenever a map is loaded, and may get a round of topping up in before
     * this is called, so what the cores really held is remembered and put back if that happens.
     */
    public void enforce(){
        var rules = Brain.team().rules();
        rules.fillItems = false;
        rules.infiniteResources = false;
        counting = true;
        //the game's own base-building AI, where something has switched it on, tops the core up as well, on every update
        var data = Brain.team().data();
        if(!(data.buildAi instanceof Idle)) data.buildAi = new Idle(data);

        CoreBuild core = core();
        if(core == null) return;
        for(Item item : content.items()){
            int held = core.items.get(item);
            if(held > core.storageCapacity){
                core.items.set(item, Math.min(mirror[item.id], core.storageCapacity));
            }else{
                mirror[item.id] = held;
            }
        }
    }

    /**
     * Stands in for the game's own base-building AI, and does nothing. That AI puts up ready-made
     * pieces of base for free and keeps the core full; with the enemy paying its way it has no place.
     */
    static class Idle extends mindustry.ai.BaseBuilderAI{
        Idle(mindustry.game.Teams.TeamData data){
            super(data);
        }

        @Override
        public void update(){
        }
    }

    /** Hands the enemy back to the game's own rules for it. */
    public void release(){
        if(!counting) return;
        counting = false;
        Brain.team().data().buildAi = null;
        if(state.isCampaign() && state.getPlanet().enemyInfiniteItems){
            var rules = Brain.team().rules();
            rules.fillItems = true;
            rules.infiniteResources = true;
        }
    }

    /**
     * Notes what the cores hold as a map is loaded. A core the game has been topping up holds far more
     * than fits, and that says nothing about what the enemy earned: it starts over with a little of
     * what a base is begun with.
     */
    public void settle(){
        CoreBuild core = core();
        if(core == null) return;
        boolean filled = false;
        for(Item item : content.items()) filled |= core.items.get(item) > core.storageCapacity;
        for(Item item : content.items()){
            boolean basic = item == mindustry.content.Items.beryllium || item == mindustry.content.Items.graphite;
            mirror[item.id] = !filled ? core.items.get(item) : basic ? Math.min(grant, core.storageCapacity) : 0;
        }
    }

    /** Sets what the cores hold, for a save from before they held the enemy's real stock. */
    public void set(Item item, int amount){
        CoreBuild core = core();
        if(core == null) return;
        mirror[item.id] = Math.max(Math.min(amount, core.storageCapacity), 0);
        core.items.set(item, mirror[item.id]);
    }

    public void update(float delta){
        if((slotTime += delta) < window / slots) return;
        slotTime = 0f;
        slot = (slot + 1) % slots;
        java.util.Arrays.fill(recent[slot], 0);
        java.util.Arrays.fill(piped[slot], 0);
    }

    @Override
    public void arrived(Team team, Item item, int amount, boolean byLine){
        if(!counting || team != Brain.team() || item.id >= earned.length) return;
        earned[item.id] += amount;
        recent[slot][item.id] += amount;
        if(byLine) piped[slot][item.id] += amount;
    }

    @Override
    public void taken(Team team, Item item, int amount){
    }

    /** How much every core together can hold of an item. */
    public int cap(){
        CoreBuild core = core();
        return core == null ? 0 : core.storageCapacity;
    }

    public int have(Item item){
        CoreBuild core = core();
        return core == null ? 0 : core.items.get(item);
    }

    /** What is not already set aside for blocks that builders have been sent to put up. */
    public int free(Item item){
        return have(item) - (item.id < reserved.length ? reserved[item.id] : 0);
    }

    public boolean covers(ItemStack[] cost){
        for(ItemStack stack : cost){
            if(free(stack.item) < stack.amount) return false;
        }
        return true;
    }

    /** Sets a cost aside, for work handed out a moment ago that is not among the orders yet. */
    public void hold(ItemStack[] cost){
        for(ItemStack stack : cost) reserved[stack.item.id] += stack.amount;
    }

    /** Works out afresh what is set aside: the cost of every order, less what has already gone into the ones under construction. */
    public void reserve(Seq<Order> orders){
        java.util.Arrays.fill(reserved, 0);
        for(Order order : orders){
            float left = 1f;
            if(order.begun && order.tile() != null && order.tile().build instanceof ConstructBuild site) left = 1f - site.progress;
            for(ItemStack stack : order.cost) reserved[stack.item.id] += Math.round(stack.amount * left);
        }
    }

    /** Everything this item ever brought in. */
    public long earned(Item item){
        return item.id < earned.length ? earned[item.id] : 0;
    }

    /** How much of an item came in over the last minute or so. */
    public int income(Item item){
        if(item.id >= earned.length) return 0;
        int total = 0;
        for(int[] part : recent) total += part[item.id];
        return total;
    }

    /** How much of an item its lines brought in over the last minute or so: what its units carried home is not in this. */
    public int piped(Item item){
        if(item.id >= earned.length) return 0;
        int total = 0;
        for(int[] part : piped) total += part[item.id];
        return total;
    }

    public String write(){
        StringBuilder out = new StringBuilder();
        for(Item item : content.items()){
            if(earned[item.id] == 0) continue;
            if(out.length() > 0) out.append(',');
            out.append(item.name).append(':').append(earned[item.id]);
        }
        return out.toString();
    }

    public void read(String text){
        for(String entry : text.split(",")){
            String[] parts = entry.split(":");
            Item item = parts.length == 2 ? content.item(parts[0]) : null;
            if(item != null) earned[item.id] = Strings.parseLong(parts[1], 0);
        }
    }
}
