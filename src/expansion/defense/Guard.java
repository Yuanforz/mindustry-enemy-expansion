package expansion.defense;

import java.util.*;

/**
 * A turret for a place being raided: where a side keeps losing what it built to a few of the other side's units, and none
 * of its own loaded turrets that can hit those units reaches the place, one module is put down there.
 *
 * The defence planner works out lines across the ways the other side comes by, for waves. Raids are not waves: one to
 * three quick units going for whatever stands out of reach of turrets (generators, crushers and bores out on the edge,
 * the lines between). Against those, one turret that can hit them, standing by what they go for, is what players build
 * ("one requester pad to two disperse turrets is usually enough to shut down any air unit harassment", reference/
 * turrets-drills-human.md); without it, the raided side puts the same blocks back under the raider again and again.
 *
 * What it picks: of the modules whose every block may be built and whose turret hits every kind raiding (in the air or
 * on the ground; hovering units are on the ground for this) with an ammunition the side can feed, the one that costs
 * least; where: the place nearest the raided place, a little towards home, from which the turret reaches it, with every
 * block of it free to go down. Team-free: the side and what it may build come in the request.
 */
public final class Guard{
    private Guard(){}

    /** A raided place: its middle (tiles), the kinds of unit raiding it, and what was lost there. */
    public static final class Raid{
        public final float x, y;
        public final Set<String> kinds;
        public final boolean air;
        public final int losses;

        public Raid(float x, float y, Set<String> kinds, boolean air, int losses){
            this.x = x;
            this.y = y;
            this.kinds = kinds;
            this.air = air;
            this.losses = losses;
        }

        @Override
        public String toString(){
            return String.format(Locale.ROOT, "%d losses round %.0f,%.0f to %s", losses, x, y, kinds);
        }
    }

    /** Lone turrets touching a drill that digs their ammunition, near enough to reach the raided place: the cheapest turret first, the nearest places first. */
    static List<Planner.Plan> touching(Planner.Ask ask, Raid raid, Free free, Feed feed, int k){
        List<Planner.Plan> out = new ArrayList<>();
        //the turrets of the side's modules that hit the raiders, by what they cost
        Map<String, Integer> guns = new LinkedHashMap<>();
        for(Battery m : ask.modules){
            Gun gun = m.gun();
            if(gun == null || (raid.air && !gun.air) || (!raid.air && !gun.ground) || !ask.blocks.contains(gun.name) || guns.containsKey(gun.name)) continue;
            int cost = 0;
            Map<String, Integer> c = ask.costs.get(gun.name);
            if(c != null) for(int v : c.values()) cost += v;
            guns.put(gun.name, cost);
        }
        List<String> order = new ArrayList<>(guns.keySet());
        order.sort(Comparator.comparingInt(guns::get));
        for(String name : order){
            Gun gun = Gun.get(name);
            int size = Battery.shape(name)[0];
            for(Gun.Ammo a : gun.ammo){
                if(a.liquid) continue;
                float reach = gun.range(a);
                List<int[]> spots = new ArrayList<>();
                for(int[] d : feed.pushers(a.what)){
                    //places for the turret whose blocks touch the drill's along a side
                    int dl = Battery.low(d[0], d[2]), db = Battery.low(d[1], d[2]), dr = dl + d[2] - 1, dt = db + d[2] - 1;
                    for(int x = dl - size - 1; x <= dr + size + 1; x++) for(int y = db - size - 1; y <= dt + size + 1; y++){
                        int tl = Battery.low(x, size), tb = Battery.low(y, size), tr = tl + size - 1, tt = tb + size - 1;
                        boolean side = (tr == dl - 1 || tl == dr + 1) && tt >= db && tb <= dt || (tt == db - 1 || tb == dt + 1) && tr >= dl && tl <= dr;
                        if(side && Math.hypot(x - raid.x, y - raid.y) <= reach * 0.7f) spots.add(new int[]{x, y});
                    }
                }
                spots.sort(Comparator.comparingDouble(sp -> Math.hypot(sp[0] - raid.x, sp[1] - raid.y)));
                List<int[]> taken = new ArrayList<>();
                for(int[] sp : spots){
                    if(out.size() >= k) return out;
                    boolean apart = true;
                    for(int[] t : taken) if(Math.hypot(t[0] - sp[0], t[1] - sp[1]) < 4) apart = false;
                    if(!apart || !free.free(name, sp[0], sp[1], 0)) continue;
                    Planner.Plan plan = new Planner.Plan();
                    plan.placements.add(new Placement(name, sp[0], sp[1], 0, null, Placement.Kind.turret, 0));
                    plan.notes.add("guard " + name + " (" + a.what + " from the drill it touches) at " + sp[0] + "," + sp[1] + " for " + raid);
                    out.add(plan);
                    taken.add(sp);
                }
            }
        }
        return out;
    }

    /** Whether a tile is free for a block of the module (the game's own rule, from the side asking). */
    public interface Free{
        boolean free(String block, int x, int y, int rotation);
    }

    /** How far (tiles) a place is from the nearest of the side's buildings that carries or makes an item: where a line to a turret is short. */
    public interface Feed{
        float distance(String item, int x, int y);

        /**
         * The side's buildings that push this item into whatever touches them and takes it (a drill digging it), as
         * {x, y, size} (the tile it stands on): a turret touching one is fed with no line.
         */
        default List<int[]> pushers(String item){
            return List.of();
        }
    }

    /**
     * One module for the raid, as a plan the economy lays like any defence (pinned where it stands, with its supply);
     * null if none of the side's modules can hit the raiders with what it can feed, or none fits near enough.
     * homeX, homeY: the side's core, in tiles. needs: the kinds of unit raiding that fly (any flying needs a turret that hits
     * air; the others one that hits ground).
     */
    public static Planner.Plan plan(Planner.Ask ask, Raid raid, int homeX, int homeY, Free free, StringBuilder notes){
        List<Planner.Plan> all = plans(ask, raid, homeX, homeY, free, (item, x, y) -> 0f, 1, notes);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Up to k ways to guard the raided place, the best first: each the module at a place from which it reaches the raided
     * one, the places ranked by how near they are to it and to where the ammunition already runs (a line from far off is
     * long, and may not be laid at all), and spread apart (the next is no use if the first could not be fed for crowding).
     */
    public static List<Planner.Plan> plans(Planner.Ask ask, Raid raid, int homeX, int homeY, Free free, Feed feed, int k, StringBuilder notes){
        List<Planner.Plan> out = new ArrayList<>();
        //first a lone turret touching a drill of ours that digs its ammunition: fed with no line at all (a line laid into
        //a raided place is the first thing lost, and in crowded ground often can't be laid)
        out.addAll(touching(ask, raid, free, feed, k));
        if(out.size() >= k) return out;
        Battery best = null;
        Gun.Ammo bestAmmo = null;
        int bestCost = Integer.MAX_VALUE;
        for(Battery m : ask.modules){
            Gun gun = m.gun();
            if(gun == null || (raid.air && !gun.air) || (!raid.air && !gun.ground)) continue;
            boolean buildable = true;
            int cost = 0;
            for(Battery.Part p : m.parts){
                if(p.port() || p.optional) continue;
                if(!ask.blocks.contains(p.block)){
                    buildable = false;
                    break;
                }
                Map<String, Integer> c = ask.costs.get(p.block);
                if(c != null) for(int v : c.values()) cost += v;
            }
            if(!buildable) continue;
            //an ammunition it can be fed with (items only: a liquid would want a tank and a line of its own)
            Gun.Ammo ammo = null;
            for(Gun.Ammo a : gun.ammo) if(!a.liquid && ask.supply.contains(a.what) && (ammo == null || a.dps > ammo.dps)) ammo = a;
            if(ammo == null) continue;
            //any port it can't do without (a liquid it must have) must be fed too
            boolean fed = true;
            for(Battery.Part p : m.parts) if(p.port() && !p.optional && p.liquidPort() && !ask.supply.contains(p.config)) fed = false;
            if(!fed) continue;
            if(cost < bestCost){
                best = m;
                bestAmmo = ammo;
                bestCost = cost;
            }
        }
        if(best == null){
            notes.append("guard: no module of ours hits ").append(raid.kinds).append(" with what we can feed\n");
            return out;
        }
        Gun gun = best.gun();
        float reach = gun.range(bestAmmo);
        //places round the raided one, the nearest first, a little towards home (the raiders come from the other way)
        double dx = homeX - raid.x, dy = homeY - raid.y, d = Math.max(1.0, Math.hypot(dx, dy));
        float cx = (float)(raid.x + dx / d * Math.min(4.0, d)), cy = (float)(raid.y + dy / d * Math.min(4.0, d));
        int r = (int)Math.ceil(reach * 0.6f);
        List<int[]> spots = new ArrayList<>();
        for(int x = (int)cx - r; x <= (int)cx + r; x++) for(int y = (int)cy - r; y <= (int)cy + r; y++){
            if(Math.hypot(x - cx, y - cy) <= r) spots.add(new int[]{x, y});
        }
        String ammoName = bestAmmo.what;
        Map<Long, Float> score = new HashMap<>();
        for(int[] s : spots) score.put(((long)s[0] << 32) | (s[1] & 0xffffffffL), (float)Math.hypot(s[0] - cx, s[1] - cy) * 0.5f + feed.distance(ammoName, s[0], s[1]));
        spots.sort(Comparator.comparingDouble(s -> score.get(((long)s[0] << 32) | (s[1] & 0xffffffffL))));
        List<int[]> taken = new ArrayList<>();
        for(int[] s : spots){
            if(out.size() >= k) break;
            boolean apart = true;
            for(int[] t : taken) if(Math.hypot(t[0] - s[0], t[1] - s[1]) < 6) apart = false;
            if(!apart) continue;
            //the raided place must be well within the turret's reach from here
            if(Math.hypot(s[0] - raid.x, s[1] - raid.y) > reach * 0.7f) continue;
            for(int turn = 0; turn < 4; turn++){
                List<Battery.Part> parts = best.at(s[0], s[1], turn);
                boolean fits = true;
                for(Battery.Part p : parts){
                    if(p.optional) continue;
                    //(a port becomes a router fed by a line)
                    String block = p.port() ? (p.liquidPort() ? "reinforced-liquid-router" : "duct-router") : p.block;
                    if(!free.free(block, p.x, p.y, p.rotation)){
                        fits = false;
                        break;
                    }
                }
                if(!fits) continue;
                Planner.Plan plan = new Planner.Plan();
                Planner.Supply supply = new Planner.Supply(0, best.name);
                for(Battery.Part p : parts){
                    if(p.optional) continue;
                    String config = p.config;
                    if(p.port() && !p.liquidPort()) config = bestAmmo.what;
                    Placement placed = new Placement(p.block, p.x, p.y, p.rotation, config, Placement.kindOf(p.block), 0);
                    placed.liquid = p.liquidPort();
                    plan.placements.add(placed);
                    if(placed.kind == Placement.Kind.port) supply.ports.add(placed);
                }
                float firing = bestAmmo.rate * best.guns();
                supply.need(bestAmmo.what, firing, firing * ask.duty, "no store");
                supply.power = gun.power * best.guns();
                plan.supplies.add(supply);
                plan.notes.add("guard " + best.name + " (" + bestAmmo.what + ") at " + s[0] + "," + s[1] + " for " + raid);
                out.add(plan);
                taken.add(s);
                break;
            }
        }
        if(out.isEmpty()) notes.append("guard: no room for ").append(best.name).append(" near ").append(raid).append('\n');
        return out;
    }
}
