package xtest;

import arc.files.*;
import arc.struct.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.core.GameState.*;
import mindustry.io.*;
import mindustry.type.*;
import mindustry.world.*;

/**
 * Where an item lies in the floor of a map, in patches, and how much of it a drill of a given size could stand on at the
 * best place in each patch, on terrain alone (nothing built). Usage: OreFloor data blueprints map.msav item size
 */
public class OreFloor{
    public static void main(String[] args){
        Sim.boot(args[0], args[1]);
        Vars.logic.reset();
        SaveIO.load(new Fi(args[2]));
        Vars.state.set(State.playing);
        Item item = Vars.content.item(args[3]);
        int size = Integer.parseInt(args[4]);
        int w = Vars.world.width(), h = Vars.world.height();
        IntSeq ore = new IntSeq();
        for(int x = 0; x < w; x++){
            for(int y = 0; y < h; y++){
                Tile tile = Vars.world.tile(x, y);
                if(tile.drop() == item && tile.block() == Blocks.air) ore.add(x + y * w);
            }
        }
        System.out.println(item.name + ": " + ore.size + " tiles in the floor");
        //patches: tiles of it joined side by side or corner to corner
        IntSet seen = new IntSet();
        for(int i = 0; i < ore.size; i++){
            int start = ore.get(i);
            if(seen.contains(start)) continue;
            IntSeq patch = new IntSeq();
            IntQueue open = new IntQueue();
            open.addLast(start);
            seen.add(start);
            while(!open.isEmpty()){
                int at = open.removeFirst();
                patch.add(at);
                for(int dx = -1; dx <= 1; dx++){
                    for(int dy = -1; dy <= 1; dy++){
                        int x = at % w + dx, y = at / w + dy;
                        if(x < 0 || y < 0 || x >= w || y >= h) continue;
                        int next = x + y * w;
                        if(!seen.contains(next) && ore.contains(next)){
                            seen.add(next);
                            open.addLast(next);
                        }
                    }
                }
            }
            //the best place for a drill over it: most of it under the drill, every tile of the drill on buildable ground
            int best = -1, bestCount = 0, offset = -(size - 1) / 2;
            int blocked = 0;
            for(int p = 0; p < patch.size; p++){
                for(int ox = -size; ox <= size; ox++){
                    for(int oy = -size; oy <= size; oy++){
                        int cx = patch.get(p) % w + ox, cy = patch.get(p) / w + oy, count = 0;
                        boolean ok = true;
                        for(int dx = 0; dx < size && ok; dx++){
                            for(int dy = 0; dy < size; dy++){
                                Tile tile = Vars.world.tile(cx + dx + offset, cy + dy + offset);
                                if(tile == null || tile.solid() || tile.floor().isDeep() || !tile.floor().placeableOn){
                                    ok = false;
                                    break;
                                }
                                if(tile.drop() == item) count++;
                            }
                        }
                        if(!ok){
                            blocked++;
                            continue;
                        }
                        if(count > bestCount){
                            bestCount = count;
                            best = cx + cy * w;
                        }
                    }
                }
            }
            System.out.println("  patch of " + patch.size + " at " + patch.first() % w + "," + patch.first() / w + ": best place " + (best < 0 ? "none" : best % w + "," + best / w) + " with " + bestCount + " of " + size * size + " on it");
        }
        System.exit(0);
    }
}
