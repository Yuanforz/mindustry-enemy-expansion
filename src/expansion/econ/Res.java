package expansion.econ;

/** Something that flows in the model: an item, a liquid, or a kind of unit. Power is kept apart, as one number. */
public final class Res{
    public enum Kind{ item, liquid, unit }

    public final int id;
    public final String name;
    public final Kind kind;
    /** Whether a core keeps it. Items that nothing is built of (sand) are burned by an Erekir core, and liquids and units never get there. */
    public final boolean storable;
    /** Whether the core unit can dig it out of a wall by hand. */
    public final boolean handMined;

    public Res(int id, String name, Kind kind, boolean storable, boolean handMined){
        this.id = id;
        this.name = name;
        this.kind = kind;
        this.storable = storable;
        this.handMined = handMined;
    }

    @Override
    public String toString(){
        return name;
    }
}
