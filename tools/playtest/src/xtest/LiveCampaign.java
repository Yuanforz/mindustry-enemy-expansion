package xtest;

import arc.*;
import arc.files.*;
import arc.struct.*;
import arc.util.*;
import expansion.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.core.GameState.*;
import mindustry.ctype.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.io.*;
import mindustry.type.*;

/**
 * A campaign sector played live in a window, the mod on the player's side, as the campaign test plays
 * it without one: the sector's map under the campaign's rules on hard, research taken up from where an
 * honest run of the sectors before left it and paid for out of the core as the test does, the strongest
 * fair settings. The fog is off so that everything can be seen. Started by play.sh campaign.
 */
public class LiveCampaign{
    static int frames, stage = -1;
    static String sector;

    public static void install(){
        sector = System.getProperty("xp.sector", "lake");
        Core.settings.put("musicvol", 0);
        Core.settings.put("ambientvol", 0);
        Core.settings.put("backgroundpause", false);
        Core.settings.put("skipcoreanimation", true);
        Core.settings.put("detach-camera", true);
        Core.settings.put("expansion-strongest", true);
        Events.on(ClientLoadEvent.class, e -> Core.app.post(() -> { stage = 0; frames = 0; }));
        Events.run(Trigger.update, LiveCampaign::step);
        int speed = Integer.getInteger("xp.speed", 1);
        if(speed > 1) Core.app.addListener(new ApplicationListener(){
            @Override public void update(){
                if(stage != 2 || !Vars.state.isGame() || Vars.state.isPaused()) return;
                for(int i = 1; i < speed; i++){
                    Vars.asyncCore.begin();
                    Vars.logic.update();
                    Vars.asyncCore.end();
                }
            }
        });
    }

    static void step(){
        if(stage == 0 && ++frames > 40){
            stage = 1;
            start();
        }else if(stage == 2 && Vars.state.isGame()){
            frames++;
            if(Vars.player.team() != Team.derelict){
                Vars.player.clearUnit();
                Vars.player.team(Team.derelict);
            }
            Vars.state.rules.fog = false;
            Vars.state.rules.staticFog = false;
            if(frames < 90 && Vars.state.rules.defaultTeam.core() != null) Core.camera.position.set(Vars.state.rules.defaultTeam.core());
            if(frames == 200) Vars.ui.showInfoToast("第" + "一二三四五六七八九十".charAt(Math.max(Math.min(java.util.Arrays.asList(Campaign.line).indexOf(sector), 9), 0)) + "关 " + sector + " · 模组扮演玩家 · 困难 · 公平最强模式 · 科技全解锁 · 战争迷雾已关", 10f);
            //a trial of the trial: a picture and a line of how it stands, then it closes
            String shot = System.getProperty("xp.shot");
            if(shot != null && frames == Integer.getInteger("xp.shotframe", 900)){
                arc.util.ScreenUtils.saveScreenshot(new Fi(shot), 0, 0, Core.graphics.getBackBufferWidth(), Core.graphics.getBackBufferHeight());
                Team me = Vars.state.rules.defaultTeam;
                Log.info("[XP] tick @, fog @, my buildings @, units @, core @, enemy units @, researched @, posture @",
                    (int)Vars.state.tick, Vars.state.rules.fog, me.data().buildings.size, me.data().units.size, me.core() == null ? "none" : me.core().items.toString(),
                    Vars.state.rules.waveTeam.data().units.size, Campaign.researched.size, ExpansionMod.brain.director.posture);
                Log.info("[XP] status:\n@", ExpansionMod.brain.status());
                Core.app.post(Core.app::exit);
            }
            //the player's part in research, once a second of game time
            if(!Vars.state.isPaused() && (int)Vars.state.tick / 60 != lastSecond){
                lastSecond = (int)Vars.state.tick / 60;
                if(System.getProperty("xp.notes") != null && lastSecond % 10 == 0){
                    for(String key : System.getProperty("xp.notes").split(",")) Log.info("[XP] t=@ note @: @", lastSecond, key, ExpansionMod.brain.note(key));
                    Team me0 = Vars.state.rules.defaultTeam;
                    if(me0.core() != null) Log.info("[XP] t=@ core @", lastSecond, me0.core().items);
                    Log.info("[XP] t=@ reserve @", lastSecond, ExpansionMod.brain.reserveNote());
                }
                try{
                    Campaign.research();
                }catch(Throwable t){
                    Log.err("[XP] research", t);
                }
            }
        }
    }

    static int lastSecond = -1;

    static void start(){
        Fi root = new Fi(System.getProperty("xp.root", "."));
        Vars.ui.loadAnd(() -> {
            //nothing researched but what everyone has from the start; then what an honest run had after the sectors before
            for(ContentType type : ContentType.all){
                for(Content content : Vars.content.getBy(type)){
                    if(content instanceof UnlockableContent unlockable) unlockable.clearUnlock();
                }
            }
            for(TechTree.TechNode node : TechTree.all) node.reset();
            Brain brain = ExpansionMod.brain;
            brain.unlocked = UnlockableContent::unlocked;
            Brain.side = Team.sharded;
            Campaign.brain = brain;
            Campaign.me = Team.sharded;
            Campaign.bank = new Campaign.Holdings();
            Campaign.stateDir = root.child("test/campaign");
            Campaign.lastDir = root.child("build/campaign-state");
            String previous = null;
            for(String name : Campaign.line){
                if(name.equals(sector)) break;
                SectorPreset done = Vars.content.getByName(ContentType.sector, name);
                Campaign.taken.add(done);
                Campaign.landed.add(done);
                previous = name;
            }
            if(previous != null && !Campaign.free) Campaign.restore(previous);
            Campaign.unlockFree();
            //as the test plays it: everything the tree allows by this sector is had at once
            if(Campaign.free) Campaign.grant();
            SectorPreset preset = Vars.content.getByName(ContentType.sector, sector);
            Campaign.landed.add(preset);

            Vars.logic.reset();
            SaveIO.load(root.child("build/test-maps/maps/erekir/" + sector + ".msav"));
            Rules rules = Vars.state.rules;
            Planet planet = Planets.erekir;
            planet.campaignRules.difficulty = Difficulty.hard;
            Team enemy = rules.waveTeam;
            planet.applyRules(rules, false);
            rules.coreIncinerates = true;
            rules.infiniteResources = false;
            if(planet.enemyInfiniteItems){
                enemy.rules().infiniteResources = true;
                enemy.rules().fillItems = true;
            }
            enemy.rules().buildSpeedMultiplier *= planet.enemyBuildSpeedMultiplier;
            enemy.rules().unitFactoryActivationDelay = Math.max(enemy.rules().unitFactoryActivationDelay, planet.enemyFactoryActivationDelay);
            if(preset.captureWave > 0) rules.winWave = preset.captureWave;
            rules.fog = false;
            rules.staticFog = false;
            Vars.state.set(State.playing);
            Vars.logic.play();
            Vars.state.wavetime *= planet.campaignRules.difficulty.waveTimeMultiplier;
            Vars.state.tick = 0;
            //you watch from outside: your unit is not to take the place of the builder the mod sends out from the core
            Vars.player.clearUnit();
            Vars.player.team(Team.derelict);
            stage = 2;
            frames = 0;
            Log.info("[XP] campaign @: researched @, bank @", sector, Campaign.researched.size, Campaign.bank);
            StringBuilder open = new StringBuilder();
            for(mindustry.world.Block block : Vars.content.blocks()) if(block.unlockedNow() && block.isOnPlanet(Planets.erekir)) open.append(block.name).append(' ');
            StringBuilder opts = new StringBuilder();
            for(expansion.Options.Option option : expansion.Options.all){
                opts.append(option.name).append('=').append(option instanceof expansion.Options.Flag flag ? String.valueOf(flag.on()) : String.valueOf(((expansion.Options.Num)option).get())).append(' ');
            }
            Log.info("[XP] options: @", opts);
        });
    }
}
