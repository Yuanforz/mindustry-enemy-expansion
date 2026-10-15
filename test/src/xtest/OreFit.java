package xtest;

import arc.files.*;
import arc.math.geom.*;
import arc.struct.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.storage.CoreBlock.*;

/**
 * How much ore in the walls a side mines, against how much it could: every place a beam drill could
 * stand by the game's own placement rules, each way round, and how many of its beams would end on ore.
 * Usage: OreFit data-dir blueprint-dir save.msav [team]
 */
public class OreFit{
    /** The ore tiles the beams of a drill standing so would end on, as packed positions; the item they hold must be this one. */
    static IntSeq beams(BeamDrill drill, int x, int y, int rotation, Item item){
        IntSeq out = new IntSeq();
        int size = drill.size, offset = -(size - 1) / 2;
        int dx = Geometry.d4x(rotation), dy = Geometry.d4y(rotation);
        for(int lane = 0; lane < size; lane++){
            //the tile on the facing side the beam starts beside
            int sx, sy;
            if(dx != 0){
                sx = x + (dx > 0 ? offset + size - 1 : offset);
                sy = y + offset + lane;
            }else{
                sx = x + offset + lane;
                sy = y + (dy > 0 ? offset + size - 1 : offset);
            }
            for(int step = 1; step <= drill.range; step++){
                Tile other = Vars.world.tile(sx + dx * step, sy + dy * step);
                if(other == null) break;
                if(other.solid()){
                    Item drop = other.wallDrop();
                    if(drop == item && drop.hardness <= drill.tier) out.add(other.pos());
                    break;
                }
            }
        }
        return out;
    }

    public static void main(String[] args){
        Sim.boot(args[0], args[1]);
        Vars.logic.reset();
        SaveIO.load(new Fi(args[2]));
        Team team = args.length > 3 ? Team.get(Integer.parseInt(args[3])) : Vars.state.rules.defaultTeam;
        BeamDrill drill = (BeamDrill)Blocks.plasmaBore;
        //what could stand anywhere, seen or not: fog is left out of this
        boolean fog = !Boolean.getBoolean("orefit.fog");
        if(fog){
            Vars.state.rules.fog = false;
            Vars.state.rules.staticFog = false;
        }
        CoreBuild core = team.core();
        System.out.println("plasma bore: size " + drill.size + ", range " + drill.range + ", tier " + drill.tier + "; side " + team + ", core at " + (core == null ? "-" : core.tileX() + "," + core.tileY()));
        if(System.getProperty("orefit.why") != null){
            //why the game refuses a place near the first ore tile of beryllium
            for(Tile tile : Vars.world.tiles){
                if(!(tile.solid() && tile.wallDrop() == Items.beryllium)) continue;
                for(int dx = -4; dx <= 4; dx++) for(int dy = -4; dy <= 4; dy++){
                    Tile at = Vars.world.tile(tile.x + dx, tile.y + dy);
                    if(at == null || at.solid()) continue;
                    System.out.println("  " + at.x + "," + at.y + " valid " + Build.validPlace(drill, team, at.x, at.y, 0, false) + " canPlaceOn " + drill.canPlaceOn(at, team, 0) + " build " + (at.build == null ? "-" : at.build.block.name) + " discovered " + Vars.fogControl.isDiscovered(team, at.x, at.y) + " fog " + Vars.state.rules.fog + "/" + Vars.state.rules.staticFog + " placeRange " + Vars.state.rules.placeRangeCheck + " state " + Vars.state.getState());
                    break;
                }
                break;
            }
        }
        for(Item item : new Item[]{Items.beryllium, Items.graphite, Items.tungsten}){
            int ore = 0;
            for(Tile tile : Vars.world.tiles) if(tile.solid() && tile.wallDrop() == item && Vars.world.isInMapArea(tile.x, tile.y)) ore++;
            //what its own drills mine now
            IntSet mined = new IntSet();
            int drills = 0, lanes = 0;
            for(Building build : team.data().buildings){
                if(build.block != drill) continue;
                IntSeq hit = beams(drill, build.tileX(), build.tileY(), build.rotation, item);
                if(hit.isEmpty()) continue;
                drills++;
                lanes += hit.size;
                for(int i = 0; i < hit.size; i++) mined.add(hit.get(i));
            }
            //every place one more could go now, the most beams on ore first; then as many as fit together, greedily
            Seq<int[]> places = new Seq<>();
            for(Tile tile : Vars.world.tiles){
                if(!Vars.world.isInMapArea(tile.x, tile.y)) continue;
                for(int r = 0; r < 4; r++){
                    if(!Build.validPlace(drill, team, tile.x, tile.y, r, false)) continue;
                    IntSeq hit = beams(drill, tile.x, tile.y, r, item);
                    int fresh = 0;
                    for(int i = 0; i < hit.size; i++) if(!mined.contains(hit.get(i))) fresh++;
                    if(fresh > 0) places.add(new int[]{tile.x, tile.y, r, fresh, core == null ? 0 : (int)Math.abs(tile.x - core.tileX()) + Math.abs(tile.y - core.tileY())});
                }
            }
            places.sort(p -> -p[3] * 10000 + p[4]);
            IntSet taken = new IntSet(), newly = new IntSet();
            int fit = 0, fitLanes = 0;
            StringBuilder list = new StringBuilder();
            for(int[] p : places){
                int offset = -(drill.size - 1) / 2;
                boolean clash = false;
                for(int dx = 0; dx < drill.size && !clash; dx++) for(int dy = 0; dy < drill.size && !clash; dy++) clash = taken.contains(Point2.pack(p[0] + offset + dx, p[1] + offset + dy));
                IntSeq hit = beams(drill, p[0], p[1], p[2], item);
                int fresh = 0;
                for(int i = 0; i < hit.size; i++) if(!mined.contains(hit.get(i)) && !newly.contains(hit.get(i))) fresh++;
                if(clash || fresh == 0) continue;
                //a tile round it is kept for the duct out
                for(int dx = -1; dx <= drill.size; dx++) for(int dy = -1; dy <= drill.size; dy++) taken.add(Point2.pack(p[0] + offset + dx, p[1] + offset + dy));
                for(int i = 0; i < hit.size; i++) newly.add(hit.get(i));
                fit++;
                fitLanes += fresh;
                if(fit <= 12) list.append(' ').append(p[0]).append(',').append(p[1]).append(" r").append(p[2]).append(" x").append(fresh).append(" (").append(p[4]).append(" from core)");
            }
            System.out.println(item.name + ": " + ore + " ore tiles in walls; its " + drills + " drills on it have " + lanes + " beams on " + mined.size + " of them; "
                + places.size + " places one more could go now; " + fit + " more fit together, with " + fitLanes + " more beams:" + list);
        }
        System.exit(0);
    }
}
