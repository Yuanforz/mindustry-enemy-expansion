package xtest;

import arc.math.geom.*;
import arc.struct.*;
import expansion.*;
import expansion.Blueprint.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.blocks.defense.turrets.*;

/** Checks of single pieces of the mod on small made-up maps. */
public class Pieces{
    static int failures;

    static void check(String what, boolean ok, Object detail){
        if(!ok) failures++;
        System.out.println((ok ? "  pass  " : "  FAIL  ") + what + "  -> " + detail);
    }

    public static void main(String[] args){
        Boot.boot(args[0]);
        Bench.install();
        boolean verbose = args.length > 1 && args[1].equals("-v");
        Seq<Blueprint> library = new Seq<>();
        for(int i = verbose ? 2 : 1; i < args.length; i++) library.addAll(Blueprint.parse(new arc.files.Fi(args[i]).readString()));
        Blueprint bores = library.find(b -> b.name.equals("bore-row"));

        //---- two mines on one line, and one item taken off it again
        for(int side = 0; side < 2; side++){
            String where = side == 0 ? "south" : "north";
            Bench.world();
            Layout first = Bench.place(bores, 12, 40, 2, false, 3, Items.beryllium);
            Lines.Line trunk = Bench.lead(first, Lines.Goal.core(null));
            check("a line from a mine to the core", trunk != null && !trunk.joins, trunk == null ? "no way" : trunk.parts.size + " ducts");
            if(trunk == null) continue;

            Layout second = Bench.place(bores, 22, 20, 3, false, 3, Items.graphite);
            Lines.Line joined = Bench.lead(second, Lines.Goal.core(Bench.tiles(trunk)));
            check("a second mine's line joins the first where that is nearer than the core", joined != null && joined.joins, joined == null ? "no way" : joined.parts.size + " ducts, ends at " + joined.endX + "," + joined.endY);
            if(joined == null) continue;

            Boot.frames(60 * 60);
            Bench.arrived.clear();
            Boot.frames(60 * 60);
            float beryllium = Bench.arrived.get(Items.beryllium) / 60f, graphite = Bench.arrived.get(Items.graphite) / 60f;
            check("both mines' ore reaches the core over the shared line", beryllium > 2.1f && graphite > 2.1f, String.format("beryllium %.2f/s, graphite %.2f/s of 2.25 each", beryllium, graphite));

            //a place on the shared part of the line: a straight stretch of it past where the second line came in
            int at = -1;
            for(int i = 2; i < trunk.parts.size - 2 && at == -1; i++){
                Part part = trunk.parts.get(i), before = trunk.parts.get(i - 1), after = trunk.parts.get(i + 1);
                boolean past = false;
                for(int j = 0; j < i - 1; j++) past |= trunk.parts.get(j).x == joined.endX && trunk.parts.get(j).y == joined.endY;
                if(past && part.block == Blocks.duct && before.rotation == part.rotation && after.rotation == part.rotation && before.block == Blocks.duct && after.block == Blocks.duct) at = i;
            }
            Part tap = trunk.parts.get(Math.max(at, 0));
            int ty = tap.y + (side == 0 ? -9 : 9), tx = tap.x + 6;
            Bench.put(Blocks.diffuse, tx, ty, 0, null);
            Bench.ground.plan(new Part(Blocks.diffuse, tx, ty, 0));
            Port port = new Port(Role.in, tx, ty + (side == 0 ? 2 : -2), side == 0 ? 3 : 1, Items.graphite);
            Lines.Line line = at == -1 ? null : Bench.lines.branch(tap.x, tap.y, Items.graphite, Lines.Goal.port(port));
            check("a branch for graphite can be laid off the shared line, to the " + where, line != null, line == null ? "no way" : line.parts.size + " blocks from " + tap.x + "," + tap.y);
            if(line == null){
                if(verbose) System.out.println(Bench.picture());
                continue;
            }
            Bench.ground.plan(line.parts);
            Bench.build(line.parts);
            Boot.frames(60 * 90);
            Bench.arrived.clear();
            Boot.frames(60 * 120);
            var turret = (ItemTurret.ItemTurretBuild)Vars.world.build(tx, ty);
            beryllium = Bench.arrived.get(Items.beryllium) / 120f;
            graphite = Bench.arrived.get(Items.graphite) / 120f;
            boolean onlyGraphite = true;
            for(int i = 3; i < line.parts.size; i++){
                Building build = Vars.world.build(line.parts.get(i).x, line.parts.get(i).y);
                if(build != null && build.items != null && build.items.has(Items.beryllium)) onlyGraphite = false;
            }
            check("the turret at its end is full, and only graphite is on the branch", turret.totalAmmo >= 20 && onlyGraphite, "ammo " + turret.totalAmmo + (onlyGraphite ? "" : ", beryllium on the branch"));
            check("with the turret full, everything goes on to the core again", beryllium > 2.1f && graphite > 2.1f, String.format("beryllium %.2f/s, graphite %.2f/s of 2.25 each", beryllium, graphite));
            if(verbose) System.out.println(Bench.picture());
        }

        //---- a line that carries one thing only, shared out with plain routers: one turret off the line, and a second off the first one's line
        {
            Bench.world();
            Layout mine = Bench.place(bores, 12, 40, 2, false, 3, Items.beryllium);
            Lines.Line trunk = Bench.lead(mine, Lines.Goal.core(null));
            int at = -1;
            for(int i = 6; trunk != null && i < trunk.parts.size - 2 && at == -1; i++){
                Part part = trunk.parts.get(i), before = trunk.parts.get(i - 1), after = trunk.parts.get(i + 1);
                if(part.block == Blocks.duct && before.rotation == part.rotation && after.rotation == part.rotation && before.block == Blocks.duct && after.block == Blocks.duct) at = i;
            }
            Part from = trunk == null || at == -1 ? null : trunk.parts.get(at);
            Lines.Line first = null, second = null;
            if(from != null){
                int tx = from.x + 6, ty = from.y - 9;
                Bench.put(Blocks.breach, tx, ty, 0, null);
                Bench.ground.plan(new Part(Blocks.breach, tx, ty, 0));
                first = Bench.lines.tap(from.x, from.y, Lines.Goal.port(new Port(Role.in, tx, ty + 2, 3, Items.beryllium)));
                check("a line is led off a one-item line with a single block, one that serves the new line first", first != null && first.parts.first().block == Blocks.underflowDuct && first.parts.count(p -> p.block == Blocks.ductRouter) == 0, first == null ? "no way" : first.parts.size + " blocks from " + from.x + "," + from.y);
                if(first != null){
                    Bench.ground.plan(first.parts);
                    Bench.build(first.parts);
                    //and a second turret, supplied from the middle of the line to the first
                    Part mid = null;
                    //the last straight stretch of it, just before the first turret
                    for(int i = first.parts.size - 3; i >= 2 && mid == null; i--){
                        Part part = first.parts.get(i), before = first.parts.get(i - 1), after = first.parts.get(i + 1);
                        if(part.block == Blocks.duct && before.block == Blocks.duct && after.block == Blocks.duct && before.rotation == part.rotation && after.rotation == part.rotation) mid = part;
                    }
                    if(mid != null){
                        int sx = mid.x + 7, sy = mid.y;
                        Bench.put(Blocks.breach, sx, sy, 0, null);
                        Bench.ground.plan(new Part(Blocks.breach, sx, sy, 0));
                        second = Bench.lines.tap(mid.x, mid.y, Lines.Goal.port(new Port(Role.in, sx - 2, sy, 0, Items.beryllium)));
                        check("and another off that line in turn, for the next turret along", second != null, second == null ? "no way" : second.parts.size + " blocks from " + mid.x + "," + mid.y);
                        if(second != null){
                            Bench.ground.plan(second.parts);
                            Bench.build(second.parts);
                        }
                    }
                }
            }
            if(first != null && second != null){
                Boot.frames(60 * 120);
                Bench.arrived.clear();
                Boot.frames(60 * 60);
                int armed = 0;
                for(Building b : Bench.team.data().buildings) if(b instanceof ItemTurret.ItemTurretBuild turret && turret.totalAmmo >= 20) armed++;
                float got = Bench.arrived.get(Items.beryllium) / 60f;
                check("both turrets fill up, and then all the ore goes on to the core", armed == 2 && got > 2.1f, armed + " turrets full, beryllium " + String.format("%.2f/s of 2.25", got));
            }
            if(verbose) System.out.println(Bench.picture());
        }

        System.out.println(failures == 0 ? "RESULT: all pass" : "RESULT: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
