package xtest;

import arc.struct.*;
import mindustry.world.*;

/** Draws blocks as text: two characters a tile, a short code and the way the block faces. */
public class Picture{
    static final String dirs = ">^<v";
    /** The side whose blocks are drawn by what they are; the other sides' are drawn as boxes. The enemy's unless set. */
    public static mindustry.game.Team side;
    public static final ObjectMap<String, String> codes = new ObjectMap<>();
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

    /** Part of the map as text, with what the enemy has planned shown in place. */
    public static String region(int x0, int y0, int w, int h, expansion.Ground ground){
        StringBuilder out = new StringBuilder();
        for(int y = y0 + h - 1; y >= y0; y--){
            out.append(String.format("%4d ", y));
            for(int x = x0; x < x0 + w; x++){
                mindustry.world.Tile t = mindustry.Vars.world.tile(x, y);
                if(t == null){ out.append("  "); continue; }
                expansion.Blueprint.Part part = ground == null ? null : ground.planned(x, y);
                if(part != null) out.append(cell(part.block, part.rotation, part.x == x && part.y == y).toLowerCase().replace(' ', '\''));
                else if(t.build != null) out.append(t.team() == (side != null ? side : mindustry.Vars.state.rules.waveTeam) ? cell(t.block(), t.build.rotation, t.build.tile == t) : "□□");
                else if(t.block().solid){ mindustry.type.Item drop = t.wallDrop(); out.append(drop != null ? "" + drop.name.charAt(0) + drop.name.charAt(0) : "%%"); }
                else if(t.floor() instanceof mindustry.world.blocks.environment.SteamVent) out.append("::");
                else if(t.floor().isDeep() || t.floor().solid || !t.floor().placeableOn) out.append("≈≈");
                else if(!mindustry.Vars.world.isInMapArea(x, y)) out.append("..");
                else if(ground != null && !ground.rule.allows(x, y)) out.append("××");
                else if(ground != null && ground.kept.contains(arc.math.geom.Point2.pack(x, y))) out.append("··");
                else out.append("  ");
            }
            out.append('\n');
        }
        out.append("     ");
        for(int x = x0; x < x0 + w; x++) out.append(x % 5 == 0 ? String.format("%-2d", x % 100) : "  ");
        return out.toString();
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
