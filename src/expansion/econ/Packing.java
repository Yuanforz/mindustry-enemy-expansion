package expansion.econ;

import arc.struct.*;

import java.util.*;

/**
 * Where machines that work the ground go so that none is in another's way: a maximum-weight independent set over every
 * place each could stand, as drill layouts are packed by players and as map labelling and facility siting are solved.
 *
 * Two places are in conflict when they overlap, when one stands on the open ground the other's beam crosses, or when one
 * stands on the other's apron: the row behind a bore or crusher (where the duct that takes what it gives runs, shared by
 * the whole row) or the ring round a floor drill (so every drill in a block of them touches a corridor). Each place is
 * worth what it gives a second, less for being far from the core.
 *
 * The set is found greedily (GWMIN: the place worth most against how many it rules out, again and again), then improved by
 * local search: putting a place in and taking out what it conflicts with when that is worth more, and taking one out to
 * put two in (the (1,2)-swaps of Andrade, Resende and Werneck), with random kicks to get out of local optima (iterated
 * local search), kept when they end better.
 */
public final class Packing{
    private Packing(){}

    public static long timeLimitMs = 1500;

    /** The best set of places that do not conflict. */
    public static List<Field.Site> select(List<Field.Site> all, float[] worth, int width){
        int n = all.size();
        if(n == 0) return new ArrayList<>();
        //which places cover, beam across or keep each tile
        IntMap<IntSeq> covers = new IntMap<>(), reaches = new IntMap<>();
        for(int i = 0; i < n; i++){
            Field.Site s = all.get(i);
            for(int t : s.tiles) covers.get(t, IntSeq::new).add(i);
            for(int t : s.lane) reaches.get(t, IntSeq::new).add(i);
            for(int t : s.apron) reaches.get(t, IntSeq::new).add(i);
        }
        int[][] adj = new int[n][];
        IntSet seen = new IntSet();
        for(int i = 0; i < n; i++){
            Field.Site s = all.get(i);
            seen.clear();
            for(int t : s.tiles){
                add(seen, covers.get(t), i);
                add(seen, reaches.get(t), i);
            }
            for(int t : s.lane) add(seen, covers.get(t), i);
            for(int t : s.apron) add(seen, covers.get(t), i);
            adj[i] = arr(seen);
        }
        //the conflicts are made symmetric: a beam or apron of one over the other rules out both together
        IntSeq[] sym = new IntSeq[n];
        for(int i = 0; i < n; i++) sym[i] = new IntSeq();
        for(int i = 0; i < n; i++) for(int j : adj[i]){ sym[i].add(j); sym[j].add(i); }
        for(int i = 0; i < n; i++){
            IntSet u = new IntSet();
            for(int k = 0; k < sym[i].size; k++) u.add(sym[i].get(k));
            adj[i] = arr(u);
        }

        boolean[] in = greedy(adj, worth);
        int[] tight = new int[n];
        for(int i = 0; i < n; i++) if(in[i]) for(int j : adj[i]) tight[j]++;
        improve(adj, worth, in, tight);
        double best = total(worth, in);
        boolean[] bestIn = in.clone();
        //iterated local search: force a few places in, mend, keep it when better
        Random rand = new Random(7 + Layout.seed);
        long end = System.currentTimeMillis() + timeLimitMs;
        int iter = 0;
        while(System.currentTimeMillis() < end && iter++ < 4000){
            boolean[] trial = bestIn.clone();
            int[] trialTight = new int[n];
            for(int i = 0; i < n; i++) if(trial[i]) for(int j : adj[i]) trialTight[j]++;
            int kicks = 1 + rand.nextInt(3);
            for(int k = 0; k < kicks; k++){
                int v = rand.nextInt(n);
                if(trial[v]) continue;
                force(adj, trial, trialTight, v);
            }
            improve(adj, worth, trial, trialTight);
            double t = total(worth, trial);
            if(t > best + 1e-6){
                best = t;
                bestIn = trial;
            }
        }
        List<Field.Site> out = new ArrayList<>();
        for(int i = 0; i < n; i++) if(bestIn[i]) out.add(all.get(i));
        return out;
    }

    static int[] arr(IntSet set){
        int[] out = new int[set.size];
        int k = 0;
        for(var it = set.iterator(); it.hasNext;) out[k++] = it.next();
        return out;
    }

    static void add(IntSet seen, IntSeq list, int self){
        if(list == null) return;
        for(int k = 0; k < list.size; k++) if(list.get(k) != self) seen.add(list.get(k));
    }

    static double total(float[] worth, boolean[] in){
        double t = 0;
        for(int i = 0; i < in.length; i++) if(in[i]) t += worth[i];
        return t;
    }

    /** GWMIN: the place with the most worth for what it rules out (worth over neighbours left, plus one), until none is left. */
    static boolean[] greedy(int[][] adj, float[] worth){
        int n = adj.length;
        boolean[] in = new boolean[n], gone = new boolean[n];
        int[] deg = new int[n];
        for(int i = 0; i < n; i++) deg[i] = adj[i].length;
        PriorityQueue<double[]> queue = new PriorityQueue<>((a, b) -> Double.compare(b[0], a[0]));
        for(int i = 0; i < n; i++) queue.add(new double[]{worth[i] / (deg[i] + 1.0), i, deg[i]});
        while(!queue.isEmpty()){
            double[] top = queue.poll();
            int v = (int)top[1];
            if(gone[v]) continue;
            if((int)top[2] != deg[v]){
                queue.add(new double[]{worth[v] / (deg[v] + 1.0), v, deg[v]});
                continue;
            }
            in[v] = true;
            gone[v] = true;
            for(int u : adj[v]){
                if(gone[u]) continue;
                gone[u] = true;
                for(int x : adj[u]) if(!gone[x]) deg[x]--;
            }
        }
        return in;
    }

    static void force(int[][] adj, boolean[] in, int[] tight, int v){
        for(int u : adj[v]){
            if(in[u]){
                in[u] = false;
                for(int x : adj[u]) tight[x]--;
            }
        }
        in[v] = true;
        for(int x : adj[v]) tight[x]++;
    }

    /** Local search to a local optimum under both kinds of swap. */
    static void improve(int[][] adj, float[] worth, boolean[] in, int[] tight){
        int n = adj.length;
        boolean changed = true;
        int rounds = 0;
        while(changed && rounds++ < 50){
            changed = false;
            //(w,1): in with one, out with what it conflicts with, when that gains
            for(int v = 0; v < n; v++){
                if(in[v]) continue;
                double lose = 0;
                for(int u : adj[v]) if(in[u]) lose += worth[u];
                if(worth[v] > lose + 1e-6){
                    force(adj, in, tight, v);
                    changed = true;
                }
            }
            //(1,2): out with one, in with two of the places only it kept out, when they are worth more
            for(int u = 0; u < n; u++){
                if(!in[u]) continue;
                IntSeq free = new IntSeq();
                for(int v : adj[u]) if(!in[v] && tight[v] == 1) free.add(v);
                if(free.size < 2) continue;
                int bestA = -1, bestB = -1;
                double bestGain = 1e-6;
                for(int a = 0; a < free.size; a++){
                    int va = free.get(a);
                    for(int b = a + 1; b < free.size; b++){
                        int vb = free.get(b);
                        if(adjacent(adj, va, vb)) continue;
                        double gain = worth[va] + worth[vb] - worth[u];
                        if(gain > bestGain){
                            bestGain = gain;
                            bestA = va;
                            bestB = vb;
                        }
                    }
                }
                if(bestA >= 0){
                    force(adj, in, tight, bestA);
                    force(adj, in, tight, bestB);
                    //and any other the swap left free that fits
                    for(int v = 0; v < free.size; v++){
                        int x = free.get(v);
                        if(!in[x] && tight[x] == 0) force(adj, in, tight, x);
                    }
                    changed = true;
                }
            }
        }
    }

    static boolean adjacent(int[][] adj, int a, int b){
        int[] l = adj[a].length < adj[b].length ? adj[a] : adj[b];
        int other = l == adj[a] ? b : a;
        for(int x : l) if(x == other) return true;
        return false;
    }

    // ---------------------------------------------------------------- fields, and keeping them for later

    /** Places of one kind giving one item that stand together: where a field of drills is. */
    public static final class Group{
        public final Recipe.Ground ground;
        public final int item;
        public final List<Field.Site> sites = new ArrayList<>();
        public float worth, rate, distance;

        Group(Recipe.Ground ground, int item){
            this.ground = ground;
            this.item = item;
        }
    }

    /** The places, put together into fields: places of the same kind and item within a few tiles of each other. */
    public static List<Group> fields(List<Field.Site> sites, Catalog cat, int width){
        int n = sites.size();
        int[] parent = new int[n];
        for(int i = 0; i < n; i++) parent[i] = i;
        IntIntMap owner = new IntIntMap();
        for(int i = 0; i < n; i++) for(int t : sites.get(i).tiles) owner.put(t, i);
        for(int i = 0; i < n; i++){
            Field.Site s = sites.get(i);
            for(int t : s.tiles){
                int x = t % width, y = t / width;
                for(int dx = -3; dx <= 3; dx++){
                    for(int dy = -3; dy <= 3; dy++){
                        int j = owner.get(x + dx + (y + dy) * width, -1);
                        if(j < 0 || j == i) continue;
                        Field.Site o = sites.get(j);
                        if(o.ground == s.ground && o.item == s.item) union(parent, i, j);
                    }
                }
            }
        }
        Map<Integer, Group> groups = new HashMap<>();
        for(int i = 0; i < n; i++){
            Field.Site s = sites.get(i);
            Group g = groups.computeIfAbsent(find(parent, i), k -> new Group(s.ground, s.item));
            g.sites.add(s);
            float r = rate(cat, s);
            g.rate += r;
            g.worth += r / (1f + s.distance / 40f);
        }
        for(Group g : groups.values()){
            float d = Float.MAX_VALUE;
            for(Field.Site s : g.sites) d = Math.min(d, s.distance);
            g.distance = d;
        }
        List<Group> out = new ArrayList<>(groups.values());
        out.sort((a, b) -> Float.compare(b.worth, a.worth));
        return out;
    }

    static int find(int[] p, int i){
        while(p[i] != i){
            p[i] = p[p[i]];
            i = p[i];
        }
        return i;
    }

    static void union(int[] p, int a, int b){
        p[find(p, a)] = find(p, b);
    }

    /** What a place gives a second (an item, or water for a vent), by the first recipe that works that kind of ground. */
    public static float rate(Catalog cat, Field.Site s){
        for(Recipe r : cat.recipes){
            if(r.ground != s.ground) continue;
            if(s.item >= 0) return r.out[s.item] + r.perFactor[s.item] * s.factor;
            return r.powerOut / 60f * s.factor;
        }
        return 0f;
    }

    /**
     * Keeps the best fields for later: for each item, the places the plan does not use in the best fields, until they
     * could give three times what the plan takes from that kind of ground; their tiles and aprons cost a line or a cell
     * that crosses them, so that they are routed round and the field can be built on one day.
     */
    public static int reserve(Grid grid, Catalog cat, List<Field.Site> sites, Set<Field.Site> used, float cost){
        List<Group> groups = fields(sites, cat, grid.w);
        Map<String, Float> want = new HashMap<>(), kept = new HashMap<>();
        for(Field.Site s : used) want.merge(s.ground + "/" + s.item, rate(cat, s), Float::sum);
        int tiles = 0;
        for(Group g : groups){
            String key = g.ground + "/" + g.item;
            float need = Math.max(want.getOrDefault(key, 0f), 0.01f) * 3f;
            //every field that the plan builds on is kept whole; the others until there is enough
            boolean builtOn = false;
            for(Field.Site s : g.sites) if(used.contains(s)) builtOn = true;
            if(!builtOn && (!want.containsKey(key) || kept.getOrDefault(key, 0f) >= need)) continue;
            kept.merge(key, g.rate, Float::sum);
            for(Field.Site s : g.sites){
                if(used.contains(s)) continue;
                for(int t : s.tiles){ grid.kept[t] = Math.max(grid.kept[t], cost); tiles++; }
                for(int t : s.apron) grid.kept[t] = Math.max(grid.kept[t], cost * 0.5f);
            }
        }
        return tiles;
    }
}
