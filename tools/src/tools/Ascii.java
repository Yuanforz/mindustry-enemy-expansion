package tools;

import arc.struct.*;
import mindustry.world.*;

/** Draws blocks as text: two characters a tile, a short code and the way the block faces. */
public class Ascii{
    static final String dirs = ">^<v";
    static final ObjectMap<String, String> codes = new ObjectMap<>();
    static{
        String[] known = {
            "duct", "d", "armored-duct", "a", "duct-router", "R", "overflow-duct", "O", "underflow-duct", "U", "duct-bridge", "B", "duct-unloader", "L",
            "surge-conveyor", "s", "surge-router", "S", "reinforced-container", "K", "reinforced-vault", "V",
            "plasma-bore", "P", "large-plasma-bore", "Q", "impact-drill", "I", "eruption-drill", "E", "cliff-crusher", "C", "large-cliff-crusher", "c",
            "beam-node", "n", "beam-tower", "N", "beam-link", "k", "turbine-condenser", "T", "vent-condenser", "W", "chemical-combustion-chamber", "G", "pyrolysis-generator", "Y", "flux-reactor", "X", "neoplasia-reactor", "Z",
            "reinforced-conduit", "~", "reinforced-bridge-conduit", "b", "reinforced-liquid-router", "r", "reinforced-liquid-junction", "j", "reinforced-liquid-container", "l", "reinforced-liquid-tank", "t", "reinforced-pump", "p",
            "silicon-arc-furnace", "F", "electrolyzer", "e", "oxidation-chamber", "o", "atmospheric-concentrator", "A", "carbide-crucible", "x", "surge-crucible", "u", "cyanogen-synthesizer", "y", "phase-synthesizer", "f",
            "electric-heater", "h", "slag-heater", "g", "phase-heater", "H", "heat-redirector", "i", "heat-router", "J", "small-heat-redirector", "q",
            "breach", "1", "diffuse", "2", "sublimate", "3", "titan", "4", "disperse", "5", "afflict", "6", "lustre", "7", "scathe", "8", "smite", "9", "malign", "0",
            "beryllium-wall", "#", "beryllium-wall-large", "#", "tungsten-wall", "#", "tungsten-wall-large", "#", "blast-door", "D", "reinforced-surge-wall", "#", "reinforced-surge-wall-large", "#", "carbide-wall", "#", "carbide-wall-large", "#", "shielded-wall", "#",
            "tank-fabricator", "M", "ship-fabricator", "M", "mech-fabricator", "M", "tank-refabricator", "m", "ship-refabricator", "m", "mech-refabricator", "m", "prime-refabricator", "w",
            "tank-assembler", "$", "ship-assembler", "$", "mech-assembler", "$", "basic-assembler-module", "%",
            "core-bastion", "@", "core-citadel", "@", "core-acropolis", "@", "regen-projector", "+", "build-tower", "&", "radar", "!", "shockwave-tower", "*", "unit-repair-tower", "=",
            "reinforced-payload-conveyor", "z", "reinforced-payload-router", "z", "payload-mass-driver", "z", "constructor", "z", "small-deconstructor", "z", "payload-loader", "z", "payload-unloader", "z",
            "world-processor", "?", "world-cell", "?", "world-message", "?"
        };
        for(int i = 0; i < known.length; i += 2) codes.put(known[i], known[i + 1]);
    }

    public static String cell(Block block, int rotation, boolean origin){
        String code = codes.get(block.name, "?");
        return code + (block.rotate ? (origin ? dirs.charAt(rotation & 3) : '.') : (origin ? ' ' : '.'));
    }

    /** The legend of the codes that came up. */
    public static String legend(ObjectIntMap<String> used){
        StringBuilder b = new StringBuilder();
        Seq<String> names = used.keys().toSeq();
        names.sort();
        for(String name : names) b.append(codes.get(name, "?")).append('=').append(name).append('×').append(used.get(name)).append("  ");
        return b.toString();
    }
}
