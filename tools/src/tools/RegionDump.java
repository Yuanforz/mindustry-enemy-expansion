package tools;

import arc.files.*;
import arc.struct.*;
import mindustry.*;
import mindustry.game.*;
import mindustry.io.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.environment.*;

/**
 * Draws part of a map as text. Usage: data-dir map.msav x y width height [team]
 * Ground: space open, '%' wall, ore walls by the item's first letter in lower case doubled, vents '::', deep/blocked floor '≈≈'.
 */
public class RegionDump{
    public static void main(String[] args){
        Boot.boot(args[0]);
        Vars.logic.reset();
        SaveIO.load(new Fi(args[1]));
        int x0 = Integer.parseInt(args[2]), y0 = Integer.parseInt(args[3]), w = Integer.parseInt(args[4]), h = Integer.parseInt(args[5]);
        String only = args.length > 6 ? args[6] : null;
        ObjectIntMap<String> used = new ObjectIntMap<>();
        StringBuilder out = new StringBuilder();
        for(int y = y0 + h - 1; y >= y0; y--){
            out.append(String.format("%4d ", y));
            for(int x = x0; x < x0 + w; x++){
                Tile t = Vars.world.tile(x, y);
                if(t == null){ out.append("  "); continue; }
                if(t.build != null && (only == null || t.team().name.equals(only))){
                    boolean origin = t.build.tile == t;
                    if(origin) used.increment(t.block().name);
                    out.append(Ascii.cell(t.block(), t.build.rotation, origin));
                }else if(t.build != null){
                    out.append("□□");
                }else if(t.block().solid){
                    Item drop = t.wallDrop();
                    out.append(drop != null ? ("" + drop.name.charAt(0) + drop.name.charAt(0)) : "%%");
                }else if(t.floor() instanceof SteamVent){
                    out.append("::");
                }else if(t.floor().isDeep() || t.floor().solid || !t.floor().placeableOn){
                    out.append("≈≈");
                }else if(t.overlay().itemDrop != null){
                    out.append("" + Character.toUpperCase(t.overlay().itemDrop.name.charAt(0)) + '\'');
                }else if(t.floor().isLiquid){
                    out.append("~~");
                }else{
                    out.append("  ");
                }
            }
            out.append('\n');
        }
        out.append("     ");
        for(int x = x0; x < x0 + w; x++) out.append(x % 5 == 0 ? String.format("%-2d", x % 100) : "  ");
        System.out.println(out);
        System.out.println(Ascii.legend(used));
        System.exit(0);
    }
}
