package expansion;

/**
 * What the planning measures its own time by. In a game it is the clock on the wall: a round of
 * planning may take so many milliseconds. For tests that are to come out the same each time it is run,
 * it can be a count of the steps the searches take instead, so that how much gets worked out in a
 * round does not hang on how busy the machine is.
 */
public final class Clock{
    /** Whether time is counted in steps of work rather than read off the wall. */
    public static boolean virtual;
    /** What one step of a search counts for, in nanoseconds, when time is counted in steps. */
    public static final long stepNanos = 1000L;
    private static long steps;

    private Clock(){
    }

    /** Now, in nanoseconds. */
    public static long now(){
        return virtual ? steps * stepNanos : System.nanoTime();
    }

    /** Counts so many steps of work done. */
    public static void spend(int count){
        steps += count;
    }
}
