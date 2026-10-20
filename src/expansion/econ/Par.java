package expansion.econ;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Work that splits into pieces that do not touch one another (orders played through the model, plans weighed), done on
 * a pool of threads, the results in the order of the pieces: what comes out is the same as one at a time, only sooner.
 * A work-stealing pool, so a piece may itself split (a plan weighed on it orders its machines on it). -Dxp.threads.
 */
public final class Par{
    private Par(){}

    public static final int threads = Integer.getInteger("xp.threads", 8);
    static final ForkJoinPool pool = new ForkJoinPool(Math.max(1, threads));

    /** f of each, in order; at once on the pool when there are two or more and threads to spare. */
    public static <T, R> List<R> map(List<T> items, Function<T, R> f){
        if(items.size() < 2 || threads <= 1){
            List<R> out = new ArrayList<>(items.size());
            for(T t : items) out.add(f.apply(t));
            return out;
        }
        List<ForkJoinTask<R>> tasks = new ArrayList<>(items.size());
        for(T t : items) tasks.add(ForkJoinTask.adapt(() -> f.apply(t)));
        //(from a thread of the pool, forked into it; from outside, handed to it)
        if(ForkJoinTask.inForkJoinPool()) for(ForkJoinTask<R> task : tasks) task.fork();
        else for(ForkJoinTask<R> task : tasks) pool.execute(task);
        List<R> out = new ArrayList<>(items.size());
        for(ForkJoinTask<R> task : tasks) out.add(task.join());
        return out;
    }
}
