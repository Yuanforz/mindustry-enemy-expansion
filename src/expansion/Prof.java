package expansion;

import arc.struct.*;

/** Adds up where the time for working things out goes. For looking into what is slow; costs next to nothing. */
public class Prof{
    private static final ObjectMap<String, long[]> totals = new ObjectMap<>();

    public static long start(){
        return System.nanoTime();
    }

    public static void end(String what, long started){
        long[] total = totals.get(what, () -> new long[2]);
        total[0] += System.nanoTime() - started;
        total[1]++;
    }

    public static String report(){
        StringBuilder out = new StringBuilder();
        for(var entry : totals) out.append(String.format("%s %.0f ms in %d calls; ", entry.key, entry.value[0] / 1e6, entry.value[1]));
        totals.clear();
        return out.toString();
    }
}
