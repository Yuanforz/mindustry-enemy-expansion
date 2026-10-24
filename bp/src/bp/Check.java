package bp;

import arc.files.*;
import arc.math.geom.*;
import arc.struct.*;
import mindustry.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.heat.*;
import mindustry.world.blocks.liquid.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.sandbox.*;
import mindustry.world.blocks.storage.*;
import mindustry.world.blocks.units.*;
import mindustry.world.consumers.*;

import java.util.*;

/**
 * The rule checker: a layout put down in the game's world (Scratch), and asked of the game's own code what each thing
 * made does there. Every item a block makes or digs is followed from it: the block hands it to every neighbour that takes
 * it (Building.dump goes round them all); a duct hands it on to what is in front if that takes it, a bridge to the bridge
 * it reaches or, at the end, in front, a router to its front and sides (by its filter), an overflow duct to its front or
 * sides; whatever takes it and is no carrier is where it ends up. Where a carrier holding it has nothing it can hand it
 * to, the line stops there (a duct holds one item at a time, first in first out: what stands at its head holds up all
 * behind). Liquids the same way, by the game's acceptLiquid, with a crafter's output sides; power: whether a block that
 * uses it is on a grid with something that makes it; heat: what each block that needs it gets from the heat blocks
 * whose faces point at it (Building.calculateHeat), heaters taken at full, redirectors passing on all they gather.
 *
 * What "takes" means is the game's acceptItem / acceptLiquid of a block just put down, holding nothing.
 */
public final class Check{
    final Scratch world;
    public final List<String> lines = new ArrayList<>();
    public int jams, unpowered, cold, mixed;
    /** Blocks the game would not let be built where they stand, or not known. */
    public int misplaced;
    /** Where each thing made reaches: "thing from block@x,y" to the blocks it ends in. */
    public final Map<String, Set<String>> reach = new HashMap<>();

    public Check(Scratch world){
        this.world = world;
    }

    // ------------------------------------------------------------------ items

    /** What a block makes or digs, item by item (empty if none). */
    static List<Item> itemsMade(Building b){
        List<Item> out = new ArrayList<>();
        if(b instanceof ItemSource.ItemSourceBuild s){
            if(s.outputItem != null) out.add(s.outputItem);
        }else if(b.block instanceof GenericCrafter g){
            if(g.outputItems != null) for(ItemStack st : g.outputItems) out.add(st.item);
        }else if(b instanceof Drill.DrillBuild d){
            if(d.dominantItem != null && d.dominantItems > 0) out.add(d.dominantItem);
        }else if(b.block instanceof BeamDrill bd){
            Item i = beamDrillItem(bd, b);
            if(i != null) out.add(i);
        }else if(b.block instanceof WallCrafter w){
            out.add(w.output);
        }
        return out;
    }

    /** What a beam drill digs where it stands (BeamDrill.updateFacing: each lane's first solid tile; two items, none). */
    static Item beamDrillItem(BeamDrill bd, Building b){
        Item found = null;
        int dx = Geometry.d4x(b.rotation), dy = Geometry.d4y(b.rotation);
        Point2 p = new Point2();
        for(int lane = 0; lane < bd.size; lane++){
            bd.nearbySide(b.tileX(), b.tileY(), b.rotation, lane, p);
            for(int i = 0; i < bd.range; i++){
                Tile other = Vars.world.tile(p.x + dx * i, p.y + dy * i);
                if(other == null) break;
                if(other.solid()){
                    Item drop = other.wallDrop();
                    if(drop != null && drop.hardness <= bd.tier && (bd.blockedItems == null || !bd.blockedItems.contains(drop))){
                        if(found != null && found != drop) return null;
                        found = drop;
                    }
                    break;
                }
            }
        }
        return found;
    }

    static boolean carrier(Building b){
        return b.block instanceof Duct || b.block instanceof DuctBridge || b.block instanceof DuctRouter || b.block instanceof OverflowDuct || b.block instanceof StackConveyor || b.block instanceof StackRouter;
    }

    /** Whether a block takes an item from another, as the game has it for one holding nothing (with a bridge's incoming sides). */
    static boolean takes(Building to, Building from, Item item){
        if(to == null || to.team != from.team) return false;
        if(to instanceof DirectionBridge.DirectionBridgeBuild bridge && to.block instanceof DuctBridge){
            //(a bridge takes nothing on the side another bridge comes into it from: DuctBridge.acceptItem, its occupied
            //sides, which the game fills as it runs)
            if(bridge.findLink() == null) return false;
            int rel = to.relativeToEdge(from.tile);
            if(rel == to.rotation) return false;
            for(Building o : incoming(bridge)) if(o.rotation == (rel + 2) % 4) return false;
            return to.items.total() < to.block.itemCapacity;
        }
        return to.acceptItem(from, item);
    }

    /** The bridges that reach this one. */
    static List<Building> incoming(DirectionBridge.DirectionBridgeBuild b){
        List<Building> out = new ArrayList<>();
        for(int dir = 0; dir < 4; dir++){
            for(int i = 1; i <= ((DirectionBridge)b.block).range; i++){
                Tile t = b.tile.nearby(Geometry.d4x(dir) * i, Geometry.d4y(dir) * i);
                if(t != null && t.build instanceof DirectionBridge.DirectionBridgeBuild o && o.block == b.block && o.findLink() == b){
                    out.add(o);
                    break;
                }
            }
        }
        return out;
    }

    /** Where a carrier holding an item can hand it (empty: it stops there, and the line behind it). */
    static List<Building> handsTo(Building c, Item item){
        List<Building> out = new ArrayList<>();
        if(c.block instanceof DuctBridge && c instanceof DirectionBridge.DirectionBridgeBuild b){
            var link = b.findLink();
            if(link != null){
                //(to the bridge it reaches, as the game hands it, past what that one would take from a neighbour)
                out.add(link);
                return out;
            }
            Building f = c.front();
            if(takes(f, c, item)) out.add(f);
            return out;
        }
        if(c.block instanceof DuctRouter && c instanceof DuctRouter.DuctRouterBuild r){
            for(int dir = 0; dir < 4; dir++){
                if(dir == (c.rotation + 2) % 4) continue;
                //(a filter's item goes on only ahead, everything else only to the sides)
                if(r.sortItem != null && (item == r.sortItem) != (dir == c.rotation)) continue;
                Building o = c.nearby(dir);
                if(takes(o, c, item)) out.add(o);
            }
            return out;
        }
        if(c instanceof StackConveyor.StackConveyorBuild sc){
            //(a surge line hands a whole stack to the surge conveyor ahead, taking or not, unless it is the last, which
            //hands ahead as a duct does: StackConveyor.updateTile; its state the game's, public as `state`)
            Building f = c.front();
            if(sc.state != 2){
                if(f instanceof StackConveyor.StackConveyorBuild && f.team == c.team) out.add(f);
                return out;
            }
            if(takes(f, c, item)) out.add(f);
            return out;
        }
        if(c.block instanceof OverflowDuct){
            //(ahead, or to a side while ahead takes nothing: both can be where it goes)
            for(int dir : new int[]{c.rotation, (c.rotation + 1) % 4, (c.rotation + 3) % 4}){
                Building o = c.nearby(dir);
                if(takes(o, c, item)) out.add(o);
            }
            return out;
        }
        Building f = c.front();
        if(takes(f, c, item)) out.add(f);
        return out;
    }

    /** Every block that takes this item from this one when it hands it out (Building.dump: all neighbours, round). */
    static List<Building> dumpsTo(Building b, Item item){
        List<Building> out = new ArrayList<>();
        for(Building o : b.proximity) if(takes(o, b, item)) out.add(o);
        return out;
    }

    void items(){
        Map<Building, Set<Item>> through = new LinkedHashMap<>();
        for(Building b : world.buildings()){
            for(Item item : itemsMade(b)){
                Set<Building> ends = new LinkedHashSet<>();
                List<String> stops = new ArrayList<>();
                Set<Building> seen = new HashSet<>();
                ArrayDeque<Building> queue = new ArrayDeque<>();
                for(Building o : dumpsTo(b, item)){
                    if(carrier(o)) queue.add(o);
                    else ends.add(o);
                }
                while(!queue.isEmpty()){
                    Building c = queue.poll();
                    if(!seen.add(c)) continue;
                    through.computeIfAbsent(c, k -> new LinkedHashSet<>()).add(item);
                    List<Building> next = handsTo(c, item);
                    if(next.isEmpty()) stops.add(Scratch.where(c) + " (ahead: " + Scratch.where(c.front()) + ")");
                    for(Building o : next){
                        if(carrier(o)) queue.add(o);
                        else ends.add(o);
                    }
                }
                StringBuilder s = new StringBuilder(String.format("item %s from %s -> ", item.name, Scratch.where(b)));
                if(ends.isEmpty() && stops.isEmpty()) s.append("nowhere (nothing about it takes it: it stays in it)");
                List<String> to = new ArrayList<>();
                for(Building e : ends) to.add(Scratch.where(e));
                s.append(String.join(", ", to));
                if(!stops.isEmpty()){
                    jams++;
                    s.append(to.isEmpty() ? "" : "; ").append("STOPS at ").append(String.join(", ", stops));
                }
                lines.add(s.toString());
                Set<String> got = reach.computeIfAbsent(item.name + " from " + Scratch.where(b), k -> new HashSet<>());
                for(Building e : ends) got.add(Scratch.where(e));
                //(the carriers it passes count as reached too: a line laid to a module's port ends at a carrier)
                for(Building c : seen) got.add(Scratch.where(c));
            }
        }
        //lines that carry more than one item: whichever comes first to what takes only the other holds the line
        for(var e : through.entrySet()) if(e.getValue().size() > 1){
            List<String> names = new ArrayList<>();
            for(Item i : e.getValue()) names.add(i.name);
            lines.add("mixed: " + Scratch.where(e.getKey()) + " carries " + names);
        }
    }

    /** Where an item handed to a carrier goes on to, by the same rules (for a line that starts at a carrier: a module's port). */
    public Set<String> traceItem(Building start, Item item){
        Set<String> ends = new LinkedHashSet<>();
        Set<Building> seen = new HashSet<>();
        ArrayDeque<Building> queue = new ArrayDeque<>();
        queue.add(start);
        while(!queue.isEmpty()){
            Building c = queue.poll();
            if(!seen.add(c)) continue;
            ends.add(Scratch.where(c));
            for(Building o : handsTo(c, item)){
                if(carrier(o)) queue.add(o);
                else ends.add(Scratch.where(o));
            }
        }
        return ends;
    }

    public Set<String> traceLiquid(Building start, Liquid liquid){
        Set<String> ends = new LinkedHashSet<>();
        Set<Building> seen = new HashSet<>();
        ArrayDeque<Building> queue = new ArrayDeque<>();
        queue.add(start);
        while(!queue.isEmpty()){
            Building c = queue.poll();
            if(!seen.add(c)) continue;
            ends.add(Scratch.where(c));
            for(Building o : liquidTo(c, liquid)){
                if(liquidCarrier(o)) queue.add(o);
                else ends.add(Scratch.where(o));
            }
        }
        return ends;
    }

    // ------------------------------------------------------------------ liquids

    static List<Object[]> liquidsMade(Building b){
        List<Object[]> out = new ArrayList<>();
        if(b instanceof LiquidSource.LiquidSourceBuild s){
            if(s.source != null) out.add(new Object[]{s.source, -1});
        }else if(b.block instanceof GenericCrafter g && g.outputLiquids != null){
            for(int i = 0; i < g.outputLiquids.length; i++) out.add(new Object[]{g.outputLiquids[i].liquid, g.liquidOutputDirections.length > i ? g.liquidOutputDirections[i] : -1});
        }else if(b.block instanceof ThermalGenerator t && t.outputLiquid != null){
            out.add(new Object[]{t.outputLiquid.liquid, -1});
        }else if(b.block instanceof ConsumeGenerator cg && cg.outputLiquid != null){
            out.add(new Object[]{cg.outputLiquid.liquid, -1});
        }else if(b instanceof Pump.PumpBuild p){
            if(p.liquidDrop != null) out.add(new Object[]{p.liquidDrop, -1});
        }
        return out;
    }

    static boolean liquidCarrier(Building b){
        return b.block instanceof Conduit || b.block instanceof LiquidRouter || b.block instanceof LiquidBridge || b.block instanceof DirectionLiquidBridge || b.block instanceof LiquidJunction;
    }

    static boolean takesLiquid(Building to, Building from, Liquid liquid){
        return to != null && to.team == from.team && to.acceptLiquid(from, liquid);
    }

    static List<Building> liquidTo(Building c, Liquid liquid){
        List<Building> out = new ArrayList<>();
        if(c.block instanceof DirectionLiquidBridge && c instanceof DirectionBridge.DirectionBridgeBuild b){
            var link = b.findLink();
            if(link != null){
                out.add(link);
                return out;
            }
            Building f = c.front();
            if(takesLiquid(f, c, liquid)) out.add(f);
            return out;
        }
        if(c.block instanceof LiquidRouter){
            for(Building o : c.proximity) if(takesLiquid(o, c, liquid)) out.add(o);
            return out;
        }
        Building f = c.front();
        if(takesLiquid(f, c, liquid)) out.add(f);
        return out;
    }

    void liquids(){
        Map<Building, Set<Liquid>> through = new LinkedHashMap<>();
        for(Building b : world.buildings()){
            for(Object[] made : liquidsMade(b)){
                Liquid liquid = (Liquid)made[0];
                int dir = (Integer)made[1];
                Set<Building> ends = new LinkedHashSet<>();
                List<String> stops = new ArrayList<>();
                Set<Building> seen = new HashSet<>();
                //(each carrier with the block it came from: a junction hands on straight, to the side across from that one)
                ArrayDeque<Building[]> queue = new ArrayDeque<>();
                Set<String> seenJunction = new HashSet<>();
                for(Building o : b.proximity){
                    //(a crafter puts each liquid out of its side only: GenericCrafter.liquidOutputDirections)
                    if(dir != -1 && (dir + b.rotation) % 4 != b.relativeTo(o)) continue;
                    if(!takesLiquid(o, b, liquid)) continue;
                    if(liquidCarrier(o)) queue.add(new Building[]{o, b});
                    else ends.add(o);
                }
                while(!queue.isEmpty()){
                    Building[] cq = queue.poll();
                    Building c = cq[0], prev = cq[1];
                    List<Building> next;
                    if(c.block instanceof LiquidJunction){
                        //(a junction keeps what crosses it apart: not mixed, each way followed on its own)
                        int in = c.relativeTo(prev);
                        if(in < 0 || !seenJunction.add(c.pos() + ":" + in)) continue;
                        seen.add(c);
                        Building o = c.nearby((in + 2) % 4);
                        next = takesLiquid(o, c, liquid) ? List.of(o) : List.of();
                    }else{
                        if(!seen.add(c)) continue;
                        through.computeIfAbsent(c, k -> new LinkedHashSet<>()).add(liquid);
                        next = liquidTo(c, liquid);
                    }
                    if(next.isEmpty() && !(c.block instanceof LiquidRouter)) stops.add(Scratch.where(c) + " (ahead: " + Scratch.where(c.front()) + (c.block instanceof Conduit cd && cd.leaks && c.front() == null ? ", it leaks there" : "") + ")");
                    for(Building o : next){
                        if(liquidCarrier(o)) queue.add(new Building[]{o, c});
                        else if(o != b) ends.add(o);
                    }
                }
                List<String> to = new ArrayList<>();
                for(Building e : ends) to.add(Scratch.where(e));
                reach.computeIfAbsent(liquid.name + " from " + Scratch.where(b), k -> new HashSet<>()).addAll(to);
                for(Building c : seen) reach.get(liquid.name + " from " + Scratch.where(b)).add(Scratch.where(c));
                lines.add(String.format("liquid %s from %s -> %s%s", liquid.name, Scratch.where(b), to.isEmpty() ? "nowhere" : String.join(", ", to), stops.isEmpty() ? "" : "; ENDS at " + String.join(", ", stops)));
            }
        }
        for(var e : through.entrySet()) if(e.getValue().size() > 1){
            mixed++;
            List<String> names = new ArrayList<>();
            for(Liquid l : e.getValue()) names.add(l.name);
            lines.add("MIXED liquids: " + Scratch.where(e.getKey()) + " gets " + names + " (a conduit holds one liquid: the second is refused)");
        }
    }

    // ------------------------------------------------------------------ power

    void power(){
        //(a beam node finds what it reaches as the game runs, BeamNode.updateTile; here at once)
        for(Building b : world.buildings()) if(b instanceof BeamNode.BeamNodeBuild n) n.updateDirections();
        for(Building b : world.buildings()){
            if(b.block.consPower == null || b.power == null || b.block instanceof PowerNode) continue;
            if(b.block.consPower.buffered) continue;
            PowerGraph g = b.power.graph;
            boolean made = g.producers.size > 0 || g.batteries.contains(o -> o.block instanceof PowerSource);
            if(!made){
                unpowered++;
                lines.add("NO POWER: " + Scratch.where(b) + " (its grid has " + g.all.size + " blocks and nothing that makes power)");
            }
        }
    }

    // ------------------------------------------------------------------ heat

    /** Heat a heat block gives, taken at full (a heater at its heatOutput, a redirector all it gathers, a router a third a face). */
    float heatOf(Building b, Set<Building> path){
        if(b.block instanceof HeatProducer hp) return hp.heatOutput;
        if(b.block instanceof HeaterGenerator hg) return hg.heatOutput;
        if(b.block instanceof HeatConductor && path.add(b)){
            float h = heatInto(b, path);
            path.remove(b);
            return h;
        }
        if(b.block.name.equals("heat-source")) return 1000f;
        return 0f;
    }

    /** What a block gets from the heat blocks about it (Building.calculateHeat). */
    float heatInto(Building b, Set<Building> path){
        float heat = 0f;
        for(Building o : b.proximity){
            if(!(o instanceof HeatBlock)) continue;
            boolean split = o.block instanceof HeatConductor hc && hc.splitHeat;
            //(heaters and redirectors must face it; a router must not face away from it from behind)
            if(o.block.rotate && !(!split && (b.relativeTo(o) + 2) % 4 == o.rotation || split && b.relativeTo(o) != o.rotation)) continue;
            if(path.contains(o)) continue;
            float diff = Math.min(Math.abs(o.x - b.x), Math.abs(o.y - b.y)) / Vars.tilesize;
            int contact = Math.min((int)(b.block.size / 2f + o.block.size / 2f - diff), Math.min(o.block.size, b.block.size));
            float add = heatOf(o, path) / o.block.size * contact;
            if(split) add /= 3f;
            heat += add;
        }
        return heat;
    }

    void heat(){
        for(Building b : world.buildings()){
            if(!(b instanceof HeatConsumer hc) || b.block instanceof HeatConductor) continue;
            float need = hc.heatRequirement();
            if(need <= 0f) continue;
            Set<Building> path = new HashSet<>();
            path.add(b);
            float got = heatInto(b, path);
            if(got < need - 1e-3f) cold++;
            lines.add(String.format("%sheat %s: gets %.1f of %.1f", got < need - 1e-3f ? "COLD " : "", Scratch.where(b), got, need));
        }
    }

    /**
     * Payloads: a block that makes them (a unit factory, a refabricator, a constructor) hands each out of its front
     * (PayloadBlock.moveOutPayload); a payload conveyor to what is ahead of it (its next, PayloadConveyor), the end to
     * whatever is ahead that takes payloads.
     */
    void payloads(){
        for(Building b : world.buildings()){
            boolean makes = b.block instanceof mindustry.world.blocks.units.UnitFactory || b.block instanceof mindustry.world.blocks.units.Reconstructor || b.block instanceof mindustry.world.blocks.payloads.Constructor;
            if(!makes) continue;
            Set<String> got = reach.computeIfAbsent("payload from " + Scratch.where(b), k -> new HashSet<>());
            Building at = b.front();
            Set<Building> seen = new HashSet<>();
            while(at != null && at.block instanceof mindustry.world.blocks.payloads.PayloadConveyor && !(at.block instanceof mindustry.world.blocks.payloads.PayloadRouter) && seen.add(at)){
                at = ((mindustry.world.blocks.payloads.PayloadConveyor.PayloadConveyorBuild)at).next;
            }
            String end = at == null ? "nowhere" : Scratch.where(at);
            if(at != null) got.add(end);
            lines.add("payload from " + Scratch.where(b) + " -> " + end + (seen.isEmpty() ? "" : " (" + seen.size() + " conveyors)"));
        }
    }

    public Check run(){
        lines.addAll(world.problems);
        misplaced = world.problems.size();
        payloads();
        items();
        liquids();
        power();
        heat();
        return this;
    }

    public String report(){
        StringBuilder s = new StringBuilder(String.format("CHECK %s: %d lines that stop, %d mixed liquids, %d without power, %d short of heat, %d that cannot be built%n", world.spec.name, jams, mixed, unpowered, cold, misplaced));
        for(String l : lines) s.append("  ").append(l).append('\n');
        return s.toString();
    }

    /** Usage: bp.Check data-dir spec-file... */
    public static void main(String[] args){
        Game.boot(args[0]);
        for(int i = 1; i < args.length; i++){
            Spec spec = Spec.read(new Fi(args[i]));
            System.out.print(new Check(new Scratch(spec)).run().report());
        }
        System.exit(0);
    }
}
