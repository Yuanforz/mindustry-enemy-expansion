package expansion;

import arc.struct.*;

/**
 * Decides what the enemy is about at the moment, and so in what order it goes about things.
 *
 * It is a small state machine. The enemy is always in one of four postures, and moves from one to
 * another only on what can be measured: whether it has a source of everything it can't do without,
 * how full its cores are, how long it has found nothing to do, how much of what it built was lately
 * destroyed. Nothing here knows the name of a block or of a map, or how far the player has researched:
 * what may be built only decides what the goals below find to do.
 *
 * A posture is a list of goals in the order they are tried. Some are tried every round, first to last.
 * The rest take turns, and the one whose turn it is gets saved up for.
 */
public class Director{
    public enum Posture{
        /** Something it can't do without has no source. Getting one comes before all else. */
        bootstrap,
        /**
         * Growing. First it takes up all the ore it can get at and makes its drills faster; turrets only
         * where it is hit. When there is no more to take up, a few turrets to cover what it has, and then
         * all it has to spare goes into units. With as many unit yards as it may have, the army's turn goes
         * to making their units better.
         */
        develop,
        /** Its cores stand full or it has run out of things to add: what it has is made better first, and then it grows on. */
        invest,
        /** Losing buildings or cut off from its mines: it mends and defends, and does not reach further out. */
        pressed,
        /**
         * Its army and the other side's stand against each other. Now turrets go up at the front, behind
         * its army, so that the line can be pushed forward; and with the fighting out there, at home its
         * lines are put in order and its turrets made better.
         */
        front
    }

    /** One thing the enemy may set about. What each does is the brain's business. */
    public enum Goal{
        /** Leads a line the player has cut on to a core again. */
        mend,
        /** Sees to a liquid something was found to need. */
        owed,
        /** A first source of each thing it can't do without. */
        staples,
        /** Its first unit yard. */
        firstYard,
        /** Its first turret: something to hold what it has against the first thing that comes. */
        firstPost,
        /** A few turrets, so that its cores and what it lives on are roughly under the guns of one. */
        cover,
        /** Makes its drills faster with what they are faster with. */
        speed,
        /** Power, taken up like ore: a generator on every free vent it can get at. */
        vents,
        /** What the map it plays asks of it, where that has not come of its own building. */
        task,
        /** Ammunition to turrets of its own that stand empty with no line to them. */
        arm,
        /** Water to its turrets, where there is some to spare within reach. */
        cool,
        /** Puts its lines in order: leads them home by fewer, shared ways, and takes up duct that serves nothing. */
        tidy,
        /** More generators for a grid that can't keep up. */
        power,
        /** Makes something it has better: adds to it, lets in what makes it faster, puts better in its place. */
        refit,
        /** Uncovers ground it wants to build on and none of its units can get to: something that sees far, put up nearby. */
        reveal,
        /** Gets what stands idle going: a yard or factory short of what it takes, or of power, is seen to before anything new. */
        activate,
        /**
         * Drills on more of what it already mines, before turrets, yards and factories, as long as its home is not
         * being hit and drills have had no more than their share of what it spent lately.
         */
        ore,
        army, defence, income, core
    }

    /** How long a posture is kept before it may be left for a less urgent one. */
    public static final float dwell = 60f * 30f;
    /** How long its cores have to stand well filled before it turns to making things better. */
    public static final float richTime = 60f * 20f;
    /** How full the emptiest of what it can't do without has to be to count as well filled, and how empty to count as spent. */
    public static final float rich = 0.6f, spent = 0.25f;
    /** How many rounds with nothing to do make it look for something better to do. */
    public static final int idleRounds = 4;
    /** How many of its buildings lost, lately, mean it is under attack. */
    public static final float pressure = 5f;

    public Posture posture = Posture.bootstrap;
    /** When it took up this posture, by the enemy's clock. */
    public float since;
    /** Rounds in a row in which it found nothing to lay out and nothing to save for. */
    public int idle;
    /** Its buildings destroyed lately: each counts one, and the count halves every half minute. */
    public float losses;
    private float richSince = -1f;

    /** What a round of planning starts from. */
    public static class Facts{
        /** Whether something it can't do without has no source that gets to a core. */
        public boolean lacking;
        /** Whether a line of its own stops short of a core. */
        public boolean cut;
        /** How full its cores are of whatever it can't do without that they hold least of, from 0 to 1. */
        public float fill;
        /** Whether all the ore it could get at is being taken up: looking for more came to nothing, time after time. */
        public boolean saturated;
        /** Whether its army has been in a fight with the other side's for a while and is still at it. */
        public boolean engaged;
        /** How near its fighting units are to as many as it may have, from 0 to 1. */
        public float army;
        /** How hard it is pressed: what it lost lately, and the other side's army seen at its base. 0 is left alone, 1 and more is under attack. */
        public float pressure;
        /** Whether a core of its own has no turret over it. */
        public boolean uncovered;
        /** Whether it has fewer turrets of its own than the few that cover a base before the fighting starts in earnest. */
        public boolean thin;
        /** How much is wrong inside its base: lines cut, things standing idle for want of supply, things that could be made better. 0 to 1. */
        public float trouble;
        /** Whether what attacks it hits so lightly that units it can build shrug most of it off. */
        public boolean counter;
    }

    /**
     * What it may set about, besides mending and seeing to what it can't do without. Each round every
     * one is weighed on how things stand, and they are tried the weightiest first. Nothing is fixed
     * beforehand: the same base under attack, left alone, short of everything or rich turns to other
     * things first. (Mining by hand is what its builders do when there is nothing to build, and its
     * army is led apart from all this.)
     */
    public enum Choice{
        /** More of what lies about: drills on ore, generators on vents, faster drills, more cores. */
        spread(Goal.income, Goal.vents, Goal.speed, Goal.core, Goal.reveal),
        /** Turrets out where its army stands against the other side's. */
        outposts(Goal.defence),
        /** Unit yards, and making their units better. */
        yards(Goal.firstYard, Goal.army),
        /** Inside its base: lines put in order, power, factories, turrets and drills made better. */
        improve(Goal.power, Goal.tidy, Goal.refit, Goal.cool),
        /** Its home defended: turrets over what it lives on, where it is hit first; water to them. */
        hold(Goal.cover, Goal.cool);

        public final Goal[] goals;

        Choice(Goal... goals){
            this.goals = goals;
        }
    }

    /** How much each choice weighed in the last round, for looking into how it decides. */
    public final float[] weights = new float[Choice.values().length];

    /** Whether it has taken up all the ore it could get at, as last seen. Decides what it grows on. */
    public boolean saturated;

    public void reset(){
        posture = Posture.bootstrap;
        since = 0f;
        idle = 0;
        losses = 0f;
        richSince = -1f;
    }

    /** Time goes by. */
    public void tick(float delta){
        losses *= (float)Math.pow(0.5, delta / (60f * 30f));
    }

    /** Takes in how things stand, and moves to another posture if they call for it. */
    public Posture update(Facts facts, float clock){
        if(facts.fill >= rich){
            if(richSince < 0f) richSince = clock;
        }else{
            richSince = -1f;
        }
        boolean wealthy = (richSince >= 0f && clock - richSince >= richTime) || idle >= idleRounds;
        boolean attacked = losses >= pressure || facts.cut;
        boolean settled = clock - since >= dwell;
        saturated = facts.saturated;

        Posture next = posture;
        if(facts.lacking && !attacked){
            next = Posture.bootstrap;
        }else if(facts.lacking){
            //hit while it has nothing coming in: it sees to both, the defence first
            next = Posture.pressed;
        }else{
            switch(posture){
                case bootstrap -> next = Posture.develop;
                case develop -> next = attacked ? Posture.pressed : facts.engaged ? Posture.front : wealthy ? Posture.invest : posture;
                case invest -> next = attacked ? Posture.pressed : facts.engaged ? Posture.front : settled && facts.fill < spent && idle == 0 ? Posture.develop : posture;
                case pressed -> next = settled && !attacked ? (facts.engaged ? Posture.front : Posture.develop) : posture;
                case front -> next = attacked ? Posture.pressed : settled && !facts.engaged ? Posture.develop : posture;
                default -> {}
            }
        }
        if(next != posture){
            posture = next;
            since = clock;
        }
        return posture;
    }

    private static final Goal[] needs = {Goal.mend, Goal.owed, Goal.arm, Goal.activate, Goal.staples, Goal.task};

    /**
     * Weighs the choices on how things stand. Spreading counts for most while there is ore and vents left
     * and its cores are not full, and for less the harder it is pressed. Yards count for more the smaller
     * its army and the fuller its cores, and most once all there is to take up is taken up. Holding its home
     * counts with the pressure, and for something whenever a core of its own has no turret over it.
     * Turrets out at the front count only while its army stands against the other side's. Making its base
     * better counts when its cores are full or something inside is wrong, and not under attack.
     */
    public void weigh(Facts facts){
        float rich = Math.min(Math.max(facts.fill, 0f), 1f), press = Math.min(Math.max(facts.pressure, 0f), 1f);
        weights[Choice.spread.ordinal()] = (facts.saturated ? 0.25f : 1f) * (1f - 0.8f * press) * (0.6f + 0.4f * (1f - rich));
        weights[Choice.yards.ordinal()] = (0.3f + 0.7f * (1f - facts.army)) * (0.3f + 0.7f * rich) * (facts.saturated ? 1f : 0.35f) * (1f - 0.5f * press);
        weights[Choice.hold.ordinal()] = 1.3f * press + (facts.uncovered ? 0.45f : 0f) + (facts.saturated && facts.thin ? 0.4f : 0f);
        weights[Choice.outposts.ordinal()] = facts.engaged ? 0.5f + 0.5f * rich : 0f;
        weights[Choice.improve.ordinal()] = (0.3f * facts.trouble + Math.max(rich - 0.5f, 0f) * 1.2f) * (1f - press);
        //against raiders whose shots its own units' armour mostly turns, a yard holds home as well as a turret does: it
        //comes right after the turrets
        if(facts.counter) weights[Choice.yards.ordinal()] = Math.max(weights[Choice.yards.ordinal()], weights[Choice.hold.ordinal()] * 0.9f);
    }

    /**
     * Orders the choices by a look-ahead instead: by how soon, setting about each first, it would be ready
     * to strike (in seconds; more for worse, far more for losing its home). Turrets out at the front stay
     * a choice only while the armies stand against each other.
     */
    public void order(float[] outcome){
        boolean front = weights[Choice.outposts.ordinal()] > 0f;
        for(Choice choice : Choice.values()) weights[choice.ordinal()] = 0.06f + 1f / (1f + outcome[choice.ordinal()] / 60f);
        weights[Choice.outposts.ordinal()] = front ? weights[Choice.hold.ordinal()] - 0.001f : 0f;
    }

    /** What is tried every round, in this order: mending and what it can't do without, then the choices, the weightiest first. */
    public Goal[] first(){
        if(posture == Posture.bootstrap) return needs;
        Seq<Choice> order = new Seq<>(Choice.values());
        order.removeAll(choice -> weights[choice.ordinal()] < 0.05f);
        order.sort(choice -> -weights[choice.ordinal()]);
        Seq<Goal> out = new Seq<>(needs);
        for(Choice choice : order){
            for(Goal goal : choice.goals) if(!out.contains(goal)) out.add(goal);
        }
        return out.toArray(Goal.class);
    }

    /** Nothing takes turns any more: what comes first is weighed afresh each round. */
    public Goal[] turns(){
        return posture == Posture.bootstrap ? new Goal[]{Goal.income, Goal.army} : new Goal[0];
    }
}
