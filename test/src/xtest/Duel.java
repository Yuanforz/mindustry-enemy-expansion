package xtest;

import arc.*;
import arc.files.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import expansion.army.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.core.GameState.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.Turret.*;
import mindustry.world.blocks.environment.*;
import mindustry.world.blocks.storage.CoreBlock.*;

/**
 * Two sides on one map, each led by a controller of its own, with the same base and the same units coming out on
 * both sides: what is measured is how the units are led and nothing else.
 *
 * The map gives the ground only. Everything on it is taken off, and each side gets the same base where the map has a
 * core: a core, three fabricators behind it, two breaches in front of it, and an outpost of two more breaches some
 * way out along the ground way to the other side. Each breach is fed by a line of ducts from a bore, which brings it
 * a beryllium a second (a drill's worth: the breach fires one and a half a second), so a turret that fires at nothing
 * worth it runs dry, and one whose line is cut fires only what it holds. Units come out of each fabricator on the same
 * clock on both sides (T1 first, T2 later), for as long as it stands. Both sides have the game's own squad AI on.
 *
 * Each pair of games is played on both sides of the map, the controllers swapped: one side of a map is often easier.
 * A game ends when a core falls, or at the time limit, when the side that destroyed the more (in health of what it
 * destroyed or damaged) wins if it is clearly ahead, else it is a draw.
 *
 * Usage: Duel data-dir minutes controllerA controllerB map...
 *   map: a .msav file, or gen:seed for a map made by the game's generator. Each map is played twice (swapped).
 *   controller: "rts" (the game's squad AI alone), or "army" or "army:flag,flag,..." (see {@link Army.Flags}).
 */
public class Duel{
    static final Team teamA = Team.sharded, teamB = Team.malis;

    /** One side of a game: who leads it and what it lost. */
    static class Side{
        Team team;
        String controller;
        @Nullable Army army;
        int coreX, coreY;
        /** Health of what it lost, in units and in buildings, and how many units. */
        float unitsLost, blocksLost;
        int unitCount, made;
        /** Its turrets' seconds with no ammunition. */
        int drySeconds;
        Seq<Feed> feeds = new Seq<>();
        Seq<Building> fabs = new Seq<>();
        /** The drills its economy runs on, out to the sides of its base, and how far each fabricator is with its next unit. */
        Seq<Building> econ = new Seq<>();
        float[] progress = new float[3];
    }

    /** A line feeding a turret: while the bore stands, a beryllium a second goes into the line's first duct. */
    static class Feed{
        Building bore, duct, turret;
        float owed;
    }

    static float feedRate = Float.parseFloat(System.getProperty("duel.feed", "1"));
    static int unitCap = Integer.getInteger("duel.cap", 40);

    public static void main(String[] args){
        Boot.boot(args[0]);
        float minutes = Float.parseFloat(args[1]);
        String ctrlA = args[2], ctrlB = args[3];
        int wins = 0, losses = 0, draws = 0;
        for(int a = 4; a < args.length; a++){
            for(int swap = 0; swap < 2; swap++){
                String result;
                try{
                    result = play(args[a], swap == 1, minutes, ctrlA, ctrlB);
                }catch(Throwable error){
                    error.printStackTrace();
                    result = "error " + error;
                }
                System.out.println(result);
                if(result.contains(" winner=A")) wins++;
                else if(result.contains(" winner=B")) losses++;
                else if(result.contains(" winner=-")) draws++;
            }
        }
        System.out.println("TOTAL A=" + ctrlA + " B=" + ctrlB + ": A won " + wins + ", lost " + losses + ", drew " + draws);
        System.exit(0);
    }

    static Side[] sides;

    static String play(String map, boolean swap, float minutes, String ctrlA, String ctrlB){
        long began = System.currentTimeMillis();
        int seed = map.hashCode() * 31 + (swap ? 1 : 0);
        int[] at = load(map);
        if(at == null) return "DUEL map=" + map + " skipped: no place for two cores";
        Rules rules = Vars.state.rules;
        //what the map gave each team of its own (stronger blocks, more damage) is gone: the two sides are alike
        rules.teams = new Rules.TeamRules();
        rules.waves = false;
        rules.attackMode = true;
        rules.pvp = false;
        //neither side is the one a player would be: both are run by the AI, and the game treats them alike
        rules.defaultTeam = Team.blue;
        rules.waveTeam = Team.crux;
        rules.fog = false;
        //the whole map is open: on some maps the part that can be played is a rectangle that only grows as the campaign goes on, and what stands outside it does nothing
        rules.limitMapArea = false;
        rules.unitCapVariable = false;
        rules.unitCap = unitCap;
        rules.coreIncinerates = true;
        rules.infiniteResources = false;
        rules.onlyDepositCore = true;
        for(Team team : new Team[]{teamA, teamB}){
            var tr = rules.teams.get(team);
            tr.rtsAi = true;
            //as the campaign sets it up for the side it plays
            tr.rtsMaxSquad = 15;
            tr.rtsMinSquad = 4;
            tr.rtsMinWeight = 1.2f;
            tr.aiCoreSpawn = false;
        }

        //the first map position is the map's player side, the second the enemy's. the team goes with the position and the
        //controller with the swap: which team the game updates first is then a part of the side of the map, which the swap evens out
        Side first = new Side(), second = new Side();
        first.team = teamA; second.team = teamB;
        first.coreX = at[0]; first.coreY = at[1];
        second.coreX = at[2]; second.coreY = at[3];
        Side a = swap ? second : first, b = swap ? first : second;
        a.controller = ctrlA;
        b.controller = ctrlB;
        sides = new Side[]{first, second};

        IntSeq way = groundWay(at[0], at[1], at[2], at[3]);
        build(first, way, false);
        build(second, way, true);

        //the same game from the same start: the game's random numbers and its clock begin where they did the last time
        if(Boolean.getBoolean("duel.randlog")) Mathf.rand = new Rand(){ @Override public long nextLong(){ if(Vars.state.tick > 3800 && Vars.state.tick < 4000){ StackTraceElement[] s = new Throwable().getStackTrace(); StringBuilder b = new StringBuilder("R " + (int)Vars.state.tick); for(int i = 1; i < Math.min(s.length, 7); i++) b.append(" ").append(s[i].getClassName().replaceAll(".*[.]", "")).append(":").append(s[i].getLineNumber()); System.out.println(b); } return super.nextLong(); } };
        Mathf.rand.setSeed(seed);
        Time.time = 0f;
        Time.globalTime = 0f;
        Time.clear();
        ControlSync.seededWorlds = null;
        Vars.state.set(State.playing);
        Vars.state.tick = 0;
        Time.setDeltaProvider(() -> 1f);
        //(the path-finding thread the map started is stopped before anything moves)
        ControlSync.step();
        Boot.frames(1);
        ControlSync.step();
        Boot.frames(1);
        ControlSync.step();
        for(Side side : sides){
            if(side.team.core() == null) return "DUEL map=" + map + " skipped: a core could not be put down";
            for(ItemStack stack : ItemStack.list(Items.beryllium, 1500, Items.graphite, 600, Items.silicon, 600, Items.tungsten, 200)) side.team.core().items.set(stack.item, stack.amount);
            for(Feed feed : side.feeds){
                while(feed.turret.acceptItem(feed.duct, Items.beryllium)) feed.turret.handleItem(feed.duct, Items.beryllium);
            }
            if(side.controller.startsWith("army")) side.army = new Army(side.team, Army.Flags.parse(side.controller));
        }

        listen();
        if(Boolean.getBoolean("duel.show")) show(map, way);
        int frames = (int)(minutes * 60f * 60f);
        Side lost = null;
        int frame;
        for(frame = 0; frame < frames; frame++){
            Boot.frames(1);
            ControlSync.step();
            ControlSync.seedPhysics(seed);
            for(Side side : sides){
                feed(side);
                if(side.army != null) side.army.update();
            }
            if(Integer.getInteger("duel.traceat", -1) >= 0 && Math.abs(frame - Integer.getInteger("duel.traceat")) < 150 && frame % 5 == 0){
                for(Unit unit : Groups.unit) System.out.println("  u " + frame + " " + unit.id + " " + unit.type.name + " " + unit.x + "," + unit.y + " v " + unit.vel + " " + (unit.controller() instanceof mindustry.ai.types.CommandAI c ? c.targetPos + " " + c.attackTarget : unit.controller()));
            }
            if(Boolean.getBoolean("duel.trace") && frame % 120 == 0){
                //a sum over where everything is: two runs from the same start that part ways show where
                double sum = 0;
                for(Unit unit : Groups.unit) sum += unit.x * 1.3 + unit.y * 0.7 + unit.health + unit.id;
                double bullets = 0;
                for(Bullet bullet : Groups.bullet) bullets += bullet.x + bullet.y;
                System.out.println("  trace " + frame + " units " + Groups.unit.size() + " " + sum + " bullets " + Groups.bullet.size() + " " + bullets + " rand " + Mathf.rand.seed0);
            }
            if(frame % 60 == 0){
                for(Side side : sides) second(side, frame);
                if(Boolean.getBoolean("duel.show") && frame % 600 == 0){
                    StringBuilder line = new StringBuilder("  t=" + frame / 60);
                    for(Side side : sides){
                        line.append(" | ").append(side.team).append(" units ").append(side.team.data().units.size).append(" ammo");
                        for(Feed feed : side.feeds) line.append(" ").append(feed.turret.isValid() ? ((TurretBuild)feed.turret).totalAmmo + (((TurretBuild)feed.turret).target != null ? "*" : "") + (((TurretBuild)feed.turret).hasAmmo() ? "" : "!" + feed.turret.canConsume() + feed.turret.enabled + feed.turret.efficiency + "/" + feed.turret.potentialEfficiency) : "x");
                    }
                    System.out.println(line);
                    for(Side side : sides) if(side.army != null) System.out.println("    " + side.team + " " + side.army.report() + " " + side.army.debug());
                }
            }
            if(a.team.core() == null || b.team.core() == null){
                lost = a.team.core() == null ? a : b;
                break;
            }
        }
        //what each side did to the other: what it destroyed, and what it left damaged
        float doneByA = b.unitsLost + b.blocksLost + damaged(b.team), doneByB = a.unitsLost + a.blocksLost + damaged(a.team);
        String winner, how;
        if(lost != null){
            winner = lost == a ? "B" : "A";
            how = "core";
        }else if(doneByA > doneByB * 1.25f && doneByA - doneByB > 2000f){
            winner = "A"; how = "points";
        }else if(doneByB > doneByA * 1.25f && doneByB - doneByA > 2000f){
            winner = "B"; how = "points";
        }else{
            winner = "-"; how = "even";
        }
        StringBuilder out = new StringBuilder();
        out.append("DUEL map=").append(new Fi(map).nameWithoutExtension()).append(" swap=").append(swap ? 1 : 0)
            .append(" A=").append(ctrlA).append(" B=").append(ctrlB)
            .append(" winner=").append(winner).append(" how=").append(how).append(" min=").append(Strings.fixed(frame / 3600f, 1))
            .append(" doneA=").append((int)doneByA).append(" doneB=").append((int)doneByB)
            .append(" madeA=").append(a.made).append(" madeB=").append(b.made)
            .append(" leftA=").append(a.team.data().units.size).append(" leftB=").append(b.team.data().units.size)
            .append(" dryA=").append(a.drySeconds).append(" dryB=").append(b.drySeconds);
        for(Side side : sides) if(side.army != null) out.append(" ").append(side == a ? "A" : "B").append("{").append(side.army.report()).append("}");
        out.append(" wall=").append((System.currentTimeMillis() - began) / 1000).append("s");
        return out.toString();
    }

    static boolean listening;

    static void listen(){
        if(listening) return;
        listening = true;
        Events.on(UnitDestroyEvent.class, e -> {
            Side side = of(e.unit.team);
            if(side != null && !e.unit.spawnedByCore) side.unitsLost += e.unit.maxHealth;
        });
        Events.on(BlockDestroyEvent.class, e -> {
            Side side = of(e.tile.team());
            //(walls put down as bait are not counted: a side that put them down meant them to go)
            if(side != null && e.tile.build != null && !(e.tile.block() instanceof mindustry.world.blocks.defense.Wall) && !(e.tile.build instanceof mindustry.world.blocks.ConstructBlock.ConstructBuild)) side.blocksLost += e.tile.block().health;
        });
    }

    static @Nullable Side of(Team team){
        if(sides == null) return null;
        for(Side side : sides) if(side.team == team) return side;
        return null;
    }

    /** Health its standing buildings have lost. */
    static float damaged(Team team){
        float sum = 0f;
        for(Building build : team.data().buildings){
            if(build.block instanceof mindustry.world.blocks.defense.Wall || build instanceof mindustry.world.blocks.ConstructBlock.ConstructBuild) continue;
            sum += build.maxHealth - build.health;
        }
        return sum;
    }

    /** What each line brings its turret, a little every frame. */
    static void feed(Side side){
        for(Feed feed : side.feeds){
            if(!feed.bore.isValid() || !feed.duct.isValid() || feed.duct.tile.build != feed.duct) continue;
            feed.owed = Math.min(feed.owed + feedRate / 60f, 3f);
            if(feed.owed >= 1f && feed.duct.acceptItem(feed.bore, Items.beryllium)){
                feed.duct.handleItem(feed.bore, Items.beryllium);
                feed.owed -= 1f;
            }
        }
    }

    /** What each side gets once a second: its units, on the same clock for both, and its turrets' dry time counted. */
    static void second(Side side, int frame){
        int second = frame / 60;
        boolean late = second >= 8 * 60;
        //what the fabricators are paid with comes from the drills of its economy: with some of them gone, units come out
        //more slowly (down to a quarter of the pace with none left)
        float pace = side.econ.isEmpty() ? 1f : Math.max(side.econ.count(Building::isValid) / (float)side.econ.size, 0.25f);
        for(int i = 0; i < side.fabs.size; i++){
            Building fab = side.fabs.get(i);
            if(!fab.isValid() || second < 30) continue;
            //the first after half a minute, then one every so often: T1 for the first eight minutes, T2 after
            int period = late ? 60 : (i == 0 ? 35 : 40);
            side.progress[i] += pace / period;
            if(second == 30) side.progress[i] = 1f;
            if(side.progress[i] < 1f) continue;
            if(side.team.data().units.count(u -> !u.spawnedByCore) >= unitCap) continue;
            side.progress[i] -= 1f;
            UnitType type = switch(i){
                case 0 -> late ? UnitTypes.locus : UnitTypes.stell;
                case 1 -> late ? UnitTypes.avert : UnitTypes.elude;
                default -> late ? UnitTypes.cleroi : UnitTypes.merui;
            };
            Unit unit = type.create(side.team);
            CoreBuild core = side.team.core();
            float angle = core == null ? 0f : Angles.angle(fab.x, fab.y, core.x, core.y);
            unit.set(fab.x + Angles.trnsx(angle, 20f), fab.y + Angles.trnsy(angle, 20f));
            unit.rotation = angle;
            unit.add();
            side.made++;
        }
        for(Feed feed : side.feeds){
            if(feed.turret.isValid() && feed.turret instanceof TurretBuild t && !t.hasAmmo()) side.drySeconds++;
        }
        //a side whose army layer baits gets the core unit a player would have (the other side has nothing for one to do here)
        CoreBuild core = side.team.core();
        if(side.army != null && (side.army.flags.bait || side.army.flags.decoy) && core != null && second % 10 == 0 && !side.team.data().units.contains(Bait::coreUnit)){
            Unit unit = ((mindustry.world.blocks.storage.CoreBlock)core.block).unitType.create(side.team);
            unit.set(core.x, core.y);
            unit.add();
        }
    }

    /** What each side was given, and where: for looking into a map's setup (-Dduel.show=true). */
    static void show(String map, IntSeq way){
        System.out.println("  " + map + ": " + Vars.world.width() + "x" + Vars.world.height() + ", ground way " + way.size + " tiles");
        for(Side side : sides){
            System.out.println("  " + side.team + " core at " + side.coreX + "," + side.coreY + " " + side.team.core());
            for(Building fab : side.fabs) System.out.println("    fab " + fab.block.name + " at " + fab.tileX() + "," + fab.tileY() + " valid " + fab.isValid());
            for(Feed feed : side.feeds) System.out.println("    line " + feed.bore.tileX() + "," + feed.bore.tileY() + " -> " + feed.duct.block.name + " r" + feed.duct.rotation + " at " + feed.duct.tileX() + "," + feed.duct.tileY()
                + " -> " + feed.turret.block.name + " at " + feed.turret.tileX() + "," + feed.turret.tileY() + " ammo " + ((TurretBuild)feed.turret).totalAmmo + " valid " + feed.turret.isValid() + "/" + feed.duct.isValid() + "/" + feed.bore.isValid());
        }
    }

    //region the map

    /**
     * Loads a map, or makes one, and takes off everything on it.
     * @return where the two cores go: the player's side first
     */
    static @Nullable int[] load(String map){
        int[] at;
        if(map.startsWith("gen:")){
            Team enemy = Wild.make(Integer.parseInt(map.substring(4)), "none");
            if(enemy == null) return null;
            CoreBuild mine = Vars.state.rules.defaultTeam.core(), theirs = enemy.core();
            at = new int[]{mine.tileX(), mine.tileY(), theirs.tileX(), theirs.tileY()};
        }else{
            Vars.logic.reset();
            //a name alone is one of the campaign maps test.sh unpacks
            Fi file = new Fi(map).exists() ? new Fi(map) : new Fi("build/test-maps/maps/erekir/" + map + ".msav");
            SaveIO.load(file);
            Planets.erekir.applyRules(Vars.state.rules, false);
            Rules rules = Vars.state.rules;
            CoreBuild mine = rules.defaultTeam.core();
            if(mine == null) return null;
            //the other side's core nearest to the player's that is far enough off for an outpost between
            CoreBuild theirs = null;
            for(CoreBuild core : rules.waveTeam.cores()){
                if(core.dst(mine) < 70f * Vars.tilesize) continue;
                if(theirs == null || core.dst(mine) < theirs.dst(mine)) theirs = core;
            }
            if(theirs == null) theirs = rules.waveTeam.cores().max(c -> c.dst(mine));
            if(theirs == null) return null;
            at = new int[]{mine.tileX(), mine.tileY(), theirs.tileX(), theirs.tileY()};
        }
        //nothing of anyone's stays: buildings, units, the map's scripts
        for(Tile tile : Vars.world.tiles){
            if(tile.build != null) tile.setBlock(Blocks.air);
        }
        Groups.unit.each(Unit::remove);
        Vars.state.rules.objectives.clear();
        Vars.state.rules.weather.clear();
        return at;
    }

    /** The ground way from one core to the other, as tile positions, or an empty list if there is none. */
    static IntSeq groundWay(int x0, int y0, int x1, int y1){
        int w = Vars.world.width(), h = Vars.world.height();
        int[] from = new int[w * h];
        java.util.Arrays.fill(from, -1);
        IntQueue queue = new IntQueue();
        int start = x0 + y0 * w, end = x1 + y1 * w;
        from[start] = start;
        queue.addLast(start);
        while(!queue.isEmpty()){
            int at = queue.removeFirst();
            if(at == end) break;
            int x = at % w, y = at / w;
            for(Point2 d : Geometry.d4){
                int nx = x + d.x, ny = y + d.y;
                if(nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                int next = nx + ny * w;
                if(from[next] != -1) continue;
                Tile tile = Vars.world.tile(nx, ny);
                if(next != end && (tile.solid() || tile.floor().isDeep())) continue;
                from[next] = at;
                queue.addLast(next);
            }
        }
        IntSeq way = new IntSeq();
        if(from[end] == -1) return way;
        for(int at = end; at != start; at = from[at]) way.add(at);
        way.add(start);
        way.reverse();
        return way;
    }

    /** A point some tiles along the way from a side's end, or along the straight line if there is no way. */
    static Point2 along(IntSeq way, boolean fromEnd, float tiles, int x0, int y0, int x1, int y1){
        if(way.size > 2){
            int i = Mathf.clamp((int)tiles, 0, way.size - 1);
            int pos = way.get(fromEnd ? way.size - 1 - i : i);
            return new Point2(pos % Vars.world.width(), pos / Vars.world.width());
        }
        float d = Mathf.dst(x0, y0, x1, y1);
        float f = Mathf.clamp(tiles / Math.max(d, 1f));
        return fromEnd ? new Point2(Math.round(x1 + (x0 - x1) * f), Math.round(y1 + (y0 - y1) * f)) : new Point2(Math.round(x0 + (x1 - x0) * f), Math.round(y0 + (y1 - y0) * f));
    }

    /** The base each side gets, laid out the same way facing the other side. */
    static void build(Side side, IntSeq way, boolean fromEnd){
        Side other = side == sides[0] ? sides[1] : sides[0];
        int cx = side.coreX, cy = side.coreY;
        //which of the four ways the base faces: towards a point some way along the way to the other side
        Point2 ahead = along(way, fromEnd, 20, cx, cy, other.coreX, other.coreY);
        int r = facing(cx, cy, ahead.x, ahead.y);
        Floor ground = firmFloor(cx, cy);
        put(side, Blocks.coreBastion, cx, cy, 0, 0, r, ground);
        //three fabricators behind the core
        side.fabs.add(put(side, Blocks.tankFabricator, cx, cy, -7, -3, r, ground));
        side.fabs.add(put(side, Blocks.shipFabricator, cx, cy, -7, 1, r, ground));
        side.fabs.add(put(side, Blocks.mechFabricator, cx, cy, -11, -1, r, ground));
        //two drills of its economy, behind and out to the sides, beyond its turrets' reach (as a base's outlying drills are)
        side.econ.add(put(side, Blocks.largePlasmaBore, cx, cy, -14, -24, r, ground));
        side.econ.add(put(side, Blocks.largePlasmaBore, cx, cy, -14, 24, r, ground));
        side.econ.removeAll(b -> b == null);
        //two breaches in front, each fed from a bore behind it by a line passing beside the core
        line(side, cx, cy, r, -4, 6, -6, ground);
        line(side, cx, cy, r, -4, 6, 7, ground);
        //the outpost some way out, facing on along the way
        Point2 out = along(way, fromEnd, 36, cx, cy, other.coreX, other.coreY);
        Point2 beyond = along(way, fromEnd, 56, cx, cy, other.coreX, other.coreY);
        int ro = facing(out.x, out.y, beyond.x, beyond.y);
        line(side, out.x, out.y, ro, -7, 3, -4, ground);
        line(side, out.x, out.y, ro, -7, 3, 4, ground);
    }

    /** A bore at (bu, v) and a breach at (tu, v), in the base's own frame, and the ducts between them. */
    static void line(Side side, int ox, int oy, int r, int bu, int tu, int v, Floor ground){
        Building bore = put(side, Blocks.largePlasmaBore, ox, oy, bu, v, r, ground);
        Building turret = put(side, Blocks.breach, ox, oy, tu, v, r, ground);
        Building first = null;
        for(int u = bu + 2; u <= tu - 2; u++){
            Building duct = put(side, Blocks.duct, ox, oy, u, v, r, ground);
            if(first == null) first = duct;
        }
        if(bore == null || turret == null || first == null) return;
        Feed feed = new Feed();
        feed.bore = bore;
        feed.duct = first;
        feed.turret = turret;
        side.feeds.add(feed);
    }

    /** Which of the four ways (0 east, 1 north, 2 west, 3 south) points most nearly from one place to another. */
    static int facing(int x0, int y0, int x1, int y1){
        float angle = Angles.angle(x0, y0, x1, y1);
        return Mathf.mod(Math.round(angle / 90f), 4);
    }

    /** A block put down at (u, v) in a frame turned r quarter turns, with the ground under it made firm and clear. */
    static @Nullable Building put(Side side, Block block, int ox, int oy, int u, int v, int r, Floor ground){
        int x = ox, y = oy;
        switch(r){
            case 0 -> { x += u; y += v; }
            case 1 -> { x -= v; y += u; }
            case 2 -> { x -= u; y -= v; }
            default -> { x += v; y -= u; }
        }
        int low = -(block.size - 1) / 2, high = block.size / 2;
        for(int dx = low - 1; dx <= high + 1; dx++){
            for(int dy = low - 1; dy <= high + 1; dy++){
                Tile tile = Vars.world.tile(x + dx, y + dy);
                if(tile == null) continue;
                boolean inside = dx >= low && dx <= high && dy >= low && dy <= high;
                if(tile.build != null && inside) tile.setBlock(Blocks.air);
                if(tile.block().isStatic()) tile.setBlock(Blocks.air);
                if(tile.floor().isDeep() || !tile.floor().placeableOn || tile.floor().solid) tile.setFloor(ground);
            }
        }
        Tile tile = Vars.world.tile(x, y);
        if(tile == null) return null;
        tile.setBlock(block, side.team, block.rotate ? r : 0);
        return tile.build;
    }

    /** The floor most of the ground round a place is made of, of the kinds a block can stand on. */
    static Floor firmFloor(int x, int y){
        ObjectIntMap<Floor> count = new ObjectIntMap<>();
        for(int dx = -6; dx <= 6; dx++){
            for(int dy = -6; dy <= 6; dy++){
                Tile tile = Vars.world.tile(x + dx, y + dy);
                if(tile != null && tile.floor().placeableOn && !tile.floor().isLiquid && !tile.floor().solid) count.increment(tile.floor());
            }
        }
        Floor best = (Floor)Blocks.rhyolite;
        int most = 0;
        for(var entry : count) if(entry.value > most){
            most = entry.value;
            best = entry.key;
        }
        return best;
    }

    //endregion
}
