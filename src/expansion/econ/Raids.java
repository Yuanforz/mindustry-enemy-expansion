package expansion.econ;

import arc.struct.*;
import arc.util.*;
import expansion.defense.Guard;
import mindustry.*;
import mindustry.entities.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;

import java.util.*;

/**
 * What a side loses to the other side's units, and where: the places being raided. A building lost while one of the other
 * side's fighting units had it within reach is put down to that unit's kind; losses close together in a short while are
 * one raided place; a place no loaded turret of the side's that can hit those kinds reaches is one to guard
 * ({@link Guard}). Losses to turrets (building too close to the other side's) are not raids and are left out.
 *
 * For any side: the side is given, the other sides are whoever its units' enemies are.
 */
public final class Raids{
    public final Team team;
    /** Losses this close together (tiles) in this long (seconds) are one place; this many make it a raided place. */
    public static float near = 15f, window = 120f;
    public static int least = 3;
    /** How long a place a guard was planned for is left alone before it may be asked for again (seconds). */
    public static float guardFor = 240f;

    static final class Loss{
        final int x, y;
        final float at;
        final String kind;
        final boolean air;
        /** What it cost to build: a furnace lost is more than a duct lost. */
        final int worth;

        Loss(int x, int y, float at, String kind, boolean air, int worth){
            this.x = x;
            this.y = y;
            this.at = at;
            this.kind = kind;
            this.air = air;
            this.worth = worth;
        }
    }

    final Seq<Loss> losses = new Seq<>();
    final Seq<float[]> guarded = new Seq<>();
    /** For the report: losses put down to raiders, by kind. */
    public final ObjectIntMap<String> byKind = new ObjectIntMap<>();

    public Raids(Team team){
        this.team = team;
    }

    static float now(){
        return (float)(Vars.state.tick / 60.0);
    }

    /** Told of a building of the side's lost (from the game's block-destroyed event). */
    public void lost(Tile tile){
        if(tile == null || tile.team() != team) return;
        float wx = tile.worldx(), wy = tile.worldy();
        Unit[] by = {null};
        Units.nearbyEnemies(team, wx, wy, 40f * Vars.tilesize, u -> {
            if(u.spawnedByCore || !u.type.canAttack || u.dead()) return;
            if(!u.within(wx, wy, u.range() + 4f * Vars.tilesize)) return;
            if(by[0] == null || u.dst2(wx, wy) < by[0].dst2(wx, wy)) by[0] = u;
        });
        if(by[0] == null) return;
        int worth = 0;
        //(a site lost half-built is its block's worth too: it was going to be that)
        Block block = tile.build instanceof mindustry.world.blocks.ConstructBlock.ConstructBuild site && site.current != null ? site.current : tile.block();
        for(mindustry.type.ItemStack stack : block.requirements) worth += stack.amount;
        losses.add(new Loss(tile.x, tile.y, now(), by[0].type.name, by[0].isFlying(), Math.max(1, worth)));
        byKind.increment(by[0].type.name);
    }

    /**
     * The place most raided lately that no loaded turret of the side's able to hit the raiders reaches, and that no guard
     * was planned for a while ago; null if none. covered: whether a turret's reach is all that is wanted (one standing
     * dry there counts, when dry counts: feeding it is the answer then).
     */
    public @Nullable Guard.Raid hottest(boolean dryCounts){
        float t = now();
        losses.removeAll(l -> t - l.at > window);
        guarded.removeAll(g -> g[2] < t);
        Guard.Raid best = null;
        float bestWorth = 0f;
        for(Loss seed : losses){
            int n = 0;
            float worth = 0f;
            float sx = 0f, sy = 0f;
            boolean air = false;
            Set<String> kinds = new TreeSet<>();
            for(Loss l : losses){
                if(Math.hypot(l.x - seed.x, l.y - seed.y) > near) continue;
                n++;
                worth += l.worth;
                sx += l.x;
                sy += l.y;
                air |= l.air;
                kinds.add(l.kind);
            }
            //(the place where the most was lost, by what it cost: the furnace and the yard it feeds before a row of crushers)
            if(n < least || worth <= bestWorth) continue;
            float cx = sx / n, cy = sy / n;
            boolean done = false;
            for(float[] g : guarded) if(Math.hypot(g[0] - cx, g[1] - cy) <= near) done = true;
            if(done || covered(cx, cy, air, dryCounts)) continue;
            best = new Guard.Raid(cx, cy, kinds, air, n);
            bestWorth = worth;
        }
        return best;
    }

    /** Whether one of the side's turrets that can hit units of this kind (in the air or not) reaches the place, loaded (or not, if dry counts). */
    public boolean covered(float x, float y, boolean air, boolean dryCounts){
        for(Building b : team.data().buildings){
            if(!(b instanceof Turret.TurretBuild tb) || !(b.block instanceof Turret turret)) continue;
            if(air ? !turret.targetAir : !turret.targetGround) continue;
            if(!dryCounts && !tb.hasAmmo()) continue;
            if(Math.hypot(b.tileX() - x, b.tileY() - y) * Vars.tilesize <= turret.range) return true;
        }
        return false;
    }

    /**
     * How far (tiles) a place is from the nearest of the side's buildings that has this item in it or makes it (a duct
     * carrying it, a drill digging it, the core): where a line for a turret would be short.
     */
    public float feedDistance(String itemName, int x, int y){
        mindustry.type.Item item = Vars.content.item(itemName);
        if(item == null) return 1000f;
        float best = 1000f;
        for(Building b : team.data().buildings){
            boolean has = b instanceof mindustry.world.blocks.storage.CoreBlock.CoreBuild
                || (b.items != null && b.items.has(item) && !(b.block instanceof Turret))
                || (b instanceof mindustry.world.blocks.production.Drill.DrillBuild d && d.dominantItem == item)
                || (b instanceof mindustry.world.blocks.production.BeamDrill.BeamDrillBuild bd && bd.lastItem == item);
            if(!has) continue;
            best = Math.min(best, (float)Math.hypot(b.tileX() - x, b.tileY() - y));
        }
        return best;
    }

    /** The side's drills that dig this item, as {x, y, size}: what touches them and takes it is fed by them. */
    public java.util.List<int[]> pushers(String itemName){
        mindustry.type.Item item = Vars.content.item(itemName);
        java.util.List<int[]> out = new java.util.ArrayList<>();
        if(item == null) return out;
        for(Building b : team.data().buildings){
            boolean digs = (b instanceof mindustry.world.blocks.production.Drill.DrillBuild d && d.dominantItem == item)
                || (b instanceof mindustry.world.blocks.production.BeamDrill.BeamDrillBuild bd && bd.lastItem == item);
            if(digs) out.add(new int[]{b.tileX(), b.tileY(), b.block.size});
        }
        return out;
    }

    /** As the guard asks for it: how far from what carries an item, and what pushes it into what touches it. */
    public Guard.Feed feed(){
        return new Guard.Feed(){
            @Override
            public float distance(String item, int x, int y){
                return feedDistance(item, x, y);
            }

            @Override
            public java.util.List<int[]> pushers(String item){
                return Raids.this.pushers(item);
            }
        };
    }

    /** A guard was planned for this place: left alone for a while. */
    public void guarding(float x, float y){
        guarded.add(new float[]{x, y, now() + guardFor});
    }
}
