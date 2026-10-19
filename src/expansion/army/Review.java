package expansion.army;

import arc.struct.*;
import mindustry.type.*;

/**
 * A4, review: what each job sent out expected to take (the threat field's damage a second where its units stood, over
 * the time they were out) set against what they took, kind of unit by kind of unit. The threat field's figure for a kind
 * is multiplied by how far out it has been, as a moving average, so that the estimate is set by what happens on the
 * map in front of it and not by numbers chosen for one map.
 */
public class Review{
    final ObjectFloatMap<UnitType> factor = new ObjectFloatMap<>();
    public int jobs;

    /** How much more (or less) than the threat field says a kind of unit is hurt; 1 until something is known. */
    public float factor(UnitType type){
        return factor.get(type, 1f);
    }

    /** One job's units: the damage a second they were expected to take, and what they took. */
    public void add(UnitType type, float expected, float taken){
        //a job that expected nothing tells nothing of the scale: it is only a floor for the next
        float ratio = expected <= 1f ? (taken <= 1f ? 1f : 2f) : taken / expected;
        ratio = Math.max(0.25f, Math.min(ratio, 4f));
        float now = factor(type);
        factor.put(type, now * 0.8f + ratio * 0.2f);
        jobs++;
    }
}
