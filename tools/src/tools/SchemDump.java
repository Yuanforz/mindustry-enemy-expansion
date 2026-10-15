package tools;

import arc.files.*;
import arc.struct.*;
import mindustry.game.*;
import mindustry.game.Schematic.*;
import mindustry.world.*;

/** Draws schematics as text. Usage: data-dir file.msch... (read only: the files are data, nothing in them is run) */
public class SchemDump{
    public static void main(String[] args) throws Exception{
        Boot.boot(args[0]);
        for(int a = 1; a < args.length; a++){
            Schematic s;
            try{
                s = Schematics.read(new Fi(args[a]));
            }catch(Throwable t){
                System.out.println("#### " + args[a] + ": unreadable (" + t + ")");
                continue;
            }
            String[][] grid = new String[s.height][s.width];
            ObjectIntMap<String> used = new ObjectIntMap<>();
            StringBuilder configs = new StringBuilder();
            for(Stile t : s.tiles){
                used.increment(t.block.name);
                int offset = -(t.block.size - 1) / 2;
                for(int dx = 0; dx < t.block.size; dx++){
                    for(int dy = 0; dy < t.block.size; dy++){
                        int x = t.x + dx + offset, y = t.y + dy + offset;
                        if(x < 0 || y < 0 || x >= s.width || y >= s.height) continue;
                        grid[y][x] = Ascii.cell(t.block, t.rotation, dx + offset == 0 && dy + offset == 0);
                    }
                }
                if(t.config != null && !(t.config instanceof byte[]) && !(t.config instanceof String str && str.length() > 30)){
                    String c = t.config instanceof mindustry.ctype.Content cc ? cc.toString() : t.config.getClass().isArray() ? "[..]" : String.valueOf(t.config);
                    if(!c.equals("0") && !c.equals("false") && !c.equals("[..]")) configs.append(t.block.name).append('@').append(t.x).append(',').append(t.y).append('=').append(c).append("  ");
                }
            }
            System.out.println("#### " + new Fi(args[a]).name() + "  \"" + s.name() + "\"  " + s.width + "x" + s.height + "  " + s.tiles.size + " blocks");
            for(int y = s.height - 1; y >= 0; y--){
                StringBuilder line = new StringBuilder(String.format("%3d ", y));
                for(int x = 0; x < s.width; x++) line.append(grid[y][x] == null ? "  " : grid[y][x]);
                System.out.println(line);
            }
            System.out.println("    " + Ascii.legend(used));
            if(configs.length() > 0) System.out.println("    set: " + configs);
        }
        System.exit(0);
    }
}
