package bp;

import arc.files.*;
import arc.math.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.io.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.environment.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.storage.*;
import mindustry.world.meta.*;

import java.util.*;

/**
 * A test map with room: wide flat ground, the core in the middle, and of every ground the recipes draw on (ore on the
 * floor or in walls, walls that give, liquids, vents) several large fields round the core with open ground between them.
 * For trying the whole climb where room and ore are not what is short.
 *
 * Usage: bp.BigMap data-dir template.msav out.msav [size] [fields of each ground] [pitch] [seed]
 * The template gives the rules and the start (its core's block and stock).
 */
public final class BigMap{
    public static void main(String[] args){
        Game.boot(args[0]);
        SaveIO.load(new Fi(args[1]));
        int size = args.length > 3 ? Integer.parseInt(args[3]) : 300;
        int per = args.length > 4 ? Integer.parseInt(args[4]) : 4;
        int pitch = args.length > 5 ? Integer.parseInt(args[5]) : 28;
        long seed = args.length > 6 ? Long.parseLong(args[6]) : 1L;
        //the start, as the template has it
        Block coreBlock = Blocks.coreBastion;
        List<ItemStack> stock = new ArrayList<>();
        for(Tile t : Vars.world.tiles) if(t.build instanceof CoreBlock.CoreBuild c && c.team == Vars.state.rules.defaultTeam && t.isCenter()){
            coreBlock = c.block;
            for(Item it : Vars.content.items()) if(c.items.get(it) > 0) stock.add(new ItemStack(it, c.items.get(it)));
        }

        //each ground some recipe draws on, once: what it is painted with
        Map<String, Block[]> grounds = new LinkedHashMap<>();
        Map<String, String> shape = new HashMap<>();
        for(Recipe r : Recipes.all()){
            switch(r.ground){
                case wallOre -> {
                    Item item = Vars.content.item(r.dug);
                    Block[] wo = item == null ? null : Terraform.wallOre(item);
                    if(wo != null && wo[0] != null) put(grounds, shape, "wall " + r.dug, wo, "ridge");
                }
                case sandWall -> {
                    Block w = Terraform.bestWall(((WallCrafter)r.block).attribute);
                    if(w != null) put(grounds, shape, "wall " + w.name, new Block[]{w, null}, "ridge");
                }
                case floorOre -> {
                    Item item = Vars.content.item(r.dug);
                    Block ore = item == null ? null : Terraform.floorOre(item);
                    if(ore != null) put(grounds, shape, "floor " + r.dug, new Block[]{ore}, "field");
                }
                case liquidFloor -> {
                    Liquid l = Vars.content.liquid(r.dug);
                    List<Floor> fs = l == null ? List.of() : Terraform.liquidFloors(l);
                    if(!fs.isEmpty()) put(grounds, shape, "liquid " + r.dug, new Block[]{fs.get(0)}, "field");
                }
                case vent -> {
                    Attribute attr = r.block instanceof AttributeCrafter a ? a.attribute : r.block instanceof ThermalGenerator t ? t.attribute : null;
                    Floor f = attr == null ? null : Terraform.bestFloor(attr);
                    if(f != null) put(grounds, shape, "vent " + attr.name, new Block[]{f}, "vent");
                }
                default -> {
                }
            }
        }

        //the fields' places: a grid round the core, nearest first; each round of grounds in a new order
        int cx = size / 2, cy = size / 2;
        List<int[]> cells = new ArrayList<>();
        int reach = (size / 2 - 10) / pitch;
        for(int j = -reach; j <= reach; j++) for(int i = -reach; i <= reach; i++) if(i != 0 || j != 0) cells.add(new int[]{cx + i * pitch, cy + j * pitch});
        cells.sort(Comparator.comparingDouble(c -> Math.hypot(c[0] - cx, c[1] - cy) + 1e-3 * Math.atan2(c[1] - cy, c[0] - cx)));
        Rand rand = new Rand(seed);
        List<String> names = new ArrayList<>(grounds.keySet());
        List<String[]> fields = new ArrayList<>();
        int k = 0;
        for(int round = 0; round < per; round++){
            List<String> order = new ArrayList<>(names);
            Collections.shuffle(order, new Random(seed * 31 + round));
            for(String g : order){
                if(k >= cells.size()) break;
                int[] c = cells.get(k++);
                fields.add(new String[]{g, String.valueOf(c[0] + rand.range(pitch / 7)), String.valueOf(c[1] + rand.range(pitch / 7))});
            }
        }

        Block base = Blocks.rhyolite, edge = Blocks.rhyoliteWall;
        Block[] floor = new Block[size * size], overlay = new Block[size * size], wall = new Block[size * size];
        Arrays.fill(floor, base);
        Arrays.fill(overlay, Blocks.air);
        Arrays.fill(wall, Blocks.air);
        for(int y = 0; y < size; y++) for(int x = 0; x < size; x++) if(x < 2 || y < 2 || x >= size - 2 || y >= size - 2) wall[x + y * size] = edge;
        StringBuilder told = new StringBuilder();
        int fi = 0;
        for(String[] f : fields){
            Block[] g = grounds.get(f[0]);
            int x0 = Integer.parseInt(f[1]), y0 = Integer.parseInt(f[2]);
            switch(shape.get(f[0])){
                //a wall two thick and long, the ore on all of it, open on both long sides; along x or y by turns
                case "ridge" -> {
                    boolean along = (fi++ & 1) == 0;
                    int len = 14, thick = 2;
                    for(int a = -len / 2; a < len / 2; a++) for(int b = 0; b < thick; b++){
                        int x = along ? x0 + a : x0 + b, y = along ? y0 + b : y0 + a;
                        wall[x + y * size] = g[0];
                        if(g.length > 1 && g[1] != null) overlay[x + y * size] = g[1];
                    }
                }
                //a square of it
                case "field" -> {
                    int s = 10;
                    for(int y = y0 - s / 2; y < y0 + s / 2; y++) for(int x = x0 - s / 2; x < x0 + s / 2; x++){
                        if(g[0] instanceof OverlayFloor || g[0] instanceof OreBlock) overlay[x + y * size] = g[0];
                        else floor[x + y * size] = g[0];
                    }
                }
                //a square of vent floor, so that a machine on vents stands whole anywhere on it
                case "vent" -> {
                    int s = 9;
                    for(int y = y0 - s / 2; y < y0 - s / 2 + s; y++) for(int x = x0 - s / 2; x < x0 - s / 2 + s; x++) floor[x + y * size] = g[0];
                }
                default -> {
                }
            }
            told.append(String.format("  %s at %s,%s (%.0f from the core)%n", f[0], f[1], f[2], Math.hypot(x0 - cx, y0 - cy)));
        }

        Vars.world.loadGenerator(size, size, tiles -> {
            for(int y = 0; y < size; y++) for(int x = 0; x < size; x++){
                int i = x + y * size;
                tiles.set(x, y, new Tile(x, y, floor[i], overlay[i], wall[i]));
            }
        });
        Tile ct = Vars.world.tile(cx, cy);
        ct.setBlock(coreBlock, Vars.state.rules.defaultTeam, 0);
        //(a core put down gets the rules' loadout of itself: its stock set to the template's, not added to that)
        ct.build.items.clear();
        for(ItemStack s : stock) ct.build.items.add(s.item, s.amount);
        SaveIO.save(new Fi(args[2]));
        System.out.println(String.format("BIGMAP %dx%d, %s at %d,%d with %s; %d grounds, %d fields:", size, size, coreBlock.name, cx, cy, stock, grounds.size(), fields.size()));
        System.out.print(told);
        System.out.println("saved " + args[2]);
        System.exit(0);
    }

    static void put(Map<String, Block[]> grounds, Map<String, String> shape, String key, Block[] g, String how){
        if(grounds.containsKey(key)) return;
        grounds.put(key, g);
        shape.put(key, how);
    }
}
