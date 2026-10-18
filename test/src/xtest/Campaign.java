package xtest;

import arc.*;
import arc.files.*;
import arc.struct.*;
import arc.util.*;
import expansion.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.content.TechTree.*;
import mindustry.ctype.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.game.MapObjectives.*;
import mindustry.game.Objectives.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.type.*;
import mindustry.world.blocks.ConstructBlock.*;
import mindustry.world.blocks.storage.CoreBlock.*;

/**
 * Has the mod play the campaign of Erekir from the player's side, sector after sector in the order
 * the game unlocks them, against the game's own enemy at a chosen difficulty, and says how far it gets.
 *
 * Nothing is given to it that a player is not given. It starts each sector with the core and the
 * stock the game hands out there. It may build only what it has researched, and research goes by
 * the game's own tree: a node can be had only when the node above it is, when what the game asks
 * for besides is met (a sector landed on or taken, an item got hold of), and when its price has been
 * paid in full out of what its cores really hold. Its units are led by the squad AI the game itself
 * uses for the enemy on these maps. A sector is won the way the game counts it won.
 *
 * What is not as in the game: what its cores on sectors already taken would go on earning is left
 * out (it has only what they held when it left); landing costs nothing; and tasks that ask the
 * player to work the interface count as done.
 *
 * Usage: Campaign data-dir blueprint-dir map-dir minutes-per-sector [difficulty] [first sector]
 */
public class Campaign{
    static final String[] line = {"onset", "aegis", "lake", "intersect", "atlas", "split", "basin", "marsh", "peaks", "ravine", "caldera-erekir", "stronghold", "crevice", "siege", "crossroads", "karst", "origin"};
    static final ObjectSet<SectorPreset> taken = new ObjectSet<>(), landed = new ObjectSet<>();
    /** What its cores on the sectors it has taken hold: research may be paid for out of this too. */
    static Holdings bank;
    /**
     * Whether research is free: on landing on a sector everything the tree allows by then is had at once, without
     * paying, and whatever more it comes to allow while the sector is played (a sector taken, an item got hold of) is
     * had the moment it does. For testing how it plays, apart from how fast it can pay for research. On unless asked
     * otherwise (-Dxp.paid=true).
     */
    static final boolean free = !Boolean.getBoolean("xp.paid");

    /**
     * What its cores on the sectors it has taken hold, as the game keeps it: each such sector goes on bringing in what
     * it was measured to bring in when it was left, a turn at a time (two minutes), up to what its cores can hold. The
     * game lets research be paid out of all of them.
     */
    static class Holdings{
        static class Held{
            final String name;
            final float[] rate;
            final int[] items;
            final int cap;

            Held(String name, int cap){
                this.name = name;
                this.cap = cap;
                rate = new float[Vars.content.items().size];
                items = new int[Vars.content.items().size];
            }
        }
        final Seq<Held> held = new Seq<>();

        int get(Item item){
            int total = 0;
            for(Held one : held) total += one.items[item.id];
            return total;
        }

        void remove(Item item, int amount){
            for(Held one : held){
                int taken = Math.min(amount, one.items[item.id]);
                one.items[item.id] -= taken;
                amount -= taken;
            }
        }

        Held add(String name, int cap){
            Held one = new Held(name, cap);
            held.add(one);
            return one;
        }

        /** A turn of so many seconds goes by on the sectors it has left. */
        void turn(float seconds){
            for(Held one : held){
                for(int i = 0; i < one.rate.length; i++) one.items[i] = Math.min(one.items[i] + (int)(one.rate[i] * seconds), Math.max(one.cap, one.items[i]));
            }
        }

        @Override
        public String toString(){
            StringBuilder out = new StringBuilder("{");
            for(Item item : Vars.content.items()) if(get(item) > 0) out.append(item.name).append('=').append(get(item)).append(' ');
            return out.toString().trim() + "}";
        }
    }

    /** What its cores held each minute of the sector being played, and what research had taken out of them by then: for working out what the sector brings in. */
    static final Seq<int[]> held = new Seq<>(), paidSoFar = new Seq<>();
    static int[] paidOut = new int[0];
    static final Seq<String> researched = new Seq<>();
    static final ObjectIntMap<String> econBuilt = new ObjectIntMap<>();
    static final Seq<String> econBlocks = Seq.with("silicon-arc-furnace", "tank-fabricator", "ship-fabricator", "mech-fabricator", "tank-refabricator", "impact-drill", "electrolyzer");
    static final ObjectIntMap<String> made = new ObjectIntMap<>(), lost = new ObjectIntMap<>(), killed = new ObjectIntMap<>();
    static int problems, built, buildingsLost, played, enemyBuildingsLost;
    /** For looking into a raid: what was lost since the last report, and how far from the core. */
    static final ObjectIntMap<String> lostBlocks = new ObjectIntMap<>(), lostFar = new ObjectIntMap<>();
    static Brain brain;
    static final ObjectMap<String, ObjectIntMap<String>> spentOn = new ObjectMap<>();
    static int incomeAt10, incomeAt30, researchAt30, researchAt60;

    /** What came into its cores over the last minute, every item together. */
    static int income(Team team){
        int total = 0;
        for(Item item : Vars.content.items()) total += brain.stock.income(item);
        return total;
    }
    static Team me = Team.sharded;
    static final IntSet births = new IntSet();
    static final ObjectIntMap<String> bornAt = new ObjectIntMap<>();

    public static void main(String[] args){
        Sim.boot(args[0], args[1]);
        brain = Sim.brain;
        bank = new Holdings();
        int minutes = Integer.parseInt(args[3]);
        Planets.erekir.campaignRules.difficulty = args.length > 4 ? Difficulty.valueOf(args[4]) : Difficulty.hard;
        String first = args.length > 5 ? args[5] : null;

        //nothing researched but what everyone has from the start
        for(ContentType type : ContentType.all){
            for(Content content : Vars.content.getBy(type)){
                if(content instanceof UnlockableContent unlockable) unlockable.clearUnlock();
            }
        }
        for(TechNode node : TechTree.all) node.reset();
        brain.unlocked = UnlockableContent::unlocked;
        Brain.side = me;
        //everything that makes it stronger set to its strongest; what keeps the game fair is not among those
        if(!Boolean.getBoolean("xp.plain") && !Hand.on()) Core.settings.put("expansion-strongest", true);
        //played by hand: the mod leaves the player's side alone
        if(Hand.on()) Core.settings.put("expansion-enabled", false);
        //how long it may think in one go is measured by the clock on the wall, and with several runs going at once on one
        //machine each gets a part of what a game played by itself would: it is given longer, so that how well it plays
        //does not hang on how busy the machine is
        Core.settings.put("expansion-time-limit", Integer.getInteger("xp.timelimit", 100));

        Events.on(BlockBuildBeginEvent.class, e -> {
            if(e.breaking || e.team != me || !(e.tile.build instanceof ConstructBuild site)) return;
            if(!site.current.unlocked()){
                problems++;
                System.out.println("  PROBLEM: began " + site.current.name + ", which it has not researched");
            }
        });
        Events.on(BlockBuildEndEvent.class, e -> {
            if(!e.breaking && e.team == me) built++;
            //when the economy's main machines go up, for setting against what the economy model plans (xtest.EconLab)
            if(!e.breaking && e.team == me && e.tile.build != null && econBlocks.contains(e.tile.block().name)){
                econBuilt.increment(e.tile.block().name);
                int second = (int)(Vars.state.tick / 60);
                System.out.println(String.format("  econ: %s #%d built at %d:%02d", e.tile.block().name, econBuilt.get(e.tile.block().name), second / 60, second % 60));
            }
            //what was spent on what, by item
            if(!e.breaking && e.team == me && e.tile.build != null){
                for(ItemStack stack : e.tile.block().requirements) spentOn.get(stack.item.name, ObjectIntMap::new).increment(e.tile.block().name, stack.amount);
            }
            if(!e.breaking && e.team == me && System.getProperty("xp.trace") != null){
                String[] span = System.getProperty("xp.trace").split(",");
                int second = (int)(Vars.state.tick / 60);
                if(second >= Integer.parseInt(span[0]) && second <= Integer.parseInt(span[1]) && me.core() != null)
                    System.out.println("      built " + e.tile.block().name + " at " + e.tile.x + "," + e.tile.y + " t=" + second + "s, core has beryllium " + me.core().items.get(Items.beryllium) + " silicon " + me.core().items.get(Items.silicon));
            }
        });
        Events.on(BlockDestroyEvent.class, e -> {
            if(e.tile.team() != me && e.tile.team() != Team.derelict) enemyBuildingsLost++;
            if(e.tile.team() == me){
                buildingsLost++;
                lostBlocks.increment(e.tile.block().name);
                CoreBuild near = me.core();
                if(near != null) lostFar.increment((int)(arc.math.Mathf.dst(e.tile.x, e.tile.y, near.tileX(), near.tileY()) / 10f) * 10 + "+");
            }
        });
        Events.on(UnitCreateEvent.class, e -> {
            if(e.unit.team != me) return;
            made.increment(e.unit.type.name);
            if(made.get(e.unit.type.name) == 1){
                int second = (int)(Vars.state.tick / 60);
                System.out.println(String.format("  econ: first %s at %d:%02d", e.unit.type.name, second / 60, second % 60));
            }
            if(!e.unit.type.unlocked()){
                problems++;
                System.out.println("  PROBLEM: turned out a " + e.unit.type.name + ", which it has not researched");
            }
        });
        Events.on(UnitDestroyEvent.class, e -> (e.unit.team == me ? lost : killed).increment(e.unit.type.name));

        System.out.println("difficulty " + Planets.erekir.campaignRules.difficulty + ", up to " + minutes + " game minutes a sector");
        Seq<String> summary = new Seq<>();
        boolean skipping = first != null;
        String previous = null;
        for(String name : line){
            SectorPreset preset = Vars.content.getByName(ContentType.sector, name);
            if(skipping){
                //starting further on: the sectors before count as taken, and it has what a run that really played them
                //was left with, which that run wrote down: what it had paid for in research, and what its cores held
                if(name.equals(first)){
                    skipping = false;
                    if(free || Boolean.getBoolean("xp.grant")){
                        //for a first look at a sector no run has got to yet: everything the tree allows by then, and a modest bank.
                        //more than an honest run would have; results from this are only for finding out what a sector asks for
                        grant();
                        Holdings.Held granted = bank.add("granted", Integer.MAX_VALUE / 2);
                        for(Item item : new Item[]{Items.graphite, Items.silicon, Items.beryllium, Items.tungsten}) granted.items[item.id] = 1500;
                        System.out.println("granted everything the tree allows before " + name + ": researched " + researched.size);
                    }else if(!restore(previous)){
                        System.out.println("no record of a run that took " + previous + ": play from the start once (./test.sh campaign 90 hard) to make one");
                        System.exit(2);
                    }
                }else{
                    taken.add(preset);
                    landed.add(preset);
                    previous = name;
                    continue;
                }
            }
            unlockFree();
            if(free) grant();
            if(!preset.unlocked()){
                summary.add(name + ": could not be landed on: " + missing(preset.techNode));
                break;
            }
            String result = play(preset, new Fi(args[2]).child(name + ".msav"), minutes);
            summary.add(name + ": " + result);
            if(!result.startsWith("WON")) break;
            record(name);
            //for trying one sector many times over: no further than so many sectors in one run
            if(++played >= Integer.getInteger("xp.sectors", 99)) break;
        }

        System.out.println("\n==== the campaign of Erekir, " + Planets.erekir.campaignRules.difficulty + " ====");
        for(String row : summary) System.out.println("  " + row);
        System.out.println("  sectors taken: " + taken.size + " of " + line.length);
        System.out.println(problems == 0 ? "RESULT: nothing built or researched out of turn" : "RESULT: " + problems + " PROBLEMS");
        System.exit(0);
    }

    /** Plays one sector to the end, or until the time is up. */
    static String play(SectorPreset preset, Fi file, int minutes){
        Vars.logic.reset();
        SaveIO.load(file);
        Rules rules = Vars.state.rules;
        Planet planet = Planets.erekir;
        if(rules.defaultTeam != me) throw new IllegalStateException("the player's side on " + preset.name + " is " + rules.defaultTeam);
        Team enemy = rules.waveTeam;

        //the rules the game lays on a sector of the campaign when it is landed on
        planet.applyRules(rules, false);
        rules.coreIncinerates = true;
        rules.infiniteResources = false;
        if(planet.enemyInfiniteItems){
            enemy.rules().infiniteResources = true;
            enemy.rules().fillItems = true;
        }
        //(a question asked of a sector, not a way to play it: whether it is what its side has that it is short of)
        if(System.getProperty("xp.rich") != null){
            me.rules().infiniteResources = true;
            me.rules().fillItems = true;
        }
        enemy.rules().buildSpeedMultiplier *= planet.enemyBuildSpeedMultiplier;
        enemy.rules().unitFactoryActivationDelay = Math.max(enemy.rules().unitFactoryActivationDelay, planet.enemyFactoryActivationDelay);
        if(preset.captureWave > 0) rules.winWave = preset.captureWave;
        Time.setDeltaProvider(() -> 1f);
        Vars.logic.play();
        //for runs that can be repeated exactly: the game's dice start from a given number
        if(System.getProperty("xp.seed") != null) arc.math.Mathf.rand.setSeed(Long.getLong("xp.seed"));
        Vars.state.wavetime *= planet.campaignRules.difficulty.waveTimeMultiplier;
        Vars.state.tick = 0;
        landed.add(preset);
        built = buildingsLost = enemyBuildingsLost = 0;
        spentOn.clear();
        held.clear();
        paidSoFar.clear();
        paidOut = new int[Vars.content.items().size];
        incomeAt10 = incomeAt30 = 0;
        researchAt30 = researchAt60 = -1;
        made.clear();
        econBuilt.clear();
        lost.clear();
        killed.clear();
        Boot.frames(2);
        long wallStart = System.nanoTime(), framesStart = Boot.framesRun, modStart = Boot.nanosMod;

        int enemyCores = enemy.cores().size;
        System.out.println("\n######## " + preset.name + ": " + Vars.world.width() + "x" + Vars.world.height() + ", " + (rules.attackMode ? "take " + enemyCores + " enemy cores" : "hold out for " + rules.winWave + " waves")
            + ", " + rules.objectives.all.size + " tasks; starts with " + holds(me) + "; researched so far " + researched.size);

        //what the map hands the player to begin with
        if(System.getProperty("xp.every") != null) Human.describe(me, enemy, "the player", false);
        if(System.getProperty("xp.raid") != null){
            //the vents there are, how far each is from its core, and whether a turbine could go on it
            StringBuilder vents = new StringBuilder("     vents:");
            arc.struct.IntSeq all = brain.terrain.vents(Blocks.turbineCondenser, me);
            for(int i = 0; i < all.size; i++){
                int vx = arc.math.geom.Point2.x(all.get(i)), vy = arc.math.geom.Point2.y(all.get(i));
                CoreBuild home = me.core();
                vents.append(' ').append(vx).append(',').append(vy).append('(').append(home == null ? -1 : (int)arc.math.Mathf.dst(vx, vy, home.tileX(), home.tileY())).append(Vars.world.build(vx, vy) != null ? " taken" : "").append(Vars.world.isInMapArea(vx, vy) ? "" : " off").append(')');
            }
            System.out.println(vents);
            //what stands by everything that makes something: how its product leaves it
            for(Building build : me.data().buildings){
                if(!(build.block instanceof mindustry.world.blocks.production.GenericCrafter) && !(build.block instanceof mindustry.world.blocks.production.BeamDrill)) continue;
                StringBuilder by = new StringBuilder();
                for(Building other : build.proximity) by.append(' ').append(other.block.name).append('@').append(other.tileX()).append(',').append(other.tileY()).append(other.block.rotate ? " r" + other.rotation : "");
                System.out.println("     " + build.block.name + " at " + build.tileX() + "," + build.tileY() + " r" + build.rotation + ", by it:" + by);
            }
        }
        int every = Integer.getInteger("xp.every", 5);
        String result = null;
        int minute = 0;
        if(Hand.on()) Hand.setup();
        for(int second = 1; second <= minutes * 60 && result == null; second++){
            if(Hand.on() && Hand.tick(second, me, enemy)){
                result = "GAVE UP";
                break;
            }
            Boot.frames(60);
            research();
            if(me.cores().isEmpty()){
                result = "LOST: its last core fell";
            }else if(rules.attackMode && enemy.cores().isEmpty()){
                result = "WON";
            }else if(!rules.attackMode && rules.winWave > 0 && Vars.state.wave >= rules.winWave && Vars.state.enemies == 0){
                //some sectors turn into an attack on the enemy's base once the waves are over
                if(preset.attackAfterWaves && !enemy.cores().isEmpty()) rules.attackMode = true;
                else result = "WON";
            }
            if(System.getProperty("xp.fog") != null && second % 20 == 0 && second > 60 * Integer.getInteger("xp.fogfrom", 0)){
                String[] at = System.getProperty("xp.fog").split(",");
                int fx = Integer.parseInt(at[0]), fy = Integer.parseInt(at[1]);
                StringBuilder where = new StringBuilder();
                for(Unit unit : me.data().units){
                    if(unit.controller() instanceof ExpansionAI ai) where.append(" builder at ").append(unit.tileX()).append(",").append(unit.tileY()).append(ai.scout != null ? " looking at " + (int)(ai.scout.x / 8) + "," + (int)(ai.scout.y / 8) : ai.order != null ? " building" : ai.dig != null ? " mining" : " -");
                }
                System.out.println("    t=" + second + "s seen " + fx + "," + fy + ": " + Vars.fogControl.isDiscovered(me, fx, fy) + where);
            }
            if(System.getProperty("xp.fight") != null && second % 3 == 0){
                //a fight seen from above, compactly: within so many tiles of a place, its own fighting units, the other side's,
                //and the other side's turrets with what they hold
                String[] at = System.getProperty("xp.fight").split(",");
                float fx = Float.parseFloat(at[0]) * 8f, fy = Float.parseFloat(at[1]) * 8f, fr = Float.parseFloat(at[2]) * 8f;
                int mine = 0, theirs = 0;
                float mineHp = 0f, theirHp = 0f;
                for(Unit unit : me.data().units) if(!unit.spawnedByCore && unit.within(fx, fy, fr)){ mine++; mineHp += unit.health; }
                for(Unit unit : enemy.data().units) if(!unit.spawnedByCore && unit.within(fx, fy, fr)){ theirs++; theirHp += unit.health; }
                if(mine > 0){
                    StringBuilder out = new StringBuilder(String.format("    fight t=%d: own %d (%d hp), theirs %d (%d hp);", second, mine, (int)mineHp, theirs, (int)theirHp));
                    for(Building build : enemy.data().buildings){
                        if(build instanceof mindustry.world.blocks.defense.turrets.ItemTurret.ItemTurretBuild t && build.within(fx, fy, fr)) out.append(String.format(" %s@%d,%d hp%d ammo%d%s", build.block.name, build.tileX(), build.tileY(), (int)build.health, t.totalAmmo, t.isShooting() ? "*" : ""));
                    }
                    System.out.println(out);
                }
            }
            if(System.getProperty("xp.hash") != null){
                //a fingerprint of how things stand, to find the first second two runs from the same start part ways
                long units = 0, blocks = Vars.state.teams.get(me).buildings.size, plans = me.data().plans.size;
                for(Unit unit : Groups.unit) units = units * 31 + (long)(unit.x * 10) * 7 + (long)(unit.y * 10) + unit.type.id;
                long rand = arc.math.Mathf.rand.nextLong();
                arc.math.Mathf.rand.setSeed(rand);
                System.out.println("    hash t=" + second + " units " + Groups.unit.size() + " " + units + " blocks " + blocks + " plans " + plans + " core " + holds(me) + " rand " + rand);
            }
            if(System.getProperty("xp.trace") != null && second % 5 == 0){
                //a close look at the fighting: where each unit of the enemy's is and what it fires at, and each turret of its own
                String[] span = System.getProperty("xp.trace").split(",");
                if(second >= Integer.parseInt(span[0]) && second <= Integer.parseInt(span[1])){
                    StringBuilder out = new StringBuilder("    t=" + second + "s");
                    for(Unit unit : enemy.data().units){
                        if(unit.spawnedByCore) continue;
                        Teamc aim = unit.isShooting ? null : null;
                        out.append(String.format(" | %s %d,%d hp%d%s", unit.type.name, unit.tileX(), unit.tileY(), (int)unit.health,
                            unit.mounts.length > 0 && unit.mounts[0].target != null ? " at " + (unit.mounts[0].target instanceof Building b ? b.block.name + "@" + b.tileX() + "," + b.tileY() : "unit") : ""));
                    }
                    if(System.getProperty("xp.traceown") != null){
                        //its own fighting units instead of its turrets, and the other side's turrets near them
                        for(Unit unit : me.data().units){
                            if(unit.spawnedByCore || !unit.type.canAttack || !(unit.controller() instanceof FighterAI fa)) continue;
                            if(fa.posted) out.append(String.format(" [post %d,%d]", (int)(fa.post.x / 8), (int)(fa.post.y / 8)));
                            if(fa.goal instanceof Building g) out.append(String.format(" [goal %s@%d,%d %s]", g.block.name, g.tileX(), g.tileY(), fa.how));
                            else if(fa.goal != null) out.append(" [goal unit]");
                            Teamc aim = unit.mounts.length > 0 ? unit.mounts[0].target : null;
                            out.append(String.format(" || %s %d,%d hp%d%s%s", unit.type.name, unit.tileX(), unit.tileY(), (int)unit.health, unit.isShooting ? " firing" : "",
                                aim instanceof Building b ? " at " + b.block.name + "@" + b.tileX() + "," + b.tileY() : aim instanceof Unit u ? " at " + u.type.name : ""));
                        }
                        for(Building build : enemy.data().buildings){
                            if(build instanceof mindustry.world.blocks.defense.turrets.Turret.TurretBuild turret && build.health < build.maxHealth) out.append(String.format(" ## %s %d,%d hp%d", build.block.name, build.tileX(), build.tileY(), (int)build.health));
                        }
                    }else for(Building build : me.data().buildings){
                        if(build instanceof mindustry.world.blocks.defense.turrets.Turret.TurretBuild turret) out.append(String.format(" || %s %d,%d hp%d ammo%d%s", build.block.name, build.tileX(), build.tileY(), (int)build.health,
                            turret instanceof mindustry.world.blocks.defense.turrets.ItemTurret.ItemTurretBuild it ? it.totalAmmo : -1, turret.target != null ? " at " + (turret.target instanceof Unit u ? u.type.name + "@" + u.tileX() + "," + u.tileY() : "bld") : ""));
                    }
                    System.out.println(out);
                }
            }
            if(System.getProperty("xp.frames") != null){
                //a picture of a stretch of the map every few seconds in a span: x0,y0,w,h,from,to,step[,dir]
                String[] f = System.getProperty("xp.frames").split(",");
                int fx0 = Integer.parseInt(f[0]), fy0 = Integer.parseInt(f[1]), fw = Integer.parseInt(f[2]), fh = Integer.parseInt(f[3]);
                int from = Integer.parseInt(f[4]), to = Integer.parseInt(f[5]), step = Integer.parseInt(f[6]);
                if(second >= from && second <= to && (second - from) % step == 0) Frames.write(new Fi(f.length > 7 ? f[7] : "build/frames").child(String.format("f%05d.png", second)), fx0, fy0, fw, fh, me);
            }
            if(System.getProperty("xp.feed") != null){
                //(a question, not a way to play: what its army would do if every yard were always fed) each yard and refit of its own
                //filled with what it takes
                for(Building build : me.data().buildings){
                    if(build instanceof mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild yard && yard.currentPlan != -1){
                        for(var stack : ((mindustry.world.blocks.units.UnitFactory)build.block).plans.get(yard.currentPlan).requirements) build.items.set(stack.item, Math.max(build.items.get(stack.item), stack.amount * 2));
                    }else if(build instanceof mindustry.world.blocks.units.Reconstructor.ReconstructorBuild){
                        for(var stack : build.block.consumers) if(stack instanceof mindustry.world.consumers.ConsumeItems items) for(var it : items.items) build.items.set(it.item, Math.max(build.items.get(it.item), it.amount * 2));
                    }
                }
            }
            if(System.getProperty("xp.give") != null){
                //(a question, not a way to play: what its army would do with units it can't make yet) minute:unit:count, beside its core
                String[] g = System.getProperty("xp.give").split(":");
                CoreBuild home = me.core();
                if(second == Integer.parseInt(g[0]) * 60 && home != null){
                    mindustry.type.UnitType type = Vars.content.unit(g[1]);
                    for(int i = 0; i < Integer.parseInt(g[2]); i++) type.spawn(me, home.x + arc.math.Mathf.range(40f), home.y + arc.math.Mathf.range(40f));
                    System.out.println("    given " + g[2] + " " + g[1]);
                }
            }
            if(System.getProperty("xp.kill") != null){
                //what a map does once given buildings of the other side's are down: minute:x,y:x,y...
                String[] k = System.getProperty("xp.kill").split(":");
                if(second == Integer.parseInt(k[0]) * 60){
                    for(int i = 1; i < k.length; i++){
                        String[] p = k[i].split(",");
                        Building b = Vars.world.build(Integer.parseInt(p[0]), Integer.parseInt(p[1]));
                        System.out.println("    killed " + (b == null ? "nothing" : b.block.name + " of " + b.team) + " at " + k[i]);
                        if(b != null) b.kill();
                    }
                }
                if(second % 30 == 0 && second > Integer.parseInt(k[0]) * 60) System.out.println("    t=" + second + " enemy cores " + enemy.cores().size + " flags " + rules.objectiveFlags + " " + task(rules));
            }
            if(System.getProperty("xp.births") != null){
                //where the other side's units come from: each new one counted against the nearest of its yards when first seen
                for(Unit unit : enemy.data().units){
                    if(unit.spawnedByCore || !births.add(unit.id)) continue;
                    Building from = null;
                    for(Building build : enemy.data().buildings){
                        if((build.block instanceof mindustry.world.blocks.units.UnitFactory || build.block instanceof mindustry.world.blocks.units.Reconstructor) && (from == null || build.dst(unit) < from.dst(unit))) from = build;
                    }
                    bornAt.increment(unit.type.name + (from == null ? "@?" : "@" + from.tileX() + "," + from.tileY()));
                }
                if(second % 300 == 0) System.out.println("           born " + bornAt);
            }
            if(System.getProperty("xp.rich") != null && me.core() != null){
                CoreBuild core = me.core();
                for(Item item : Vars.content.items()) if(item.buildable || item == Items.sand) core.items.set(item, core.storageCapacity);
            }
            if(System.getProperty("xp.corewatch") != null && second % 5 == 0 && me.core() != null && me.core().health < me.core().maxHealth * 0.95f){
                //while its core is being hurt: who is at it, and where its own fighting units are and what they are about
                CoreBuild core = me.core();
                StringBuilder out = new StringBuilder(String.format("    core t=%d hp%d/%d:", second, (int)core.health, (int)core.maxHealth));
                for(Unit unit : enemy.data().units){
                    if(unit.spawnedByCore || !unit.within(core, 50f * 8f)) continue;
                    Teamc aim = unit.mounts.length > 0 ? unit.mounts[0].target : null;
                    out.append(String.format(" %s@%d,%d hp%d%s", unit.type.name, unit.tileX(), unit.tileY(), (int)unit.health, aim instanceof Building b ? ">" + b.block.name : aim instanceof Unit u ? ">" + u.type.name : ""));
                }
                out.append(" || own:");
                for(Unit unit : me.data().units){
                    if(unit.spawnedByCore || !unit.type.canAttack) continue;
                    Teamc aim = unit.mounts.length > 0 ? unit.mounts[0].target : null;
                    String doing = unit.controller() instanceof FighterAI fa ? (fa.posted ? " post" + (int)(fa.post.x / 8) + "," + (int)(fa.post.y / 8) : "") + (fa.goal instanceof Building g ? " goal" + g.block.name : fa.goal instanceof Unit u ? " goal" + u.type.name : "")
                        : unit.controller() instanceof mindustry.ai.types.CommandAI ca ? (ca.attackTarget instanceof Unit u ? " cmd>" + u.type.name : ca.attackTarget instanceof Building b ? " cmd>" + b.block.name : ca.targetPos != null ? " cmd@" + (int)(ca.targetPos.x / 8) + "," + (int)(ca.targetPos.y / 8) : "") : "";
                    out.append(String.format(" %s@%d,%d hp%d%s%s", unit.type.name, unit.tileX(), unit.tileY(), (int)unit.health, doing, aim instanceof Unit u ? ">" + u.type.name : aim instanceof Building b ? ">" + b.block.name : ""));
                }
                System.out.println(out);
            }
            if(second % 60 == 0 && System.getProperty("xp.saves") != null && ("," + System.getProperty("xp.saves") + ",").contains("," + (second / 60) + ",")){
                //a save of how it stands, to be looked at in the game itself
                Fi to = new Fi(System.getProperty("xp.savedir", "build/saves")).child(preset.name + "-" + String.format("%03d", second / 60) + "min.msav");
                to.parent().mkdirs();
                SaveIO.save(to);
            }
            if(second % 120 == 0) bank.turn(120f);
            if(second % 60 == 0 && me.core() != null){
                int[] now = new int[Vars.content.items().size];
                for(Item item : Vars.content.items()) now[item.id] = me.core().items.get(item);
                held.add(now);
                paidSoFar.add(paidOut.clone());
            }
            if(second % 60 == 0){
                minute = second / 60;
                //what a run is measured by besides how it ended, at set times
                if(minute == 10) incomeAt10 = income(me);
                if(minute == 30){
                    incomeAt30 = income(me);
                    researchAt30 = researched.size;
                }
                if(minute == 60) researchAt60 = researched.size;
                if(minute % every == 0 || result != null) System.out.println(String.format("  min %3d: %s | tasks %d/%d%s | built %d, lost %d | units %s, lost %s, killed %s | enemy cores %d%s | %s, researched %d",
                    minute, holds(me), rules.objectives.all.count(MapObjective::isCompleted), rules.objectives.all.size, task(rules), built, buildingsLost, made, lost, killed,
                    enemy.cores().size, rules.waves ? ", wave " + Vars.state.wave : "", brain.director.posture + (brain.homeShort ? " homeShort" : "") + (brain.turretsHold ? "" : " turretsShort") + armyAt(me), researched.size));
                if(System.getProperty("xp.zones") != null && (minute % every == 0 || result != null)) System.out.print(brain.atlas.describe());
                if(System.getProperty("xp.sources") != null){
                    //each source of the items named that its plan knows of: what it is, where, and whether its line runs whole
                    for(String name : System.getProperty("xp.sources").split(",")){
                        StringBuilder out = new StringBuilder("           sources " + name + ":");
                        for(var thing : brain.net.things){
                            if(thing.item == null || !thing.item.name.equals(name)) continue;
                            out.append(' ').append(thing.kind).append('@').append(thing.x).append(',').append(thing.y).append(brain.net.whole(thing) ? "" : "(cut)");
                        }
                        System.out.println(out);
                    }
                }
                if(System.getProperty("xp.area") != null){
                    Rules r = Vars.state.rules;
                    System.out.println("           area limit " + r.limitMapArea + " " + r.limitX + "," + r.limitY + " " + r.limitWidth + "x" + r.limitHeight);
                }
                if(System.getProperty("xp.whyat") != null){
                    //why nothing may go on the ground about a place, a letter a tile (see Brain.whyNot), at the minutes named
                    String[] at = System.getProperty("xp.whyat").split(",");
                    int wx = Integer.parseInt(at[0]), wy = Integer.parseInt(at[1]), wr = Integer.parseInt(at[2]);
                    if(minute == Integer.parseInt(at[3])){
                        for(int y = wy + wr; y >= wy - wr; y--){
                            StringBuilder row = new StringBuilder(String.format("    why %4d ", y));
                            for(int x = wx - wr; x <= wx + wr; x++) row.append(built(x, y, brain.whyNot(x, y)));
                            System.out.println(row);
                        }
                    }
                }
                if(System.getProperty("xp.notes") != null){
                    //what it last said about the goals named
                    for(String key : System.getProperty("xp.notes").split(",")) System.out.println("           note " + key + ": " + brain.note(key));
                }
                if(System.getProperty("xp.raid") != null && (minute % every == 0 || result != null)){
                    //who is at its base, and what that cost it since the last report
                    ObjectIntMap<String> raiders = new ObjectIntMap<>();
                    CoreBuild home = me.core();
                    int close = 0;
                    for(Unit unit : enemy.data().units){
                        raiders.increment(unit.type.name);
                        if(home != null && unit.within(home, 40f * 8f)) close++;
                    }
                    StringBuilder near = new StringBuilder();
                    for(Unit unit : enemy.data().units) if(home != null && unit.within(home, 40f * 8f)) near.append(' ').append(unit.type.name).append('@').append(unit.tileX()).append(',').append(unit.tileY());
                    StringBuilder turrets = new StringBuilder();
                    for(Building build : me.data().buildings){
                        if(build instanceof mindustry.world.blocks.defense.turrets.ItemTurret.ItemTurretBuild turret) turrets.append(' ').append(build.tileX()).append(',').append(build.tileY()).append(turret.totalAmmo > 0 ? " loaded" : " EMPTY").append(home == null ? "" : " " + (int)(build.dst(home) / 8f) + " from core");
                    }
                    System.out.println("           enemy units " + raiders + ", " + close + " within 40 tiles of its core:" + near + "; lost since " + lostBlocks + " at " + lostFar + " tiles from the core; turrets:" + turrets);
                    lostBlocks.clear();
                    lostFar.clear();
                }
            }
        }
        if(result == null) result = "NOT WON in " + minutes + " minutes";
        if(System.getProperty("xp.spent") != null){
            for(var entry : spentOn){
                Seq<String> names = new Seq<>();
                for(var each : entry.value) names.add(each.key);
                ObjectIntMap<String> by = entry.value;
                names.sort(name -> -by.get(name, 0));
                StringBuilder out = new StringBuilder("  spent " + entry.key + ":");
                int total = 0;
                for(var each : by) total += each.value;
                out.append(' ').append(total).append(" on");
                for(int i = 0; i < Math.min(names.size, 8); i++) out.append(' ').append(names.get(i)).append('=').append(by.get(names.get(i), 0));
                System.out.println(out);
            }
        }
        //one line a run of what it is measured by, for comparing many runs: how it ended, how long it lasted, what it lost,
        //what came in a minute at 10 and 30 minutes, how far research got by 30 and 60, units made, the other side's buildings destroyed
        int madeAll = 0;
        for(var entry : made) madeAll += entry.value;
        System.out.println(String.format("  METRICS sector=%s result=%s minute=%d lost=%d income10=%d income30=%d research30=%d research60=%d made=%d destroyed=%d",
            preset.name, result.startsWith("WON") ? "won" : result.startsWith("LOST") ? "lost" : "timeout", Math.max(minute, 1), buildingsLost, incomeAt10, incomeAt30,
            researchAt30 < 0 ? researched.size : researchAt30, researchAt60 < 0 ? researched.size : researchAt60, madeAll, enemyBuildingsLost));
        result += " (minute " + Math.max(minute, 1) + "; tasks " + rules.objectives.all.count(MapObjective::isCompleted) + "/" + rules.objectives.all.size + task(rules)
            + "; enemy cores left " + enemy.cores().size + " of " + enemyCores + "; enemy buildings destroyed " + enemyBuildingsLost + "; its units " + made + ", lost " + lost + ", killed " + killed + ")";
        System.out.println("  " + brain.status().replace("\n", "\n  "));
        //where a line is said to be cut: what stands there and around it
        java.util.regex.Matcher cut = java.util.regex.Pattern.compile("cut at (\\d+),(\\d+)").matcher(brain.status());
        ObjectSet<String> shown = new ObjectSet<>();
        while(cut.find() && shown.size < 3){
            if(!shown.add(cut.group())) continue;
            int cx = Integer.parseInt(cut.group(1)), cy = Integer.parseInt(cut.group(2));
            StringBuilder around = new StringBuilder("  " + cut.group() + ":");
            for(int dy = 2; dy >= -2; dy--){
                around.append("\n     ");
                for(int dx = -3; dx <= 3; dx++){
                    Building b = Vars.world.build(cx + dx, cy + dy);
                    around.append(String.format("%-22s", b == null ? "." : (b instanceof ConstructBuild site ? "(" + site.current.name + ")" : b.block.name) + (b.block.rotate ? " r" + b.rotation : "")));
                }
            }
            System.out.println(around);
        }
        //where its army stands and what it has been told
        ObjectIntMap<String> doing = new ObjectIntMap<>();
        float sumX = 0f, sumY = 0f;
        int counted = 0;
        for(Unit unit : me.data().units){
            if(!(unit.controller() instanceof mindustry.ai.types.CommandAI ai)) continue;
            doing.increment((ai.attackTarget != null ? "attacking " + ai.attackTarget : ai.targetPos != null ? "moving to " + (int)(ai.targetPos.x / 8) + "," + (int)(ai.targetPos.y / 8) : "idle") + (ai.currentCommand() == null ? "" : " [" + ai.currentCommand().name + "]"));
            sumX += unit.x / 8f;
            sumY += unit.y / 8f;
            counted++;
        }
        if(counted > 0) System.out.println("  its " + counted + " fighting units are around " + (int)(sumX / counted) + "," + (int)(sumY / counted) + ": " + doing);
        for(CoreBuild core : enemy.cores()) System.out.println("  enemy core at " + core.tileX() + "," + core.tileY() + (Vars.world.isInMapArea(core.tileX(), core.tileY()) ? "" : " (outside the open part of the map)"));
        for(CoreBuild core : me.cores()) System.out.println("  its core at " + core.tileX() + "," + core.tileY());
        if(System.getProperty("xp.draw") != null){
            String[] box = System.getProperty("xp.draw").split(",");
            brain.syncGround();
            Picture.side = me;
            System.out.println(Picture.region(Integer.parseInt(box[0]), Integer.parseInt(box[1]), Integer.parseInt(box[2]), Integer.parseInt(box[3]), brain.ground));
            //and where the ore in the floor lies
            StringBuilder ore = new StringBuilder("  tungsten in the floor at:");
            arc.struct.IntSeq floor = brain.survey.floor(Items.tungsten);
            for(int i = 0; i < floor.size; i++) ore.append(' ').append(arc.math.geom.Point2.x(floor.get(i))).append(',').append(arc.math.geom.Point2.y(floor.get(i)));
            System.out.println(ore);
            Picture.side = null;
            //and why nothing may go where nothing stands
            int bx = Integer.parseInt(box[0]), by = Integer.parseInt(box[1]), bw = Integer.parseInt(box[2]), bh = Integer.parseInt(box[3]);
            System.out.println("  why not (b built, w wall or water, k kept, p planned, o ore, l way home, t not its ground, s scripts, a given up, h shunned, g guns, c units' way, z core zone, n near core, m off the map):");
            for(int y = by + bh - 1; y >= by; y--){
                StringBuilder row = new StringBuilder(String.format("  %3d ", y));
                for(int x = bx; x < bx + bw; x++) row.append(brain.whyNot(x, y));
                row.append("   ");
                for(int x = bx; x < bx + bw; x++) row.append(brain.room(x, y) == 0 ? "." : "" + Math.min(brain.room(x, y), 9));
                System.out.println(row);
            }
        }
        System.out.println("  " + result);
        double wall = (System.nanoTime() - wallStart) / 1e9, frames = Boot.framesRun - framesStart;
        System.out.println(String.format("  speed: %.0f game seconds in %.1f s, %.0f times as fast as played; the mod took %.0f%% of that", frames / 60.0, wall, frames / 60.0 / wall, (Boot.nanosMod - modStart) / 1e7 / wall));
        if(System.getProperty("xp.prof") != null) System.out.println("  where the mod's time went: " + Prof.report());
        System.out.println("  researched: " + researched);
        //what it has standing, set out the way a person's saved base is, to put the two side by side
        Human.describe(me, enemy, "the player", System.getProperty("xp.draw") != null);
        for(Building build : me.data().buildings){
            if(build.block instanceof mindustry.world.blocks.production.Drill) System.out.println(String.format("     %s at %d,%d: working at %.0f%%, power %.0f%%, holds %s, water %.0f", build.block.name, build.tileX(), build.tileY(),
                build.efficiency * 100f, build.power == null ? 100f : build.power.status * 100f, build.items, build.liquids == null ? 0f : build.liquids.get(Liquids.water)));
        }
        //the way home it keeps open from ore it does not mine yet: how much of it is still clear
        if(!brain.ground.lane.isEmpty()){
            StringBuilder blocked = new StringBuilder();
            int taken = 0;
            arc.struct.IntSet.IntSetIterator each = brain.ground.lane.iterator();
            while(each.hasNext){
                int pos = each.next();
                Building on = Vars.world.build(pos);
                if(on == null) continue;
                if(taken++ < 10) blocked.append(' ').append(on.block.name).append('@').append(arc.math.geom.Point2.x(pos)).append(',').append(arc.math.geom.Point2.y(pos));
            }
            StringBuilder where = new StringBuilder();
            arc.struct.IntSet.IntSetIterator all = brain.ground.lane.iterator();
            while(all.hasNext){
                int pos = all.next();
                where.append(' ').append(arc.math.geom.Point2.x(pos)).append(',').append(arc.math.geom.Point2.y(pos)).append('(').append(brain.territory.steps(arc.math.geom.Point2.x(pos), arc.math.geom.Point2.y(pos))).append(')');
            }
            System.out.println("     way home kept open: " + brain.ground.lane.size + " tiles, " + taken + " built on:" + blocked);
        }else{
            System.out.println("     no way home kept open at the end");
        }
        for(Unit unit : me.data().units){
            if(unit.controller() instanceof ExpansionAI ai) System.out.println("     builder at " + unit.tileX() + "," + unit.tileY() + ": " + (ai.order != null ? "building " + ai.order.block.name : ai.haul != null ? "carrying " + ai.haul.item.name : ai.dig != null ? "mining " + ai.dig.item.name : "nothing to do") + "; to build " + me.data().plans.size);
        }

        if(result.startsWith("WON")){
            taken.add(preset);
            //what its core holds stays there, and can pay for research from now on
            CoreBuild core = me.core();
            if(core != null){
                //it goes on bringing in what it was measured to bring in over its last five minutes, net of what was spent
                Holdings.Held kept = bank.add(preset.name, core.storageCapacity);
                core.items.each((item, amount) -> kept.items[item.id] = amount);
                int back = Math.min(5, held.size - 1);
                if(back > 0){
                    int[] now = held.peek(), then = held.get(held.size - 1 - back), paidNow = paidSoFar.peek(), paidThen = paidSoFar.get(paidSoFar.size - 1 - back);
                    for(int i = 0; i < kept.rate.length; i++) kept.rate[i] = Math.max(0f, (now[i] - then[i] + paidNow[i] - paidThen[i]) / (back * 60f));
                }
                StringBuilder rates = new StringBuilder();
                for(Item item : Vars.content.items()) if(kept.rate[item.id] > 0f) rates.append(' ').append(item.name).append('=').append(Strings.fixed(kept.rate[item.id] * 60f, 0)).append("/min");
                System.out.println("  " + preset.name + " will go on bringing in" + (rates.length() == 0 ? " nothing" : rates) + ", up to " + core.storageCapacity + " of each");
            }
        }
        return result;
    }

    /** The task the map is waiting on, if any. */
    /** What stands on a tile, as one letter: ducts by the way they face, = bridge, ~ conduit, + node, D drill, F factory, Y yard, W wall, T turret, C core, # anything else; else why nothing may go there. */
    static char built(int x, int y, char why){
        mindustry.world.Tile t = Vars.world.tile(x, y);
        if(t == null || t.build == null) return why;
        var b = t.build.block;
        if(b instanceof mindustry.world.blocks.distribution.Duct) return ">^<v".charAt(t.build.rotation);
        if(b instanceof mindustry.world.blocks.distribution.DirectionBridge) return '=';
        if(b instanceof mindustry.world.blocks.distribution.DuctRouter || b instanceof mindustry.world.blocks.distribution.OverflowDuct) return 'r';
        if(b instanceof mindustry.world.blocks.liquid.LiquidBlock || b instanceof mindustry.world.blocks.liquid.LiquidBridge) return '~';
        if(b instanceof mindustry.world.blocks.power.BeamNode || b instanceof mindustry.world.blocks.power.PowerNode) return '+';
        if(b instanceof mindustry.world.blocks.production.BeamDrill || b instanceof mindustry.world.blocks.production.Drill || b instanceof mindustry.world.blocks.production.WallCrafter) return 'D';
        if(b instanceof mindustry.world.blocks.units.UnitFactory || b instanceof mindustry.world.blocks.units.Reconstructor) return 'Y';
        if(b instanceof mindustry.world.blocks.production.GenericCrafter) return 'F';
        if(b instanceof mindustry.world.blocks.defense.Wall) return 'W';
        if(b instanceof mindustry.world.blocks.defense.turrets.Turret) return 'T';
        if(b instanceof mindustry.world.blocks.storage.CoreBlock) return 'C';
        return '#';
    }

    static String task(Rules rules){
        for(MapObjective objective : rules.objectives.all){
            if(!objective.isCompleted() && objective.dependencyFinished()) return ", now: " + objective.getClass().getSimpleName().replace("Objective", "") + " " + CampaignInfo.fields(objective).replaceAll("(editorX|editorY|markers|parents|flagsAdded|flagsRemoved|text|details)=[^ ]* ?", "").trim()
                + (objective instanceof CoreItemObjective item ? " (counted " + Vars.state.stats.coreItemCount.get(item.item) + " of all " + Vars.state.stats.coreItemCount + ", core " + (me.core() == null ? "-" : me.core().getClass().getSimpleName() + " " + me.core().block.name) + ")" : "");
        }
        return "";
    }

    /** Where its fighting units are on the whole, for the line each minute. */
    static String armyAt(Team team){
        int n = 0;
        float x = 0f, y = 0f;
        for(Unit unit : team.data().units){
            if(unit.spawnedByCore || !unit.type.canAttack) continue;
            n++;
            x += unit.tileX();
            y += unit.tileY();
        }
        return n == 0 ? "" : " army " + n + " at " + (int)(x / n) + "," + (int)(y / n);
    }

    static String holds(Team team){
        CoreBuild core = team.core();
        if(core == null) return "no core";
        StringBuilder out = new StringBuilder();
        core.items.each((item, amount) -> out.append(item.name).append('=').append(amount).append(' '));
        return out.length() == 0 ? "nothing" : out.toString().trim();
    }

    //region research

    /** Whether what the game asks for besides its price, for a node to be had, is met. */
    static boolean met(TechNode node){
        if(node.parent != null && !node.parent.content.unlocked()) return false;
        for(Objective objective : node.objectives){
            boolean done = objective instanceof Research research ? research.content.unlocked()
                : objective instanceof Produce produce ? produce.content.unlocked()
                : objective instanceof SectorComplete complete ? taken.contains(complete.preset)
                : objective instanceof OnSector on ? landed.contains(on.preset)
                : objective instanceof OnPlanet planet && planet.planet == Planets.erekir;
            if(!done) return false;
        }
        return true;
    }

    static String missing(TechNode node){
        StringBuilder out = new StringBuilder();
        if(node.parent != null && !node.parent.content.unlocked()) out.append("research ").append(node.parent.content.name).append("; ");
        for(Objective objective : node.objectives){
            if(objective instanceof Research research && !research.content.unlocked()) out.append("research ").append(research.content.name).append("; ");
            if(objective instanceof Produce produce && !produce.content.unlocked()) out.append("get hold of ").append(produce.content.name).append("; ");
            if(objective instanceof SectorComplete complete && !taken.contains(complete.preset)) out.append("take ").append(complete.preset.name).append("; ");
            if(objective instanceof OnSector on && !landed.contains(on.preset)) out.append("land on ").append(on.preset.name).append("; ");
        }
        return out.toString();
    }

    static boolean erekir(TechNode node){
        TechNode root = node;
        while(root.parent != null) root = root.parent;
        return root == Planets.erekir.techTree;
    }

    static void unlock(UnlockableContent content){
        if(content.unlocked()) return;
        content.unlock();
        researched.add(content.name);
    }

    /** What costs nothing is had the moment what the game asks for it is met: sectors, and the items and liquids it has got hold of. The game does the same. */
    static void unlockFree(){
        boolean again = true;
        while(again){
            again = false;
            for(TechNode node : TechTree.all){
                if(node.content.unlocked() || !erekir(node) || node.requirements.length > 0 || !met(node)) continue;
                unlock(node.content);
                again = true;
            }
        }
    }

    /** Records are read from the ones kept with the tests, then the ones of the last run; a run writes its own where it is told to, so that many can go at once. */
    static Fi stateDir = new Fi("test/campaign"), lastDir = new Fi("build/campaign-state"), freshDir = new Fi(System.getProperty("xp.state", "build/campaign-state"));

    /** Writes down how it stands after taking a sector: what it has researched, and what its cores on the sectors it holds contain. */
    static void record(String sector){
        StringBuilder out = new StringBuilder("# what an honest run had after taking " + sector + ": research it paid for in full, and what its cores held\n");
        out.append("researched ").append(researched.toString(",")).append('\n');
        for(Holdings.Held one : bank.held){
            StringBuilder items = new StringBuilder(), rates = new StringBuilder();
            for(Item item : Vars.content.items()){
                if(one.items[item.id] > 0) items.append(items.length() == 0 ? "" : ",").append(item.name).append('=').append(one.items[item.id]);
                if(one.rate[item.id] > 0f) rates.append(rates.length() == 0 ? "" : ",").append(item.name).append('=').append(one.rate[item.id]);
            }
            out.append("sector ").append(one.name).append(' ').append(one.cap).append(' ').append(items.length() == 0 ? "-" : items).append(' ').append(rates.length() == 0 ? "-" : rates).append('\n');
        }
        freshDir.mkdirs();
        freshDir.child("after-" + sector + ".txt").writeString(out.toString());
    }

    /** Takes up from what a run that took a sector wrote down. The record kept with the tests comes before one from the last run. */
    static boolean restore(String sector){
        Fi file = stateDir.child("after-" + sector + ".txt");
        if(!file.exists()) file = lastDir.child("after-" + sector + ".txt");
        if(!file.exists()) return false;
        for(String row : file.readString().split("\n")){
            if(row.startsWith("researched ")){
                for(String name : row.substring(11).split(",")){
                    UnlockableContent content = null;
                    for(ContentType type : ContentType.all){
                        if(content == null && Vars.content.getByName(type, name.trim()) instanceof UnlockableContent found) content = found;
                    }
                    if(content != null) unlock(content);
                }
            }else if(row.startsWith("bank ")){
                //a record from before sectors were kept apart: what they held, bringing in nothing more
                Holdings.Held old = bank.add("earlier", Integer.MAX_VALUE / 2);
                for(String entry : row.substring(5).split(",")){
                    String[] parts = entry.split("=");
                    Item item = parts.length == 2 ? Vars.content.item(parts[0].trim()) : null;
                    if(item != null) old.items[item.id] += Strings.parseInt(parts[1].trim(), 0);
                }
            }else if(row.startsWith("sector ")){
                String[] parts = row.split(" ");
                Holdings.Held one = bank.add(parts[1], Strings.parseInt(parts[2], 2000));
                for(String entry : parts[3].split(",")){
                    String[] pair = entry.split("=");
                    Item item = pair.length == 2 ? Vars.content.item(pair[0]) : null;
                    if(item != null) one.items[item.id] = Strings.parseInt(pair[1], 0);
                }
                for(String entry : parts[4].split(",")){
                    String[] pair = entry.split("=");
                    Item item = pair.length == 2 ? Vars.content.item(pair[0]) : null;
                    if(item != null) one.rate[item.id] = Strings.parseFloat(pair[1], 0f);
                }
            }
        }
        System.out.println("taking up from " + file.path() + ": researched " + researched.size + ", in its other cores " + bank);
        return true;
    }

    /** For looking at a later sector by itself: everything that could be researched by then, without the paying. */
    static void grant(){
        boolean again = true;
        while(again){
            again = false;
            for(TechNode node : TechTree.all){
                if(node.content.unlocked() || !erekir(node) || !met(node)) continue;
                //what is got hold of only by making it comes with the sector that first lets it be made
                unlock(node.content);
                again = true;
            }
            for(Item item : Vars.content.items()){
                if(!item.unlocked() && Planets.erekir.techTree != null && TechTree.all.contains(n -> n.content == item && erekir(n) && n.parent != null && n.parent.content.unlocked())){
                    unlock(item);
                    again = true;
                }
            }
        }
    }

    private static int tick;

    /**
     * One second of the player's part in research. What its core holds, and the liquids in what it
     * has built, count as got hold of. Then it pays towards what it can research, out of what its
     * cores hold: first what the map asks it to research, what the next sectors ask for and what its
     * own blueprints are built of, the cheapest first; then the rest. It keeps a little back to go on building with.
     */
    static void research(){
        CoreBuild core = me.core();
        if(core == null) return;
        core.items.each((item, amount) -> unlock(item));
        if(free){
            //whatever the tree comes to allow is had at once
            if(tick++ % 5 == 0) grant();
            return;
        }
        if(tick++ % 5 == 0){
            for(Building build : me.data().buildings){
                if(build.liquids != null) build.liquids.each((liquid, amount) -> {
                    if(amount > 0.5f) unlock(liquid);
                });
            }
        }
        unlockFree();

        //what it is after in particular; and of that, what the map or the next sector asks for outright
        ObjectSet<UnlockableContent> wanted = new ObjectSet<>(), pressing = new ObjectSet<>();
        for(MapObjective objective : Vars.state.rules.objectives.all){
            if(objective instanceof ResearchObjective research && !objective.isCompleted()) pressing.add(research.content);
            if(objective instanceof BuildCountObjective build && !objective.isCompleted()) pressing.add(build.block);
            if(objective instanceof UnitCountObjective unit && !objective.isCompleted()) pressing.add(unit.unit);
            //an item it is to get into its core that it can't mine yet: the cheapest drill it has a layout for that can
            if(objective instanceof CoreItemObjective wants && !objective.isCompleted() && objective.dependencyFinished() && core.items.get(wants.item) == 0){
                mindustry.world.Block cheapest = null;
                for(Blueprint blueprint : brain.blueprints){
                    mindustry.world.Block main = blueprint.main();
                    int tier = main instanceof mindustry.world.blocks.production.Drill drill ? drill.tier : main instanceof mindustry.world.blocks.production.BeamDrill beam ? beam.tier : -1;
                    if(tier < wants.item.hardness || main.unlocked()) continue;
                    if(cheapest == null || left(main.techNode) < left(cheapest.techNode)) cheapest = main;
                }
                boolean can = brain.blueprints.contains(b -> b.main().unlocked() && (b.main() instanceof mindustry.world.blocks.production.Drill d ? d.tier : b.main() instanceof mindustry.world.blocks.production.BeamDrill m ? m.tier : -1) >= wants.item.hardness);
                if(cheapest != null && !can && cheapest.techNode != null) pressing.add(cheapest);
            }
        }
        for(String name : line){
            SectorPreset preset = Vars.content.getByName(ContentType.sector, name);
            if(preset.unlocked() || preset.techNode == null) continue;
            for(Objective objective : preset.techNode.objectives) if(objective instanceof Research research) pressing.add(research.content);
            break;
        }
        for(TechNode node : TechTree.all){
            if(!pressing.contains(node.content)) continue;
            for(TechNode up = node.parent; up != null; up = up.parent) pressing.add(up.content);
        }
        wanted.addAll(pressing);
        for(Blueprint blueprint : brain.blueprints){
            for(var part : blueprint.parts){
                wanted.add(part.block);
                if(part.config instanceof UnlockableContent set) wanted.add(set);
            }
        }
        wanted.addAll(Blocks.duct, Blocks.beamNode, Blocks.ductRouter, Blocks.ductBridge, Blocks.underflowDuct, Blocks.reinforcedConduit, Blocks.reinforcedBridgeConduit, Blocks.reinforcedLiquidRouter);
        //and whatever stands above those in the tree
        for(TechNode node : TechTree.all){
            if(!wanted.contains(node.content)) continue;
            for(TechNode up = node.parent; up != null; up = up.parent) wanted.add(up.content);
        }

        Seq<TechNode> open = TechTree.all.select(node -> !node.content.unlocked() && erekir(node) && node.requirements.length > 0 && met(node));
        open.sort(node -> (wanted.contains(node.content) ? 0f : 1e7f) + left(node));
        if(System.getProperty("xp.research") != null && tick % 300 == 0){
            StringBuilder out = new StringBuilder("    research at " + (int)(Vars.state.tick / 3600) + " min: bank " + bank + "; open:");
            for(int i = 0; i < Math.min(open.size, 6); i++) out.append(" ").append(open.get(i).content.name).append(wanted.contains(open.get(i).content) ? "*" : "").append(" left ").append((int)left(open.get(i)));
            out.append("; waiting on:");
            for(TechNode node : TechTree.all){
                if(node.content.unlocked() || !erekir(node) || met(node) || !wanted.contains(node.content) || node.content instanceof SectorPreset) continue;
                if(node.parent != null && node.parent.content.unlocked()) out.append(" ").append(node.content.name).append(" [").append(missing(node)).append("]");
            }
            System.out.println(out);
        }
        //while it can build nothing yet, everything goes to research. after that building comes first: what the map
        //or the next sector asks for outright is paid for down to a little kept back, the rest only out of plenty,
        //and never out of what it is saving up to build something with
        boolean building = Blocks.duct.unlocked() && Blocks.plasmaBore.unlocked() && Blocks.turbineCondenser.unlocked() && Blocks.beamNode.unlocked();
        brain.wanted.clear();
        //what the map or the next sector asks for outright is what a player would put first: the cheapest of those is
        //paid for down to a tenth of what the core holds, the rest down to a quarter. (holding the core's stock back
        //for it altogether was tried: the base then builds nothing, and with nothing built nothing comes in.)
        boolean hard = building && (brain.director.posture == Director.Posture.pressed || brain.director.posture == Director.Posture.bootstrap);
        TechNode first = hard ? null : open.find(node -> pressing.contains(node.content));
        for(TechNode node : open){
            //and while it is fighting for its base, or has lost what it mines with, nothing at all goes out of the core for research
            boolean struggling = hard;
            TechNode saved = first;
            int floor = !building ? 0 : struggling ? Integer.MAX_VALUE / 2 : node == saved ? core.storageCapacity / 10 : pressing.contains(node.content) ? core.storageCapacity / 4 : core.storageCapacity / 2;
            boolean done = true;
            for(int i = 0; i < node.requirements.length; i++){
                ItemStack price = node.requirements[i], paid = node.finishedRequirements[i];
                int owed = price.amount - paid.amount;
                if(owed <= 0) continue;
                //out of the sectors it has left first: those have no other use for it
                int fromBank = Math.min(owed, bank.get(price.item));
                bank.remove(price.item, fromBank);
                int reserve = Math.max(floor, building ? brain.savingFor(price.item) + 60 : 0);
                int fromCore = Math.min(owed - fromBank, Math.max(core.items.get(price.item) - reserve, 0));
                core.items.remove(price.item, fromCore);
                if(price.item.id < paidOut.length) paidOut[price.item.id] += fromCore;
                paid.amount += fromBank + fromCore;
                if(paid.amount < price.amount){
                    done = false;
                    //it has to get hold of this, whether or not anything it builds calls for it
                    if(wanted.contains(node.content) && !brain.wanted.contains(price.item)) brain.wanted.add(price.item);
                }
            }
            if(done){
                unlock(node.content);
                System.out.println("    researched " + node.content.name + " at " + (int)(Vars.state.tick / 3600) + " min");
            }
            //one thing at a time gets what there is, apart from what it is after in particular
            if(!done && !wanted.contains(node.content)) break;
        }
    }

    /** What is still to be paid for a node. */
    static float left(TechNode node){
        float total = 0f;
        for(int i = 0; i < node.requirements.length; i++) total += Math.max(node.requirements[i].amount - node.finishedRequirements[i].amount, 0);
        return total;
    }

    //endregion
}
