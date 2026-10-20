package xtest;

import arc.*;
import arc.files.*;
import arc.struct.*;
import arc.util.*;
import expansion.*;
import expansion.econ.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.entities.*;
import mindustry.io.*;
import mindustry.type.*;
import mindustry.ctype.*;
import mindustry.world.*;
import mindustry.world.blocks.storage.*;

import java.util.*;

/**
 * A whole game, ours against the mod's enemy (the {@link Brain} of the main line): our side is the economy executor
 * (plans laid out and built by the core unit, one stage after another), its units led by the game's squad AI; the
 * other side is the Brain, as it plays the enemy in the campaign.
 *
 * Two ways to start:
 *   sector: the map as the campaign lands on it, the Brain with the base the map gives it, we from our landing core
 *   even:   everything on the map taken off but one core a side, the same block and the same items for both
 *
 * A game ends when one side has no core left, or at the time limit. Each minute it says how both sides stand.
 *
 * Usage: Match data-dir map.msav minutes sector|even
 *   -Dmatch.stages=stell:3,locus:2   what our side builds, stage after stage (unit or item: how many of its makers, or
 *                                    for an item how much a second)
 *   -Dmatch.plain=true               the Brain at its default settings instead of its strongest
 */
public class Match{
    static Team us, them;
    static final ObjectIntMap<String> made = new ObjectIntMap<>(), lostUnits = new ObjectIntMap<>();
    static int blocksLostUs, blocksLostThem;
    static final ObjectFloatMap<String> hurt = new ObjectFloatMap<>();
    static final ObjectIntMap<String> died = new ObjectIntMap<>();
    static final ObjectFloatMap<String> blockHurt = new ObjectFloatMap<>(), coreHurt = new ObjectFloatMap<>();
    /** Where raiders take what we built (-Dmatch.guard=false: no turret put down for it). */
    static expansion.econ.Raids raids;
    static int guards, feeds;
    static final ObjectIntMap<String> blocksLostKind = new ObjectIntMap<>();
    /** Seconds our turrets stood, and of those, with no ammunition. */
    static int turretSeconds, drySeconds;
    /** What the last defence planned could feed turrets with. */
    static final Set<String> feedable = new HashSet<>();
    /** What our core held a minute ago, by item name. */
    static final ObjectIntMap<String> lastMinute = new ObjectIntMap<>();

    public static void main(String[] args){
        //the path finders stepped with the game (else the same start plays out otherwise each time, and units wait on a
        //thread that lags behind); -Dxp.syncpath=false for the game's own threads
        if(System.getProperty("xp.syncpath") == null) System.setProperty("xp.syncpath", "true");
        //and the fog worked out in step with the game too; the Brain's thinking timed by its steps, not the wall (else it
        //plays worse the busier the machine: as xtest.Campaign's batches run it, test/one-run.sh)
        if(System.getProperty("xp.syncfog") == null) System.setProperty("xp.syncfog", "true");
        if(System.getProperty("xp.virtual") == null) System.setProperty("xp.virtual", "true");
        //each run its own arrangement of the hash codes maps of blocks, items and units are gone through in
        //(-Dxp.hashshuffle=n): with one for every run of a build, repeated runs play the same game
        for(int i = Integer.getInteger("xp.hashshuffle", 0); i > 0; i--) System.identityHashCode(new Object());
        ExpansionMod[] mod = {null};
        Boot.boot(args[0], () -> mod[0] = new ExpansionMod());
        mod[0].init();
        Brain brain = ExpansionMod.brain;
        for(Fi file : new Fi("blueprints").list()) if(file.extEquals("txt")) brain.blueprints.addAll(Blueprint.parse(file.readString()));
        //the mod's grace at the start of a map as a player has it (2 minutes by default; -Dmatch.grace=n)
        if(System.getProperty("match.grace") != null) Core.settings.put("expansion-grace", Integer.getInteger("match.grace"));
        if(!Boolean.getBoolean("match.plain")) Core.settings.put("expansion-strongest", true);
        //the Brain as the rule-made baseline: without the opening planned by our planner, unless asked for it
        //(-Dmatch.brainopening=true: the Brain that opens with Operations' plan, the stronger opponent)
        if(System.getProperty("xp.pin.planned-opening") == null) System.setProperty("xp.pin.planned-opening", String.valueOf(Boolean.getBoolean("match.brainopening")));
        Core.settings.put("expansion-time-limit", Integer.getInteger("xp.timelimit", 100));
        Boot.modTime = () -> brain.lastThink;
        //nothing is researched in a fresh data dir: the Brain may build all it knows, as in the other tests
        brain.unlocked = content -> true;

        //gen:seed, a map the game's generator makes for Erekir, one core a side and the same stock (a custom game, as PvP)
        boolean generated = args[1].startsWith("gen:");
        Fi map = generated ? new Fi("gen-" + args[1].substring(4)) : new Fi(args[1]);
        float minutes = Float.parseFloat(args[2]);
        String mode = args.length > 3 ? args[3] : generated ? "gen" : "sector";
        //sandbox: no other side at all; our stages climb the tech from the landing stock, and what got made is told at the end
        boolean sandbox = mode.equals("sandbox");
        //campaign: the sector as the campaign plays it against the game's own enemy (its waves and its base as the map has
        //them, on hard unless asked otherwise), won as the campaign counts it; -Dmatch.us=brain has the Brain play our side
        //there, as the baseline (xtest.Campaign's way: Brain.side)
        boolean campaign = mode.equals("campaign");
        boolean brainSide = campaign && System.getProperty("match.us", "").equals("brain");
        if(campaign){
            //the Brain in our place as xtest.Campaign has it play the campaign: at its strongest, from the first second
            Core.settings.put("expansion-strongest", brainSide);
            if(brainSide) Core.settings.put("expansion-grace", 0);
            if(!brainSide) Core.settings.put("expansion-enabled", false);
        }

        Vars.logic.reset();
        if(generated){
            if(Wild.make(Integer.parseInt(args[1].substring(4)), System.getProperty("match.stock", "plain")) == null){
                System.out.println("RESULT " + map.nameWithoutExtension() + " gen winner=none: the generator left no place for a second core");
                System.exit(0);
            }
            //(the generator's test keeps the player's side standing; not here)
            Vars.state.rules.defaultTeam.rules().blockHealthMultiplier = 1f;
        }else{
            SaveIO.load(map);
        }
        Rules rules = Vars.state.rules;
        //the campaign's difficulty (-Dmatch.difficulty=eradication: the enemy's units and buildings hardier, as a player may set it)
        Planets.erekir.campaignRules.difficulty = Difficulty.valueOf(System.getProperty("match.difficulty", campaign ? "hard" : "normal"));
        Planets.erekir.applyRules(rules, false);
        us = rules.defaultTeam;
        them = rules.waveTeam;
        //the fog off unless asked (-Dmatch.fog=true keeps the map's own, as the campaign plays it: the game's enemy then sees
        //only what its units and buildings see, and a side builds only on ground it has seen. xtest.Campaign, where the
        //Brain took onset, plays with it; here the Brain lost onset to eight stells at 2:15 with the fog off)
        if(!Boolean.getBoolean("match.fog")) rules.fog = false;
        rules.coreIncinerates = true;
        rules.infiniteResources = false;
        SectorPreset preset = Vars.content.getByName(ContentType.sector, map.nameWithoutExtension());
        if(campaign){
            //(as xtest.Campaign lands on a sector: its waves, its win wave, the time between waves for the difficulty)
            if(preset != null && preset.captureWave > 0) rules.winWave = preset.captureWave;
        }else{
            rules.waves = false;
            rules.attackMode = true;
        }
        //a sector of waves has no base of the Brain's to play against
        //every kind of ground in reach of our core (-Dmatch.allground=true): what the map lacks is put down near the core, a
        //patch of each (a fixture for climbing the whole tech; no generated map of 200 has them all)
        if(Boolean.getBoolean("match.allground")) allGround(us.core());
        if(sandbox){
            for(Tile tile : Vars.world.tiles) if(tile.build != null && tile.team() == them) tile.setBlock(Blocks.air);
            for(Unit u : Groups.unit.copy(new Seq<>())) if(u.team == them) u.remove();
            rules.attackMode = false;
            rules.winWave = 0;
        }
        if(!sandbox && !campaign && (them.cores().isEmpty() || us.cores().isEmpty())){
            System.out.println("RESULT " + map.nameWithoutExtension() + " " + mode + " winner=none: " + (them.cores().isEmpty() ? "the Brain has no core on this map (a sector of waves)" : "we have no core"));
            System.exit(0);
        }
        if(mode.equals("even")) even();
        else if(!generated && !sandbox){
            //the rules the campaign lays on the enemy of a sector (the mod keeps its own count of what it has)
            Planet planet = Planets.erekir;
            if(planet.enemyInfiniteItems){
                them.rules().infiniteResources = true;
                them.rules().fillItems = true;
            }
            them.rules().buildSpeedMultiplier *= planet.enemyBuildSpeedMultiplier;
            them.rules().unitFactoryActivationDelay = Math.max(them.rules().unitFactoryActivationDelay, planet.enemyFactoryActivationDelay);
            if(!them.cores().isEmpty() && !campaign){
                var c = them.cores().first();
                for(Item item : Vars.content.items()) c.items.set(item, c.getMaximumAccepted(item));
            }
        }
        CoreBlock.CoreBuild core = us.core();
        if(rules.loadout != null && rules.loadout.size > 0 && (mode.equals("sector") || campaign)){
            core.items.clear();
            for(ItemStack stack : rules.loadout) core.items.set(stack.item, stack.amount);
        }
        //the game gives the side of the waves no unit cap at all (Units.getCap); where the two sides are to start alike (even,
        //gen) ours gets none either. In a sector it keeps the cap a player has there.
        if((mode.equals("even") || generated) && !sandbox && !Boolean.getBoolean("match.cap")) us.ignoreUnitCap = true;
        //the squad AI, which the game runs for its AI sides only: run here for ours by hand
        us.rules().rtsAi = !brainSide;
        if(brainSide) Brain.side = us;
        mindustry.ai.RtsAI squads = new mindustry.ai.RtsAI(us.data());
        //and our army layer over it (-Dmatch.us=rts for the squad AI alone, or army:parts); the Brain is played as the main
        //line has it, without the layer (-Dmatch.brainarmy=true to give it the layer too)
        String ours = System.getProperty("match.us", "army:defaults,push,hold");
        expansion.army.Army army = ours.startsWith("army") ? new expansion.army.Army(us, expansion.army.Army.Flags.parse(ours)) : null;
        if(!Boolean.getBoolean("match.brainarmy")) System.setProperty("xp.noarmy", "true");
        System.out.println("  our units: " + (army == null ? "the squad AI alone" : "the squad AI with the army layer: " + army.flags.toString().trim()));
        Time.setDeltaProvider(() -> 1f);
        Vars.logic.play();
        //(the time between waves, as the campaign's difficulty has it)
        //(and after every wave: the game times the next by the spacing times the multiplier only in a real campaign, which
        //this is not, so the spacing itself is made what it would be there)
        if(campaign){
            Vars.state.wavetime *= Planets.erekir.campaignRules.difficulty.waveTimeMultiplier;
            rules.waveSpacing *= Planets.erekir.campaignRules.difficulty.waveTimeMultiplier;
        }
        Vars.state.tick = 0;
        Boot.frames(1);
        //the whole map open, the way the map's own script opens it up (as the other tests run it, "open"); not in the
        //campaign, where the map's own limit stands and its script opens it as its tasks are done, as xtest.Campaign plays
        //it (opened, onset's enemy base was in reach from the first second: eight stells took the core at 2:16)
        if(rules.limitMapArea && !campaign){
            int x = rules.limitX, y = rules.limitY, w = rules.limitWidth, h = rules.limitHeight;
            rules.limitMapArea = false;
            Vars.world.checkMapArea(x, y, w, h);
        }
        Boot.frames(1);

        raids = new expansion.econ.Raids(us);
        Events.on(UnitCreateEvent.class, e -> {
            if(e.unit.team == us) made.increment("us " + e.unit.type.name);
            if(e.unit.team == them) made.increment("them " + e.unit.type.name);
        });
        //who hurts our buildings, and which: by the kind of attacker, and by the block hit
        Events.on(BuildDamageEvent.class, e -> {
            if(e.build == null || e.build.team != us || e.source == null) return;
            Object owner = e.source.owner;
            String who = owner instanceof Building b ? b.block.name : owner instanceof Unit u ? "unit " + u.type.name : String.valueOf(owner);
            blockHurt.put(who, blockHurt.get(who, 0f) + e.source.damage);
            //(what hits the core, and from how far: for the minute's report)
            if(e.build instanceof CoreBlock.CoreBuild) coreHitRecent += e.source.damage;
            if(e.build instanceof CoreBlock.CoreBuild && owner instanceof Posc at){
                coreHurt.put(who + " from " + (int)(e.build.dst(at.getX(), at.getY()) / Vars.tilesize / 5 * 5) + " tiles", coreHurt.get(who + " from " + (int)(e.build.dst(at.getX(), at.getY()) / Vars.tilesize / 5 * 5) + " tiles", 0f) + e.source.damage);
            }
        });
        //who hurts our units: by the block or unit that fired, damage summed
        Events.on(UnitDamageEvent.class, e -> {
            if(e.unit.team != us || e.bullet == null) return;
            Object owner = e.bullet.owner;
            String who = owner instanceof Building b ? b.block.name + "@" + b.tileX() + "," + b.tileY() : owner instanceof Unit u ? "unit " + u.type.name : String.valueOf(owner);
            hurt.put(who, hurt.get(who, 0f) + e.bullet.damage);
        });
        Events.on(UnitDestroyEvent.class, e -> {
            if(e.unit.team != us || e.unit.spawnedByCore) return;
            var t = e.unit.tileOn();
            String where = t == null ? "off the map" : t.floor().name + (t.floor().isDeep() ? " (deep)" : "") + (t.floor().damageTaken > 0 ? " (hurts)" : "") + (t.floor().status != null && t.floor().status != StatusEffects.none ? " (" + t.floor().status.name + ")" : "");
            died.increment(where);
        });
        Events.on(UnitDestroyEvent.class, e -> lostUnits.increment(e.unit.team == us ? "us" : e.unit.team == them ? "them" : "other"));
        Events.on(BlockDestroyEvent.class, e -> {
            if(e.tile.team() == us && Boolean.getBoolean("match.losses")) System.out.println(String.format("    lost %s at %d,%d at %s", e.tile.block().name, e.tile.x, e.tile.y, Plan.time((float)(Vars.state.tick / 60.0))));
            raids.lost(e.tile);
            if(e.tile.team() == us){
                blocksLostUs++;
                blocksLostKind.increment(e.tile.block().name);
            }
            else if(e.tile.team() == them) blocksLostThem++;
        });

        if(Boolean.getBoolean("match.turrets")) for(Building b : us.data().buildings) if(b.block instanceof mindustry.world.blocks.defense.turrets.Turret t) System.out.println(String.format("  our turret %s at %d,%d range %.0f tiles", b.block.name, b.tileX(), b.tileY(), t.range / Vars.tilesize));
        System.out.println(String.format("  building: ours at %.2fx (the team's multiplier %.2f, the rules' %.2f, the core unit's speed %.2f), the other side's at %.2fx", rules.buildSpeed(us), us.rules().buildSpeedMultiplier, rules.buildSpeedMultiplier, ((CoreBlock)us.core().block).unitType.buildSpeed, rules.buildSpeed(them)));
        System.out.println(String.format("MATCH %s (%s): we have %d core(s), %d blocks; the Brain %d core(s), %d blocks", map.nameWithoutExtension(), mode, us.cores().size, us.data().buildings.size, them.cores().size, them.data().buildings.size));
        System.out.println(landing(us, them));

        //our stages
        List<String> blocks = new ArrayList<>(Plan.erekir());
        Collections.sort(blocks);
        Set<String> allowed = new HashSet<>(blocks);
        //in the campaign, both our side and the Brain in its place have what a player has researched by this sector (the
        //economy bench's list of it); every Erekir block besides that is held back
        //(xtest.Campaign's research, free: everything the game's tech tree allows once the sectors before are taken and this
        //one landed on, and, as the sector is played, whatever more it comes to allow: an item first got hold of)
        if(campaign && preset != null){
            for(ContentType type : ContentType.all) for(mindustry.ctype.Content c : Vars.content.getBy(type)) if(c instanceof UnlockableContent u) u.clearUnlock();
            for(TechTree.TechNode node : TechTree.all) node.reset();
            for(String name : Campaign.line){
                SectorPreset at = Vars.content.getByName(ContentType.sector, name);
                if(at == null) continue;
                Campaign.landed.add(at);
                if(name.equals(preset.name)) break;
                Campaign.taken.add(at);
            }
            Campaign.unlockFree();
            Campaign.grant();
            brain.unlocked = UnlockableContent::unlocked;
            researchedOnly(blocks, allowed);
            System.out.println("  researched for " + map.nameWithoutExtension() + ": " + blocks);
        }
        List<Design> library = Plan.library(System.getProperty("xp.modules", "blueprints/own,blueprints/posts,blueprints/plants,blueprints/modules"));
        String[] stages = brainSide ? new String[0] : System.getProperty("match.stages", "stell:1|merui:1|elude:1|avert:1,stell:2|merui:2|elude:2|avert:1,defend,locus:1|stell:4|merui:4|elude:4|avert:2,defend,locus:2|stell:6|merui:6|elude:6|avert:3").split(",");
        //the command layer (-Dmatch.command=steady|balanced|aggressive): each stage chosen by looking ahead, no list
        expansion.ops.Command command = null;
        if(System.getProperty("match.command") != null && !brainSide){
            command = new expansion.ops.Command(us, them);
            command.posture = expansion.ops.Command.Posture.of(System.getProperty("match.command"));
        }
        //chosen from the game as it goes (expansion.army.Muster): what to make, and how many makers, each stage
        if(Boolean.getBoolean("match.auto")) stages = "auto,defend,auto,defend,auto,auto,auto,auto,auto,auto".split(",");
        //the other side has yards from the start (a base that came with the map) whose units can get to us: a defence first,
        //before the economy, as a player sets the first breaches down before the first fabricator (-Dmatch.early=false: not)
        String early = earlyThreat(us, them);
        if(command == null && early != null && stages.length > 0 && System.getProperty("match.early", "true").equals("true") && !stages[0].startsWith("defend")){
            String[] more = new String[stages.length + 1];
            more[0] = "defend";
            System.arraycopy(stages, 0, more, 1, stages.length);
            stages = more;
            System.out.println("  a defence first: " + early);
        }
        //a stage not all up after this long is left as it is, and the next begun
        float stageLimit = Float.parseFloat(System.getProperty("match.stagemin", "6")) * 3600f;
        //each stage's own time: twice what its plan takes by the model, and never less than the least (a 22-machine thorium
        //plan the model has up in 7 minutes was left at 20 of 22 after six, and nothing after it could be had)
        float limitNow = stageLimit;
        String sector = expansion.defense.Tech.known(map.nameWithoutExtension()) ? map.nameWithoutExtension() : "atlas";
        expansion.defense.GameData.shapes();
        int stage = -1, climbSteps = 0;
        boolean again = false;
        float stageFrom = 0f;
        expansion.econ.Hand hand = null;
        //a guard put down where raiders take what we built: the stage's hand waits meanwhile
        expansion.econ.Hand paused = null;
        int guardFrom = 0;
        boolean guarding = !"false".equals(System.getProperty("match.guard"));
        Map<Long, float[]> lossBook = new HashMap<>();
        //the stages before: what of them goes missing is put back too, where its tile is empty
        List<expansion.econ.Hand> before = new ArrayList<>();
        Unit builder = null;
        int frames = (int)(minutes * 3600);
        String winner = "-";
        float planSeconds = 0f;
        for(int f = 0; f < frames; f++){
            //the next stage, planned from what stands, once the last is all up (or has had its time)
            //(a stage is not left for want of time while what makes its units is not up yet, unless three times over: on lake,
            //raided from the second minute, the ship fabricator never went up and no unit came all game)
            //(a stage is over once its own order is up, whatever it is mending meanwhile: under raids that never ends)
            //(with the command layer: weighed again once the stage is behind its own model, the job it is on begun later than
            //the model has it by half again and a minute; a stage that goes as planned is let finish)
            boolean behind = command != null && stageModel != null && hand != null && f % 60 == 0 && behindModel(hand, stageModel, (f - stageFrom) / 60f);
            boolean over = paused == null && hand != null && (hand.orderDone() || behind || command == null && f - stageFrom > limitNow && (makerUp(hand) || f - stageFrom > limitNow * 3));
            //what the map's tasks ask for, before the next of our own stages (in the campaign, once a stage of ours is up): a
            //stage the same task asked for is not asked again for four minutes
            expansion.econ.Asks.Ask task = null;
            //(with the command layer, what the tasks ask is weighed among its ways, not put first)
            List<String> taskWays = new ArrayList<>();
            if((hand == null || over) && campaign && hand != null && us.core() != null && f % 60 == 0){
                expansion.econ.Asks.Ask a = asks(hand, allowed, f);
                if(command != null){
                    if(a.stage != null){
                        taskWays.add(a.stage);
                        System.out.println("  " + Plan.time(f / 60f) + ": the map's tasks ask: " + a.stage + " (" + a.why.toString("; ") + ")");
                    }
                }else if(a.stage != null && (a.by != lastTaskBy || f - lastTaskAt > 4 * 3600)) task = a;
            }
            //(the command layer weighs again no sooner than a minute after the last time, as a receding horizon re-plans at
            //its own pace: a stage with nothing left to build is over at once, and onset was weighed four thousand times)
            boolean commandWaits = command != null && f - lastDecisionAt < 60 * 60;
            if((hand == null || over) && !commandWaits && (again || task != null || command != null || stage + 1 < stages.length) && us.core() != null){
                if(command != null) lastDecisionAt = f;
                if(hand != null && !hand.orderDone()) System.out.println("  stage " + (stage + 1) + " left at " + hand.next + " of " + hand.order.size() + " after its time: " + hand.doing);
                if(task != null){
                    lastTaskBy = task.by;
                    lastTaskAt = f;
                    System.out.println("  " + Plan.time(f / 60f) + ": a stage the map's tasks ask for, " + task.stage + ": " + task.why.toString("; "));
                }else if(!again) stage++;
                again = false;
                //(the world as it stands when a stage is planned, kept for planning it again by itself: -Dmatch.save=dir, xtest.PlanAt)
                if(System.getProperty("match.save") != null){
                    Fi to = new Fi(System.getProperty("match.save")).child(map.nameWithoutExtension() + "-stage" + (stage + 1) + ".msav");
                    SaveIO.save(to);
                    System.out.println("  saved the world as it stands to " + to);
                }
                stageFrom = f;
                long p0 = System.nanoTime();
                Plan plan = null;
                String[] st = null;
                //each stage may name ways to go, the first that can be done is taken: "locus:1|merui:2"
                //no kind of this stage that can be made gets to the Brain's base: then the first that can be made, to hold our own
                //(on intersect every stage was passed over, nothing was built, and the Brain's few units took the core)
                boolean homeGuard = false;
                //(with the command layer: what it weighs best now; its stages are not a list)
                String spec = command != null ? "command" : stages[stage];
                String[] ways;
                if(task != null) ways = new String[]{task.stage};
                else if(command != null){
                    int fNow = f;
                    expansion.ops.Command.Candidate best = command.decide(blocks, allowed, library, new expansion.ops.Command.Defences(){
                        public expansion.defense.Planner.Plan plan(){
                            return defence(us, them, sector, fNow / 60f, "weighing a defence: ");
                        }

                        public Collection<String> feedable(){
                            return feedable;
                        }
                    }, income(us, f), taskWays);
                    System.out.print(command.log);
                    if(best == null) ways = new String[0];
                    else if(best.modules > 0){
                        ways = new String[]{"defend"};
                        defendModules = best.modules;
                    }else ways = new String[]{best.stage()};
                }else ways = stages[stage].equals("auto") ? autoWays(f, stage, blocks, allowed, library, army) : stages[stage].split("\\|");
                //"climb:conquer": a step at a time towards the goal, each step the nearest of what its maker needs that nothing
                //of ours makes yet (Plan.next), the goal itself last; the stage is taken again till then (at most 10 steps)
                boolean climbing = false;
                if(task == null && spec.startsWith("climb:")){
                    String goal = spec.substring(6);
                    Plan probe = new Plan(System.out);
                    probe.gate = !Boolean.getBoolean("match.allblocks");
                    String aim = null;
                    try{
                        aim = probe.next(us, blocks, allowed, library, goal);
                    }catch(Throwable t){
                        System.out.println("  stage " + (stage + 1) + ": no way up to " + goal + ": " + t);
                    }
                    climbing = aim != null && !aim.equals(goal) && climbSteps < 10;
                    if(climbing) climbSteps++;
                    else climbSteps = 0;
                    System.out.println("  stage " + (stage + 1) + ": climbing to " + goal + ", " + (aim == null ? "no way there here" : climbing ? "next " + aim : "now the goal") + (probe.climbed.isEmpty() ? "" : " (" + probe.climbed + ")"));
                    ways = aim == null ? new String[0] : new String[]{climbing ? aim : goal + ":1"};
                }
                for(int pass = 0; pass < 2 && plan == null; pass++){
                homeGuard = pass == 1;
                if(homeGuard && (ways.length == 0 || ways[0].startsWith("defend"))) break;
                if(homeGuard) System.out.println("  stage " + (stage + 1) + ": nothing that can be made gets to the Brain's base; units to hold our own");
                for(String way : ways){
                    st = way.split(":");
                    //units that can't get to the Brain's base are not worth making (tanks, where deep water parts the bases)
                    mindustry.type.UnitType kind = Vars.content.unit(st[0]);
                    if(kind != null && !homeGuard && !expansion.army.Reach.toFoe(kind, us, them)){
                        System.out.println("  stage " + (stage + 1) + ": " + way + " passed over, " + kind.name + " can't get to the Brain's cores from ours");
                        continue;
                    }
                    Plan p = new Plan(System.out);
                    p.gate = !Boolean.getBoolean("match.allblocks");
                    p.widen = !"false".equals(System.getProperty("match.widen"));
                    //raided lately: what may stand anywhere goes where our turrets reach
                    //(or able to be: the other side has yards that make units)
                    if((raids.raided() || makesUnits(them)) && !"false".equals(System.getProperty("match.cover"))) p.cover = raids.cover();
                    p.label = "stage " + (stage + 1) + " at " + Plan.time(f / 60f) + ": ";
                    try{
                        if(st[0].equals("defend")){
                            lastDefenceAt = f;
                            expansion.defense.Planner.Plan dp = defence(us, them, sector, f / 60f, p.label);
                            //(as many modules as the command layer weighed)
                            if(command != null) p.maxModules = defendModules;
                            //(it pays for itself out of what the core holds and brings in; built of what is near at hand; the one
                            //put first for a threat from the start is its first module only)
                            p.income = income(us, f);
                            p.gateDepth = 2;
                            if(stage == 0 && early != null) p.maxModules = 1;
                            plan = p.defend(us, blocks, allowed, dp, feedable);
                        }else{
                            plan = p.make(us, blocks, allowed, library, st[0], Float.parseFloat(st[1]), income(us, f));
                        }
                    }catch(Throwable t){
                        System.out.println("  stage " + (stage + 1) + " (" + way + ") could not be planned: " + t);
                        t.printStackTrace(System.out);
                        plan = null;
                    }
                    if(plan != null) break;
                    System.out.println("  stage " + (stage + 1) + ": " + way + " can't be done here");
                }
                }
                planSeconds += (System.nanoTime() - p0) / 1e9f;
                //(a step of a climb: the same stage again once this is up)
                again = climbing && plan != null;
                System.out.println(String.format("  stage %d planned in %.1f s (wall)", stage + 1, (System.nanoTime() - p0) / 1e9f));
                if(Boolean.getBoolean("xp.time")) System.out.print(expansion.econ.Took.report());
                if(plan != null && plan.model != null) limitNow = Math.max(stageLimit, Math.min(plan.model.t, 3600f) * 2f * 60f);
                //(the command layer weighs again once a stage falls behind its model by half again and a minute: what is
                //found then, it plans from)
                if(plan != null && plan.model != null) stageModel = plan.model;
                if(plan != null && plan.model != null){
                    if(hand != null) before.add(hand);
                    hand = new expansion.econ.Hand(plan.works, plan.model.order);
                    if(Boolean.getBoolean("match.order")){
                        StringBuilder ob = new StringBuilder("  the order:");
                        for(Machine m : plan.model.order) ob.append(" ").append(m.recipe.name);
                        System.out.println(ob);
                        StringBuilder eb = new StringBuilder("  counted on, standing:");
                        for(Machine m : plan.field.existing) eb.append(" ").append(m.recipe.name);
                        System.out.println(eb);
                    }
                    //its own jobs before putting back what raiders broke, every stage (a stage put off till every raid's damage
                    //is mended never gets its factory up: lake, raided from the second minute on, made no unit in twenty)
                    hand.urgent = true;
                    hand.watch.losses = lossBook;
                    //our units are the squad AI's to lead (a unit told where to wait is left alone by it)
                    hand.rallyUnits = false;
                    Intent.current = plan.works.intent();
                    hand.intent = Intent.current;
                    if(builder != null && !builder.dead()) builder.controller(hand);
                }
            }
            //a guard up, or out of time: the stage's hand takes over again
            if(paused != null && (hand.done() || f - guardFrom > 60 * 240)){
                System.out.println("  " + Plan.time(f / 60f) + ": guard " + (hand.done() ? "up" : "left at " + hand.next + " of " + hand.order.size()) + "; back to stage " + (stage + 1));
                before.add(hand);
                hand = paused;
                paused = null;
                if(builder != null && !builder.dead()) builder.controller(hand);
            }
            if(f % 3600 == 0){
                coreHitLastMinute = coreHitRecent;
                coreHitRecent = 0f;
            }
            //a place being raided that nothing of ours that can hit the raiders reaches: a turret for it (or, where one of ours
            //stands dry in reach, ammunition led to it)
            if(guarding && paused == null && hand != null && f % 300 == 150 && us.core() != null){
                expansion.defense.Guard.Raid raid = raids.hottest(false);
                if(raid != null){
                    long g0 = System.nanoTime();
                    raids.guarding(raid.x, raid.y);
                    Plan gp = new Plan(System.out);
                    gp.gate = !Boolean.getBoolean("match.allblocks");
                    gp.label = "guard at " + Plan.time(f / 60f) + ": ";
                    StringBuilder notes = new StringBuilder();
                    Plan made = null;
                    try{
                        if(raids.covered(raid.x, raid.y, raid.air, true)){
                            //a turret of ours reaches it but stands dry: a line to it is all
                            defenceAsk(sector);
                            made = gp.defend(us, blocks, allowed, null, feedable);
                            if(made != null) feeds++;
                            System.out.println("  " + Plan.time(f / 60f) + ": raided, " + raid + "; a dry turret of ours reaches it: fed" + (made == null ? " (no way)" : ""));
                        }else{
                            expansion.defense.Planner.Ask ask = defenceAsk(sector);
                            expansion.econ.Hand now = hand;
                            CoreBlock.CoreBuild c = us.core();
                            List<expansion.defense.Planner.Plan> ways = expansion.defense.Guard.plans(ask, raid, c.tileX(), c.tileY(), (block, x, y, rot) -> freeFor(now, block, x, y, rot), raids.feed(), 4, notes);
                            if(ways.isEmpty()) System.out.print("  " + Plan.time(f / 60f) + ": raided, " + raid + "; " + notes);
                            //the first way whose supply can be laid (a turret standing dry is so many items for nothing)
                            int stake = raids.atStake(raid.x, raid.y);
                            for(expansion.defense.Planner.Plan dp : ways){
                                //(what its own blocks cost, before anything is laid out: dearer than what it guards, not tried)
                                int least = 0;
                                for(expansion.defense.Placement pl : dp.placements){
                                    Block pb = Vars.content.block(pl.block);
                                    if(pb != null && pl.kind != expansion.defense.Placement.Kind.port) for(ItemStack st : pb.requirements) least += st.amount;
                                }
                                if(least > stake){
                                    System.out.println("  " + Plan.time(f / 60f) + ": raided, " + raid + "; " + dp.notes.get(0) + String.format(" (costs at least %d, guards %d: not worth it)", least, stake));
                                    continue;
                                }
                                Plan tryPlan = new Plan(System.out);
                                tryPlan.gate = gp.gate;
                                tryPlan.label = gp.label;
                                Plan got = tryPlan.defend(us, blocks, allowed, dp, feedable);
                                boolean fed = got != null && got.works != null;
                                if(fed) for(Object[] u : got.works.unfed()) if(expansion.econ.Defence.is((Machine)u[1])) fed = false;
                                //no dearer than what it guards
                                int cost = 0;
                                if(got != null && got.model != null) for(Machine m : got.model.order) for(int v : m.cost) cost += v;
                                boolean worth = cost <= stake;
                                System.out.println("  " + Plan.time(f / 60f) + ": raided, " + raid + "; " + dp.notes.get(0) + (fed ? "" : ": its supply can't be laid")
                                    + String.format(" (costs %d, guards %d%s)", cost, stake, worth ? "" : ": not worth it"));
                                fed &= worth;
                                if(fed){
                                    made = got;
                                    guards++;
                                    break;
                                }
                            }
                        }
                    }catch(Throwable t){
                        System.out.println("  guard could not be planned: " + t);
                        t.printStackTrace(System.out);
                        made = null;
                    }
                    planSeconds += (System.nanoTime() - g0) / 1e9f;
                    if(made != null && made.model != null && !made.model.order.isEmpty()){
                        paused = hand;
                        guardFrom = f;
                        hand = new expansion.econ.Hand(made.works, made.model.order);
                        hand.urgent = true;
                        hand.rallyUnits = false;
                        hand.watch.losses = lossBook;
                        if(builder != null && !builder.dead()) builder.controller(hand);
                    }
                }
            }
            CoreBlock.CoreBuild home = us.core();
            if(home != null && hand != null && (builder == null || builder.dead())){
                builder = ((CoreBlock)home.block).unitType.spawn(us, home.x, home.y);
                builder.controller(hand);
            }
            //what the stages before lost: back to the hand of now, where nothing else stands on its tile
            if(hand != null && f % 120 == 0) for(expansion.econ.Hand old : before){
                old.watch.update(old.next);
                for(expansion.Blueprint.Part p : old.watch.repairs){
                    Tile tile = Vars.world.tile(p.x, p.y);
                    if(tile != null && tile.build == null && !hand.watch.repairs.contains(p, true)) hand.watch.repairs.add(p);
                }
                old.watch.repairs.clear();
            }
            if(campaign && preset != null && f % 60 == 0){
                int had = blocks.size();
                Campaign.research();
                researchedOnly(blocks, allowed);
                if(blocks.size() != had) System.out.println("  " + Plan.time(f / 60f) + ": researched now " + blocks);
            }
            //what the map's tasks ask to have put down or destroyed: blocks to the hand of now, targets to the army
            if(campaign && hand != null && f % 600 == 300 && us.core() != null){
                expansion.econ.Asks.Ask a = asks(hand, allowed, f);
                for(expansion.Blueprint.Part p : a.parts){
                    hand.deferred.add(p);
                    taskGiven.put(p, f);
                }
                if(a.parts.size > 0) System.out.println("  " + Plan.time(f / 60f) + ": put down for the map's tasks: " + a.parts.size + " " + a.parts.first().block.name + " (" + a.why.toString("; ") + ")");
                if(army != null){
                    army.push.asked.clear();
                    army.push.asked.addAll(a.targets);
                }
            }
            long q0 = System.nanoTime();
            Boot.frames(1);
            long q1 = System.nanoTime();
            ControlSync.step();
            long q2 = System.nanoTime();
            if(!brainSide) squads.update();
            long q3 = System.nanoTime();
            if(army != null) army.update();
            long q4 = System.nanoTime();
            prof[0] += q1 - q0; prof[1] += q2 - q1; prof[2] += q3 - q2; prof[3] += q4 - q3;
            if(f % 60 == 0) for(Building b : us.data().buildings) if(b instanceof mindustry.world.blocks.defense.turrets.Turret.TurretBuild tb){
                turretSeconds++;
                if(!tb.hasAmmo()) drySeconds++;
            }
            if(f % 3600 == 0 && f > 0) System.out.println(report(f / 60f, hand, stage));
            //the forecast of the other side's force, against what it turns out to have (-Dmatch.forecast=true)
            if(f % 3600 == 0 && Boolean.getBoolean("match.forecast") && us.core() != null){
                int minute = f / 3600;
                expansion.ops.Forecast fc = new expansion.ops.Forecast(us, them, 300f, 10f);
                expansion.ops.Force now = fc.total(0f);
                StringBuilder fb = new StringBuilder(String.format("    forecast at %d: theirs now %s; foretold", minute, now));
                for(int back : new int[]{1, 3, 5}){
                    Float was = foretold.get(minute * 100 + back);
                    fb.append(String.format(" %d min ago %s", back, was == null ? "-" : String.format("%.3g", was)));
                }
                //what it makes: foretold for the next three minutes, and what it made in the last three
                int madeNow = 0;
                for(var e : made) if(e.key.startsWith("them ")) madeNow += e.value;
                madeAt.put(minute, madeNow);
                Integer made3 = madeAt.get(minute - 3);
                Float said3 = foretold.get(minute * 100 + 93);
                fb.append(String.format("; made in the last 3 min %s (foretold %s)", made3 == null ? "-" : String.valueOf(madeNow - made3), said3 == null ? "-" : String.valueOf(Math.round(said3))));
                foretold.put((minute + 3) * 100 + 93, (float)(fc.total(180f).count - now.count));
                fb.append("; at our core in 1/3/5 min:");
                for(int ahead : new int[]{1, 3, 5}){
                    foretold.put((minute + ahead) * 100 + ahead, fc.total(ahead * 60f).strength());
                    fb.append(' ').append(fc.atCore(ahead * 60f));
                }
                System.out.println(fb.append("; ").append(fc.notes));
            }
            if(f % 3600 == 0 && f > 0 && coreHurt.size > 0){
                StringBuilder ch = new StringBuilder("    our core hit by:");
                for(var e : coreHurt) ch.append(' ').append(e.key).append(' ').append((int)e.value).append(';');
                System.out.println(ch);
                coreHurt.clear();
            }
            if(f % 3600 == 0 && f > 0 && army != null){
                int commanded = 0, fighters = 0;
                for(Unit u : us.data().units){
                    if(u.spawnedByCore || !(u.controller() instanceof mindustry.ai.types.CommandAI ai)) continue;
                    fighters++;
                    if(ai.hasCommand() || ai.attackTarget != null) commanded++;
                }
                System.out.println(String.format("    army: go %b (strength %.2f of theirs), cap %d, %d fighting units, %d with orders; %s%s", army.allot.go, army.allot.lastRatio, Units.getCap(us), fighters, commanded,
                    army.push.report(), army.push.target == null ? "" : " (now " + army.push.target.block.name + " at " + army.push.target.tileX() + "," + army.push.target.tileY() + ")"));
                //where ours stand against home, and the other side's that are near it
                if(us.core() != null){
                    var core0 = us.core();
                    int atHome = 0, out = 0, foesNear = 0;
                    for(Unit u : us.data().units) if(!u.spawnedByCore && u.type.canAttack){ if(u.within(core0, 15 * Vars.tilesize)) atHome++; else out++; }
                    for(Unit e : them.data().units) if(!e.spawnedByCore && e.type.canAttack && e.within(core0, 35 * Vars.tilesize)) foesNear++;
                    System.out.println(String.format("    at home: %d of ours within 15 tiles of the core, %d out; %d of theirs within 35; %s", atHome, out, foesNear, army.flags.tactics ? army.tactics.report() : army.hold.report()));
                }
            }
            if(f % (3600 * 3) == 0 && f > 0 && hand != null && Boolean.getBoolean("match.watch")) System.out.print(hand.watch.report(hand.works.cat));
            if(f % 3600 == 0 && us.core() != null) for(Item item : Vars.content.items()) lastMinute.put(item.name, us.core().items.get(item));
            if(f % 3600 == 0 && f > 0 && us == Vars.state.rules.defaultTeam){
                receivedRate.clear();
                for(Item item : Vars.content.items()){
                    int n = Vars.state.stats.coreItemCount.get(item, 0), was = receivedBefore.get(item.name, 0);
                    if(n > was) receivedRate.put(item.name, (n - was) / 60f);
                    receivedBefore.put(item.name, n);
                }
            }
            if(f == 600 || (f % 3600 == 0 && Boolean.getBoolean("match.brainlog"))) System.out.println("  the Brain: active " + brain.active() + "; " + brain.status().replace("\n", "; "));
            if(us.cores().isEmpty()){
                winner = campaign ? "enemy" : "Brain";
                break;
            }
            if(!sandbox && !campaign && them.cores().isEmpty()){
                winner = "us";
                break;
            }
            //the sector taken as the campaign counts it: its cores destroyed where it is to be attacked, its waves held to the
            //win wave and the last of them beaten elsewhere (and then attacked, where the sector says so)
            if(campaign){
                if(rules.attackMode && them.cores().isEmpty()){
                    winner = "us";
                    break;
                }
                if(!rules.attackMode && rules.winWave > 0 && Vars.state.wave >= rules.winWave && Vars.state.enemies == 0){
                    if(preset != null && preset.attackAfterWaves && !them.cores().isEmpty()) rules.attackMode = true;
                    else{
                        winner = "us";
                        break;
                    }
                }
            }
        }
        float t = (float)(Vars.state.tick / 60.0);
        System.out.println(String.format("  wall time: game frames %.1fs (the mod's own %.1fs), unit paths %.1fs, squad AI %.1fs, army layer %.1fs; planning %.1fs", prof[0] / 1e9, Boot.nanosMod / 1e9, prof[1] / 1e9, prof[2] / 1e9, prof[3] / 1e9, planSeconds));
        System.out.println(report(t, hand, stage));
        if(sandbox){
            StringBuilder made = new StringBuilder("  made, by kind:");
            for(var e : Match.made) if(e.key.startsWith("us ")) made.append(' ').append(e.key.substring(3)).append('=').append(e.value);
            System.out.println(made);
            ObjectIntMap<String> turrets = new ObjectIntMap<>(), ready = new ObjectIntMap<>();
            for(Building b : us.data().buildings) if(b instanceof mindustry.world.blocks.defense.turrets.Turret.TurretBuild tb){
                turrets.increment(b.block.name);
                if(tb.hasAmmo() && tb.efficiency > 0f) ready.increment(b.block.name);
            }
            StringBuilder tb = new StringBuilder("  turrets standing (ready to fire):");
            for(var e : turrets) tb.append(' ').append(e.key).append('=').append(e.value).append(" (").append(ready.get(e.key, 0)).append(')');
            System.out.println(tb);
            StringBuilder held = new StringBuilder("  the core holds:");
            if(us.core() != null) for(Item item : Vars.content.items()) if(us.core().items.get(item) > 0) held.append(' ').append(item.name).append('=').append(us.core().items.get(item));
            System.out.println(held);
            //each kind of drill and crafter: how many, how hard they work, how many sit full (their way out is shut)
            Map<String, float[]> kinds = new TreeMap<>();
            for(Building b : us.data().buildings){
                String item = b instanceof mindustry.world.blocks.production.Drill.DrillBuild d ? d.dominantItem == null ? "" : " " + d.dominantItem.name
                    : b instanceof mindustry.world.blocks.production.BeamDrill.BeamDrillBuild bd ? bd.lastItem == null ? "" : " " + bd.lastItem.name : null;
                if(item == null && !(b instanceof mindustry.world.blocks.production.GenericCrafter.GenericCrafterBuild)) continue;
                float[] k = kinds.computeIfAbsent(b.block.name + (item == null ? "" : item), x -> new float[3]);
                k[0]++;
                k[1] += b.efficiency;
                if(b.items != null && b.items.total() >= b.block.itemCapacity) k[2]++;
            }
            StringBuilder ks = new StringBuilder("  makers:");
            for(var e : kinds.entrySet()) ks.append(String.format(" %s x%.0f (working %.2f, full %.0f);", e.getKey(), e.getValue()[0], e.getValue()[1] / e.getValue()[0], e.getValue()[2]));
            System.out.println(ks);
            //what each unit maker and each turret that can't fire is short of
            for(Building b : us.data().buildings){
                boolean maker = b.block instanceof mindustry.world.blocks.units.UnitFactory || b.block instanceof mindustry.world.blocks.units.Reconstructor
                    || b.block instanceof mindustry.world.blocks.units.UnitAssembler;
                boolean dry = b instanceof mindustry.world.blocks.defense.turrets.Turret.TurretBuild tb2 && !(tb2.hasAmmo() && tb2.efficiency > 0f);
                if(!maker && !dry) continue;
                StringBuilder s = new StringBuilder(String.format("  %s at %d,%d: working %.2f, power %.2f;", b.block.name, b.tileX(), b.tileY(), b.efficiency, b.power == null ? 1f : b.power.status));
                if(b.items != null) b.items.each((item, n) -> s.append(' ').append(item.name).append('=').append(n));
                if(b.liquids != null) b.liquids.each((liquid, n) -> s.append(' ').append(liquid.name).append('=').append(String.format("%.1f", n)));
                if(b instanceof mindustry.world.blocks.payloads.PayloadBlock.PayloadBlockBuild<?> pb){
                    s.append(" payload ").append(pb.payload == null ? "none" : pb.payload.content().name);
                    Tile ahead = b.tile.nearby(arc.math.geom.Geometry.d4x(b.rotation) * (b.block.size / 2 + 1), arc.math.geom.Geometry.d4y(b.rotation) * (b.block.size / 2 + 1));
                    s.append(" facing ").append(b.rotation).append(" onto ").append(ahead == null ? "the edge" : ahead.block().name + (ahead.solid() ? " (solid)" : ""));
                }
                if(b instanceof mindustry.world.blocks.units.UnitAssembler.UnitAssemblerBuild ab) s.append(" parts ").append(ab.blocks);
                if(b instanceof mindustry.world.blocks.heat.HeatConsumer hc) s.append(String.format(" heat %.0f", hc.heatRequirement()));
                System.out.println(s);
            }
        }
        if(hand != null) for(String tr : hand.trouble) System.out.println("  could not put down: " + tr);
        if(hand != null && hand.job != null) for(var p : hand.job){
            Tile tile = Vars.world.tile(p.x, p.y);
            if(tile.block() == p.block) continue;
            System.out.println(String.format("  unfinished: %s at %d,%d (there %s%s); valid %b; dark %.0f; in the map area %b; the builder at %.0f tiles", p.block.name, p.x, p.y, tile.block().name,
                tile.build instanceof mindustry.world.blocks.ConstructBlock.ConstructBuild cb ? String.format(", %.0f%% built", cb.progress * 100) : "", Build.validPlace(p.block, us, p.x, p.y, p.rotation), Vars.world.getDarkness(p.x, p.y), Vars.world.isInMapArea(p.x, p.y),
                hand.unit() == null ? -1f : hand.unit().dst(tile.worldx(), tile.worldy()) / Vars.tilesize));
            StringBuilder on = new StringBuilder();
            float ts = Vars.tilesize, size = p.block.size * ts;
            mindustry.entities.Units.nearby(null, p.x * ts + p.block.offset - size / 2f, p.y * ts + p.block.offset - size / 2f, size, size, u -> on.append(' ').append(u.type.name).append('(').append(u.controller().getClass().getSimpleName())
                .append(u.controller() instanceof mindustry.ai.types.CommandAI ai ? ai.targetPos == null ? ", no order" : String.format(", told to %.0f,%.0f", ai.targetPos.x / ts, ai.targetPos.y / ts) : "").append(')'));
            if(on.length() > 0) System.out.println("    standing on it:" + on);
        }
        Seq<String> who = hurt.keys().toSeq().sort(k -> -hurt.get(k, 0f));
        StringBuilder hb = new StringBuilder("  what hurt our units most:");
        for(int i = 0; i < Math.min(8, who.size); i++) hb.append(String.format(" %s %.0f;", who.get(i), hurt.get(who.get(i), 0f)));
        System.out.println(hb);
        System.out.println("  where our units died: " + died);
        Seq<String> bw = blockHurt.keys().toSeq().sort(k -> -blockHurt.get(k, 0f));
        StringBuilder bb = new StringBuilder("  what hurt our buildings most:");
        for(int i = 0; i < Math.min(8, bw.size); i++) bb.append(String.format(" %s %.0f;", bw.get(i), blockHurt.get(bw.get(i), 0f)));
        System.out.println(bb);
        System.out.println("  our blocks lost, by kind: " + blocksLostKind);
        if(hand != null) System.out.println("  repairs held back under fire: " + hand.watch.heldBack + ", parts put back: " + hand.watch.repaired);
        System.out.println("  raids: losses to raiders by kind " + raids.byKind + "; guards put down " + guards + ", dry turrets fed " + feeds);
        if(hand != null && Boolean.getBoolean("match.grids")) for(int i = 0; i < hand.order.size(); i++){
            StringBuilder nodes = new StringBuilder();
            for(var p : hand.works.job(hand.order.get(i))) if(p.block.name.equals("beam-node")) nodes.append(" ").append(p.x).append(",").append(p.y).append(Vars.world.tile(p.x, p.y).block() == p.block ? "" : "(missing)");
            System.out.println("    job #" + (i + 1) + " " + hand.order.get(i).recipe.name + (i < hand.next ? "" : " (not begun)") + ": nodes" + nodes);
        }
        if(hand != null && Boolean.getBoolean("match.grids")) for(Machine m : hand.order){
            var part = hand.works.at.get(m);
            Building b = part == null ? null : Vars.world.build(part.x, part.y);
            if(b == null || b.power == null) continue;
            var g = b.power.graph;
            if(g.getSatisfaction() < 0.5f && g.all.size < 10) for(Building o : g.all){
                StringBuilder ln = new StringBuilder();
                if(o instanceof mindustry.world.blocks.power.BeamNode.BeamNodeBuild bn) for(int i = 0; i < 4; i++) ln.append(" ").append(bn.links[i] == null ? "-" : bn.links[i].block.name + "@" + bn.links[i].tileX() + "," + bn.links[i].tileY());
                System.out.println("      on it: " + o.block.name + " at " + o.tileX() + "," + o.tileY() + ln);
            }
            System.out.println(String.format("    grid of %s at %d,%d: #%d, %d buildings, made %.1f wanted %.1f a second, satisfaction %.2f", m.recipe.name, part.x, part.y, System.identityHashCode(g) % 10000, g.all.size, g.getLastPowerProduced() * 60f, g.getLastPowerNeeded() * 60f, g.getSatisfaction()));
        }
        System.out.println(String.format("RESULT %s %s winner=%s at %s; units made us %d them %d, lost us %d them %d; blocks lost us %d them %d; planning took %.0fs; our turrets dry %d of %d turret-seconds",
            map.nameWithoutExtension(), mode, winner, Plan.time(t), count("us "), count("them "), lostUnits.get("us"), lostUnits.get("them"), blocksLostUs, blocksLostThem, planSeconds, drySeconds, turretSeconds));
        System.exit(0);
    }

    /**
     * What the defence planner would have us build against the Brain: its cores as where the enemy comes from, with the
     * units it fields now and those it can make by this time of the game (from its yards' blueprints: stell, merui and
     * elude, then their refits), our tech of the sector, and what the economy can feed turrets with.
     */
    static expansion.defense.Planner.Plan defence(String sector, float seconds, String label){
        return defence(us, them, sector, seconds, label);
    }

    /** The defence planner's plan for a side against its foe now: what the foe has and what its yards will soon have made. */
    static expansion.defense.Planner.Plan defence(Team me, Team foe, String sector, float seconds, String label){
        expansion.defense.Planner.Ask ask = defenceAsk(me, sector);
        Map<String, Integer> units = new TreeMap<>();
        float minute = seconds / 60f;
        //what the Brain may send: what it has now, half as many again, and at least what its yards could have made by now
        //(the kinds: what its yards make and what it has; before it has any, all three of the first tier)
        int early = Math.max(4, Math.min(10, 3 + (int)(minute / 2))), later = Math.min(6, 2 + (int)(minute / 4));
        Set<String> kinds = new TreeSet<>();
        for(Building b : foe.data().buildings){
            if(b instanceof mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild yard && yard.currentPlan >= 0){
                kinds.add(((mindustry.world.blocks.units.UnitFactory)yard.block).plans.get(yard.currentPlan).unit.name);
            }
            if(b.block instanceof mindustry.world.blocks.units.Reconstructor re) for(var up : re.upgrades) kinds.add(up[1].name);
        }
        for(Unit u : foe.data().units) if(!u.spawnedByCore) kinds.add(u.type.name);
        //(only fighting units of known kinds: the Brain's builder, an evoke not counted as the core's, left the kinds
        //without the first tier's and the defence was planned against nothing: none on basin, stell took the core at 13:00)
        kinds.removeIf(k -> expansion.defense.Foe.get(k) == null);
        if(kinds.isEmpty()) kinds.addAll(List.of("stell", "merui", "elude"));
        for(String k : kinds){
            var kind = expansion.defense.Foe.get(k);
            if(kind != null) units.put(k, kind.tier <= 1 ? early : later);
        }
        ObjectIntMap<String> now = new ObjectIntMap<>();
        for(Unit u : foe.data().units) if(!u.spawnedByCore) now.increment(u.type.name);
        for(var e : now) if(expansion.defense.Foe.get(e.key) != null) units.merge(e.key, (int)Math.ceil(e.value * 1.5f), Math::max);
        //and what its yards will have made by the time this defence is up and the next is planned: each yard's rate over
        //the next few minutes, on top of what it has (eleven yards on one sector made sixty units in four minutes, against a
        //defence planned for ten)
        float ahead = Float.parseFloat(System.getProperty("match.ahead", "3"));
        ObjectFloatMap<String> coming = new ObjectFloatMap<>();
        for(Building b : foe.data().buildings){
            if(b.block instanceof mindustry.world.blocks.units.UnitFactory uf && b instanceof mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild yard){
                int plan = yard.currentPlan >= 0 ? yard.currentPlan : 0;
                if(uf.plans.isEmpty()) continue;
                var up = uf.plans.get(Math.min(plan, uf.plans.size - 1));
                coming.increment(up.unit.name, 0f, ahead * 3600f / Math.max(60f, up.time));
            }else if(b.block instanceof mindustry.world.blocks.units.Reconstructor re && re.upgrades.size > 0){
                //(an upgrade takes a unit of the tier below: it adds one of this tier and takes one of that)
                var up = re.upgrades.first();
                float n = ahead * 3600f / Math.max(60f, re.constructTime);
                coming.increment(up[1].name, 0f, n);
                coming.increment(up[0].name, 0f, -n);
            }
        }
        for(var e : coming){
            if(expansion.defense.Foe.get(e.key) == null || e.value <= 0f) continue;
            int have = now.get(e.key, 0);
            units.merge(e.key, (int)Math.ceil(have + e.value), Math::max);
        }
        ask.units.putAll(units);
        //the map's waves (a sector the game's enemy plays by waves): those of the next few minutes, each from where it comes
        //(the spawns, the map edge for air), sized by the largest of them (on intersect three stells of the first wave took
        //a core with 3400 items in it and not one turret, for the defence looked only at the other side's yards)
        int[] next = wavesAhead(seconds);
        if(next != null){
            DefenseRun.sources(ask, foe, next[0], next[1]);
            for(Map<String, Integer> w : expansion.defense.GameData.waves(next[0], next[1])) for(var e : w.entrySet()) if(expansion.defense.Foe.get(e.getKey()) != null) ask.units.merge(e.getKey(), e.getValue(), Math::max);
            System.out.println(String.format("  waves %d-%d come in the next %.0f minutes: largest of each %s", next[0], next[1], wavesHorizon / 60f, ask.units));
        }
        for(CoreBlock.CoreBuild c : foe.cores()){
            expansion.defense.Planner.Source ground = new expansion.defense.Planner.Source(c.tileX(), c.tileY(), "Brain core " + c.tileX() + "," + c.tileY());
            for(var e : units.entrySet()){
                ground.units.put(e.getKey(), e.getValue());
                ground.moves.add(expansion.defense.Foe.get(e.getKey()).move);
            }
            ask.sources.add(ground);
        }
        if(ask.sources.isEmpty()) return null;
        long t0 = System.nanoTime();
        expansion.defense.Planner.Plan plan = new expansion.defense.Planner(ask).plan();
        System.out.println(String.format("######## %sthe defence planner (tech of %s, fed with %s, budget %s) against %s: %d placements in %.0f ms", label, sector, ask.supply, ask.budget, units, plan.placements.size(), (System.nanoTime() - t0) / 1e6));
        System.out.print(plan.describe());
        return plan;
    }

    /** What the defence planner is asked with for our side now: the ground, our tech that can be paid for, what can be fed, the budget, the modules. */
    static expansion.defense.Planner.Ask defenceAsk(String sector){
        return defenceAsk(us, sector);
    }

    /** The same for any side. */
    static expansion.defense.Planner.Ask defenceAsk(Team me, String sector){
        expansion.defense.Planner.Ask ask = new expansion.defense.Planner.Ask();
        CoreBlock.CoreBuild core = me.core();
        ask.terrain = expansion.defense.GameData.terrain(me);
        ask.coreX = core.tileX();
        ask.coreY = core.tileY();
        ask.coreSize = core.block.size;
        ask.blocks.addAll(expansion.defense.Tech.blocks(sector));
        ask.blocks.removeIf(b -> !b.endsWith("-source") && (Vars.content.block(b) == null || Vars.state.rules.isBanned(Vars.content.block(b))));
        //what turrets can be fed with: what the sector's tech gives that our base makes already, or that a drill on the
        //ground digs with nothing put into it (a plasma bore's beryllium and graphite): a defence that waits on a new
        //chain of crafters is not up when it is wanted
        Catalog cat = Recipes.read(new ArrayList<>(Plan.erekir()), (CoreBlock)core.block, Vars.state.rules.buildSpeed(me));
        Field field = Sites.scan(cat, me, Integer.getInteger("xp.radius", 110));
        Chains chains = new Chains(cat, field);
        Set<String> made = new HashSet<>();
        for(Machine m : field.existing) for(int k = 0; k < cat.size(); k++) if(m.out(k) > 1e-4f) made.add(cat.get(k).name);
        for(int k = 0; k < cat.size(); k++){
            Recipe by = chains.by[k];
            if(by == null || by.ground == Recipe.Ground.none || Float.isInfinite(chains.cost[k])) continue;
            boolean bare = true;
            for(float v : by.in) if(v > 0f) bare = false;
            //(a drill that takes power counts: the defence's plan lays the power too, a turbine on a vent; at the landing nothing
            //at all is made yet, and a defence that only counted what stands could fire nothing)
            if(bare && cat.get(k).kind == Res.Kind.item) made.add(cat.get(k).name);
        }
        for(String what : expansion.defense.Tech.supply(sector)) if(made.contains(what)) ask.supply.add(what);
        feedable.clear();
        feedable.addAll(ask.supply);
        //and only what can be paid for: what the core holds or what is made
        //(of what the core lacks, what is near at hand: held, dug, or made straight from that; the defence's plan brings it
        //in, makers of it for the core built first)
        Map<String, Integer> depth = Plan.depths(cat, field, me, new ArrayList<>(ask.blocks));
        ask.blocks.removeIf(name -> {
            Block b = Vars.content.block(name);
            if(b == null || name.endsWith("-source")) return false;
            return depth.getOrDefault(name, Integer.MAX_VALUE) > 2;
        });
        ask.costs = expansion.defense.GameData.costs();
        //no more than the core holds and what comes in over the next minutes (-Dmatch.defbudget: how many minutes; the
        //income is what the core gained over the last minute, or a guess at the start)
        //(and at least about two of the first line's pairs: the hand saves up for what is not there yet, the budget only
        //keeps the plan to what the next few minutes can pay for)
        float budgetMinutes = Float.parseFloat(System.getProperty("match.defbudget", "3"));
        Map<String, Integer> floor = Map.of("beryllium", 900, "graphite", 600, "silicon", 700, "tungsten", 150);
        for(Item item : Vars.content.items()){
            int have = core.items.get(item);
            int income = Math.max(0, have - lastMinute.get(item.name, have));
            int budget = Math.max(floor.getOrDefault(item.name, 0), have + Math.round(income * budgetMinutes));
            if(budget > 0) ask.budget.put(item.name, budget);
        }
        ask.modules = expansion.defense.Battery.parse(new Fi("blueprints/defense/modules.txt").readString());
        ask.capacity = expansion.defense.Capacity.parse(new Fi("blueprints/defense/capacity.txt").readString());
        return ask;
    }

    /** When a defence was last put first for pressure; how much stronger the other side must be for it (-Dmatch.pressure). */
    static int lastDefenceAt = -100000;
    /** What our cores lost to hits, over the minute now and the last whole one. */
    static float coreHitRecent, coreHitLastMinute;

    /** Whether one of our loaded turrets stands within 12 tiles of our core. */
    static boolean guardedCore(Team me){
        var home = me.core();
        if(home == null) return false;
        for(Building b : me.data().buildings) if(b instanceof mindustry.world.blocks.defense.turrets.Turret.TurretBuild t && t.hasAmmo() && b.within(home, 12 * Vars.tilesize)) return true;
        return false;
    }
    /** When the command layer last weighed. */
    static int lastDecisionAt = -100000;
    /** The model of the stage under way, for telling whether the stage is behind it. */
    static Timeline stageModel;

    /** Whether a stage is behind its model: the job it is on (or saving up for) begun later than the model has it, by half again and a minute. */
    static boolean behindModel(expansion.econ.Hand hand, Timeline model, float seconds){
        int i = Math.min(hand.next, model.begun.length - 1);
        if(i < 0 || hand.orderDone()) return false;
        float planned = model.begun[i];
        if(Float.isNaN(planned)) planned = model.t;
        return seconds > planned * 1.5f + 60f;
    }

    /** How many modules of the defence the command layer chose (Integer.MAX_VALUE: all). */
    static int defendModules = Integer.MAX_VALUE;
    static float pressureEdge = Float.parseFloat(System.getProperty("match.pressure", "1.5"));

    /**
     * The other side's fighting units in Lanchester strength (damage a second times health, all of them together), ours
     * at home (our fighting units, and our loaded turrets within 40 tiles of our core), and how many of theirs.
     */
    static float[] pressure(Team me, Team foe){
        float tDps = 0f, tHp = 0f, n = 0;
        for(Unit u : foe.data().units){
            if(u.spawnedByCore || !u.type.canAttack || u.dead()) continue;
            tDps += u.type.dpsEstimate;
            tHp += u.health;
            n++;
        }
        float oDps = 0f, oHp = 0f;
        for(Unit u : me.data().units){
            if(u.spawnedByCore || !u.type.canAttack || u.dead()) continue;
            oDps += u.type.dpsEstimate;
            oHp += u.health;
        }
        CoreBlock.CoreBuild home = me.core();
        if(home != null) for(Building b : me.data().buildings){
            if(!(b instanceof mindustry.world.blocks.defense.turrets.Turret.TurretBuild t) || !t.hasAmmo() || b.dst(home) > 40 * Vars.tilesize) continue;
            var g = expansion.army.Threat.gun(t, (mindustry.world.blocks.defense.turrets.Turret)b.block);
            oDps += g.dps;
            oHp += g.health;
        }
        return new float[]{tDps * tHp, oDps * oHp, n};
    }

    /** What the forecast said the other side's whole force would be, by minute * 100 + how many minutes ahead it was said. */
    static final Map<Integer, Float> foretold = new HashMap<>();
    static final Map<Integer, Integer> madeAt = new HashMap<>();

    /** The task that last asked for a stage, and when. */
    static Object lastTaskBy;
    static int lastTaskAt;
    /** Blocks put down for the map's tasks, and when: not asked for again for three minutes, while they go up. */
    static final Map<expansion.Blueprint.Part, Integer> taskGiven = new IdentityHashMap<>();

    /** What the map's tasks ask of our side now. */
    static expansion.econ.Asks.Ask asks(expansion.econ.Hand hand, Set<String> allowed, int f){
        taskGiven.values().removeIf(at -> f - at > 3 * 3600);
        Seq<expansion.Blueprint.Part> given = new Seq<>();
        for(var p : taskGiven.keySet()){
            Tile t = Vars.world.tile(p.x, p.y);
            if(t != null && t.block() != p.block) given.add(p);
        }
        return expansion.econ.Asks.now(us, allowed, (b, x, y, r) -> freeFor(hand, b.name, x, y, r), given);
    }

    /** Whether a block may go down here now, on ground the hand's own layout has nothing planned on. */
    static boolean freeFor(expansion.econ.Hand hand, String name, int x, int y, int rotation){
        Block b = Vars.content.block(name);
        if(b == null || !Build.validPlace(b, us, x, y, rotation)) return false;
        int off = -(b.size - 1) / 2;
        for(int dx = 0; dx < b.size; dx++) for(int dy = 0; dy < b.size; dy++){
            if(hand.works.grid != null && hand.works.grid.block(x + off + dx, y + off + dy) != null && Vars.world.tile(x + off + dx, y + off + dy).build == null) return false;
        }
        return true;
    }

    /** Whether a side has anything standing that makes units. */
    static boolean makesUnits(Team team){
        for(Building b : team.data().buildings) if(b.block instanceof mindustry.world.blocks.units.UnitFactory || b.block instanceof mindustry.world.blocks.units.Reconstructor || b.block instanceof mindustry.world.blocks.units.UnitAssembler) return true;
        return false;
    }

    /** What a side starts with, and what the other side has, in words. */
    static String landing(Team me, Team foe){
        StringBuilder st = new StringBuilder("  at the start: our core " + me.core().block.name + " at " + me.core().tileX() + "," + me.core().tileY() + " holds");
        for(Item item : Vars.content.items()) if(me.core().items.get(item) > 0) st.append(" ").append(item.name).append("=").append(me.core().items.get(item));
        ObjectIntMap<String> ourGuns = new ObjectIntMap<>(), theirGuns = new ObjectIntMap<>();
        for(Building b : me.data().buildings) if(b.block instanceof mindustry.world.blocks.defense.turrets.Turret || b.block instanceof mindustry.world.blocks.units.UnitFactory) ourGuns.increment(b.block.name);
        for(Building b : foe.data().buildings) if(b.block instanceof mindustry.world.blocks.defense.turrets.Turret || b.block instanceof mindustry.world.blocks.units.UnitFactory || b.block instanceof mindustry.world.blocks.units.Reconstructor) theirGuns.increment(b.block.name);
        float near = Float.MAX_VALUE;
        for(CoreBlock.CoreBuild c : foe.cores()) near = Math.min(near, c.dst(me.core()) / Vars.tilesize);
        st.append("; our turrets and yards ").append(ourGuns).append("; the other side's ").append(theirGuns).append(String.format("; its nearest core %.0f tiles off", near));
        return st.toString();
    }

    /**
     * Whether a side is under threat from the start: the other side's yards that stand already and make units that can get to
     * it, in words; null if none.
     */
    /** How far ahead the waves a defence is planned against reach, in seconds (-Dmatch.waveahead=minutes). */
    static float wavesHorizon = Float.parseFloat(System.getProperty("match.waveahead", "8")) * 60f;

    /**
     * The first and last of the waves due within the horizon (at least the next three, never past the wave that wins the
     * sector), or null where the map sends none.
     */
    static int[] wavesAhead(float seconds){
        Rules rules = Vars.state.rules;
        if(!rules.waves || rules.spawns.isEmpty() || (!Vars.state.hasSpawns() && !(rules.wavesSpawnAtCores && rules.attackMode))) return null;
        float spacing = Math.max(1f, rules.waveSpacing / 60f);
        float toNext = Math.max(0f, Vars.state.wavetime / 60f);
        int first = Math.max(1, Vars.state.wave);
        int last = first + Math.max(2, (int)((wavesHorizon - toNext) / spacing));
        if(rules.winWave > 0) last = Math.min(last, rules.winWave);
        return last < first ? null : new int[]{first, last};
    }

    static String earlyThreat(Team me, Team foe){
        //waves on the way, with units that can get to us
        int[] next = wavesAhead(0f);
        if(next != null){
            for(Map<String, Integer> w : expansion.defense.GameData.waves(next[0], next[1])){
                for(String name : w.keySet()){
                    mindustry.type.UnitType kind = Vars.content.unit(name);
                    if(kind != null && (kind.flying || Vars.spawner.getSpawns().contains(t -> expansion.army.Reach.can(kind, foe, t.x, t.y, me.core().tileX(), me.core().tileY())))){
                        return String.format("the map sends waves, the first in %.0f s, %s of them by the %d-th", Vars.state.wavetime / 60f, name, next[1]);
                    }
                }
            }
        }
        ObjectIntMap<String> yards = new ObjectIntMap<>();
        for(Building b : foe.data().buildings){
            mindustry.type.UnitType made = null;
            if(b.block instanceof mindustry.world.blocks.units.UnitFactory uf && !uf.plans.isEmpty()) made = uf.plans.first().unit;
            else if(b.block instanceof mindustry.world.blocks.units.Reconstructor re && re.upgrades.size > 0) made = re.upgrades.first()[1];
            if(made == null || !expansion.army.Reach.toFoe(made, foe, me)) continue;
            yards.increment(b.block.name);
        }
        return yards.isEmpty() ? null : "the other side has " + yards + " standing";
    }

    /** What a side's core gained a second over the last whole minute, by item (nothing before the first minute is out). */
    static Map<String, Float> income(Team team, int frame){
        Map<String, Float> out = new HashMap<>();
        if(team.core() == null || frame < 3600) return out;
        //(for the side the game keeps count for, what came into its cores over the last whole minute, whatever was spent
        //meanwhile: the gain alone understates what comes in while the core unit builds)
        if(team == Vars.state.rules.defaultTeam && !receivedRate.isEmpty()) return new HashMap<>(receivedRate);
        for(Item item : Vars.content.items()){
            int have = team.core().items.get(item), before = lastMinute.get(item.name, have);
            if(have > before) out.put(item.name, (have - before) / 60f);
        }
        return out;
    }

    /** What came into our cores a second over the last whole minute (the game's own count), and that count a minute ago. */
    static final Map<String, Float> receivedRate = new HashMap<>();
    static final ObjectIntMap<String> receivedBefore = new ObjectIntMap<>();

    /**
     * The ways for an "auto" stage, best first: the kinds Muster puts forward, each weighed by the model alone (nothing laid
     * out): the one with the most strength out by the horizon (Muster.horizon), its first unit counted in; the rest after it, as the
     * ways to fall back on. What the stronger ones lacked is what the army goes to open up.
     */
    static String[] autoWays(int f, int stage, List<String> blocks, Set<String> allowed, List<Design> library, expansion.army.Army army){
        List<expansion.army.Muster.Aim> aims = expansion.army.Muster.aims(us, them, blocks);
        System.out.println("  stage " + (stage + 1) + ": kinds to weigh " + aims);
        String chosen = null;
        float bestValue = 0f;
        Set<String> lacking = new TreeSet<>();
        //each weighed by what it has out by the horizon: an early kind that is weak, a strong one that comes late, alike
        float strongestHad = 0f;
        for(int i = 0; i < aims.size(); i++){
            var aim = aims.get(i);
            //(weaker than one already found that has something out: not worth weighing)
            if(chosen != null && aim.by(expansion.army.Muster.horizon, 0f) <= bestValue) continue;
            Plan p = new Plan(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
            p.gate = true;
            p.widen = true;
            p.dry = true;
            Plan dry = null;
            try{
                dry = p.make(us, blocks, allowed, library, aim.type.name, aim.makers, null);
            }catch(Throwable t){
                dry = null;
            }
            float first = dry == null ? Float.POSITIVE_INFINITY : dry.model.first[dry.unit];
            float value = Float.isInfinite(first) ? 0f : aim.by(expansion.army.Muster.horizon, first);
            System.out.println(String.format("    %s: %s", aim, Float.isInfinite(first) ? "can't be had" + (p.lacking.isEmpty() ? "" : ", lacking " + p.lacking) : String.format("first at %s, worth %.3g by %s", Plan.time(first), value, Plan.time(expansion.army.Muster.horizon))));
            if(Float.isInfinite(first)){
                //(the step up that lacks least is the one to open up for, if stronger than what can be had)
                if(aim.strength > strongestHad && !p.lacking.isEmpty() && (lacking.isEmpty() || p.lacking.size() < lacking.size())){
                    lacking.clear();
                    lacking.addAll(p.lacking);
                }
                continue;
            }
            strongestHad = Math.max(strongestHad, aim.strength);
            if(value > bestValue){
                bestValue = value;
                chosen = aim.toString();
            }
        }
        if(army != null){
            army.push.want.clear();
            for(String name : lacking) if(Vars.content.item(name) != null) army.push.want.add(Vars.content.item(name));
        }
        List<String> out = new ArrayList<>();
        if(chosen != null) out.add(chosen);
        for(var aim : aims) if(!aim.toString().equals(chosen)) out.add(aim.toString());
        return out.toArray(new String[0]);
    }

    /** Wall time spent each frame (-Dmatch.prof): the game's frame (with the units' controllers), the units' path finder, the squad AI, the army layer. */
    static final long[] prof = new long[4];

    /** Keeps of the Erekir blocks those researched by now (in the campaign), in the planner's list and set. */
    static void researchedOnly(List<String> blocks, Set<String> allowed){
        blocks.clear();
        for(String name : Plan.erekir()){
            Block b = Vars.content.block(name);
            if(b != null && b.unlocked()) blocks.add(name);
        }
        Collections.sort(blocks);
        allowed.clear();
        allowed.addAll(blocks);
    }

    static boolean isTechSector(String name){
        for(String[] row : EconLab.tech) if(row[0].equals(name)) return true;
        return false;
    }

    /** Whether what makes the stage's units is up (true for a stage that makes none). */
    static boolean makerUp(expansion.econ.Hand hand){
        boolean any = false;
        for(int i = 0; i < hand.order.size(); i++){
            if(!hand.order.get(i).recipe.makesUnits()) continue;
            any = true;
            if(!Float.isNaN(hand.finished[i])) return true;
        }
        return !any;
    }

    static int count(String prefix){
        int n = 0;
        for(var e : made) if(e.key.startsWith(prefix)) n += e.value;
        return n;
    }

    /** How the two sides stand. */
    static String report(float t, expansion.econ.Hand hand, int stage){
        StringBuilder b = new StringBuilder();
        b.append(String.format("  %s: ", Plan.time(t)));
        for(Team team : new Team[]{us, them}){
            float hp = 0f, max = 0f;
            for(CoreBlock.CoreBuild c : team.cores()){
                hp += c.health;
                max += c.maxHealth;
            }
            ObjectIntMap<String> units = new ObjectIntMap<>();
            for(Unit u : team.data().units) if(!u.spawnedByCore) units.increment(u.type.name);
            int items = 0;
            if(team.core() != null) for(Item item : Vars.content.items()) items += team.core().items.get(item);
            int turrets = 0;
            for(Building bd : team.data().buildings) if(bd.block instanceof mindustry.world.blocks.defense.turrets.Turret) turrets++;
            b.append(String.format("%s cores %d (%.0f%%) blocks %d turrets %d items %d units %s; ", team == us ? "us" : "Brain", team.cores().size, max > 0 ? hp * 100 / max : 0f, team.data().buildings.size, turrets, items, units));
        }
        if(hand != null) b.append(String.format("our stage %d: %d of %d begun, %s", stage + 1, hand.next, hand.order.size(), hand.doing));
        //the map's own tasks: how many done, and the one it waits on
        var all = Vars.state.rules.objectives.all;
        if(all.size > 0) b.append(String.format("; tasks %d/%d%s", all.count(o -> o.isCompleted()), all.size, Campaign.task(Vars.state.rules)));
        return b.toString();
    }

    /** Puts a patch of each kind of ground Erekir's tech needs that is not in reach of the core, on open ground near it. */
    static void allGround(CoreBlock.CoreBuild core){
        int cx = core.tileX(), cy = core.tileY();
        ObjectIntMap<String> found = ResourceScan.census(cx, cy, 110);
        Object[][] kinds = {
            {"floor:thorium", Blocks.oreThorium, false},
            {"floor:tungsten", Vars.content.block("ore-tungsten"), false},
            {"liquid:slag", Blocks.slag, true},
            {"liquid:arkycite", Blocks.arkyciteFloor, true},
        };
        int placed = 0;
        for(Object[] k : kinds){
            if(found.get((String)k[0], 0) >= 30 || k[1] == null) continue;
            Tile at = openSquare(cx, cy, 9, placed);
            if(at == null){
                System.out.println("  no room near the core for " + k[0]);
                continue;
            }
            for(int dx = 0; dx < 9; dx++) for(int dy = 0; dy < 9; dy++){
                Tile t = Vars.world.tile(at.x + dx, at.y + dy);
                if((Boolean)k[2]) t.setFloor(((Block)k[1]).asFloor());
                else t.setOverlay((Block)k[1]);
            }
            placed++;
            System.out.println("  put down " + k[0] + " at " + at.x + "," + at.y + " (the map had " + found.get((String)k[0], 0) + " tiles of it in reach)");
        }
        //thorium in rock: thorium on the floor is dug only by the eruption drill, which costs thorium to build; the way in is
        //a large plasma bore on thorium in a wall. A strip of it on the rock face nearest the core that open ground meets
        if(found.get("wall:thorium", 0) < 12) wallOre(cx, cy, Blocks.wallOreThorium, 24);
    }

    /** Ore put into the rock face nearest (cx, cy), from 15 tiles out: up to n tiles of rock that open ground touches. */
    static void wallOre(int cx, int cy, Block ore, int n){
        Tile start = null;
        float best = Float.MAX_VALUE;
        for(int dx = -50; dx <= 50; dx++) for(int dy = -50; dy <= 50; dy++){
            float d = (float)Math.hypot(dx, dy);
            if(d < 15 || d > 50 || d >= best) continue;
            Tile t = Vars.world.tile(cx + dx, cy + dy);
            if(face(t)){
                best = d;
                start = t;
            }
        }
        if(start == null){
            System.out.println("  no rock face near the core for " + ore.name);
            return;
        }
        //along the face from there
        IntSeq queue = new IntSeq();
        IntSet seen = new IntSet();
        queue.add(start.pos());
        seen.add(start.pos());
        int put = 0;
        for(int i = 0; i < queue.size && put < n; i++){
            Tile t = Vars.world.tile(queue.get(i));
            t.setOverlay(ore);
            put++;
            for(int[] d : new int[][]{{1, 0}, {0, 1}, {-1, 0}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}}){
                Tile o = Vars.world.tile(t.x + d[0], t.y + d[1]);
                if(o != null && seen.add(o.pos()) && face(o)) queue.add(o.pos());
            }
        }
        System.out.println("  put " + ore.name + " into " + put + " tiles of rock from " + start.x + "," + start.y);
    }

    /** A tile of plain rock beside open ground that can be built on. */
    static boolean face(Tile t){
        if(t == null || !t.block().isStatic() || !t.solid() || t.overlay() != Blocks.air || t.block().itemDrop != null) return false;
        for(int[] d : new int[][]{{1, 0}, {0, 1}, {-1, 0}, {0, -1}}){
            Tile o = Vars.world.tile(t.x + d[0], t.y + d[1]);
            if(o != null && !o.solid() && o.build == null && !o.floor().isLiquid && o.floor().placeableOn) return true;
        }
        return false;
    }

    /** The corner of a square of open ground of this size some way out from (cx, cy), the n-th such going round. */
    static Tile openSquare(int cx, int cy, int size, int n){
        int seen = 0;
        for(int r = 18; r <= 45; r += 3){
            for(int a = 0; a < 360; a += 20){
                int x = cx + (int)(Math.cos(Math.toRadians(a + n * 97)) * r), y = cy + (int)(Math.sin(Math.toRadians(a + n * 97)) * r);
                boolean ok = true;
                for(int dx = -1; dx <= size && ok; dx++) for(int dy = -1; dy <= size && ok; dy++){
                    Tile t = Vars.world.tile(x + dx, y + dy);
                    if(t == null || t.solid() || t.build != null || t.floor().isDeep() || t.floor().isLiquid || t.overlay() != Blocks.air) ok = false;
                }
                if(ok) return Vars.world.tile(x, y);
            }
        }
        return null;
    }

    /** Everything taken off but one core a side, the same core and the same items for both. */
    static void even(){
        CoreBlock.CoreBuild ours = us.core();
        CoreBlock.CoreBuild theirs = null;
        //the farthest of the Brain's cores that ground units can walk to from ours, else one hovering units get to, else any
        //(some maps have cores behind walls that only open later, or across what nothing but aircraft crosses)
        for(mindustry.type.UnitType kind : new mindustry.type.UnitType[]{UnitTypes.stell, UnitTypes.elude, null}){
            for(CoreBlock.CoreBuild c : them.cores()){
                if(kind != null && !expansion.army.Reach.can(kind, us, ours.tileX(), ours.tileY(), c.tileX(), c.tileY())) continue;
                if(theirs == null || c.dst(ours) > theirs.dst(ours)) theirs = c;
            }
            if(theirs != null) break;
        }
        int ox = ours.tileX(), oy = ours.tileY(), tx = theirs.tileX(), ty = theirs.tileY();
        Block coreBlock = ours.block;
        //(every tile with a building of either side's cleared: taken off one by one, some stood, and the Brain, which opens as
        //planned only from a bare core, did not)
        for(Tile tile : Vars.world.tiles) if(tile.build != null && (tile.team() == us || tile.team() == them)) tile.setBlock(Blocks.air);
        Vars.world.tile(ox, oy).setNet(coreBlock, us, 0);
        Vars.world.tile(tx, ty).setNet(coreBlock, them, 0);
        for(Unit u : Groups.unit.copy(new Seq<>())) if(u.team == us || u.team == them) u.remove();
        Seq<ItemStack> loadout = Vars.state.rules.loadout;
        for(Team team : new Team[]{us, them}){
            CoreBlock.CoreBuild c = team.core();
            c.items.clear();
            if(loadout != null) for(ItemStack stack : loadout) c.items.set(stack.item, stack.amount);
        }
    }
}
