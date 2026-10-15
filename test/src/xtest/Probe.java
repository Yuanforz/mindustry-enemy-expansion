package xtest;

import arc.files.*;
import arc.struct.*;
import expansion.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.core.GameState.*;
import mindustry.io.*;
import mindustry.type.*;

/**
 * Looks into why something is or is not found on a map.
 * Usage: Probe data-dir blueprint-dir map.msav minutes [x y width height]
 * Runs the map for so many minutes, prints what the enemy makes of it, and draws a part of the map if asked.
 */
public class Probe{
    public static void main(String[] args){
        Sim.boot(args[0], args[1]);
        Brain brain = Sim.brain;
        brain.unlocked = c -> true;
        Vars.logic.reset();
        SaveIO.load(new Fi(args[2]));
        Vars.state.set(State.playing);
        Vars.state.tick = 0;
        arc.util.Time.setDeltaProvider(() -> 1f);
        Boot.frames(1);
        Sim.campaignRules(true);
        Boot.frames(60 * 60 * Integer.parseInt(args[3]));
        System.out.println(brain.status());
        System.out.println("stock: " + Sim.stock());
        System.out.println("things: " + brain.net.write());
        System.out.println(String.format("slowest working-out %.0f ms (%s); slowest update %.0f ms", brain.slowestPlan, brain.slowestWhat, Boot.slowest / 1e6));
        if(args.length == 6 && args[4].equals("mine")){
            Item item = Vars.content.item(args[5]);
            brain.syncGround();
            brain.lines.allowance = 1000000;
            var project = brain.planner.mine(brain.blueprints.find(b -> b.name.equals("bore-row")), item, 3, Options.reach.get());
            System.out.println("mine " + item.name + ": " + (project == null ? brain.planner.note : project.parts.size + " blocks"));
            var tried = brain.planner.lastTried;
            if(tried != null){
                if(project != null) brain.ground.plan(project.parts); else brain.ground.plan(tried.parts);
                for(var port : tried.ports) System.out.println("port " + port.role + " at " + port.x + "," + port.y + " open=" + brain.ground.open(port.x, port.y) + " kept=" + brain.ground.kept.contains(arc.math.geom.Point2.pack(port.x, port.y)) + " rule=" + brain.ground.rule.allows(port.x, port.y));
                System.out.println(Picture.region(tried.main().x - 20, tried.main().y - 14, 40, 28, brain.ground));
            }
        }
        brain.planner.onFeedFailed = layout -> {
            System.out.println("-- a line could not be laid to " + layout.blueprint.name + " at " + layout.main().x + "," + layout.main().y + " turned " + layout.main().rotation);
            for(var port : layout.ports) System.out.println("   port " + port.item + " at " + port.x + "," + port.y + " facing " + port.rotation + " open=" + brain.ground.open(port.x, port.y));
            System.out.println(Picture.region(layout.main().x - 14, layout.main().y - 10, 28, 20, brain.ground));
        };
        if(args.length == 6 && args[4].equals("try")){
            System.out.println("try " + args[5] + ": " + brain.tryOut(args[5]));
            var tried = brain.planner.lastTried;
            if(tried != null){
                brain.syncGround();
                brain.ground.plan(tried.parts);
                for(var port : tried.ports){
                    StringBuilder b = new StringBuilder("port " + port.role + " " + port.item + " at " + port.x + "," + port.y + " facing " + port.rotation + ": open=" + brain.ground.open(port.x, port.y));
                    for(int d = 0; d < 4; d++){
                        int nx = port.x + arc.math.geom.Geometry.d4x(d), ny = port.y + arc.math.geom.Geometry.d4y(d);
                        b.append(" | ").append(d).append(": ").append(brain.ground.look(nx, ny) ? brain.ground.block.name + " r" + brain.ground.rotation : brain.ground.open(nx, ny) ? "open" : "shut").append(brain.ground.pushesInto(nx, ny, port.x, port.y) ? " PUSHES" : "");
                    }
                    System.out.println(b);
                }
                System.out.println(Picture.region(tried.main().x - 14, tried.main().y - 10, 28, 20, brain.ground));
                //try the lines to its inputs by hand
                brain.lines.allowance = 1000000;
                for(var port : tried.ports){
                    if(port.item == null) continue;
                    var plain = brain.lines.route(port.x - 3, port.y - 5, new arc.struct.IntSet(), expansion.Lines.Goal.port(port));
                    System.out.println("a line from " + (port.x - 3) + "," + (port.y - 5) + " to the port for " + port.item + ": " + (plain == null ? "no way" : plain.parts.size + " long") + " (looked at " + brain.lines.lastLooked + ")");
                }
            }
            if(brain.planner.lastSpots != null && brain.planner.lastSpots.size > 0){
                int[] at = brain.planner.lastSpots.first();
                System.out.println("spots: " + brain.planner.lastSpots.map(p -> p[0] + "," + p[1]));
                System.out.println(Picture.region(at[0] - 22, at[1] - 12, 44, 24, brain.ground));
                int open = 0, clear3 = 0, rule = 0, solid = 0, kept = 0;
                for(int dx = -6; dx <= 6; dx++) for(int dy = -6; dy <= 6; dy++){
                    int x = at[0] + dx, y = at[1] + dy;
                    if(brain.ground.open(x, y)) open++;
                    if(brain.ground.clear(Blocks.tankFabricator, x, y)) clear3++;
                    if(brain.ground.rule.allows(x, y)) rule++;
                    if(brain.ground.solidRule.allows(x, y)) solid++;
                    if(brain.ground.kept.contains(arc.math.geom.Point2.pack(x, y))) kept++;
                }
                System.out.println("around the first spot, of 169 tiles: open " + open + ", room for a yard " + clear3 + ", allowed " + rule + ", allowed for solid " + solid + ", kept clear " + kept);
            }
            if(tried != null && brain.planner.lastSpots == null){
                for(var port : tried.ports) System.out.println("port " + port.role + " " + port.item + " at " + port.x + "," + port.y + " facing " + port.rotation);
                System.out.println(Picture.region(tried.main().x - 30, tried.main().y - 18, 60, 36, brain.ground));
                //the vents around, and whether a generator could go on each
                var vents = brain.terrain.vents(Blocks.turbineCondenser, Vars.state.rules.waveTeam);
                StringBuilder near = new StringBuilder("vents within 60: ");
                for(int i = 0; i < vents.size; i++){
                    int vx = arc.math.geom.Point2.x(vents.get(i)), vy = arc.math.geom.Point2.y(vents.get(i));
                    if(Math.abs(vx - tried.main().x) > 60 || Math.abs(vy - tried.main().y) > 60) continue;
                    var b = Vars.world.build(vx, vy);
                    near.append(vx).append(',').append(vy).append(b != null ? "[" + b.block.name + (b.power != null ? String.format(" spare %.1f", brain.power.spare(b.power.graph)) : "") + "]" : brain.ground.fits(Blocks.turbineCondenser, vx, vy, 0) ? "[free]" : brain.ground.clear(Blocks.turbineCondenser, vx, vy) ? "[game says no]" : "[not open]").append(' ');
                }
                System.out.println(near);
            }
        }
        if(args.length == 9 && args[8].equals("detail")){
            //every block of the enemy's in the box: which way it faces and what is on it
            int x0 = Integer.parseInt(args[4]), y0 = Integer.parseInt(args[5]), w = Integer.parseInt(args[6]), h = Integer.parseInt(args[7]);
            for(int y = y0 + h - 1; y >= y0; y--){
                for(int x = x0; x < x0 + w; x++){
                    var build = Vars.world.build(x, y);
                    if(build == null || build.tile != Vars.world.tile(x, y) || build.team != Vars.state.rules.waveTeam) continue;
                    String link = build instanceof mindustry.world.blocks.distribution.DirectionBridge.DirectionBridgeBuild bridge ? (bridge.findLink() == null ? " (end of a bridge)" : " (hands on to " + bridge.findLink().tileX() + "," + bridge.findLink().tileY() + ")") : "";
                    System.out.println("  " + x + "," + y + " " + build.block.name + " facing " + build.rotation + (build.items != null && build.items.any() ? " holds " + build.items : "") + (build.config() != null ? " set to " + build.config() : "") + link);
                }
            }
        }
        if(args.length >= 8){
            System.out.println(Picture.region(Integer.parseInt(args[4]), Integer.parseInt(args[5]), Integer.parseInt(args[6]), Integer.parseInt(args[7]), brain.ground));
        }
        System.exit(0);
    }
}
