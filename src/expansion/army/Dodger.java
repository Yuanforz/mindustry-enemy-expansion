package expansion.army;

import arc.math.*;
import arc.math.geom.*;
import arc.util.*;
import mindustry.*;
import mindustry.ai.types.*;
import mindustry.entities.bullet.*;
import mindustry.gen.*;

/**
 * A3, micro: the game's own controller for a unit given orders (so the squad AI still leads it, and gives it its orders
 * and takes them back as before), with one thing more: it gets out of the way of what is fired at it.
 *
 * Each frame it moves as the game would move it. If, moving that way, a shot of the other side's flying now would hit
 * it in the next second or so, it looks at sixteen other ways to go (and standing still) and takes the one nearest the
 * way it meant to go that no shot hits, or the one hit least if all are: a velocity obstacle, worked out by stepping
 * each shot and the unit forward together, the unit speeding up as fast as it can and no faster. A shot that bursts
 * where it lands (artillery) is kept away from where it will land, by its blast.
 *
 * Only for units quick enough to get anywhere in the time a shot takes ({@link #agile}): the slow ones lose more by
 * leaving their path than they save.
 */
public class Dodger extends CommandAI{
    /** How far ahead it looks, in ticks, and in steps of how many. */
    static final float horizon = 48f, step = 3f;
    static final int ways = 16;
    static final Vec2 want = new Vec2(), best = new Vec2(), tryVel = new Vec2();

    public final Army army;
    /** Shots stepped out of the way of, for the report. */
    public int dodged;

    public Dodger(Army army){
        this.army = army;
    }

    /** Whether a kind of unit is quick enough for it: it can move its own width in the time a slow shot crosses a turret's range. */
    public static boolean agile(Unit unit){
        var type = unit.type;
        if(!type.omniMovement || type.speed <= 0f) return false;
        //(a breach's shot crosses its range in about 25 ticks)
        float t = 25f, off = Math.min(0.5f * type.accel * type.speed * t * t, type.speed * t);
        return off >= type.hitSize;
    }

    @Override
    public void updateUnit(){
        float vx = unit.vel.x, vy = unit.vel.y;
        super.updateUnit();
        if(!unit.isValid() || !army.flags.micro) return;
        //what the game meant it to do this frame, as how its speed changed
        want.set(unit.vel).sub(vx, vy);
        //where it was going: the speed it was heading for
        Vec2 intent = Tmp.v4.set(vx, vy);
        if(!want.isZero(0.0001f)) intent.add(want.setLength(unit.speed()));
        intent.limit(unit.speed());
        if(!steer(unit, intent, vx, vy, best)) return;
        dodged++;
        unit.vel.set(vx, vy);
        unit.movePref(best);
    }

    /**
     * The way out for a unit from the speed it has, if heading for the speed it means to would get it hit: the nearest
     * of the other ways to go, or standing still, that is hit least.
     * @return whether there is one better than what it meant to do (then it is in out)
     */
    public static boolean steer(Unit unit, Vec2 intent, float vx, float vy, Vec2 out){
        float accel = unit.type.accel * unit.speed();
        if(accel <= 0f) return false;
        float baseCost = cost(unit, intent, vx, vy, accel);
        if(baseCost <= 0f) return false;
        float bestCost = baseCost + 0.001f;
        out.set(intent);
        boolean better = false;
        for(int i = 0; i <= ways; i++){
            if(i == ways) tryVel.setZero();
            else tryVel.trns(i * 360f / ways + unit.id % 7, unit.speed());
            float c = cost(unit, tryVel, vx, vy, accel);
            //a little for going a different way from where it meant to
            c += tryVel.dst(intent) / Math.max(unit.speed(), 0.01f) * 0.5f;
            if(c < bestCost){
                bestCost = c;
                out.set(tryVel);
                better = true;
            }
        }
        return better;
    }

    /** The damage the shots near it would do to it, were it to head for a speed from the speed it has. */
    static float cost(Unit unit, Vec2 target, float vx, float vy, float accel){
        float[] sum = {0f};
        float range = horizon * 12f;
        float ux = unit.x, uy = unit.y, radius = unit.hitSize / 2f;
        boolean air = unit.isFlying();
        Groups.bullet.intersect(ux - range, uy - range, range * 2f, range * 2f, b -> {
            if(b.team == unit.team || b.type == null) return;
            BulletType type = b.type;
            boolean hits = type.collides && (air ? type.collidesAir : type.collidesGround), bursts = !hits && type.splashDamageRadius > 0f;
            if(!hits && !bursts) return;
            float left = b.lifetime - b.time;
            if(left <= 0f) return;
            //stepped forward with the unit
            float px = ux, py = uy, pvx = vx, pvy = vy, bx = b.x, by = b.y, bvx = b.vel.x, bvy = b.vel.y;
            float reach = radius + b.hitSize / 2f + 2f;
            float end = Math.min(horizon, left);
            for(float t = step; t <= end + 0.01f; t += step){
                //its speed heads for the target as fast as it can
                float dvx = target.x - pvx, dvy = target.y - pvy, len = Mathf.len(dvx, dvy), most = accel * step;
                if(len > most){
                    dvx *= most / len;
                    dvy *= most / len;
                }
                pvx += dvx;
                pvy += dvy;
                //nearest the two come within the step (a shot goes further in a step than the unit is wide, so the ends alone miss it)
                if(!bursts){
                    float rx = bx - px, ry = by - py, wx = bvx - pvx, wy = bvy - pvy, ww = wx * wx + wy * wy;
                    float at = ww <= 0.0001f ? 0f : Mathf.clamp(-(rx * wx + ry * wy) / ww, 0f, step);
                    if(Mathf.within(rx + wx * at, ry + wy * at, 0f, 0f, reach)){
                        sum[0] += b.damage;
                        return;
                    }
                }
                px += pvx * step;
                py += pvy * step;
                bx += bvx * step;
                by += bvy * step;
            }
            //what bursts where it lands: kept away from there
            if(bursts && left <= horizon){
                float lx = b.x + bvx * left, ly = b.y + bvy * left;
                if(Mathf.within(px, py, lx, ly, type.splashDamageRadius + radius)) sum[0] += type.splashDamage;
            }
        });
        return sum[0];
    }
}
