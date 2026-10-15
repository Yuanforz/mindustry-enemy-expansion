package xtest;

import arc.*;
import arc.struct.*;
import expansion.*;
import expansion.Director.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.type.*;
import mindustry.world.*;

/**
 * The parts of the enemy's thinking that can be checked without playing a game out: the machine that
 * says what posture it is in, what it holds it can't do without, what it will spare for walls, how a
 * blueprint that adds to something is read, and that what it knows of its base survives being written down.
 *
 * Usage: Mind data-dir blueprint-dir
 */
public class Mind{
    static int failures;

    static void check(String what, boolean ok){
        System.out.println((ok ? "  pass  " : "  FAIL  ") + what);
        if(!ok) failures++;
    }

    public static void main(String[] args){
        Sim.boot(args[0], args[1]);
        Brain brain = Sim.brain;

        //the posture machine: only what is measured moves it, and not back and forth in a moment
        Director director = new Director();
        Facts facts = new Facts();
        facts.lacking = true;
        check("with something it can't do without missing, it sees to that", director.update(facts, 0f) == Posture.bootstrap && Seq.with(director.first()).contains(Goal.staples));
        facts.lacking = false;
        facts.fill = 0.1f;
        check("with a source of each, it grows", director.update(facts, 60f) == Posture.develop);

        //what comes first is weighed on how things stand, not fixed beforehand
        director.weigh(facts);
        Seq<Goal> first = Seq.with(director.first());
        check("left alone, short of things and with ore to take up: more of what lies about comes first, before yards and turrets", first.indexOf(Goal.income) >= 0
            && first.indexOf(Goal.income) < first.indexOf(Goal.army) && !first.contains(Goal.cover) && !first.contains(Goal.defence));
        facts.pressure = 1f;
        director.weigh(facts);
        first = Seq.with(director.first());
        check("pressed hard: holding its home comes before spreading", first.indexOf(Goal.cover) >= 0 && first.indexOf(Goal.cover) < first.indexOf(Goal.income));
        facts.pressure = 0f;
        facts.uncovered = true;
        facts.saturated = true;
        facts.fill = 0.5f;
        director.weigh(facts);
        first = Seq.with(director.first());
        check("with all there is taken up, a small army and a core no turret is over: yards and a turret come before spreading", first.indexOf(Goal.army) < first.indexOf(Goal.income)
            && first.indexOf(Goal.cover) >= 0 && first.indexOf(Goal.cover) < first.indexOf(Goal.income));
        facts.uncovered = false;
        facts.army = 1f;
        facts.fill = 0.95f;
        director.weigh(facts);
        first = Seq.with(director.first());
        check("with a full army and full cores: making its base better comes before more yards", first.indexOf(Goal.refit) >= 0 && first.indexOf(Goal.refit) < first.indexOf(Goal.army));
        facts.engaged = true;
        director.weigh(facts);
        first = Seq.with(director.first());
        check("with its army standing against the other side's: turrets out at the front come in", first.contains(Goal.defence));
        check("and the posture turns to the front", director.update(facts, 200f) == Posture.front);
        facts.engaged = false;
        facts.army = 0f;
        facts.fill = 0.1f;
        facts.saturated = false;

        //and the postures still move only on what is measured
        director = new Director();
        director.update(facts, 0f);
        director.update(facts, 60f);
        director.losses = Director.pressure;
        check("losing buildings, it holds", director.update(facts, 100f) == Posture.pressed);
        director.tick(60f * 60f * 4f);
        check("what it lost is forgotten in time", director.losses < 1f);
        check("but it keeps to holding until some time has passed", director.update(facts, 101f) == Posture.pressed);
        check("and then grows again", director.update(facts, 101f + Director.dwell) == Posture.develop);
        facts.cut = true;
        check("a cut line has it hold too", director.update(facts, 102f + Director.dwell) == Posture.pressed);
        facts.cut = false;

        //what it knows of a thing it built, written down and read back
        Asset asset = new Asset(Net.Kind.mine, "bore-row", 10, 20, 3, true, 4, 9, 22);
        asset.boosted = 5;
        asset.armed = 7;
        Asset back = Asset.read(asset.write());
        check("a thing it built is read back as it was written", back != null && back.kind == asset.kind && back.blueprint.equals("bore-row") && back.x == 10 && back.y == 20 && back.turns == 3
            && back.mirrored && back.copies == 4 && back.thingX == 9 && back.thingY == 22 && back.boosted == 5 && back.armed == 7);
        check("and one written before turrets could change ammunition still reads", Asset.read("p:breach-pen:1:2:0:0:1:1:2:0") != null);

        //blueprints that add to something, and that say where a liquid is let in
        Seq<Blueprint> read = Blueprint.parse("[a]\n@tank-fabricator 0 0 0 stell\ntank-refabricator 3 0 0\nin silicon 4 2 3\n[b]\nplasma-bore 0 0 0\nboost hydrogen 2 0\nboost hydrogen 2 1\nrow 0 2 3\n[c]\nduct 0 0\n@plasma-bore 1 0\n");
        Blueprint addition = read.find(b -> b.name.equals("a")), row = read.find(b -> b.name.equals("b"));
        check("an addition is read as one, and what it is added to is not paid for again", addition != null && addition.addon() && addition.kind() == Blueprint.Kind.addon
            && !Seq.with(addition.cost()).contains(stack -> stack.amount > Blocks.tankRefabricator.requirements[0].amount * 3));
        check("the places a liquid is let in are repeated with every copy of a row", row != null && row.at(10, 10, 0, false, 3).boosts.size == 6 && row.at(10, 10, 0, false, 3).boosts.get(5).y == 15);
        check("what it is added to has to be written first", !read.contains(b -> b.name.equals("c")));

        //on a map: what it can't do without, and what it will spare for walls
        Team enemy = Wild.make(6, "plain");
        Boot.frames(2);
        for(String level : new String[]{"full", "half", "early"}){
            Sim.tech(level);
            brain.tryOut("income");
            check("with " + level + " research, what it can't do without is beryllium, graphite and silicon: " + brain.staples(), brain.staples().equals(Seq.with(Items.beryllium, Items.graphite, Items.silicon)));
        }
        Sim.tech("full");
        brain.stock.set(Items.beryllium, 1500);
        brain.stock.set(Items.tungsten, 100);
        brain.tryOut("income");
        check("with little tungsten, a wall it would have of tungsten goes up in beryllium: " + brain.spared(Blocks.tungstenWallLarge), brain.spared(Blocks.tungstenWallLarge) == Blocks.berylliumWallLarge);
        brain.stock.set(Items.tungsten, brain.stock.cap());
        brain.tryOut("income");
        check("with plenty, a wall it was to have of beryllium goes up in tungsten: " + brain.spared(Blocks.berylliumWallLarge), brain.spared(Blocks.berylliumWallLarge) == Blocks.tungstenWallLarge);
        Sim.tech("early");
        brain.tryOut("income");
        check("but not where the player has not researched tungsten walls", brain.spared(Blocks.tungstenWallLarge) == Blocks.berylliumWallLarge);
        Sim.tech("full");

        //a yard keeps room in front of it for what may be added to it, when it makes things better at all
        IntSeq front = new IntSeq();
        brain.frontOf(Blocks.tankFabricator, 50, 50, 0, front);
        check("a fabricator keeps six rows clear in front of it, for a refabricator and the way out of that", front.size == 18);
        Core.settings.put("expansion-refit", false);
        front.clear();
        brain.frontOf(Blocks.tankFabricator, 50, 50, 0, front);
        check("and three when it is not to make things better", front.size == 9);
        Core.settings.put("expansion-refit", true);
        front.clear();
        brain.frontOf(Blocks.tankRefabricator, 50, 50, 0, front);
        check("a refabricator, with nothing to add to it, keeps three", front.size == 9);

        System.out.println(failures == 0 ? "RESULT: all pass" : "RESULT: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
