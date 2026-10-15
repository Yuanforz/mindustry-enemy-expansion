package xtest;

import arc.files.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import mindustry.*;
import mindustry.core.GameState.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.storage.*;

/**
 * Looks at how the bases that come with the game's own maps are put together: where their walls stand
 * in relation to their turrets and to the way to the player, and how their turrets are supplied. For
 * learning from, before deciding how the enemy should lay out its own.
 *
 * Usage: Study data-dir list|walls|feeds|draw map.msav [x y w h]
 */
public class Study{
    public static void main(String[] args){
        Boot.boot(args[0]);
        String what = args[1];
        Vars.logic.reset();
        SaveIO.load(new Fi(args[2]));
        Vars.state.set(State.playing);
        Boot.frames(1);
        Team enemy = Vars.state.rules.waveTeam, player = Vars.state.rules.defaultTeam;
        String name = new Fi(args[2]).nameWithoutExtension();

        if(what.equals("draw")){
            int x = Integer.parseInt(args[3]), y = Integer.parseInt(args[4]), w = Integer.parseInt(args[5]), h = Integer.parseInt(args[6]);
            System.out.println("#### " + name + " " + x + "," + y + " " + w + "x" + h);
            System.out.println(Picture.region(x, y, w, h, null));
            ObjectIntMap<String> used = new ObjectIntMap<>();
            for(int tx = x; tx < x + w; tx++) for(int ty = y; ty < y + h; ty++){
                Building b = Vars.world.build(tx, ty);
                if(b != null && b.team == enemy && b.tile.x == tx && b.tile.y == ty) used.increment(b.block.name);
            }
            System.out.println(Picture.legend(used));
            System.exit(0);
        }

        if(what.equals("find")){
            StringBuilder out = new StringBuilder("#### " + name + " " + args[3] + ":");
            for(Building b : enemy.data().buildings) if(b.block.name.equals(args[3])) out.append(" ").append(b.tileX()).append(",").append(b.tileY());
            System.out.println(out);
            System.exit(0);
        }
        Seq<Building> turrets = new Seq<>(), walls = new Seq<>();
        ObjectIntMap<String> counts = new ObjectIntMap<>();
        for(Building b : enemy.data().buildings){
            counts.increment(b.block.name);
            if(b.block instanceof BaseTurret) turrets.add(b);
            if(b.block instanceof Wall) walls.add(b);
        }
        Building target = player.core();

        if(what.equals("list")){
            System.out.println("#### " + name + ": " + turrets.size + " turrets, " + walls.size + " walls; enemy cores " + enemy.cores().size + ", player core " + (target == null ? "-" : target.tileX() + "," + target.tileY()));
            Seq<String> names = counts.keys().toSeq();
            names.sort(n -> -counts.get(n));
            StringBuilder line = new StringBuilder("  ");
            for(String n : names) line.append(n).append("×").append(counts.get(n)).append("  ");
            System.out.println(line);
            //where the turrets are thickest, in squares of 24 tiles
            ObjectIntMap<Integer> squares = new ObjectIntMap<>(), wallSquares = new ObjectIntMap<>();
            for(Building b : turrets) squares.increment(b.tileX() / 24 + b.tileY() / 24 * 1000);
            for(Building b : walls) wallSquares.increment(b.tileX() / 24 + b.tileY() / 24 * 1000);
            Seq<Integer> keys = squares.keys().toSeq();
            keys.sort(k -> -squares.get(k) * 100 - wallSquares.get(k, 0));
            for(int i = 0; i < Math.min(keys.size, 8); i++){
                int k = keys.get(i);
                System.out.println("  square at " + (k % 1000 * 24) + "," + (k / 1000 * 24) + ": " + squares.get(k) + " turrets, " + wallSquares.get(k, 0) + " walls");
            }
        }

        if(what.equals("walls") && target != null){
            //each wall: how far from the nearest turret, and on which side of it, counting the way to the player's core as its front
            int[] byDistance = new int[8], bySide = new int[4];
            int near = 0, alone = 0;
            for(Building wall : walls){
                Building turret = Geometry.findClosest(wall.x, wall.y, turrets);
                if(turret == null){ alone++; continue; }
                float far = Mathf.dst(wall.tileX(), wall.tileY(), turret.tileX(), turret.tileY());
                byDistance[Math.min((int)(far / 3f), 7)]++;
                if(far > 12f){ alone++; continue; }
                near++;
                float front = Angles.angle(turret.x, turret.y, target.x, target.y), at = Angles.angle(turret.x, turret.y, wall.x, wall.y);
                float off = Angles.angleDist(front, at);
                bySide[off < 45f ? 0 : off < 135f ? 1 : 2]++;
            }
            //walls that touch each other make up one piece of walling: how big the pieces are, and how thick
            IntSet seen = new IntSet();
            IntSeq sizes = new IntSeq();
            int lines = 0, blobs = 0;
            for(Building wall : walls){
                if(!seen.add(wall.id)) continue;
                Seq<Building> piece = new Seq<>(), open = Seq.with(wall);
                int minX = 9999, minY = 9999, maxX = 0, maxY = 0, tiles = 0;
                while(open.size > 0){
                    Building b = open.pop();
                    piece.add(b);
                    tiles += b.block.size * b.block.size;
                    minX = Math.min(minX, b.tileX()); maxX = Math.max(maxX, b.tileX() + b.block.size - 1);
                    minY = Math.min(minY, b.tileY()); maxY = Math.max(maxY, b.tileY() + b.block.size - 1);
                    for(Building other : b.proximity){
                        if(other.block instanceof Wall && other.team == enemy && seen.add(other.id)) open.add(other);
                    }
                }
                sizes.add(tiles);
                int w = maxX - minX + 1, h = maxY - minY + 1;
                if(piece.size >= 3){
                    if(Math.min(w, h) <= 2) lines++; else blobs++;
                }
            }
            sizes.sort();
            System.out.println("#### " + name + ": " + walls.size + " walls (" + counts.get("beryllium-wall-large", 0) + counts.get("tungsten-wall-large", 0) + " large of beryllium+tungsten), " + turrets.size + " turrets");
            System.out.println("  tiles from the nearest turret, in steps of 3: " + java.util.Arrays.toString(byDistance));
            System.out.println("  of the " + near + " within 12 tiles of a turret: in front of it (towards the player) " + bySide[0] + ", to its sides " + bySide[1] + ", behind it " + bySide[2] + "; further off than that: " + alone);
            System.out.println("  pieces of walling: " + sizes.size + "; tiles in each, smallest to biggest: " + (sizes.size > 30 ? "median " + sizes.get(sizes.size / 2) + ", biggest " + sizes.get(sizes.size - 1) : sizes.toString()) + "; of those with 3 walls or more, " + lines + " are rows no more than 2 thick, " + blobs + " are wider");
        }

        if(what.equals("feeds")){
            //each turret that takes items: what stands against it and hands it things
            ObjectIntMap<String> feeders = new ObjectIntMap<>();
            int fed = 0, unfed = 0;
            IntSeq lengths = new IntSeq();
            for(Building turret : turrets){
                if(!(turret.block instanceof ItemTurret)) continue;
                boolean any = false;
                for(Building other : turret.proximity){
                    if(other.team != enemy) continue;
                    boolean into = other.block instanceof Duct || other.block instanceof DuctBridge ? other.front() == turret
                        : other.block instanceof DuctRouter || other.block instanceof OverflowDuct || other.block instanceof DirectionalUnloader || other.block instanceof BeamDrill || other.block instanceof Drill || other.block instanceof GenericCrafter || other.block instanceof StorageBlock || other.block instanceof Sorter;
                    if(!into) continue;
                    any = true;
                    feeders.increment(other.block.name);
                    //how long the line behind it is, back to whatever puts things on it
                    if(other.block instanceof Duct){
                        int length = 0;
                        Building at = other;
                        IntSet walked = new IntSet();
                        String source = "?";
                        while(at != null && walked.add(at.id) && length < 400){
                            length++;
                            Building back = null;
                            for(Building p : at.proximity){
                                if(p.team != enemy || p == turret) continue;
                                if((p.block instanceof Duct || p.block instanceof DuctBridge) && p.front() == at){ back = p; break; }
                                if(p.block instanceof DuctBridge && at.block instanceof DuctBridge){ back = p; break; }
                                if(p.block instanceof DuctRouter || p.block instanceof OverflowDuct || p.block instanceof Sorter){ source = p.block.name; }
                                if(p.block instanceof BeamDrill || p.block instanceof Drill || p.block instanceof GenericCrafter || p.block instanceof DirectionalUnloader){ source = p.block.name; }
                            }
                            at = back;
                        }
                        lengths.add(length);
                        feeders.increment("  …line ending at " + source);
                    }
                }
                if(any) fed++; else unfed++;
            }
            lengths.sort();
            System.out.println("#### " + name + ": item turrets with something handing them ammunition " + fed + ", with nothing " + unfed);
            System.out.println("  what hands it over: " + feeders);
            if(lengths.size > 0) System.out.println("  ducts in the line behind a turret: median " + lengths.get(lengths.size / 2) + ", longest " + lengths.get(lengths.size - 1) + ", all " + (lengths.size > 40 ? "(" + lengths.size + ")" : lengths.toString()));
        }
        System.exit(0);
    }
}
