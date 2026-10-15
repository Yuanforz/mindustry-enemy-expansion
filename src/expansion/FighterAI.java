package expansion;

import arc.math.*;
import arc.math.geom.*;
import arc.util.*;
import mindustry.entities.units.*;
import mindustry.gen.*;

import static mindustry.Vars.*;

/**
 * One fighting unit of the side the mod commands, steered on its own. The commander says what it is
 * sent against, or where it is to stand; the unit itself chooses what to fire at near there and how
 * to move. What it goes for first is what keeps the other side's turrets firing: the ducts that bring
 * them their ammunition. A turret with nothing to fire is no turret. A unit fast enough to (ships that
 * hover, anything that flies) keeps moving round what it fights at the edge of its own range, and
 * turns now one way and now the other, so that turrets, which aim where it is going, miss; a slow one
 * stands at the edge of its range and fires.
 */
public class FighterAI extends AIController{
    final Brain brain;
    /** What it is sent against, or null. */
    public @Nullable Teamc goal;
    /** Where it is to stand when it is sent against nothing. */
    public final Vec2 post = new Vec2();
    public boolean posted;
    private @Nullable Teamc pick;
    private float pickAt = -1000f, flipAt;
    private int side = 1;
    private final Vec2 way = new Vec2(), step = new Vec2();

    public FighterAI(Brain brain){
        this.brain = brain;
    }

    public void attack(Teamc what){
        goal = what;
        posted = false;
    }

    public void standAt(float x, float y){
        goal = null;
        posted = true;
        post.set(x, y);
    }

    /**
     * Whether it is quick enough to dodge. That is a matter of speed alone: walkers on legs count as hovering, and at under
     * half a ship's pace they were sent zig-zagging under the guns like ships, and thrown back with one left of seventeen.
     */
    public boolean agile(){
        return unit.type.speed >= 1.2f;
    }

    private static boolean gone(@Nullable Teamc what){
        return what == null || (what instanceof Healthc health && !health.isValid()) || (what instanceof Building build && !build.isValid());
    }

    /** What it fires at near what it was sent against: chosen afresh twice a second. */
    private @Nullable Teamc choice(){
        if(gone(goal)) return null;
        if(gone(pick) || Time.time - pickAt > 30f){
            pickAt = Time.time;
            pick = brain.pickTarget(unit, goal);
        }
        return pick;
    }

    @Override
    public void updateMovement(){
        if(gone(goal)) goal = null;
        Teamc aim = choice();
        if(aim == null){
            if(posted) travel(post.x, post.y, tilesize * 3f);
            return;
        }
        float range = unit.range();
        //laying siege to a turret, it keeps to that turret's far side from its neighbours whatever it fires at: what feeds the
        //turret runs in among the others, and going after it there brought every turret round within reach
        float[] spot = agile() && goal instanceof mindustry.world.blocks.defense.turrets.Turret.TurretBuild ? brain.siegeSpot(goal, range) : null;
        if(spot != null) aim = goal;
        //it steers itself only once within reach of its own guns: before that it goes by the game's path-finding. (steering
        //itself from three dozen tiles off, it went straight at what it was sent against, into the wall between, and stood
        //there; what walks has to go round)
        //(laying siege, only once on its own stretch of the ring: going round from the side it came up on, it passed under
        //the next turret's guns)
        if(unit.within(aim, range + tilesize * (unit.type.flying ? 12f : 4f)) && (spot == null || Angles.angleDist(Angles.angle(aim.getX(), aim.getY(), unit.x, unit.y), spot[0]) <= spot[1] + 30f)){
            //each comes at it from a side of its own, spread over half the ring that faces its own base: what defends it
            //has to turn from one to the next and back. one that is quick enough moves to and fro within its side as well,
            //turning one way and then the other every second or two, so that what aims where it is going misses
            //but when the whole group goes for one thing it comes from one side: what is fired at a turret hits the walls round
            //it first, and walls are only got through where all the fire falls on the same ones
            boolean together = brain.striking(aim);
            //against a turret, from the stretch of the ring round it its neighbours do not reach, and no further round than that
            float home = spot == null ? homeBearing(aim) : spot[0];
            float sector = home + (together && !agile() ? 0f : ((unit.id % 7) - 3) * 22f), spread = together && !agile() ? 12f : 30f;
            if(spot != null){
                sector = spot[0] + Mathf.clamp(((unit.id % 7) - 3) * 22f, -spot[1] * 0.5f, spot[1] * 0.5f);
                spread = Math.max(spot[1] * 0.5f, 8f);
            }
            if(agile()){
                //never at a steady speed: a turret aims where its target will be if it keeps going as it goes, and its shot
                //takes a while to come; what is always changing speed across the line of fire is not there when it does. it
                //goes to and fro across that line as hard as it can, turning back before it is up to full speed, so that it is
                //never not speeding up one way or the other. how far off it is when the shot comes is how hard it can speed
                //up times the shot's time squared over two. (ships that stood still to fire were hit by nearly every shot;
                //ones that circled, by two in three)
                float dist = unit.dst(aim);
                float now = Angles.angle(aim.getX(), aim.getY(), unit.x, unit.y);
                //which way across: back towards its own side of the ring when it has gone too far round
                float off = Angles.angleDist(now, sector);
                way.trns(now, 1f);
                step.set(-way.y, way.x);
                float across = unit.vel.dot(step);
                if(across * side >= unit.speed() * 0.8f || (off > spread && Angles.angleDist(now + side, sector) > off)) side = -side;
                //as near as six tenths of its range: further out its shots, slow and turning after their target, mostly
                //went astray (measured against one breach: six ships at six tenths of their range brought it down to a tenth
                //and lost one; standing still half as near, they lost five and took a third off it)
                float outward = Mathf.clamp((range * 0.6f - dist) / (tilesize * 2f), -1f, 1f);
                //and slantwise, in and out as it goes across: straight across, its own shots, which keep its speed, went wide.
                //(measured against one breach: six ships going straight across brought it to a quarter and lost three; going
                //slantwise they brought it down and lost one)
                step.scl(side).add(way.x * (outward * 0.6f + 0.5f * side), way.y * (outward * 0.6f + 0.5f * side)).nor().scl(unit.speed());
                unit.movePref(step);
            }else{
                way.trns(sector, range * 0.85f).add(aim.getX(), aim.getY());
                moveTo(way, tilesize * 1.5f);
            }
        }else if(spot != null){
            //to that stretch, not to the nearest side of it: coming up on the near side, it came within reach of the others
            travel(aim.getX() + Angles.trnsx(spot[0], range * 0.9f), aim.getY() + Angles.trnsy(spot[0], range * 0.9f), tilesize * 3f);
        }else{
            travel(aim.getX(), aim.getY(), range * 0.85f);
        }
    }

    /** Which way its own nearest core lies from a place, in degrees: the side its units come at it from. */
    private float homeBearing(Teamc what){
        Building core = unit.closestCore();
        return core == null ? Angles.angle(what.getX(), what.getY(), unit.x, unit.y) : Angles.angle(what.getX(), what.getY(), core.x, core.y);
    }

    /** Goes to a place by the way the game's own path-finding gives for its kind of unit. */
    /** How it last moved, for looking into it. */
    public String how = "";

    private void travel(float x, float y, float within){
        if(unit.within(x, y, within)){
            how = "there";
            return;
        }
        way.set(x, y);
        if(unit.type.flying){
            how = "flies";
            moveTo(way, within);
        }else if(controlPath.getPathPosition(unit, way, way, step, null)){
            how = "path to " + (int)(step.x / tilesize) + "," + (int)(step.y / tilesize);
            moveTo(step, 1f);
        }else if(!unit.within(x, y, tilesize * 30f) && unit.closestEnemyCore() != null && unit.closestEnemyCore().within(x, y, tilesize * 60f)){
            how = "field";
            //no way worked out to the place itself: towards the other side's core by the game's own field for its kind of unit,
            //which is always worked out, as the game's own ground units go. straight on, it pressed against the wall of its own
            //base for a quarter of an hour
            pathfind(mindustry.ai.Pathfinder.fieldCore);
        }else{
            how = "straight";
            moveTo(way, within);
        }
    }

    @Override
    public Teamc findMainTarget(float x, float y, float range, boolean air, boolean ground){
        Teamc aim = choice();
        if(aim != null && aim.within(x, y, range + size(aim))) return aim;
        return super.findMainTarget(x, y, range, air, ground);
    }

    @Override
    public Teamc findTarget(float x, float y, float range, boolean air, boolean ground){
        Teamc aim = choice();
        if(aim != null && aim.within(x, y, range + size(aim))) return aim;
        return super.findTarget(x, y, range, air, ground);
    }


    private static float size(Teamc what){
        return what instanceof Building build ? build.hitSize() / 2f : what instanceof Hitboxc box ? box.hitSize() / 2f : 0f;
    }
}
