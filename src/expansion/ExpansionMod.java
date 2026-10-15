package expansion;

import arc.*;
import arc.scene.ui.*;
import arc.util.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.mod.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.world.*;

import static mindustry.Vars.*;

public class ExpansionMod extends Mod{
    public static final Brain brain = new Brain();

    @Override
    public void init(){
        for(Options.Option option : Options.all){
            if(option instanceof Options.Flag flag) Core.settings.defaults(flag.key(), flag.start);
            if(option instanceof Options.Num num) Core.settings.defaults(num.key(), num.start);
        }

        //has to be in place before any world exists
        Intake.install(brain.stock);

        //what the enemy builds is described in text files that come with the mod
        var mod = mods == null ? null : mods.getMod(ExpansionMod.class);
        if(mod != null){
            for(var file : mod.root.child("blueprints").list()){
                if(file.extEquals("txt")) brain.blueprints.addAll(Blueprint.parse(file.readString()));
            }
        }
        Log.info("[EnemyExpansion] @ blueprints loaded.", brain.blueprints.size);

        Events.on(ResetEvent.class, e -> brain.clear());
        Events.on(WorldLoadEvent.class, e -> brain.load());
        Events.on(SaveWriteEvent.class, e -> brain.save());
        Events.on(BlockDestroyEvent.class, e -> brain.lost(e.tile));
        Events.run(Trigger.update, brain::update);

        if(!headless) Events.on(ClientLoadEvent.class, e -> buildUi());
    }

    /**
     * Says what the enemy just did. Only for looking into how the mod behaves: in normal play the player
     * is told nothing and sees nothing of the enemy's stock, and has to scout like against any opponent.
     */
    public static void announce(String key){
        if(!headless && Options.debug.on()) ui.hudfrag.showToast(Icon.warning, Core.bundle.get(key));
    }

    void buildUi(){
        //the settings screen is made from the list of options, under a heading for each group of them
        ui.settings.addCategory(Core.bundle.get("expansion.category"), Icon.units, t -> {
            for(String group : Options.groups){
                t.pref(new Heading(group));
                for(Options.Option option : Options.all){
                    if(!option.group.equals(group)) continue;
                    if(option instanceof Options.Flag flag) t.checkPref(flag.key(), flag.start);
                    if(option instanceof Options.Num num) t.sliderPref(num.key(), num.start, num.least, num.most, num.step, value -> shown(num, value));
                }
            }
        });

        ui.hudGroup.fill(t -> {
            t.name = "enemy-expansion";
            t.left();
            Label label = new Label("");
            label.setStyle(Styles.outlineLabel);
            float[] timer = {30f};
            label.update(() -> {
                if((timer[0] += Time.delta) < 30f) return;
                timer[0] = 0f;
                label.setText(summary());
            });
            t.table(Styles.black3, info -> info.add(label).left()).margin(6f);
            t.visible(() -> ui.hudfrag.shown && !ui.minimapfrag.shown() && Options.debug.on() && brain.active());
        });
    }

    /** A number with what it counts. */
    static String shown(Options.Num num, int value){
        String own = label(num, value);
        //while the enemy is set to its strongest the number that goes is another one: it is shown beside the player's own
        if(Options.strongest.set() && num.strong != null && num.strong != value) return own + "  [accent]" + Core.bundle.format("expansion.strongest.now", label(num, num.strong)) + "[]";
        return own;
    }

    static String label(Options.Num num, int value){
        return switch(num.unit){
            case "percent" -> value + "%";
            //a limit that can be taken off altogether
            case "cap" -> value == 0 ? Core.bundle.get("expansion.nolimit") : value + "";
            case "" -> value + "";
            default -> Core.bundle.format("expansion." + num.unit, value);
        };
    }

    /** A line of text over a group of settings. */
    static class Heading extends mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable.Setting{
        Heading(String group){
            //no name of its own, so that putting the settings back to how they came passes it over
            super(null);
            title = Core.bundle.get("expansion.group." + group);
        }

        @Override
        public void add(mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable table){
            table.add("[accent]" + title).left().padTop(14f).padBottom(4f);
            table.row();
        }
    }

    /** What the enemy has in the bank, and what it is saving up for. */
    static String summary(){
        StringBuilder out = new StringBuilder(Core.bundle.format("expansion.stock", brain.stock.cap()));
        int shown = 0;
        for(Item item : content.items()){
            int amount = brain.stock.have(item), income = brain.stock.income(item);
            if(amount <= 0 && income <= 0) continue;
            out.append(shown++ % 2 == 0 ? "\n" : "  ").append(item.emoji()).append(' ').append(amount).append(" [lightgray]+").append(income).append("[]");
        }
        if(shown == 0) out.append("\n[lightgray]").append(Core.bundle.get("expansion.stock.none")).append("[]");

        Block aim = brain.aim();
        if(aim != null){
            out.append("\n[lightgray]").append(Core.bundle.format("expansion.aim", aim.emoji() + " " + aim.localizedName)).append("[]");
            for(ItemStack stack : ItemStack.mult(aim.requirements, state.rules.buildCostMultiplier)){
                int free = Math.max(brain.stock.free(stack.item), 0);
                if(free < stack.amount) out.append("\n  ").append(stack.item.emoji()).append(" [scarlet]").append(free).append("[]/").append(stack.amount);
            }
        }
        out.append("\n[lightgray]").append(Core.bundle.format("expansion.built", brain.count(Net.Kind.mine), brain.count(Net.Kind.maker), brain.count(Net.Kind.yard), brain.count(Net.Kind.post))).append("[]");
        //what it is thinking, cut short so that the panel does not cover the view
        for(String line : brain.status().split("\n")) out.append("\n[lightgray]").append(line.length() > 78 ? line.substring(0, 76) + "…" : line).append("[]");
        return out.toString();
    }
}
