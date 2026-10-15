package expansion;

import arc.struct.*;
import arc.util.*;
import mindustry.type.*;

/**
 * The two queues: what the builders are to do, and what the army is to do. Tasks are asked for again and
 * again as long as the reason for them lasts; one that has not been asked for in a while is dropped.
 * Asking for a task that is on the queue already only brings it up to date.
 */
public class Tasks{
    /** How long a task stays on its queue without being asked for again. */
    public static final float stale = 60f * 20f;

    public final Seq<Task> builder = new Seq<>(), army = new Seq<>();

    public void reset(){
        builder.clear();
        army.clear();
    }

    /** Puts a task on its queue, or brings the one there up to date. */
    public Task post(Task.Kind kind, int zone, @Nullable Item item, float value, float cost, String why, float clock){
        Seq<Task> queue = kind.army() ? army : builder;
        long key = Task.key(kind, zone, item);
        Task task = queue.find(other -> other.key() == key);
        if(task == null) queue.add(task = new Task(kind, zone, item));
        task.value = value;
        task.cost = cost;
        task.why = why;
        task.asked = clock;
        return task;
    }

    /** Drops what has not been asked for lately, and puts the rest in order: the most worth for what it costs first. */
    public void sort(float clock){
        builder.removeAll(task -> clock - task.asked > stale);
        army.removeAll(task -> clock - task.asked > stale);
        builder.sort(task -> -task.value / Math.max(task.cost, 1f));
        army.sort(task -> -task.value);
    }

    public @Nullable Task find(Task.Kind kind, int zone){
        Seq<Task> queue = kind.army() ? army : builder;
        return queue.find(task -> task.kind == kind && task.zone == zone);
    }

    public String describe(){
        return "builders " + builder.toString(", ") + "\n  army " + army.toString(", ");
    }
}
