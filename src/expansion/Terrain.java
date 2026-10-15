package expansion;

import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.game.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.production.*;
import mindustry.world.meta.*;

import static mindustry.Vars.*;

/** What the ground offers: vents to put generators on, and ore walls for drills. */
public class Terrain{
    private final ObjectMap<Block, IntSeq> vents = new ObjectMap<>();

    public void clear(){
        vents.clear();
    }

    /** Every tile a generator or condenser of this kind could be centred on, as far as the ground goes. Worked out once per map for each kind. */
    public IntSeq vents(Block generator, Team team){
        IntSeq out = vents.get(generator);
        if(out == null){
            vents.put(generator, out = new IntSeq());
            //only ground that gives it something is looked at closely, which is a tiny part of a map
            Attribute attribute = generator instanceof ThermalGenerator thermal ? thermal.attribute : generator instanceof AttributeCrafter crafter ? crafter.attribute : null;
            for(Tile tile : world.tiles){
                if((attribute == null || tile.floor().attributes.get(attribute) > 0f) && generator.canPlaceOn(tile, team, 0)) out.add(tile.pos());
            }
        }
        return out;
    }

    /**
     * How many of a drill's beams would end on ore it can mine if it stood here, worked out the way the drill does it.
     * Tiles in the given set count as built on, so a beam that would have to pass through one of them reaches nothing.
     */
    public static int oreBeams(BeamDrill drill, int x, int y, int rotation, IntSet blocked){
        int dx = Geometry.d4x(rotation), dy = Geometry.d4y(rotation), count = 0;
        for(int beam = 0; beam < drill.size; beam++){
            drill.nearbySide(x, y, rotation, beam, Tmp.p1);
            int startX = Tmp.p1.x, startY = Tmp.p1.y;
            for(int i = 0; i < drill.range; i++){
                Tile other = world.tile(startX + dx * i, startY + dy * i);
                if(other == null) continue;
                if(blocked.contains(other.pos())) break;
                if(other.solid()){
                    Item drop = other.wallDrop();
                    if(drop != null && drop.hardness <= drill.tier && (drill.blockedItems == null || !drill.blockedItems.contains(drop))) count++;
                    break;
                }
            }
        }
        return count;
    }

    /** Whether the ground a block lets its units out onto is open: three rows in front of it, nothing built and nothing solid. */
    public static boolean frontOpen(Block block, int x, int y, int rotation, IntSet blocked){
        int dx = Geometry.d4x(rotation), dy = Geometry.d4y(rotation);
        for(int lane = 0; lane < block.size; lane++){
            block.nearbySide(x, y, rotation, lane, Tmp.p1);
            int startX = Tmp.p1.x, startY = Tmp.p1.y;
            for(int i = 0; i < 3; i++){
                Tile other = world.tile(startX + dx * i, startY + dy * i);
                if(other == null || other.solid() || other.build != null || blocked.contains(other.pos())) return false;
            }
        }
        return true;
    }
}
