package bp;

import arc.files.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.liquid.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.sandbox.*;
import mindustry.world.blocks.storage.*;
import mindustry.world.meta.*;

import java.util.*;

/**
 * The blocks that carry things on Erekir, found among the game's blocks by what they are (their class), with what the
 * game gives them (range, laser range, solidity, cost), and how much each carries a second, measured: a straight line of
 * it from a source to a sink run in the game's world for a while (a duct's or a conduit's rate comes of how the line
 * hands on, not of one field). Measured once for a game version and kept in the data directory.
 */
public final class Carriers{
    /** What a carrier does in a line, by its class (Board's rules are written for these). */
    public enum Role{duct, armored, router, bridge, surge, conduit, liquidBridge, liquidJunction, liquidRouter, node, powerSource, payload, payloadRouter}

    public final EnumMap<Role, List<Block>> blocks = new EnumMap<>(Role.class);
    /** Items or liquid a second a line of it carries (for a surge line, without power; powered in surgePowered). */
    public final Map<Block, Float> rate = new HashMap<>();
    public final Map<Block, Float> surgePowered = new HashMap<>();

    static Role role(Block b){
        if(b instanceof mindustry.world.blocks.payloads.PayloadRouter) return Role.payloadRouter;
        if(b instanceof mindustry.world.blocks.payloads.PayloadConveyor) return Role.payload;
        if(b instanceof StackConveyor s) return s.outputRouter ? null : Role.surge;
        if(b instanceof StackRouter) return null;
        if(b instanceof Duct d) return d.armored ? Role.armored : Role.duct;
        if(b instanceof OverflowDuct) return null;
        if(b instanceof DuctRouter) return Role.router;
        if(b instanceof DuctBridge) return Role.bridge;
        if(b instanceof ArmoredConduit) return Role.conduit;
        if(b instanceof DirectionLiquidBridge) return Role.liquidBridge;
        if(b instanceof LiquidJunction) return Role.liquidJunction;
        if(b instanceof LiquidRouter) return Role.liquidRouter;
        if(b instanceof BeamNode) return Role.node;
        if(b instanceof PowerSource) return Role.powerSource;
        return null;
    }

    public Carriers(String dataDir){
        for(Block b : Vars.content.blocks()){
            Role r = role(b);
            if(r == null) continue;
            boolean sandbox = r == Role.powerSource;
            if(!sandbox && (!b.isOnPlanet(Planets.erekir) || b.buildVisibility == BuildVisibility.sandboxOnly)) continue;
            blocks.computeIfAbsent(r, k -> new ArrayList<>()).add(b);
        }
        measure(dataDir);
    }

    public List<Block> of(Role r){
        return blocks.getOrDefault(r, List.of());
    }

    /** The one of a role the weights price lowest (null if there is none). */
    public Block cheapest(Role r, Weights w){
        Block best = null;
        double bc = Double.MAX_VALUE;
        for(Block b : of(r)){
            double c = w.cost(b);
            if(c < bc){
                bc = c;
                best = b;
            }
        }
        return best;
    }

    public static int range(Block b){
        if(b instanceof DirectionBridge d) return d.range;
        if(b instanceof BeamNode n) return n.range;
        return 0;
    }

    // ------------------------------------------------------------------ measuring

    void measure(String dataDir){
        Fi file = new Fi(dataDir).child("carriers-" + Version.build + "." + Version.revision + ".txt");
        if(file.exists()){
            for(String line : file.readString().split("\n")){
                String[] w = line.trim().split("\\s+");
                if(w.length < 2) continue;
                Block b = Vars.content.block(w[0]);
                if(b == null) continue;
                rate.put(b, Float.parseFloat(w[1]));
                if(w.length > 2) surgePowered.put(b, Float.parseFloat(w[2]));
            }
            boolean all = true;
            for(Role r : new Role[]{Role.duct, Role.armored, Role.bridge, Role.surge, Role.conduit, Role.liquidBridge}) for(Block b : of(r)) if(!rate.containsKey(b)) all = false;
            if(all) return;
        }
        StringBuilder out = new StringBuilder();
        for(Role r : new Role[]{Role.duct, Role.armored, Role.bridge, Role.surge, Role.conduit, Role.liquidBridge}){
            for(Block b : of(r)){
                float plain = line(b, r, false);
                rate.put(b, plain);
                out.append(b.name).append(' ').append(plain);
                if(r == Role.surge){
                    float powered = line(b, r, true);
                    surgePowered.put(b, powered);
                    out.append(' ').append(powered);
                }
                out.append('\n');
            }
        }
        file.writeString(out.toString());
    }

    /** What a straight line of a carrier passes a second, run in the game's world. */
    float line(Block b, Role r, boolean powered){
        boolean liquid = r == Role.conduit || r == Role.liquidBridge;
        int len = 12, w = len + 8, h = 7, y = 3;
        Spec s = new Spec();
        for(int i = 0; i < h; i++) s.rows.add(".".repeat(w));
        Block sink = liquid ? biggest(of(Role.liquidRouter)) : Blocks.coreBastion;
        s.places.add(new Spec.Place(liquid ? "liquid-source" : "item-source", 0, y, 0, liquid ? "water" : "beryllium"));
        if(r == Role.bridge || r == Role.liquidBridge){
            //(bridges each reaching the next at its full range, the last one handing on ahead)
            int step = range(b);
            int x = 1;
            for(; x + step < 1 + len; x += step) s.places.add(new Spec.Place(b.name, x, y, 0, null));
            s.places.add(new Spec.Place(b.name, x, y, 0, null));
            len = x;
        }else{
            for(int x = 1; x <= len; x++) s.places.add(new Spec.Place(b.name, x, y, 0, null));
        }
        int sx = len + 1 + (sink.size - 1) / 2;
        s.places.add(new Spec.Place(sink.name, sx, y, 0, null));
        if(powered) s.places.add(new Spec.Place("power-source", 1, y + 1, 0, null));
        Scratch world = new Scratch(s);
        Vars.state.rules.coreIncinerates = true;
        Time.setDeltaProvider(() -> 1f);
        Building end = Vars.world.build(sx, y);
        int warm = 600, frames = 1800;
        float got = 0f;
        for(int f = 0; f < warm + frames; f++){
            Time.update();
            Vars.logic.update();
            if(end == null) break;
            float here = liquid ? end.liquids.get(Liquids.water) : end.items.total();
            if(f >= warm) got += here;
            if(liquid) end.liquids.clear();
            else end.items.clear();
        }
        Vars.logic.reset();
        return got / (frames / 60f);
    }

    static Block biggest(List<Block> bs){
        Block best = null;
        for(Block b : bs) if(best == null || b.liquidCapacity > best.liquidCapacity) best = b;
        return best;
    }

    @Override
    public String toString(){
        StringBuilder s = new StringBuilder("carriers:\n");
        for(var e : blocks.entrySet()){
            s.append("  ").append(e.getKey()).append(':');
            for(Block b : e.getValue()){
                s.append(' ').append(b.name);
                if(rate.containsKey(b)) s.append(String.format(" (%.2f/s%s)", rate.get(b), surgePowered.containsKey(b) ? String.format(", powered %.2f/s", surgePowered.get(b)) : ""));
                if(range(b) > 0) s.append(" range ").append(range(b));
                if(b.solid) s.append(" solid");
            }
            s.append('\n');
        }
        return s.toString();
    }

    public static void main(String[] args){
        Game.boot(args[0]);
        System.out.print(new Carriers(args[0]));
        System.exit(0);
    }
}
