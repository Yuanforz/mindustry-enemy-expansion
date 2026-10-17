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
        //drawn as its side sees it: the watcher belongs to no side, so that the core's unit is the mod's, and is that side only
        //while the frame is drawn
        //what its side can see, drawn from what the game keeps for that side, which is what the mod goes by: black where it has
        //never looked, darkened where it looked once (it knows what stands there) and sees nothing now, clear where it sees now.
        //the game's own fog picture is not used: it takes in what is uncovered only for the side the watcher is on as it
        //happens, and the watcher is on no side outside the drawing, so that the core's unit stays the mod's
        Events.run(Trigger.preDraw, () -> {
            if(stage != 2) return;
            Vars.player.team(Team.sharded);
            sight = null;
            if(!Vars.state.rules.fog || System.getProperty("xp.fogoff") != null) return;
            //the minimap still goes by the game's own picture of the fog: that is made again from what the game has, once a second
            if(Vars.state.rules.staticFog && frames % 60 == 0 && Vars.renderer != null && Vars.renderer.fog != null) Vars.renderer.fog.copyFromCpu();
            float w = Core.camera.width, h = Core.camera.height;
            sx = Math.max((int)((Core.camera.position.x - w / 2f) / Vars.tilesize) - 1, 0);
            sy = Math.max((int)((Core.camera.position.y - h / 2f) / Vars.tilesize) - 1, 0);
            sw = Math.min((int)(w / Vars.tilesize) + 3, Vars.world.width() - sx);
            sh = Math.min((int)(h / Vars.tilesize) + 3, Vars.world.height() - sy);
            if(sw <= 0 || sh <= 0) return;
            sight = new byte[sw * sh];
            boolean staticFog = Vars.state.rules.staticFog;
            for(int y = 0; y < sh; y++){
                for(int x = 0; x < sw; x++){
                    int tx = sx + x, ty = sy + y;
                    sight[x + y * sw] = (byte)(Vars.fogControl.isVisibleTile(Team.sharded, tx, ty) ? 2 : !staticFog || Vars.fogControl.isDiscovered(Team.sharded, tx, ty) ? 1 : 0);
                }
            }
            //the other side's units it does not see now: those are hidden as the game hides them
            hidden.clear();
            for(mindustry.gen.Unit unit : mindustry.gen.Groups.unit){
                if(unit.team != Team.sharded && unit.team != Team.derelict && !Vars.fogControl.isVisible(Team.sharded, unit.x, unit.y)) hidden.add(unit);
            }
            heldFog = true;
            heldStatic = staticFog;
            Vars.state.rules.fog = false;
            Vars.state.rules.staticFog = false;
        });
        Events.run(Trigger.draw, () -> {
            if(stage != 2 || sight == null) return;
            arc.graphics.g2d.Draw.z(mindustry.graphics.Layer.fogOfWar);
            for(int y = 0; y < sh; y++){
                for(int x = 0; x < sw; x++){
                    byte seen = sight[x + y * sw];
                    if(seen == 2) continue;
                    arc.graphics.g2d.Draw.color(0f, 0f, 0f, seen == 1 ? 0.62f : 0.96f);
                    arc.graphics.g2d.Fill.rect((sx + x) * Vars.tilesize, (sy + y) * Vars.tilesize, Vars.tilesize, Vars.tilesize);
                }
            }
            for(mindustry.gen.Unit unit : hidden){
                if(!unit.isValid()) continue;
                arc.graphics.g2d.Draw.color(0f, 0f, 0f, 1f);
                arc.graphics.g2d.Fill.rect(unit.x, unit.y, unit.hitSize * 1.6f + 6f, unit.hitSize * 1.6f + 6f);
            }
            arc.graphics.g2d.Draw.color();
        });
        Events.run(Trigger.postDraw, () -> {
            if(stage != 2) return;
            Vars.player.team(Team.derelict);
            if(heldFog){
                Vars.state.rules.fog = true;
                Vars.state.rules.staticFog = heldStatic;
                heldFog = false;
            }
        });
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
            //the fog stays as the game has it: with it off both sides played another game (the fifth sector, held 8 of 8 with
            //it, was lost 7 of 8 without). what is drawn is what its side sees (xp.fogoff for the old way)
            if(System.getProperty("xp.fogoff") != null){
                Vars.state.rules.fog = false;
                Vars.state.rules.staticFog = false;
            }
            if(frames < 90 && Vars.state.rules.defaultTeam.core() != null) Core.camera.position.set(Vars.state.rules.defaultTeam.core());
            if(frames == 100 && System.getProperty("xp.zoom") != null){
                Vars.renderer.minZoom = 0.2f;
                Vars.renderer.setScale(Float.parseFloat(System.getProperty("xp.zoom")));
            }
            if(frames == 200) Vars.ui.showInfoToast("第" + "一二三四五六七八九十".charAt(Math.max(Math.min(java.util.Arrays.asList(Campaign.line).indexOf(sector), 9), 0)) + "关 " + sector + " · 模组扮演玩家 · 困难 · 公平最强模式 · 科技全解锁 · 战争迷雾照常（显示我方视野）", 10f);
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
    /** What its side sees of the tiles in view, worked out before the frame is drawn: 0 never seen, 1 seen once, 2 seen now. */
    static byte[] sight;
    static int sx, sy, sw, sh;
    static boolean heldFog, heldStatic;
    static final arc.struct.Seq<mindustry.gen.Unit> hidden = new arc.struct.Seq<>();

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
            if(System.getProperty("xp.fogoff") != null){
                rules.fog = false;
                rules.staticFog = false;
            }
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
