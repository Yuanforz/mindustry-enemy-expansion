package expansion.econ;

/** One machine in the plan or in the plant: what it is, where it stands, and what that place gives it. */
public final class Machine{
    public final Recipe recipe;
    /** For machines on the ground, the item their place gives; -1 otherwise. */
    public final int item;
    /** How much the place gives (ore tiles hit, points of sand, ore tiles under it; 1 for a full vent or no ground at all). */
    public final float factor;
    /** Where it stands, in tiles, and how far that is from the core. */
    public final int x, y;
    public final float distance;
    /** Items it costs: the machine and the line that joins it to the rest. */
    public final int[] cost;
    /** Whether its booster liquid is led to it. */
    public final boolean boosted;
    /** Whether it stood there before anything was planned (part of the base the map comes with). */
    public final boolean existing;
    /** Which way it faces, for machines that face something (drills face their wall). */
    public int rotation;
    /** Whether its place is on ground not yet seen: it can only be begun once a unit has been there. */
    public boolean unseen;
    /** How long its job takes to build once laid out (the machine, its lines and nodes), in seconds; NaN until then. */
    public float buildSeconds = Float.NaN;
    /**
     * For a machine that makes units: the share of what would go on to the core that it takes while it fills its buffer.
     * All of it behind an underflow dock (it is served first), half behind a router, a share when a maker it touches
     * hands out by turns. Known once laid out; a middle guess before.
     */
    public float draw = 0.5f;
    /** Whether it is a machine of the map's that stood idle, taken up by the plan: it stands already, only its lines are built. */
    public boolean revived;
    /**
     * Whether no line could be laid from it to the core, so the core unit carries what it makes there by hand; then it
     * gives no more than {@link #haulRate} a second (a stack a round trip).
     */
    public boolean hauled;
    public float haulRate;

    public Machine(Recipe recipe, int item, float factor, int x, int y, float distance, int[] cost, boolean boosted, boolean existing){
        this.recipe = recipe;
        this.item = item;
        this.factor = factor;
        this.x = x;
        this.y = y;
        this.distance = distance;
        this.cost = cost;
        this.boosted = boosted;
        this.existing = existing;
    }

    /** What it gives per second at full speed, before any boost. */
    public float out(int res){
        float o = recipe.out[res];
        if(res == item) o += recipe.perFactor[res] * factor;
        return hauled ? Math.min(o, haulRate) : o;
    }

    public float powerOut(){
        return recipe.ground == Recipe.Ground.vent ? recipe.powerOut * factor : recipe.powerOut;
    }

    @Override
    public String toString(){
        return recipe.name + (item >= 0 ? "(" + factor + ")" : "") + "@" + x + "," + y;
    }
}
