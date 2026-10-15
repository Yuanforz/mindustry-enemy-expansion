package xtest;

import arc.files.*;
import expansion.*;
import mindustry.*;
import mindustry.core.GameState.*;
import mindustry.game.EventType.*;
import mindustry.io.*;
import mindustry.type.*;

/**
 * Saving and loading: what the enemy has and knows survives a save, and a save from the mod's first
 * version, which kept the enemy's stock on the side, is taken over.
 * Usage: Reload data-dir blueprint-dir map.msav [old-save.msav]
 */
public class Reload{
    static int failures;

    static void check(String what, boolean ok, Object detail){
        if(!ok) failures++;
        System.out.println((ok ? "  pass  " : "  FAIL  ") + what + "  -> " + detail);
    }

    static void start(String file, boolean campaign){
        Vars.logic.reset();
        SaveIO.load(new Fi(file));
        Vars.state.set(State.playing);
        arc.util.Time.setDeltaProvider(() -> 1f);
        Boot.frames(1);
        if(campaign) Sim.campaignRules(true);
        Boot.frames(1);
    }

    public static void main(String[] args){
        Sim.boot(args[0], args[1]);
        Brain brain = Sim.brain;
        brain.unlocked = c -> true;

        Vars.logic.reset();
        SaveIO.load(new Fi(args[2]));
        Vars.state.set(State.playing);
        Vars.state.tick = 0;
        arc.util.Time.setDeltaProvider(() -> 1f);
        Boot.frames(1);
        Sim.campaignRules(true);
        Boot.frames(60 * 60 * 14);

        String things = brain.net.write(), stock = Sim.stock().replaceAll("\\(\\+\\d+\\)", "");
        arc.struct.ObjectIntMap<Item> had = new arc.struct.ObjectIntMap<>();
        for(Item item : Vars.content.items()) had.put(item, brain.stock.have(item));
        int queued = Vars.state.rules.waveTeam.data().plans.size;
        Fi file = new Fi(args[0]).child("reload.msav");
        //the game tells mods a save is being written, and writes the map
        arc.Events.fire(new SaveWriteEvent());
        SaveIO.save(file);

        start(file.absolutePath(), true);
        String after = Sim.stock().replaceAll("\\(\\+\\d+\\)", "");
        check("what the enemy knows of its base is the same after loading", brain.net.write().equals(things), brain.count(Net.Kind.mine) + " mines, " + brain.count(Net.Kind.maker) + " factories, " + brain.count(Net.Kind.yard) + " yards, " + brain.count(Net.Kind.post) + " turrets");
        //a moment passes in loading, in which something is built and something comes in: but the cores are not filled up for nothing
        boolean same = true;
        for(Item item : Vars.content.items()) same &= Math.abs(brain.stock.have(item) - had.get(item, 0)) <= 120;
        check("its stock is the same after loading, with the game's free resources switched on again by the load", same, "before " + stock + " | after " + after);
        check("what was waiting to be built still is", Vars.state.rules.waveTeam.data().plans.size == queued, queued + " blocks");
        Boot.frames(60 * 60 * 6);
        check("and it carries on: no problems in six more minutes", Sim.problems == 0, Sim.stock());

        if(args.length > 3){
            start(args[3], true);
            String tag = Vars.state.rules.tags.get("enemy-expansion", "");
            boolean right = true;
            StringBuilder detail = new StringBuilder();
            for(String entry : tag.split(";", -1)[0].split(",")){
                String[] fields = entry.split(":");
                Item item = fields.length == 4 ? Vars.content.item(fields[0]) : null;
                if(item == null) continue;
                int want = Math.min(Integer.parseInt(fields[1]), brain.stock.cap());
                detail.append(item.name).append(' ').append(brain.stock.have(item)).append(" (had ").append(fields[1]).append(") ");
                //a moment has passed, so a little may have come in
                if(Math.abs(brain.stock.have(item) - want) > 40) right = false;
            }
            check("a save from the first version: what the enemy had on the side is in its cores now", right && detail.length() > 0, detail);
            Boot.frames(60 * 60 * 5);
            check("and the game runs on from it", Sim.problems == 0, Sim.stock());
        }

        System.out.println(failures == 0 ? "RESULT: all pass" : "RESULT: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
