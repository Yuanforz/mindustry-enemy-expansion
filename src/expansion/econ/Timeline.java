package expansion.econ;

import java.util.*;

/**
 * Plays a build order forward in time, from one event to the next: a machine finished, a unit made, enough in the core
 * for the next thing. Between events everything runs at a steady rate, so long stretches cost one step.
 *
 * The core unit does one thing at a time. It builds the next machine of the order once the core can pay for all of it
 * (building itself takes seconds; what takes time is getting the items); until then it digs, by hand, whatever of the next
 * machine's cost is furthest from being met. It cannot dig and build at once (the game's rule).
 */
public final class Timeline{
    public final Catalog cat;
    public final Field field;
    public float t;
    public final float[] stock;
    public final List<Machine> built;
    /** What is to be built, in order, and how far along the order it has got. */
    public List<Machine> order;
    public int next;
    /** The machine being built (-1 for none) and seconds of it left; where the core unit is. */
    public int building = -1;
    public float buildLeft;
    public float bx, by;
    /** The item being dug by hand, -1 for none. */
    public int digging = -1;
    /** Unit making: for each machine built, how far along its unit is, whether a refabricator has a unit in it, whether a factory's finished unit is waiting. */
    public float[] progress = new float[0];
    public boolean[] holding = new boolean[0], waiting = new boolean[0];
    /** For each unit factory built, the index of the refabricator it hands its units to, -1 for the army. */
    public int[] feeds = new int[0];
    /**
     * For each unit maker built, when it will have its first batch of items in: a factory or refabricator only starts
     * on a unit once it holds all that unit takes (the game's rule), so a new one waits for its first batch to come in.
     * NaN until that time is worked out.
     */
    public float[] ready = new float[0];
    /**
     * What of each thing the machines filling their first batch take that would have gone to the core: a dock gives the
     * machine all it will take first, so while it fills the core gets that much less.
     */
    float[] draw;
    /** Until when each machine still draws to fill its buffer (it holds two batches: the game's unit makers do). */
    float[] drawUntil = new float[0];
    /** How many batches a machine draws for before the core gets that thing again (for testing the model: xp.draw). */
    public static float drawBatches = Float.parseFloat(System.getProperty("xp.draw", "2"));
    /** For each unit kind: how many were made, and when the first was. */
    public final float[] made, first;
    /** When each machine of the order was begun and finished, by its place in the order. */
    public float[] begun, finished;

    public final Plant plant;
    boolean dirty = true;

    public Timeline(Catalog cat, Field field, List<Machine> order){
        this.cat = cat;
        this.field = field;
        stock = field.stock.clone();
        built = new ArrayList<>(field.existing);
        this.order = order;
        bx = field.coreX;
        by = field.coreY;
        made = cat.zeros();
        first = cat.zeros();
        Arrays.fill(first, Float.POSITIVE_INFINITY);
        begun = new float[order.size()];
        finished = new float[order.size()];
        Arrays.fill(begun, Float.NaN);
        Arrays.fill(finished, Float.NaN);
        plant = new Plant(cat);
        grow();
        link();
    }

    private Timeline(Timeline o){
        cat = o.cat;
        field = o.field;
        t = o.t;
        stock = o.stock.clone();
        built = new ArrayList<>(o.built);
        order = o.order;
        next = o.next;
        building = o.building;
        buildLeft = o.buildLeft;
        bx = o.bx;
        by = o.by;
        digging = o.digging;
        progress = o.progress.clone();
        holding = o.holding.clone();
        waiting = o.waiting.clone();
        feeds = o.feeds.clone();
        ready = o.ready.clone();
        drawUntil = o.drawUntil.clone();
        made = o.made.clone();
        first = o.first.clone();
        goal = o.goal;
        begun = o.begun.clone();
        finished = o.finished.clone();
        plant = new Plant(cat);
        dirty = true;
    }

    public Timeline copy(){
        return new Timeline(this);
    }

    /** Puts a different order in place from where this one has got to: what is done stays done. */
    public void reorder(List<Machine> rest){
        List<Machine> now = new ArrayList<>(order.subList(0, next));
        now.addAll(rest);
        float[] b = Arrays.copyOf(begun, now.size()), f = Arrays.copyOf(finished, now.size());
        for(int i = next; i < now.size(); i++){ b[i] = Float.NaN; f[i] = Float.NaN; }
        order = now;
        begun = b;
        finished = f;
    }

    public boolean done(){
        return next >= order.size() && building < 0;
    }

    /** Runs until the order is all built and the given unit has come out once, or the time is up. */
    public void runToGoal(int unit, float limit){
        goal = unit;
        while(t < limit && !(done() && made[unit] >= goalAmount(unit))){
            if(!step(limit)) break;
        }
    }

    /**
     * What is aimed at: a unit (the first of it), or an item, of which {@link #itemGoal} must have come to the core from
     * the plant (what is dug by hand does not count). -1 for none.
     */
    public int goal = -1;
    public static float itemGoal = Float.parseFloat(System.getProperty("xp.itemgoal", "100"));

    public float goalAmount(int res){
        return cat.get(res).kind == Res.Kind.unit ? 1f : itemGoal;
    }

    /** Runs until the core unit has started on the machine at this place in the order, or the time is up. */
    public void runToStart(int index, float limit){
        while(t < limit && next <= index){
            if(!step(limit)) break;
        }
    }

    public void runUntil(float time){
        while(t < time){
            if(!step(time)) break;
        }
    }

    /** One event forward. False when nothing will ever happen again. */
    public boolean step(float limit){
        if(dirty) settle();
        //what the core unit does
        float seenAt = 0f;
        if(building < 0 && next < order.size()) seenAt = seenAt(order.get(next));
        if(building < 0 && next < order.size()){
            Machine m = order.get(next);
            if(affordable(m) && seenAt <= t){
                for(int k = 0; k < m.cost.length; k++) stock[k] -= m.cost[k];
                float fly = Math.max(0f, (float)Math.hypot(m.x - bx, m.y - by) - cat.buildRange) / cat.flySpeed;
                building = next;
                begun[next] = t;
                buildLeft = fly + (Float.isNaN(m.buildSeconds) ? cat.buildSeconds(m.recipe) : m.buildSeconds);
                bx = m.x;
                by = m.y;
                next++;
                digging = -1;
            }else{
                digging = toDig(m);
            }
        }else if(building < 0){
            digging = toDig(null);
        }

        float[] income = plant.toCore.clone();
        if(draw != null) for(int k = 0; k < income.length; k++) income[k] = Math.max(0f, income[k] - draw[k]);
        if(digging >= 0) income[digging] += field.handRate(cat, digging);

        //the next thing to happen
        float dt = limit - t;
        if(building >= 0) dt = Math.min(dt, buildLeft);
        else if(next < order.size()){
            float wait = Math.max(untilAffordable(order.get(next), income), seenAt - t);
            dt = Math.min(dt, wait);
            //the item being dug may be met before the rest: then it is time to dig something else
            if(digging >= 0){
                Machine m = order.get(next);
                float short_ = m.cost[digging] - stock[digging];
                if(short_ > 0f && income[digging] > 0f) dt = Math.min(dt, short_ / income[digging]);
            }
        }
        for(int i = 0; i < built.size(); i++){
            Recipe r = built.get(i).recipe;
            if(!r.makesUnits()) continue;
            float e = plant.eff[i];
            if(Float.isNaN(ready[i])) continue;
            if(drawUntil[i] > t) dt = Math.min(dt, drawUntil[i] - t);
            if(ready[i] > t){
                dt = Math.min(dt, ready[i] - t);
                continue;
            }
            if(e > 1e-6f && !waiting[i]) dt = Math.min(dt, (1f - progress[i]) * r.unitSeconds / e);
        }
        //an item aimed at: the moment enough of it has come in
        if(goal >= 0 && cat.get(goal).kind == Res.Kind.item && made[goal] < itemGoal && plant.toCore[goal] > 1e-6f){
            dt = Math.min(dt, (itemGoal - made[goal]) / plant.toCore[goal]);
        }
        if(Float.isInfinite(dt) || dt < 0f) return false;
        dt = Math.max(dt, 0f);

        //everything moves on by that much
        t += dt;
        //(what the plant gives the core of each item, counted as made)
        for(int k = 0; k < stock.length; k++){
            if(cat.get(k).kind != Res.Kind.item || plant.toCore[k] <= 0f) continue;
            made[k] += plant.toCore[k] * dt;
            if(Float.isInfinite(first[k]) && made[k] >= itemGoal - 1e-3f) first[k] = t;
        }
        for(int k = 0; k < stock.length; k++){
            stock[k] = Math.min(cat.coreCap, stock[k] + income[k] * dt);
        }
        boolean changed = false;
        //a machine whose first batch is in stops drawing what the core would have had
        for(int i = 0; i < built.size(); i++){
            if(!Float.isNaN(ready[i]) && ready[i] > t - dt + 1e-6f && ready[i] <= t + 1e-6f) changed = true;
            if(drawUntil[i] > t - dt + 1e-6f && drawUntil[i] <= t + 1e-6f) changed = true;
        }
        for(int i = 0; i < built.size(); i++){
            Recipe r = built.get(i).recipe;
            if(!r.makesUnits() || waiting[i] || Float.isNaN(ready[i]) || ready[i] > t - dt + 1e-6f) continue;
            progress[i] += plant.eff[i] * dt / r.unitSeconds;
            if(progress[i] >= 1f - 1e-5f){
                changed |= unitDone(i);
            }
        }
        if(building >= 0){
            buildLeft -= dt;
            if(buildLeft <= 1e-6f){
                Machine m = order.get(building);
                built.add(m);
                finished[building] = t;
                building = -1;
                grow();
                link();
                changed = true;
            }
        }
        if(changed) dirty = true;
        return true;
    }

    /** A unit is finished at machine i: it goes on to the refabricator the factory feeds, or to the army. True when what can work has changed. */
    private boolean unitDone(int i){
        Machine m = built.get(i);
        Recipe r = m.recipe;
        if(feeds[i] >= 0){
            int into = feeds[i];
            if(holding[into]){
                //the refabricator is busy: the unit waits in the factory, which stops
                waiting[i] = true;
                progress[i] = 1f;
                return true;
            }
            holding[into] = true;
            progress[i] = 0f;
            return true;
        }
        progress[i] = 0f;
        made[r.unitOut]++;
        if(Float.isInfinite(first[r.unitOut])) first[r.unitOut] = t;
        if(r.unitIn >= 0){
            holding[i] = false;
            //a factory with a unit waiting for this refabricator hands it over
            for(int j = 0; j < built.size(); j++){
                if(waiting[j] && feeds[j] == i){
                    waiting[j] = false;
                    progress[j] = 0f;
                    holding[i] = true;
                    break;
                }
            }
        }
        return true;
    }

    private void settle(){
        boolean[] active = new boolean[built.size()];
        for(int i = 0; i < built.size(); i++){
            Recipe r = built.get(i).recipe;
            active[i] = !(r.unitIn >= 0 && !holding[i]) && !waiting[i];
        }
        //while something still to be built costs a thing, the line that takes it on to the core is there (without
        //underflow ducts a dock gives the core as much as the machine); once nothing does, the line is cut
        if(cat.coreShare != null){
            float[] share = new float[cat.size()];
            for(int k = 0; k < share.length; k++){
                if(cat.coreShare[k] <= 0f) continue;
                for(int i = Math.max(0, building >= 0 ? building : next); i < order.size(); i++){
                    if(order.get(i).cost[k] > 0){
                        share[k] = cat.coreShare[k];
                        break;
                    }
                }
            }
            plant.share = share;
        }
        plant.solve(built, active, false);
        dirty = false;
        //the first batch of each machine that makes units comes in at what the plant gives it running plus what would have
        //gone on to the core: a dock hands the machine all it will take, and it takes until it holds two batches. While it
        //draws, the core gets none of that. One machine draws at a time from what goes to the core: the next starts once
        //the first is full
        float[] free = plant.toCore.clone();
        draw = new float[cat.size()];
        for(int pass = 0; pass < 2; pass++){
            for(int i = 0; i < built.size(); i++){
                Recipe r = built.get(i).recipe;
                if(!r.makesUnits()) continue;
                float share = built.get(i).draw;
                if(pass == 0 && drawUntil[i] > t + 1e-6f){
                    for(int k = 0; k < r.in.length; k++){
                        if(r.in[k] <= 0f || cat.get(k).kind != Res.Kind.item) continue;
                        draw[k] += free[k] * share;
                        free[k] -= free[k] * share;
                    }
                }
                if(pass == 1 && Float.isNaN(ready[i])){
                    float fill = 0f;
                    for(int k = 0; k < r.in.length; k++){
                        if(r.in[k] <= 0f || cat.get(k).kind != Res.Kind.item) continue;
                        float batch = r.in[k] * r.unitSeconds, rate = r.in[k] * (active[i] ? plant.eff[i] : 0f) + free[k] * share;
                        fill = Math.max(fill, rate <= 1e-6f ? Float.POSITIVE_INFINITY : batch / rate);
                    }
                    if(!Float.isInfinite(fill)){
                        ready[i] = t + fill;
                        drawUntil[i] = t + drawBatches * fill;
                        if(drawBatches > 0f) for(int k = 0; k < r.in.length; k++){
                            if(r.in[k] <= 0f || cat.get(k).kind != Res.Kind.item) continue;
                            draw[k] += free[k] * share;
                            free[k] -= free[k] * share;
                        }
                    }
                }
            }
        }
    }

    /** How the built machines would run with units going through: what the plant gives once it has settled. */
    public Plant steady(){
        Plant p = new Plant(cat);
        p.solve(built, null, true);
        return p;
    }

    private void grow(){
        int n = built.size();
        if(progress.length < n){
            progress = Arrays.copyOf(progress, n);
            holding = Arrays.copyOf(holding, n);
            waiting = Arrays.copyOf(waiting, n);
            int old = feeds.length;
            feeds = Arrays.copyOf(feeds, n);
            Arrays.fill(feeds, old, n, -1);
            ready = Arrays.copyOf(ready, n);
            drawUntil = Arrays.copyOf(drawUntil, n);
            //what stood from the start has its items in already
            for(int i = old; i < n; i++) ready[i] = built.get(i).existing ? 0f : Float.NaN;
        }
    }

    /** Each unit factory hands its units to a refabricator that takes them, if there is one not already fed. */
    private void link(){
        boolean[] fed = new boolean[built.size()];
        for(int f : feeds) if(f >= 0) fed[f] = true;
        for(int i = 0; i < built.size(); i++){
            Machine m = built.get(i);
            if(!m.recipe.makesUnits() || feeds[i] >= 0) continue;
            for(int j = 0; j < built.size(); j++){
                if(!fed[j] && built.get(j).recipe.unitIn == m.recipe.unitOut){
                    feeds[i] = j;
                    fed[j] = true;
                    break;
                }
            }
        }
    }

    /**
     * When a machine's place will have been seen: now, if it was seen from the start; else once the first unit made has
     * gone there from the core (the core unit sees nothing). Infinite while there is no unit yet.
     */
    float seenAt(Machine m){
        if(!m.unseen) return 0f;
        float best = Float.POSITIVE_INFINITY;
        for(var e : cat.unitSpeed.entrySet()){
            float at = first[e.getKey()];
            if(!Float.isInfinite(at) && e.getValue() > 0f) best = Math.min(best, at + m.distance / e.getValue());
        }
        return best;
    }

    boolean affordable(Machine m){
        for(int k = 0; k < m.cost.length; k++) if(stock[k] + 1e-4f < m.cost[k]) return false;
        return true;
    }

    float untilAffordable(Machine m, float[] income){
        float wait = 0f;
        for(int k = 0; k < m.cost.length; k++){
            float short_ = m.cost[k] - stock[k];
            if(short_ <= 1e-4f) continue;
            if(m.cost[k] > cat.coreCap || income[k] <= 0f) return Float.POSITIVE_INFINITY;
            wait = Math.max(wait, short_ / income[k]);
        }
        return wait;
    }

    /**
     * What to dig while waiting for a machine: of what it still needs that can be dug, the one that would take longest
     * to come in otherwise. With nothing of it to dig (it waits on silicon, say), what the rest of the order needs most.
     */
    int toDig(Machine m){
        int best = -1;
        float worst = -1f;
        if(m != null){
            for(int k = 0; k < m.cost.length; k++){
                float short_ = m.cost[k] - stock[k];
                if(short_ <= 1e-4f || field.handRate(cat, k) <= 0f) continue;
                float rest = plant.toCore[k];
                float time = short_ / (rest + field.handRate(cat, k));
                if(time > worst){
                    worst = time;
                    best = k;
                }
            }
            if(best >= 0) return best;
        }
        //looking further on
        float[] need = cat.zeros();
        for(int i = next; i < order.size(); i++){
            int[] c = order.get(i).cost;
            for(int k = 0; k < c.length; k++) need[k] += c[k];
        }
        for(int k = 0; k < need.length; k++){
            if(field.handRate(cat, k) <= 0f || stock[k] >= cat.coreCap - 1f) continue;
            float short_ = need[k] - stock[k] - plant.toCore[k] * 60f;
            if(short_ > worst){
                worst = short_;
                best = k;
            }
        }
        return best;
    }
}
