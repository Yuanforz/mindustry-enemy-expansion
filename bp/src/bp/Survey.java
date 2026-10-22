package bp;

import arc.files.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.heat.*;
import mindustry.world.blocks.liquid.*;
import mindustry.world.blocks.payloads.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.storage.*;
import mindustry.world.blocks.units.*;
import mindustry.world.consumers.*;
import mindustry.world.meta.*;

import java.util.*;

/**
 * How a layout is made, counted the same way whoever made it: a shared schematic, one of our modules, or a base grown on
 * a map (a ledger). Its machines and the tiles they cover; the pieces that carry things between them (ducts, conduits,
 * bridges, routers, unloaders, containers, payload conveyors, heat redirectors), the power nodes, the walls; how much of
 * its box it fills; and how often a machine hands what it makes straight into a neighbour that takes it, with no line
 * between them: an item or liquid a crafter makes into a block that uses it (a crafter dumps into every neighbour that
 * accepts), heat out of a heater's front into what is heated or a redirector, a unit factory's payload out of its front
 * into the next stage.
 *
 * Usage: bp.Survey data-dir out.tsv (file.msch | ledger:file)...
 */
public final class Survey{
    /** A block as it stands: at its centre tile (the game's way), turned. */
    record B(Block block, int x, int y, int rot){
        int x0(){
            return Geo.lo(x, block.size);
        }

        int y0(){
            return Geo.lo(y, block.size);
        }

        int x1(){
            return Geo.hi(x, block.size);
        }

        int y1(){
            return Geo.hi(y, block.size);
        }
    }

    static String cat(Block b){
        if(b.buildVisibility == BuildVisibility.sandboxOnly) return "port";
        if(b instanceof CoreBlock) return "core";
        if(b instanceof Wall) return "wall";
        if(b instanceof PowerNode || b instanceof BeamNode || b instanceof Battery || b instanceof PowerDiode) return "power";
        if(b instanceof Duct || b instanceof DuctRouter || b instanceof DuctBridge || b instanceof OverflowDuct || b instanceof DuctJunction
        || b instanceof DirectionalUnloader || b instanceof StackConveyor || b instanceof StackRouter || b instanceof Conveyor
        || b instanceof Junction || b instanceof mindustry.world.blocks.distribution.Router || b instanceof Sorter || b instanceof OverflowGate || b instanceof ItemBridge
        || b instanceof Unloader || b instanceof MassDriver) return "items";
        if(b instanceof StorageBlock) return "buffer";
        if(b instanceof Conduit || b instanceof LiquidRouter || b instanceof LiquidJunction || b instanceof LiquidBridge
        || b instanceof DirectionLiquidBridge) return "liquids";
        if(b instanceof PayloadConveyor || b instanceof PayloadMassDriver || b instanceof PayloadLoader) return "payloads";
        if(b instanceof HeatConductor) return "heat";
        return "machine";
    }

    static boolean takesHeat(Block b){
        return b instanceof HeatCrafter || b instanceof HeatConductor || b instanceof VariableReactor || (b instanceof Turret t && t.heatRequirement > 0);
    }

    static boolean uses(Block b, Item it){
        if(b instanceof ItemTurret t) return t.ammoTypes.containsKey(it);
        return b.consumesItem(it);
    }

    static boolean uses(Block b, Liquid l){
        if(b instanceof LiquidTurret t) return t.ammoTypes.containsKey(l);
        if(b instanceof Turret t) for(Consume c : t.consumers) if(c instanceof ConsumeLiquidFilter f && f.filter.get(l)) return true;
        return b.consumesLiquid(l);
    }

    /** Whether b stands against a's front face. */
    static boolean inFront(B a, B b){
        int r = a.rot;
        if(r == 0) return b.x0() == a.x1() + 1 && b.y1() >= a.y0() && b.y0() <= a.y1();
        if(r == 2) return b.x1() == a.x0() - 1 && b.y1() >= a.y0() && b.y0() <= a.y1();
        if(r == 1) return b.y0() == a.y1() + 1 && b.x1() >= a.x0() && b.x0() <= a.x1();
        return b.y1() == a.y0() - 1 && b.x1() >= a.x0() && b.x0() <= a.x1();
    }

    /** What a hands into b by touching it (item, liquid, heat, payload), or null. */
    static String hands(B a, B b){
        Block x = a.block, y = b.block;
        List<Item> items = new ArrayList<>();
        List<Liquid> liquids = new ArrayList<>();
        if(x instanceof GenericCrafter g){
            if(g.outputItems != null) for(ItemStack s : g.outputItems) items.add(s.item);
            if(g.outputLiquids != null) for(LiquidStack s : g.outputLiquids) liquids.add(s.liquid);
        }
        if(x instanceof ConsumeGenerator c && c.outputLiquid != null) liquids.add(c.outputLiquid.liquid);
        if(x instanceof WallCrafter w) items.add(w.output);
        for(Item it : items) if(uses(y, it)) return "item";
        for(Liquid l : liquids) if(uses(y, l)) return "liquid";
        if((x instanceof HeatProducer || x instanceof HeaterGenerator || x instanceof HeatConductor) && takesHeat(y) && inFront(a, b)) return "heat";
        if((x instanceof UnitFactory || x instanceof Reconstructor) && y instanceof Reconstructor && inFront(a, b)) return "payload";
        if(x instanceof Constructor && y instanceof UnitAssembler) return "payload";
        return null;
    }

    static final String[] CATS = {"machine", "items", "liquids", "payloads", "buffer", "heat", "power", "wall"};
    static final String[] HANDS = {"item", "liquid", "heat", "payload"};

    static String measure(String name, String file, List<B> bs){
        Map<String, Integer> count = new LinkedHashMap<>(), tiles = new LinkedHashMap<>();
        Map<Long, B> at = new HashMap<>();
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        boolean erekir = true;
        Set<String> foreign = new TreeSet<>();
        TreeMap<String, Integer> kinds = new TreeMap<>();
        for(B b : bs){
            String c = cat(b.block);
            if(c.equals("port") || c.equals("core")) continue;
            count.merge(c, 1, Integer::sum);
            tiles.merge(c, b.block.size * b.block.size, Integer::sum);
            if(c.equals("machine")) kinds.merge(b.block.name, 1, Integer::sum);
            if(!b.block.isOnPlanet(Planets.erekir)){
                erekir = false;
                foreign.add(b.block.name);
            }
            x0 = Math.min(x0, b.x0());
            y0 = Math.min(y0, b.y0());
            x1 = Math.max(x1, b.x1());
            y1 = Math.max(y1, b.y1());
            for(int tx = b.x0(); tx <= b.x1(); tx++) for(int ty = b.y0(); ty <= b.y1(); ty++) at.put(((long)tx << 32) | (ty & 0xffffffffL), b);
        }
        //touching pairs, each once a way
        Map<String, Integer> hand = new LinkedHashMap<>();
        Set<Long> seen = new HashSet<>();
        Map<B, Integer> ids = new IdentityHashMap<>();
        for(B b : at.values()) ids.putIfAbsent(b, ids.size());
        Set<B> touched = Collections.newSetFromMap(new IdentityHashMap<>());
        for(B a : ids.keySet()){
            if(!cat(a.block).equals("machine") && !cat(a.block).equals("heat")) continue;
            Set<B> near = Collections.newSetFromMap(new IdentityHashMap<>());
            for(int tx = a.x0() - 1; tx <= a.x1() + 1; tx++) for(int ty = a.y0() - 1; ty <= a.y1() + 1; ty++){
                boolean edge = (tx == a.x0() - 1 || tx == a.x1() + 1) != (ty == a.y0() - 1 || ty == a.y1() + 1);
                if(!edge) continue;
                B n = at.get(((long)tx << 32) | (ty & 0xffffffffL));
                if(n != null && n != a) near.add(n);
            }
            for(B n : near){
                String h = hands(a, n);
                if(h == null || !seen.add(((long)ids.get(a) << 32) | ids.get(n))) continue;
                hand.merge(h, 1, Integer::sum);
                if(cat(n.block).equals("machine")) touched.add(n);
                touched.add(a);
            }
        }
        int machines = count.getOrDefault("machine", 0);
        int carriers = 0;
        for(String c : new String[]{"items", "liquids", "payloads", "buffer", "heat"}) carriers += count.getOrDefault(c, 0);
        int all = 0;
        for(int t : tiles.values()) all += t;
        int box = x1 < x0 ? 0 : (x1 - x0 + 1) * (y1 - y0 + 1);
        StringBuilder sb = new StringBuilder();
        sb.append(name.replace('\t', ' ')).append('\t').append(file).append('\t').append(x1 < x0 ? "0x0" : (x1 - x0 + 1) + "x" + (y1 - y0 + 1)).append('\t').append(box).append('\t').append(all);
        for(String c : CATS) sb.append('\t').append(count.getOrDefault(c, 0));
        sb.append('\t').append(tiles.getOrDefault("machine", 0));
        int handsAll = 0;
        for(String h : HANDS){
            sb.append('\t').append(hand.getOrDefault(h, 0));
            handsAll += hand.getOrDefault(h, 0);
        }
        sb.append('\t').append(String.format(Locale.ROOT, "%.2f", machines == 0 ? 0f : carriers / (float)machines));
        sb.append('\t').append(String.format(Locale.ROOT, "%.2f", box == 0 ? 0f : all / (float)box));
        sb.append('\t').append(String.format(Locale.ROOT, "%.2f", box == 0 ? 0f : tiles.getOrDefault("machine", 0) / (float)box));
        sb.append('\t').append(String.format(Locale.ROOT, "%.2f", machines == 0 ? 0f : handsAll / (float)machines));
        sb.append('\t').append(erekir ? "erekir" : "foreign:" + String.join(",", foreign));
        StringBuilder k = new StringBuilder();
        for(var e : kinds.entrySet()) k.append(k.length() == 0 ? "" : ";").append(e.getKey()).append(" x").append(e.getValue());
        sb.append('\t').append(k);
        return sb.toString();
    }

    static List<B> fromSchematic(Schematic s){
        List<B> out = new ArrayList<>();
        for(Schematic.Stile t : s.tiles) out.add(new B(t.block, t.x, t.y, t.rotation));
        return out;
    }

    static List<B> fromLedger(Grow.Ledger l){
        List<B> out = new ArrayList<>();
        for(Grow.Inst i : l.insts){
            if(i.removed) continue;
            if(i.kind.equals("line")){
                for(Object[] q : i.pieces){
                    Block bl = Vars.content.block((String)q[1]);
                    if(bl != null) out.add(new B(bl, (Integer)q[2], (Integer)q[3], (Integer)q[4]));
                }
            }else{
                for(Object[] p : i.parts){
                    Block bl = Vars.content.block((String)p[0]);
                    if(bl == null) continue;
                    int rot = p.length > 3 && p[3] != null ? (Integer)p[3] : 0;
                    out.add(new B(bl, Geo.at((Integer)p[1], bl.size), Geo.at((Integer)p[2], bl.size), rot));
                }
            }
        }
        return out;
    }

    public static void main(String[] args){
        Game.boot(args[0]);
        StringBuilder out = new StringBuilder("name\tfile\tsize\tbox\ttiles");
        for(String c : CATS) out.append('\t').append(c);
        out.append("\tmachine_tiles");
        for(String h : HANDS) out.append("\thand_").append(h);
        out.append("\tcarriers_per_machine\tfill\tmachine_fill\thands_per_machine\tplanet\tkinds\n");
        for(int a = 2; a < args.length; a++){
            String f = args[a];
            try{
                if(f.startsWith("ledger:")){
                    String file = f.substring(7);
                    Grow.Ledger l = Grow.Ledger.read(file);
                    out.append(measure(new Fi(file).nameWithoutExtension() + " step " + l.step, file, fromLedger(l))).append('\n');
                }else{
                    Schematic s = Schematics.read(new Fi(f));
                    out.append(measure(s.name(), f, fromSchematic(s))).append('\n');
                }
            }catch(Throwable t){
                System.out.println("could not read " + f + ": " + t);
            }
        }
        new Fi(args[1]).writeString(out.toString());
        System.out.println("surveyed " + (args.length - 2) + " into " + args[1]);
        System.exit(0);
    }
}
