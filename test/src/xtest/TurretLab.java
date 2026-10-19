package xtest;

import arc.*;
import arc.files.*;
import arc.math.*;
import arc.struct.*;
import arc.util.*;
import expansion.defense.*;
import expansion.defense.Battery;
import mindustry.*;
import mindustry.content.*;
import mindustry.core.GameState.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.sandbox.*;

import java.util.*;

/**
 * The defence lab: an empty Erekir lane with our core at one end, a defence laid out by the planner's own code
 * (expansion.defense.Post) a way up the lane, fed by sources standing in for its supply lines and given power, and waves
 * of enemy units sent down the lane at it. It says whether the defence held, what it lost, how long it took to kill the
 * wave, and how much ammunition and liquid it used a second while it fired.
 *
 * A run is written as key=value pairs joined by ';':
 *   front=breach-pair,diffuse-pair   modules in the first row (from blueprints/defense/modules.txt)
 *   back=titan-pair                  modules in the second row
 *   ammo=breach:beryllium            ammunition for a turret kind (else what the module is drawn with)
 *   wet                              water laid on for faster fire
 *   wall=tungsten                    wall line in front: beryllium, tungsten, carbide, surge or none
 *   units=stell*8+locus*2            one wave; waves=3 sends it three times, spacing=40 seconds apart
 *   supply=2                         each item port gives at most this many a second (else as fast as a duct takes)
 *   liquid=6                         each liquid port gives at most this much a second
 *   width=15                         the lane's width in tiles; seed=1
 * Several runs can be given; "cap:<run>:<unit>" finds the largest single wave of that unit the run holds.
 * "suite:<name>" runs a set written here.
 *
 * Usage: TurretLab data-dir run...
 */
public class TurretLab{
    static final int W = 200, H = 90, coreX = 10, postX = 62, spawnX = 132;
    static int laneY = H / 2;
    static Block supplyItem, supplyLiquid;
    /** What each supply port has given so far (items or liquid, by content id), and the most it may give a second. */
    static final ObjectMap<Building, float[]> given = new ObjectMap<>();
    static final ObjectFloatMap<Building> cap = new ObjectFloatMap<>();
    static List<Battery> modules;
    static Team us = Team.sharded, them = Team.crux;

    static float[] given(Building b){
        float[] c = given.get(b);
        if(c == null) given.put(b, c = new float[256]);
        return c;
    }

    /** Supply ports for the lab: sources that count what they give and give no more than a line would. */
    static void content(){
        supplyItem = new ItemSource("lab-supply-item"){{
            buildType = () -> new ItemSourceBuild(){
                float budget;

                @Override
                public void updateTile(){
                    if(outputItem == null) return;
                    float most = cap.get(this, 0f);
                    budget = most <= 0f ? 4f : Math.min(budget + most / 60f, 2f);
                    while(budget >= 1f){
                        items.set(outputItem, 1);
                        boolean went = dump(outputItem);
                        items.set(outputItem, 0);
                        if(!went) break;
                        given(this)[outputItem.id]++;
                        budget -= 1f;
                    }
                }
            };
        }};
        supplyLiquid = new LiquidSource("lab-supply-liquid"){{
            buildType = () -> new LiquidSourceBuild(){
                @Override
                public void updateTile(){
                    if(source == null){
                        liquids.clear();
                        return;
                    }
                    float most = cap.get(this, 0f);
                    float have = liquids.get(source);
                    if(most <= 0f) liquids.set(source, liquidCapacity);
                    else liquids.add(source, Math.min(most / 60f, liquidCapacity - have));
                    float before = liquids.get(source);
                    dumpLiquid(source, 1f);
                    given(this)[source.id] += before - liquids.get(source);
                    if(most <= 0f) liquids.set(source, liquidCapacity);
                }
            };
        }};
    }

    /** One run as asked for. */
    static final class Run{
        String text;
        final List<String> front = new ArrayList<>(), back = new ArrayList<>();
        final Map<String, String> ammo = new HashMap<>();
        boolean wet;
        String wall = "tungsten";
        /** plan=sector: the planner picks and lays the defence itself, with that sector's tech, for the wave asked. */
        String plan;
        final Map<String, Integer> units = new LinkedHashMap<>();
        //-Dlab.width=n: the width every run has unless it says its own (an open lane for the open-ground table)
        int waves = 1, width = Integer.getInteger("lab.width", 15), seed = 1;
        float spacing = 45f, supply = 0f, liquid = 0f, limit = 150f;

        static Run parse(String text){
            Run r = new Run();
            r.text = text;
            for(String kv : text.split(";")){
                kv = kv.trim();
                if(kv.isEmpty()) continue;
                String k = kv.contains("=") ? kv.substring(0, kv.indexOf('=')) : kv, v = kv.contains("=") ? kv.substring(kv.indexOf('=') + 1) : "";
                switch(k){
                    case "front" -> r.front.addAll(Arrays.asList(v.split(",")));
                    case "back" -> r.back.addAll(Arrays.asList(v.split(",")));
                    case "ammo" -> {
                        for(String a : v.split(",")) r.ammo.put(a.split(":")[0], a.split(":")[1]);
                    }
                    case "wet" -> r.wet = true;
                    case "wall" -> r.wall = v;
                    case "units" -> {
                        for(String u : v.split("\\+")){
                            String[] p = u.split("\\*");
                            r.units.put(p[0], p.length > 1 ? Integer.parseInt(p[1]) : 1);
                        }
                    }
                    case "waves" -> r.waves = Integer.parseInt(v);
                    case "spacing" -> r.spacing = Float.parseFloat(v);
                    case "supply" -> r.supply = Float.parseFloat(v);
                    case "liquid" -> r.liquid = Float.parseFloat(v);
                    case "width" -> r.width = Integer.parseInt(v);
                    case "seed" -> r.seed = Integer.parseInt(v);
                    case "limit" -> r.limit = Float.parseFloat(v);
                    case "plan" -> r.plan = v;
                    default -> throw new IllegalArgumentException("unknown key " + k + " in " + text);
                }
            }
            return r;
        }

        Run with(String unit, int n){
            Run r = parse(text);
            r.units.clear();
            r.units.put(unit, n);
            r.text = text + ";units=" + unit + "*" + n;
            return r;
        }
    }

    static final class Result{
        /** cleared: every unit of every wave dead in time; passed: one got 20 tiles behind the post; reached: one came in range of the core. */
        boolean held, cleared, passed, reached, coreHit;
        int spawned, killed, turrets, turretsLost, wallsLost, blocksLost;
        float spawnedHealth, damageTaken, killSeconds = -1f, firing, turretFiring, seconds;
        final Map<String, Float> used = new TreeMap<>();
        final List<String> notes = new ArrayList<>();

        /** Held with nothing lost but walls and nobody through the line: what a module is measured by. */
        boolean strict(){
            return held && turretsLost == 0 && !passed;
        }
    }

    public static void main(String[] args){
        Boot.boot(args[0], null, TurretLab::content);
        GameData.shapes();
        modules = Battery.parse(new Fi("blueprints/defense/modules.txt").readString());
        Events.on(BlockDestroyEvent.class, e -> {
            if(current != null && e.tile.team() == us && e.tile.build != null) current.lost(e.tile.build);
        });
        Events.on(UnitDestroyEvent.class, e -> {
            if(current != null && e.unit.team == them) current.result.killed++;
        });
        List<String> runs = new ArrayList<>();
        for(int i = 1; i < args.length; i++){
            if(args[i].startsWith("suite:")) runs.addAll(suite(args[i].substring(6)));
            else runs.add(args[i]);
        }
        for(String text : runs){
            if(text.startsWith("cap:")){
                String rest = text.substring(4);
                int at = rest.lastIndexOf(':');
                capacity(Run.parse(rest.substring(0, at)), rest.substring(at + 1));
            }else{
                Run run = Run.parse(text);
                Result r = run(run);
                print(run, r);
            }
        }
        System.exit(0);
    }

    /** Sets of runs used to check the module library. */
    static List<String> suite(String name){
        List<String> out = new ArrayList<>();
        switch(name){
            case "caps", "caps1", "caps2" -> {
                //every module, in each way it is fed, against every unit it can hit: the planner's table
                String[] configs = {
                    "front=breach-pair-direct;ammo=breach:beryllium;wall=beryllium",
                    "front=breach-pair;ammo=breach:beryllium;wall=beryllium",
                    "front=breach-pair;ammo=breach:tungsten;wall=tungsten",
                    "front=breach-pair;ammo=breach:tungsten;wet;wall=tungsten",
                    "front=diffuse-pair;ammo=diffuse:graphite;wall=tungsten",
                    "front=diffuse-pair;ammo=diffuse:graphite;wet;wall=tungsten",
                    "front=diffuse-pair;ammo=diffuse:oxide;wall=tungsten",
                    "front=sublimate-pair;ammo=sublimate:ozone;wall=tungsten",
                    "front=sublimate-pair;ammo=sublimate:cyanogen;wall=tungsten",
                    "front=disperse-pair;ammo=disperse:tungsten;wall=none",
                    "front=disperse-pair;ammo=disperse:tungsten;wet;wall=none",
                    "front=titan-pair;ammo=titan:thorium;wall=tungsten",
                    "front=afflict-post;wall=tungsten",
                    "front=lustre-post;wall=tungsten",
                    "front=smite-post;ammo=smite:surge-alloy;wall=tungsten",
                    "front=smite-post;ammo=smite:surge-alloy;wet;wall=tungsten"};
                for(int i = 0; i < configs.length; i++){
                    if(name.equals("caps1") && i % 2 == 1 || name.equals("caps2") && i % 2 == 0) continue;
                    Run base = Run.parse(configs[i]);
                    Gun gun = module(base.front.get(0)).gun();
                    for(Foe foe : Foe.all.values()) if(gun.hits(foe.move)) out.add("cap:" + configs[i] + ":" + foe.name);
                }
            }
            case "roles" -> {
                //every module against what it is meant for
                out.add("front=breach-pair-direct;ammo=breach:beryllium;wall=beryllium;units=stell*6");
                out.add("front=breach-pair;ammo=breach:tungsten;wall=tungsten;units=stell*10+locus*2");
                out.add("front=breach-pair;ammo=breach:tungsten;wet;wall=tungsten;units=locus*6");
                out.add("front=diffuse-pair;ammo=diffuse:graphite;wall=tungsten;units=merui*12");
                out.add("front=diffuse-pair;ammo=diffuse:graphite;wall=tungsten;units=cleroi*6");
                out.add("front=sublimate-pair;wall=tungsten;units=stell*12+locus*4");
                out.add("front=sublimate-pair;wall=tungsten;units=precept*2");
                out.add("front=breach-pair;back=titan-pair;wall=tungsten;units=precept*3+locus*4");
                out.add("front=disperse-pair;wall=none;units=avert*10");
                out.add("front=disperse-pair;wall=none;units=obviate*4");
                out.add("front=disperse-pair;wall=none;units=elude*6");
                out.add("front=afflict-post;wall=tungsten;units=locus*4+avert*4");
                out.add("front=sublimate-pair;back=lustre-post;wall=tungsten;units=vanquish*1+locus*4");
            }
            default -> throw new IllegalArgumentException("no suite " + name);
        }
        return out;
    }

    static Battery module(String name){
        for(Battery m : modules) if(m.name.equals(name)) return m;
        throw new IllegalArgumentException("no module " + name);
    }

    /** The largest single wave of a unit the run holds with nothing lost but walls: doubling, then halving the gap. */
    static void capacity(Run base, String unit){
        int good = 0, bad = -1;
        Result last = null;
        for(int n = 1; n <= 96; n *= 2){
            Result r = run(base.with(unit, n));
            if(!r.strict()){
                bad = n;
                break;
            }
            good = n;
            last = r;
        }
        while(bad > 0 && bad - good > 1){
            int mid = (good + bad) / 2;
            Result r = run(base.with(unit, mid));
            if(r.strict()){
                good = mid;
                last = r;
            }else bad = mid;
        }
        System.out.println(String.format("CAP %s | %s | holds %d%s%s", base.text, unit, good, bad < 0 ? "+" : "",
            last == null ? "" : String.format(" | at that: %.0f s to kill, %.0f damage taken, used %s", last.killSeconds, last.damageTaken, usedText(last))));
        //-Dlab.capfile=path: one line a module for the planner's table (blueprints/defense/capacity.txt)
        String file = System.getProperty("lab.capfile");
        if(file != null && base.front.size() == 1 && base.back.isEmpty()){
            Battery m = module(base.front.get(0));
            Gun gun = m.gun();
            String ammo = gun == null ? "-" : base.ammo.getOrDefault(gun.name, null);
            if(ammo == null) for(Battery.Part p : m.parts) if(p.port() && gun.pick(Set.of(String.valueOf(p.config))) != null) ammo = p.config;
            StringBuilder used = new StringBuilder();
            if(last != null) for(var e : last.used.entrySet()) used.append(used.length() == 0 ? "" : ",").append(e.getKey()).append(':').append(Math.round(e.getValue()));
            String line = String.format("%s %s %s %s %s %d %.1f %s%n", m.name, ammo == null ? "-" : ammo, base.wet ? "wet" : "dry", base.wall + ":" + base.width, unit, good,
                last == null ? 0f : last.killSeconds, used.length() == 0 ? "-" : used);
            new Fi(file).writeString(line, true);
        }
    }

    static Probe current;

    /** What is watched during one run. */
    static final class Probe{
        final Result result = new Result();
        final Seq<Building> turrets = new Seq<>();
        final ObjectSet<Building> counted = new ObjectSet<>();

        void lost(Building b){
            if(counted.contains(b)) return;
            counted.add(b);
            result.blocksLost++;
            result.damageTaken += b.maxHealth;
            if(b.block instanceof Turret) result.turretsLost++;
            if(b.block instanceof mindustry.world.blocks.defense.Wall) result.wallsLost++;
        }
    }

    static void world(Run run){
        Vars.logic.reset();
        laneY = H / 2;
        int half = run.width / 2;
        Vars.world.loadGenerator(W, H, tiles -> {
            for(int x = 0; x < W; x++) for(int y = 0; y < H; y++){
                boolean open = x >= 2 && x < W - 2 && Math.abs(y - laneY) <= half;
                tiles.set(x, y, new Tile(x, y, Blocks.rhyolite, Blocks.air, open ? Blocks.air : Blocks.rhyoliteWall));
            }
        });
        Rules rules = new Rules();
        Planets.erekir.applyRules(rules, true);
        rules.fog = false;
        rules.waves = true;
        rules.waveTimer = false;
        rules.defaultTeam = us;
        rules.waveTeam = them;
        rules.unitCap = 1000;
        rules.coreIncinerates = true;
        Vars.state.rules = rules;
        Vars.state.set(State.playing);
        Time.setDeltaProvider(() -> 1f);
        Vars.world.tile(coreX, laneY).setBlock(Blocks.coreBastion, us, 0);
    }

    static Result run(Run run){
        world(run);
        Mathf.rand.setSeed(run.seed);
        given.clear();
        cap.clear();
        Probe probe = new Probe();
        current = probe;
        Result r = probe.result;

        //the defence, laid out by the planner's code on the lane as it is
        Terrain terrain = GameData.terrain(us);
        List<Placement> plan;
        if(run.plan != null){
            Planner.Ask pa = new Planner.Ask();
            pa.terrain = terrain;
            pa.coreX = coreX;
            pa.coreY = laneY;
            pa.blocks.addAll(Tech.blocks(run.plan));
            pa.supply.addAll(Tech.supply(run.plan));
            pa.costs = GameData.costs();
            pa.modules = modules;
            pa.capacity = Capacity.parse(new Fi("blueprints/defense/capacity.txt").readString());
            pa.units.putAll(run.units);
            pa.sources.add(new Planner.Source(spawnX, laneY, "the far end"));
            Planner.Plan made = new Planner(pa).plan();
            if(Boolean.getBoolean("lab.draw")) System.out.print(made.describe());
            plan = made.placements;
            for(Planner.Site site : made.sites) r.notes.add(site.chosen.toString());
        }else{
            Post post = new Post(terrain, new HashSet<>(), 0);
            Post.Ask ask = new Post.Ask();
            for(String m : run.front) ask.front.add(module(m));
            for(String m : run.back) ask.back.add(module(m));
            ask.ammo.putAll(run.ammo);
            ask.wet = run.wet;
            if(!run.wall.equals("none")){
                String material = run.wall.equals("surge") ? "reinforced-surge" : run.wall;
                ask.wall = material + "-wall-large";
                ask.smallWall = material + "-wall";
            }
            plan = post.lay(postX, laneY, 0, ask);
            r.notes.addAll(post.notes);
        }
        GameData.PortMaker ports = new GameData.PortMaker(){
            @Override
            public Block block(Placement port){
                return port.liquid ? supplyLiquid : supplyItem;
            }

            @Override
            public void configure(Building built, Placement port){
                GameData.sources.configure(built, port);
                cap.put(built, port.liquid ? run.liquid : run.supply);
            }
        };
        List<Placement> failed = GameData.place(plan, us, ports);
        for(Placement p : failed) r.notes.add("could not place " + p);
        //power for all of it
        Tile power = Vars.world.tile(coreX - 6, laneY);
        power.setBlock(Blocks.powerSource, us, 0);
        PowerGraph graph = power.build.power.graph;
        for(Building b : us.data().buildings){
            if(b.power != null && b != power.build && b.power.graph != graph) graph.addGraph(b.power.graph);
            if(b.block instanceof Turret) probe.turrets.add(b);
        }
        r.turrets = probe.turrets.size;
        if(Boolean.getBoolean("lab.draw")) System.out.println(picture(coreX - 6, laneY - run.width / 2 - 1, postX + 30, laneY + run.width / 2 + 1));
        //stores fill and turrets load before the first wave
        Boot.frames(20 * 60);
        Map<String, Float> stockBefore = stock(), givenBefore = givenTotals();

        //the waves
        int frames = 0, lastSpawn = -1, wave = 0, firstShot = -1, lastKill = -1;
        int limit = (int)((run.limit + run.spacing * (run.waves - 1)) * 60);
        Building core = us.core();
        float coreHealth = core.health;
        Seq<Unit> foes = new Seq<>();
        while(frames < limit){
            if(wave < run.waves && (lastSpawn < 0 || frames - lastSpawn >= run.spacing * 60)){
                spawn(run, foes, r);
                wave++;
                lastSpawn = frames;
            }
            Boot.frames(1);
            frames++;
            boolean any = false;
            for(Building t : probe.turrets){
                if(t.isValid() && t instanceof Turret.TurretBuild tb && tb.wasShooting){
                    any = true;
                    r.turretFiring++;
                }
            }
            if(any){
                r.firing++;
                if(firstShot < 0) firstShot = frames;
            }
            int alive = 0;
            for(Unit u : foes){
                if(u.isValid() && !u.dead){
                    alive++;
                    if(u.x < (postX - 20) * Vars.tilesize) r.passed = true;
                    if(u.dst(core) - core.block.size * Vars.tilesize / 2f < u.type.maxRange + 2 * Vars.tilesize) r.reached = true;
                }
            }
            if(!core.isValid()) break;
            if(alive == 0 && wave >= run.waves){
                r.cleared = true;
                lastKill = frames;
                break;
            }
        }
        r.seconds = frames / 60f;
        r.coreHit = !core.isValid() || core.health < coreHealth;
        r.firing /= 60f;
        r.turretFiring /= 60f;
        if(firstShot >= 0 && lastKill >= 0) r.killSeconds = (lastKill - firstShot) / 60f;
        r.held = r.cleared && !r.reached && !r.coreHit;
        //killed: whatever of the waves is no longer there (counted here rather than by events, which a unit leaving the map does not send)
        r.killed = 0;
        for(Unit u : foes) if(!u.isValid() || u.dead) r.killed++;
        //what was used: what the ports gave while it fought, less what is still in the stores and turrets
        Map<String, Float> stockAfter = stock(), givenAfter = givenTotals();
        Set<String> keys = new TreeSet<>(givenAfter.keySet());
        keys.addAll(stockBefore.keySet());
        for(String k : keys){
            float used = givenAfter.getOrDefault(k, 0f) - givenBefore.getOrDefault(k, 0f) - (stockAfter.getOrDefault(k, 0f) - stockBefore.getOrDefault(k, 0f));
            if(Math.abs(used) > 0.5f) r.used.put(k, used);
        }
        //what our blocks lost in health, besides those destroyed
        for(Building b : us.data().buildings) if(b != core && !probe.counted.contains(b)) r.damageTaken += b.maxHealth - b.health;
        current = null;
        return r;
    }

    /** One wave at the far end of the lane, in rows. */
    static void spawn(Run run, Seq<Unit> foes, Result r){
        //the whole wave at once over a patch of the lane as deep as it needs (up to 50 tiles), scattered the way the
        //game's wave spawner scatters a group: the game pushes overlapping units apart
        float area = 0f;
        for(var e : run.units.entrySet()){
            UnitType type = Vars.content.unit(e.getKey());
            if(type == null) throw new IllegalArgumentException("no unit " + e.getKey());
            area += e.getValue() * Mathf.sqr(type.hitSize / Vars.tilesize + 1f);
        }
        float depth = Mathf.clamp(area / Math.max(1, run.width - 2), 4f, 50f);
        for(var e : run.units.entrySet()){
            UnitType type = Vars.content.unit(e.getKey());
            for(int i = 0; i < e.getValue(); i++){
                float x = (spawnX + Mathf.random(depth)) * Vars.tilesize, y = (laneY + Mathf.range(run.width / 2f - 1.5f)) * Vars.tilesize;
                Unit u = type.spawn(them, x, y);
                foes.add(u);
                r.spawned++;
                r.spawnedHealth += u.maxHealth;
            }
        }
    }

    /** Items and liquids held by our blocks, turrets' loaded ammunition counted back into items. */
    static Map<String, Float> stock(){
        Map<String, Float> out = new TreeMap<>();
        for(Building b : us.data().buildings){
            if(b.block == supplyItem || b.block == supplyLiquid || b instanceof mindustry.world.blocks.storage.CoreBlock.CoreBuild) continue;
            if(b.items != null) for(Item item : Vars.content.items()) if(b.items.get(item) > 0) out.merge(item.name, (float)b.items.get(item), Float::sum);
            if(b.liquids != null) for(Liquid l : Vars.content.liquids()) if(b.liquids.get(l) > 0.01f) out.merge(l.name, b.liquids.get(l), Float::sum);
            if(b instanceof ItemTurret.ItemTurretBuild t){
                ItemTurret block = (ItemTurret)t.block;
                for(var entry : t.ammo){
                    if(entry instanceof ItemTurret.ItemEntry ie){
                        var type = block.ammoTypes.get(ie.item);
                        if(type != null) out.merge(ie.item.name, ie.amount / type.ammoMultiplier, Float::sum);
                    }
                }
            }
        }
        return out;
    }

    static Map<String, Float> givenTotals(){
        Map<String, Float> out = new TreeMap<>();
        for(var e : given){
            boolean liquid = e.key.block == supplyLiquid;
            for(int id = 0; id < 256; id++){
                if(e.value[id] <= 0) continue;
                String name = liquid ? Vars.content.liquid(id).name : Vars.content.item(id).name;
                out.merge(name, e.value[id], Float::sum);
            }
        }
        return out;
    }

    static String usedText(Result r){
        StringBuilder b = new StringBuilder();
        for(var e : r.used.entrySet()){
            if(b.length() > 0) b.append(", ");
            b.append(String.format("%s %.0f (%.2f/s while firing, %.2f/s a turret)", e.getKey(), e.getValue(), r.firing > 0 ? e.getValue() / r.firing : 0f, r.turretFiring > 0 ? e.getValue() / r.turretFiring : 0f));
        }
        return b.length() == 0 ? "nothing" : b.toString();
    }

    /** The lane round the post, a character a tile: @ turret (its own tile), T the rest of it, # wall, arrows ducts, r router, u unloader, C store, H heater, S/L sources, % rock. */
    static String picture(int x0, int y0, int x1, int y1){
        StringBuilder b = new StringBuilder();
        for(int y = y1; y >= y0; y--){
            for(int x = x0; x <= x1; x++){
                Tile t = Vars.world.tile(x, y);
                char c;
                if(t == null) c = ' ';
                else if(t.build == null) c = t.solid() ? '%' : '.';
                else{
                    Block k = t.build.block;
                    c = k instanceof Turret ? (t.build.tile == t ? '@' : 'T') : k instanceof mindustry.world.blocks.defense.Wall ? '#' : k == supplyItem ? 'S' : k == supplyLiquid ? 'L'
                        : k.name.contains("router") ? 'r' : k.name.contains("unloader") ? 'u' : k.name.contains("duct") ? (">^<v".charAt(t.build.rotation)) : k.name.contains("container") || k.name.contains("tank") ? 'C'
                        : k.name.contains("heat") ? 'H' : k instanceof mindustry.world.blocks.storage.CoreBlock ? 'K' : k.name.contains("power") ? 'P' : '?';
                }
                b.append(c);
            }
            b.append('\n');
        }
        return b.toString();
    }

    static void print(Run run, Result r){
        System.out.println(String.format("LAB %s | %s | killed %d/%d (%.0f hp) in %s | lost %d turrets of %d, %d walls, %d blocks, %.0f damage | fired %.0f s | used %s%s%s",
            run.text, r.strict() ? "HOLDS" : r.held ? (r.turretsLost > 0 ? "HOLDS (turrets lost)" : "HOLDS (line crossed)") : r.reached ? "FAILS (got through)" : r.coreHit ? "FAILS (core hit)" : "FAILS (not cleared)",
            r.killed, r.spawned, r.spawnedHealth, r.killSeconds >= 0 ? Strings.autoFixed(r.killSeconds, 1) + " s" : "-",
            r.turretsLost, r.turrets, r.wallsLost, r.blocksLost, r.damageTaken, r.firing, usedText(r),
            r.notes.isEmpty() ? "" : " | " + String.join("; ", r.notes), ""));
    }
}
