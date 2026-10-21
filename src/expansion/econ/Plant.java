package expansion.econ;

import java.util.*;

/**
 * How a set of machines runs once everything has settled: how fast each one works, how much power is met, and what is
 * left over for the core. Everything that is made goes straight to what uses it (an Erekir core gives nothing back); what
 * nothing uses goes to the core if the core keeps it, and is lost otherwise.
 *
 * Shortages are shared out the way the game does it for power: everyone who wants a thing that is short gets the same
 * share of what they want. Lines are assumed to be laid so that every maker reaches every user of what it makes.
 */
public final class Plant{
    public final Catalog cat;
    /** How fast each machine works, 0..1, by its index in the list solved. */
    public float[] eff = new float[0];
    /** How much of what each boosted machine's booster wants it gets. */
    public float[] boost = new float[0];
    /** Per second: made, used, and left over for the core, by {@link Res#id}. Power is the last entry of supply and used. */
    public float[] supply, used, toCore;
    public float powerMet = 1f;
    /** For each thing, the share of it that goes past the machines to the core regardless (see Catalog.coreShare); null for none. */
    public float[] share;

    private final int power;

    public Plant(Catalog cat){
        this.cat = cat;
        power = cat.size();
        supply = new float[power + 1];
        used = new float[power + 1];
        toCore = new float[power];
    }

    /**
     * @param active for each machine, whether it can work at all just now (a refabricator with no unit in it can't); null for all
     * @param unitsFlow whether units go from factory to refabricator as a flow (the steady state) instead of one by one
     */
    public void solve(List<Machine> machines, boolean[] active, boolean unitsFlow){
        //what power would be wanted if there were enough: the share that is met is what the game shows as satisfaction
        solve(machines, active, unitsFlow, true);
        float wanted = used[power];
        solve(machines, active, unitsFlow, false);
        powerWanted = wanted;
        powerMet = wanted <= 0f ? 1f : Math.min(1f, supply[power] / wanted);
    }

    /** Power a second the machines would use with all they want of it. */
    public float powerWanted;

    private void solve(List<Machine> machines, boolean[] active, boolean unitsFlow, boolean freePower){
        int m = machines.size();
        eff = new float[m];
        boost = new float[m];
        float[] cap = new float[m];
        //what each machine takes and gives, kept short: only the things it has to do with. Power is one more thing
        int[][] ins = new int[m][], outs = new int[m][];
        float[][] inAmt = new float[m][], outAmt = new float[m][];
        int[] boostRes = new int[m];
        for(int i = 0; i < m; i++){
            Machine mc = machines.get(i);
            Recipe r = mc.recipe;
            cap[i] = (active == null || active[i]) && !mc.unfed ? 1f : 0f;
            eff[i] = cap[i];
            int ni = 0, no = 0;
            int[] ti = new int[power + 2], to = new int[power + 2];
            float[] ai = new float[power + 2], ao = new float[power + 2];
            for(int k = 0; k < power; k++){
                if(r.in[k] > 0f){ ti[ni] = k; ai[ni++] = r.in[k]; }
                float o = mc.out(k);
                if(o > 0f){ to[no] = k; ao[no++] = o; }
            }
            if(r.powerUse > 0f && !freePower){ ti[ni] = power; ai[ni++] = r.powerUse; }
            if(mc.powerOut() > 0f){ to[no] = power; ao[no++] = mc.powerOut(); }
            if(unitsFlow && r.makesUnits()){
                to[no] = r.unitOut; ao[no++] = 1f / r.unitSeconds;
                if(r.unitIn >= 0){ ti[ni] = r.unitIn; ai[ni++] = 1f / r.unitSeconds; }
            }
            ins[i] = Arrays.copyOf(ti, ni);
            inAmt[i] = Arrays.copyOf(ai, ni);
            outs[i] = Arrays.copyOf(to, no);
            outAmt[i] = Arrays.copyOf(ao, no);
            boostRes[i] = mc.boosted ? r.boostRes : -1;
        }

        float[] s = supply, u = used, wantBoost = new float[power];
        for(int round = 0; round < 300; round++){
            add(machines, m, ins, inAmt, outs, outAmt, boostRes, s, u, false);
            //boosters get what is left of their liquid once the machines that must have it are served
            Arrays.fill(wantBoost, 0f);
            for(int i = 0; i < m; i++) if(boostRes[i] >= 0) wantBoost[boostRes[i]] += eff[i] * machines.get(i).recipe.boostUse;

            float change = 0f;
            boolean last = round >= 250;
            for(int i = 0; i < m; i++){
                float limit = 1f;
                int[] in = ins[i];
                for(int j = 0; j < in.length; j++){
                    int k = in[j];
                    float have = avail(k, s);
                    if(u[k] > have + 1e-6f) limit = Math.min(limit, have / u[k]);
                }
                //held back by what it is short of; with nothing short it goes back up towards what it could do
                float target = limit < 1f ? eff[i] * limit : Math.min(cap[i], eff[i] + 0.3f * (cap[i] - eff[i]) + 0.01f);
                float next = last ? Math.min(eff[i], target) : 0.5f * eff[i] + 0.5f * target;
                if(next < 0f) next = 0f;
                change = Math.max(change, Math.abs(next - eff[i]));
                eff[i] = next;
                int b = boostRes[i];
                if(b >= 0) boost[i] = wantBoost[b] <= 0f ? 0f : Math.min(1f, Math.max(0f, s[b] - u[b]) / wantBoost[b]);
            }
            if(change < 1e-5f && round > 10){
                if(feasible(s, u)) break;
                if(round < 250) round = 249;
            }
        }

        add(machines, m, ins, inAmt, outs, outAmt, boostRes, s, u, true);
        if(freePower){
            u[power] = 0f;
            for(int i = 0; i < m; i++) u[power] += eff[i] * machines.get(i).recipe.powerUse;
        }
        for(int k = 0; k < power; k++) toCore[k] = cat.get(k).storable ? Math.max(0f, s[k] - u[k] - (cat.drain == null ? 0f : cat.drain[k])) : 0f;
    }

    private boolean feasible(float[] s, float[] u){
        for(int k = 0; k <= power; k++) if(u[k] > avail(k, s) * 1.0001f + 1e-5f) return false;
        return true;
    }

    /** What of a thing the machines can have: all that is made, but for the share that goes past them to the core. */
    private float avail(int k, float[] s){
        if(k >= power || share == null) return s[k];
        return s[k] * (1f - share[k]);
    }

    private void add(List<Machine> machines, int m, int[][] ins, float[][] inAmt, int[][] outs, float[][] outAmt, int[] boostRes, float[] s, float[] u, boolean countBoost){
        Arrays.fill(s, 0f);
        Arrays.fill(u, 0f);
        for(int i = 0; i < m; i++){
            Machine mc = machines.get(i);
            float e = eff[i];
            float mult = boostRes[i] >= 0 ? 1f + (mc.recipe.boostMultiplier - 1f) * boost[i] : 1f;
            int[] in = ins[i], out = outs[i];
            for(int j = 0; j < out.length; j++) s[out[j]] += e * outAmt[i][j] * (out[j] == mc.item ? mult : 1f);
            for(int j = 0; j < in.length; j++) u[in[j]] += e * inAmt[i][j];
            if(countBoost && boostRes[i] >= 0) u[boostRes[i]] += e * mc.recipe.boostUse * boost[i];
        }
    }

    /** Units a second that come out of the machines making this unit, as last solved. */
    public float unitRate(List<Machine> machines, int unit){
        float rate = 0f;
        for(int i = 0; i < machines.size(); i++){
            Recipe r = machines.get(i).recipe;
            if(r.unitOut == unit) rate += eff[i] / r.unitSeconds;
        }
        return rate;
    }
}
