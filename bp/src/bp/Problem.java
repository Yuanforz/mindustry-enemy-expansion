package bp;

import arc.files.*;

import java.util.*;

/**
 * What the layout is asked to do, in a file:
 * <pre>
 * map            the ground, as a spec draws it (Spec)
 * ...
 * end
 * fixed          blocks already standing, as a spec's place lines: block x y rotation [config]
 * core-bastion 20 10 0
 * item-source 1 5 0 graphite       (a supply point: what it gives is supplied, without limit)
 * power-source 1 1 0               (a point power is supplied at)
 * end
 * goal silicon 4                   (into the core, a second; a unit, a turret's name for one kept firing)
 * power free                       (power-source blocks may be put down anywhere; else power comes from the
 *                                   fixed ones or from generators the balance chooses)
 * weights tile=1,item=0.01         (the balance's and the layout's weights)
 * seconds 120                      (how long the bench runs the result)
 * </pre>
 */
public final class Problem{
    public final Spec ground = new Spec();
    public final List<Spec.Place> fixed = new ArrayList<>();
    public final LinkedHashMap<String, Float> goals = new LinkedHashMap<>();
    public boolean freePower;
    public float perTile = 1f, perItem = 0.01f, perSecond = 0f;
    public float seconds = 120f;
    public String name = "";
    /**
     * A module: so wide and high, its ports on its edge; an input port gives what it names without limit, an output port
     * takes what it names (at the rate given, or what is to spare, for "out spare").
     */
    public int moduleW, moduleH;
    public final List<Port> ports = new ArrayList<>();
    public boolean spare;

    /** A port of a module: in or out, its kind (item, liquid, power, heat), what, the rate (0: as the balance has it). */
    public record Port(boolean in, String kind, String res, float rate){
    }

    public static Problem read(Fi file){
        Problem p = parse(file.readString());
        p.name = file.nameWithoutExtension();
        return p;
    }

    public static Problem parse(String text){
        Problem p = parse0(text);
        //(a module with no map drawn: open ground of its size)
        if(p.moduleW > 0 && p.ground.rows.isEmpty()) for(int y = 0; y < p.moduleH; y++) p.ground.rows.add(".".repeat(p.moduleW));
        for(Port port : p.ports) if(!port.in() && port.rate() > 0f) p.goals.put(port.res(), port.rate());
        return p;
    }

    static Problem parse0(String text){
        Problem p = new Problem();
        String section = "";
        for(String raw : text.split("\n")){
            String line = raw.replace("\r", "");
            if(line.trim().startsWith("#") && !section.equals("map")) continue;
            if(line.trim().isEmpty()) continue;
            if(line.trim().equals("end")){
                section = "";
                continue;
            }
            String[] w = line.trim().split("\\s+");
            if(section.isEmpty()){
                switch(w[0]){
                    case "map", "fixed" -> section = w[0];
                    case "goal" -> p.goals.put(w[1], Float.parseFloat(w[2]));
                    case "power" -> p.freePower = w[1].equals("free");
                    case "module" -> {
                        p.moduleW = Integer.parseInt(w[1]);
                        p.moduleH = Integer.parseInt(w[2]);
                    }
                    case "in", "out" -> {
                        if(w[1].equals("spare")) p.spare = true;
                        else p.ports.add(new Port(w[0].equals("in"), w[1], w.length > 2 ? w[2] : w[1], w.length > 3 ? Float.parseFloat(w[3]) : 0f));
                    }
                    case "seconds" -> p.seconds = Float.parseFloat(w[1]);
                    case "weights" -> {
                        for(String kv : w[1].split(",")){
                            String[] q = kv.split("=");
                            float v = Float.parseFloat(q[1]);
                            if(q[0].equals("tile")) p.perTile = v;
                            else if(q[0].equals("item")) p.perItem = v;
                            else if(q[0].equals("second")) p.perSecond = v;
                        }
                    }
                    default -> throw new IllegalArgumentException("unknown line: " + line);
                }
                continue;
            }
            if(section.equals("map")) p.ground.rows.add(line);
            else p.fixed.add(new Spec.Place(w[0], Integer.parseInt(w[1]), Integer.parseInt(w[2]), w.length > 3 ? Integer.parseInt(w[3]) : 0, w.length > 4 ? w[4] : null));
        }
        return p;
    }
}
