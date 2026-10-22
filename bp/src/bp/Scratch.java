package bp;

import arc.struct.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.core.GameState.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;

import java.util.*;

/**
 * A spec's map loaded into the game's world (no window, no time passing) and its layout put down there, so that the
 * game's own code can be asked about it: what a block takes from which neighbour, where a duct leads, which bridge a
 * bridge reaches, which power grid a building is on. Putting a block down runs what the game runs then (its neighbours
 * found, its links made), and nothing more: nothing moves.
 */
public final class Scratch{
    public static final Team team = Team.sharded;
    public final Spec spec;
    /** The blocks put down, in the spec's order (null where one could not be). */
    public final List<Building> built = new ArrayList<>();
    public final List<String> problems = new ArrayList<>();

    public Scratch(Spec spec){
        this.spec = spec;
        int width = spec.width(), height = spec.height();
        Vars.logic.reset();
        Rules rules = new Rules();
        Planets.erekir.applyRules(rules, true);
        rules.waves = false;
        rules.fog = false;
        rules.staticFog = false;
        rules.infiniteResources = true;
        Vars.state.rules = rules;
        if(spec.world != null){
            //(a map saved by the game: its ground, every building on it taken away)
            mindustry.io.SaveIO.load(new arc.files.Fi(spec.world));
            spec.worldCores.clear();
            for(Tile t : Vars.world.tiles) if(t.build instanceof mindustry.world.blocks.storage.CoreBlock.CoreBuild c && t.isCenter() && c.team == Vars.state.rules.defaultTeam) spec.worldCores.add(new Spec.Place(c.block.name, t.x, t.y, 0, null));
            Vars.state.rules = rules;
            for(Tile t : Vars.world.tiles) if(t.build != null && t.isCenter()) t.setBlock(Blocks.air);
            for(var u : mindustry.gen.Groups.unit.copy(new arc.struct.Seq<>())) u.remove();
            spec.worldW = Vars.world.width();
            spec.worldH = Vars.world.height();
        }else Vars.world.loadGenerator(width, height, tiles -> {
            for(int y = 0; y < height; y++) for(int x = 0; x < width; x++){
                char c = spec.at(x, y);
                tiles.set(x, y, new Tile(x, y, Spec.floorOf(c), Spec.oreOf(c), Spec.wallOf(c)));
            }
        });
        Vars.state.rules = rules;
        Vars.state.set(State.playing);
        for(Spec.Place p : spec.places){
            Block block = Vars.content.block(p.block());
            Tile tile = Vars.world.tile(p.x(), p.y());
            if(block == null || tile == null){
                problems.add(block == null ? "no block " + p.block() : p.block() + " at " + p.x() + "," + p.y() + " is off the map");
                built.add(null);
                continue;
            }
            tile.setNet(block, team, p.rotation());
            built.add(tile.build);
        }
        for(int i = 0; i < spec.places.size(); i++){
            Spec.Place p = spec.places.get(i);
            Building b = built.get(i);
            if(p.config() == null || b == null) continue;
            Object value = configOf(p.config());
            if(value == null) problems.add("config " + p.config() + " of " + p.block() + " not known");
            else b.configureAny(value);
        }
    }

    /** A config written in a spec: an item, a liquid, a block or a unit by name. */
    public static Object configOf(String s){
        Item item = Vars.content.item(s);
        if(item != null) return item;
        Liquid liquid = Vars.content.liquid(s);
        if(liquid != null) return liquid;
        Block block = Vars.content.block(s);
        if(block != null) return block;
        UnitType unit = Vars.content.unit(s);
        return unit;
    }

    /** Every building put down, once each (a block over several tiles is one). */
    public Seq<Building> buildings(){
        Seq<Building> out = new Seq<>();
        for(Building b : built) if(b != null && !out.contains(b, true)) out.add(b);
        return out;
    }

    /** Where a building is, in words. */
    public static String where(Building b){
        return b == null ? "nothing" : b.block.name + "@" + b.tileX() + "," + b.tileY();
    }
}
