package expansion.econ;

import java.util.*;

/** Where the time for planning goes (this thread's own running time), phase by phase: for looking into what is slow (-Dxp.time=true prints it per plan). */
public final class Took{
    static final Map<String, long[]> totals = new LinkedHashMap<>();

    static final java.lang.management.ThreadMXBean threads = java.lang.management.ManagementFactory.getThreadMXBean();

    /** The time this thread has run (what others running on the machine take is not counted). */
    public static long start(){
        return threads.getCurrentThreadCpuTime();
    }

    public static synchronized void end(String what, long started){
        long[] t = totals.computeIfAbsent(what, k -> new long[2]);
        t[0] += threads.getCurrentThreadCpuTime() - started;
        t[1]++;
    }

    /** Whether counts are kept (-Dxp.time=true). */
    public static final boolean on = Boolean.getBoolean("xp.time");
    static final Map<String, long[]> counts = new LinkedHashMap<>();

    public static synchronized void count(String what, long n){
        counts.computeIfAbsent(what, k -> new long[1])[0] += n;
    }

    /** What was added up since the last report, and clears it. */
    public static synchronized String report(){
        StringBuilder out = new StringBuilder();
        for(var e : totals.entrySet()) out.append(String.format("    %-28s %8.1f s in %d\n", e.getKey(), e.getValue()[0] / 1e9, e.getValue()[1]));
        totals.clear();
        for(var e : counts.entrySet()) out.append(String.format("    %-28s %10d\n", e.getKey(), e.getValue()[0]));
        counts.clear();
        return out.toString();
    }
}
