package xtest;

import arc.struct.*;
import mindustry.*;
import mindustry.ai.*;

import java.lang.reflect.*;

/**
 * The game's path-finding for units given orders (what the squad AI uses) done on the main thread, a step a frame,
 * instead of on a thread of its own that runs thirty times a second of the wall clock. On its own thread, how far it
 * has got when a unit asks depends on how fast the machine runs the game, and two games from the same start part ways
 * at the first fight. This is the loop of that thread (ControlPathfinder.run) done once a frame and to the end each
 * time, as {@link Boot} does for the other path-finder.
 */
public class ControlSync{
    static Field thread, queue, clustersToUpdate, clustersToInnerUpdate, invalidRequests, fields, fieldList, threadPathRequests,
        reqInvalidated, reqDestination, reqCostId, reqTeam, reqOldCache, cacheFrontier, cacheMapKey;
    static Method updateClustersComplete, updateClustersInner, updateFields, recalculatePath, fieldIndexGet;
    static ControlPathfinder held;
    static int frame;

    static void init() throws Exception{
        if(thread != null) return;
        Class<?> type = ControlPathfinder.class, request = Class.forName("mindustry.ai.ControlPathfinder$PathRequest"), cache = Class.forName("mindustry.ai.ControlPathfinder$FieldCache");
        thread = type.getDeclaredField("thread");
        queue = type.getDeclaredField("queue");
        clustersToUpdate = type.getDeclaredField("clustersToUpdate");
        clustersToInnerUpdate = type.getDeclaredField("clustersToInnerUpdate");
        invalidRequests = type.getDeclaredField("invalidRequests");
        fields = type.getDeclaredField("fields");
        fieldList = type.getDeclaredField("fieldList");
        threadPathRequests = type.getDeclaredField("threadPathRequests");
        reqInvalidated = request.getDeclaredField("invalidated");
        reqDestination = request.getDeclaredField("destination");
        reqCostId = request.getDeclaredField("costId");
        reqTeam = request.getDeclaredField("team");
        reqOldCache = request.getDeclaredField("oldCache");
        cacheFrontier = cache.getDeclaredField("frontier");
        cacheMapKey = cache.getDeclaredField("mapKey");
        updateClustersComplete = type.getDeclaredMethod("updateClustersComplete", int.class);
        updateClustersInner = type.getDeclaredMethod("updateClustersInner", int.class);
        updateFields = type.getDeclaredMethod("updateFields", cache, long.class);
        recalculatePath = type.getDeclaredMethod("recalculatePath", request);
        fieldIndexGet = Class.forName("mindustry.gen.FieldIndex").getDeclaredMethod("get", int.class, int.class, int.class);
        for(AccessibleObject member : new AccessibleObject[]{thread, queue, clustersToUpdate, clustersToInnerUpdate, invalidRequests, fields, fieldList, threadPathRequests,
            reqInvalidated, reqDestination, reqCostId, reqTeam, reqOldCache, cacheFrontier, cacheMapKey, updateClustersComplete, updateClustersInner, updateFields, recalculatePath, fieldIndexGet}) member.setAccessible(true);
    }

    /** One round of the path-finding thread's loop, after its thread is stopped. */
    @SuppressWarnings("unchecked")
    public static void step(){
        if(!Boot.syncPath || Vars.controlPath == null || !Vars.state.isPlaying()) return;
        try{
            init();
            ControlPathfinder path = Vars.controlPath;
            if(path != held){
                held = path;
                frame = 0;
            }
            clock(path);
            Thread running = (Thread)thread.get(path);
            if(running != null){
                running.interrupt();
                running.join(2000);
                thread.set(path, null);
            }
            ((arc.util.TaskQueue)queue.get(path)).run();
            IntSet complete = (IntSet)clustersToUpdate.get(path), inner = (IntSet)clustersToInnerUpdate.get(path);
            //(in order: a set's order is that of its numbers, the same every time)
            for(int cluster : sorted(complete)){
                updateClustersComplete.invoke(path, cluster);
                inner.remove(cluster);
            }
            for(int cluster : sorted(inner)) updateClustersInner.invoke(path, cluster);
            inner.clear();
            complete.clear();

            //once a second of the game's, as the thread does once a second of the wall's
            if(frame++ % 60 == 0){
                ObjectSet<Object> invalid = (ObjectSet<Object>)invalidRequests.get(path);
                LongMap<Object> fieldMap = (LongMap<Object>)fields.get(path);
                Seq<Object> requests = (Seq<Object>)threadPathRequests.get(path);
                arc.util.TaskQueue tasks = (arc.util.TaskQueue)queue.get(path);
                Seq<Object> pending = new Seq<>();
                for(Object request : invalid) pending.add(request);
                invalid.clear();
                for(Object request : pending){
                    if(reqInvalidated.getBoolean(request)) continue;
                    long key = (long)fieldIndexGet.invoke(null, reqDestination.getInt(request), reqCostId.getInt(request), reqTeam.getInt(request));
                    Object field = fieldMap.get(key);
                    if(field != null){
                        if(((IntQueue)cacheFrontier.get(field)).isEmpty()){
                            fieldMap.remove(cacheMapKey.getLong(field));
                            Object dead = field;
                            arc.Core.app.post(() -> {
                                try{
                                    ((Seq<Object>)fieldList.get(path)).remove(dead);
                                }catch(Exception e){
                                    throw new RuntimeException(e);
                                }
                            });
                            for(Object other : requests){
                                if(reqDestination.getInt(other) == reqDestination.getInt(request)){
                                    reqOldCache.set(other, field);
                                    if(other != request) tasks.post(() -> recalc(path, other));
                                }
                            }
                            tasks.post(() -> recalc(path, request));
                        }else{
                            //not done yet: looked at again next time
                            invalid.add(request);
                        }
                    }else{
                        tasks.post(() -> recalc(path, request));
                    }
                }
            }
            LongMap<Object> fieldMap = (LongMap<Object>)fields.get(path);
            long[] keys = fieldMap.keys().toSeq().toArray();
            java.util.Arrays.sort(keys);
            for(long key : keys){
                Object cache = fieldMap.get(key);
                if(cache != null) updateFields.invoke(path, cache, Long.MAX_VALUE / 4);
            }
        }catch(Exception e){
            throw new RuntimeException(e);
        }
    }

    static final java.util.IdentityHashMap<Object, long[]> recomputed = new java.util.IdentityHashMap<>();

    /**
     * A unit's way is worked out afresh at most once a second of the wall clock (unless where it is going has moved far):
     * run many times faster than the game is played, that is minutes of the game's time, and how many depends on the
     * machine. Here each request's time is set so that it reads as a second of the game's time.
     */
    @SuppressWarnings("unchecked")
    static void clock(ControlPathfinder path) throws Exception{
        if(unitRequests == null){
            unitRequests = ControlPathfinder.class.getDeclaredField("unitRequests");
            lastRecompute = Class.forName("mindustry.ai.ControlPathfinder$PathRequest").getDeclaredField("lastRecomputeTime");
            unitRequests.setAccessible(true);
            lastRecompute.setAccessible(true);
        }
        long now = System.currentTimeMillis(), tick = (long)Vars.state.tick;
        java.util.IdentityHashMap<Object, long[]> seen = new java.util.IdentityHashMap<>();
        for(Object request : ((ObjectMap<Object, Object>)unitRequests.get(path)).values()){
            long[] mark = recomputed.get(request);
            long value = lastRecompute.getLong(request);
            //set by the game since it was last looked at: worked out afresh just now
            if(mark == null || mark[0] != value) mark = new long[]{0, tick};
            long set = tick - mark[1] < 60 ? now + 60_000 : now - 60_000;
            lastRecompute.setLong(request, set);
            mark[0] = set;
            seen.put(request, mark);
        }
        recomputed.clear();
        recomputed.putAll(seen);
    }

    static Field unitRequests, lastRecompute, physicsWorlds, worldRand;
    static Object seededWorlds;

    /**
     * The physics that pushes units apart has a random number source of its own for each layer, seeded from the clock,
     * which it uses when two units stand exactly on top of each other (as two made by one fabricator do): seeded here
     * from the game's, once a game.
     */
    public static void seedPhysics(long seed){
        try{
            if(physicsWorlds == null){
                physicsWorlds = mindustry.async.PhysicsProcess.class.getDeclaredField("physics");
                worldRand = mindustry.async.PhysicsProcess.PhysicsWorld.class.getDeclaredField("rand");
                physicsWorlds.setAccessible(true);
                worldRand.setAccessible(true);
            }
            for(mindustry.async.AsyncProcess process : Vars.asyncCore.processes){
                if(!(process instanceof mindustry.async.PhysicsProcess)) continue;
                Object[] worlds = (Object[])physicsWorlds.get(process);
                if(worlds == null || worlds == seededWorlds) continue;
                seededWorlds = worlds;
                for(int i = 0; i < worlds.length; i++) ((arc.math.Rand)worldRand.get(worlds[i])).setSeed(seed * 31 + i);
            }
        }catch(Exception e){
            throw new RuntimeException(e);
        }
    }

    static void recalc(ControlPathfinder path, Object request){
        try{
            recalculatePath.invoke(path, request);
        }catch(Exception e){
            throw new RuntimeException(e);
        }
    }

    static int[] sorted(IntSet set){
        int[] out = new IntSet.IntSetIterator(set).toArray().toArray();
        java.util.Arrays.sort(out);
        return out;
    }
}
