package xtest;

import arc.files.*;
import mindustry.*;
import mindustry.game.*;
import mindustry.world.*;

/**
 * A schematic as text: each block with its position, rotation and config, and a picture with one letter a block (the
 * legend after it). For looking at how a design is laid out.
 *
 * Usage: SchemDump data-dir file.msch...
 */
public class SchemDump{
    public static void main(String[] args) throws Exception{
        Boot.boot(args[0]);
        for(int a = 1; a < args.length; a++){
            Schematic s = Schematics.read(new Fi(args[a]));
            System.out.println("== " + args[a] + " " + s.width + "x" + s.height);
            char[][] pic = new char[s.height][s.width];
            for(char[] row : pic) java.util.Arrays.fill(row, '.');
            java.util.Map<String, Character> legend = new java.util.LinkedHashMap<>();
            String letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
            for(Schematic.Stile t : s.tiles){
                Character c = legend.get(t.block.name);
                if(c == null){
                    c = letters.charAt(Math.min(legend.size(), letters.length() - 1));
                    legend.put(t.block.name, c);
                }
                int off = (t.block.size - 1) / 2;
                for(int dx = 0; dx < t.block.size; dx++) for(int dy = 0; dy < t.block.size; dy++){
                    int x = t.x - off + dx, y = t.y - off + dy;
                    if(x >= 0 && y >= 0 && x < s.width && y < s.height) pic[y][x] = c;
                }
                if(Boolean.getBoolean("dump.list")) System.out.println(String.format("  %s at %d,%d r%d %s", t.block.name, t.x, t.y, t.rotation, t.config));
            }
            for(int y = s.height - 1; y >= 0; y--) System.out.println("  " + new String(pic[y]));
            StringBuilder l = new StringBuilder("  ");
            for(var e : legend.entrySet()) l.append(e.getValue()).append('=').append(e.getKey()).append(' ');
            System.out.println(l);
        }
        System.exit(0);
    }
}
