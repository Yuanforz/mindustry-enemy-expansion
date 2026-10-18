package maptools;

import mindustry.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.type.*;

/** Erekir tech tree in tree order: depth, content, research cost, objectives (resolved). Usage: TechDump data-dir */
public class TechDump{
    static String obj(Objectives.Objective o){
        if(o instanceof Objectives.Research r) return "R:" + r.content.name;
        if(o instanceof Objectives.Produce p) return "P:" + p.content.name;
        if(o instanceof Objectives.SectorComplete s) return "S:" + s.preset.name;
        if(o instanceof Objectives.OnSector s) return "On:" + s.preset.name;
        if(o instanceof Objectives.OnPlanet p) return "Planet:" + p.planet.name;
        return o.getClass().getSimpleName();
    }
    static String stacks(ItemStack[] st){
        StringBuilder b = new StringBuilder();
        if(st == null) return "-";
        for(ItemStack s : st) b.append(b.length() > 0 ? "+" : "").append(s.item.name).append(s.amount);
        return b.length() == 0 ? "-" : b.toString();
    }
    static void walk(TechTree.TechNode node, int depth){
        StringBuilder objs = new StringBuilder();
        for(var o : node.objectives){
            //the parent research is implied; leave it out
            if(o instanceof Objectives.Research r && node.parent != null && r.content == node.parent.content) continue;
            objs.append(obj(o)).append(' ');
        }
        String kind = node.content.getContentType().name();
        String cost = node.content instanceof mindustry.world.Block b ? stacks(b.requirements) : node.content instanceof UnitType u ? stacks(u.getTotalRequirements()) : "";
        System.out.println(depth + "\t" + kind + "\t" + node.content.name + "\t" + (node.parent == null ? "-" : node.parent.content.name) + "\t" + stacks(node.requirements) + "\t" + objs.toString().trim() + "\t" + cost);
        for(var c : node.children) walk(c, depth + 1);
    }
    public static void main(String[] args){
        xtest.Boot.boot(args[0]);
        walk(Planets.erekir.techTree, 0);
        System.exit(0);
    }
}
