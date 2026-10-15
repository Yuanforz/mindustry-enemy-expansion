package expansion;

/**
 * Looks ahead before choosing what to build. From what is measured on the map as it is played (what
 * comes in a second of each thing, how much of the other side's army arrives at its base and how much
 * of it its turrets can kill, what the other side's target takes to bring down, how fast its yards
 * turn out units), it plays the next few minutes forward once for each thing it could set about first,
 * and takes the one after which it would be ready to strike soonest without losing its home.
 *
 * The model is small and plain, and every number in it is either measured or read from the game:
 * <ul>
 * <li>Its stock grows by what comes in and shrinks by what it builds. A new source adds what a source
 * of that item brings in now, once it stands.</li>
 * <li>The other side's units arrive as a stream of health; its turrets and the units at home take it
 * away as fast as they fire. What they can't kill piles up and fires on what it has built.</li>
 * <li>What it would take to bring the other side's target down goes by the square law of a fight in
 * which both sides fire at once: a group of n units, each dealing d a second and lasting h, beats
 * what has H health behind guns dealing G a second when n squared is more than 2 H G over d h.</li>
 * <li>A yard turns out units as fast as it is measured to, while there is stock for them.</li>
 * </ul>
 * After the first thing, the same plain rule decides each step of the look-ahead: turrets while more
 * arrives than is killed, sources while the stock is short, yards otherwise. That rule only fills in
 * the rest of the look-ahead; what is chosen is the first step.
 */
public class Forecast{
    /** What is measured and read, filled in by the brain before each look-ahead. */
    public static class Inputs{
        /** Stock and what comes in a second, as worth: every item counted alike. */
        public float stock, income, cap;
        /** What a new source would add a second, and cost; 0 when there is no more to be had. */
        public float sourceGain, sourceCost;
        /** The damage a second the other side's units at its base deal, and the fire of its turrets that reaches where they are. */
        public float exposure, coverage;
        /** What of its own is being destroyed a second, in what it cost: measured. */
        public float lossRate;
        /** What one more turret fires a second, and costs. */
        public float turretKill, turretCost;
        /** What its buildings are worth, and how much health stands for each point of that. */
        public float assets, healthPerWorth = 4f;
        /** Units it has that can get to the target; how many it may have; one unit's damage a second against the target, health, and cost. */
        public float army, armyCap, unitDps, unitHealth, unitCost;
        /** Units a second its yards turn out flat out, and what one more yard adds and costs. 0 where no kind it can build reaches the target. */
        public float yardRate, yardGain, yardCost;
        /** What the target and what stands in front of it come to, and what guards it deals a second. */
        public float targetHealth, guard;
        /** The kind of unit that would hold home best: its damage a second, what one costs, how many a second a yard of them turns out, and the yard's cost. */
        public float guardDps, guardCost, guardRate, guardYardCost;
        /** How many such units it has at home now, and how many a second its yards of them turn out. */
        public float guards, guardYards;
        /** What making the base better would add a second (starved things fed, lines put right), and cost. */
        public float improveGain, improveCost;
        /** How much the builders put up a second, in worth. */
        public float buildRate = 20f;
    }

    /** How far ahead it looks, and in steps of how long, in seconds. */
    public static final float horizon = 60f * 8f, step = 5f;

    /** What each choice came to in the last look-ahead: how many seconds until it could strike, or more for worse. For looking into it. */
    public final float[] outcome = new float[Director.Choice.values().length];

    /** Units needed to bring the target down, by the square law; and how soon it could have them, as last worked out. */
    public float needed, readyIn;

    /**
     * Plays the look-ahead once for each choice and gives back how each came out: the seconds until it
     * would be ready to strike, the time it would lose its home counting as never.
     */
    public float[] look(Inputs in){
        needed = in.unitDps > 0f && in.unitHealth > 0f ? (float)Math.sqrt(2f * in.targetHealth * Math.max(in.guard, 1f) / (in.unitDps * in.unitHealth)) : Float.MAX_VALUE;
        for(Director.Choice choice : Director.Choice.values()) outcome[choice.ordinal()] = play(in, choice);
        if(needed == Float.MAX_VALUE){
            //with nothing to get ready for, each came out as minus what its base would be worth at the end: that is turned
            //into how many seconds of what comes in now it falls behind the best of them
            float best = Float.MAX_VALUE;
            for(float value : outcome) if(value < 0f) best = Math.min(best, value);
            for(int i = 0; i < outcome.length; i++){
                outcome[i] = outcome[i] >= 0f ? Math.max(outcome[i], horizon * 4f) : Math.min((outcome[i] - best) / Math.max(in.income, 0.1f), horizon * 4f);
            }
        }
        readyIn = Float.MAX_VALUE;
        for(float value : outcome) readyIn = Math.min(readyIn, value);
        return outcome;
    }

    private float play(Inputs in, Director.Choice first){
        float stock = in.stock, income = in.income, army = in.army, yardRate = in.yardRate, assets = in.assets;
        //a unit of theirs lives as long as the fire on it takes to bring it down, and deals damage all that while: more fire on
        //the places they come to cuts what they do in proportion
        float cover = Math.max(in.coverage, 30f), lost = 0f, guards = in.guards, guardYards = in.guardYards;
        //what is being built and when it stands
        Director.Choice building = null;
        float buildLeft = 0f;
        boolean firstDone = false;
        for(float t = 0f; t < horizon; t += step){
            //the next thing to set about, once the last stands and is paid for
            if(building == null){
                Director.Choice next = firstDone ? rule(in, stock, in.lossRate * Math.max(in.coverage, 30f) / Math.max(cover + (guards - in.guards) * in.guardDps, 1f)) : first;
                float cost = cost(in, next);
                if(cost < 0f){
                    //nothing of that kind to be done: the look-ahead goes on by the rule
                    if(!firstDone) return horizon * 4f;
                }else if(stock >= cost){
                    stock -= cost;
                    building = next;
                    buildLeft = cost / Math.max(in.buildRate, 1f);
                    firstDone = true;
                }
            }
            if(building != null && (buildLeft -= step) <= 0f){
                switch(building){
                    case spread -> income += in.sourceGain;
                    case hold, outposts -> cover += in.turretKill;
                    case yards -> {
                        if(in.yardGain > 0f) yardRate += in.yardGain;
                        else guardYards += in.guardRate;
                    }
                    case improve -> income += in.improveGain;
                }
                building = null;
            }
            stock = Math.min(stock + income * step, in.cap);
            //units, while there is stock for them
            if(yardRate > 0f && army < in.armyCap){
                float made = Math.min(yardRate * step, stock / Math.max(in.unitCost, 1f));
                made = Math.min(made, in.armyCap - army);
                army += made;
                stock -= made * in.unitCost;
            }
            //units that only hold home, while there is stock for them: they go where the other side's units are
            if(guardYards > 0f && guards < in.armyCap){
                float made = Math.min(Math.min(guardYards * step, stock / Math.max(in.guardCost, 1f)), in.armyCap - guards);
                guards += made;
                stock -= made * in.guardCost;
            }
            //the other side's army: it goes on destroying what it is measured to destroy now, less in proportion to the fire
            //added on the places it comes to, by turrets and by units at home
            float fire = cover + (guards - in.guards) * in.guardDps;
            float loss = in.lossRate * Math.max(in.coverage, 30f) / Math.max(fire, 1f) * step;
            lost += loss;
            if(lost > assets * 0.5f) return horizon * 10f;
            //what is lost brings in nothing any more, and costs as much again to put back
            income = Math.max(income - loss / Math.max(assets, 1f) * income, 0f);
            stock = Math.max(stock - loss, 0f);
            if(army >= needed && needed < Float.MAX_VALUE) return t;
        }
        //with nothing to get ready for, what its base would be worth: what it holds and what it would go on bringing in over
        //as long again, less what it lost, counted in seconds of what it brings in now
        if(needed == Float.MAX_VALUE) return -(stock + income * horizon - lost);
        //not ready in the time looked at: how much longer at the pace it ended on
        float short_ = Math.max(needed - army, 0f);
        float pace = Math.min(yardRate, income / Math.max(in.unitCost, 1f));
        return horizon + (pace > 0f && needed < Float.MAX_VALUE ? short_ / pace : horizon * 3f) + lost * 0.01f;
    }

    /** The plain rule that fills in the look-ahead after the first step. */
    private static Director.Choice rule(Inputs in, float stock, float losing){
        //more than a little of what it has built a minute going down: turrets
        if(losing * 60f > in.assets * 0.01f) return Director.Choice.hold;
        if(in.sourceGain > 0f && stock < in.cap * 0.5f) return Director.Choice.spread;
        if(in.yardGain > 0f) return Director.Choice.yards;
        return in.sourceGain > 0f ? Director.Choice.spread : Director.Choice.improve;
    }

    /** What setting about a choice costs, or -1 when there is nothing of that kind to be done. */
    private static float cost(Inputs in, Director.Choice choice){
        return switch(choice){
            case spread -> in.sourceGain > 0f ? in.sourceCost : -1f;
            case hold, outposts -> in.turretKill > 0f && (in.exposure > 0.5f || in.lossRate > 0.05f) ? in.turretCost : -1f;
            case yards -> in.yardGain > 0f ? in.yardCost : in.guardRate > 0f && (in.exposure > 0.5f || in.lossRate > 0.05f) ? in.guardYardCost : -1f;
            case improve -> in.improveGain > 0f ? in.improveCost : -1f;
        };
    }
}
