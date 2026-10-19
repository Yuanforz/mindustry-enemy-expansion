package expansion.defense;

/** One block the defence plan puts down. A port is where a supply line ends: on the map a router, in the lab a source. */
public final class Placement{
    public enum Kind{turret, wall, bus, store, heat, support, port}

    public final String block;
    public final int x, y, rotation;
    /** What it is set to: the item or liquid a port takes. */
    public final String config;
    public final Kind kind;
    /** Which module (by its number in the plan) it belongs to; walls belong to the post's wall line. */
    public final int module;
    /** For a port: whether it is a liquid, and whether the module does without it (water for faster fire). */
    public boolean liquid, optional;

    public Placement(String block, int x, int y, int rotation, String config, Kind kind, int module){
        this.block = block;
        this.x = x;
        this.y = y;
        this.rotation = rotation;
        this.config = config;
        this.kind = kind;
        this.module = module;
    }

    @Override
    public String toString(){
        return block + " " + x + "," + y + (rotation != 0 ? " r" + rotation : "") + (config != null ? " " + config : "");
    }

    /** What a block is, from its name, for the plan's lists. */
    static Kind kindOf(String block){
        if(Gun.get(block) != null) return Kind.turret;
        if(block.endsWith("-source")) return Kind.port;
        if(block.contains("wall") || block.equals("blast-door")) return Kind.wall;
        if(block.contains("container") || block.contains("tank")) return Kind.store;
        if(block.contains("heater") || block.contains("heat-")) return Kind.heat;
        if(block.contains("projector") || block.contains("tower")) return Kind.support;
        return Kind.bus;
    }
}
