package expansion;

import arc.struct.*;

/**
 * Keeps account of how what was set about turned out, for each kind of task in each zone, so that it can
 * be seen when the same thing keeps happening to no end: building something in a zone and losing it,
 * and building it again; laying something out that never gets anywhere; sending the army at something and
 * being thrown back, again and again. Such a loop is a sign that the way it goes about that thing there is
 * wrong, and the way has to change.
 */
public class Ledger{
    /** How long what happened counts for. */
    public static final float memory = 60f * 60f * 10f;
    /** How many times a thing has to go wrong within that time to be a loop. */
    public static final int loopAt = 2;

    static class Entry{
        final FloatSeq laid = new FloatSeq(), lost = new FloatSeq(), failed = new FloatSeq(), repulsed = new FloatSeq();
    }

    private final LongMap<Entry> entries = new LongMap<>();

    public void reset(){
        entries.clear();
    }

    private Entry entry(long key){
        Entry entry = entries.get(key);
        if(entry == null) entries.put(key, entry = new Entry());
        return entry;
    }

    private static int recent(FloatSeq times, float clock){
        int count = 0;
        for(int i = times.size - 1; i >= 0; i--){
            if(clock - times.get(i) > memory){
                times.removeIndex(i);
            }else{
                count++;
            }
        }
        return count;
    }

    /** Something was laid out for a task. */
    public void laid(long key, float clock){
        entry(key).laid.add(clock);
    }

    /** Something built for a task was destroyed after it stood. */
    public void lost(long key, float clock){
        //one raid that takes down a whole row of something is one loss, not one for every block of it
        FloatSeq lost = entry(key).lost;
        if(lost.size > 0 && clock - lost.peek() < 60f * 30f) return;
        lost.add(clock);
    }

    /** Working out how to do a task came to nothing. */
    public void failed(long key, float clock){
        entry(key).failed.add(clock);
    }

    /** The army was thrown back going for a task's target. */
    public void repulsed(long key, float clock){
        entry(key).repulsed.add(clock);
    }

    /**
     * Whether a task has been going round in a loop lately: what was built for it was lost again and again,
     * or the army was thrown back again and again.
     */
    public boolean looping(long key, float clock){
        Entry entry = entries.get(key);
        if(entry == null) return false;
        return recent(entry.lost, clock) >= loopAt || recent(entry.repulsed, clock) >= loopAt;
    }

    /** What has happened to a task lately, for looking into it. */
    public String describe(long key, float clock){
        Entry entry = entries.get(key);
        if(entry == null) return "nothing yet";
        return "laid " + recent(entry.laid, clock) + ", lost " + recent(entry.lost, clock) + ", failed " + recent(entry.failed, clock) + ", thrown back " + recent(entry.repulsed, clock);
    }

    /** Every loop there is now, as keys. */
    public LongSeq loops(float clock){
        LongSeq out = new LongSeq();
        for(var entry : entries){
            if(looping(entry.key, clock)) out.add(entry.key);
        }
        return out;
    }
}
