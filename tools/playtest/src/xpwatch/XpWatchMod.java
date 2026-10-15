package xpwatch;

import arc.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.mod.*;
import mindustry.type.*;
import mindustry.world.*;

/**
 * For looking at how the enemy builds. Starts a map the mod has never seen, made by the game's own
 * generator for Erekir, with the enemy on one bare core and the same starting stock as the player;
 * turns the fog off; and puts the camera, set free of the player's unit, over the enemy's core.
 */
public class XpWatchMod extends Mod{
    int stage = -1, frames;
    /** "watch": camera over the enemy, nothing can hurt the player. "play": an ordinary game to be played. "menu": nothing, the game as it is. */
    final String mode = System.getProperty("xp.mode", "watch");
    final boolean play = mode.equals("play");

    public XpWatchMod(){
        if(mode.equals("menu")) return;
        //a campaign sector played live, the mod on the player's side
        if(mode.equals("campaign")){
            xtest.LiveCampaign.install();
            return;
        }
        if(play){
            //the player's own settings are left as they are
            Events.on(ClientLoadEvent.class, e -> Core.app.post(() -> { stage = 0; frames = 0; }));
            Events.run(Trigger.update, this::step);
            return;
        }
        Core.settings.put("musicvol", 0);
        Core.settings.put("ambientvol", 0);
        Core.settings.put("backgroundpause", false);
        //no landing cutscene, and the camera moves by itself, not with the player's unit
        Core.settings.put("skipcoreanimation", true);
        Core.settings.put("detach-camera", true);
        //the enemy's stock and what it is thinking, on screen; and no waiting at the start
        Core.settings.put("expansion-debug", true);
        Core.settings.put("expansion-grace", 0);
        //for a trial run: the enemy set to its strongest, or as it comes
        Core.settings.put("expansion-strongest", System.getProperty("xp.strongest") != null);
        Events.on(ClientLoadEvent.class, e -> Core.app.post(() -> { stage = 0; frames = 0; }));
        Events.run(Trigger.update, this::step);
        //for a trial run: the game several times as fast, by whole extra updates
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

    void step(){
        if(stage == 0 && ++frames > 40){
            stage = 1;
            start();
        }else if(stage == 2 && Vars.state.isGame()){
            Team enemy = Vars.state.rules.waveTeam;
            //held over the enemy's core for the first moments; after that it is the viewer's to move
            frames++;
            if(play){
                if(frames == 240) Vars.ui.showInfoToast("随机地图 · 种子 " + Integer.getInteger("xp.seed", 5) + " · 战争迷雾已关闭 · 摧毁敌方核心获胜", 8f);
                String peek = System.getProperty("xp.peek");
                if(peek != null && frames == Integer.getInteger("xp.shotframe", 600)){
                    arc.util.ScreenUtils.saveScreenshot(new arc.files.Fi(peek), 0, 0, Core.graphics.getBackBufferWidth(), Core.graphics.getBackBufferHeight());
                    var rule = Vars.state.rules.teams.get(enemy);
                    Log.info("[XP] peek saved; tick @, fog @/@, enemy cores @ buildings @ units @, squads @, enemy free items @, player cores @ unit @, detached camera @, debug @, grace @",
                        (int)Vars.state.tick, Vars.state.rules.fog, Vars.state.rules.staticFog, enemy.cores().size, enemy.data().buildings.size, enemy.data().units.size, rule.rtsAi,
                        rule.infiniteResources || rule.fillItems || rule.cheat, Vars.state.rules.defaultTeam.cores().size, Vars.player.unit() == null ? "none" : Vars.player.unit().type.name,
                        Core.settings.getBool("detach-camera"), Core.settings.getBool("expansion-debug"), Core.settings.getInt("expansion-grace", -1));
                }
                Vars.state.rules.fog = false;
                Vars.state.rules.staticFog = false;
                return;
            }
            if(frames < 90 && enemy.core() != null){
                Core.camera.position.set(enemy.core());
                Vars.renderer.setScale(arc.scene.ui.layout.Scl.scl(1.6f));
            }
            if(frames == 120) Vars.ui.showInfoToast("镜头已脱离单位：WASD 移动，滚轮缩放。左侧是敌方库存与规划状态。", 12f);
            //a trial run: a picture of what is on screen, and out again
            String shot = System.getProperty("xp.shot");
            //a trial run that is to show the mod's page of the settings: opened a moment before the picture is taken
            if(shot != null && System.getProperty("xp.settings") != null && frames == Integer.getInteger("xp.shotframe", 900) - 240){
                Log.info("[XP] strongest mode is stored as @", Core.settings.getBool("expansion-strongest"));
                Vars.ui.settings.show();
                String title = Core.bundle.get("expansion.category");
                arc.scene.Element button = Vars.ui.settings.find(e -> e instanceof arc.scene.ui.TextButton b && String.valueOf(b.getText()).contains(title));
                Log.info("[XP] settings page @: @", title, button == null ? "no button for it" : "opened");
                if(button != null) button.fireClick();
            }
            if(shot != null && System.getProperty("xp.settings") != null && frames == Integer.getInteger("xp.shotframe", 900)){
                //what the page shows for each switch, beside what is stored for it
                StringBuilder boxes = new StringBuilder();
                Vars.ui.settings.find(e -> {
                    if(e instanceof arc.scene.ui.Button button && !(e instanceof arc.scene.ui.TextButton text && String.valueOf(text.getText()).isEmpty())){
                        arc.scene.ui.Label name = button.find(c -> c instanceof arc.scene.ui.Label);
                        if(name != null && boxes.length() < 900) boxes.append(e.getClass().getSimpleName()).append(' ').append(name.getText()).append('=').append(button.isChecked() ? "on" : "off").append("  ");
                    }
                    if(e instanceof arc.scene.ui.Label label && String.valueOf(label.getText()).contains(Core.bundle.get("expansion.strongest.now").replace("{0}", "").trim())) boxes.append("[").append(label.getText()).append("]  ");
                    return false;
                });
                Log.info("[XP] strongest mode is stored as @ at the picture; switches on the page: @", Core.settings.getBool("expansion-strongest"), boxes);
            }
            if(shot != null && frames == Integer.getInteger("xp.shotframe", 900)){
                arc.util.ScreenUtils.saveScreenshot(new arc.files.Fi(shot), 0, 0, Core.graphics.getBackBufferWidth(), Core.graphics.getBackBufferHeight());
                Log.info("[XP] picture saved; enemy buildings @, units @, fog @, tick @, delta @", enemy.data().buildings.size, enemy.data().units.size, Vars.state.rules.fog, (int)Vars.state.tick, arc.util.Time.delta);
                for(mindustry.gen.Unit unit : enemy.data().units){
                    Log.info("[XP] unit @ at @,@: mining @, holds @ @, building @, controller @", unit.type.name, unit.tileX(), unit.tileY(), unit.mineTile == null ? "nothing" : unit.mineTile.x + "," + unit.mineTile.y + " " + unit.getMineResult(unit.mineTile), unit.stack.amount, unit.stack.item, unit.buildPlan() == null ? "nothing" : unit.buildPlan().block, unit.controller().getClass().getSimpleName());
                }
                arc.struct.ObjectIntMap<String> drills = new arc.struct.ObjectIntMap<>();
                for(mindustry.gen.Building b : enemy.data().buildings){
                    if(b.block instanceof mindustry.world.blocks.production.BeamDrill){
                        var d = (mindustry.world.blocks.production.BeamDrill.BeamDrillBuild)b;
                        String what = (d.lastItem == null ? "no ore" : d.lastItem.name) + (b.efficiency > 0.5f ? " working" : " idle power " + (int)(b.power.status * 100) + "% holds " + b.items.total());
                        drills.increment(what);
                    }
                }
                Log.info("[XP] wall drills: @", drills);
                try{
                    Object main = Vars.mods.getMod("enemy-expansion").main;
                    Object brain = main.getClass().getField("brain").get(null);
                    Log.info("[XP] status:\n@", brain.getClass().getMethod("status").invoke(brain));
                }catch(Throwable t){ Log.info("[XP] no status: @", t); }
                Core.app.post(Core.app::exit);
            }
            //the fog stays off whatever else sets it
            Vars.state.rules.fog = false;
            Vars.state.rules.staticFog = false;
        }
    }

    void start(){
        int seed = Integer.getInteger("xp.seed", 5);
        String stock = System.getProperty("xp.stock", "plain");
        Vars.ui.loadAnd(() -> {
            Vars.logic.reset();
            Rules rules = new Rules();
            Planets.erekir.applyRules(rules, true);
            Gamemode.attack.apply(rules);
            rules.waves = false;
            rules.waveTimer = false;
            rules.fog = false;
            rules.staticFog = false;
            rules.loadout = switch(stock){
                case "plain" -> ItemStack.list(Items.beryllium, 300, Items.graphite, 300);
                case "poor" -> ItemStack.list(Items.beryllium, 60);
                default -> new Seq<>();
            };
            Vars.state.rules = rules;

            Seq<Sector> sectors = Planets.erekir.sectors.select(sector -> sector.preset == null);
            Sector sector = sectors.get(Math.floorMod(seed * 7919, sectors.size));
            int size = Integer.getInteger("xp.size", 150 + Math.floorMod(seed * 31, 5) * 50);
            WorldParams params = new WorldParams();
            params.seedOffset = seed;
            rules.sector = sector;
            try{
                Vars.world.loadGenerator(size, size, tiles -> Planets.erekir.generator.generate(tiles, sector, params));
            }finally{
                rules.sector = null;
            }
            Vars.state.rules = rules;
            rules.editor = false;
            //the generator switches on a rule of its own, which no ordinary map has: nothing may be built within
            //some tiles of the other side's buildings. the map plays by the usual rules, the same for both sides
            rules.placeRangeCheck = false;

            Tile spawn = null;
            for(Tile tile : Vars.world.tiles){
                if(tile.overlay() == Blocks.spawn) spawn = tile;
            }
            if(spawn != null){
                spawn.setOverlay(Blocks.air);
                Tile site = firm(spawn);
                if(site != null) site.setBlock(Blocks.coreBastion, rules.waveTeam);
                //further cores for the enemy, if asked for: each some way from the ones before, and no nearer the player than those are
                Tile mine = null;
                for(Tile tile : Vars.world.tiles){
                    if(tile.build != null && tile.team() == rules.defaultTeam && tile.block() instanceof mindustry.world.blocks.storage.CoreBlock) mine = tile.build.tile;
                }
                Seq<Tile> theirs = Seq.with(site);
                for(int more = 1; more < Integer.getInteger("xp.cores", 1) && site != null && mine != null; more++){
                    Tile next = second(theirs, mine);
                    if(next == null) break;
                    next.setBlock(Blocks.coreBastion, rules.waveTeam);
                    theirs.add(next);
                    Log.info("[XP] another enemy core at @,@", next.x, next.y);
                }
            }
            if(play){
                //its units gather and attack in squads, as on the campaign's attack maps
                if(!"0".equals(System.getProperty("xp.rts"))){
                    var rule = rules.teams.get(rules.waveTeam);
                    rule.rtsAi = true;
                    rule.rtsMaxSquad = 15;
                }
            }else{
                //so that the watching is not cut short by the enemy winning
                rules.teams.get(rules.defaultTeam).blockHealthMultiplier = 1e6f;
            }

            Events.fire(new RulesLoadEvent(Vars.state.rules));
            Vars.logic.play();
            if(play){
                //a save of its own, so the game keeps itself and can be carried on later
                try{
                    Vars.control.saves.addSave("试玩 种子" + seed);
                }catch(Throwable error){
                    Log.err("[XP] no save", error);
                }
            }
            Events.fire(Trigger.newGame);
            Log.info("[XP] " + mode + " seed @, @x@, stock @, enemy core @", seed, size, size, stock, rules.waveTeam.core() == null ? "none" : rules.waveTeam.core().tileX() + "," + rules.waveTeam.core().tileY());
            stage = 2;
            frames = 0;
        });
    }

    /** A place for another core: firm ground about fifty tiles from the nearest core there is, and not nearer the player than three quarters of the way the first one is. */
    static @Nullable Tile second(Seq<Tile> cores, Tile player){
        float keep = cores.first().dst(player) / Vars.tilesize * 0.75f;
        Tile best = null;
        float bestScore = Float.MAX_VALUE;
        for(int x = 10; x < Vars.world.width() - 10; x += 3){
            for(int y = 10; y < Vars.world.height() - 10; y += 3){
                Tile tile = Vars.world.tile(x, y);
                float near = Float.MAX_VALUE;
                for(Tile core : cores) near = Math.min(near, tile.dst(core) / Vars.tilesize);
                float score = Math.abs(near - 50f);
                if(near < 35f || score >= bestScore || tile.dst(player) / Vars.tilesize < keep) continue;
                boolean good = true;
                for(int dx = -7; dx <= 7 && good; dx++){
                    for(int dy = -7; dy <= 7 && good; dy++){
                        Tile other = Vars.world.tile(x + dx, y + dy);
                        good = other != null && other.build == null && ground(other);
                    }
                }
                if(good){
                    best = tile;
                    bestScore = score;
                }
            }
        }
        return best;
    }

    static boolean ground(Tile tile){
        return !tile.block().isStatic() && tile.floor().placeableOn && !tile.floor().isDeep() && !tile.floor().solid;
    }

    /** The nearest tile to a place that has open, buildable ground for eight tiles every way. */
    static @Nullable Tile firm(Tile from){
        Tile best = null;
        float bestFar = Float.MAX_VALUE;
        for(Tile tile : Vars.world.tiles){
            float far = tile.dst2(from);
            if(far >= bestFar || far > 60f * 60f * 64f) continue;
            boolean good = true;
            for(int dx = -8; dx <= 8 && good; dx++){
                for(int dy = -8; dy <= 8 && good; dy++){
                    Tile other = Vars.world.tile(tile.x + dx, tile.y + dy);
                    good = other != null && other.build == null && ground(other);
                }
            }
            if(good){
                best = tile;
                bestFar = far;
            }
        }
        return best;
    }
}
