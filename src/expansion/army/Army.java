package expansion.army;

import arc.util.*;
import mindustry.ai.types.*;
import mindustry.game.*;
import mindustry.gen.*;

/**
 * The army module (控兵模块, docs/PLAN.md §22; what was built and measured from §22.6 on): a layer over the game's own
 * squad AI for one team. By default every unit is the squad AI's. The layers below take some of them over for a while
 * by giving them orders (the squad AI passes over any unit that has one), give them back by letting the order end, and
 * set the squad AI's own knobs.
 *
 * - A0 {@link Threat}: the other side's turrets, how hard they hit each kind of unit where, and what feeds them.
 * - A1 {@link Allot}: when to wait and when to go, by Lanchester's square law; sets the squad AI's knobs.
 * - A2 {@link Ops}: quick units taken for raids on what the turrets do not cover, and for cutting turrets' supply.
 * - A2b {@link Bait}: the core unit puts walls down at the edge of the other side's turrets' reach for them to waste
 *   their ammunition on (bait), or flies round a turret's reach with a wall in its hold, which lets it be shot at, and
 *   steps out of the way of every shot (decoy).
 * - A3 {@link Dodger}: quick units step out of the way of shots in flight.
 * - A4 {@link Review}: what jobs expected to take set against what they took, as a correction to A0.
 *
 * Each part is switched by a flag, so that what each does can be measured by itself (xtest.Duel).
 * The same object leads the side the mod plays whichever side that is: against a person, or the other AI.
 */
public class Army{
    /** Which parts are on. */
    public static class Flags{
        public boolean allot, harass, cut, bait, decoy, creep, micro, review, push, hold, tactics;

        /**
         * The parts on by default: those that, together, won more games against the squad AI alone in the duels
         * (docs/PLAN.md §22.8). Raids, supply cuts and the turret creep are there to switch on, but did not win more.
         */
        public static Flags defaults(){
            Flags flags = new Flags();
            flags.allot = true;
            flags.micro = true;
            flags.bait = true;
            flags.decoy = true;
            return flags;
        }

        /**
         * From text: "army" for the defaults, or "army:" and a list of parts ("allot,harass,cut,bait,micro,review",
         * "all", "none"); a part with a minus in front is taken off what comes before it ("army:all,-cut").
         */
        public static Flags parse(String text){
            int colon = text.indexOf(':');
            if(colon < 0) return defaults();
            Flags flags = new Flags();
            for(String part : text.substring(colon + 1).split(",")){
                boolean on = !part.startsWith("-");
                String name = on ? part : part.substring(1);
                switch(name){
                    case "all" -> {
                        flags.allot = flags.harass = flags.cut = flags.bait = flags.decoy = flags.creep = flags.micro = flags.review = flags.push = flags.hold = on;
                    }
                    case "defaults" -> {
                        Flags d = defaults();
                        flags.allot |= d.allot; flags.harass |= d.harass; flags.cut |= d.cut; flags.bait |= d.bait; flags.decoy |= d.decoy; flags.creep |= d.creep; flags.micro |= d.micro; flags.review |= d.review; flags.push |= d.push; flags.hold |= d.hold;
                    }
                    case "allot" -> flags.allot = on;
                    case "harass" -> flags.harass = on;
                    case "cut" -> flags.cut = on;
                    case "bait" -> flags.bait = on;
                    case "decoy" -> flags.decoy = on;
                    case "creep" -> flags.creep = on;
                    case "micro" -> flags.micro = on;
                    case "review" -> flags.review = on;
                    case "push" -> flags.push = on;
                    case "hold" -> flags.hold = on;
                    case "tactics" -> flags.tactics = on;
                    case "none", "" -> {}
                    default -> Log.warn("[EnemyExpansion] army: no part called @", name);
                }
            }
            return flags;
        }

        @Override
        public String toString(){
            return (allot ? "allot " : "") + (harass ? "harass " : "") + (cut ? "cut " : "") + (bait ? "bait " : "") + (decoy ? "decoy " : "") + (creep ? "creep " : "") + (micro ? "micro " : "") + (review ? "review " : "") + (push ? "push " : "") + (hold ? "hold " : "") + (tactics ? "tactics" : "");
        }
    }

    /** The one leading the side the mod plays in the game being played, if the setting is on. */
    static @Nullable Army current;

    /**
     * Has the army layer run in the game itself: every frame, for the side the mod plays when that side's units are led by
     * the game's squad AI (the campaign's enemy is), with the parts that are on by default. Off with the setting, or when
     * the mod plays the player's side (its own marshal leads that army).
     */
    public static void install(){
        arc.Events.on(mindustry.game.EventType.ResetEvent.class, e -> current = null);
        arc.Events.on(mindustry.game.EventType.WorldLoadEvent.class, e -> current = null);
        arc.Events.run(mindustry.game.EventType.Trigger.update, Army::tick);
    }

    static void tick(){
        if(mindustry.Vars.state.isPaused()) return;
        //(-Dxp.noarmy=true: off for a test run, whatever the setting says)
        boolean on = expansion.Options.army.on() && !Boolean.getBoolean("xp.noarmy") && expansion.ExpansionMod.brain.active() && expansion.Brain.side == null;
        Team team = expansion.Brain.team();
        if(!on || !team.rules().rtsAi){
            if(current != null){
                current.release();
                current = null;
            }
            return;
        }
        if(current == null || current.team != team){
            current = new Army(team, Flags.defaults());
            Log.info("[EnemyExpansion] The army layer leads @'s units over the game's squad AI: @.", team.name, current.flags.toString().trim());
        }
        current.update();
    }

    public final Team team;
    public final Flags flags;
    public final Threat threat;
    public final Allot allot;
    public final Ops ops;
    public final Bait bait;
    public final Creep creep;
    public final Push push;
    public final Hold hold;
    /** The squads' scripts searched for (Portfolio Greedy Search): in the place of the allotment, the push and the hold. */
    public final Tactics tactics;
    public final Review review = new Review();
    /** Ticks since it began. */
    public float clock;
    /** The squad AI's knobs as they were when it began, which A1 goes back to when it lets the squads go. */
    public final int baseMinSquad;
    public final float baseMinWeight;
    int frame;

    public Army(Team team, Flags flags){
        this.team = team;
        this.flags = flags;
        threat = new Threat(team);
        allot = new Allot(this);
        ops = new Ops(this);
        bait = new Bait(this);
        creep = new Creep(this);
        push = new Push(this);
        hold = new Hold(this);
        tactics = new Tactics(this);
        //the one army of a team that hears what is lost
        live.removeAll(a -> a.team == team);
        live.add(this);
        listen();
        baseMinSquad = team.rules().rtsMinSquad;
        baseMinWeight = team.rules().rtsMinWeight;
        //(the search leads every unit: the squad AI is left to stand by. Kept from forming squads, it still sent units to every
        //damaged block, and took those that had got to where the search had them stand)
        if(flags.tactics) team.data().rtsAi = new StandBy(team.data());
    }

    /** The squad AI that does nothing (the team's rule stays on, so its units take orders as a player's do). */
    static final class StandBy extends mindustry.ai.RtsAI{
        StandBy(mindustry.game.Teams.TeamData data){
            super(data);
        }

        @Override
        public void update(){
        }
    }

    /** Once a frame. */
    public void update(){
        clock += Time.delta;
        frame++;
        if(frame % 30 == 1) threat.update();
        if(frame % 300 == 2 && flags.cut) threat.findCuts(ourDps());
        if(flags.micro) dodgers();
        if(flags.tactics){
            //(the search leads them all: the squad AI's own squads are kept from going anywhere it did not send them)
            if(frame % 120 == 3){
                team.rules().rtsMinSquad = 1000;
                tactics.update();
            }
            if(frame % 60 == 3 && (flags.harass || flags.cut)) ops.update();
        }else if(frame % 60 == 3){
            if(flags.allot) allot.update();
            if(flags.harass || flags.cut) ops.update();
            if(flags.push) push.update(!flags.allot || allot.go);
            if(flags.hold) hold.update(flags.push ? push : null);
        }
        if(flags.creep && frame % 60 == 5) creep.update();
        if(frame % 300 == 7) upgrades();
        if(flags.bait || flags.decoy || flags.creep){
            if(frame % 30 == 4) bait.plan();
            bait.track();
        }
    }

    /** Its quick units moved over to the controller that dodges, once each; the squad AI leads them as before. */
    void dodgers(){
        for(int i = 0; i < team.data().units.size; i++){
            Unit unit = team.data().units.get(i);
            if(unit.controller() instanceof CommandAI ai && !(ai instanceof Dodger) && !unit.isPlayer() && Dodger.agile(unit) && !Bait.coreUnit(unit)){
                Dodger dodger = new Dodger(this);
                unit.controller(dodger);
                //what it was told to do goes over with it
                dodger.command(ai.command == null ? unit.type.defaultCommand : ai.command);
                if(ai.attackTarget != null) dodger.commandTarget(ai.attackTarget, false);
                else if(ai.targetPos != null) dodger.commandPosition(ai.targetPos);
            }
        }
    }

    /**
     * Units with no orders walk into a refabricator or an assembler of ours that waits for one, as a player sends them:
     * one that doesn't stand in front of the fabricator making them waits for good otherwise, and so does one whose
     * fabricator stopped at the unit cap (in a game with no fighting, fifteen tanks stood and no tank of the next tier ever
     * came). One unit a building at a time. A building that waits counts as ready once it has its power and its items
     * (with nothing inside it does no work, so its plain efficiency reads 0).
     */
    void upgrades(){
        for(int i = 0; i < team.data().buildings.size; i++){
            if(!(team.data().buildings.get(i) instanceof mindustry.world.blocks.units.UnitAssembler.UnitAssemblerBuild asm) || asm.potentialEfficiency <= 0f) continue;
            var plan = asm.plan();
            if(plan == null || plan.requirements == null) continue;
            for(mindustry.type.PayloadStack st : plan.requirements){
                if(!(st.item instanceof mindustry.type.UnitType kind) || asm.blocks.get(kind) >= st.amount) continue;
                send(kind, asm);
                break;
            }
        }
        for(int i = 0; i < team.data().buildings.size; i++){
            if(!(team.data().buildings.get(i) instanceof mindustry.world.blocks.units.Reconstructor.ReconstructorBuild rc) || rc.payload != null || rc.potentialEfficiency <= 0f) continue;
            mindustry.world.blocks.units.Reconstructor block = (mindustry.world.blocks.units.Reconstructor)rc.block;
            for(mindustry.type.UnitType[] up : block.upgrades){
                if(!mindustry.entities.Units.canCreate(team, up[1])) continue;
                if(send(up[0], rc)) break;
            }
        }
    }

    /** The nearest unit of this kind with no orders sent into the building as a payload, unless one is on its way there already. */
    boolean send(mindustry.type.UnitType kind, mindustry.gen.Building into){
        Unit best = null;
        float bestD = Float.MAX_VALUE;
        for(int u = 0; u < team.data().units.size; u++){
            Unit unit = team.data().units.get(u);
            if(unit.type != kind || !(unit.controller() instanceof CommandAI ai) || unit.isPlayer() || !unit.type.allowedInPayloads) continue;
            if(ai.command == mindustry.ai.UnitCommand.enterPayloadCommand && ai.targetPos != null && ai.targetPos.within(into.x, into.y, 1f)) return false;
            if(ai.hasCommand() || ai.attackTarget != null) continue;
            float d = unit.dst(into);
            if(d < bestD){
                bestD = d;
                best = unit;
            }
        }
        if(best == null) return false;
        CommandAI ai = (CommandAI)best.controller();
        ai.command(mindustry.ai.UnitCommand.enterPayloadCommand);
        ai.commandPosition(new arc.math.geom.Vec2(into.x, into.y));
        return true;
    }

    float ourDps(){
        float sum = 0f;
        for(int i = 0; i < team.data().units.size; i++) sum += team.data().units.get(i).type.dpsEstimate;
        return Math.max(sum / 4f, 50f);
    }

    static final arc.struct.Seq<Army> live = new arc.struct.Seq<>();
    static boolean listening;

    /** What is lost on the map, told to the army of the team that lost it and to the others. */
    static void listen(){
        if(listening) return;
        listening = true;
        arc.Events.on(mindustry.game.EventType.UnitDestroyEvent.class, e -> {
            for(Army army : live) if(army.team == e.unit.team) army.creep.lost(e.unit);
        });
        arc.Events.on(mindustry.game.EventType.BlockDestroyEvent.class, e -> {
            if(e.tile.build == null) return;
            for(Army army : live) if(army.team != e.tile.team()) army.creep.enemyLost(e.tile.build);
        });
    }

    /** Hands everything back to the game: its units' orders as they are, the squad AI's knobs as they were. */
    public void release(){
        team.rules().rtsMinSquad = baseMinSquad;
        team.rules().rtsMinWeight = baseMinWeight;
        bait.release();
        live.remove(this);
    }

    /** What it is doing now, for looking into it. */
    public String debug(){
        StringBuilder out = new StringBuilder();
        if(flags.bait || flags.decoy || flags.creep){
            Unit worker = null;
            for(int i = 0; i < team.data().units.size; i++) if(team.data().units.get(i).controller() instanceof Bait.BaitAI) worker = team.data().units.get(i);
            out.append(" worker ").append(worker == null ? "none" : worker.tileX() + "," + worker.tileY() + " plans " + worker.plans().size + (worker.buildPlan() != null ? " building " + worker.buildPlan().x + "," + worker.buildPlan().y : "") + " " + ((Bait.BaitAI)worker.controller()).state + (worker instanceof Payloadc pay && pay.hasPayload() ? " loaded" : ""));
            for(Bait.Spot spot : bait.spots) out.append(" spot ").append(spot.x).append(",").append(spot.y).append(" by ").append(spot.turret.tileX()).append(",").append(spot.turret.tileY())
                .append(bait.standing(spot) ? " up" : " down").append(" stand ").append((int)(spot.stand.x / 8)).append(",").append((int)(spot.stand.y / 8));
        }
        if(flags.harass || flags.cut){
            for(var job : ops.jobs.values()){
                out.append(" [").append(Ops.names[job.kind]).append(" ").append(job.unit.type.name).append(" hp ").append((int)job.unit.health).append(" at ").append(job.unit.tileX()).append(",").append(job.unit.tileY());
                if(job.target != null) out.append(" -> ").append(job.target.block.name).append(" ").append(job.target.tileX()).append(",").append(job.target.tileY()).append(" ").append((int)(job.unit.dst(job.target) / 8)).append("t");
                out.append(" exp ").append((int)job.expected).append("]");
            }
        }
        if(flags.creep){
            out.append(" creep").append(creep.stuck ? " stuck" : "");
            if(creep.building != null) out.append(" building ").append(creep.building.module.name).append(" at ").append(creep.building.x).append(",").append(creep.building.y);
            for(Creep.Post post : creep.posts) out.append(" post ").append(post.module.name).append(" ").append(post.x).append(",").append(post.y);
        }
        if(flags.allot) out.append(" ratio ").append(Strings.fixed(allot.lastRatio, 2)).append(" go ").append(allot.go);
        return out.toString();
    }

    public String report(){
        int dodged = 0;
        for(int i = 0; i < team.data().units.size; i++) if(team.data().units.get(i).controller() instanceof Dodger d) dodged += d.dodged;
        return "flags " + flags.toString().trim().replace(' ', '+') + (flags.allot ? " go " + allot.go + " changes " + allot.changes : "") + (flags.harass || flags.cut ? " " + ops.report() : "")
            + (flags.bait || flags.decoy ? " " + bait.report() : "") + (flags.creep ? " " + creep.report() : "") + (flags.micro ? " dodging " + dodged : "") + " review " + review.jobs;
    }
}
