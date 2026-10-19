package xtest;

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
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.power.*;

import java.util.*;

/**
 * The defence planner on a real map: the map read as the campaign lands on it, the defence planned for its own waves
 * (or, on a map without waves, for waves sent from the enemy cores), put down at once with sources standing in for its
 * supply lines and given power, and then the waves played one after another. Says, wave by wave, whether the core
 * stood and what the defence lost.
 *
 * Usage: DefenseRun data-dir map.msav [first wave] [last wave]
 *   -Ddef.sector=name     the tech of this sector (default: the map's name)
 *   -Ddef.units=stell:4   on a map without waves: the first wave, sent from the enemy cores, one more unit of each every two
 *                         waves (stell*4 also works); several joined by +
 *   -Ddef.keep=true       keep the turrets the map gives us (by default they are taken away, so only the plan is tested)
 *   -Ddef.budget=loadout  plan within the starting items instead of without a limit
 *   -Ddef.repair=false    nothing rebuilt or mended between waves
 *   -Ddef.trace=true      what we lose, and which units come within 30 tiles of the core
 *   -Ddef.draw=true       a picture of each defended place; -Ddef.probe=x,y the planner's flags for one tile
 */
public class DefenseRun{
    public static void main(String[] args){
        Boot.boot(args[0]);
        //-Ddef.trace=true: what we lose, and which units come near the core
        boolean trace = Boolean.getBoolean("def.trace");
        arc.Events.on(mindustry.game.EventType.BlockDestroyEvent.class, e -> {
            if(trace && e.tile.build != null && e.tile.team() == Vars.state.rules.defaultTeam) System.out.println("    lost " + e.tile.block().name + " at " + e.tile.x + "," + e.tile.y + " (wave " + Vars.state.wave + ")");
        });
        GameData.shapes();
        Fi map = new Fi(args[1]);
        int first = args.length > 2 ? Integer.parseInt(args[2]) : 1;
        String sector = System.getProperty("def.sector", map.nameWithoutExtension());

        Vars.logic.reset();
        SaveIO.load(map);
        Rules rules = Vars.state.rules;
        Planets.erekir.applyRules(rules, false);
        rules.fog = false;
        rules.coreIncinerates = true;
        Team us = rules.defaultTeam, enemy = rules.waveTeam;
        var core = us.core();
        if(rules.loadout != null && rules.loadout.size > 0){
            core.items.clear();
            for(ItemStack stack : rules.loadout) core.items.set(stack.item, stack.amount);
        }
        //the enemy's own factories are stopped: only the waves come
        enemy.rules().unitBuildSpeedMultiplier = 0.0001f;
        enemy.rules().rtsAi = false;
        //waves sent from the enemy cores on a map that has none of its own
        String spec = System.getProperty("def.units");
        if(spec != null){
            rules.spawns.clear();
            for(String u : spec.split("\\+")){
                SpawnGroup g = new SpawnGroup(Vars.content.unit(u.split("[*:]")[0]));
                g.unitAmount = Integer.parseInt(u.split("[*:]")[1]);
                g.unitScaling = 2f;
                rules.spawns.add(g);
            }
            rules.wavesSpawnAtCores = true;
        }
        int last = args.length > 3 ? Integer.parseInt(args[3]) : rules.winWave > 0 ? rules.winWave : 10;
        Time.setDeltaProvider(() -> 1f);
        Vars.logic.play();
        Vars.state.rules.waveTimer = false;
        Vars.state.wave = 1;
        Boot.frames(2);
        if(!Boolean.getBoolean("def.keep")){
            for(Building b : us.data().buildings.copy()) if(b.block instanceof Turret) b.tile.setNet(Blocks.air);
        }

        //what the planner is told
        Planner.Ask ask = new Planner.Ask();
        ask.terrain = GameData.terrain(us);
        if(System.getProperty("def.probe") != null){
            String[] at = System.getProperty("def.probe").split(",");
            int px = Integer.parseInt(at[0]), py = Integer.parseInt(at[1]);
            System.out.println("  probe " + px + "," + py + ": flags " + ask.terrain.at(px, py) + ", waves " + rules.waves + ", has spawns " + Vars.state.hasSpawns() + ", spawns " + Vars.spawner.getSpawns().size + ", drop zone " + rules.dropZoneRadius);
        }
        ask.coreX = core.tileX();
        ask.coreY = core.tileY();
        ask.coreSize = core.block.size;
        ask.blocks.addAll(Tech.blocks(sector));
        ask.supply.addAll(Tech.supply(sector));
        ask.costs = GameData.costs();
        ask.modules = Battery.parse(new Fi("blueprints/defense/modules.txt").readString());
        ask.capacity = Capacity.parse(new Fi("blueprints/defense/capacity.txt").readString());
        if("loadout".equals(System.getProperty("def.budget"))) for(ItemStack s : rules.loadout) ask.budget.put(s.item.name, s.amount);
        List<Map<String, Integer>> waves = GameData.waves(first, last);
        for(Map<String, Integer> w : waves) for(var e : w.entrySet()) ask.units.merge(e.getKey(), e.getValue(), Math::max);
        sources(ask, enemy, first, last);
        System.out.println("######## " + map.nameWithoutExtension() + " (tech of " + sector + "), waves " + first + "-" + last + ": largest of each " + ask.units);
        for(Planner.Source s : ask.sources) System.out.println("  source " + s.label + " " + s.units);
        long t0 = System.nanoTime();
        Planner.Plan plan = new Planner(ask).plan();
        System.out.println(String.format("  planned in %.0f ms", (System.nanoTime() - t0) / 1e6));
        System.out.print(plan.describe());

        //put down, fed and powered
        List<Placement> failed = GameData.place(plan.placements, us, GameData.sources);
        for(Placement p : failed) System.out.println("  could not place " + p);
        Tile power = null;
        for(int r = 3; r < 12 && power == null; r++) for(int dx = -r; dx <= r && power == null; dx++){
            Tile t = Vars.world.tile(core.tileX() + dx, core.tileY() - r);
            if(t != null && t.build == null && !t.solid() && !t.floor().isDeep()) power = t;
        }
        if(power != null){
            power.setBlock(Blocks.powerSource, us, 0);
            PowerGraph graph = power.build.power.graph;
            for(Building b : us.data().buildings) if(b.power != null && b != power.build && b.power.graph != graph) graph.addGraph(b.power.graph);
        }
        if(Boolean.getBoolean("def.draw")) for(Planner.Site s : plan.sites) System.out.println(TurretLab.picture(s.x - 25, s.y - 18, s.x + 25, s.y + 18));
        int turrets = 0;
        for(Building b : us.data().buildings) if(b.block instanceof Turret) turrets++;
        //stores fill before the first wave
        Boot.frames(30 * 60);

        //the waves, one after another: the next comes when this one is dead or its time is up
        float coreMax = core.maxHealth, coreLow = 1f;
        boolean lost = false;
        //-Ddef.repair=false: nothing is rebuilt or mended between waves (by default it is, as the economy's watch and the core unit do)
        boolean repair = !"false".equals(System.getProperty("def.repair"));
        int rebuilt = 0;
        Map<String, Integer> rebuildCost = new TreeMap<>();
        int lastWave = first - 1;
        Vars.state.wave = first;
        for(int w = first; w <= last && !lost; w++){
            //between waves: what the plan put down and is gone goes up again, and everything is mended
            if(repair && w > first){
                List<Placement> gone = new ArrayList<>();
                for(Placement p : plan.placements){
                    Tile t = Vars.world.tile(p.x, p.y);
                    if(t != null && t.build == null) gone.add(p);
                }
                rebuilt += gone.size();
                for(Placement p : gone){
                    Map<String, Integer> c = p.kind == Placement.Kind.port ? null : ask.costs.get(p.block);
                    if(c != null) c.forEach((k, v) -> rebuildCost.merge(k, v, Integer::sum));
                }
                GameData.place(gone, us, GameData.sources);
                if(power != null && power.build != null) for(Building b : us.data().buildings) if(b.power != null && b.power.graph != power.build.power.graph) power.build.power.graph.addGraph(b.power.graph);
                for(Building b : us.data().buildings) b.heal();
                Boot.frames(10 * 60);
            }
            int before = count(us), turretsBefore = turretCount(us);
            Vars.logic.runWave();
            int spawned = enemyCount(enemy);
            float limit = Math.max(rules.waveSpacing, 150f * 60f), frames = 0;
            ObjectSet<Unit> seen = new ObjectSet<>();
            while(frames < limit && enemyCount(enemy) > 0 && core.isValid()){
                Boot.frames(60);
                frames += 60;
                if(trace) for(Unit u : Groups.unit){
                    if(u.team == enemy && !u.dead && u.within(core, 30 * Vars.tilesize) && seen.add(u)) System.out.println("    " + u.type.name + " near the core at " + u.tileX() + "," + u.tileY() + " after " + (int)(frames / 60) + " s");
                }
            }
            lost = !core.isValid();
            if(core.isValid()) coreLow = Math.min(coreLow, core.health / coreMax);
            lastWave = w;
            System.out.println(String.format("  wave %2d: %3d units %s; %s after %3.0f s; core %s; lost %d blocks, %d turrets (%d left)",
                w, spawned, waves.size() > w - first ? waves.get(w - first) : "", enemyCount(enemy) == 0 ? "all dead" : enemyCount(enemy) + " still alive", frames / 60f,
                core.isValid() ? Math.round(core.health / coreMax * 100) + "%" : "DESTROYED", before - count(us), turretsBefore - turretCount(us), turretCount(us)));
        }
        System.out.println("DEFENSE " + map.nameWithoutExtension() + " waves " + first + "-" + last + ": " + (lost ? "FAILS at wave " + lastWave : "HOLDS") + "; core at "
            + (core.isValid() ? Math.round(core.health / coreMax * 100) : 0) + "%, lowest " + Math.round(coreLow * 100) + "%; turrets " + turretCount(us) + " of " + turrets + " planned and placed"
            + (repair ? "; " + rebuilt + " blocks rebuilt between waves " + rebuildCost : "; nothing rebuilt"));
        System.exit(0);
    }

    /**
     * Where the waves come from, each with the largest wave it sends: each spawn (tanks, mechs and hover from the tile,
     * air from the map edge beyond it unless the map has air use the spawns), and each enemy core where waves come from
     * cores. A group set to no spawn comes from every one of them, as the game's wave spawner does it.
     */
    static void sources(Planner.Ask ask, Team enemy, int first, int last){
        Rules rules = Vars.state.rules;
        List<Planner.Source> ground = new ArrayList<>(), air = new ArrayList<>();
        IntSeq groundPos = new IntSeq(), airPos = new IntSeq();
        for(Tile spawn : Vars.spawner.getSpawns()){
            ground.add(new Planner.Source(spawn.x, spawn.y, "spawn " + spawn.x + "," + spawn.y));
            groundPos.add(spawn.pos());
            if(rules.airUseSpawns) air.add(new Planner.Source(spawn.x, spawn.y, "air spawn " + spawn.x + "," + spawn.y));
            else{
                float angle = Angles.angle(Vars.world.width() / 2f, Vars.world.height() / 2f, spawn.x, spawn.y), trns = Math.max(Vars.world.width(), Vars.world.height()) * Mathf.sqrt2;
                int ax = (int)Mathf.clamp(Vars.world.width() / 2f + Angles.trnsx(angle, trns), 0, Vars.world.width() - 1), ay = (int)Mathf.clamp(Vars.world.height() / 2f + Angles.trnsy(angle, trns), 0, Vars.world.height() - 1);
                air.add(new Planner.Source(ax, ay, "air edge " + ax + "," + ay));
            }
            airPos.add(spawn.pos());
        }
        if(rules.wavesSpawnAtCores && rules.attackMode){
            for(Building c : enemy.cores()){
                ground.add(new Planner.Source(c.tileX(), c.tileY(), "enemy core " + c.tileX() + "," + c.tileY()));
                groundPos.add(c.pos());
                air.add(new Planner.Source(c.tileX(), c.tileY(), "enemy core " + c.tileX() + "," + c.tileY() + " (air)"));
                airPos.add(c.pos());
            }
        }
        for(int k = 0; k < 2; k++){
            List<Planner.Source> list = k == 0 ? ground : air;
            IntSeq pos = k == 0 ? groundPos : airPos;
            for(int i = 0; i < list.size(); i++){
                Planner.Source src = list.get(i);
                for(int w = first; w <= last; w++){
                    Map<String, Integer> wave = new TreeMap<>();
                    for(SpawnGroup g : rules.spawns){
                        if(g.type == null || g.type.flying != (k == 1) || (g.spawn != -1 && g.spawn != pos.get(i))) continue;
                        int n = g.getSpawned(w - 1);
                        //a guardian has half as much health again and hits 1.3 times as hard: it counts as two
                        if(g.effect == StatusEffects.boss) n *= 2;
                        if(n > 0 && Foe.get(g.type.name) != null) wave.merge(g.type.name, n, Integer::sum);
                    }
                    for(var e : wave.entrySet()) src.units.merge(e.getKey(), e.getValue(), Math::max);
                    src.waves.add(wave);
                }
                for(String u : src.units.keySet()) src.moves.add(Foe.get(u).move);
                if(!src.units.isEmpty()) ask.sources.add(src);
            }
        }
    }

    static int count(Team t){
        return t.data().buildings.size;
    }

    static int turretCount(Team t){
        int n = 0;
        for(Building b : t.data().buildings) if(b.block instanceof Turret) n++;
        return n;
    }

    static int enemyCount(Team t){
        int n = 0;
        for(Unit u : Groups.unit) if(u.team == t && !u.dead) n++;
        return n;
    }
}
