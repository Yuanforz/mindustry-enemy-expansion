package bp;

import arc.files.*;
import mindustry.content.*;
import mindustry.world.*;

import java.util.*;

/**
 * A small map drawn in characters and a layout on it, as the test bench (xtest.BpBench) reads them:
 * <pre>
 * map            rows, top first; the bottom row is y 0
 * ...
 * end
 * place          block x y rotation [config]
 * ...
 * end
 * seconds 60
 * </pre>
 * Map characters: . floor (rhyolite), # wall (rhyolite), S sand wall (yellow stone, 1.5 sand), D dune wall (2 sand),
 * B beryllium ore in a wall, G graphitic wall, T tungsten ore in a wall, H thorium ore in a wall, v vent (rhyolite),
 * b beryllium ore, t tungsten ore, h thorium ore on the floor, ~ molten slag, a arkycite.
 */
public final class Spec{
    public final List<String> rows = new ArrayList<>();
    public final List<Place> places = new ArrayList<>();
    public float seconds = 60f;
    public String name = "";

    public record Place(String block, int x, int y, int rotation, String config){
        @Override
        public String toString(){
            return block + " " + x + " " + y + " " + rotation + (config == null ? "" : " " + config);
        }
    }

    public int width(){
        int w = 0;
        for(String r : rows) w = Math.max(w, r.length());
        return w;
    }

    public int height(){
        return rows.size();
    }

    /** The character at a tile (x right, y up). */
    public char at(int x, int y){
        String r = rows.get(height() - 1 - y);
        return x < r.length() ? r.charAt(x) : '.';
    }

    public static Spec read(Fi file){
        Spec s = parse(file.readString());
        s.name = file.nameWithoutExtension();
        return s;
    }

    public static Spec parse(String text){
        Spec s = new Spec();
        String section = "";
        for(String raw : text.split("\n")){
            String line = raw.replace("\r", "");
            if(line.trim().startsWith("#") && !section.equals("map")) continue;
            if(line.trim().isEmpty()) continue;
            if(line.trim().equals("end")){
                section = "";
                continue;
            }
            if(section.isEmpty()){
                String[] w = line.trim().split("\\s+");
                if(w[0].equals("map") || w[0].equals("place")) section = w[0];
                else if(w[0].equals("seconds")) s.seconds = Float.parseFloat(w[1]);
                continue;
            }
            if(section.equals("map")) s.rows.add(line);
            else{
                String[] w = line.trim().split("\\s+");
                s.places.add(new Place(w[0], Integer.parseInt(w[1]), Integer.parseInt(w[2]), w.length > 3 ? Integer.parseInt(w[3]) : 0, w.length > 4 ? w[4] : null));
            }
        }
        return s;
    }

    /** The map and the layout written back, as a spec file reads. */
    public String write(){
        StringBuilder b = new StringBuilder("map\n");
        for(String r : rows) b.append(r).append('\n');
        b.append("end\nplace\n");
        for(Place p : places) b.append(p).append('\n');
        b.append("end\nseconds ").append(seconds).append('\n');
        return b.toString();
    }

    public static Block floorOf(char c){
        return switch(c){
            case 'v' -> Blocks.rhyoliteVent;
            case '~' -> Blocks.slag;
            case 'a' -> Blocks.arkyciteFloor;
            default -> Blocks.rhyolite;
        };
    }

    public static Block wallOf(char c){
        return switch(c){
            case '#', 'B', 'T', 'H' -> Blocks.rhyoliteWall;
            case 'S' -> Blocks.yellowStoneWall;
            case 'D' -> Blocks.duneWall;
            case 'G' -> Blocks.graphiticWall;
            default -> Blocks.air;
        };
    }

    public static Block oreOf(char c){
        return switch(c){
            case 'B' -> Blocks.wallOreBeryllium;
            case 'T' -> Blocks.wallOreTungsten;
            case 'H' -> Blocks.wallOreThorium;
            case 'b' -> Blocks.oreBeryllium;
            case 't' -> Blocks.oreTungsten;
            case 'h' -> Blocks.oreThorium;
            default -> Blocks.air;
        };
    }
}
