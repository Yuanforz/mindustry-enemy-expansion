package maptools;

import arc.files.*;
import arc.math.*;
import arc.math.geom.Point2;
import mindustry.world.blocks.heat.*;
import mindustry.game.Rules.TeamRule;
import arc.struct.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.core.GameState.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.environment.*;
import mindustry.world.blocks.liquid.*;
import mindustry.world.blocks.logic.*;
import mindustry.world.blocks.payloads.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.storage.*;
import mindustry.world.blocks.units.*;
import mindustry.world.meta.*;

import java.util.*;

/**
 * Static facts about a map for the AI rewrite: rules, waves, cores, resources by distance from the
 * player core, ground/hover/leg connectivity with bottlenecks, buildings of each team, an overview picture.
 *
 * Usage: MapScan data-dir map.msav...
 */
public class MapScan{
    static int W, H;
    static Tile[] tiles;
    static boolean[] wall, deep, groundOk, hoverOk, legOk, inArea;
    static int[] clear; //distance to nearest ground-impassable tile (4-neighbour steps), 0 for impassable
    static int px, py; //player core
    static StringBuilder out;

    static void p(String s){ out.append(s).append('\n'); }

    public static void main(String[] args){
        xtest.Boot.boot(args[0]);
        for(int a = 1; a < args.length; a++){
            out = new StringBuilder();
            Fi file = new Fi(args[a]);
            Vars.logic.reset();
            SaveIO.load(file);
            Vars.state.set(State.playing);
            xtest.Boot.frames(1);
            try{
                scan(file.nameWithoutExtension());
            }catch(Throwable t){
                t.printStackTrace();
                p("ERROR " + t);
            }
            System.out.println(out);
        }
        System.exit(0);
    }

    static int idx(int x, int y){ return x + y * W; }

    static Seq<Building> allBuilds(){
        Seq<Building> out = new Seq<>();
        for(Tile t : Vars.world.tiles) if(t.build != null && t.build.tile == t) out.add(t.build);
        return out;
    }

    static String fmtTicks(float ticks){
        float s = ticks / 60f;
        return s >= 60 ? String.format("%.1fmin", s / 60f) : String.format("%.0fs", s);
    }

    static String stacks(Iterable<ItemStack> st){
        StringBuilder b = new StringBuilder();
        for(ItemStack s : st) b.append(b.length() > 0 ? " " : "").append(s.item.name).append('=').append(s.amount);
        return b.length() == 0 ? "-" : b.toString();
    }

    static void scan(String name){
        Rules r = Vars.state.rules;
        W = Vars.world.width(); H = Vars.world.height();
        tiles = new Tile[W * H];
        for(Tile t : Vars.world.tiles) tiles[idx(t.x, t.y)] = t;
        Team enemy = r.waveTeam, player = r.defaultTeam;
        p("######## MAP " + name + " " + W + "x" + H);

        //---- rules
        TeamRule er = r.teams.get(enemy), pr = r.teams.get(player);
        p("== rules");
        p("mode: attack=" + r.attackMode + " waves=" + r.waves + " waveTimer=" + r.waveTimer + " winWave=" + r.winWave + " waveSpacing=" + fmtTicks(r.waveSpacing) + " initialWaveSpacing=" + fmtTicks(r.initialWaveSpacing)
            + " wavesSpawnAtCores=" + r.wavesSpawnAtCores + " airUseSpawns=" + r.airUseSpawns + " waveTeam=" + enemy.name + " defaultTeam=" + player.name);
        p("area: limitMapArea=" + r.limitMapArea + (r.limitMapArea ? " rect x=" + r.limitX + " y=" + r.limitY + " w=" + r.limitWidth + " h=" + r.limitHeight : "") + " disableOutsideArea=" + r.disableOutsideArea);
        p("misc: fog=" + r.fog + " staticFog=" + r.staticFog + " placeRangeCheck=" + r.placeRangeCheck + " enemyCoreBuildRadius=" + r.enemyCoreBuildRadius + " polygonCoreProtection=" + r.polygonCoreProtection
            + " unitCap=" + r.unitCap + " unitCapVariable=" + r.unitCapVariable + " coreIncinerates=" + r.coreIncinerates + " onlyDepositCore=" + r.onlyDepositCore + " coreCapture=" + r.coreCapture
            + " buildSpeedMult=" + r.buildSpeedMultiplier + " buildCostMult=" + r.buildCostMultiplier + " unitBuildSpeedMult=" + r.unitBuildSpeedMultiplier + " unitCostMult=" + r.unitCostMultiplier
            + " deconstructRefund=" + r.deconstructRefundMultiplier + " env=" + r.env + " weather=" + r.weather.size + " disableWorldProcessors=" + r.disableWorldProcessors);
        p("enemy team rule: rtsAi=" + er.rtsAi + " squad=" + er.rtsMinSquad + ".." + er.rtsMaxSquad + " minWeight=" + er.rtsMinWeight + " buildAi=" + er.buildAi + " buildAiTier=" + er.buildAiTier
            + " infiniteResources=" + er.infiniteResources + " fillItems=" + er.fillItems + " cheat=" + er.cheat + " aiCoreSpawn=" + er.aiCoreSpawn + " unitBuildSpeed=" + er.unitBuildSpeedMultiplier
            + " buildSpeed=" + er.buildSpeedMultiplier + " blockHealth=" + er.blockHealthMultiplier + " unitHealth=" + er.unitHealthMultiplier + " unitDamage=" + er.unitDamageMultiplier
            + " -> effective enemy buildSpeed(team)=" + r.buildSpeed(enemy));
        p("player team rule: infiniteResources=" + pr.infiniteResources + " buildSpeed=" + pr.buildSpeedMultiplier + " unitBuildSpeed=" + pr.unitBuildSpeedMultiplier);
        p("loadout: " + stacks(r.loadout));
        try{
            Rules c = r.copy();
            Planets.erekir.applyRules(c);
            TeamRule ce = c.teams.get(c.waveTeam);
            p("campaign-effective (after Erekir planet rules, normal difficulty): fog=" + c.fog + " staticFog=" + c.staticFog + " placeRangeCheck=" + c.placeRangeCheck + " onlyDepositCore=" + c.onlyDepositCore + " coreDestroyClear=" + c.coreDestroyClear
                + " enemy rtsAi=" + ce.rtsAi + " rtsMaxSquad=" + ce.rtsMaxSquad + " enemy buildSpeed(team)=" + c.buildSpeed(c.waveTeam) + " player buildSpeed(team)=" + c.buildSpeed(c.defaultTeam) + " objectiveTimerMult=" + c.objectiveTimerMultiplier
                + " enemy unitHealth=" + ce.unitHealthMultiplier + " unitDamage=" + ce.unitDamageMultiplier);
        }catch(Throwable t){ p("campaign-effective: failed " + t); }
        StringBuilder banned = new StringBuilder();
        for(Block b : r.bannedBlocks) banned.append(b.name).append(' ');
        for(UnitType u : r.bannedUnits) banned.append(u.name).append(' ');
        p("banned: " + (banned.length() == 0 ? "-" : banned) + " revealedBlocks=" + r.revealedBlocks.size + " blockLimits=" + r.blockLimits.size);
        if(r.objectives.all.size > 0){
            p("objectives:");
            for(var o : r.objectives.all){
                StringBuilder parents = new StringBuilder();
                for(var parent : o.parents) parents.append(r.objectives.all.indexOf(parent)).append(',');
                p("  " + r.objectives.all.indexOf(o) + " " + o.getClass().getSimpleName() + " after[" + parents + "] " + describe(o) + (o.flagsAdded.length > 0 ? " flags+" + Arrays.toString(o.flagsAdded) : ""));
            }
        }

        //---- cores, spawns
        p("== cores and spawns");
        Seq<Building> pcores = new Seq<>(), ecores = new Seq<>();
        for(Building b : allBuilds()){
            if(b instanceof CoreBlock.CoreBuild){
                if(b.team == player) pcores.add(b);
                else if(b.team == enemy) ecores.add(b);
                else p("  core of other team " + b.team.name + " " + b.block.name + " at " + b.tileX() + "," + b.tileY());
            }
        }
        if(pcores.isEmpty()){ p("NO PLAYER CORE"); return; }
        Building pc = pcores.first();
        px = pc.tileX(); py = pc.tileY();
        for(Building c : pcores) p("  player core " + c.block.name + " at " + c.tileX() + "," + c.tileY() + " items: " + itemsOf(c));
        for(Building c : ecores) p("  enemy core " + c.block.name + " at " + c.tileX() + "," + c.tileY() + " dist " + (int)Mathf.dst(px, py, c.tileX(), c.tileY()) + " items: " + itemsOf(c));
        IntSeq spawns = new IntSeq();
        for(Tile t : Vars.spawner.getSpawns()){
            spawns.add(idx(t.x, t.y));
            p("  ground spawn at " + t.x + "," + t.y + " dist " + (int)Mathf.dst(px, py, t.x, t.y));
        }
        //units already on the map
        ObjectIntMap<String> units = new ObjectIntMap<>();
        for(Unit u : Groups.unit) units.increment(u.team.name + ":" + u.type.name);
        p("  units on map: " + units);

        //---- waves
        if(r.spawns.size > 0 && (r.waves || r.waveTimer)){
            p("== waves (" + r.spawns.size + " groups)");
            float first = (r.initialWaveSpacing <= 0 ? r.waveSpacing * 2 : r.initialWaveSpacing);
            p("  first wave after " + fmtTicks(first) + " (normal; x0.8 hard), then every " + fmtTicks(r.waveSpacing));
            for(SpawnGroup g : r.spawns){
                p("  group " + g.type.name + (g.type.flying ? "(air)" : "") + " begin=" + (g.begin + 1) + " end=" + (g.end >= SpawnGroup.never ? "-" : (g.end + 1)) + " spacing=" + g.spacing + " amount=" + g.unitAmount
                    + " scaling=" + g.unitScaling + " max=" + g.max + " shields=" + g.shields + "+" + g.shieldScaling + "/w" + (g.effect != null ? " effect=" + g.effect.name : "") + (g.spawn != -1 ? " spawn=" + Point2.x(g.spawn) + "," + Point2.y(g.spawn) : ""));
            }
            for(int w = 0; w < 30; w++){
                ObjectIntMap<String> m = new ObjectIntMap<>();
                float hp = 0; int air = 0, total = 0;
                for(SpawnGroup g : r.spawns){
                    int n = g.getSpawned(w);
                    if(n <= 0) continue;
                    int mult = g.spawn == -1 ? Math.max(1, spawns.size) : 1;
                    if(g.type.flying && !r.airUseSpawns) mult = g.spawn == -1 ? Math.max(1, spawns.size) : 1;
                    m.increment(g.type.name, n * mult);
                    total += n * mult;
                    if(g.type.flying) air += n * mult;
                    hp += (g.type.health + g.getShield(w)) * n * mult;
                }
                if(w < 15 || w % 5 == 4) p("  wave " + (w + 1) + " @" + fmtTicks(first + r.waveSpacing * w) + ": " + total + " units (" + air + " air) hp~" + (int)hp + " " + m);
            }
        }

        //---- terrain
        terrain(r);
        String dbg = System.getProperty("clear");
        if(dbg != null){
            String[] q = dbg.split(",");
            int x0 = Integer.parseInt(q[0]), y0 = Integer.parseInt(q[1]), w = Integer.parseInt(q[2]), h = Integer.parseInt(q[3]);
            for(int y = y0 + h - 1; y >= y0; y--){
                StringBuilder b = new StringBuilder(String.format("%4d ", y));
                for(int x = x0; x < x0 + w; x++){ int c = clear[idx(x, y)] / 3; b.append(!groundOk[idx(x, y)] ? '#' : c > 9 ? '+' : (char)('0' + c)); }
                p(b.toString());
            }
        }
        walk = dist(groundOk, 0, anchor(groundOk, px, py), null);
        resources(r);
        connectivity(pc, ecores, spawns);
        buildings(enemy, player, ecores);
        processors();
        picture(pc, ecores, spawns, enemy, player);
    }

    static String describe(MapObjectives.MapObjective o){
        StringBuilder b = new StringBuilder();
        for(java.lang.reflect.Field f : o.getClass().getFields()){
            if(java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            String n = f.getName();
            if(n.equals("parents") || n.equals("markers") || n.equals("editorX") || n.equals("editorY") || n.equals("flagsAdded") || n.equals("flagsRemoved") || n.equals("details") || n.equals("hidden")) continue;
            try{
                Object v = f.get(o);
                if(v == null) continue;
                String s = v instanceof Object[] arr ? Arrays.toString(arr) : v instanceof mindustry.ctype.MappableContent mc ? mc.name : String.valueOf(v);
                if(s.equals("") || s.equals("false")) continue;
                if(n.equals("duration") && v instanceof Float fl) s = fmtTicks(fl);
                b.append(n).append('=').append(s).append(' ');
            }catch(Exception ignored){}
        }
        return b.toString().trim();
    }

    static String itemsOf(Building b){
        if(b.items == null) return "-";
        StringBuilder s = new StringBuilder();
        b.items.each((item, amount) -> s.append(item.name).append('=').append(amount).append(' '));
        return s.length() == 0 ? "empty" : s.toString().trim();
    }

    static void terrain(Rules r){
        wall = new boolean[W * H]; deep = new boolean[W * H]; groundOk = new boolean[W * H]; hoverOk = new boolean[W * H]; legOk = new boolean[W * H]; inArea = new boolean[W * H];
        for(int i = 0; i < W * H; i++){
            Tile t = tiles[i];
            wall[i] = (t.block().isStatic() && t.block().solid) || t.floor().solid;
            deep[i] = t.floor().isDeep();
            hoverOk[i] = !wall[i];
            legOk[i] = !t.legSolid();
            inArea[i] = !r.limitMapArea || (t.x >= r.limitX && t.y >= r.limitY && t.x < r.limitX + r.limitWidth && t.y < r.limitY + r.limitHeight);
        }
        for(int y = 0; y < H; y++) for(int x = 0; x < W; x++){
            int i = idx(x, y);
            if(wall[i]){ groundOk[i] = false; continue; }
            boolean allDeep = deep[i];
            if(allDeep) for(int d = 0; d < 4; d++){
                int nx = x + Geometry4.dx[d], ny = y + Geometry4.dy[d];
                if(nx >= 0 && ny >= 0 && nx < W && ny < H && !deep[idx(nx, ny)]){ allDeep = false; break; }
            }
            groundOk[i] = !allDeep;
        }
        //clearance by BFS from impassable tiles and the edge
        clear = new int[W * H];
        Arrays.fill(clear, -1);
        IntQueue q = new IntQueue();
        //small lumps of rock (boulder-sized, up to 12 tiles) do not narrow a corridor much: units walk round them, so they are left out of the width measure
        boolean[] small = new boolean[W * H];
        {
            int[] comp = new int[W * H];
            Arrays.fill(comp, -1);
            IntQueue cq = new IntQueue();
            IntSeq members = new IntSeq();
            for(int i0 = 0; i0 < W * H; i0++){
                if(groundOk[i0] || comp[i0] != -1) continue;
                members.clear();
                comp[i0] = i0; cq.addLast(i0);
                boolean edge = false;
                while(cq.size > 0 && members.size <= 12){
                    int a = cq.removeFirst(); members.add(a);
                    int ax = a % W, ay = a / W;
                    if(ax == 0 || ay == 0 || ax == W - 1 || ay == H - 1) edge = true;
                    for(int d = 0; d < 4; d++){
                        int nx = ax + Geometry4.dx[d], ny = ay + Geometry4.dy[d];
                        if(nx < 0 || ny < 0 || nx >= W || ny >= H) continue;
                        int j = idx(nx, ny);
                        if(!groundOk[j] && comp[j] == -1){ comp[j] = i0; cq.addLast(j); }
                    }
                }
                //a big lump: mark the rest of it seen without measuring
                while(cq.size > 0){
                    int a = cq.removeFirst();
                    int ax = a % W, ay = a / W;
                    for(int d = 0; d < 4; d++){
                        int nx = ax + Geometry4.dx[d], ny = ay + Geometry4.dy[d];
                        if(nx < 0 || ny < 0 || nx >= W || ny >= H) continue;
                        int j = idx(nx, ny);
                        if(!groundOk[j] && comp[j] == -1){ comp[j] = i0; cq.addLast(j); }
                    }
                    members.add(a);
                }
                if(members.size <= 12 && !edge) for(int k = 0; k < members.size; k++) small[members.get(k)] = true;
            }
        }
        //chamfer (3-4) distance to the nearest impassable tile or the map edge, in thirds of a tile: close to the straight-line distance, also across diagonal gaps
        final int INF = 1 << 28;
        for(int y = 0; y < H; y++) for(int x = 0; x < W; x++){
            int i = idx(x, y);
            clear[i] = (!groundOk[i] && !small[i]) ? 0 : (x == 0 || y == 0 || x == W - 1 || y == H - 1) ? 3 : INF;
        }
        for(int y = 0; y < H; y++) for(int x = 0; x < W; x++){
            int i = idx(x, y), c = clear[i];
            if(c == 0) continue;
            if(x > 0) c = Math.min(c, clear[i - 1] + 3);
            if(y > 0){
                c = Math.min(c, clear[i - W] + 3);
                if(x > 0) c = Math.min(c, clear[i - W - 1] + 4);
                if(x < W - 1) c = Math.min(c, clear[i - W + 1] + 4);
            }
            clear[i] = c;
        }
        for(int y = H - 1; y >= 0; y--) for(int x = W - 1; x >= 0; x--){
            int i = idx(x, y), c = clear[i];
            if(c == 0) continue;
            if(x < W - 1) c = Math.min(c, clear[i + 1] + 3);
            if(y < H - 1){
                c = Math.min(c, clear[i + W] + 3);
                if(x < W - 1) c = Math.min(c, clear[i + W + 1] + 4);
                if(x > 0) c = Math.min(c, clear[i + W - 1] + 4);
            }
            clear[i] = c;
        }
        for(int i = 0; i < W * H; i++) if(!groundOk[i]) clear[i] = 0;
        int walls = 0, deeps = 0, open = 0, area = 0;
        for(int i = 0; i < W * H; i++){ if(wall[i]) walls++; else if(!groundOk[i]) deeps++; else open++; if(inArea[i]) area++; }
        p("== terrain");
        p(String.format("  tiles: wall %d%%, deep-liquid (ground-impassable) %d%%, ground-open %d%%; initial build area %d%% of map", walls * 100 / (W * H), deeps * 100 / (W * H), open * 100 / (W * H), area * 100 / (W * H)));
    }

    static class Geometry4{
        static int[] dx = {1, 0, -1, 0}, dy = {0, 1, 0, -1};
        static int[] d8x = {1, 0, -1, 0, 1, -1, -1, 1}, d8y = {0, 1, 0, -1, 1, 1, -1, -1};
    }

    static boolean exposed(int x, int y){
        for(int d = 0; d < 4; d++){
            int nx = x + Geometry4.dx[d], ny = y + Geometry4.dy[d];
            if(nx >= 0 && ny >= 0 && nx < W && ny < H && !wall[idx(nx, ny)]) return true;
        }
        return false;
    }

    /** One kind of resource: tiles counted by straight distance from the player's core. */
    static class Res{
        String name; int total, area, d30, d60, d100; float nearest = 9999, nearestArea = 9999; int nx, ny;
        Res(String n){ name = n; }
        void add(int x, int y){
            total++;
            float d = Mathf.dst(x, y, px, py);
            if(d <= 30) d30++;
            if(d <= 60) d60++;
            if(d <= 100) d100++;
            boolean in = inArea[idx(x, y)];
            if(in) area++;
            if(d < nearest){ nearest = d; nx = x; ny = y; }
            if(in && d < nearestArea) nearestArea = d;
            //walking distance: the tile itself, or the open ground beside it (ore in rock, deep liquid)
            float w = Float.MAX_VALUE;
            for(int k = -1; k < 8; k++){
                int ax = k < 0 ? x : x + Geometry4.d8x[k], ay = k < 0 ? y : y + Geometry4.d8y[k];
                if(ax < 0 || ay < 0 || ax >= W || ay >= H) continue;
                float g = walk[idx(ax, ay)];
                if(g < w) w = g;
            }
            if(w < walkNearest){ walkNearest = w; wx = x; wy = y; }
            if(w < Float.MAX_VALUE) reach++;
        }
        float walkNearest = Float.MAX_VALUE; int wx, wy, reach;
        String line(){
            return String.format("  %-26s total %6d | in start area %6d | <=30: %5d  <=60: %5d  <=100: %5d | nearest %s%s | walkable from player %d, nearest by ground path %s", name, total, area, d30, d60, d100,
                total == 0 ? "-" : (int)nearest + " at " + nx + "," + ny, nearestArea < 9999 && nearestArea != nearest ? " (nearest in start area " + (int)nearestArea + ")" : "",
                reach, walkNearest == Float.MAX_VALUE ? "none" : (int)walkNearest + " at " + wx + "," + wy);
        }
    }

    static IntSeq ventCenters = new IntSeq();
    static float[] walk;

    static void resources(Rules r){
        LinkedHashMap<String, Res> res = new LinkedHashMap<>();
        java.util.function.Function<String, Res> get = n -> res.computeIfAbsent(n, Res::new);
        get.apply("vent 3x3 (turbine fits)");
        boolean[] usedVent = new boolean[W * H];
        ventCenters.clear();
        int ventTiles = 0, ventOccupied = 0;
        ObjectIntMap<String> ventOcc = new ObjectIntMap<>();
        ThermalGenerator turbine = (ThermalGenerator)Blocks.turbineCondenser;
        for(int y = 1; y < H - 1; y++) for(int x = 1; x < W - 1; x++){
            Tile t = tiles[idx(x, y)];
            if(t.floor().attributes.get(Attribute.steam) > 0) ventTiles++;
            boolean fits = true;
            for(int dx = -1; dx <= 1 && fits; dx++) for(int dy = -1; dy <= 1; dy++){
                int j = idx(x + dx, y + dy);
                Tile o = tiles[j];
                if(usedVent[j] || o.floor().attributes.get(Attribute.steam) <= 0 || (o.block().isStatic() && o.block().solid)){ fits = false; break; }
            }
            if(!fits || !turbine.canPlaceOn(t, Team.sharded, 0)) continue;
            for(int dx = -1; dx <= 1; dx++) for(int dy = -1; dy <= 1; dy++) usedVent[idx(x + dx, y + dy)] = true;
            ventCenters.add(idx(x, y));
            get.apply("vent 3x3 (turbine fits)").add(x, y);
            if(t.build != null){ ventOccupied++; ventOcc.increment(t.build.team.name + ":" + t.block().name); }
        }
        for(int y = 0; y < H; y++) for(int x = 0; x < W; x++){
            int i = idx(x, y);
            Tile t = tiles[i];
            if(wall[i]){
                if(!t.block().isStatic()) continue;
                if(!exposed(x, y)) continue;
                Item drop = t.wallDrop();
                if(drop != null) get.apply("wall ore " + drop.name + " (exposed)").add(x, y);
                if(t.block().attributes.get(Attribute.sand) > 0) get.apply("sand wall (exposed)").add(x, y);
            }else{
                if(t.overlay() != null && t.overlay().itemDrop != null && !(t.overlay() instanceof SpawnBlock)) get.apply("floor ore " + t.overlay().itemDrop.name).add(x, y);
                else if(t.floor().itemDrop != null) get.apply("floor drop " + t.floor().itemDrop.name).add(x, y);
                Liquid l = t.floor().liquidDrop;
                if(l != null) get.apply("liquid " + l.name + (t.floor().isDeep() ? " (deep)" : "")).add(x, y);
            }
        }
        p("== resources (straight-line distance from player core " + px + "," + py + "; wall ores = exposed wall tiles)");
        p("  vent tiles " + ventTiles + "; distinct 3x3 turbine spots " + ventCenters.size + (ventOccupied > 0 ? "; of those already built on: " + ventOccupied + " " + ventOcc : ""));
        ArrayList<String> keys = new ArrayList<>(res.keySet());
        Collections.sort(keys, Comparator.comparing(k -> k.startsWith("vent") ? "0" + k : k));
        for(String k : keys) p(res.get(k).line());
        //list the 10 nearest vents
        IntSeq sorted = new IntSeq(ventCenters);
        Integer[] arr = new Integer[sorted.size];
        for(int i = 0; i < sorted.size; i++) arr[i] = sorted.get(i);
        Arrays.sort(arr, Comparator.comparingDouble(v -> Mathf.dst(v % W, v / W, px, py)));
        StringBuilder v = new StringBuilder("  nearest vents:");
        for(int i = 0; i < Math.min(12, arr.length); i++) v.append(" ").append(arr[i] % W).append(",").append(arr[i] / W).append("(").append((int)Mathf.dst(arr[i] % W, arr[i] / W, px, py)).append(tiles[arr[i]].build != null ? "," + tiles[arr[i]].build.team.name : "").append(")");
        p(v.toString());
    }

    /** Dijkstra over tiles allowed by pass, 8-neighbour (diagonal only if both sides open), from src; returns distance and parent. */
    static float[] dist(boolean[] pass, int minClear, int src, int[] parent){
        float[] d = new float[W * H];
        Arrays.fill(d, Float.MAX_VALUE);
        if(parent != null) Arrays.fill(parent, -1);
        PriorityQueue<long[]> pq = new PriorityQueue<>(Comparator.comparingLong(a -> a[0]));
        d[src] = 0;
        pq.add(new long[]{0, src});
        while(!pq.isEmpty()){
            long[] top = pq.poll();
            int i = (int)top[1];
            float di = top[0] / 1000f;
            if(di > d[i] + 0.0001f) continue;
            int x = i % W, y = i / W;
            for(int k = 0; k < 8; k++){
                int nx = x + Geometry4.d8x[k], ny = y + Geometry4.d8y[k];
                if(nx < 0 || ny < 0 || nx >= W || ny >= H) continue;
                int j = idx(nx, ny);
                if(!pass[j] || (minClear > 0 && clear[j] < minClear * 3)) continue;
                if(k >= 4 && (!pass[idx(nx, y)] || !pass[idx(x, ny)])) continue;
                float nd = di + (k >= 4 ? 1.4142f : 1f);
                if(nd < d[j]){
                    d[j] = nd;
                    if(parent != null) parent[j] = i;
                    pq.add(new long[]{(long)(nd * 1000), j});
                }
            }
        }
        return d;
    }

    /** The nearest tile to (x,y) that pass allows, preferring roomy ones (cores sit on open ground, but their own tiles may be next to walls). */
    static int anchor(boolean[] pass, int x, int y){
        int best = -1; float score = -1;
        for(int dx = -6; dx <= 6; dx++) for(int dy = -6; dy <= 6; dy++){
            int nx = x + dx, ny = y + dy;
            if(nx < 0 || ny < 0 || nx >= W || ny >= H) continue;
            int j = idx(nx, ny);
            if(!pass[j]) continue;
            float s = Math.min(clear[j] / 3, 6) * 10 - Mathf.dst(dx, dy);
            if(s > score){ score = s; best = j; }
        }
        return best;
    }

    static int[] uf;
    static int find(int i){ while(uf[i] != i){ uf[i] = uf[uf[i]]; i = uf[i]; } return i; }
    static IntSeq[] byC;

    static boolean[] and(boolean[] pass, boolean[] blocked){
        boolean[] o = new boolean[pass.length];
        for(int i = 0; i < o.length; i++) o[i] = pass[i] && !blocked[i];
        return o;
    }

    /** Widest ground route between two tiles: adds tiles roomiest first until they join; returns {clearance, tile} at the join, or null. */
    static int[] widest(boolean[] blocked, int a0, int b0){
        if(byC == null){
            int maxC = 0;
            for(int c : clear) maxC = Math.max(maxC, c);
            byC = new IntSeq[maxC + 1];
            for(int c = 0; c <= maxC; c++) byC[c] = new IntSeq();
            for(int i = 0; i < W * H; i++) if(clear[i] > 0 && groundOk[i]) byC[clear[i]].add(i);
        }
        if(blocked[a0] || blocked[b0]) return null;
        uf = new int[W * H];
        for(int i = 0; i < W * H; i++) uf[i] = i;
        boolean[] added = new boolean[W * H];
        for(int c = byC.length - 1; c >= 1; c--){
            for(int k = 0; k < byC[c].size; k++){
                int i = byC[c].get(k), x = i % W, y = i / W;
                if(blocked[i]) continue;
                added[i] = true;
                boolean merged = false;
                for(int d = 0; d < 8; d++){
                    int nx = x + Geometry4.d8x[d], ny = y + Geometry4.d8y[d];
                    if(nx < 0 || ny < 0 || nx >= W || ny >= H) continue;
                    int j = idx(nx, ny);
                    if(!added[j]) continue;
                    int a = find(i), b = find(j);
                    if(a != b){ uf[a] = b; merged = true; }
                }
                if((merged || i == a0 || i == b0) && added[a0] && added[b0] && find(a0) == find(b0)) return new int[]{c, i};
            }
        }
        return null;
    }

    static void connectivity(Building pc, Seq<Building> ecores, IntSeq spawns){
        p("== connectivity from player core (ground = tanks: no walls, no fully deep liquid; hover = elude: no walls; legs = mechs: not deep inside rock; air = always)");
        IntSeq targets = new IntSeq();
        Seq<String> labels = new Seq<>();
        for(Building c : ecores){ targets.add(idx(c.tileX(), c.tileY())); labels.add("enemy " + c.block.name.replace("core-", "") + "@" + c.tileX() + "," + c.tileY()); }
        for(int i = 0; i < spawns.size; i++){ targets.add(spawns.get(i)); labels.add("spawn@" + spawns.get(i) % W + "," + spawns.get(i) / W); }
        byC = null;
        int src = anchor(groundOk, px, py);
        int[] parent = new int[W * H];
        float[] dg = dist(groundOk, 0, src, parent);
        float[] dbig = dist(groundOk, 3, anchor(groundOk, px, py), null);
        float[] dh = dist(hoverOk, 0, anchor(hoverOk, px, py), null);
        float[] dl = dist(legOk, 0, anchor(legOk, px, py), null);
        int[] tAnchor = new int[targets.size];
        for(int t = 0; t < targets.size; t++) tAnchor[t] = anchor(groundOk, targets.get(t) % W, targets.get(t) / W);
        for(int t = 0; t < targets.size; t++){
            int ti = tAnchor[t] < 0 ? targets.get(t) : tAnchor[t];
            int tx = targets.get(t) % W, ty = targets.get(t) / W;
            float straight = Mathf.dst(px, py, tx, ty);
            String g = dg[ti] == Float.MAX_VALUE ? "NO" : String.valueOf((int)dg[ti]);
            String big = dbig[ti] == Float.MAX_VALUE ? "NO" : String.valueOf((int)dbig[ti]);
            int hi = anchor(hoverOk, tx, ty), li = anchor(legOk, tx, ty);
            String h = hi < 0 || dh[hi] == Float.MAX_VALUE ? "NO" : String.valueOf((int)dh[hi]);
            String l = li < 0 || dl[li] == Float.MAX_VALUE ? "NO" : String.valueOf((int)dl[li]);
            StringBuilder s = new StringBuilder(String.format("  -> %-28s straight %4d | ground %s | ground for >=5-wide routes %s | hover %s | legs %s", labels.get(t), (int)straight, g, big, h, l));
            if(dg[ti] != Float.MAX_VALUE && src >= 0){
                //ground routes, widest first: each one's narrowest point; then close it and look for the next
                s.append("\n       ground routes, widest first:");
                boolean[] blocked = new boolean[W * H];
                for(int k = 0; k < 3; k++){
                    int[] w = widest(blocked, src, ti);
                    if(w == null){ s.append(k == 0 ? " none" : " | no other route"); break; }
                    float[] d = k == 0 ? dg : dist(and(groundOk, blocked), 0, src, null);
                    s.append(String.format("%s ~%d wide at %d,%d (path %d)", k == 0 ? "" : " |", Math.max(1, Math.round(w[0] * 2 / 3f - 1)), w[1] % W, w[1] / W, (int)d[ti]));
                    int bx = w[1] % W, by = w[1] / W, rad = w[0] / 3 + 2;
                    if(Mathf.dst(bx, by, src % W, src / W) <= rad + 1 || Mathf.dst(bx, by, ti % W, ti / W) <= rad + 1){ s.append(" (the endpoint area itself is the narrowest part: the route is at least this wide throughout)"); break; }
                    for(int dx = -rad; dx <= rad; dx++) for(int dy = -rad; dy <= rad; dy++){
                        int nx = bx + dx, ny = by + dy;
                        if(nx >= 0 && ny >= 0 && nx < W && ny < H && dx * dx + dy * dy <= rad * rad) blocked[idx(nx, ny)] = true;
                    }
                }
                IntSeq path = new IntSeq();
                for(int at = ti; at != -1; at = parent[at]) path.add(at);
                StringBuilder prof = new StringBuilder("\n       corridor width along shortest ground route (player->target, ~12 samples):");
                for(int k = path.size - 1; k >= 0; k -= Math.max(1, path.size / 12)) prof.append(' ').append(Math.max(1, Math.round(clear[path.get(k)] * 2 / 3f - 1)));
                s.append(prof);
            }
            p(s.toString());
        }
        //islands of ground
        int[] comp = new int[W * H];
        Arrays.fill(comp, -1);
        IntSeq sizes = new IntSeq();
        IntQueue q = new IntQueue();
        for(int i = 0; i < W * H; i++){
            if(!groundOk[i] || comp[i] != -1) continue;
            int id = sizes.size, n = 0;
            comp[i] = id; q.addLast(i);
            while(q.size > 0){
                int a = q.removeFirst(); n++;
                int x = a % W, y = a / W;
                for(int d = 0; d < 4; d++){
                    int nx = x + Geometry4.dx[d], ny = y + Geometry4.dy[d];
                    if(nx < 0 || ny < 0 || nx >= W || ny >= H) continue;
                    int j = idx(nx, ny);
                    if(groundOk[j] && comp[j] == -1){ comp[j] = id; q.addLast(j); }
                }
            }
            sizes.add(n);
        }
        int big = 0;
        for(int i = 0; i < sizes.size; i++) if(sizes.get(i) >= 300) big++;
        int pcomp = src >= 0 ? comp[src] : -1;
        StringBuilder isl = new StringBuilder("  ground regions >=300 tiles: " + big + "; player's region " + (pcomp >= 0 ? sizes.get(pcomp) : 0) + " tiles");
        int reachable = 0;
        for(int i = 0; i < W * H; i++) if(groundOk[i] && comp[i] == pcomp) reachable++;
        int ventsReach = 0;
        for(int k = 0; k < ventCenters.size; k++) if(comp[ventCenters.get(k)] == pcomp) ventsReach++;
        isl.append(String.format(" (%d%% of open ground); vents in player's ground region %d/%d", reachable * 100 / Math.max(1, countTrue(groundOk)), ventsReach, ventCenters.size));
        //how far the player's ground region is from each region it cannot walk to: steps across rock or deep liquid
        int[] gap = null;
        if(pcomp >= 0){
            gap = new int[W * H];
            Arrays.fill(gap, -1);
            IntQueue gq = new IntQueue();
            for(int i = 0; i < W * H; i++) if(groundOk[i] && comp[i] == pcomp){ gap[i] = 0; gq.addLast(i); }
            while(gq.size > 0){
                int a = gq.removeFirst(), x = a % W, y = a / W;
                for(int d = 0; d < 8; d++){
                    int nx = x + Geometry4.d8x[d], ny = y + Geometry4.d8y[d];
                    if(nx < 0 || ny < 0 || nx >= W || ny >= H) continue;
                    int j = idx(nx, ny);
                    if(gap[j] != -1) continue;
                    gap[j] = gap[a] + 1;
                    if(!groundOk[j]) gq.addLast(j); //stop at other ground: that is where the gap ends
                }
            }
        }
        for(int t = 0; t < targets.size; t++) if(tAnchor[t] >= 0 && comp[tAnchor[t]] != pcomp){
            int tc = comp[tAnchor[t]], best = Integer.MAX_VALUE, rock = 0, liquid = 0;
            if(gap != null) for(int i = 0; i < W * H; i++) if(groundOk[i] && comp[i] == tc && gap[i] > 0 && gap[i] < best) best = gap[i];
            isl.append("; ").append(labels.get(t)).append(" is on another ground region (" + sizes.get(tc) + " tiles), narrowest gap from the player's region " + (best == Integer.MAX_VALUE ? "?" : (best - 1) + " tiles"));
        }
        p(isl.toString());
    }

    static int countTrue(boolean[] a){ int n = 0; for(boolean b : a) if(b) n++; return n; }

    static String category(Block b){
        if(b instanceof CoreBlock) return "core";
        if(b instanceof Turret) return "turret";
        if(b instanceof Wall) return "wall";
        if(b instanceof UnitFactory || b instanceof Reconstructor || b instanceof UnitAssembler || b instanceof UnitAssemblerModule) return "units";
        if(b instanceof Drill || b instanceof BeamDrill || b instanceof WallCrafter) return "drill";
        if(b instanceof PowerNode || b instanceof BeamNode || b instanceof Battery || b instanceof PowerGenerator) return "power";
        if(b instanceof GenericCrafter || b instanceof Separator || b instanceof HeatProducer || b instanceof HeatConductor) return "crafter";
        if(b instanceof Duct || b instanceof DuctRouter || b instanceof DuctBridge || b instanceof OverflowDuct || b instanceof DirectionalUnloader || b instanceof Sorter || b instanceof StackConveyor || b instanceof Conveyor || b instanceof Junction || b instanceof Router) return "items";
        if(b instanceof LiquidBlock || b instanceof Pump || b instanceof LiquidBridge || b instanceof DirectionLiquidBridge) return "liquid";
        if(b instanceof StorageBlock) return "storage";
        if(b instanceof LogicBlock || b instanceof MemoryBlock || b instanceof MessageBlock) return "logic";
        if(b instanceof PayloadBlock || b instanceof PayloadConveyor) return "payload";
        return "other";
    }

    static void buildings(Team enemy, Team player, Seq<Building> ecores){
        p("== buildings");
        for(Team team : new Team[]{enemy, Team.derelict, player}){
            ObjectMap<String, ObjectIntMap<String>> cats = new ObjectMap<>();
            int n = 0, turrets = 0, armed = 0, liquidFed = 0, powered = 0;
            ObjectIntMap<String> ammo = new ObjectIntMap<>();
            for(Building b : allBuilds()){
                if(b.team != team) continue;
                if(b.block instanceof LogicBlock && ((LogicBlock)b.block).privileged) continue;
                n++;
                String c = category(b.block);
                if(!cats.containsKey(c)) cats.put(c, new ObjectIntMap<>());
                cats.get(c).increment(b.block.name);
                if(b instanceof Turret.TurretBuild tb){
                    turrets++;
                    if(tb.hasAmmo()) armed++;
                    if(b instanceof ItemTurret.ItemTurretBuild it && it.ammo.size > 0){
                        for(var e : it.ammo) if(e instanceof ItemTurret.ItemEntry ie) ammo.increment(ie.item.name, e.amount);
                    }
                    if(b.liquids != null && b.liquids.currentAmount() > 0.01f) liquidFed++;
                    if(b.power != null && b.power.status > 0.01f) powered++;
                }
            }
            if(n == 0){ p("  " + team.name + ": nothing"); continue; }
            p("  " + team.name + ": " + n + " buildings" + (turrets > 0 ? "; turrets " + turrets + " (with ammo at start " + armed + ", stored ammo " + ammo + ", holding liquid " + liquidFed + ")" : ""));
            for(String c : new String[]{"core", "turret", "wall", "units", "drill", "crafter", "power", "items", "liquid", "storage", "payload", "logic", "other"}){
                if(!cats.containsKey(c)) continue;
                ObjectIntMap<String> m = cats.get(c);
                int sum = 0;
                for(var e : m) sum += e.value;
                Seq<String> names = m.keys().toSeq();
                names.sort(k -> -m.get(k));
                StringBuilder s = new StringBuilder();
                for(String k : names) s.append(k).append('x').append(m.get(k)).append(' ');
                p(String.format("    %-8s %4d: %s", c, sum, s));
            }
        }
        //per enemy core: what stands within 45 tiles (nearest core wins)
        if(ecores.size > 1 || ecores.size == 1){
            p("  enemy buildings grouped by nearest enemy core (within 60 tiles; rest 'far'):");
            ObjectMap<Building, ObjectIntMap<String>> per = new ObjectMap<>();
            ObjectIntMap<String> far = new ObjectIntMap<>();
            for(Building b : allBuilds()){
                if(b.team != enemy || b.block instanceof CoreBlock) continue;
                if(b.block instanceof LogicBlock) continue;
                Building best = null; float bd = 1e9f;
                for(Building c : ecores){ float d = b.dst(c) / 8f; if(d < bd){ bd = d; best = c; } }
                String key = category(b.block);
                if(key.equals("turret")) key = b.block.name;
                if(best == null || bd > 60){ far.increment(key); continue; }
                if(!per.containsKey(best)) per.put(best, new ObjectIntMap<>());
                per.get(best).increment(key);
            }
            for(Building c : ecores){
                ObjectIntMap<String> m = per.get(c, new ObjectIntMap<>());
                p("    core@" + c.tileX() + "," + c.tileY() + ": " + sorted(m));
            }
            if(far.size > 0) p("    far from any core: " + sorted(far));
        }
    }

    static String sorted(ObjectIntMap<String> m){
        Seq<String> names = m.keys().toSeq();
        names.sort(k -> -m.get(k));
        StringBuilder s = new StringBuilder();
        for(String k : names) s.append(k).append('x').append(m.get(k)).append(' ');
        return s.length() == 0 ? "-" : s.toString().trim();
    }

    static void processors(){
        boolean any = false;
        for(Building b : allBuilds()){
            if(!(b instanceof LogicBlock.LogicBuild l) || l.links.size == 0) continue;
            if(!any){ p("== world processor links"); any = true; }
            StringBuilder s = new StringBuilder("  proc@" + b.tileX() + "," + b.tileY() + ":");
            for(var link : l.links){
                Building t = Vars.world.build(link.x, link.y);
                s.append(" ").append(link.name).append("=").append(t == null ? "none" : t.block.name + "(" + t.team.name + ")").append("@").append(link.x).append(",").append(link.y);
            }
            p(s.toString());
        }
    }

    /** Overview, sx tiles a column and 2*sx tiles a row. */
    static void picture(Building pc, Seq<Building> ecores, IntSeq spawns, Team enemy, Team player){
        int x0 = W, y0 = H, x1 = 0, y1 = 0;
        for(int i = 0; i < W * H; i++) if(!wall[i]){ int x = i % W, y = i / W; x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y); }
        x0 = Math.max(0, x0 - 3); y0 = Math.max(0, y0 - 3); x1 = Math.min(W - 1, x1 + 3); y1 = Math.min(H - 1, y1 + 3);
        int bw = x1 - x0 + 1, bh = y1 - y0 + 1;
        int sx = Math.max(1, (int)Math.ceil(bw / 80.0)), sy = sx * 2;
        int cols = (bw + sx - 1) / sx, rows = (bh + sy - 1) / sy;
        final int ox = x0, oy = y0;
        char[][] g = new char[rows][cols];
        int[][] rank = new int[rows][cols];
        int[][] nWall = new int[rows][cols], nDeep = new int[rows][cols], nArea = new int[rows][cols], nTot = new int[rows][cols], nShallow = new int[rows][cols];
        for(int i = 0; i < W * H; i++){
            int x = i % W, y = i / W, cx = (x - ox) / sx, cy = (y - oy) / sy;
            if(x < ox || y < oy || cx >= cols || cy >= rows) continue;
            nTot[cy][cx]++;
            if(wall[i]) nWall[cy][cx]++;
            else if(!groundOk[i]) nDeep[cy][cx]++;
            else if(tiles[i].floor().isLiquid) nShallow[cy][cx]++;
            if(inArea[i]) nArea[cy][cx]++;
        }
        for(int cy = 0; cy < rows; cy++) for(int cx = 0; cx < cols; cx++){
            int t = nTot[cy][cx];
            if(t == 0){ g[cy][cx] = '#'; continue; }
            char c = nWall[cy][cx] * 2 > t ? '#' : nDeep[cy][cx] * 2 > t ? '~' : nShallow[cy][cx] * 2 > t ? '-' : (nWall[cy][cx] + nDeep[cy][cx]) * 2 > t ? '#' : ' ';
            if(c == ' ' && nArea[cy][cx] * 2 < t) c = '.';
            g[cy][cx] = c;
        }
        java.util.function.BiConsumer<int[], Character> mark = (pos, ch) -> {
            if(pos[0] < ox || pos[1] < oy) return;
            int cx = (pos[0] - ox) / sx, cy = (pos[1] - oy) / sy, rk = pos[2];
            if(cx < 0 || cy < 0 || cx >= cols || cy >= rows) return;
            if(rank[cy][cx] <= rk){ rank[cy][cx] = rk; g[cy][cx] = ch; }
        };
        for(int i = 0; i < W * H; i++){
            Tile t = tiles[i];
            int x = i % W, y = i / W;
            if(wall[i] && t.block().isStatic() && exposed(x, y)){
                Item d = t.wallDrop();
                if(d == Items.tungsten) mark.accept(new int[]{x, y, 3}, 'T');
                else if(d == Items.thorium) mark.accept(new int[]{x, y, 3}, 'H');
                else if(d == Items.graphite) mark.accept(new int[]{x, y, 1}, 'g');
                else if(d == Items.beryllium) mark.accept(new int[]{x, y, 1}, 'b');
            }else if(!wall[i] && t.overlay() != null && t.overlay().itemDrop != null){
                Item d = t.overlay().itemDrop;
                if(d == Items.tungsten) mark.accept(new int[]{x, y, 3}, 't');
                else if(d == Items.thorium) mark.accept(new int[]{x, y, 3}, 'h');
                else if(d == Items.beryllium) mark.accept(new int[]{x, y, 1}, 'b');
                else if(d == Items.graphite) mark.accept(new int[]{x, y, 1}, 'g');
            }
            if(!wall[i] && !deep[i] && t.floor().liquidDrop == Liquids.arkycite) mark.accept(new int[]{x, y, 2}, 'a');
            if(!wall[i] && !deep[i] && t.floor().liquidDrop == Liquids.slag) mark.accept(new int[]{x, y, 2}, 's');
            if(!wall[i] && t.floor().liquidDrop == Liquids.water && !t.floor().isDeep()) mark.accept(new int[]{x, y, 0}, 'w');
        }
        for(int k = 0; k < ventCenters.size; k++){ int v = ventCenters.get(k); mark.accept(new int[]{v % W, v / W, 4}, ':'); }
        for(Building b : allBuilds()){
            int rk; char ch;
            if(b.team == enemy){
                if(b.block instanceof CoreBlock){ ch = 'X'; rk = 10; }
                else if(b.block instanceof Turret){ ch = 'G'; rk = 8; }
                else if(category(b.block).equals("units")){ ch = 'Y'; rk = 7; }
                else if(b.block instanceof Wall){ ch = '='; rk = 5; }
                else if(b.block instanceof LogicBlock){ continue; }
                else{ ch = 'e'; rk = 5; }
            }else if(b.team == player){
                if(b.block instanceof CoreBlock){ ch = 'O'; rk = 11; }
                else if(b.block instanceof LogicBlock) continue;
                else{ ch = 'p'; rk = 6; }
            }else if(b.team == Team.derelict){ ch = 'd'; rk = 4; }
            else{ ch = '?'; rk = 4; }
            mark.accept(new int[]{b.tileX(), b.tileY(), rk}, ch);
        }
        for(int k = 0; k < spawns.size; k++) mark.accept(new int[]{spawns.get(k) % W, spawns.get(k) / W, 9}, 'S');
        p("== overview (x " + ox + ".." + x1 + ", y " + oy + ".." + y1 + "; 1 column = " + sx + " tiles, 1 row = " + sy + " tiles; north up)");
        p("   O player core  X enemy core  G enemy turret  Y enemy unit factory  = enemy wall  e other enemy block  p player block  d derelict  S spawn");
        p("   : 3x3 vent  T/t tungsten wall/floor  H/h thorium wall/floor  b beryllium  g graphite  a arkycite  s slag  w shallow water  ~ deep (no ground)  - shallow liquid  # rock  . outside start area");
        for(int cy = rows - 1; cy >= 0; cy--) p(String.format("%4d|", oy + cy * sy) + new String(g[cy]) + "|");
        StringBuilder axis = new StringBuilder("    +");
        for(int cx = 0; cx < cols; cx++) axis.append(cx % 10 == 0 ? '|' : '-');
        p(axis.toString());
        StringBuilder lab = new StringBuilder("     ");
        for(int cx = 0; cx < cols; cx += 10){ String s = String.valueOf(ox + cx * sx); lab.append(s); for(int k = s.length(); k < 10; k++) lab.append(' '); }
        p(lab.toString());
    }
}
