package xtest;

import arc.files.*;
import arc.math.geom.*;
import arc.struct.*;
import mindustry.*;
import mindustry.ai.types.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.storage.CoreBlock.*;

/**
 * Playing a sector by hand, from the command line: the mod is switched off, and the player's side is played by
 * commands written to a file, one to a line. The game waits for the next command whenever the time asked for has
 * run. What a command shows goes to another file.
 *
 * Started with -Dxp.hand=dir (the files are dir/in.txt and dir/out.txt). Commands:
 *   b block x y [rotation]           build one block (rotation 0 right, 1 up, 2 left, 3 down)
 *   l block x1 y1 x2 y2              a straight line of a block from one tile to the other, facing along it
 *   r x y [x2 y2]                    take down what stands there (or in that rectangle)
 *   s seconds                        let the game run
 *   v x y w h                        what a part of the map looks like
 *   i                                how things stand: what the core holds and gets, waves, what it has, what is queued
 *   f                                what each factory, yard and turret is short of
 *   q                                give up the sector
 * The core's own unit builds everything, at the pace a player's does: nothing goes up by itself.
 */
public class Hand{
    static Fi in, out;
    static long read;
    static int until;
    static boolean quit;
    static final ObjectIntMap<String> lostSince = new ObjectIntMap<>();
    static int[] heldBefore;

    static boolean on(){
        return System.getProperty("xp.hand") != null;
    }

    static void setup(){
        Fi dir = new Fi(System.getProperty("xp.hand"));
        dir.mkdirs();
        in = dir.child("in.txt");
        out = dir.child("out.txt");
        if(!in.exists()) in.writeString("");
        read = in.length();
        out.writeString("ready: write commands to " + in.absolutePath() + "\n", true);
        until = 0;
        quit = false;
    }

    static void say(String text){
        out.writeString(text + "\n", true);
    }

    /** The builder of the player's side: one of the core's own units, spawned if there is none. */
    static Unit builder(Team team){
        CoreBuild core = team.core();
        if(core == null) return null;
        UnitType type = ((mindustry.world.blocks.storage.CoreBlock)core.block).unitType;
        for(Unit unit : team.data().units) if(unit.type == type) return unit;
        Unit unit = type.create(team);
        unit.set(core);
        unit.add();
        unit.controller(new BuilderAI());
        return unit;
    }

    /** Called every game second; waits for commands when the time asked for has run. @return whether to give up */
    static boolean tick(int second, Team me, Team enemy){
        Unit unit = builder(me);
        if(unit != null && !(unit.controller() instanceof BuilderAI)) unit.controller(new BuilderAI());
        keepPlans(unit, me);
        if(second < until) return quit;
        say("@ " + second / 60 + ":" + String.format("%02d", second % 60) + " " + Campaign.holds(me) + (Vars.state.rules.waves ? " | wave " + Vars.state.wave + ", next in " + (int)(Vars.state.wavetime / 60f) + "s" : "") + " | queued " + (unit == null ? 0 : unit.plans.size));
        while(true){
            String line = next();
            if(line == null){
                try{ Thread.sleep(100); }catch(InterruptedException e){ return true; }
                continue;
            }
            line = line.trim();
            if(line.isEmpty()) continue;
            say("> " + line);
            try{
                if(run(line, second, me, enemy, unit)) break;
            }catch(Throwable t){
                say("  error: " + t);
            }
            if(quit) break;
        }
        return quit;
    }

    static String next(){
        long length = in.length();
        if(length <= read) return null;
        String all = in.readString();
        int end = all.indexOf('\n', (int)read);
        if(end < 0) return null;
        String line = all.substring((int)read, end);
        read = end + 1;
        return line;
    }

    /** @return whether time is to run on */
    static boolean run(String line, int second, Team me, Team enemy, Unit unit){
        String[] a = line.split("\\s+");
        switch(a[0]){
            case "b" -> {
                Block block = block(a[1]);
                int x = Integer.parseInt(a[2]), y = Integer.parseInt(a[3]), r = a.length > 4 ? Integer.parseInt(a[4]) : 0;
                place(unit, me, block, x, y, r);
            }
            case "l" -> {
                Block block = block(a[1]);
                int x1 = Integer.parseInt(a[2]), y1 = Integer.parseInt(a[3]), x2 = Integer.parseInt(a[4]), y2 = Integer.parseInt(a[5]);
                int dx = Integer.signum(x2 - x1), dy = Integer.signum(y2 - y1);
                if(dx != 0 && dy != 0){
                    say("  a line goes straight across or straight up");
                    return false;
                }
                int r = dx > 0 ? 0 : dy > 0 ? 1 : dx < 0 ? 2 : 3, n = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1)), step = Math.max(block.size, 1);
                int ok = 0;
                for(int i = 0; i <= n; i += step) ok += place(unit, me, block, x1 + dx * i, y1 + dy * i, r) ? 1 : 0;
                say("  " + ok + " queued");
            }
            case "r" -> {
                int x1 = Integer.parseInt(a[1]), y1 = Integer.parseInt(a[2]), x2 = a.length > 4 ? Integer.parseInt(a[3]) : x1, y2 = a.length > 4 ? Integer.parseInt(a[4]) : y1;
                IntSet done = new IntSet();
                for(int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++){
                    for(int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++){
                        Building build = Vars.world.build(x, y);
                        if(build == null || build.team != me || !done.add(build.pos())) continue;
                        unit.addBuild(new BuildPlan(build.tileX(), build.tileY()));
                    }
                }
                say("  " + done.size + " to take down");
            }
            case "s" -> {
                until = second + Math.max(Integer.parseInt(a[1]), 1);
                heldBefore = new int[Vars.content.items().size];
                if(me.core() != null) for(Item item : Vars.content.items()) heldBefore[item.id] = me.core().items.get(item);
                return true;
            }
            case "v" -> {
                Picture.side = me;
                say(Picture.region(Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]), Integer.parseInt(a[4]), null));
                Picture.side = null;
            }
            case "m" -> say(map(me, Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]), Integer.parseInt(a[4])));
            case "i" -> info(me, enemy, unit);
            case "f" -> short_(me);
            case "q" -> quit = true;
            default -> say("  unknown: " + a[0]);
        }
        return false;
    }

    /**
     * A part of the map at one character a tile. Ground: '.' open, ',' open but not to be built on, '~' deep water,
     * '#' wall, 's' wall that crushers get sand from, 'B' 'G' 'T' wall with beryllium, graphite, tungsten ore in it,
     * 'b' 'g' 't' the same ore in the floor, ':' vent. Its own buildings by their code letter (see Picture), the other
     * side's 'X'.
     */
    static String map(Team me, int x0, int y0, int w, int h){
        StringBuilder out = new StringBuilder();
        for(int y = y0 + h - 1; y >= y0; y--){
            out.append(String.format("%4d ", y));
            for(int x = x0; x < x0 + w; x++){
                Tile t = Vars.world.tile(x, y);
                char c;
                if(t == null) c = ' ';
                else if(t.build != null) c = t.team() == me ? Picture.codes.get(t.block().name, "?").charAt(0) : 'X';
                else if(t.block().solid){
                    Item drop = t.wallDrop();
                    c = drop != null ? Character.toUpperCase(drop.name.charAt(0)) : t.block().attributes.get(mindustry.world.meta.Attribute.sand) > 0f ? 's' : '#';
                }else if(t.floor() instanceof mindustry.world.blocks.environment.SteamVent) c = ':';
                else if(t.floor().isDeep()) c = '~';
                else if(!t.floor().placeableOn || !Vars.world.isInMapArea(x, y)) c = ',';
                else if(t.drop() != null && t.overlay() != null && t.overlay().itemDrop != null) c = t.overlay().itemDrop.name.charAt(0);
                else c = '.';
                out.append(c);
            }
            out.append('\n');
        }
        out.append("     ");
        for(int x = x0; x < x0 + w; x++) out.append(x % 10 == 0 ? '|' : x % 5 == 0 ? '\'' : ' ');
        out.append("\n     ").append(x0).append(" .. ").append(x0 + w - 1);
        return out.toString();
    }

    static Block block(String name){
        Block block = Vars.content.block(name);
        if(block == null) throw new IllegalArgumentException("no block " + name);
        return block;
    }

    static boolean place(Unit unit, Team me, Block block, int x, int y, int r){
        if(!block.unlocked()){
            say("  " + block.name + " is not researched");
            return false;
        }
        if(!Build.validPlace(block, me, x, y, r)){
            say("  the game does not allow " + block.name + " at " + x + "," + y);
            return false;
        }
        BuildPlan plan = new BuildPlan(x, y, r, block);
        unit.addBuild(plan);
        pending.add(plan);
        return true;
    }

    /** What was asked to be built and is not standing yet: the builder drops a plan now and then, and is given it again. */
    static final Seq<BuildPlan> pending = new Seq<>();

    static void keepPlans(Unit unit, Team me){
        if(unit == null) return;
        pending.removeAll(plan -> {
            Tile tile = Vars.world.tile(plan.x, plan.y);
            if(tile == null) return true;
            if(tile.build != null && tile.block() == plan.block && tile.build.tile == tile) return true;
            boolean site = tile.build instanceof mindustry.world.blocks.ConstructBlock.ConstructBuild c && c.current == plan.block;
            return !site && !Build.validPlace(plan.block, me, plan.x, plan.y, plan.rotation);
        });
        for(BuildPlan plan : pending){
            boolean queued = false;
            for(BuildPlan other : unit.plans) queued |= other.x == plan.x && other.y == plan.y && !other.breaking;
            if(!queued) unit.addBuild(plan.copy());
        }
    }

    static void info(Team me, Team enemy, Unit unit){
        CoreBuild core = me.core();
        if(core == null){
            say("  no core");
            return;
        }
        StringBuilder s = new StringBuilder("  core at " + core.tileX() + "," + core.tileY() + " holds (of " + core.storageCapacity + "):");
        for(Item item : Vars.content.items()){
            int have = core.items.get(item);
            if(have == 0 && (heldBefore == null || heldBefore[item.id] == 0)) continue;
            s.append(' ').append(item.name).append('=').append(have);
            if(heldBefore != null) s.append('(').append(have - heldBefore[item.id] >= 0 ? "+" : "").append(have - heldBefore[item.id]).append(')');
        }
        say(s.toString());
        ObjectIntMap<String> mine = new ObjectIntMap<>();
        for(Building build : me.data().buildings) mine.increment(build.block.name);
        say("  buildings: " + mine);
        ObjectIntMap<String> units = new ObjectIntMap<>(), theirs = new ObjectIntMap<>();
        for(Unit u : me.data().units) units.increment(u.type.name);
        for(Unit u : enemy.data().units) if(!u.inFogTo(me)) theirs.increment(u.type.name);
        say("  units: " + units + "; the other side's in sight: " + theirs + "; its cores: " + enemy.cores().size);
        StringBuilder where = new StringBuilder("  where they come from:");
        for(Tile spawn : Vars.spawner.getSpawns()) where.append(' ').append(spawn.x).append(',').append(spawn.y);
        for(CoreBuild other : enemy.cores()) where.append(" core@").append(other.tileX()).append(',').append(other.tileY());
        say(where.toString());
        StringBuilder seen = new StringBuilder();
        for(Unit u : enemy.data().units) if(!u.inFogTo(me)) seen.append(' ').append(u.type.name).append('@').append(u.tileX()).append(',').append(u.tileY()).append(" hp").append((int)u.health);
        if(seen.length() > 0) say("  in sight:" + seen);
        if(unit != null) say("  builder at " + unit.tileX() + "," + unit.tileY() + ", " + unit.plans.size + " queued" + (unit.buildPlan() != null ? ", working on " + (unit.buildPlan().breaking ? "taking down" : unit.buildPlan().block.name) + " at " + unit.buildPlan().x + "," + unit.buildPlan().y : ""));
    }

    static void short_(Team me){
        for(Building build : me.data().buildings){
            if(build.block.consumers.length == 0 && !(build instanceof mindustry.world.blocks.defense.turrets.Turret.TurretBuild)) continue;
            if(build.block instanceof mindustry.world.blocks.distribution.Duct || build.block.group == mindustry.world.meta.BlockGroup.transportation) continue;
            String state;
            if(build instanceof mindustry.world.blocks.defense.turrets.ItemTurret.ItemTurretBuild t) state = "ammo " + t.totalAmmo + (t.liquids != null && t.liquids.currentAmount() > 0 ? ", cooled" : "");
            else state = "running " + (int)(build.efficiency * 100) + "%" + (build.power != null && build.block.consPower != null ? ", power " + (int)(build.power.status * 100) + "%" : "");
            if(build.items != null && build.items.total() > 0){
                StringBuilder held = new StringBuilder();
                build.items.each((item, amount) -> held.append(' ').append(item.name).append('=').append(amount));
                state += ", holds" + held;
            }
            say("  " + build.block.name + " at " + build.tileX() + "," + build.tileY() + ": " + state);
        }
    }
}
