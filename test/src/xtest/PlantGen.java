package xtest;

import arc.files.*;
import arc.math.geom.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.payloads.*;
import mindustry.world.blocks.units.*;

import java.util.*;

/**
 * Unit plants of our own for the assemblers' units (T4 and T5): one pattern for every assembler and plan.
 *
 * The assembler faces east, its build area (13 tiles a side) in front of it kept clear. Behind it, facing it, what makes
 * the units its plan takes: a fabricator, and for a plan that takes refabricated units a refabricator between the two
 * (each hands its unit to the block it faces). Above and below it, facing it, two constructors build the walls the plan
 * takes. For the second plan of an assembler, a basic assembler module stands on the far edge of the build area facing
 * into it (UnitAssembler.moduleFits: its front on the area's edge), which raises the assembler to that plan.
 *
 * Each machine takes its items at ports of its own, against it: a duct router facing in, the line coming from behind;
 * the refabricator its hydrogen, the assembler its cyanogen, at liquid ports against them. Power: beam nodes, as
 * DesignGen does it. The lab measures what a plant makes (ModLab spares the assembly drones).
 *
 * Usage: PlantGen data-dir out-dir
 */
public class PlantGen{
    static final int E = 0, N = 1, W = 2, S = 3;

    public static void main(String[] args) throws Exception{
        Boot.boot(args[0]);
        Fi dir = new Fi(args[1]);
        dir.mkdirs();
        List<DesignGen.Plan> plants = new ArrayList<>();
        for(Block b : new Block[]{Blocks.tankAssembler, Blocks.mechAssembler, Blocks.shipAssembler}){
            UnitAssembler a = (UnitAssembler)b;
            for(int i = 0; i < a.plans.size; i++){
                try{
                    plants.add(plant(a, i));
                }catch(IllegalStateException e){
                    System.out.println("NOT DRAWN: " + e.getMessage());
                }
            }
        }
        StringBuilder pictures = new StringBuilder();
        for(DesignGen.Plan p : plants){
            try{
                DesignGen.checkLines(p);
            }catch(IllegalStateException e){
                System.out.println("NOT WRITTEN: " + e.getMessage());
                continue;
            }
            DesignGen.power(p);
            Schematics.write(p.schematic(), dir.child(p.id + ".msch"));
            String picture = p.id + "  " + p.pattern + "\n" + p.picture();
            dir.child(p.id + ".txt").writeString(picture);
            pictures.append(picture).append('\n');
            System.out.println(p.id + ": " + p.pattern + " (" + DesignGen.grids(p) + " grid)");
        }
        dir.child("pictures.txt").writeString(pictures.toString());
        System.exit(0);
    }

    /** The plant of this plan of this assembler. */
    static DesignGen.Plan plant(UnitAssembler a, int planIndex){
        UnitAssembler.AssemblerUnitPlan plan = a.plans.get(planIndex);
        UnitType unitIn = null;
        Block wall = null;
        for(PayloadStack st : plan.requirements){
            if(st.item instanceof UnitType u) unitIn = u;
            else if(st.item instanceof Block b) wall = b;
        }
        if(unitIn == null || wall == null) throw new IllegalStateException(a.name + " plan " + planIndex + ": not a unit and a wall");
        //what makes the unit it takes: a fabricator, or a fabricator and the refabricator that turns its unit into this one
        UnitFactory fab = null;
        UnitType fabUnit = null;
        Reconstructor refab = null;
        for(Block b : Vars.content.blocks()){
            if(!b.isOnPlanet(Planets.erekir)) continue;
            if(b instanceof UnitFactory f) for(UnitFactory.UnitPlan up : f.plans) if(up.unit == unitIn){ fab = f; fabUnit = unitIn; }
        }
        if(fab == null){
            for(Block b : Vars.content.blocks()){
                if(!b.isOnPlanet(Planets.erekir) || !(b instanceof Reconstructor r)) continue;
                for(UnitType[] up : r.upgrades){
                    if(up[1] != unitIn) continue;
                    for(Block f : Vars.content.blocks()){
                        if(!f.isOnPlanet(Planets.erekir) || !(f instanceof UnitFactory uf)) continue;
                        for(UnitFactory.UnitPlan fp : uf.plans) if(fp.unit == up[0]){ fab = uf; fabUnit = up[0]; refab = r; }
                    }
                }
            }
        }
        if(fab == null) throw new IllegalStateException(a.name + ": nothing makes " + unitIn.name);
        String id = "unit-" + plan.unit.name + "-own";
        DesignGen.Plan p = new DesignGen.Plan(id, plan.unit.name, "");
        int s = a.size;
        //the assembler, lower-left at 0,0, facing east; its build area east of it, kept clear
        p.put(a, 0, 0, E);
        int area = a.areaSize, ax0 = s, ay0 = (s - area) / 2;
        for(int x = ax0; x < ax0 + area; x++) for(int y = ay0; y < ay0 + area; y++) p.reserve(x, y);
        //the module, for the second plan: on the area's far side (north), facing down into it, its front on the edge
        if(planIndex > 0){
            Block mod = Blocks.basicAssemblerModule;
            int mx = ax0 + area / 2 - mod.size / 2, my = ay0 + area;
            p.put(mod, mx, my, S);
        }
        //the unit makers behind it
        int fy = (s - fab.size) / 2;
        int fx;
        if(refab != null){
            int rx = -refab.size;
            p.put(refab, rx, fy, E);
            fx = rx - fab.size;
            items(p, refab, rx, fy, refab.size, unitInputs(refab));
            p.in(Liquids.hydrogen, rx + refab.size - 1, fy - 1, N);
        }else{
            fx = -fab.size;
        }
        p.put(fab, fx, fy, E, fabUnit);
        items(p, fab, fx, fy, fab.size, fabInputs(fab, fabUnit));
        //the constructors above and below it, facing it, building the plan's wall
        Block con = Blocks.constructor;
        int cx = (s - con.size) / 2;
        p.put(con, cx, -con.size, N, wall);
        p.put(con, cx, s, S, wall);
        for(ItemStack st : wall.requirements){
            p.in(st.item, cx + con.size / 2 + (st == wall.requirements[0] ? 0 : 1), -con.size - 1, N);
            p.in(st.item, cx + con.size / 2 + (st == wall.requirements[0] ? 0 : 1), s + con.size, S);
        }
        //cyanogen into the assembler's top right corner
        p.in(Liquids.cyanogen, s - 1, s, S);
        p.pattern = "unit plant: " + a.name + " making " + plan.unit.name + " from " + (refab != null ? fab.name + " (" + fabUnit.name + ") and " + refab.name : fab.name) + " (" + unitIn.name + ") and two constructors (" + wall.name + ")" + (planIndex > 0 ? ", with a basic assembler module" : "");
        return p;
    }

    /** What a fabricator takes for this unit. */
    static ItemStack[] fabInputs(UnitFactory f, UnitType u){
        for(UnitFactory.UnitPlan up : f.plans) if(up.unit == u) return up.requirements;
        return new ItemStack[0];
    }

    /** What a refabricator takes, items only. */
    static ItemStack[] unitInputs(Reconstructor r){
        for(var c : r.consumers) if(c instanceof mindustry.world.consumers.ConsumeItems ci) return ci.items;
        return new ItemStack[0];
    }

    /** One port against the machine for each item it takes: the first under it, the next above it, then further along. */
    static void items(DesignGen.Plan p, Block machine, int x, int y, int size, ItemStack[] stacks){
        int i = 0;
        for(ItemStack st : stacks){
            int px = x + i / 2, py = i % 2 == 0 ? y - 1 : y + size;
            p.in(st.item, px, py, i % 2 == 0 ? N : S);
            i++;
        }
    }
}
